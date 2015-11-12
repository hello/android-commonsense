package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.util.Errors;
import is.hello.commonsense.util.StringRef;

import static is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.MorpheusCommand.CommandType;

public class SenseUnexpectedResponseError extends BuruberiException implements Errors.Reporting {
    public final CommandType expected;
    public final CommandType actual;

    public SenseUnexpectedResponseError(@NonNull SenseCommandProtos.MorpheusCommand.CommandType expected,
                                        @NonNull SenseCommandProtos.MorpheusCommand.CommandType actual) {
        super("Expected '" + expected + "', got '" + actual + "' instead");

        this.expected = expected;
        this.actual = actual;
    }

    @Nullable
    @Override
    public String getContextInfo() {
        return null;
    }

    @NonNull
    @Override
    public StringRef getDisplayMessage() {
        return StringRef.from(R.string.error_message_unexpected_response);
    }
}
