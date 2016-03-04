package is.hello.commonsense;

import android.support.annotation.NonNull;

import java.util.UUID;

import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattCharacteristic;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.GattService;
import is.hello.buruberi.bluetooth.stacks.util.LoggerFacade;
import is.hello.commonsense.bluetooth.SenseIdentifiers;
import rx.schedulers.Schedulers;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@SuppressWarnings("ResourceType")
public final class Mocks {
    public static final String DEVICE_ID = "CA154FFA";

    public static BluetoothStack createBluetoothStack() {
        final BluetoothStack stack = mock(BluetoothStack.class);
        doReturn(Schedulers.immediate())
                .when(stack)
                .getScheduler();
        doReturn(mock(LoggerFacade.class, CALLS_REAL_METHODS))
                .when(stack)
                .getLogger();
        return stack;
    }

    public static GattPeripheral createPeripheral(@NonNull BluetoothStack stack) {
        final GattPeripheral device = mock(GattPeripheral.class);
        doReturn(stack)
                .when(device)
                .getStack();
        doReturn(DEVICE_ID)
                .when(device)
                .getAddress();
        return device;
    }

    public static GattService createGattService() {
        final GattService service = mock(GattService.class);
        doReturn(SenseIdentifiers.SERVICE)
                .when(service)
                .getUuid();
        doReturn(GattService.TYPE_PRIMARY)
                .when(service)
                .getType();
        return service;
    }

    public static GattCharacteristic createGattCharacteristic(@NonNull GattService service,
                                                              @NonNull UUID uuid) {
        final GattCharacteristic characteristic = mock(GattCharacteristic.class);
        doReturn(service)
                .when(characteristic)
                .getService();
        doReturn(uuid)
                .when(characteristic)
                .getUuid();
        return characteristic;
    }
}
