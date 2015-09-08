package is.hello.commonsense.util;

import android.content.Context;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import is.hello.commonsense.BuildConfig;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class,
        sdk = 21)
public abstract class CommonSenseTestCase {
    protected Context getContext() {
        return RuntimeEnvironment.application.getApplicationContext();
    }
}
