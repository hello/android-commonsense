package is.hello.commonsense.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.shadows.ShadowApplication;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import is.hello.commonsense.CommonSenseTestCase;
import is.hello.commonsense.bluetooth.SensePeripheral;
import rx.Observable;
import rx.Observer;
import rx.functions.Func1;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class SenseServiceConnectionTests extends CommonSenseTestCase {
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


    @Test
    public void lifecycle() {
        final SenseServiceConnection.Listener listener = mock(SenseServiceConnection.Listener.class);
        final SenseServiceConnection connection = new SenseServiceConnection(getContext());
        connection.registerConsumer(listener);
        connection.create();

        assertThat(connection.senseService, is(notNullValue()));
        //noinspection ConstantConditions
        verify(listener).onSenseServiceConnected(connection.senseService);

        connection.destroy();
        assertThat(connection.senseService, is(nullValue()));
        verify(listener).onSenseServiceDisconnected();
    }

    @Test
    public void senseServiceCold() {
        final SenseServiceConnection connection = new SenseServiceConnection(getContext());
        assertThat(connection.senseService, is(nullValue()));

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<SenseService> service = new AtomicReference<>();
        connection.senseService().subscribe(new Observer<SenseService>() {
            @Override
            public void onCompleted() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
                fail();
            }

            @Override
            public void onNext(SenseService senseService) {
                service.set(senseService);
            }
        });

        connection.create();

        assertThat(completed.get(), is(true));
        assertThat(service, is(notNullValue()));
    }

    @Test
    public void senseServiceWarm() {
        final SenseServiceConnection connection = new SenseServiceConnection(getContext());
        connection.create();
        assertThat(connection.senseService, is(notNullValue()));

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<SenseService> service = new AtomicReference<>();
        connection.senseService().subscribe(new Observer<SenseService>() {
            @Override
            public void onCompleted() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
                fail();
            }

            @Override
            public void onNext(SenseService senseService) {
                service.set(senseService);
            }
        });

        assertThat(completed.get(), is(true));
        assertThat(service, is(notNullValue()));
    }

    @Test
    public void performCold() {
        final SenseServiceConnection connection = spy(new SenseServiceConnection(getContext()));
        assertThat(connection.senseService, is(nullValue()));

        final AtomicBoolean functorCalled = new AtomicBoolean(false);
        connection.perform(new Func1<SenseService, Observable<SenseService>>() {
            @Override
            public Observable<SenseService> call(SenseService service) {
                functorCalled.set(true);
                return Observable.just(service);
            }
        }).subscribe();

        connection.create();

        verify(connection).senseService();
        assertThat(functorCalled.get(), is(true));
    }

    @Test
    public void performWarm() {
        final SenseServiceConnection connection = spy(new SenseServiceConnection(getContext()));
        connection.create();
        assertThat(connection.senseService, is(notNullValue()));

        final AtomicBoolean functorCalled = new AtomicBoolean(false);
        connection.perform(new Func1<SenseService, Observable<SenseService>>() {
            @Override
            public Observable<SenseService> call(SenseService service) {
                functorCalled.set(true);
                return Observable.just(service);
            }
        }).subscribe();

        connection.create();

        verify(connection, never()).senseService();
        assertThat(functorCalled.get(), is(true));
    }

    @Test
    public void isConnectedToSense() {
        final SenseServiceConnection connection = new SenseServiceConnection(getContext());
        assertThat(connection.isConnectedToSense(), is(false));

        connection.create();
        assertThat(connection.senseService, is(notNullValue()));
        assertThat(connection.isConnectedToSense(), is(false));

        final SensePeripheral fakePeripheral = mock(SensePeripheral.class);
        doReturn(true).when(fakePeripheral).isConnected();
        //noinspection ConstantConditions
        connection.senseService.sense = fakePeripheral;

        assertThat(connection.isConnectedToSense(), is(true));

        connection.destroy();
    }
}
