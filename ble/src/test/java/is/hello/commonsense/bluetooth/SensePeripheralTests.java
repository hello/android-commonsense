package is.hello.commonsense.bluetooth;

import android.bluetooth.BluetoothGatt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import is.hello.buruberi.bluetooth.errors.BondException;
import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.errors.GattException;
import is.hello.buruberi.bluetooth.errors.UserDisabledBuruberiException;
import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattCharacteristic;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.GattService;
import is.hello.buruberi.bluetooth.stacks.OperationTimeout;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.buruberi.util.AdvertisingDataBuilder;
import is.hello.buruberi.util.Operation;
import is.hello.commonsense.CommonSenseTestCase;
import is.hello.commonsense.Mocks;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.util.Sync;
import rx.Observable;

import static is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.MorpheusCommand;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("ResourceType")
public class SensePeripheralTests extends CommonSenseTestCase {
    //region Discovery

    @Test
    public void discovery() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();

        final AdvertisingDataBuilder builder = new AdvertisingDataBuilder();
        builder.add(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        final AdvertisingData advertisingData = builder.build();

        final GattPeripheral device1 = Mocks.createPeripheral(stack);
        doReturn("Sense-Test").when(device1).getName();
        doReturn("ca:15:4f:fa:b7:0b").when(device1).getAddress();
        doReturn(-50).when(device1).getScanTimeRssi();
        doReturn(advertisingData).when(device1).getAdvertisingData();

        final GattPeripheral device2 = Mocks.createPeripheral(stack);
        doReturn("Sense-Test2").when(device2).getName();
        doReturn("c2:18:4e:fb:b3:0a").when(device2).getAddress();
        doReturn(-90).when(device2).getScanTimeRssi();
        doReturn(advertisingData).when(device2).getAdvertisingData();

        final List<GattPeripheral> peripheralsInRange = new ArrayList<>();
        peripheralsInRange.add(device1);
        peripheralsInRange.add(device2);
        doReturn(Observable.just(peripheralsInRange))
                .when(stack)
                .discoverPeripherals(any(PeripheralCriteria.class));

        final PeripheralCriteria peripheralCriteria = new PeripheralCriteria();
        Sync.wrap(SensePeripheral.discover(stack, peripheralCriteria))
            .assertThat(hasSize(2));
    }

    @Test
    public void rediscovery() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();

        final AdvertisingDataBuilder builder = new AdvertisingDataBuilder();
        builder.add(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS, SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        builder.add(AdvertisingData.TYPE_SERVICE_DATA, SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + Mocks.DEVICE_ID);
        final AdvertisingData advertisingData = builder.build();

        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn("Sense-Test").when(device).getName();
        doReturn("ca:15:4f:fa:b7:0b").when(device).getAddress();
        doReturn(-50).when(device).getScanTimeRssi();
        doReturn(advertisingData).when(device).getAdvertisingData();

        final List<GattPeripheral> peripheralsInRange = new ArrayList<>();
        peripheralsInRange.add(device);
        doReturn(Observable.just(peripheralsInRange))
                .when(stack)
                .discoverPeripherals(any(PeripheralCriteria.class));

        SensePeripheral peripheral = Sync.last(SensePeripheral.rediscover(stack, Mocks.DEVICE_ID, false));
        assertThat(peripheral.getName(), is(equalTo("Sense-Test")));
    }

    //endregion


    //region Attributes

    @Test
    public void isConnected() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn(GattPeripheral.STATUS_CONNECTED).when(device).getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        assertThat(peripheral.isConnected(), is(false));

        peripheral.gattService = Mocks.createGattService();
        assertThat(peripheral.isConnected(), is(true));

        doReturn(GattPeripheral.STATUS_DISCONNECTED).when(device).getConnectionStatus();
        assertThat(peripheral.isConnected(), is(false));

        doReturn(GattPeripheral.STATUS_CONNECTING).when(device).getConnectionStatus();
        assertThat(peripheral.isConnected(), is(false));

