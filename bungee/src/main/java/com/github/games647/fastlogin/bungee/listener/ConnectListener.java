package com.github.games647.fastlogin.bungee.listener;

import com.github.games647.craftapi.UUIDAdapter;
import com.github.games647.fastlogin.bungee.BungeeLoginSession;
import com.github.games647.fastlogin.bungee.FastLoginBungee;
import com.github.games647.fastlogin.bungee.task.AsyncPremiumCheck;
import com.github.games647.fastlogin.bungee.task.ForceLoginTask;
import com.github.games647.fastlogin.core.RateLimiter;
import com.github.games647.fastlogin.core.StoredProfile;
import com.github.games647.fastlogin.core.shared.LoginSession;

import java.lang.reflect.Field;
import java.util.UUID;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.connection.InitialHandler;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.connection.LoginResult.Property;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Enables online mode logins for specified users and sends plugin message to the Bukkit version of this plugin in
 * order to clear that the connection is online mode.
 */
public class ConnectListener implements Listener {

    private final FastLoginBungee plugin;
    private final Property[] emptyProperties = {};

    private final RateLimiter rateLimiter;

    public ConnectListener(FastLoginBungee plugin, RateLimiter rateLimiter) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent preLoginEvent) {
        if (preLoginEvent.isCancelled()) {
            return;
        }

        PendingConnection connection = preLoginEvent.getConnection();
        if (!rateLimiter.tryAcquire()) {
            plugin.getLog().warn("Rate Limit hit - Ignoring player {}", connection);
            return;
        }

        preLoginEvent.registerIntent(plugin);

        Runnable asyncPremiumCheck = new AsyncPremiumCheck(plugin, preLoginEvent, connection);
        plugin.getScheduler().runAsync(asyncPremiumCheck);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent loginEvent) {
        if (loginEvent.isCancelled()) {
            return;
        }

        //use the login event instead of the post login event in order to send the login success packet to the client
        //with the offline uuid this makes it possible to set the skin then
        final PendingConnection connection = loginEvent.getConnection();
        final InitialHandler initialHandler = (InitialHandler) connection;

        final String username = initialHandler.getLoginRequest().getData();
        if (connection.isOnlineMode()) {
            LoginSession session = plugin.getSession().get(connection);
            session.setUuid(connection.getUniqueId());

            StoredProfile playerProfile = session.getProfile();
            playerProfile.setId(connection.getUniqueId());

            //bungeecord will do this automatically so override it on disabled option
            if (!plugin.getCore().getConfig().get("premiumUuid", true)) {
                try {
                    final UUID oldPremiumId = connection.getUniqueId();
                    final UUID offlineUUID = UUIDAdapter.generateOfflineId(username);

                    // BungeeCord only allows setting the UUID in PreLogin events and before requesting online mode
                    // However if online mode is requested, it will override previous values
                    // So we have to do it with reflection
                    Field idField = InitialHandler.class.getDeclaredField("uniqueId");
                    idField.setAccessible(true);
                    idField.set(connection, offlineUUID);

                    String format = "Overridden UUID from {} to {} (based of {}) on {}";
                    plugin.getLog().info(format, oldPremiumId, offlineUUID, username, connection);

                    // check if the field was actually set correctly
                    UUID offlineResult = (UUID) idField.get(connection);
                    UUID connectionResult = connection.getUniqueId();
                    if (!offlineUUID.equals(offlineResult)
                            || !offlineUUID.equals(connectionResult)) {
                        throw new RuntimeException("Inconsistent UUIDs: expected " + offlineUUID
                                + " got (Reflection, Connection)" + offlineResult + " and " + connection);
                    }
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    plugin.getLog().error("Failed to set offline uuid of {}", username, ex);
                }
            }

            if (!plugin.getCore().getConfig().get("forwardSkin", true)) {
                // this is null on offline mode
                LoginResult loginProfile = initialHandler.getLoginProfile();
                if (loginProfile != null) {
                    loginProfile.setProperties(emptyProperties);
                }
            }
        }
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent serverConnectedEvent) {
        ProxiedPlayer player = serverConnectedEvent.getPlayer();
        Server server = serverConnectedEvent.getServer();

        BungeeLoginSession session = plugin.getSession().get(player.getPendingConnection());
        if (session == null) {
            return;
        }

        // delay sending force command, because Paper will process the login event asynchronously
        // In this case it means that the force command (plugin message) is already received and processed while
        // player is still in the login phase and reported to be offline.
        Runnable loginTask = new ForceLoginTask(plugin.getCore(), player, server, session);
        plugin.getScheduler().runAsync(loginTask);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent disconnectEvent) {
        ProxiedPlayer player = disconnectEvent.getPlayer();
        plugin.getSession().remove(player.getPendingConnection());
        plugin.getCore().getPendingConfirms().remove(player.getUniqueId());
    }
}
