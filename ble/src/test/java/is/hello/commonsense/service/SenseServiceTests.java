package is.hello.commonsense.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.shadows.ShadowApplication;

import java.util.concurrent.atomic.AtomicInteger;

import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.buruberi.util.AdvertisingDataBuilder;
import is.hello.buruberi.util.SerialQueue;
import is.hello.commonsense.CommonSenseTestCase;
import is.hello.commonsense.Mocks;
import is.hello.commonsense.bluetooth.SenseIdentifiers;
import is.hello.commonsense.bluetooth.SensePeripheral;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint.sec_type;
import is.hello.commonsense.util.Sync;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

public class SenseServiceTests extends CommonSenseTestCase {
    private SenseService service;

    //region Lifecycle

    @Before
    public void setUp() throws Exception {
        final Context context = getContext();
        final ComponentName componentName = new ComponentName(context, SenseService.class);
        this.service = new SenseService();
        service.onCreate();

        final IBinder binder = service.onBind(new Intent(context, SenseService.class));
        ShadowApplication.getInstance().setComponentNameAndServiceForBindService(componentName,
                                                                                 binder);
    }

    @After
    public void tearDown() throws Exception {
        service.onDestroy();
    }

    //endregion

    @Test
    public void getDeviceId() {
        assertThat(service.getDeviceId(), is(nullValue()));

        final SensePeripheral peripheral = mock(SensePeripheral.class);
        doReturn(Mocks.DEVICE_ID).when(peripheral).getDeviceId();
        service.sense = peripheral;

        assertThat(service.getDeviceId(), is(equalTo(Mocks.DEVICE_ID)));
    }

    @Test
    public void createSenseCriteria() {
        final AdvertisingData withoutDeviceId = new AdvertisingDataBuilder()
                .add(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                     SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT)
                .build();

        final PeripheralCriteria criteriaWithoutDeviceId = SenseService.createSenseCriteria();
        assertThat(criteriaWithoutDeviceId.matches(withoutDeviceId), is(true));


        final AdvertisingData withDeviceId = new AdvertisingDataBuilder()
                .add(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                     SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT)
                .add(AdvertisingData.TYPE_SERVICE_DATA,
                     SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + Mocks.DEVICE_ID)
                .build();

        final PeripheralCriteria criteriaWithDeviceId = SenseService.createSenseCriteria(Mocks.DEVICE_ID);
        assertThat(criteriaWithDeviceId.limit, is(equalTo(1)));
        assertThat(criteriaWithDeviceId.matches(withDeviceId), is(true));
    }

    @Test(expected = IllegalStateException.class)
    public void connectSingleDeviceOnly() {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        service.sense = spy(new SensePeripheral(device));
        doReturn(true).when(service.sense).isConnected();
        Sync.last(service.connect(device)); // should throw
    }

    @Test
    public void peripheralDisconnected() {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        service.sense = new SensePeripheral(device);

        service.queue.execute(new SerialQueue.Task() {
            @Override
            public void cancel(Throwable throwable) {
                // This will never be called.
            }

            @Override
            public void run() {
                // Do nothing. This task is just to block the queue.
            }
        });
        final AtomicInteger cancelCount = new AtomicInteger(0);
        service.queue.execute(new SerialQueue.Task() {
            @Override
            public void cancel(Throwable throwable) {
                cancelCount.incrementAndGet();
            }

            @Override
            public void run() {
                fail();
            }
        });

        final Intent disconnected = new Intent(GattPeripheral.ACTION_DISCONNECTED)
                .putExtra(GattPeripheral.EXTRA_ADDRESS, Mocks.DEVICE_ID);
        LocalBroadcastManager.getInstance(getContext())
                             .sendBroadcastSync(disconnected);

        assertThat(service.sense, is(nullValue()));
        assertThat(cancelCount.get(), is(equalTo(1)));
    }

    @Test
    public void disconnectWithNoDevice() {
        assertThat(Sync.last(service.disconnect()), is(equalTo(null)));
    }

    @Test(expected = ConnectionStateException.class)
    public void runLedAnimationRequiresDevice() {
        Sync.last(service.runLedAnimation(SenseLedAnimation.BUSY));
    }

    @Test(expected = ConnectionStateException.class)
    public void trippyLEDs() {
        Sync.last(service.trippyLEDs());
    }

    @Test(expected = ConnectionStateException.class)
    public void busyLEDs() {
        Sync.last(service.busyLEDs());
    }

    @Test(expected = ConnectionStateException.class)
    public void fadeOutLEDs() {
        Sync.last(service.fadeOutLEDs());
    }

    @Test(expected = ConnectionStateException.class)
    public void stopLEDs() {
        Sync.last(service.stopLEDs());
    }


    @Test(expected = ConnectionStateException.class)
    public void scanForWifiNetworksRequiresDevice() {
        Sync.last(service.scanForWifiNetworks(null));
    }

    @Test(expected = ConnectionStateException.class)
    public void currentWifiNetworkRequiresDevice() {
        Sync.last(service.currentWifiNetwork());
    }

    @Test(expected = ConnectionStateException.class)
    public void sendWifiCredentialsRequiresDevice() {
        Sync.last(service.sendWifiCredentials("Hello",
                                              sec_type.SL_SCAN_SEC_TYPE_OPEN,
                                              null));
    }

    @Test(expected = ConnectionStateException.class)
    public void linkAccountRequiresDevice() {
        Sync.last(service.linkAccount("token"));
    }

    @Test(expected = ConnectionStateException.class)
    public void linkPillRequiresDevice() {
        Sync.last(service.linkPill("token"));
    }

    @Test(expected = ConnectionStateException.class)
    public void pushDataRequiresDevice() {
        Sync.last(service.pushData());
    }

    @Test(expected = ConnectionStateException.class)
    public void enablePairingModeRequiresDevice() {
        Sync.last(service.enablePairingMode());
    }

    @Test(expected = ConnectionStateException.class)
    public void disablePairingModeRequiresDevice() {
        Sync.last(service.disablePairingMode());
    }

    @Test(expected = ConnectionStateException.class)
    public void factoryResetRequiresDevice() {
        Sync.last(service.factoryReset());
    }
}
