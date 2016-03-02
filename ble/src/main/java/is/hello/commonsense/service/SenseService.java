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

/**
 * Encapsulates connection and command management for communicating with a Sense over Bluetooth
 * Low Energy. The {@code SenseService} class replaces the {@link SensePeripheral} class for all
 * new code. It provides stronger guarantees around command serialization, and host process
 * lifecycle management.
 * <p>
 * {@code SenseService} is automatically exported when you include the CommonSense library through
 * maven/gradle. To communicate with {@code SenseService}, use {@link SenseServiceConnection}.
 */
public class SenseService extends Service {
    private static final String LOG_TAG = SenseService.class.getSimpleName();

    @VisibleForTesting final SerialQueue queue = new SerialQueue();
    @VisibleForTesting @Nullable SensePeripheral sense;

    private ForegroundNotificationProvider notificationProvider;
    @VisibleForTesting boolean foregroundEnabled = false;

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

    /**
     * Controls the state of foregrounding for the service. This method is idempotent.
     * @param enabled   Whether or not foregrounding is enabled.
     * @throws IllegalStateException if {@code enabled} is true, and no notification provider is set.
     * @see #setForegroundNotificationProvider(ForegroundNotificationProvider)
     */
    @VisibleForTesting void setForegroundEnabled(boolean enabled) {
        if (enabled != this.foregroundEnabled) {
            this.foregroundEnabled = enabled;

            if (enabled) {
                if (notificationProvider == null) {
                    throw new IllegalStateException("Cannot enable foregrounding without a notification provider");
                }

                Log.d(LOG_TAG, "Foregrounding enabled");
                startForeground(notificationProvider.getId(),
                                notificationProvider.getNotification());
            } else {
                Log.d(LOG_TAG, "Foregrounding disabled");
                stopForeground(true);
            }
        }
    }

    /**
     * Sets the foreground notification provider the service should use to establish foreground
     * status when it has an active connection with a remote Sense peripheral.
     *
     * @param provider The new provider. If {@code null}, foreground status will be discontinued.
     */
    public void setForegroundNotificationProvider(@Nullable ForegroundNotificationProvider provider) {
        this.notificationProvider = provider;

        if (provider == null && foregroundEnabled) {
            setForegroundEnabled(false);
        }
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

    @VisibleForTesting void onPeripheralDisconnected() {
        this.sense = null;
        queue.cancelPending(createNoDeviceException());
        setForegroundEnabled(false);
    }

    /**
     * Creates a {@code PeripheralCriteria} object configured to match zero or more Sense peripherals.
     * @return A new {@code PeripheralCriteria} object.
     */
    public static PeripheralCriteria createSenseCriteria() {
        final PeripheralCriteria criteria = new PeripheralCriteria();
        criteria.addExactMatchPredicate(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        return criteria;
    }

    /**
     * Creates a {@code PeripheralCriteria} object configured
     * to match a single Sense peripheral with a given device id.
     * @param deviceId The device id to match.
     * @return A new {@code PeripheralCriteria} object.
     */
    public static PeripheralCriteria createSenseCriteria(@NonNull String deviceId) {
        final PeripheralCriteria criteria = createSenseCriteria();
        criteria.setLimit(1);
        criteria.addStartsWithPredicate(AdvertisingData.TYPE_SERVICE_DATA,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + deviceId);
        return criteria;
    }

    /**
     * Attempts to connect to a Sense {@code GattPeripheral} object, implicitly enabling
     * service foregrounding if possible after a connection is successfully established.
     * @param peripheral The Sense to connect to.
     * @return An {@code Observable} representing the connection attempt operation.
     */
    @CheckResult
    public Observable<ConnectProgress> connect(@NonNull GattPeripheral peripheral) {
        if (this.sense != null && sense.isConnected()) {
            return Observable.error(new IllegalStateException("Cannot connect to multiple Senses at once."));
        }

        this.sense = new SensePeripheral(peripheral);

        return serialize(sense.connect()).doOnCompleted(new Action0() {
            @Override
            public void call() {
                if (notificationProvider != null) {
                    setForegroundEnabled(true);
                }
            }
        });
    }

    /**
     * Disconnects from the Sense {@code GattPeripheral} the service is connected to.
     * The operation {@code Observable} returned by this method is not serialized like the other
     * observables returned by {@link SenseService}, the disconnect is guaranteed to be issued
     * immediately upon subscription.
     * <p>
     * This method does nothing if there is no active connection.
     * @return A disconnect operation {@code Observable}.
     */
    @CheckResult
    public Observable<SenseService> disconnect() {
        if (sense == null) {
            return Observable.just(this);
        }

        // Intentionally not serialized on #queue so that disconnect
        // can happen as soon as possible relative to its call site.
        return sense.disconnect()
                    .map(Func.justValue(this));
    }

    /**
     * Indicates whether or not the service is connected to a Sense peripheral.
     * @return true if the service is connected; false otherwise.
     */
    public boolean isConnected() {
        return (sense != null && sense.isConnected());
    }

    /**
     * Extracts the Sense device id for the currently connected peripheral.
     * @return The device id for the Sense if one is connected; null otherwise.
     */
    @Nullable
    public String getDeviceId() {
        return sense != null ? sense.getDeviceId() : null;
    }

    //endregion


    //region Commands

    @CheckResult
    public Observable<SenseService> trippyLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.runLedAnimation(SenseLedAnimation.TRIPPY)
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> busyLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.runLedAnimation(SenseLedAnimation.BUSY)
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> fadeOutLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.runLedAnimation(SenseLedAnimation.FADE_OUT)
                    .map(Func.justValue(this));
    }

    @CheckResult
    public Observable<SenseService> stopLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return sense.runLedAnimation(SenseLedAnimation.STOP)
                    .map(Func.justValue(this));
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


    /**
     * Provides the notification used when the {@link SenseService} enters foreground mode.
     */
    public interface ForegroundNotificationProvider {
        /**
         * Get the id to use for the notification.
         * @return The id. Must not be {@code 0}.
         */
        int getId();

        /**
         * Get the notification to display. Should have {@code PRIORITY_MIN}, and be marked as ongoing.
         * @return The notification to display when in the service is in the foreground.
         */
        @NonNull Notification getNotification();
    }
}
