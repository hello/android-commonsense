package is.hello.commonsense.bluetooth.model;

import android.support.annotation.Nullable;

import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;

/**
 * Encapsulates WiFi network connection status for Sense.
 */
public final class SenseNetworkStatus {
    /**
     * The SSID of the WiFi network Sense is connected to.
     * <p />
     * <code>null</code> indicates there is no connection.
     */
    public final @Nullable String ssid;

    /**
     * The last known connection state of Sense to the WiFi network.
     */
    public final @Nullable
    SenseCommandProtos.wifi_connection_state connectionState;


    public SenseNetworkStatus(@Nullable String ssid,
                              @Nullable SenseCommandProtos.wifi_connection_state connectionState) {
        this.ssid = ssid;
        this.connectionState = connectionState;
    }


    @Override
    public String toString() {
        return "SenseWifiNetwork{" +
                "ssid='" + ssid + '\'' +
                ", connectionState=" + connectionState +
                '}';
    }
}
