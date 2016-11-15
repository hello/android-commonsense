package is.hello.commonsense.bluetooth.model;

import is.hello.commonsense.bluetooth.SensePeripheral;

/**
 * Possible HardwareVersions returned by {@link SensePeripheral#getAdvertisedHardwareVersion()}
 */

public enum SenseHardwareVersion {
    /**
     * Original Sense 1.0
     */
    SENSE,
    /**
     * Sense 1.5
     */
    SENSE_WITH_VOICE,
    /**
     * Default fallback if could not be determined
     */
    UNKNOWN
}
