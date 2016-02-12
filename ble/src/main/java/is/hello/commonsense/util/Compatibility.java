package is.hello.commonsense.util;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import is.hello.commonsense.R;

/**
 * Provides compatibility reports for the device CommonSense is currently running on.
 */
public class Compatibility {
    private static String[] getModelBlacklist(@NonNull Context context) {
        return context.getResources().getStringArray(R.array.model_blacklist);
    }

    @VisibleForTesting
    static boolean isModelBlacklisted(@NonNull String[] blacklist, @NonNull String model) {
        for (final String match : blacklist) {
            if (match.equalsIgnoreCase(model)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBluetooth(@NonNull Context context) {
        final BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return (bluetoothManager != null && bluetoothManager.getAdapter() != null);
    }

    /**
     * Checks the OS version against the library's recommended minimum version,
     * and checks if the device is on CommonSense's model blacklist.
     * @param context   The context to load the blacklist through.
     * @return A new {@link Compatibility.Report} object.
     */
    public static Report generateReport(@NonNull Context context) {
        final String[] modelBlacklist = getModelBlacklist(context);
        final boolean modelSupported = (!isModelBlacklisted(modelBlacklist, Build.MODEL) &&
                hasBluetooth(context));
        final boolean systemVersionSupported = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT);
        return new Report(modelSupported, systemVersionSupported);
    }

    public static class Report {
        private final boolean modelSupported;
        private final boolean systemVersionSupported;

        Report(boolean modelSupported,
               boolean systemVersionSupported) {
            this.modelSupported = modelSupported;
            this.systemVersionSupported = systemVersionSupported;
        }

        /**
         * Indicates whether or not the current device configuration is supported.
         */
        public boolean isSupported() {
            return isModelSupported() && isSystemVersionSupported();
        }

        /**
         * Indicates whether or not the current device model passed the
         * blacklist check, and has a usable Bluetooth Low Energy chip.
         */
        public boolean isModelSupported() {
            return modelSupported;
        }

        /**
         * Indicates whether or not the current device's OS meets the minimum requirements.
         */
        public boolean isSystemVersionSupported() {
            return systemVersionSupported;
        }
    }
}
