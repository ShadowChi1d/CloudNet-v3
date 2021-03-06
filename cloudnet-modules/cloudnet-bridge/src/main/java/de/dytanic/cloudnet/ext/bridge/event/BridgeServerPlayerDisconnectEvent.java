package de.dytanic.cloudnet.ext.bridge.event;

import de.dytanic.cloudnet.driver.event.events.DriverEvent;
import de.dytanic.cloudnet.ext.bridge.player.NetworkConnectionInfo;
import de.dytanic.cloudnet.ext.bridge.player.NetworkPlayerServerInfo;

public final class BridgeServerPlayerDisconnectEvent extends DriverEvent {

    private final NetworkConnectionInfo networkConnectionInfo;

    private final NetworkPlayerServerInfo networkPlayerServerInfo;

    public BridgeServerPlayerDisconnectEvent(NetworkConnectionInfo networkConnectionInfo, NetworkPlayerServerInfo networkPlayerServerInfo) {
        this.networkConnectionInfo = networkConnectionInfo;
        this.networkPlayerServerInfo = networkPlayerServerInfo;
    }

    public NetworkConnectionInfo getNetworkConnectionInfo() {
        return this.networkConnectionInfo;
    }

    public NetworkPlayerServerInfo getNetworkPlayerServerInfo() {
        return this.networkPlayerServerInfo;
    }
}