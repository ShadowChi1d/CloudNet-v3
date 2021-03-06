package de.dytanic.cloudnet.ext.bridge.bungee;

import com.google.common.base.Preconditions;
import de.dytanic.cloudnet.common.logging.LogLevel;
import de.dytanic.cloudnet.driver.network.HostAndPort;
import de.dytanic.cloudnet.driver.service.ServiceEnvironmentType;
import de.dytanic.cloudnet.driver.service.ServiceInfoSnapshot;
import de.dytanic.cloudnet.ext.bridge.BridgeHelper;
import de.dytanic.cloudnet.ext.bridge.PluginInfo;
import de.dytanic.cloudnet.ext.bridge.bungee.event.BungeePlayerFallbackEvent;
import de.dytanic.cloudnet.ext.bridge.player.NetworkConnectionInfo;
import de.dytanic.cloudnet.ext.bridge.player.NetworkServiceInfo;
import de.dytanic.cloudnet.ext.bridge.proxy.BridgeProxyHelper;
import de.dytanic.cloudnet.wrapper.Wrapper;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class BungeeCloudNetHelper {

    private static int lastOnlineCount = -1;

    /**
     * @deprecated use {@link BridgeProxyHelper#getCachedServiceInfoSnapshot(String)} or {@link BridgeProxyHelper#cacheServiceInfoSnapshot(ServiceInfoSnapshot)}
     */
    @Deprecated
    public static final Map<String, ServiceInfoSnapshot> SERVER_TO_SERVICE_INFO_SNAPSHOT_ASSOCIATION = BridgeProxyHelper.SERVICE_CACHE;

    private BungeeCloudNetHelper() {
        throw new UnsupportedOperationException();
    }


    public static int getLastOnlineCount() {
        return lastOnlineCount;
    }

    public static boolean isOnAFallbackInstance(ProxiedPlayer proxiedPlayer) {
        return proxiedPlayer.getServer() != null && isFallbackServer(proxiedPlayer.getServer().getInfo());
    }

    public static boolean isFallbackServer(ServerInfo serverInfo) {
        if (serverInfo == null) {
            return false;
        }
        return BridgeProxyHelper.isFallbackService(serverInfo.getName());
    }

    public static Optional<ServerInfo> getNextFallback(ProxiedPlayer player) {
        return BridgeProxyHelper.getNextFallback(
                player.getUniqueId(),
                player.getServer() != null ? player.getServer().getInfo().getName() : null,
                player::hasPermission
        ).map(serviceInfoSnapshot -> ProxyServer.getInstance().getPluginManager().callEvent(
                new BungeePlayerFallbackEvent(player, serviceInfoSnapshot, serviceInfoSnapshot.getName())
        )).map(BungeePlayerFallbackEvent::getFallbackName).map(fallback -> ProxyServer.getInstance().getServerInfo(fallback));
    }

    public static CompletableFuture<ServiceInfoSnapshot> connectToFallback(ProxiedPlayer player, String currentServer) {
        return BridgeProxyHelper.connectToFallback(player.getUniqueId(), currentServer,
                player::hasPermission,
                serviceInfoSnapshot -> {
                    BungeePlayerFallbackEvent event = new BungeePlayerFallbackEvent(player, serviceInfoSnapshot, serviceInfoSnapshot.getName());
                    ProxyServer.getInstance().getPluginManager().callEvent(event);
                    if (event.getFallbackName() == null) {
                        return CompletableFuture.completedFuture(false);
                    }

                    CompletableFuture<Boolean> future = new CompletableFuture<>();
                    ServerInfo serverInfo = ProxyServer.getInstance().getServerInfo(event.getFallbackName());
                    player.connect(serverInfo, (result, error) -> future.complete(result && error == null));
                    return future;
                }
        );
    }

    public static boolean isServiceEnvironmentTypeProvidedForBungeeCord(ServiceInfoSnapshot serviceInfoSnapshot) {
        Preconditions.checkNotNull(serviceInfoSnapshot);
        ServiceEnvironmentType currentServiceEnvironment = Wrapper.getInstance().getCurrentServiceInfoSnapshot().getServiceId().getEnvironment();
        return (serviceInfoSnapshot.getServiceId().getEnvironment().isMinecraftJavaServer() && currentServiceEnvironment.isMinecraftJavaProxy())
                || (serviceInfoSnapshot.getServiceId().getEnvironment().isMinecraftBedrockServer() && currentServiceEnvironment.isMinecraftBedrockProxy());
    }

    public static void initProperties(ServiceInfoSnapshot serviceInfoSnapshot) {
        Preconditions.checkNotNull(serviceInfoSnapshot);

        lastOnlineCount = ProxyServer.getInstance().getPlayers().size();

        serviceInfoSnapshot.getProperties()
                .append("Online", BridgeHelper.isOnline())
                .append("Version", ProxyServer.getInstance().getVersion())
                .append("Game-Version", ProxyServer.getInstance().getGameVersion())
                .append("Online-Count", ProxyServer.getInstance().getOnlineCount())
                .append("Channels", ProxyServer.getInstance().getChannels())
                .append("BungeeCord-Name", ProxyServer.getInstance().getName())
                .append("Players", ProxyServer.getInstance().getPlayers().stream().map(proxiedPlayer -> new BungeeCloudNetPlayerInfo(
                        proxiedPlayer.getUniqueId(),
                        proxiedPlayer.getName(),
                        proxiedPlayer.getServer() != null ? proxiedPlayer.getServer().getInfo().getName() : null,
                        proxiedPlayer.getPing(),
                        new HostAndPort(proxiedPlayer.getPendingConnection().getAddress())
                )).collect(Collectors.toList()))
                .append("Plugins", ProxyServer.getInstance().getPluginManager().getPlugins().stream().map(plugin -> {
                    PluginInfo pluginInfo = new PluginInfo(plugin.getDescription().getName(), plugin.getDescription().getVersion());

                    pluginInfo.getProperties()
                            .append("author", plugin.getDescription().getAuthor())
                            .append("main-class", plugin.getDescription().getMain())
                            .append("depends", plugin.getDescription().getDepends())
                    ;

                    return pluginInfo;
                }).collect(Collectors.toList()))
        ;
    }

    public static NetworkConnectionInfo createNetworkConnectionInfo(PendingConnection pendingConnection) {
        return BridgeHelper.createNetworkConnectionInfo(
                pendingConnection.getUniqueId(),
                pendingConnection.getName(),
                pendingConnection.getVersion(),
                new HostAndPort(pendingConnection.getAddress()),
                new HostAndPort(pendingConnection.getListener().getHost()),
                pendingConnection.isOnlineMode(),
                pendingConnection.isLegacy(),
                new NetworkServiceInfo(
                        Wrapper.getInstance().getServiceId(),
                        Wrapper.getInstance().getCurrentServiceInfoSnapshot().getConfiguration().getGroups()
                )
        );
    }


    public static ServerInfo createServerInfo(String name, InetSocketAddress address) {
        Preconditions.checkNotNull(name);
        Preconditions.checkNotNull(address);

        // with rakNet enabled to support bedrock servers on Waterdog
        if (Wrapper.getInstance().getCurrentServiceInfoSnapshot().getServiceId().getEnvironment() == ServiceEnvironmentType.WATERDOG) {
            try {
                Class<ProxyServer> proxyServerClass = ProxyServer.class;

                Method method = proxyServerClass.getMethod("constructServerInfo",
                        String.class, SocketAddress.class, String.class, boolean.class, boolean.class, String.class);
                method.setAccessible(true);
                return (ServerInfo) method.invoke(ProxyServer.getInstance(), name, address, "CloudNet provided serverInfo", false, true, "default");
            } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException exception) {
                Wrapper.getInstance().getLogger().log(LogLevel.ERROR, "Unable to enable rakNet, although using Waterdog: ", exception);
            }
        }

        return ProxyServer.getInstance().constructServerInfo(name, address, "CloudNet provided serverInfo", false);
    }
}
