package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BondException;
import is.hello.buruberi.bluetooth.errors.ChangePowerStateException;
import is.hello.buruberi.bluetooth.errors.ConnectionStateException;
import is.hello.buruberi.bluetooth.errors.GattException;
import is.hello.buruberi.bluetooth.errors.LostConnectionException;
import is.hello.buruberi.bluetooth.errors.LowEnergyScanException;
import is.hello.buruberi.bluetooth.errors.OperationTimeoutException;
import is.hello.buruberi.bluetooth.errors.ServiceDiscoveryException;
import is.hello.buruberi.bluetooth.errors.UserDisabledBuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

public class BuruberiReportingProvider implements Errors.ReportingProvider {
    private static boolean registered = false;

    public static void register() {
        if (!BuruberiReportingProvider.registered) {
            final BuruberiReportingProvider provider = new BuruberiReportingProvider();
            Errors.registerReportingProvider(ConnectionStateException.class, provider);
            Errors.registerReportingProvider(LostConnectionException.class, provider);
            Errors.registerReportingProvider(UserDisabledBuruberiException.class, provider);
            Errors.registerReportingProvider(LowEnergyScanException.class, provider);
            Errors.registerReportingProvider(ChangePowerStateException.class, provider);
            Errors.registerReportingProvider(OperationTimeoutException.class, provider);
            Errors.registerReportingProvider(ServiceDiscoveryException.class, provider);
            Errors.registerReportingProvider(GattException.class, provider);
            Errors.registerReportingProvider(BondException.class, provider);

            BuruberiReportingProvider.registered = true;
        }
    }

    @Nullable
    @Override
    public String getContextInfo(@NonNull Throwable e) {
        if (e instanceof OperationTimeoutException) {
            return ((OperationTimeoutException) e).operation.toString();
        } else if (e instanceof GattException) {
            final GattException gattException = (GattException) e;
            if (gattException.operation != null) {
                return gattException.operation + ": " + GattException.statusToString(gattException.statusCode);
            } else {
                return GattException.statusToString(gattException.statusCode);
            }
        } else if (e instanceof BondException) {
            final BondException bondException = (BondException) e;
            return BondException.getReasonString(bondException.reason);
        }
        return null;
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage(@NonNull Throwable e) {
        if (e instanceof ConnectionStateException) {
            return StringRef.from(R.string.error_bluetooth_no_connection);
        } else if (e instanceof LostConnectionException) {
            return StringRef.from(R.string.error_bluetooth_connection_lost);
        } else if (e instanceof UserDisabledBuruberiException) {
            return StringRef.from(R.string.error_bluetooth_disabled);
        } else if (e instanceof LowEnergyScanException) {
            return StringRef.from(R.string.error_peripheral_scan_failure);
        } else if (e instanceof ChangePowerStateException) {
            return StringRef.from(R.string.error_bluetooth_power_change);
        } else if (e instanceof OperationTimeoutException) {
            return StringRef.from(R.string.error_generic_bluetooth_timeout);
        } else if (e instanceof ServiceDiscoveryException) {
            return StringRef.from(R.string.error_bluetooth_service_discovery_failed);
        } else if (e instanceof GattException) {
            final GattException gattException = (GattException) e;
            switch (gattException.statusCode) {
                case GattException.GATT_INTERNAL_ERROR:
                case GattException.GATT_STACK_ERROR: {
                    return StringRef.from(R.string.error_bluetooth_gatt_stack);
                }

                case GattException.GATT_CONN_TERMINATE_LOCAL_HOST: {
                    return StringRef.from(R.string.error_bluetooth_gatt_connection_lost);
                }

                case GattException.GATT_CONN_TIMEOUT: {
                    return StringRef.from(R.string.error_bluetooth_gatt_connection_timeout);
                }

                case GattException.GATT_CONN_FAIL_ESTABLISH: {
                    return StringRef.from(R.string.error_bluetooth_gatt_connection_failed);
                }

                default: {
                    return StringRef.from(R.string.error_bluetooth_gatt_failure_fmt, getContextInfo(e));
                }
            }
        } else if (e instanceof BondException) {
            final BondException bondException = (BondException) e;
            if (bondException.reason == BondException.REASON_REMOTE_DEVICE_DOWN) {
                return StringRef.from(R.string.error_bluetooth_out_of_range);
            } else {
                return StringRef.from(R.string.error_bluetooth_bonding_change_fmt, getContextInfo(e));
            }
        }

        return StringRef.from(e.getMessage());
    }
}
