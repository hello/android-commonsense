package is.hello.commonsense.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.List;

import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.commonsense.bluetooth.SenseIdentifiers;
import is.hello.commonsense.bluetooth.SensePeripheral;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.SenseNetworkStatus;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint;
import is.hello.commonsense.util.ConnectProgress;
import is.hello.commonsense.util.Functions;
import rx.Observable;
import rx.functions.Action0;

public class SenseService extends Service {
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
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    //endregion


    //region Utilities

    private static Exception createNoDeviceException() {
        return new ConnectionStateException("Not connected to Sense");
    }

    //endregion


    //region Managing Connectivity

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

    @CheckResult
    public Observable<ConnectProgress> connect(@NonNull GattPeripheral peripheral) {
        if (this.sense != null) {
            return Observable.error(new IllegalStateException("Cannot connect to multiple Senses at once."));
        }

        this.sense = new SensePeripheral(peripheral);
        if (sense.isConnected()) {
            return Observable.just(ConnectProgress.CONNECTED);
        }

        return sense.connect();
    }

    @CheckResult
    public Observable<Void> disconnect() {
        if (sense == null) {
            return Observable.just(null);
        }

        return sense.disconnect()
                    .map(Functions.createMapperToVoid())
                    .doOnTerminate(new Action0() {
                        @CheckResult
                        @Override
                        public void call() {
                            SenseService.this.sense = null;
                        }
                    });
    }

    @CheckResult
    public Observable<Void> removeBond() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

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

        return sense.runLedAnimation(animationType);
    }

    @CheckResult
    public Observable<List<wifi_endpoint>> scanForWifiNetworks(@Nullable SensePeripheral.CountryCode countryCode) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.scanForWifiNetworks(countryCode);
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

        return sense.connectToWiFiNetwork(ssid, securityType, password);
    }

    @CheckResult
    public Observable<Void> linkAccount(@NonNull String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.linkAccount(accessToken);
    }

    @CheckResult
    public Observable<String> linkPill(@NonNull String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.pairPill(accessToken);
    }

    @CheckResult
    public Observable<Void> pushData() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.pushData();
    }

    @CheckResult
    public Observable<Void> putIntoPairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.putIntoPairingMode();
    }

    @CheckResult
    public Observable<Void> factoryReset() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.factoryReset();
    }

    //endregion
}
