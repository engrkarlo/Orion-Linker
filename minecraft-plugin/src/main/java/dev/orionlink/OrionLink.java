package dev.orionlink;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One-way role sync foundation. Bukkit access is confined to the server thread. */
public final class OrionLink extends JavaPlugin implements Listener {
    private static final int LIMIT = 4096;
    private final Gson gson = new Gson();
    private static final class Dirty { final User user; Dirty(User user) { this.user = user; } }
    private final Map<UUID, Dirty> snapshots = new LinkedHashMap<>();
    private final Map<UUID, Long> requests = new LinkedHashMap<>();
    private final AtomicInteger loads = new AtomicInteger();
    private ScheduledExecutorService io;
    private HttpClient http;
    private LuckPerms lp;
    private AutoCloseable subscription;
    private volatile WebSocket socket;
    private volatile boolean closed;
    private URI endpoint;
    private String serverId, secret;
    private volatile long retryAt;
    private int failures;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            endpoint = URI.create(getConfig().getString("endpoint", ""));
            serverId = getConfig().getString("server-id", "");
            secret = getConfig().getString("secret", "");
            if (!"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null || !serverId.matches("[a-zA-Z0-9_-]{1,64}") || !secret.matches("[a-fA-F0-9]{64,128}")) throw new IllegalArgumentException();
        } catch (Exception e) {
            getLogger().warning("Configure a WSS endpoint, valid server ID and 64–128 hex character secret before enabling OrionLink.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        if (!Bukkit.getOnlineMode()) { getLogger().warning("This preview requires online-mode=true; proxy and Floodgate identity integration are not implemented."); getServer().getPluginManager().disablePlugin(this); return; }
        lp = LuckPermsProvider.get();
        io = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "OrionLink-io"); t.setDaemon(true); return t; });
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        subscription = lp.getEventBus().subscribe(this, UserDataRecalculateEvent.class, e -> snapshot(e.getUser()));
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("link")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            if (!Bukkit.getOnlineMode()) { tell(p.getUniqueId(), "Linking requires authenticated player identities.", NamedTextColor.RED); return true; }
            if (socket == null || closed) { p.sendMessage(Component.text("OrionLink is reconnecting. Try again shortly.", NamedTextColor.RED)); return true; }
            UUID id = p.getUniqueId();
            synchronized (requests) {
                if (requests.size() >= 256 || requests.containsKey(id)) { p.sendMessage(Component.text("A request is pending. Try again shortly.", NamedTextColor.YELLOW)); return true; }
                requests.put(id, System.currentTimeMillis());
            }
            User u = lp.getUserManager().getUser(id);
            if (u != null) snapshot(u);
            p.sendMessage(Component.text("Requesting your private verification code…", NamedTextColor.LIGHT_PURPLE));
            return true;
        });
        for (Player p : Bukkit.getOnlinePlayers()) { User u = lp.getUserManager().getUser(p.getUniqueId()); if (u != null) snapshot(u); }
        io.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        User u = lp.getUserManager().getUser(e.getPlayer().getUniqueId());
        if (u != null) snapshot(u);
    }
    private void snapshot(User u) {
        if (closed) return;
        synchronized (snapshots) { if (snapshots.containsKey(u.getUniqueId()) || snapshots.size() < LIMIT) snapshots.put(u.getUniqueId(), new Dirty(u)); }
    }
    private JsonObject buildSnapshot(User u) {
        JsonObject m = message("snapshot", u.getUniqueId());
        JsonArray groups = new JsonArray();
        u.getInheritedGroups(QueryOptions.nonContextual()).stream().map(g -> g.getName()).sorted().forEach(groups::add);
        m.add("groups", groups);
        return m;
    }
    private JsonObject message(String type, UUID id) {
        JsonObject m = new JsonObject(); m.addProperty("type", type); m.addProperty("uuid", id.toString()); return m;
    }
    private void tell(UUID id, String text, NamedTextColor color) {
        if (closed) return;
        Bukkit.getScheduler().runTask(this, () -> { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(Component.text(text, color)); });
    }
    private void tick() {
        if (closed) return;
        try {
            if (socket == null) {
                if (System.currentTimeMillis() < retryAt) return;
                WebSocket fresh = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + secret).header("X-Orion-Server", serverId)
                    .buildAsync(endpoint, new Inbound()).get(7, TimeUnit.SECONDS);
                if (closed || fresh.isInputClosed() || fresh.isOutputClosed()) { fresh.abort(); throw new IllegalStateException("Connection closed"); }
                socket = fresh; failures = 0;
            }
            List<UUID> expired = new ArrayList<>();
            UUID issue = null;
            synchronized (requests) {
                long now = System.currentTimeMillis();
                var it = requests.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next(); long stamp = entry.getValue();
                    if (now - Math.abs(stamp) > 15_000) { expired.add(entry.getKey()); it.remove(); }
                    else if (stamp > 0 && issue == null) { issue = entry.getKey(); entry.setValue(-stamp); }
                }
            }
            expired.forEach(id -> tell(id, "Verification request timed out.", NamedTextColor.RED));
            if (issue != null) send(message("issue", issue));
            for (int n = 0; n < 16; n++) {
                UUID id; Dirty value;
                synchronized (snapshots) {
                    if (snapshots.isEmpty()) break;
                    var e = snapshots.entrySet().iterator().next(); id = e.getKey(); value = e.getValue();
                }
                send(buildSnapshot(value.user));
                synchronized (snapshots) { snapshots.remove(id, value); }
            }
        } catch (Exception e) {
            WebSocket old = socket; socket = null; if (old != null) old.abort();
            retryAt = System.currentTimeMillis() + Math.min(60_000, 1000L << Math.min(6, failures++)) + ThreadLocalRandom.current().nextLong(500);
        }
    }
    private void send(JsonObject m) throws Exception {
        WebSocket current = socket;
        if (current == null) throw new IllegalStateException("Disconnected");
        current.sendText(gson.toJson(m), true).get(3, TimeUnit.SECONDS);
    }
    private final class Inbound implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (text.length() + data.length() > 8192) { ws.abort(); return CompletableFuture.completedFuture(null); }
            text.append(data);
            if (last) {
                try {
                    JsonObject m = JsonParser.parseString(text.toString()).getAsJsonObject();
                    UUID id = UUID.fromString(m.get("uuid").getAsString());
                    switch (m.get("type").getAsString()) {
                        case "code" -> {
                            boolean pending; synchronized (requests) { pending = requests.remove(id) != null; }
                            String code = m.get("code").getAsString();
                            if (pending && code.matches("[A-F0-9]{12}")) tell(id, "ᴏʀɪᴏɴʟɪɴᴋ » Use /orionlink link code:" + code + " on Discord. Expires in 5 minutes.", NamedTextColor.LIGHT_PURPLE);
                        }
                        case "snapshot_request" -> {
                            if (!closed && loads.incrementAndGet() <= 32) {
                                lp.getUserManager().loadUser(id).whenComplete((u, err) -> { try { if (err == null) snapshot(u); } finally { loads.decrementAndGet(); } });
                            } else if (!closed) loads.decrementAndGet();
                        }
                        default -> { }
                    }
                } catch (RuntimeException ignored) { /* Ignore malformed peer messages. */ }
                text.setLength(0);
            }
            ws.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { if (socket == ws) { retryAt = System.currentTimeMillis() + 1000 + ThreadLocalRandom.current().nextLong(500); socket = null; } return CompletableFuture.completedFuture(null); }
        @Override public void onError(WebSocket ws, Throwable error) { if (socket == ws) { retryAt = System.currentTimeMillis() + 1000 + ThreadLocalRandom.current().nextLong(500); socket = null; } }
    }
    @Override public void onDisable() {
        closed = true;
        if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
        if (io != null) io.shutdownNow();
        WebSocket ws = socket; socket = null; if (ws != null) ws.abort();
        // Java 25 HttpClient is AutoCloseable; avoid waiting for network shutdown on the server thread.
        if (http != null) http.shutdownNow();
        synchronized (snapshots) { snapshots.clear(); }
        synchronized (requests) { requests.clear(); }
    }
}
