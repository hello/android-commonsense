package is.hello.commonsense.bluetooth;

import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import is.hello.buruberi.bluetooth.errors.BluetoothConnectionLostError;
import is.hello.buruberi.bluetooth.errors.OperationTimeoutError;
import is.hello.buruberi.bluetooth.errors.PeripheralBusyError;
import is.hello.buruberi.bluetooth.errors.PeripheralNotFoundError;
import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.OperationTimeout;
import is.hello.buruberi.bluetooth.stacks.PeripheralService;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.Bytes;
import is.hello.buruberi.bluetooth.stacks.util.LoggerFacade;
import is.hello.buruberi.bluetooth.stacks.util.Operation;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.commonsense.bluetooth.errors.SenseConnectWifiError;
import is.hello.commonsense.bluetooth.errors.SensePeripheralError;
import is.hello.commonsense.bluetooth.errors.SenseSetWifiValidationError;
import is.hello.commonsense.bluetooth.errors.SenseUnexpectedResponseError;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.SenseNetworkStatus;
import is.hello.commonsense.bluetooth.model.SensePacketHandler;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint;
import is.hello.commonsense.util.Functions;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

import static is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.MorpheusCommand;
import static is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.MorpheusCommand.CommandType;
import static is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_connection_state;

public class SensePeripheral {
    public static final String LOG_TAG = SensePeripheral.class.getSimpleName();

    //region Versions

    /**
     * The command version used by the app.
     */
    public static final int APP_VERSION = 0;

    /**
     * The command version used by the firmware on the original PVT units.
     */
    public static final int COMMAND_VERSION_PVT = 0;

    /**
     * The command version used by the firmware that
     * is able to parse WEP keys from ASCII strings.
     */
    public static final int COMMAND_VERSION_WEP_FIX = 1;

    public static enum CountryCodes{
        EU,
        JP,
        US
    }
    //endregion

    private static final long STACK_OPERATION_TIMEOUT_S = 30;
    private static final long REMOVE_BOND_TIMEOUT_S = 15;
    private static final long SIMPLE_COMMAND_TIMEOUT_S = 45;
    private static final long ANIMATION_TIMEOUT_S = 45;
    private static final long PAIR_PILL_TIMEOUT_S = 90; // Per Pang
    private static final long SET_WIFI_TIMEOUT_S = 90;
    private static final long WIFI_SCAN_TIMEOUT_S = 30;

    private final GattPeripheral gattPeripheral;
    private final LoggerFacade logger;
    @VisibleForTesting PeripheralService peripheralService;

    private final SensePacketHandler packetHandler;

    private int commandVersion = COMMAND_VERSION_PVT;


    //region Lifecycle

