package is.hello.commonsense.util;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.SimpleArrayMap;

public class Errors {
    /**
     * Returns the type string for a given error object.
     */
    public static @Nullable String getType(@Nullable Throwable e) {
        if (e != null) {
            return e.getClass().getCanonicalName();
        } else {
            return null;
        }
    }

    /**
     * Returns the context of a given error object.
     *
     * @see Reporting#getContextInfo()
     */
    public static @Nullable String getContextInfo(@Nullable Throwable e) {
        if (e instanceof Reporting) {
            return ((Reporting) e).getContextInfo();
        } else if (e != null) {
            final ReportingProvider reporting = PROVIDERS.get(e.getClass());
            if (reporting != null) {
                return reporting.getContextInfo(e);
            }
        }
        return null;
    }

    /**
     * Returns the human readable message for a given error object.
     *
     * @see Reporting#getDisplayMessage()
     */
    public static @Nullable StringRef getDisplayMessage(@Nullable Throwable e) {
        if (e instanceof Reporting) {
            return ((Reporting) e).getDisplayMessage();
        } else if (e != null) {
            final ReportingProvider reporting = PROVIDERS.get(e.getClass());
            if (reporting != null) {
                return reporting.getDisplayMessage(e);
            }

            String messageString = e.getMessage();
            if (messageString != null) {
                return StringRef.from(messageString);
            }
        }

        return null;
    }

    /**
     * Describes an error with extended reporting facilities.
     */
    public interface Reporting {
        /**
         * Returns the context of an error. Meaning and form
         * depends on error, provided as a means of disambiguation.
         */
        @Nullable String getContextInfo();

        /**
         * Returns the localized message representing the
         * error's cause, and its potential resolution.
         */
        @NonNull StringRef getDisplayMessage();
    }

    /**
     * An external implementation of {@link Reporting}.
     */
    public interface ReportingProvider {
        /**
         * Returns the context of an error. Meaning and form
         * depends on error, provided as a means of disambiguation.
         */
        @Nullable String getContextInfo(@NonNull Throwable e);

        /**
         * Returns the localized message representing the
         * error's cause, and its potential resolution.
         */
        @NonNull StringRef getDisplayMessage(@NonNull Throwable e);
    }

    /**
     * Stores non-inlined implementations of reporting.
     */
    @VisibleForTesting
    static final SimpleArrayMap<Class<?>, ReportingProvider> PROVIDERS = new SimpleArrayMap<>();

    /**
     * Registers an provider of {@link Reporting} for a given class.
     * @param clazz The class to register for.
     * @param provider The provider.
     */
    public static void registerReportingProvider(@NonNull Class<?> clazz,
                                                 @NonNull ReportingProvider provider) {
        PROVIDERS.put(clazz, provider);
    }
}
