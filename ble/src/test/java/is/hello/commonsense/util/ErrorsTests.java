package is.hello.commonsense.util;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.junit.Test;

import is.hello.buruberi.bluetooth.errors.BondException;
import is.hello.buruberi.bluetooth.errors.BuruberiException;
import is.hello.commonsense.R;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

public class ErrorsTests extends CommonSenseTestCase {
    @Test
    public void getType() throws Exception {
        assertEquals("java.lang.Throwable", Errors.getType(new Throwable()));
        assertEquals("java.lang.RuntimeException", Errors.getType(new RuntimeException()));
        assertEquals("is.hello.buruberi.bluetooth.errors.BuruberiException", Errors.getType(new BuruberiException("test")));
        assertNull(Errors.getType(null));
    }

    @Test
    public void getContextInfo() throws Exception {
        assertNull(Errors.getContextInfo(new Throwable()));
        BondException error = new BondException(BondException.REASON_REMOVED);
        assertEquals("REASON_REMOVED", Errors.getContextInfo(error));
    }

    @Test
    public void getDisplayMessage() throws Exception {
        Context context = getContext();

        assertNull(Errors.getDisplayMessage(null));

        StringRef throwableMessage = Errors.getDisplayMessage(new Throwable("test"));
        assertNotNull(throwableMessage);
        assertEquals("test", throwableMessage.resolve(context));

        BondException error = new BondException(BondException.REASON_REMOTE_DEVICE_DOWN);
        StringRef errorMessage = Errors.getDisplayMessage(error);
        assertNotNull(errorMessage);
        assertEquals(context.getString(R.string.error_bluetooth_out_of_range), errorMessage.resolve(context));
    }

    @Test
    public void registry() {
        final IllegalStateException exception = new IllegalStateException();
        assertThat(Errors.getReporting(exception), is(nullValue()));

        Errors.registerReportingImplementation(IllegalStateException.class,
                                               new IllegalStateReporting());

        try {
            assertThat(Errors.getReporting(exception), is(notNullValue()));
            assertThat(Errors.getContextInfo(exception), is(equalTo("hello, world")));
            //noinspection ConstantConditions
            assertThat(Errors.getDisplayMessage(exception).resolve(getContext()),
                       is(equalTo("Something went terribly wrong!")));
        } finally {
            Errors.REPORTING_REGISTRY.remove(IllegalStateException.class);
        }
    }


    static class IllegalStateReporting implements Errors.Reporting {
        @Nullable
        @Override
        public String getContextInfo() {
            return "hello, world";
        }

        @NonNull
        @Override
        public StringRef getDisplayMessage() {
            return StringRef.from("Something went terribly wrong!");
        }
    }
}
