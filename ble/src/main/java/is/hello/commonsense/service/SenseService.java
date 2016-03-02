package is.hello.commonsense.service;

import android.app.Notification;
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
import rx.functions.Action0;

public class SenseService extends Service {
    private static final String LOG_TAG = SenseService.class.getSimpleName();

    @VisibleForTesting final SerialQueue queue = new SerialQueue();
    @VisibleForTesting @Nullable SensePeripheral sense;

    private int notificationId = 0;
    private @Nullable Notification notification;
    private int foregroundCount = 0;

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

    /**
     * Binds an {@code Observable} to the {@code SenseService}'s internal job queue. The returned
     * observable will act like observables returned by other methods in {@code SenseService} and
     * only run when all work submitted before it has completed.
     */
    public <T> Observable<T> serialize(@NonNull Observable<T> observable) {
        return Rx.serialize(observable, queue);
    }

    //endregion


    //region Foregrounding

    private void incrementForeground() {
        if (notification == null) {
            throw new IllegalStateException("Cannot call incrementForeground() before setting a notification");
        }

        this.foregroundCount++;

        if (foregroundCount == 1) {
            Log.d(LOG_TAG, "Starting foregrounding");
            startForeground(notificationId, notification);
        }
    }

    private void decrementForeground() {
        if (foregroundCount == 0) {
            Log.w(LOG_TAG, "decrementForeground() called too many times");
            return;
        }

        this.foregroundCount--;

        if (foregroundCount == 0) {
            Log.d(LOG_TAG, "Stopping foregrounding");
            stopForeground(true);
        }
    }

    /**
     * Specifies the notification to display when the {@code SenseService}
     * is connected to Sense, and has entered foreground mode.
     * <p>
     * This method should be called before a connection is created.
     *
     * @param id            The id of the notification in the notification manager. Cannot be 0.
     * @param notification  The notification to display.
     */
    public void setForegroundNotification(int id, @Nullable Notification notification) {
        if (id == 0 && notification != null) {
            throw new IllegalArgumentException("id cannot be 0");
        }

        this.notificationId = id;
        this.notification = notification;

        if (notification == null && foregroundCount > 0) {
            stopForeground(true);
            this.foregroundCount = 0;
        }
    }

    /**
     * Indicates whether or not foregrounding is currently enabled for the service.
     * This is contingent on the service having a notification bound to it.
     * @return true if the service will run in the foreground when connected to a sense; false otherwise.
     */
    public boolean isForegroundingEnabled() {
        return (notificationId != 0 && notification != null);
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
        if (isForegroundingEnabled()) {
            decrementForeground();
        }
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

        return serialize(sense.connect()).doOnCompleted(new Action0() {
            @Override
            public void call() {
                if (isForegroundingEnabled()) {
                    incrementForeground();
                }
            }
        });
    }

    @CheckResult
    public Observable<SenseService> disconnect() {
        if (sense == null) {
            return Observable.just(null);
        }

        // Intentionally not serialized on #queue so that disconnect
        // can happen as soon as possible relative to its call site.
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

        return sense.runLedAnimation(animationType)
                    .map(Func.justValue(this));
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

        return sense.scanForWifiNetworks(countryCode);
    }

    @CheckResult
    public Observable<SenseNetworkStatus> currentWifiNetwork() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.getWifiNetwork());
    }

    @CheckResult
    public Observable<SenseConnectToWiFiUpdate> sendWifiCredentials(@NonNull String ssid,
                                                                    @NonNull wifi_endpoint.sec_type securityType,
                                                                    @Nullable String password) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.connectToWiFiNetwork(ssid, securityType, password);
    }

    @CheckResult
    public Observable<SenseService> linkAccount(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.linkAccount(accessToken)
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> linkPill(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.pairPill(accessToken)
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> pushData() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.pushData()
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> enablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.putIntoPairingMode()
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> disablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.putIntoNormalMode()
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> factoryReset() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.factoryReset()
                    .map(Func.justValue(this));
    }

    //endregion
}
