package is.hello.commonsense;

import android.content.Context;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class,
        sdk = 21)
public abstract class CommonSenseTestCase {
    protected Context getContext() {
        return RuntimeEnvironment.application.getApplicationContext();
    }
}
