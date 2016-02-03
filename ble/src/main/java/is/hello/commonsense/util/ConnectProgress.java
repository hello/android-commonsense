package is.hello.commonsense.util;

import is.hello.commonsense.bluetooth.SensePeripheral;

/**
 * Describes the difference steps taken by the {@link SensePeripheral#connect()} method.
 */
public enum ConnectProgress {
    /**
     * A remote connection is being established with Sense.
     */
    CONNECTING,

    /**
     * The services on Sense are being discovered.
     */
    DISCOVERING_SERVICES,

    /**
     * The phone is establishing a bond with the remote Sense.
     */
    BONDING,

    /**
     * The connection to Sense has been successfully established.
     */
    CONNECTED
}
