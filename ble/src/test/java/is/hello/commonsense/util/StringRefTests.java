package is.hello.commonsense.util;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.support.annotation.NonNull;

import org.junit.Test;

import is.hello.commonsense.CommonSenseTestCase;
import is.hello.commonsense.R;

import static junit.framework.Assert.assertEquals;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class StringRefTests extends CommonSenseTestCase {
    @Test
    public void resolveSimpleRes() throws Exception {
        final Context context = getContext();

        final StringRef simpleMessage = StringRef.from(R.string.tests_string_ref_simple);
        assertEquals("Buruberi Tests", simpleMessage.resolve(context));
    }

    @Test
    public void resolveFormatRes() throws Exception {
        final Context context = getContext();

        final StringRef formatMessage = StringRef.from(R.string.tests_string_ref_fmt, 4, 2);
        assertEquals("4 2", formatMessage.resolve(context));
    }

    @Test
    public void resolveString() throws Exception {
        final Context context = getContext();

        final StringRef stringMessage = StringRef.from("hello, world");
        assertEquals("hello, world", stringMessage.resolve(context));
    }

    private static StringRef doParcelRoundTrip(@NonNull StringRef stringRef) {
        final Bundle outBundle = new Bundle();
        outBundle.putParcelable("stringRef", stringRef);

        final Parcel outParcel = Parcel.obtain();
        outBundle.writeToParcel(outParcel, 0);
        final byte[] parcelBytes = outParcel.marshall();
        outParcel.recycle();

        final Parcel inParcel = Parcel.obtain();
        inParcel.unmarshall(parcelBytes, 0, parcelBytes.length);

        final Bundle inBundle = new Bundle();
        inBundle.readFromParcel(inParcel);
        inParcel.recycle();

        return inBundle.getParcelable("stringRef");
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void parcelingWithString() throws Exception {
        final Context context = getContext();
        final StringRef stringMessage = StringRef.from("hello, world");
        final StringRef deserializedMessage = doParcelRoundTrip(stringMessage);
        assertThat(deserializedMessage.resolve(context), is(equalTo("hello, world")));
    }

    @Test
    public void parcelingWithResource() throws Exception {
        final Context context = getContext();
        final StringRef resourceMessage = StringRef.from(R.string.tests_string_ref_simple);
        final StringRef deserializedMessage = doParcelRoundTrip(resourceMessage);
        assertThat(deserializedMessage.resolve(context), is(equalTo("Buruberi Tests")));
    }

    @Test
    public void parcelingWithFormat() throws Exception {
        final Context context = getContext();
        final StringRef formatMessage = StringRef.from(R.string.tests_string_ref_fmt, 4, 2);
        final StringRef message = doParcelRoundTrip(formatMessage);
        assertThat(message.resolve(context), is(equalTo("4 2")));
    }
}