        doReturn(GattPeripheral.STATUS_DISCONNECTING).when(device).getConnectionStatus();
        assertThat(peripheral.isConnected(), is(false));
    }

    @Test
    public void getBondStatus() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        final SensePeripheral peripheral = new SensePeripheral(device);

        doReturn(GattPeripheral.BOND_NONE).when(device).getBondStatus();
        assertThat(GattPeripheral.BOND_NONE, is(equalTo(peripheral.getBondStatus())));

        doReturn(GattPeripheral.BOND_CHANGING).when(device).getBondStatus();
        assertThat(GattPeripheral.BOND_CHANGING, is(equalTo(peripheral.getBondStatus())));

        doReturn(GattPeripheral.BOND_BONDED).when(device).getBondStatus();
        assertThat(GattPeripheral.BOND_BONDED, is(equalTo(peripheral.getBondStatus())));
    }

    @Test
    public void getScannedRssi() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn(-50).when(device).getScanTimeRssi();

        final SensePeripheral peripheral = new SensePeripheral(device);
        assertThat(-50, is(equalTo(peripheral.getScannedRssi())));
    }

    @Test
    public void getAddress() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn("ca:15:4f:fa:b7:0b").when(device).getAddress();

        final SensePeripheral peripheral = new SensePeripheral(device);
        assertThat("ca:15:4f:fa:b7:0b", is(equalTo(peripheral.getAddress())));
    }

    @Test
    public void getName() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn("Sense-Test").when(device).getName();

        final SensePeripheral peripheral = new SensePeripheral(device);
        assertThat("Sense-Test", is(equalTo(peripheral.getName())));
    }

    @Test
    public void getDeviceId() throws Exception {
        AdvertisingDataBuilder builder = new AdvertisingDataBuilder();
        builder.add(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        builder.add(AdvertisingData.TYPE_SERVICE_DATA,
                    SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + Mocks.DEVICE_ID);
        AdvertisingData advertisingData = builder.build();

        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn(advertisingData)
                .when(device)
                .getAdvertisingData();

        final SensePeripheral peripheral = new SensePeripheral(device);
        assertEquals(Mocks.DEVICE_ID, peripheral.getDeviceId());
    }

    //endregion


    //region Connectivity

    @Test
    public void connectSucceeded() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        //noinspection ResourceType
        doReturn(Observable.just(device))
                .when(device)
                .connect(any(int.class), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .createBond();
        doReturn(GattPeripheral.STATUS_CONNECTED)
                .when(device)
                .getConnectionStatus();
        final GattService service = mock(GattService.class);
        doReturn(mock(GattCharacteristic.class))
                .when(service)
                .getCharacteristic(any(UUID.class));
        doReturn(Observable.just(service))
                .when(device)
                .discoverService(eq(SenseIdentifiers.SERVICE), any(OperationTimeout.class));

        final SensePeripheral peripheral = spy(new SensePeripheral(device));

        Sync.last(peripheral.connect());

        //noinspection ResourceType
        verify(device).connect(any(int.class), any(OperationTimeout.class));
        verify(device).createBond();
        verify(device).discoverService(eq(SenseIdentifiers.SERVICE), any(OperationTimeout.class));
    }

    @Test
    public void connectFailedFromConnect() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        //noinspection ResourceType
        doReturn(Observable.error(new UserDisabledBuruberiException()))
                .when(device)
                .connect(any(int.class), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .createBond();
        doReturn(Observable.just(mock(GattService.class)))
                .when(device)
                .discoverService(eq(SenseIdentifiers.SERVICE), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .disconnect();

        final SensePeripheral peripheral = new SensePeripheral(device);

        Sync.wrap(peripheral.connect())
            .assertThrows(UserDisabledBuruberiException.class);
    }

    @Test
    public void connectFailedFromCreateBond() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        //noinspection ResourceType
        doReturn(Observable.error(new UserDisabledBuruberiException()))
                .when(device)
                .connect(any(int.class), any(OperationTimeout.class));
        doReturn(Observable.error(new BondException(BondException.REASON_ANDROID_API_CHANGED)))
                .when(device)
                .createBond();
        doReturn(Observable.just(mock(GattService.class)))
                .when(device)
                .discoverService(eq(SenseIdentifiers.SERVICE), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .disconnect();

        final SensePeripheral peripheral = new SensePeripheral(device);

        Sync.wrap(peripheral.connect())
            .assertThrows(BondException.class);
    }

    @Test
    public void connectFailedFromDiscoverService() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        //noinspection ResourceType
        doReturn(Observable.error(new UserDisabledBuruberiException()))
                .when(device)
                .connect(any(int.class), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .createBond();
        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_FAILURE,
                                                    Operation.DISCOVER_SERVICES)))
                .when(device)
                .discoverService(eq(SenseIdentifiers.SERVICE), any(OperationTimeout.class));
        doReturn(Observable.just(device))
                .when(device)
                .disconnect();

        final SensePeripheral peripheral = new SensePeripheral(device);

        Sync.wrap(peripheral.connect())
            .assertThrows(GattException.class);
    }

    @Test
    public void disconnectSuccess() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn(Observable.just(device))
                .when(device)
                .disconnect();

        final SensePeripheral peripheral = new SensePeripheral(device);

        Sync.wrap(peripheral.disconnect())
            .assertThat(is(equalTo(peripheral)));
    }

    @Test
    public void disconnectFailure() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);
        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_FAILURE,
                                                    Operation.DISCONNECT)))
                .when(device)
                .disconnect();

        final SensePeripheral peripheral = new SensePeripheral(device);

        Sync.wrap(peripheral.disconnect())
            .assertThrows(GattException.class);
    }

    //endregion


    //region Subscriptions

    @Test
    public void subscribeResponseSuccess() throws Exception {
        final UUID characteristicId = SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE;
        final UUID descriptorId = SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG;

        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        doReturn(GattPeripheral.STATUS_CONNECTED)
                .when(device)
                .getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        doReturn(Observable.just(characteristicId))
                .when(peripheral.responseCharacteristic)
                .enableNotification(eq(descriptorId),
                                    any(OperationTimeout.class));

        Sync.wrap(peripheral.subscribeResponse(mock(OperationTimeout.class)))
            .assertThat(is(equalTo(characteristicId)));


        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                                                    Operation.ENABLE_NOTIFICATION)))
                .when(peripheral.responseCharacteristic)
                .enableNotification(eq(descriptorId),
                                    any(OperationTimeout.class));

        Sync.wrap(peripheral.subscribeResponse(mock(OperationTimeout.class)))
            .assertThrows(GattException.class);


        doReturn(GattPeripheral.STATUS_DISCONNECTED)
                .when(device)
                .getConnectionStatus();

        Sync.wrap(peripheral.subscribeResponse(mock(OperationTimeout.class)))
            .assertThrows(ConnectionStateException.class);
    }

    @Test
    public void subscribeResponseFailure() throws Exception {
        final UUID descriptorId = SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG;

        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        doReturn(GattPeripheral.STATUS_CONNECTED)
                .when(device)
                .getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                                                    Operation.ENABLE_NOTIFICATION)))
                .when(peripheral.responseCharacteristic)
                .enableNotification(eq(descriptorId),
                                    any(OperationTimeout.class));

        Sync.wrap(peripheral.subscribeResponse(mock(OperationTimeout.class)))
            .assertThrows(GattException.class);
    }

    @Test
    public void unsubscribeResponseSuccess() throws Exception {
        final UUID characteristicId = SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE;
        final UUID descriptorId = SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG;

        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        doReturn(GattPeripheral.STATUS_CONNECTED)
                .when(device)
                .getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        doReturn(Observable.just(characteristicId))
                .when(peripheral.responseCharacteristic)
                .disableNotification(eq(descriptorId),
                                     any(OperationTimeout.class));

        Sync.wrap(peripheral.unsubscribeResponse(mock(OperationTimeout.class)))
            .assertThat(is(equalTo(characteristicId)));


        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                                                    Operation.ENABLE_NOTIFICATION)))
                .when(peripheral.responseCharacteristic)
                .disableNotification(eq(descriptorId),
                                     any(OperationTimeout.class));

        Sync.wrap(peripheral.unsubscribeResponse(mock(OperationTimeout.class)))
            .assertThrows(GattException.class);
    }

    @Test
    public void unsubscribeResponseFailure() throws Exception {
        final UUID descriptorId = SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG;

        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        doReturn(GattPeripheral.STATUS_CONNECTED)
                .when(device)
                .getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        doReturn(Observable.error(new GattException(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                                                    Operation.ENABLE_NOTIFICATION)))
                .when(peripheral.responseCharacteristic)
                .disableNotification(eq(descriptorId),
                                     any(OperationTimeout.class));

        Sync.wrap(peripheral.unsubscribeResponse(mock(OperationTimeout.class)))
            .assertThrows(GattException.class);
    }

    @Test
    public void unsubscribeResponseNoConnection() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        doReturn(GattPeripheral.STATUS_DISCONNECTED)
                .when(device)
                .getConnectionStatus();

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();

        Sync.wrap(peripheral.unsubscribeResponse(mock(OperationTimeout.class)))
            .assertThat(is(equalTo(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE)));
    }

    //endregion


    //region Writing Commands

    @Test
    public void writeLargeCommandSuccess() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        doReturn(Observable.just(null))
                .when(peripheral.commandCharacteristic)
                .write(any(GattPeripheral.WriteType.class),
                       any(byte[].class),
                       any(OperationTimeout.class));

        MorpheusCommand command = MorpheusCommand.newBuilder()
                                                 .setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT)
                                                 .setVersion(0)
                                                 .setWifiName("Mostly Radiation")
                                                 .setWifiSSID("00:00:00:00:00:00")
                                                 .setSecurityType(SenseCommandProtos.wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_OPEN)
                                                 .build();
        Sync.last(peripheral.writeLargeCommand(
                command.toByteArray()));

        verify(peripheral.commandCharacteristic, times(3)).write(any(GattPeripheral.WriteType.class),
                                                                 any(byte[].class),
                                                                 any(OperationTimeout.class));
    }

    @Test
    public void writeLargeCommandFailure() throws Exception {
        final BluetoothStack stack = Mocks.createBluetoothStack();
        final GattPeripheral device = Mocks.createPeripheral(stack);

        final SensePeripheral peripheral = new SensePeripheral(device);
        peripheral.gattService = Mocks.createGattService();
        peripheral.commandCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                          SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
        peripheral.responseCharacteristic = Mocks.createGattCharacteristic(peripheral.gattService,
                                                                           SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);

        doReturn(Observable.error(new GattException(GattException.GATT_STACK_ERROR,
                                                    Operation.WRITE_COMMAND)))
                .when(peripheral.commandCharacteristic)
                .write(any(GattPeripheral.WriteType.class),
                       any(byte[].class),
                       any(OperationTimeout.class));

        MorpheusCommand command = MorpheusCommand.newBuilder()
                                                 .setType(MorpheusCommand.CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT)
                                                 .setVersion(0)
                                                 .setWifiName("Mostly Radiation")
                                                 .setWifiSSID("00:00:00:00:00:00")
                                                 .setSecurityType(SenseCommandProtos.wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_OPEN)
                                                 .build();
        Sync.wrap(peripheral.writeLargeCommand(
                command.toByteArray()))
            .assertThrows(GattException.class);

        verify(peripheral.commandCharacteristic, times(1)).write(any(GattPeripheral.WriteType.class),
                                                                 any(byte[].class),
                                                                 any(OperationTimeout.class));
    }

    //endregion
}
