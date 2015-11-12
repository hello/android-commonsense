package is.hello.commonsense.bluetooth.errors;

import android.support.annotation.NonNull;

import is.hello.buruberi.bluetooth.errors.BuruberiException;

public class SenseProtobufError extends BuruberiException {
    public final Reason reason;

    public SenseProtobufError(@NonNull Reason reason) {
        super(reason.toString());
        this.reason = reason;
    }

    public enum Reason {
        DATA_LOST_OR_OUT_OF_ORDER("Protobuf data lost or out of order"),
        INVALID_PROTOBUF("Invalid protobuf data");

        private final String description;

        @Override
        public String toString() {
            return description;
        }

        Reason(@NonNull String description) {
            this.description = description;
        }
    }
}
