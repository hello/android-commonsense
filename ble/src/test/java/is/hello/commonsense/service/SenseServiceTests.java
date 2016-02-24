package is.hello.commonsense.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.shadows.ShadowApplication;

import is.hello.commonsense.CommonSenseTestCase;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SenseServiceTests extends CommonSenseTestCase {
    private SenseService service;

    //region Lifecycle

    @Before
    public void setUp() throws Exception {
        final Context context = getContext();
        final ComponentName componentName = new ComponentName(context, SenseService.class);
        this.service = new SenseService();
        service.onCreate();

        final IBinder binder = service.onBind(new Intent(context, SenseService.class));
        ShadowApplication.getInstance().setComponentNameAndServiceForBindService(componentName,
                                                                                 binder);
    }

    @After
    public void tearDown() throws Exception {
        service.onDestroy();
    }

    //endregion


    static SenseServiceConnection.Listener createMockConsumer() {
        return mock(SenseServiceConnection.Listener.class);
    }

    @Test
    public void connection() {
        final SenseServiceConnection.Listener listener = createMockConsumer();
        final SenseServiceConnection helper = new SenseServiceConnection(getContext());
        helper.registerConsumer(listener);
        helper.create();

        assertThat(helper.getSenseService(), is(notNullValue()));
        //noinspection ConstantConditions
        verify(listener).onSenseServiceConnected(helper.getSenseService());

        helper.destroy();
        assertThat(helper.getSenseService(), is(nullValue()));
        verify(listener).onSenseServiceDisconnected();
    }
}
