package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

public class SenseNotFoundError extends BuruberiException implements Errors.Reporting {
    public SenseNotFoundError() {
        super("Peripheral not found");
    }

    @Nullable
    @Override
    public String getContextInfo() {
        return null;
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage() {
        return StringRef.from(R.string.error_sense_not_found);
    }
}
