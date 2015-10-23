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
        final Reporting reporting = getReporting(e);
        if (reporting != null) {
            return reporting.getContextInfo();
        } else {
            return null;
        }
    }

    /**
     * Returns the human readable message for a given error object.
     *
     * @see Reporting#getDisplayMessage()
     */
    public static @Nullable StringRef getDisplayMessage(@Nullable Throwable e) {
        final Reporting reporting = getReporting(e);
        if (reporting != null) {
            return reporting.getDisplayMessage();
        } else if (e != null) {
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
     * Stores non-inlined implementations of reporting.
     */
    @VisibleForTesting
    static final SimpleArrayMap<Class<?>, Reporting> REPORTING_REGISTRY = new SimpleArrayMap<>();

    /**
     * Registers an implementation of {@link Reporting} for a given class.
     * @param clazz The class to register for.
     * @param implementation The implementation.
     */
    public static void registerReportingImplementation(@NonNull Class<?> clazz,
                                                       @NonNull Reporting implementation) {
        REPORTING_REGISTRY.put(clazz, implementation);
    }

    /**
     * Searches for an implementation of {@link Reporting} for a given {@code Throwable}.
     * <p>
     * First checks if the {@code Throwable} itself implements {@code Reporting},
     * then checks the reporting registry for a viable implementation.
     *
     * @param e The throwable to find a reporting implementation for.
     * @return  The reporting implementation for the throwable, if any.
     *
     * @see Errors#getContextInfo(Throwable)
     * @see Errors#getDisplayMessage(Throwable)
     */
    public static @Nullable Reporting getReporting(@Nullable Throwable e) {
        if (e == null) {
            return null;
        } else if (e instanceof Reporting) {
            return (Reporting) e;
        } else {
            return REPORTING_REGISTRY.get(e.getClass());
        }
    }
}
