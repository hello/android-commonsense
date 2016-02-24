package is.hello.commonsense.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.List;
import java.util.Objects;

import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.buruberi.util.Rx;
import is.hello.buruberi.util.SerialQueue;
import is.hello.commonsense.bluetooth.SenseIdentifiers;
import is.hello.commonsense.bluetooth.SensePeripheral;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.SenseNetworkStatus;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint;
import is.hello.commonsense.util.ConnectProgress;
import is.hello.commonsense.util.Functions;
import rx.Observable;

public class SenseService extends Service {
    private static final String LOG_TAG = SenseService.class.getSimpleName();

    private final SerialQueue queue = new SerialQueue();
    private @Nullable SensePeripheral sense;

    //region Service Lifecycle

    private final LocalBinder binder = new LocalBinder();

    class LocalBinder extends Binder {
        @NonNull
        SenseService getService() {
            return SenseService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        final IntentFilter intentFilter = new IntentFilter(GattPeripheral.ACTION_DISCONNECTED);
        LocalBroadcastManager.getInstance(this)
                             .registerReceiver(peripheralDisconnected, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        LocalBroadcastManager.getInstance(this)
                             .unregisterReceiver(peripheralDisconnected);

        if (sense != null && sense.isConnected()) {
            Log.w(LOG_TAG, "Service being destroyed with active connection");
        }
    }

    private final BroadcastReceiver peripheralDisconnected = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (sense != null) {
                final String intentAddress = intent.getStringExtra(GattPeripheral.EXTRA_ADDRESS);
                final String senseAddress = sense.getAddress();
                if (Objects.equals(intentAddress, senseAddress)) {
                    onPeripheralDisconnected();
                }
            }
        }
    };

    //endregion


    //region Utilities

    private static Exception createNoDeviceException() {
        return new ConnectionStateException("Not connected to Sense");
    }

    //endregion


    //region Managing Connectivity

    private void onPeripheralDisconnected() {
        this.sense = null;
        queue.cancelPending(createNoDeviceException());
    }

    public void preparePeripheralCriteria(@NonNull PeripheralCriteria criteria,
                                          @Nullable String deviceId) {
        criteria.addExactMatchPredicate(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        if (deviceId != null) {
            criteria.setLimit(1);
            criteria.addStartsWithPredicate(AdvertisingData.TYPE_SERVICE_DATA,
                                            SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + deviceId);
        }
    }

    public Observable<List<GattPeripheral>> discover(@NonNull BluetoothStack onStack,
                                                     @Nullable String deviceId) {
        final PeripheralCriteria criteria = new PeripheralCriteria();
        preparePeripheralCriteria(criteria, deviceId);
        return onStack.discoverPeripherals(criteria);
    }

    @CheckResult
    public Observable<ConnectProgress> connect(@NonNull GattPeripheral peripheral) {
        if (this.sense != null) {
            return Observable.error(new IllegalStateException("Cannot connect to multiple Senses at once."));
        }

        this.sense = new SensePeripheral(peripheral);
        if (sense.isConnected()) {
            return Observable.just(ConnectProgress.CONNECTED);
        }

        // Intentionally not serialized on #queue
        return sense.connect();
    }

    @CheckResult
    public Observable<Void> disconnect() {
        if (sense == null) {
            return Observable.just(null);
        }

        // Intentionally not serialized on #queue
        return sense.disconnect()
                    .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<Void> removeBond() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        // Intentionally not serialized on #queue
        return sense.removeBond()
                    .map(Functions.createMapperToVoid());
    }

    //endregion


    //region Commands

    @CheckResult
    public Observable<Void> runLedAnimation(@NonNull SenseLedAnimation animationType) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.runLedAnimation(animationType), queue);
    }

    @CheckResult
    public Observable<List<wifi_endpoint>> scanForWifiNetworks(@Nullable SensePeripheral.CountryCode countryCode) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.scanForWifiNetworks(countryCode), queue);
    }

    @CheckResult
    public Observable<SenseNetworkStatus> currentWifiNetwork() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.getWifiNetwork();
    }

    @CheckResult
    public Observable<SenseConnectToWiFiUpdate> sendWifiCredentials(@NonNull String ssid,
                                                                    @NonNull wifi_endpoint.sec_type securityType,
                                                                    @NonNull String password) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.connectToWiFiNetwork(ssid, securityType, password), queue);
    }

    @CheckResult
    public Observable<Void> linkAccount(@NonNull String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.linkAccount(accessToken), queue);
    }

    @CheckResult
    public Observable<String> linkPill(@NonNull String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.pairPill(accessToken), queue);
    }

    @CheckResult
    public Observable<Void> pushData() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.pushData(), queue);
    }

    @CheckResult
    public Observable<Void> putIntoPairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.putIntoPairingMode(), queue);
    }

    @CheckResult
    public Observable<Void> factoryReset() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.factoryReset(), queue);
    }

    //endregion
}
