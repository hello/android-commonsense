package is.hello.commonsense.bluetooth;

import android.os.Build;
import android.support.annotation.CheckResult;
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

import is.hello.buruberi.bluetooth.errors.LostConnectionException;
import is.hello.buruberi.bluetooth.errors.OperationTimeoutException;
import is.hello.buruberi.bluetooth.stacks.BluetoothStack;
import is.hello.buruberi.bluetooth.stacks.GattCharacteristic;
import is.hello.buruberi.bluetooth.stacks.GattPeripheral;
import is.hello.buruberi.bluetooth.stacks.GattService;
import is.hello.buruberi.bluetooth.stacks.OperationTimeout;
import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.Bytes;
import is.hello.buruberi.bluetooth.stacks.util.LoggerFacade;
import is.hello.buruberi.bluetooth.stacks.util.PeripheralCriteria;
import is.hello.buruberi.util.Operation;
import is.hello.commonsense.bluetooth.errors.BuruberiReportingProvider;
import is.hello.commonsense.bluetooth.errors.SenseBusyError;
import is.hello.commonsense.bluetooth.errors.SenseConnectWifiError;
import is.hello.commonsense.bluetooth.errors.SenseNotFoundError;
import is.hello.commonsense.bluetooth.errors.SensePeripheralError;
import is.hello.commonsense.bluetooth.errors.SenseSetWifiValidationError;
import is.hello.commonsense.bluetooth.errors.SenseUnexpectedResponseError;
import is.hello.commonsense.bluetooth.model.ProtobufPacketListener;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.SenseLedAnimation;
import is.hello.commonsense.bluetooth.model.SenseNetworkStatus;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_endpoint;
import is.hello.commonsense.util.ConnectProgress;
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

    static {
        BuruberiReportingProvider.register();
    }

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

    /**
     * Country codes supported by Sense during Wi-Fi scans. Literal enum
     * value corresponds to expected string value in Sense firmware.
     */
    public enum CountryCode {
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

    /**
     * Sense 1.5 returns a HashMap called {@link AdvertisingData#records}. At the time of writing
     * this on 10/24/16, the size of records is 6. Each value is a List of bytes aka List<byte[]>.
     *
     * Sense 1.0's record size is only 5. Because we're unsure if these sizes will ever change
     * we need to look at the individual bytes to determine if the Sense is 1.5 or not.
     *
     * Sense 1.5 will contain a List of bytes where the first 3 elements are
     * [0]: 0xEA hex or -22 decimal.
     * [1]: 0x3 hex or 3 decimal.
     * [2]: 0x22 hex or 34 decimal.
     *
     * Use the following field with {@link #BYTES_COMPANY_BLE_ID_2} and
     * {@link #BYTES_HARDWARE_BLE_ID_3} to check each index position and determine if the Sense is
     * 1.5.
     *
     * Sense 1.0 may eventually or already contain these three indexes too. But it will have a
     * different value for {@link #BYTES_HARDWARE_BLE_ID_3}
     */
    private static final int BYTES_COMPANY_BLE_ID_1= 0xEA;
    private static final int BYTES_COMPANY_BLE_ID_2= 0x3;
    private static final int BYTES_HARDWARE_BLE_ID_3 = 0x22;
    /**
     * These are the first three values of the mac address of for every 1.5 Sense.
     */
    private static final String START_OF_MAC_ADDRESS = "5c:6b:4f";

    private final GattPeripheral gattPeripheral;
    private final LoggerFacade logger;
    @VisibleForTesting GattService gattService;
    @VisibleForTesting GattCharacteristic commandCharacteristic;
    @VisibleForTesting GattCharacteristic responseCharacteristic;

    private final ProtobufPacketListener packetListener;

    private int commandVersion = COMMAND_VERSION_PVT;


    //region Lifecycle

    @CheckResult
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

    @CheckResult
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
                    return Observable.error(new SenseNotFoundError());
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

        this.packetListener = new ProtobufPacketListener();
    }

    //endregion


    //region Connectivity

    /**
     * Connects to the Sense, ensures a bond is present, and performs service discovery.
     */
    @CheckResult
    public Observable<ConnectProgress> connect() {
        final int connectFlags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Waiting for the device to become available breaks
            // connect on older Android versions. Just because.
            connectFlags = (GattPeripheral.CONNECT_FLAG_TRANSPORT_LE |
                    GattPeripheral.CONNECT_FLAG_WAIT_AVAILABLE);
        } else {
            connectFlags = GattPeripheral.CONNECT_FLAG_TRANSPORT_LE;
        }
        final OperationTimeout timeout = createStackTimeout("Connect");
        final Func1<GattService, ConnectProgress> onDiscoveredServices = new Func1<GattService, ConnectProgress>() {
            @Override
            public ConnectProgress call(GattService service) {
                SensePeripheral.this.gattService = service;
                SensePeripheral.this.commandCharacteristic =
                        gattService.getCharacteristic(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND);
                SensePeripheral.this.responseCharacteristic =
                        gattService.getCharacteristic(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
                responseCharacteristic.setPacketListener(packetListener);
                return ConnectProgress.CONNECTED;
            }
        };

        final Observable<ConnectProgress> sequence;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Some Lollipop devices (not all!) do not support establishing
            // bonds after connecting. This is the exact opposite of the
            // behavior in KitKat and Gingerbread, which cannot establish
            // bonds without an active connection.
            sequence = Observable.concat(
                    Observable.just(ConnectProgress.BONDING),
                    gattPeripheral.createBond().map(Functions.createMapperToValue(ConnectProgress.CONNECTING)),
                    gattPeripheral.connect(connectFlags, timeout).map(Functions.createMapperToValue(ConnectProgress.DISCOVERING_SERVICES)),
                    gattPeripheral.discoverService(SenseIdentifiers.SERVICE, timeout).map(onDiscoveredServices)
                                        );
        } else {
            sequence = Observable.concat(
                    Observable.just(ConnectProgress.CONNECTING),
                    gattPeripheral.connect(connectFlags, timeout).map(Functions.createMapperToValue(ConnectProgress.BONDING)),
                    gattPeripheral.createBond().map(Functions.createMapperToValue(ConnectProgress.DISCOVERING_SERVICES)),
                    gattPeripheral.discoverService(SenseIdentifiers.SERVICE, timeout).map(onDiscoveredServices)
                                        );
        }

        return sequence.subscribeOn(gattPeripheral.getStack().getScheduler())
                       .doOnNext(new Action1<ConnectProgress>() {
                           @Override
                           public void call(ConnectProgress s) {
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

    @CheckResult
    public Observable<SensePeripheral> disconnect() {
        return gattPeripheral.disconnect()
                             .map(Functions.createMapperToValue(this))
                             .finallyDo(new Action0() {
                                 @Override
                                 public void call() {
                                     SensePeripheral.this.gattService = null;
                                     SensePeripheral.this.commandCharacteristic = null;
                                     SensePeripheral.this.responseCharacteristic = null;
                                 }
                             });
    }

    @CheckResult
    public Observable<SensePeripheral> removeBond() {
        final OperationTimeout timeout = gattPeripheral.createOperationTimeout("Remove bond",
                                                                               REMOVE_BOND_TIMEOUT_S,
                                                                               TimeUnit.SECONDS);
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
                gattService != null);
    }

    public int getBondStatus() {
        return gattPeripheral.getBondStatus();
    }

    public @Nullable String getDeviceId() {
        final AdvertisingData advertisingData = gattPeripheral.getAdvertisingData();
        final Collection<byte[]> serviceDataRecords =
                advertisingData.getRecordsForType(AdvertisingData.TYPE_SERVICE_DATA);
        if (serviceDataRecords != null) {
            final byte[] servicePrefix =
                    Bytes.fromString(SenseIdentifiers.ADVERTISEMENT_SERVICE_16_BIT);
            for (final byte[] serviceDataRecord : serviceDataRecords) {
                if (Bytes.startWith(serviceDataRecord, servicePrefix)) {
                    return Bytes.toString(serviceDataRecord,
                                          servicePrefix.length,
                                          serviceDataRecord.length);
                }
            }
        }
        return null;
    }

    /**
     *  1. Confirm Sense is 1.5
     *  2. Find last three values of manufacturer data and convert to hex string for mac address.
     *
     * @return mac address or null if not sense 1.5
     */
    public String getMacAddress() {
        // Because we're not sure if the Manufacturer specific data may change or not we're going to
        // check every record type this has.
        final List<Integer> recordTypes = gattPeripheral.getAdvertisingData().copyRecordTypes();
        if (recordTypes == null || recordTypes.isEmpty()) {
            return null;
        }
        for (final Integer recordType : recordTypes) {
            // Get the list of bytes
            final List<byte[]> byteList = gattPeripheral.getAdvertisingData().getRecordsForType(recordType);
            if (byteList == null || byteList.isEmpty()) {
                continue;
            }

            for (final byte[] bytes : byteList) {

                // Make sure it has 3 index's to check.
                if (bytes == null || bytes.length < 3) {
                    continue;
                }
                // Check them
                if (bytes[0] != (byte) BYTES_COMPANY_BLE_ID_1) {
                    continue;
                }
                if (bytes[1] != (byte) BYTES_COMPANY_BLE_ID_2) {
                    continue;
                }
                if (bytes[2] != (byte) BYTES_HARDWARE_BLE_ID_3) {
                    continue;
                }

                // If we get this far it's safe to assume this is Sense 1.5
                // Now we need to read the last three bytes of the array and convert them.
                String macAddress = START_OF_MAC_ADDRESS;
                macAddress += getPrettyMacAddressForByte(bytes[bytes.length - 3]);
                macAddress += getPrettyMacAddressForByte(bytes[bytes.length - 2]);
                macAddress += getPrettyMacAddressForByte(bytes[bytes.length - 1]);
                return macAddress;

            }

        }
        return null;
    }

    /**
     * Sometimes {@link Integer#toHexString(int)} returns a large string. Jackson said to only use
     * the last two values of this string.
     *
     * @param value from Advertising data.
     * @return a string of length 3, with a colon in front of it.
     */
    private String getPrettyMacAddressForByte(byte value) {
        String hexString = Integer.toHexString(value);
        if (hexString.length() > 2) {
            hexString = hexString.substring(hexString.length() - 2, hexString.length());
        }
        return ":" + hexString;
    }

    @Override
    public String toString() {
        return '{' + getClass().getSimpleName() + ' ' + getName() + '@' + getAddress() + '}';
    }

    //endregion


    //region Internal

    private @NonNull OperationTimeout createStackTimeout(@NonNull String name) {
        return gattPeripheral.createOperationTimeout(name,
                STACK_OPERATION_TIMEOUT_S,
                TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createSimpleCommandTimeout() {
        return gattPeripheral.createOperationTimeout("Simple Command",
                SIMPLE_COMMAND_TIMEOUT_S,
                TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createScanWifiTimeout() {
        return gattPeripheral.createOperationTimeout("Scan Wifi",
                WIFI_SCAN_TIMEOUT_S,
                TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createPairPillTimeout() {
        return gattPeripheral.createOperationTimeout("Pair Pill",
                PAIR_PILL_TIMEOUT_S,
                TimeUnit.SECONDS);
    }

    private @NonNull OperationTimeout createAnimationTimeout() {
        return gattPeripheral.createOperationTimeout("Animation",
                                                     ANIMATION_TIMEOUT_S,
                                                     TimeUnit.SECONDS);
    }

    private boolean isBusy() {
        return packetListener.hasResponseListener();
    }

    @VisibleForTesting
    Observable<UUID> subscribeResponse(@NonNull OperationTimeout timeout) {
        return responseCharacteristic.enableNotification(SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG,
                                                         timeout);
    }

    @VisibleForTesting
    Observable<UUID> unsubscribeResponse(@NonNull OperationTimeout timeout) {
        if (isConnected()) {
            return responseCharacteristic.disableNotification(SenseIdentifiers.DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG,
                                                              timeout);
        } else {
            return Observable.just(SenseIdentifiers.CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE);
        }
    }

    @CheckResult
    private <T> Observable<T> performCommand(@NonNull final MorpheusCommand command,
                                             @NonNull final OperationTimeout timeout,
                                             @NonNull final ResponseHandler<T> responseHandler) {
        return gattPeripheral.getStack().newConfiguredObservable(new Observable.OnSubscribe<T>() {
            @Override
            public void call(Subscriber<? super T> subscriber) {
                responseHandler.configure(subscriber, timeout);

                if (isBusy()) {
                    responseHandler.onError(new SenseBusyError());
                    return;
                }

                timeout.setTimeoutAction(new Action0() {
                    @Override
                    public void call() {
                        logger.error(GattPeripheral.LOG_TAG, "Command timed out " + command, null);

                        packetListener.setResponseListener(null);

                        final MorpheusCommand timeoutResponse =
                                MorpheusCommand.newBuilder()
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
                        packetListener.setResponseListener(null);

                        responseHandler.onError(error);
                    }
                };
                final Observable<UUID> subscribe = subscribeResponse(createStackTimeout("Subscribe"));
                subscribe.subscribe(new Action1<UUID>() {
                    @Override
                    public void call(UUID subscribedCharacteristic) {
                        packetListener.setResponseListener(new ProtobufPacketListener.ResponseListener() {
                            @Override
                            public void onDataReady(MorpheusCommand response) {
                                logger.info(GattPeripheral.LOG_TAG, "Got response to command " + command + ": " + response);
                                SensePeripheral.this.commandVersion = response.getVersion();
                                responseHandler.onResponse(response);
                            }

                            @Override
                            public void onError(final Throwable error) {
                                timeout.unschedule();

                                if (error instanceof LostConnectionException || !isConnected()) {
                                    onError.call(error);
                                } else {
                                    final Observable<UUID> unsubscribe =
                                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
                                    logger.error(GattPeripheral.LOG_TAG,
                                                 "Could not complete command " + command,
                                                 error);
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
                        final Observable<Void> write =
                                writeLargeCommand(
                                        commandData);
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

    @CheckResult
    private Observable<MorpheusCommand> performSimpleCommand(@NonNull final MorpheusCommand command,
                                                             @NonNull final OperationTimeout commandTimeout) {
        return performCommand(command, commandTimeout, new ResponseHandler<MorpheusCommand>() {
            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                timeout.unschedule();

                final Observable<UUID> unsubscribe =
                        unsubscribeResponse(createStackTimeout("Unsubscribe"));
                unsubscribe.subscribe(new Action1<UUID>() {
                    @Override
                    public void call(UUID ignored) {
                        packetListener.setResponseListener(null);

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

    @CheckResult
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
                            logger.warn(GattPeripheral.LOG_TAG,
                                        "Could not cleanly disconnect from Sense, ignoring.", e);

                            packetListener.setResponseListener(null);

                            subscriber.onNext(response);
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onNext(SensePeripheral sensePeripheral) {
                            packetListener.setResponseListener(null);

                            subscriber.onNext(response);
                        }
                    });
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    packetListener.setResponseListener(null);

                    propagateResponseError(response, null);
                } else {
                    packetListener.setResponseListener(null);

                    propagateUnexpectedResponseError(command.getType(), response);
                }
            }

            @Override
            void onError(Throwable e) {
                if (e instanceof LostConnectionException) {
                    packetListener.setResponseListener(null);

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
    @CheckResult
    Observable<Void> writeLargeCommand(@NonNull final byte[] commandData) {
        List<byte[]> blePackets = packetListener.createOutgoingPackets(commandData);
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
                            logger.info(GattPeripheral.LOG_TAG, "Wrote large command");

                            subscriber.onNext(null);
                            subscriber.onCompleted();
                        } else {
                            logger.info(GattPeripheral.LOG_TAG,
                                        "Writing next chunk of large command");
                            commandCharacteristic.write(GattPeripheral.WriteType.NO_RESPONSE,
                                                        remainingPackets.getFirst(),
                                                        createStackTimeout("Write Partial Command"))
                                                 .subscribe(this);
                        }
                    }
                };

                logger.info(GattPeripheral.LOG_TAG,
                            "Writing first chunk of large command (" +
                                    remainingPackets.size() + " chunks)");
                commandCharacteristic.write(GattPeripheral.WriteType.NO_RESPONSE,
                                            remainingPackets.getFirst(),
                                            createStackTimeout("Write Partial Command"))
                                     .subscribe(writeObserver);
            }
        });
    }

    @CheckResult
    public Observable<Void> putIntoNormalMode() {
        logger.info(GattPeripheral.LOG_TAG, "putIntoNormalMode()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_SWITCH_TO_NORMAL_MODE)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<Void> putIntoPairingMode() {
        logger.info(GattPeripheral.LOG_TAG, "putIntoPairingMode()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_SWITCH_TO_PAIRING_MODE)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();
        return performDisconnectingCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<SenseConnectToWiFiUpdate> connectToWiFiNetwork(@NonNull String ssid,
                                                                     @NonNull wifi_endpoint.sec_type securityType,
                                                                     @Nullable String password) {
        logger.info(GattPeripheral.LOG_TAG, "connectToWiFiNetwork(" + ssid + ")");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        if (securityType != wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_OPEN &&
                TextUtils.isEmpty(password)) {
            return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.EMPTY_PASSWORD));
        }

        final int version = commandVersion;
        final MorpheusCommand.Builder builder =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT)
                               .setVersion(version)
                               .setAppVersion(APP_VERSION)
                               .setWifiSSID(ssid)
                               .setSecurityType(securityType);
        if (version == COMMAND_VERSION_PVT && securityType == wifi_endpoint.sec_type.SL_SCAN_SEC_TYPE_WEP) {
            final byte[] keyBytes = Bytes.tryFromString(password);
            if (keyBytes == null) {
                return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.MALFORMED_BYTES));
            } else if (Bytes.contains(keyBytes, (byte) 0x0)) {
                return Observable.error(new SenseSetWifiValidationError(SenseSetWifiValidationError.Reason.CONTAINS_NUL_BYTE));
            }
            final ByteString keyString = ByteString.copyFrom(keyBytes);
            builder.setWifiPasswordBytes(keyString);
        } else {
            builder.setWifiPassword(password);
        }

        final MorpheusCommand command = builder.build();
        final OperationTimeout commandTimeout =
                gattPeripheral.createOperationTimeout("Set Wifi",
                                                      SET_WIFI_TIMEOUT_S,
                                                      TimeUnit.SECONDS);
        return performCommand(command, commandTimeout, new ResponseHandler<SenseConnectToWiFiUpdate>() {
            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                final Action1<Throwable> onError = new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        packetListener.setResponseListener(null);
                        subscriber.onError(e);
                    }
                };

                if (response.getType() == CommandType.MORPHEUS_COMMAND_CONNECTION_STATE) {
                    timeout.reschedule();

                    final SenseConnectToWiFiUpdate status = new SenseConnectToWiFiUpdate(response);
                    logger.info(GattPeripheral.LOG_TAG, "connection state update " + status);

                    if (status.state == wifi_connection_state.CONNECTED) {
                        timeout.unschedule();

                        final Observable<UUID> unsubscribe =
                                unsubscribeResponse(createStackTimeout("Unsubscribe"));
                        unsubscribe.subscribe(new Action1<UUID>() {
                            @Override
                            public void call(UUID ignored) {
                                packetListener.setResponseListener(null);

                                subscriber.onNext(status);
                                subscriber.onCompleted();
                            }
                        }, onError);
                    } else if (SenseConnectWifiError.isImmediateError(status)) {
                        timeout.unschedule();

                        final Observable<UUID> unsubscribe =
                                unsubscribeResponse(createStackTimeout("Unsubscribe"));
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

                        packetListener.setResponseListener(null);
                    } else {
                        subscriber.onNext(status);
                    }
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_SET_WIFI_ENDPOINT) { //old fw
                    timeout.unschedule();

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Action1<UUID>() {
                        @Override
                        public void call(UUID ignored) {
                            packetListener.setResponseListener(null);

                            final SenseConnectToWiFiUpdate fakeStatus =
                                    new SenseConnectToWiFiUpdate(wifi_connection_state.CONNECTED,
                                                                 null, null);
                            subscriber.onNext(fakeStatus);
                            subscriber.onCompleted();
                        }
                    }, onError);
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    timeout.unschedule();

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
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

                    packetListener.setResponseListener(null);
                } else {
                    timeout.unschedule();
                    packetListener.setResponseListener(null);

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
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

    @CheckResult
    public Observable<SenseNetworkStatus> getWifiNetwork() {
        logger.info(GattPeripheral.LOG_TAG, "getWifiNetwork()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_GET_WIFI_ENDPOINT)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();

        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(new Func1<MorpheusCommand, SenseNetworkStatus>() {
                    @Override
                    public SenseNetworkStatus call(MorpheusCommand response) {
                        return new SenseNetworkStatus(response.getWifiSSID(),
                                                      response.getWifiConnectionState());
                    }
                });
    }

    @CheckResult
    public Observable<String> pairPill(final String accountToken) {
        logger.info(GattPeripheral.LOG_TAG, "pairPill(" + accountToken + ")");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
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

    @CheckResult
    public Observable<Void> linkAccount(final String accountToken) {
        logger.info(GattPeripheral.LOG_TAG, "linkAccount(" + accountToken + ")");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .setAccountId(accountToken)
                               .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<Void> factoryReset() {
        logger.info(GattPeripheral.LOG_TAG, "factoryReset()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_FACTORY_RESET)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();
        return performDisconnectingCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<Void> pushData() {
        logger.info(GattPeripheral.LOG_TAG, "pushData()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_PUSH_DATA_AFTER_SET_TIMEZONE)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();
        return performSimpleCommand(morpheusCommand, createSimpleCommandTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<Void> runLedAnimation(@NonNull SenseLedAnimation animationType) {
        logger.info(GattPeripheral.LOG_TAG, "runLedAnimation(" + animationType + ")");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand morpheusCommand =
                MorpheusCommand.newBuilder()
                               .setType(animationType.commandType)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION)
                               .build();
        return performSimpleCommand(morpheusCommand, createAnimationTimeout())
                .map(Functions.createMapperToVoid());
    }

    @CheckResult
    public Observable<List<wifi_endpoint>> scanForWifiNetworks(@Nullable CountryCode countryCode) {
        logger.info(GattPeripheral.LOG_TAG, "scanForWifiNetworks()");

        if (isBusy()) {
            return Observable.error(new SenseBusyError());
        }

        final MorpheusCommand.Builder builder =
                MorpheusCommand.newBuilder()
                               .setType(CommandType.MORPHEUS_COMMAND_START_WIFISCAN)
                               .setVersion(commandVersion)
                               .setAppVersion(APP_VERSION);
        if (countryCode != null){
            builder.setCountryCode(countryCode.toString());
        }

        final MorpheusCommand command = builder.build();

        return performCommand(command, createScanWifiTimeout(), new ResponseHandler<List<wifi_endpoint>>() {
            final List<wifi_endpoint> endpoints = new ArrayList<>();

            @Override
            void onResponse(@NonNull final MorpheusCommand response) {
                if (response.getType() == CommandType.MORPHEUS_COMMAND_START_WIFISCAN) {
                    timeout.reschedule();

                    if (response.getWifiScanResultCount() == 1
                            && response.getWifiScanResult(0).hasSsid()
                            && !response.getWifiScanResult(0).getSsid().isEmpty()) {
                        endpoints.add(response.getWifiScanResult(0));
                    }
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_STOP_WIFISCAN) {
                    timeout.unschedule();

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
                    unsubscribe.subscribe(new Action1<UUID>() {
                        @Override
                        public void call(UUID ignored) {
                            packetListener.setResponseListener(null);

                            subscriber.onNext(endpoints);
                            subscriber.onCompleted();
                        }
                    }, this);
                } else if (response.getType() == CommandType.MORPHEUS_COMMAND_ERROR) {
                    timeout.unschedule();

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
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

                    packetListener.setResponseListener(null);

                    final Observable<UUID> unsubscribe =
                            unsubscribeResponse(createStackTimeout("Unsubscribe"));
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
                subscriber.onError(new OperationTimeoutException(Operation.COMMAND_RESPONSE));
            } else {
                subscriber.onError(new SensePeripheralError(response.getError(), nestedCause));
            }
        }

        void propagateUnexpectedResponseError(@NonNull CommandType expected, @NonNull MorpheusCommand response) {
            subscriber.onError(new SenseUnexpectedResponseError(expected, response.getType()));
        }

        void onError(Throwable e) {
            packetListener.setResponseListener(null);
            subscriber.onError(e);
        }

        @Override
        public void call(Throwable throwable) {
            onError(throwable);
        }
    }
}
