package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.bluetooth.model.SenseConnectToWiFiUpdate;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.wifi_connection_state;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

public class SenseConnectWifiError extends BuruberiException implements Errors.Reporting {
    public final SenseConnectToWiFiUpdate status;

    /**
     * Returns whether or not a given connection status indicates an
     * error that cannot be recovered from by a connect retry on Sense.
     */
    public static boolean isImmediateError(@NonNull SenseConnectToWiFiUpdate status) {
        return (status.state == wifi_connection_state.SSL_FAIL ||
                status.state == wifi_connection_state.HELLO_KEY_FAIL);
    }

    public SenseConnectWifiError(@NonNull SenseConnectToWiFiUpdate status, @Nullable Throwable cause) {
        super(status.state.toString(), cause);
        this.status = status;
    }

    @Nullable
    @Override
    public String getContextInfo() {
        if (status.httpResponseCode != null) {
            return "Http Response Code: " + status.httpResponseCode;
        } else if (status.socketErrorCode != null) {
            return "Socket Error Code: " + status.socketErrorCode;
        } else {
            return null;
        }
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage() {
        switch (status.state) {
            case SSL_FAIL:
                return StringRef.from(R.string.error_wifi_ssl_failure);

            case HELLO_KEY_FAIL:
                return StringRef.from(R.string.error_wifi_hello_key_failure);

            default:
                return StringRef.from(status.state.toString());
        }
    }
}