    public static Observable<List<SensePeripheral>> discover(@NonNull BluetoothStack bluetoothStack,
                                                             @NonNull PeripheralCriteria criteria) {
        criteria.addExactMatchPredicate(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        return bluetoothStack.discoverPeripherals(criteria).map(new Func1<List<GattPeripheral>, List<SensePeripheral>>() {
            @Override
            public List<SensePeripheral> call(List<GattPeripheral> peripherals) {
                return SensePeripheral.fromDevices(peripherals);
            }
        });
    }

    public static Observable<SensePeripheral> rediscover(@NonNull BluetoothStack bluetoothStack,
                                                         @NonNull String deviceId,
                                                         boolean includeHighPowerPreScan) {
        PeripheralCriteria criteria = new PeripheralCriteria();
        criteria.setLimit(1);
        criteria.setWantsHighPowerPreScan(includeHighPowerPreScan);
        criteria.addExactMatchPredicate(AdvertisingData.TYPE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_128_BIT);
        criteria.addStartsWithPredicate(AdvertisingData.TYPE_SERVICE_DATA,
                                        SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT + deviceId);
        return discover(bluetoothStack, criteria).flatMap(new Func1<List<SensePeripheral>, Observable<? extends SensePeripheral>>() {
            @Override
            public Observable<? extends SensePeripheral> call(List<SensePeripheral> peripherals) {
                if (peripherals.isEmpty()) {
                    return Observable.error(new PeripheralNotFoundError());
                } else {
                    return Observable.just(peripherals.get(0));
                }
            }
        });
    }

    static List<SensePeripheral> fromDevices(@NonNull List<GattPeripheral> peripherals) {
        List<SensePeripheral> mapped = new ArrayList<>();
        for (GattPeripheral gattPeripheral : peripherals) {
            mapped.add(new SensePeripheral(gattPeripheral));
        }
        return mapped;
    }

    public SensePeripheral(@NonNull GattPeripheral gattPeripheral) {
        this.logger = gattPeripheral.getStack().getLogger();
        this.gattPeripheral = gattPeripheral;

        this.packetHandler = new SensePacketHandler();
        gattPeripheral.setPacketHandler(packetHandler);
    }

    //endregion


    //region Connectivity

    /**
     * Connects to the Sense, ensures a bond is present, and performs service discovery.
     */
    public Observable<Operation> connect() {
        Observable<Operation> sequence;

        OperationTimeout timeout = createStackTimeout("Connect");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Some Lollipop devices (not all!) do not support establishing
            // bonds after connecting. This is the exact opposite of the
            // behavior in KitKat and Gingerbread, which cannot establish
            // bonds without an active connection.
            sequence = Observable.concat(
                    Observable.just(Operation.BONDING),
                    gattPeripheral.createBond().map(Functions.createMapperToValue(Operation.CONNECTING)),
                    gattPeripheral.connect(timeout).map(Functions.createMapperToValue(Operation.DISCOVERING_SERVICES)),
                    gattPeripheral.discoverService(SenseIdentifiers.SERVICE, timeout).map(new Func1<PeripheralService, Operation>() {
                        @Override
                        public Operation call(PeripheralService service) {
                            SensePeripheral.this.peripheralService = service;
                            return Operation.CONNECTED;
                        }
                    })
                                        );
        } else {
            sequence = Observable.concat(
                    Observable.just(Operation.CONNECTING),
                    gattPeripheral.connect(timeout).map(Functions.createMapperToValue(Operation.BONDING)),
                    gattPeripheral.createBond().map(Functions.createMapperToValue(Operation.DISCOVERING_SERVICES)),
                    gattPeripheral.discoverService(SenseIdentifiers.SERVICE, timeout).map(new Func1<PeripheralService, Operation>() {
                        @Override
                        public Operation call(PeripheralService service) {
                            SensePeripheral.this.peripheralService = service;
                            return Operation.CONNECTED;
                        }
                    })
                                        );
        }

        return sequence.subscribeOn(gattPeripheral.getStack().getScheduler())
                       .doOnNext(new Action1<Operation>() {
                           @Override
                           public void call(Operation s) {
                               logger.info(LOG_TAG, "is " + s);
                           }
                       })
                       .doOnError(new Action1<Throwable>() {
                           @Override
                           public void call(Throwable connectError) {
                               if (isConnected()) {
                                   logger.warn(LOG_TAG, "Disconnecting after failed connection attempt.", connectError);
                                   disconnect().subscribe(new Subscriber<SensePeripheral>() {
                                       @Override
                                       public void onCompleted() {
                                       }

                                       @Override
                                       public void onError(Throwable e) {
                                           logger.error(LOG_TAG, "Disconnected after failed connection attempt failed, ignoring.", e);
                                       }

                                       @Override
                                       public void onNext(SensePeripheral sensePeripheral) {
                                           logger.info(LOG_TAG, "Disconnected after failed connection attempt.");
                                       }
                                   });
                               }
                           }
                       });
    }

    public Observable<SensePeripheral> disconnect() {
        return gattPeripheral.disconnect()
                             .map(Functions.createMapperToValue(this))
                             .finallyDo(new Action0() {
                                 @Override
                                 public void call() {
                                     SensePeripheral.this.peripheralService = null;
                                 }
                             });
    }

