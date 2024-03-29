package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

public class SenseSetWifiValidationError extends BuruberiException implements Errors.Reporting {
    public final Reason reason;

    public SenseSetWifiValidationError(@NonNull Reason reason) {
        super(reason.toString());

        this.reason = reason;
    }

    @Nullable
    @Override
    public String getContextInfo() {
        return reason.toString();
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage() {
        switch (reason) {
            case MALFORMED_BYTES: {
                return StringRef.from(R.string.error_bluetooth_malformed_wifi_password);
            }

            case CONTAINS_NUL_BYTE: {
                return StringRef.from(R.string.error_bluetooth_wep_password_nul);
            }

            case EMPTY_PASSWORD: {
                return StringRef.from(R.string.error_bluetooth_empty_wifi_password);
            }

            default: {
                return StringRef.from(reason.toString());
            }
        }
    }

    public enum Reason {
        MALFORMED_BYTES,
        CONTAINS_NUL_BYTE,
        EMPTY_PASSWORD,
    }
}
