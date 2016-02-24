package is.hello.commonsense.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to facilitate communication between a component and the {@link SenseService}.
 */
public class SenseServiceHelper implements ServiceConnection {
    private final Context context;
    private final List<Consumer> consumers = new ArrayList<>();
    private @Nullable SenseService senseService;

    //region Lifecycle

    /**
     * Construct a service helper.
     * @param context   The context whose lifecycle this helper will be bound to.
     */
    public SenseServiceHelper(@NonNull Context context) {
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

        for (int i = consumers.size() - 1; i >= 0; i--) {
            consumers.get(i).onSenseServiceAvailable(senseService);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.senseService = null;

        for (int i = consumers.size() - 1; i >= 0; i--) {
            consumers.get(i).onSenseServiceUnavailable();
        }
    }

    //endregion


    //region Consumers

    /**
     * Register a new consumer with the helper. The consumer will receive an immediate
     * callback if the service helper is already bound to the service.
     * @param consumer  The consumer.
     */
    public void registerConsumer(@NonNull Consumer consumer) {
        consumers.add(consumer);

        if (senseService != null) {
            consumer.onSenseServiceAvailable(senseService);
        }
    }

    /**
     * Unregister a consumer from the helper. Safe to call within {@link Consumer} callbacks.
     * @param consumer  The consumer.
     */
    public void unregisterConsumer(@NonNull Consumer consumer) {
        consumers.remove(consumer);
    }

    /**
     * Unregisters all consumers from the helper. Not safe to call within {@link Consumer} callbacks.
     */
    public void removeAllConsumers() {
        consumers.clear();
    }

    /**
     * @return The {@link SenseService} if it's currently bound; {@code null} otherwise.
     */
    @Nullable
    public SenseService getSenseService() {
        return senseService;
    }

    //endregion


    /**
     * Specifies a class that is interested in communicating with the {@link SenseService}.
     */
    public interface Consumer {
        /**
         * Called when the {@link SenseService} is available for usel
         * @param service   The service.
         */
        void onSenseServiceAvailable(@NonNull SenseService service);

        /**
         * Called when the {@link SenseService} becomes unavailable. Any external
         * references to it should be immediately cleared.
         */
        void onSenseServiceUnavailable();
    }
}
