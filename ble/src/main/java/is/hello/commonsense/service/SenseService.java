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
import android.support.annotation.VisibleForTesting;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.List;
import java.util.Objects;

import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.buruberi.util.Rx;
import is.hello.buruberi.util.SerialQueue;
import is.hello.commonsense.bluetooth.SenseIdentifiers;
import is.hello.commonsense.bluetooth.SensePeripheral;
import is.hello.commonsense.bluetooth.SensePeripheral.CountryCode;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.SenseNetworkStatus;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint;
import is.hello.commonsense.util.ConnectProgress;
import is.hello.commonsense.util.Func;
import rx.Observable;

public class SenseService extends Service {
    private static final String LOG_TAG = SenseService.class.getSimpleName();

    @VisibleForTesting final SerialQueue queue = new SerialQueue();
    @VisibleForTesting @Nullable SensePeripheral sense;

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

    //endregion


    //region Utilities

    private static Exception createNoDeviceException() {
        return new ConnectionStateException("Not connected to Sense");
    }

    //endregion


    //region Managing Connectivity

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

    private void onPeripheralDisconnected() {
        this.sense = null;
        queue.cancelPending(createNoDeviceException());
    }

    public static PeripheralCriteria createSenseCriteria() {
        final PeripheralCriteria criteria = new PeripheralCriteria();
        criteria.addExactMatchPredicate(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        return criteria;
    }

    public static PeripheralCriteria createSenseCriteria(@NonNull String deviceId) {
        final PeripheralCriteria criteria = createSenseCriteria();
        criteria.setLimit(1);
        criteria.addStartsWithPredicate(AdvertisingData.TYPE_SERVICE_DATA,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + deviceId);
        return criteria;
    }

    @CheckResult
    public Observable<ConnectProgress> connect(@NonNull GattPeripheral peripheral) {
        if (this.sense != null && sense.isConnected()) {
            return Observable.error(new IllegalStateException("Cannot connect to multiple Senses at once."));
        }

        this.sense = new SensePeripheral(peripheral);

        // Intentionally not serialized on #queue
        return sense.connect();
    }

    @CheckResult
    public Observable<SenseService> disconnect() {
        if (sense == null) {
            return Observable.just(null);
        }

        // Intentionally not serialized on #queue
        return sense.disconnect()
                    .map(Func.justValue(this));
    }

    public boolean isConnected() {
        return (sense != null && sense.isConnected());
    }

    @Nullable
    public String getDeviceId() {
        return sense != null ? sense.getDeviceId() : null;
    }

    //endregion


    //region Commands

    @CheckResult
    public Observable<SenseService> runLedAnimation(@NonNull SenseLedAnimation animationType) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.runLedAnimation(animationType)
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> trippyLEDs() {
        return runLedAnimation(SenseLedAnimation.TRIPPY);
    }

    @CheckResult
    public Observable<SenseService> busyLEDs() {
        return runLedAnimation(SenseLedAnimation.BUSY);
    }

    @CheckResult
    public Observable<SenseService> fadeOutLEDs() {
        return runLedAnimation(SenseLedAnimation.FADE_OUT);
    }

    @CheckResult
    public Observable<SenseService> stopLEDs() {
        return runLedAnimation(SenseLedAnimation.STOP);
    }

    @CheckResult
    public Observable<List<wifi_endpoint>> scanForWifiNetworks(@Nullable CountryCode countryCode) {
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
                                                                    @Nullable String password) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.connectToWiFiNetwork(ssid, securityType, password), queue);
    }

    @CheckResult
    public Observable<SenseService> linkAccount(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.linkAccount(accessToken)
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> linkPill(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.pairPill(accessToken)
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> pushData() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.pushData()
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> enablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.putIntoPairingMode()
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> disablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.putIntoNormalMode()
                                 .map(Func.justValue(this)), queue);
    }

    @CheckResult
    public Observable<SenseService> factoryReset() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return Rx.serialize(sense.factoryReset()
                                 .map(Func.justValue(this)), queue);
    }

    //endregion
}