    public Observable<SensePeripheral> removeBond() {
        OperationTimeout timeout = gattPeripheral.createOperationTimeout("Remove bond", REMOVE_BOND_TIMEOUT_S, TimeUnit.SECONDS);
        return gattPeripheral.removeBond(timeout)
                             .map(Functions.createMapperToValue(this));
    }

    //endregion


    //region Attributes

    public int getScannedRssi() {
        return gattPeripheral.getScanTimeRssi();
    }

    public String getAddress() {
        return gattPeripheral.getAddress();
    }

    public String getName() {
        return gattPeripheral.getName();
    }

    public boolean isConnected() {
        return (gattPeripheral.getConnectionStatus() == GattPeripheral.STATUS_CONNECTED &&
                peripheralService != null);
    }

    public int getBondStatus() {
        return gattPeripheral.getBondStatus();
    }

    public @Nullable String getDeviceId() {
        AdvertisingData advertisingData = gattPeripheral.getAdvertisingData();
        Collection<byte[]> serviceDataRecords = advertisingData.getRecordsForType(AdvertisingData.TYPE_SERVICE_DATA);
        if (serviceDataRecords != null) {
            byte[] servicePrefix = Bytes.fromString(SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT);
            for (byte[] serviceDataRecord : serviceDataRecords) {
                if (Bytes.startWith(serviceDataRecord, servicePrefix)) {
                    return Bytes.toString(serviceDataRecord, servicePrefix.length, serviceDataRecord.length);
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return '{' + getClass().getSimpleName() + ' ' + getName() + '@' + getAddress() + '}';
    }

    //endregion


    //region Internal

    private @NonNull OperationTimeout createStackTimeout(@NonNull String name) {
        return gattPeripheral.createOperationTimeout(name, STACK_OPERATION_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createSimpleCommandTimeout() {
        return gattPeripheral.createOperationTimeout("Simple Command", SIMPLE_COMMAND_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createScanWifiTimeout() {
        return gattPeripheral.createOperationTimeout("Scan Wifi", WIFI_SCAN_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createPairPillTimeout() {
        return gattPeripheral.createOperationTimeout("Pair Pill", PAIR_PILL_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createAnimationTimeout() {
        return gattPeripheral.createOperationTimeout("Animation", ANIMATION_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private boolean isBusy() {
        return packetHandler.hasResponseListener();
    }

    @VisibleForTesting
    Observable<UUID> subscribeResponse(@NonNull OperationTimeout timeout) {
        return gattPeripheral.enableNotification(peripheralService,
                                                 SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE,
                                                 SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG,
                                                 timeout);
    }

    @VisibleForTesting
    Observable<UUID> unsubscribeResponse(@NonNull OperationTimeout timeout) {
        if (isConnected()) {
            return gattPeripheral.disableNotification(peripheralService,
                                                      SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE,
                                                      SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG,
                                                      timeout);
        } else {
            return Observable.just(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        }
    }

    private <T> Observable<T> performCommand(@NonNull final MorpheusCommand command,
                                             @NonNull final OperationTimeout timeout,
                                             @NonNull final ResponseHandler<T> responseHandler) {
        return gattPeripheral.getStack().newConfiguredObservable(new Observable.OnSubscribe<T>() {
            @Override
            public void call(Subscriber<? super T> subscriber) {
                responseHandler.configure(subscriber, timeout);

                if (isBusy()) {
                    responseHandler.onError(new PeripheralBusyError());
                    return;
                }

                timeout.setTimeoutAction(new Action0() {
                    @Override
                    public void call() {
                        logger.error(GattPeripheral.LOG_TAG, "Command timed out " + command, null);

                        packetHandler.setResponseListener(null);

                        MorpheusCommand timeoutResponse = MorpheusCommand.newBuilder()
                                                                         .setVersion(commandVersion)
                                                                         .setType(CommandType.MORPHEUS_COMMAND_ERROR)
                                                                         .setError(SenseCommandProtos.ErrorType.TIME_OUT)
                                                                         .build();
                        responseHandler.onResponse(timeoutResponse);
                    }
                }, gattPeripheral.getStack().getScheduler());

                final Action1<Throwable> onError = new Action1<Throwable>() {
                    @Override
                    public void call(Throwable error) {
                        timeout.unschedule();
                        packetHandler.setResponseListener(null);

                        responseHandler.onError(error);
                    }
                };
                Observable<UUID> subscribe = subscribeResponse(createStackTimeout("Subscribe"));
                subscribe.subscribe(new Action1<UUID>() {
                    @Override
                    public void call(UUID subscribedCharacteristic) {
                        packetHandler.setResponseListener(new SensePacketHandler.ResponseListener() {
                            @Override
                            public void onDataReady(MorpheusCommand response) {
                                logger.info(GattPeripheral.LOG_TAG, "Got response to command " + command + ": " + response);
                                SensePeripheral.this.commandVersion = response.getVersion();
                                responseHandler.onResponse(response);
                            }

                            @Override
                            public void onError(final Throwable error) {
                                timeout.unschedule();

                                if (error instanceof BluetoothConnectionLostError || !isConnected()) {
                                    onError.call(error);
                                } else {
                                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                                    logger.error(GattPeripheral.LOG_TAG, "Could not complete command " + command, error);
                                    unsubscribe.subscribe(new Action1<UUID>() {
                                        @Override
                                        public void call(UUID ignored) {
                                            onError.call(error);
                                        }
                                    }, onError);
                                }
                            }
                        });

                        logger.info(GattPeripheral.LOG_TAG, "Writing command " + command);

                        final byte[] commandData = command.toByteArray();
                        Observable<Void> write = SensePeripheral.this.writeLargeCommand(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND, commandData);
                        write.subscribe(new Action1<Void>() {
                            @Override
                            public void call(Void ignored) {
                                logger.info(GattPeripheral.LOG_TAG, "Wrote command " + command);
                                timeout.schedule();
                            }
                        }, onError);
                    }
                }, onError);
            }
        });
    }

    private Observable<MorpheusCommand> performSimpleCommand(@NonNull final MorpheusCommand command,
                                                             @NonNull final OperationTimeout commandTimeout) {
        return performCommand(command, commandTimeout, new ResponseHandler<MorpheusCommand>() {
            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                timeout.unschedule();

                Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                unsubscribe.subscribe(new Action1<UUID>() {
                    @Override
                    public void call(UUID ignored) {
                        packetHandler.setResponseListener(null);

                        if (response.getType() == command.getType()) {
                            subscriber.onNext(response);
                            subscriber.onCompleted();
                        } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                            propagateResponseError(response, null);
                        } else {
                            propagateUnexpectedResponseError(command.getType(), response);
                        }
                    }
                }, this);
            }
        });
    }

    private Observable<MorpheusCommand> performDisconnectingCommand(@NonNull final MorpheusCommand command,
                                                                    @NonNull final OperationTimeout commandTimeout) {
        return performCommand(command, commandTimeout, new ResponseHandler<MorpheusCommand>() {
            @Override
            void onResponse(final @NonNull MorpheusCommand response) {
                timeout.unschedule();

                if (response.getType() == command.getType()) {
                    disconnect().subscribe(new Subscriber<SensePeripheral>() {
                        @Override
                        public void onCompleted() {
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onError(Throwable e) {
                            logger.warn(GattPeripheral.LOG_TAG, "Could not cleanly disconnect from Sense, ignoring.", e);

                            packetHandler.setResponseListener(null);

                            subscriber.onNext(response);
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onNext(SensePeripheral sensePeripheral) {
                            packetHandler.setResponseListener(null);

                            subscriber.onNext(response);
                        }
                    });
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    packetHandler.setResponseListener(null);

                    propagateResponseError(response, null);
                } else {
                    packetHandler.setResponseListener(null);

                    propagateUnexpectedResponseError(command.getType(), response);
                }
            }

            @Override
            void onError(Throwable e) {
                if (e instanceof BluetoothConnectionLostError) {
                    packetHandler.setResponseListener(null);

                    subscriber.onNext(command);
                    subscriber.onCompleted();
                } else {
                    super.onError(e);
                }
            }
        });
    }

    //endregion

    //region Operations

    @VisibleForTesting
    Observable<Void> writeLargeCommand(@NonNull final UUID commandUUID,
                                       @NonNull final byte[] commandData) {
        List<byte[]> blePackets = packetHandler.createOutgoingPackets(commandData);
        final LinkedList<byte[]> remainingPackets = new LinkedList<>(blePackets);

        return Observable.create(new Observable.OnSubscribe<Void>() {
            @Override
            public void call(final Subscriber<? super Void> subscriber) {
                Observer<Void> writeObserver = new Observer<Void>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        subscriber.onError(e);
                    }

                    @Override
                    public void onNext(Void ignored) {
                        remainingPackets.removeFirst();
                        if (remainingPackets.isEmpty()) {
                            logger.info(GattPeripheral.LOG_TAG, "Write large command " + commandUUID);

                            subscriber.onNext(null);
                            subscriber.onCompleted();
                        } else {
                            logger.info(GattPeripheral.LOG_TAG, "Writing next chunk of large command " + commandUUID);
                            gattPeripheral.writeCommand(peripheralService, commandUUID, GattPeripheral.WriteType.NO_RESPONSE, remainingPackets.getFirst(), createStackTimeout("Write Partial Command")).subscribe(this);
                        }
                    }
                };
                logger.info(GattPeripheral.LOG_TAG, "Writing first chunk of large command (" + remainingPackets.size() + " chunks) " + commandUUID);
                gattPeripheral.writeCommand(peripheralService, commandUUID, GattPeripheral.WriteType.NO_RESPONSE, remainingPackets.getFirst(), SensePeripheral.this.createStackTimeout("Write Partial Command")).subscribe(writeObserver);
            }
        });
    }

    public Observable<Void> putIntoNormalMode() {
        logger.info(GattPeripheral.LOG_TAG, "putIntoNormalMode()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_SWITCH_TO_NORMAL_MODE)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<Void> putIntoPairingMode() {
        logger.info(GattPeripheral.LOG_TAG, "putIntoPairingMode()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_SWITCH_TO_PAIRING_MODE)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();
        return performDisconnectingCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<SenseConnectToWiFiUpdate> connectToWiFiNetwork(@NonNull String ssid,
                                                                     @NonNull wifi_endpoint.sec_type securityType,
                                                                     @Nullable String password,
                                                                     @Nullable CountryCodes countryCode) {
        logger.info(GattPeripheral.LOG_TAG, "connectToWiFiNetwork(" + ssid + ")");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        if (securityType != wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_OPEN &&
                TextUtils.isEmpty(password)) {
            return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.EMPTY_PASSWORD));
        }

        int version = commandVersion;
        MorpheusCommand.Builder builder = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT)
                                                         .setVersion(version)
                                                         .setAppVersion(APP_VERSION)
                                                         .setWifiSSID(ssid)
                                                         .setSecurityType(securityType);
        if (countryCode != null){
            builder.setCountryCode(countryCode.toString());
        }

        if (version == COMMAND_VERSION_PVT && securityType == wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_WEP) {
            byte[] keyBytes = Bytes.tryFromString(password);
            if (keyBytes == null) {
                return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.MALFORMED_BYTES));
            } else if (Bytes.contains(keyBytes, (byte) 0x0)) {
                return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.CONTAINS_NUL_BYTE));
            }
            ByteString keyString = ByteString.copyFrom(keyBytes);
            builder.setWifiPasswordBytes(keyString);
        } else {
            builder.setWifiPassword(password);
        }

        final MorpheusCommand command = builder.build();
        OperationTimeout commandTimeout = gattPeripheral.createOperationTimeout("Set Wifi", SET_WIFI_TIMEOUT_S, TimeUnit.SECONDS);
        return performCommand(command, commandTimeout, new ResponseHandler<SenseConnectToWiFiUpdate>() {
            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                Action1<Throwable> onError = new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        packetHandler.setResponseListener(null);
                        subscriber.onError(e);
                    }
                };

                if (response.getType() == CommandType.MORPHEUS_COMMAND_CONNECTION_STATE) {
                    timeout.reschedule();

                    final SenseConnectToWiFiUpdate status = new SenseConnectToWiFiUpdate(response);
                    logger.info(GattPeripheral.LOG_TAG, "connection state update " + status);

                    if (status.state == wifi_connection_state.CONNECTED) {
                        timeout.unschedule();

                        Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                        unsubscribe.subscribe(new Action1<UUID>() {
                            @Override
                            public void call(UUID ignored) {
                                packetHandler.setResponseListener(null);

                                subscriber.onNext(status);
                                subscriber.onCompleted();
                            }
                        }, onError);
                    } else if (SenseConnectWifiError.isImmediateError(status)) {
                        timeout.unschedule();

                        Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                        unsubscribe.subscribe(new Action1<UUID>() {
                                                  @Override
                                                  public void call(UUID ignored) {
                                                      onError(new SenseConnectWifiError(status, null));
                                                  }
                                              },
                                              new Action1<Throwable>() {
                                                  @Override
                                                  public void call(Throwable e) {
                                                      onError(new SenseConnectWifiError(status, e));
                                                  }
                                              });

                        packetHandler.setResponseListener(null);
                    } else {
                        subscriber.onNext(status);
                    }
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT) { //old fw
                    timeout.unschedule();

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Action1<UUID>() {
                        @Override
                        public void call(UUID ignored) {
                            packetHandler.setResponseListener(null);

                            SenseConnectToWiFiUpdate fakeStatus = new SenseConnectToWiFiUpdate(wifi_connection_state.CONNECTED, null, null);
                            subscriber.onNext(fakeStatus);
                            subscriber.onCompleted();
                        }
                    }, onError);
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    timeout.unschedule();

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Subscriber<UUID>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            propagateResponseError(response, e);
                        }

                        @Override
                        public void onNext(UUID uuid) {
                            propagateResponseError(response, null);
                        }
                    });

                    packetHandler.setResponseListener(null);
                } else {
                    timeout.unschedule();
                    packetHandler.setResponseListener(null);

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Subscriber<UUID>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            propagateUnexpectedResponseError(command.getType(), response);
                        }

                        @Override
                        public void onNext(UUID uuid) {
                            propagateUnexpectedResponseError(command.getType(), response);
                        }
                    });
                }
            }
        });

    }

    public Observable<SenseNetworkStatus> getWifiNetwork() {
        logger.info(GattPeripheral.LOG_TAG, "getWifiNetwork()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_GET_WIFI_ENDPOINT)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();

        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(new Func1<MorpheusCommand, SenseNetworkStatus>() {
                    @Override
                    public SenseNetworkStatus call(MorpheusCommand response) {
                        return new SenseNetworkStatus(response.getWifiSSID(), response.getWifiConnectionState());
                    }
                });
    }

    public Observable<String> pairPill(final String accountToken) {
        logger.info(GattPeripheral.LOG_TAG, "pairPill(" + accountToken + ")");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_PAIR_PILL)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .setAccountId(accountToken)
                                                         .build();
        return performSimpleCommand(morpheusCommand, createPairPillTimeout())
                .map(new Func1<MorpheusCommand, String>() {
                    @Override
                    public String call(MorpheusCommand response) {
                        return response.getDeviceId();
                    }
                });
    }

    public Observable<Void> linkAccount(final String accountToken) {
        logger.info(GattPeripheral.LOG_TAG, "linkAccount(" + accountToken + ")");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .setAccountId(accountToken)
                                                         .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<Void> factoryReset() {
        logger.info(GattPeripheral.LOG_TAG, "factoryReset()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_FACTORY_RESET)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();
        return performDisconnectingCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<Void> pushData() {
        logger.info(GattPeripheral.LOG_TAG, "pushData()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(CommandType.MORPHEUS_COMMAND_PUSH_DATA_AFTER_SET_TIMEZONE)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<Void> runLedAnimation(@NonNull SenseLedAnimation animationType) {
        logger.info(GattPeripheral.LOG_TAG, "runLedAnimation(" + animationType + ")");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        MorpheusCommand morpheusCommand = MorpheusCommand.newBuilder()
                                                         .setType(animationType.commandType)
                                                         .setVersion(commandVersion)
                                                         .setAppVersion(APP_VERSION)
                                                         .build();
        return performSimpleCommand(morpheusCommand, createAnimationTimeout())
                .map(Functions.createMapperToVoid());
    }

    public Observable<List<wifi_endpoint>> scanForWifiNetworks() {
        logger.info(GattPeripheral.LOG_TAG, "scanForWifiNetworks()");

        if (isBusy()) {
            return Observable.error(new PeripheralBusyError());
        }

        final MorpheusCommand command = MorpheusCommand.newBuilder()
                                                       .setType(CommandType.MORPHEUS_COMMAND_START_WIFISCAN)
                                                       .setVersion(commandVersion)
                                                       .setAppVersion(APP_VERSION)
                                                       .build();
        return performCommand(command, createScanWifiTimeout(), new ResponseHandler<List<wifi_endpoint>>() {
            final List<wifi_endpoint> endpoints = new ArrayList<>();

            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                if (response.getType() == CommandType.MORPHEUS_COMMAND_START_WIFISCAN) {
                    timeout.reschedule();

                    if (response.getWifiScanResultCount() == 1) {
                        endpoints.add(response.getWifiScanResult(0));
                    }
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_STOP_WIFISCAN) {
                    timeout.unschedule();

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Action1<UUID>() {
                        @Override
                        public void call(UUID ignored) {
                            packetHandler.setResponseListener(null);

                            subscriber.onNext(endpoints);
                            subscriber.onCompleted();
                        }
                    }, this);
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    timeout.unschedule();

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Subscriber<UUID>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            propagateResponseError(response, e);
                        }

                        @Override
                        public void onNext(UUID uuid) {
                            propagateResponseError(response, null);
                        }
                    });
                } else {
                    timeout.unschedule();

                    packetHandler.setResponseListener(null);

                    Observable<UUID> unsubscribe = unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Subscriber<UUID>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            propagateUnexpectedResponseError(command.getType(), response);
                        }

                        @Override
                        public void onNext(UUID uuid) {
                            propagateUnexpectedResponseError(command.getType(), response);
                        }
                    });
                }
            }
        });
    }

    //endregion
    private abstract class ResponseHandler<T> implements Action1<Throwable> {
        Subscriber<? super T> subscriber;
        OperationTimeout timeout;

        void configure(@NonNull Subscriber<? super T> subscriber,
                       @NonNull OperationTimeout timeout) {
            this.subscriber = subscriber;
            this.timeout = timeout;
        }

        abstract void onResponse(@NonNull MorpheusCommand response);

        void propagateResponseError(@NonNull MorpheusCommand response, @Nullable Throwable nestedCause) {
            if (response.getError() == SenseCommandProtos.ErrorType.TIME_OUT) {
                subscriber.onError(new OperationTimeoutError(OperationTimeoutError.Operation.COMMAND_RESPONSE));
            } else {
                subscriber.onError(new SensePeripheralError(response.getError(), nestedCause));
            }
        }

        void propagateUnexpectedResponseError(@NonNull CommandType expected, @NonNull MorpheusCommand response) {
            subscriber.onError(new SenseUnexpectedResponseError(expected, response.getType()));
        }

        void onError(Throwable e) {
            packetHandler.setResponseListener(null);
            subscriber.onError(e);
        }

        @Override
        public void call(Throwable throwable) {
            onError(throwable);
        }
    }
}
