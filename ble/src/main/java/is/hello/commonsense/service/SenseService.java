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
import is.hello.buruberi.bluetooth.errors.OperationTimeoutException;
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
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;

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

        final IntentFilter connectionIntentFilter = new IntentFilter();
        connectionIntentFilter.addAction(GattPeripheral.ACTION_CONNECTED);
        connectionIntentFilter.addAction(GattPeripheral.ACTION_DISCONNECTED);
        LocalBroadcastManager.getInstance(this)
                             .registerReceiver(connectionBroadcastReceiver, connectionIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        LocalBroadcastManager.getInstance(this)
                             .unregisterReceiver(connectionBroadcastReceiver);

        if (sense != null && sense.isConnected()) {
            Log.w(LOG_TAG, "Service being destroyed with active connection");
        }
    }

    //endregion


    //region Utilities

    private static Exception createNoDeviceException() {
        return new ConnectionStateException("Not connected to Sense");
    }

    private Action1<Throwable> createCleanUpHandler() {
        return new Action1<Throwable>() {
            @Override
            public void call(Throwable e) {
                // There is basically no recovering from a timeout, it's best
                // to just abandon the connection and get on with our lives.
                if (e instanceof OperationTimeoutException) {
                    Log.d(LOG_TAG, "Dropping connection after timeout");
                    disconnect().subscribe(new Subscriber<SenseService>() {
                        @Override
                        public void onCompleted() {
                            Log.d(LOG_TAG, "Connection dropped");
                        }

                        @Override
                        public void onError(Throwable e) {
                            Log.e(LOG_TAG, "Could not drop connection cleanly", e);
                        }

                        @Override
                        public void onNext(SenseService service) {
                            // Do nothing.
                        }
                    });
                }
            }
        };
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

    private final BroadcastReceiver connectionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (sense != null) {
                final String intentAddress = intent.getStringExtra(GattPeripheral.EXTRA_ADDRESS);
                final String senseAddress = sense.getAddress();
                if (Objects.equals(intentAddress, senseAddress)) {
                    final String action = intent.getAction();
                    if (action.equals(GattPeripheral.ACTION_CONNECTED)) {
                        onPeripheralConnected();
                    } else if (action.equals(GattPeripheral.ACTION_DISCONNECTED)) {
                        onPeripheralDisconnected();
                    }
                }
            }
        }
    };

    @VisibleForTesting void onPeripheralConnected() {
        setForegroundEnabled(true);
    }

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

        return serialize(sense.connect());
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
                    .map(Func.justValue(this))
                    .doOnCompleted(new Action0() {
                        @Override
                        public void call() {
                            queue.cancelPending();
                        }
                    });
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

        return serialize(sense.runLedAnimation(SenseLedAnimation.TRIPPY)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> busyLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.runLedAnimation(SenseLedAnimation.BUSY)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> fadeOutLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.runLedAnimation(SenseLedAnimation.FADE_OUT)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> stopLEDs() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.runLedAnimation(SenseLedAnimation.STOP)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<List<wifi_endpoint>> scanForWifiNetworks(@Nullable CountryCode countryCode) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.scanForWifiNetworks(countryCode)
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseNetworkStatus> currentWifiNetwork() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.getWifiNetwork()
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseConnectToWiFiUpdate> sendWifiCredentials(@NonNull String ssid,
                                                                    @NonNull wifi_endpoint.sec_type securityType,
                                                                    @Nullable String password) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.connectToWiFiNetwork(ssid, securityType, password)
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> linkAccount(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.linkAccount(accessToken)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> linkPill(String accessToken) {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.pairPill(accessToken)
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> pushData() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.pushData()
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> enablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.putIntoPairingMode()
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> disablePairingMode() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.putIntoNormalMode()
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
    }

    @CheckResult
    public Observable<SenseService> factoryReset() {
        if (sense == null) {
            return Observable.error(createNoDeviceException());
        }

        return serialize(sense.factoryReset()
                              .map(Func.justValue(this))
                              .doOnError(createCleanUpHandler()));
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
