package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

public class SensePeripheralError extends BuruberiException implements Errors.Reporting {
    public final SenseCommandProtos.ErrorType errorType;

    public SensePeripheralError(@Nullable SenseCommandProtos.ErrorType errorType, @Nullable Throwable cause) {
        super(errorType != null ? errorType.toString() : "Unknown Sense error", cause);

        this.errorType = errorType != null ? errorType : SenseCommandProtos.ErrorType.INTERNAL_OPERATION_FAILED;
    }

    public static boolean errorTypeEquals(@Nullable Throwable error, SenseCommandProtos.ErrorType errorType) {
        return (error != null && (error instanceof SensePeripheralError) &&
                ((SensePeripheralError) error).errorType == errorType);

    }

    @Nullable
    @Override
    public String getContextInfo() {
        return errorType.toString();
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage() {
        switch (errorType) {
            case DEVICE_ALREADY_PAIRED:
                return StringRef.from(R.string.error_sense_already_paired);

            case DEVICE_DATABASE_FULL:
                return StringRef.from(R.string.error_sense_device_db_full);

            case TIME_OUT:
                return StringRef.from(R.string.error_generic_bluetooth_timeout);

            case NETWORK_ERROR:
                return StringRef.from(R.string.error_network_failure);

            case WLAN_CONNECTION_ERROR:
            case NO_ENDPOINT_IN_RANGE:
                return StringRef.from(R.string.error_wifi_connection_failed);

            case FAIL_TO_OBTAIN_IP:
                return StringRef.from(R.string.error_wifi_ip_failure);

            case INTERNAL_DATA_ERROR:
            case DEVICE_NO_MEMORY:
            case INTERNAL_OPERATION_FAILED:
            case PROTOBUF_ENCODE_FAILED:
            case PROTOBUF_DECODE_FAILED:
                return StringRef.from(R.string.error_generic_sense_failure);

            case WLAN_ENDPOINT_DELETE_FAILED:
                return StringRef.from(R.string.error_factory_reset_wlan_failure);

            case INVALID_ACCOUNT_ID:
                return StringRef.from(R.string.error_invalid_account_id);

            case FORCE_DATA_PUSH_FAILED:
                return StringRef.from(R.string.error_force_push_data);

            case SERVER_CONNECTION_TIMEOUT:
                return StringRef.from(R.string.error_server_connection_timeout);

            default:
                return StringRef.from(errorType.toString());
        }
    }
}
