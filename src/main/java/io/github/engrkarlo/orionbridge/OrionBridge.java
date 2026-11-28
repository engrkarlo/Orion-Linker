package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.security.SecureRandom;
import java.util.HexFormat;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Read-only snapshots; all Bukkit player access is on the server thread. */
public final class OrionBridge extends JavaPlugin {
    private static final Pattern TEXTURE = Pattern.compile("/texture/([a-fA-F0-9]{32,64})");
    private static final int MAX_PLAYERS = 2000;
    private BukkitTask task;
    private SecurityAudit audit;
    private LocalModeration moderation;
    private ControlEndpoint control;
    private VerificationGate verification;
    private AuthenticatedIdentity identities;
    private OrionGuard guard;
    private LuckPermsRoles roles;
    LuckPermsRoles roles(){return roles;}
    private AutoCloseable worldEditGuard,luckPermsAudit;
    OrionGuard guard(){return guard;}
    AuthenticatedIdentity identities(){return identities;}
    void main(Runnable action){if(org.bukkit.Bukkit.isPrimaryThread())action.run();else getServer().getScheduler().runTask(this,action);}
    private volatile Session session;
    private volatile long lastWarning;
    private record Settings(String mode, URI endpoint, String bind, int port, String token, int seconds, boolean hideVanished, boolean respectListing, boolean savedSkins) {}
    private static final class Session {
        final Settings settings;
        final AtomicBoolean busy = new AtomicBoolean();
        final RetryGate retry = new RetryGate();
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OrionBridge-telemetry"); t.setDaemon(true); return t;
        });
        final HttpClient http;
        final StoredSkins storedSkins;
        final PullHttp pull;
        Session(Settings settings, StoredSkins skins, AuditEndpoint audit,ControlEndpoint control) throws java.io.IOException {
            this.settings=settings; this.storedSkins=skins;
            this.http=settings.mode().equals("push")?HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build():null;
            try {this.pull=settings.mode().equals("pull")?new PullHttp(settings.bind(),settings.port(),settings.token(),audit,control):null;}
            catch(java.io.IOException | RuntimeException e){worker.shutdownNow();if(http!=null)http.shutdownNow();throw e;}
        }
        void close(){try{if(pull!=null)pull.close();}finally{worker.shutdownNow();if(http!=null)http.shutdownNow();}}
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            Settings settings=readSettings();
            var cfg=new YamlConfiguration();cfg.load(new File(getDataFolder(),"config.yml"));
            identities=new AuthenticatedIdentity(this,cfg.getString("verification.authme-realm",""));
            if(settings.mode().equals("pull")&&cfg.getBoolean("audit.enabled",false)){
                try{audit=new SecurityAudit(this,cfg);}catch(Exception e){getLogger().warning("Audit feed inactive: check audit scope, signing key and storage.");}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("roles.enabled",false)&&getServer().getPluginManager().isPluginEnabled("LuckPerms"))try{roles=new LuckPermsRoles(cfg);}catch(LinkageError|RuntimeException e){getLogger().warning("LuckPerms role sync unavailable.");}
            if(cfg.getBoolean("verification.enabled",false)&&cfg.getBoolean("verification.setup-complete",false)){
                try{verification=new VerificationGate(this,cfg);}
                catch(Exception e){getLogger().severe("Verification setup could not initialize. Restricting joins until configuration is corrected.");getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener(){
                    @org.bukkit.event.EventHandler public void login(org.bukkit.event.player.PlayerLoginEvent event){event.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,"OrionBridge verification unavailable. Contact staff.");}
                },this);}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("control.enabled",false)){
                try{
                    String key=cfg.getString("control.signing-key","");
                    if(key.equals(settings.token())||key.equals(cfg.getString("audit.signing-key","")))throw new IllegalArgumentException("Control key must be distinct");
                    AuditAuth auth=new AuditAuth(cfg.getString("audit.guild-id",""),cfg.getString("audit.server-id",""),key);
                    // Scope is shared with the audit feed, signing credentials are not.
                    moderation=new LocalModeration(this,cfg);control=new ControlEndpoint(auth,moderation,verification,this);
                }catch(Exception e){getLogger().severe("Moderation control unavailable; check configuration and sanction storage. No remote enforcement is available.");}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("guard.enabled",false)){
                guard=new OrionGuard(this,cfg,verification,moderation,audit);
                if(getServer().getPluginManager().isPluginEnabled("WorldEdit")||getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit"))try{worldEditGuard=new WorldEditGuard(guard);}catch(LinkageError|RuntimeException e){getLogger().severe("WorldEdit/FAWE guard hook unavailable; command guard remains active.");}
                if(getServer().getPluginManager().isPluginEnabled("LuckPerms"))try{luckPermsAudit=new LuckPermsAudit(this,guard);}catch(LinkageError|RuntimeException e){getLogger().warning("LuckPerms audit hook unavailable.");}
            }
            apply(settings);
        }
        catch(Exception e){getLogger().warning("Bridge inactive: "+safeError(e)+". Correct config.yml and run /ob reload.");}
        startupBanner();
    }

    private void startupBanner(){
        var console=getServer().getConsoleSender();
        Session current=session;
        String border=ChatColor.DARK_GRAY+"  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
        console.sendMessage(border);
        console.sendMessage(ChatColor.LIGHT_PURPLE+""+ChatColor.BOLD+"  ✦ O R I O N"+ChatColor.AQUA+ChatColor.BOLD+" B R I D G E"+ChatColor.RESET+ChatColor.GRAY+"  v"+getDescription().getVersion());
        console.sendMessage(ChatColor.GRAY+"  Minecraft → Discord • Live telemetry");
        console.sendMessage(ChatColor.DARK_GRAY+"  Created by "+ChatColor.LIGHT_PURPLE+"Karlow");
        console.sendMessage(border);
        console.sendMessage(ChatColor.GRAY+"  State       "+(current==null?ChatColor.RED+"INACTIVE":ChatColor.GREEN+"READY")+" "+ChatColor.DARK_GRAY+(current==null?"(check config.yml)":"(session initialized)"));
        console.sendMessage(ChatColor.GRAY+"  Platform    "+ChatColor.WHITE+"Paper "+getServer().getMinecraftVersion());
        if(current!=null){
            Settings s=current.settings;
            console.sendMessage(ChatColor.GRAY+"  Transport   "+ChatColor.AQUA+s.mode().toUpperCase(java.util.Locale.ROOT)+(s.mode().equals("pull")?ChatColor.GRAY+" • port "+s.port():""));
            console.sendMessage(ChatColor.GRAY+"  Collection  "+ChatColor.WHITE+s.seconds()+"s"+ChatColor.DARK_GRAY+" • async delivery");
            console.sendMessage(ChatColor.GRAY+"  Privacy     "+ChatColor.WHITE+"Vanish "+(s.hideVanished()?"on":"off")+" · Listing "+(s.respectListing()?"on":"off"));
            console.sendMessage(ChatColor.GRAY+"  Saved skins "+(current.storedSkins!=null?ChatColor.GREEN+"Available":ChatColor.YELLOW+"Not active"));
            console.sendMessage(ChatColor.GRAY+"  Credentials "+ChatColor.GREEN+"Configured"+ChatColor.DARK_GRAY+" (hidden)");
            console.sendMessage(ChatColor.GRAY+"  Telemetry   "+ChatColor.YELLOW+"Awaiting first snapshot");
        }
        console.sendMessage(border);
    }

    private static String safeError(Exception e){
        return e instanceof IllegalArgumentException?e.getMessage():"Unable to read configuration or bind the listener";
    }

    private Settings readSettings() throws Exception {
        File file=new File(getDataFolder(),"config.yml");
        try(var input=getResource("config.yml")){
            if(input!=null){
                var defaults=new YamlConfiguration();defaults.load(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
                if(ConfigMigration.migrate(file.toPath(),defaults))getLogger().info("Migrated config.yml; original backed up.");
            }
        }
        YamlConfiguration cfg=new YamlConfiguration(); cfg.load(file);
        String endpointText=cfg.getString("endpoint","").trim();
        String mode=cfg.getString("mode",endpointText.isEmpty()?"pull":"push").trim().toLowerCase(java.util.Locale.ROOT);
        if(!mode.equals("push")&&!mode.equals("pull"))throw new IllegalArgumentException("mode must be push or pull");
        URI endpoint=null;
        if(mode.equals("push")){
            try{endpoint=URI.create(endpointText);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid push endpoint");}
            if(!validEndpoint(endpoint))throw new IllegalArgumentException("Push requires an HTTPS pairing endpoint");
        }
        String bind=cfg.getString("bind","0.0.0.0").trim();
        if(!bind.equals("0.0.0.0")&&!bind.equals("127.0.0.1"))throw new IllegalArgumentException("bind must be 0.0.0.0 or 127.0.0.1");
        int port=cfg.getInt("port",25762),seconds=cfg.getInt("interval-seconds",10);
        if(port<1||port>65535||seconds<5||seconds>30)throw new IllegalArgumentException("Invalid port or interval (5–30 seconds)");
        String token=cfg.getString("token","").trim();
        if(mode.equals("pull")&&token.isEmpty()){
            byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
            token=HexFormat.of().formatHex(secret);cfg.set("token",token);cfg.save(file);
            getLogger().info("Generated a pull token in config.yml. Keep it private.");
        }
        if(!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Token must be 64 lowercase hexadecimal characters");
        return new Settings(mode,endpoint,bind,port,token,seconds,cfg.getBoolean("hide-vanished",true),cfg.getBoolean("respect-listing-preference",true),cfg.getBoolean("skinsrestorer-fallback",true));
    }

    private void apply(Settings next) throws Exception {
        Settings previous=session==null?null:session.settings;
        stopSession();
        try {startSession(next);}
        catch(Exception e){
            if(previous!=null)try{startSession(previous);}catch(Exception rollback){getLogger().warning("Previous bridge settings could not be restored.");}
            throw e;
        }
    }

    private void startSession(Settings settings) throws java.io.IOException {
        StoredSkins skins=null;
        if(settings.savedSkins()){
            Plugin sr=getServer().getPluginManager().getPlugin("SkinsRestorer");
            if(sr!=null&&sr.isEnabled())skins=StoredSkins.connect(sr);
        }
        Session next=new Session(settings,skins,audit==null?null:audit.endpoint,control);
        session=next;
        try {task=getServer().getScheduler().runTaskTimer(this,()->collect(next),1L,settings.seconds()*20L);}
        catch(RuntimeException e){stopSession();throw e;}
        getLogger().info("OrionBridge "+settings.mode()+" enabled; interval "+settings.seconds()+" seconds.");
    }

    private void stopSession(){
        if(task!=null){task.cancel();task=null;}
        Session old=session;session=null;
        if(old!=null)old.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if(args.length==1&&args[0].equalsIgnoreCase("verification-off")&&sender instanceof org.bukkit.command.ConsoleCommandSender){
            if(verification!=null){verification.close();verification=null;getLogger().warning("Verification temporarily disabled by console; restart reapplies configuration.");sender.sendMessage("Verification disabled until restart.");}
            else sender.sendMessage("No active verification gate. Correct configuration and restart if initialization failed.");
            return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("status")&&sender.hasPermission("orionbridge.admin")){
            sender.sendMessage("OrionBridge: telemetry="+(session!=null)+" audit="+(audit!=null)+" verification="+(verification!=null)+" moderation="+(moderation!=null)+" guard="+(guard!=null)+" WorldEdit="+(worldEditGuard!=null)+" LuckPerms="+(luckPermsAudit!=null));return true;
        }
        if(args.length==2&&args[0].equalsIgnoreCase("lockdown")&&sender instanceof org.bukkit.command.ConsoleCommandSender&&guard!=null){
            try{guard.lockdown(args[1].equalsIgnoreCase("off")?0:System.currentTimeMillis()+Math.multiplyExact(Long.parseLong(args[1]),60000L),"console");sender.sendMessage("Lockdown updated.");}catch(RuntimeException e){sender.sendMessage("Usage: ob lockdown <1-60 minutes|off>");}return true;
        }
        if(sender instanceof Player player&&verification!=null&&verification.playerCommand(player,args))return true;
        if(args.length!=1||!args[0].equalsIgnoreCase("reload")){sender.sendMessage("Usage: /ob account | /ob link | /ob reload");return true;}
        if(!sender.hasPermission("orionbridge.reload")){sender.sendMessage("You do not have permission to reload OrionBridge.");return true;}
        try {apply(readSettings());sender.sendMessage("OrionBridge reloaded ("+session.settings.mode()+").");}
        catch(Exception e){sender.sendMessage("OrionBridge reload failed: "+safeError(e)+(session==null?". Bridge inactive.":". Previous settings restored."));}
        return true;
    }

    static boolean validEndpoint(URI uri) {
        String host = uri.getHost(), scheme = uri.getScheme();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) return false;
        if (uri.getPath() == null || !uri.getPath().matches(".*/v1/snapshot/[0-9]{17,20}/[a-f0-9]{16}")) return false;
        return "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host)));
    }

    private record Person(UUID id, String name, String texture, int ping) {}
    private record Snapshot(long sentAt, int online, int max, String version, String motd, double tps, double mspt, long used, long total, String icon, List<Person> players) {}

    private void collect(Session current) {
        if (session!=current||!current.retry.ready(System.nanoTime())||!current.busy.compareAndSet(false, true)) return;
        try {
            List<Person> people = new ArrayList<>();
            int count = 0;
            for (Player p : getServer().getOnlinePlayers()) {
                if (p.hasPermission("orionbridge.hide") || (current.settings.hideVanished() && p.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean())) || (current.settings.respectListing() && !p.isAllowingServerListings())) continue;
                count++;
                if (people.size() >= MAX_PLAYERS) continue;
                String texture = "";
                try { texture = textureId(p.getPlayerProfile().getTextures().getSkin()); } catch (RuntimeException ignored) {}
                people.add(new Person(p.getUniqueId(), p.getName(), texture, Math.max(0,p.getPing())));
            }
            var runtime = Runtime.getRuntime();
            String icon = "";
            try {
                var cached = getServer().getServerIcon();
                if (cached != null && cached.getData() != null && cached.getData().length() <= 350000) icon = cached.getData();
            } catch (RuntimeException ignored) {}
            Snapshot snapshot = new Snapshot(System.currentTimeMillis(), count, getServer().getMaxPlayers(), getServer().getMinecraftVersion(), getServer().getMotd().replaceAll("(?i)§[0-9a-fk-orx]", ""),
                getServer().getTPS()[0], getServer().getAverageTickTime(), runtime.totalMemory()-runtime.freeMemory(), runtime.maxMemory(), icon, List.copyOf(people));
            current.worker.execute(() -> transmit(current,snapshot));
        } catch (RuntimeException e) {
            current.busy.set(false); warning("Unable to collect server telemetry.");
        }
    }

    private void transmit(Session current, Snapshot s) {
        try {
            StringBuilder json = new StringBuilder(1024);
            json.append("{\"protocol\":1,\"sentAt\":").append(s.sentAt()).append(",\"online\":").append(s.online())
                .append(",\"max\":").append(s.max()).append(",\"version\":").append(quote(s.version()))
                .append(",\"motd\":").append(quote(s.motd().substring(0,Math.min(s.motd().length(),800))))
                .append(",\"tps\":").append(finite(s.tps())).append(",\"mspt\":").append(finite(s.mspt()))
                .append(",\"memoryUsed\":").append(s.used()).append(",\"memoryMax\":").append(s.total())
                .append(",\"icon\":").append(quote(s.icon())).append(",\"players\":[");
            boolean first = true;
            for (Person p : s.players()) {
                String texture = p.texture();
                String source = texture.isEmpty() ? "" : "live";
                if (texture.isEmpty() && current.storedSkins != null) {texture = current.storedSkins.lookup(p.id()); if(!texture.isEmpty())source="stored";}
                if (!first) json.append(',');
                first = false;
                json.append("{\"uuid\":").append(quote(p.id().toString())).append(",\"name\":").append(quote(p.name()))
                    .append(",\"texture\":").append(quote(texture)).append(",\"skinSource\":").append(quote(source)).append(",\"ping\":").append(p.ping()).append('}');
            }
            json.append("]}");
            if(session!=current)return;
            if(current.pull!=null){current.pull.publish(json.toString(),s.sentAt());return;}
            var request = HttpRequest.newBuilder(current.settings.endpoint()).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + current.settings.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
            var response = current.http.send(request, HttpResponse.BodyHandlers.discarding());
            int status=response.statusCode();
            if (status == 204) current.retry.success();
            else {
                current.retry.failure(System.nanoTime(),status==429||status==503?RetryGate.retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(""),java.time.Instant.now()):0);
                if(session==current)warning("OrionBot rejected telemetry (HTTP " + status + "); backing off. Check the endpoint and pairing token.");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { current.retry.failure(System.nanoTime(),0);if(session==current)warning("Unable to reach OrionBot; backing off before the next heartbeat."); }
        finally { current.busy.set(false); }
    }

    private static double finite(double n) { return Double.isFinite(n) ? n : 0; }

    static String textureId(URL url) {
        if (url == null || !"textures.minecraft.net".equalsIgnoreCase(url.getHost()) || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) return "";
        if (!"https".equals(url.getProtocol()) && !"http".equals(url.getProtocol())) return "";
        if (url.getPort() != -1) return "";
        var m = TEXTURE.matcher(url.getPath());
        return m.matches() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    static String quote(String value) {
        StringBuilder s = new StringBuilder("\"");
        for (int i=0; i<value.length(); i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                default -> { if(c<32) s.append(String.format("\\u%04x",(int)c)); else s.append(c); }
            }
        }
        return s.append('"').toString();
    }

    private void warning(String message) {
        long now=System.currentTimeMillis();
        if(now-lastWarning>=60000) {lastWarning=now;getLogger().warning(message);}
    }

    /** Optional SR API: only saved skin lookup, off the main thread, no mutation or generation. */
    private static final class StoredSkins {
        private final Object storage;
        private final java.lang.reflect.Method lookup, url;
        private final java.util.LinkedHashMap<UUID, Entry> cache=new java.util.LinkedHashMap<>();
        private record Entry(String texture,long expires){}
        private StoredSkins(Object storage,java.lang.reflect.Method lookup,java.lang.reflect.Method url){this.storage=storage;this.lookup=lookup;this.url=url;}
        static StoredSkins connect(Plugin plugin) {
            try {
                ClassLoader cl=plugin.getClass().getClassLoader();
                Class<?> provider=Class.forName("net.skinsrestorer.api.SkinsRestorerProvider",true,cl);
                Class<?> api=Class.forName("net.skinsrestorer.api.SkinsRestorer",true,cl);
                Class<?> store=Class.forName("net.skinsrestorer.api.storage.PlayerStorage",true,cl);
                Class<?> props=Class.forName("net.skinsrestorer.api.property.SkinProperty",true,cl);
                Class<?> utils=Class.forName("net.skinsrestorer.api.PropertyUtils",true,cl);
                Object storage=api.getMethod("getPlayerStorage").invoke(provider.getMethod("get").invoke(null));
                return new StoredSkins(storage,store.getMethod("getSkinOfPlayer",UUID.class),utils.getMethod("getSkinTextureUrl",props));
            } catch (ReflectiveOperationException | LinkageError e) {return null;}
        }
        String lookup(UUID id) {
            long now=System.currentTimeMillis();Entry old=cache.get(id);
            if(old!=null&&old.expires()>now)return old.texture();
            String found="";
            try {
                Object result=lookup.invoke(storage,id);
                if(result instanceof Optional<?> o && o.isPresent())found=textureId(URI.create(String.valueOf(url.invoke(null,o.get()))).toURL());
            } catch (Exception | LinkageError ignored) {}
            cache.put(id,new Entry(found,now+60000));
            if(cache.size()>2000)cache.remove(cache.keySet().iterator().next());
            return found;
        }
    }

    @Override public void onDisable() {
        stopSession();
        if(audit!=null){audit.close();audit=null;}
        if(worldEditGuard!=null)try{worldEditGuard.close();}catch(Exception ignored){}
        if(luckPermsAudit!=null)try{luckPermsAudit.close();}catch(Exception ignored){}
        if(guard!=null){guard.close();guard=null;}
        if(roles!=null){roles.close();roles=null;}
        if(verification!=null){verification.close();verification=null;}
        if(moderation!=null){moderation.close();moderation=null;}control=null;
    }
}
