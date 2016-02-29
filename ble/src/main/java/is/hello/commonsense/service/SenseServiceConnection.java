package is.hello.commonsense.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

import rx.Observable;
import rx.functions.Func1;
import rx.subjects.AsyncSubject;

/**
 * Helper class to facilitate communication between a component and the {@link SenseService}.
 */
public class SenseServiceConnection implements ServiceConnection {
    private final Context context;
    private final List<Listener> listeners = new ArrayList<>();
    @VisibleForTesting @Nullable SenseService senseService;

    //region Lifecycle

    /**
     * Construct a service helper.
     * @param context   The context whose lifecycle this helper will be bound to.
     */
    public SenseServiceConnection(@NonNull Context context) {
        this.context = context;
    }

    /**
     * Binds the service helper to the {@link SenseService}.
     */
    public void create() {
        final Intent intent = new Intent(context, SenseService.class);
        context.bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds the service helper from the @{link SenseService}.
     */
    public void destroy() {
        context.unbindService(this);
    }


    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        this.senseService = ((SenseService.LocalBinder) service).getService();

        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onSenseServiceConnected(senseService);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.senseService = null;

        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onSenseServiceDisconnected();
        }
    }

    //endregion


    //region Consumers

    /**
     * Register a new consumer with the helper. The consumer will receive an immediate
     * callback if the service helper is already bound to the service.
     * @param listener  The consumer.
     */
    public void registerConsumer(@NonNull Listener listener) {
        listeners.add(listener);

        if (senseService != null) {
            listener.onSenseServiceConnected(senseService);
        }
    }

    /**
     * Unregister a consumer from the helper. Safe to call within {@link Listener} callbacks.
     * @param listener  The consumer.
     */
    public void unregisterConsumer(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Creates an {@code Observable} that will produce the
     * {@link SenseService} as soon as it becomes available.
     */
    public Observable<SenseService> senseService() {
        if (senseService != null) {
            return Observable.just(senseService);
        } else {
            final AsyncSubject<SenseService> mirror = AsyncSubject.create();
            registerConsumer(new Listener() {
                @Override
                public void onSenseServiceConnected(@NonNull SenseService service) {
                    mirror.onNext(service);
                    mirror.onCompleted();

                    unregisterConsumer(this);
                }

                @Override
                public void onSenseServiceDisconnected() {
                    // Do nothing.
                }
            });
            return mirror;
        }
    }

    /**
     * Apply a functor producing an operation {@code Observable}
     * to the {@link SenseService} this connection is bound to.
     * <p>
     * This method is more efficient than just calling {@code #flatMap(Func1)}
     * on the {@code Observable} returned by {@link #senseService()}.
     * @param <R>   The type of value produced by the returned {@code Observable}.
     * @param f     Applied immediately if the {@code SenseService} is available; otherwise applied
     *              once the {@code SenseService} is connected.
     * @return An operation observable.
     */
    public <R> Observable<R> perform(@NonNull Func1<SenseService, Observable<R>> f) {
        if (senseService != null) {
            return f.call(senseService);
        } else {
            return senseService().flatMap(f);
        }
    }

    /**
     * Indicates whether or not the {@link SenseService} is currently
     * available, and connected to a remote Sense peripheral.
     */
    public boolean isConnectedToSense() {
        return (senseService != null && senseService.isConnected());
    }

    //endregion


    /**
     * Specifies a class that is interested in communicating with the {@link SenseService}.
     */
    public interface Listener {
        /**
         * Called when the {@link SenseService} is available for use.
         * @param service   The service.
         */
        void onSenseServiceConnected(@NonNull SenseService service);

        /**
         * Called when the {@link SenseService} becomes unavailable. Any external
         * references to it should be immediately cleared.
         */
        void onSenseServiceDisconnected();
    }
}
