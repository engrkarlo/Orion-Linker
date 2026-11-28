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
    private static final Pattern MOTD_COLORS=Pattern.compile("(?i)§[0-9a-fk-orx]");
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
    private ChatRelay chat;
    private CoreProtectRecovery recovery;
    private DashboardMenus dashboards;
    private final BridgeMetrics metrics=new BridgeMetrics();
    // Keep nonce history across reload/rollback and key rotations for the full replay window.
    private final AuditAuth.NonceLedger auditNonces=new AuditAuth.NonceLedger(4096),controlNonces=new AuditAuth.NonceLedger(4096);
    AuditAuth auditAuthorization(String guild,String server,String key){return new AuditAuth(guild,server,key,auditNonces);}
    BridgeMetrics metrics(){return metrics;}
    CoreProtectRecovery recovery(){return recovery;}
    LocalModeration moderation(){return moderation;}
    VerificationGate verification(){return verification;}
    SecurityAudit audit(){return audit;}
    ChatRelay chat(){return chat;}
    LuckPermsRoles roles(){return roles;}
    private AutoCloseable worldEditGuard,luckPermsAudit;
    OrionGuard guard(){return guard;}
    AuthenticatedIdentity identities(){return identities;}
    void main(Runnable action){if(org.bukkit.Bukkit.isPrimaryThread())action.run();else getServer().getScheduler().runTask(this,action);}
    private volatile Session session;
    private volatile long lastWarning;
    private org.bukkit.event.Listener verificationFallback;
    private YamlConfiguration activeConfig;
    private volatile java.util.Map<String,YamlConfiguration> themes=java.util.Map.of();
    private volatile TextTheme messages=TextTheme.empty();
    TextTheme messages(){return messages;}
    YamlConfiguration theme(String name){return java.util.Objects.requireNonNull(themes.get(name));}
    private OrionCommands commands;
    private final SetupRequest setup=new SetupRequest();
    private volatile String setupStatus="{}";
    String setupStatus(){return setupStatus;}
    void signal(String lane){var current=session;if(current!=null&&current.pull!=null)current.pull.signal(lane);}
    private void publishSetupStatus(){setupStatus="{\"transport\":"+quote(session==null||session.pull==null?"OFF":session.pull.transport())+",\"audit\":"+(audit!=null)+",\"control\":"+(control!=null)+",\"linking\":"+(verification!=null)+",\"forced\":"+(verification!=null&&verification.forced())+",\"chat\":"+(chat!=null)+",\"chatRequireLink\":"+(chat==null||chat.requiresLink())+",\"guard\":"+(guard!=null)+",\"roles\":"+(roles!=null)+",\"recovery\":"+(recovery!=null)+"}";}
    private OrionGuard retainedGuard;
    private record Settings(String mode, String transport, URI endpoint, String bind, int port, String token, int seconds, boolean hideVanished, boolean respectListing, boolean savedSkins) {}
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
        Session(Settings settings, StoredSkins skins, AuditEndpoint audit,ControlEndpoint control,SetupRequest setup) throws java.io.IOException {
            this.settings=settings; this.storedSkins=skins;
            this.http=settings.mode().equals("push")?HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build():null;
            try {this.pull=settings.mode().equals("pull")?new PullHttp(settings.bind(),settings.port(),settings.token(),audit,control,setup,settings.transport()):null;}
            catch(java.io.IOException | RuntimeException e){worker.shutdownNow();if(http!=null)http.shutdownNow();throw e;}
        }
        void close(){try{if(pull!=null)pull.close();}finally{worker.shutdownNow();if(http!=null)http.shutdownNow();}}
    }

    @Override public void onEnable() {
        long started=System.nanoTime();
        YamlConfiguration startupConfig=null;
        boolean failed=false;
        commands=new OrionCommands(this);
        var command=getCommand("ob");if(command!=null){command.setExecutor(commands);command.setTabCompleter(commands);}
        try {
            var cfg=CategoryConfig.load(this);startupConfig=cfg;
            var appearance=loadAppearance();setAppearance(appearance);
            Settings settings=readSettings(cfg,true);
            initialize(cfg,settings,null,false);
            activeConfig=cfg;
            refreshOnline();
        }
        catch(Exception|LinkageError e){failed=true;getLogger().warning("Bridge startup incomplete: "+safeError(e)+". Check configuration files.");if(verification==null&&(startupConfig==null||forceRequested(startupConfig)))installVerificationFallback();}
        startupBanner(startupConfig,failed,started);
    }
    private java.util.Map<String,YamlConfiguration> loadAppearance()throws Exception{
        var directory=getDataFolder().toPath();var loaded=new java.util.LinkedHashMap<String,YamlConfiguration>();
        for(String name:CategoryConfig.MENUS){String path="menus/"+name+".yml";var yaml=CategoryConfig.read(directory.resolve(path));CategoryConfig.merge(yaml,CategoryConfig.resource(this,path),false);loaded.put(name,yaml);}
        var yaml=CategoryConfig.read(directory.resolve("messages.yml"));CategoryConfig.merge(yaml,CategoryConfig.resource(this,"messages.yml"),false);loaded.put("messages",yaml);
        return java.util.Map.copyOf(loaded);
    }
    private void setAppearance(java.util.Map<String,YamlConfiguration> appearance){
        themes=appearance;messages=new TextTheme(appearance.get("messages"));
    }
    private void initialize(YamlConfiguration cfg,Settings settings,VerificationState challengeState,boolean strict)throws Exception{
            identities=new AuthenticatedIdentity(this,cfg.getString("verification.authme-realm",""));
            if(settings.mode().equals("pull")&&cfg.getBoolean("audit.enabled",false)){
                try{audit=new SecurityAudit(this,cfg);}catch(Exception e){if(strict)throw e;getLogger().warning("Audit feed inactive: check audit scope, signing key and storage.");}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("roles.enabled",false)&&getServer().getPluginManager().isPluginEnabled("LuckPerms"))try{roles=new LuckPermsRoles(this,cfg);}catch(LinkageError|RuntimeException e){if(strict)throw e;getLogger().warning("LuckPerms role sync unavailable.");}
            if(cfg.getBoolean("verification.enabled",false)&&cfg.getBoolean("verification.setup-complete",false)){
                try{verification=new VerificationGate(this,cfg,challengeState);}
                catch(Exception e){if(strict)throw e;getLogger().severe("Identity setup could not initialize. Check verification configuration.");if(forceRequested(cfg))installVerificationFallback();}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("control.enabled",false)){
                try{
                    String key=cfg.getString("control.signing-key","");
                    if(key.equals(settings.token())||key.equals(cfg.getString("audit.signing-key","")))throw new IllegalArgumentException("Control key must be distinct");
                    AuditAuth auth=new AuditAuth(cfg.getString("audit.guild-id",""),cfg.getString("audit.server-id",""),key,controlNonces);
                    // Scope is shared with the audit feed, signing credentials are not.
                    moderation=new LocalModeration(this,cfg);control=new ControlEndpoint(auth,moderation,verification,this);
                }catch(Exception e){if(strict)throw e;getLogger().severe("Moderation control unavailable; check configuration and sanction storage. No remote enforcement is available.");}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("guard.enabled",false)){
                guard=new OrionGuard(this,cfg,verification,moderation,audit,retainedGuard);
                if(getServer().getPluginManager().isPluginEnabled("WorldEdit")||getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit"))try{worldEditGuard=new WorldEditGuard(guard);guard.worldEditReady();}catch(LinkageError|RuntimeException e){getLogger().severe("WorldEdit/FAWE guard hook unavailable; edit commands will be denied while edit enforcement is enabled.");}
                if(getServer().getPluginManager().isPluginEnabled("LuckPerms"))try{luckPermsAudit=new LuckPermsAudit(this,guard);}catch(LinkageError|RuntimeException e){getLogger().warning("LuckPerms audit hook unavailable.");}
            }
            if(settings.mode().equals("pull")&&cfg.getBoolean("chat.enabled",false)&&(verification!=null||!cfg.getBoolean("chat.require-link",true)))chat=new ChatRelay(this,cfg,verification,moderation);
            if(settings.mode().equals("pull")&&cfg.getBoolean("recovery.enabled",false))try{recovery=new CoreProtectRecovery(this,cfg);}catch(ReflectiveOperationException|RuntimeException e){if(strict)throw e;getLogger().warning("CoreProtect recovery unavailable; check plugin API.");}
            dashboards=new DashboardMenus(this,cfg);
            if(strict&&settings.mode().equals("pull")&&cfg.getBoolean("roles.enabled")&&roles==null)throw new IllegalArgumentException("Rank synchronization requires LuckPerms");
            if(strict&&verifyRequested(cfg)&&control==null)throw new IllegalArgumentException("Verification requires signed control in Pull mode");
            if(strict&&cfg.getBoolean("chat.enabled")&&(chat==null||control==null))throw new IllegalArgumentException("Chat requires signed control and, in linked-only mode, account linking");
            startSession(settings);
    }
    private static boolean sameIdentityScope(YamlConfiguration a,YamlConfiguration b){
        return a!=null&&b!=null&&java.util.List.of("audit.guild-id","audit.server-id","control.signing-key","verification.authme-realm").stream().allMatch(k->java.util.Objects.equals(a.get(k),b.get(k)));
    }
    enum ReloadResult{APPLIED,BUSY,UNCHANGED,RESTORED,FAILED}
    ReloadResult reloadAll(){
        var previous=activeConfig;var previousAppearance=themes;
        Settings previousSettings=session==null?null:session.settings;
        final YamlConfiguration next;final java.util.Map<String,YamlConfiguration> appearance;final Settings settings;
        try{
            next=CategoryConfig.load(this);appearance=loadAppearance();settings=readSettings(next,previousSettings==null);validateDependencies(next,settings);
        }catch(Exception|LinkageError e){getLogger().warning("Configuration not applied: "+safeError(e));return ReloadResult.UNCHANGED;}
        if(previous!=null&&session!=null&&verificationFallback==null&&(!verifyRequested(previous)||verification!=null)&&CategoryConfig.same(previous,next)&&runtimeMatches(next,settings)){
            // Only presentation changed; keep sessions, leases, HTTP and queues.
            try{reloadAppearance(next,appearance);return ReloadResult.APPLIED;}
            catch(Exception|LinkageError failure){getLogger().warning("Appearance reload rejected: "+safeError(failure));return ReloadResult.UNCHANGED;}
        }
        // Quiesce signed requests before closing their stores. Do not wait for
        // workers that may themselves be waiting for the Minecraft main thread.
        Session current=session;
        if(current!=null&&current.pull!=null&&!current.pull.quiesce())return ReloadResult.BUSY;
        if(moderation!=null&&moderation.pendingEffects()){if(current!=null&&current.pull!=null)current.pull.resume();return ReloadResult.BUSY;}
        VerificationState challenges=verification==null?null:verification.reloadState();
        boolean compatible=sameIdentityScope(previous,next);
        try{
            stopServices();setAppearance(appearance);initialize(next,settings,compatible?challenges:null,true);
            activeConfig=next;clearFallback();refreshOnline();
            return ReloadResult.APPLIED;
        }catch(Exception|LinkageError failure){
            getLogger().warning("Reload failed: "+safeError(failure)+"; restoring the previous runtime.");
            stopServices();
            if(previous!=null&&previousSettings!=null)try{
                setAppearance(previousAppearance);initialize(previous,previousSettings,challenges,true);
                activeConfig=previous;clearFallback();refreshOnline();return ReloadResult.RESTORED;
            }catch(Exception|LinkageError rollback){getLogger().severe("Previous runtime could not be restored: "+safeError(rollback));stopServices();}
            if(!previousAppearance.isEmpty())setAppearance(previousAppearance);
            activeConfig=null;installVerificationFallback();
            // A failed rollback must not leave current players unrestricted.
            for(Player player:java.util.List.copyOf(getServer().getOnlinePlayers()))player.kick(messages.render("verification.configuration-unavailable"));
            return ReloadResult.FAILED;
        }
    }
    private void validateDependencies(YamlConfiguration cfg,Settings settings){
        boolean pull=settings.mode().equals("pull");
        if(cfg.getBoolean("verification.lobby.enabled")&&!forceRequested(cfg))throw new IllegalArgumentException("Verification lobby requires forced verification");
        if(verifyRequested(cfg)&&(!pull||!cfg.getBoolean("control.enabled")))throw new IllegalArgumentException("Verification requires Pull mode and signed control");
        if(cfg.getBoolean("chat.enabled")&&(!cfg.getBoolean("control.enabled")||!pull||cfg.getBoolean("chat.require-link",true)&&!verifyRequested(cfg)))throw new IllegalArgumentException("Chat requires Pull mode, signed control and, in linked-only mode, account linking");
        if(pull&&cfg.getBoolean("audit.enabled"))new AuditAuth(cfg.getString("audit.guild-id",""),cfg.getString("audit.server-id",""),cfg.getString("audit.signing-key",""));
        if(pull&&cfg.getBoolean("control.enabled")){
            String key=cfg.getString("control.signing-key","");
            if(key.equals(settings.token())||key.equals(cfg.getString("audit.signing-key","")))throw new IllegalArgumentException("Control key must be distinct");
            new AuditAuth(cfg.getString("audit.guild-id",""),cfg.getString("audit.server-id",""),key);
        }
    }
    private boolean runtimeMatches(YamlConfiguration cfg,Settings settings){
        boolean pull=settings.mode().equals("pull");
        return dashboards!=null&&(verification!=null)==verifyRequested(cfg)
            &&(audit!=null)==(pull&&cfg.getBoolean("audit.enabled"))
            &&(control!=null)==(pull&&cfg.getBoolean("control.enabled"))
            &&(guard!=null)==(pull&&cfg.getBoolean("guard.enabled"))
            &&(roles!=null)==(pull&&cfg.getBoolean("roles.enabled"))
            &&(chat!=null)==(pull&&cfg.getBoolean("chat.enabled")&&(verification!=null||!cfg.getBoolean("chat.require-link",true)))
            &&(recovery!=null)==(pull&&cfg.getBoolean("recovery.enabled"));
    }
    private void refreshOnline(){
        publishSetupStatus();
        if(moderation!=null)moderation.enforceOnlineBans();
        if(verification!=null)verification.refreshAfterReload();
    }
    private void reloadAppearance(YamlConfiguration config,java.util.Map<String,YamlConfiguration> appearance)throws Exception{
        var oldAppearance=themes;DashboardMenus nextDashboard=null;VerificationMenus nextVerification=null;
        try{
            setAppearance(appearance);
            nextDashboard=new DashboardMenus(this,config);
            if(verification!=null)nextVerification=new VerificationMenus(this,config);
        }catch(Exception|LinkageError e){
            if(nextDashboard!=null)nextDashboard.close();if(nextVerification!=null)nextVerification.close();
            setAppearance(oldAppearance);throw e;
        }
        closeMenus();
        if(dashboards!=null)dashboards.close();dashboards=nextDashboard;
        if(verification!=null)verification.replaceMenus(nextVerification);
        activeConfig=config;publishSetupStatus();
        if(verification!=null)verification.refreshAfterReload();
    }
    private void closeMenus(){
        for(Player player:getServer().getOnlinePlayers())if(VerificationMenus.owns(player.getOpenInventory().getTopInventory())||DashboardMenus.owns(player.getOpenInventory().getTopInventory()))player.closeInventory();
    }
    private void clearFallback(){if(verificationFallback!=null){org.bukkit.event.HandlerList.unregisterAll(verificationFallback);verificationFallback=null;}}
    private void installVerificationFallback(){
        if(verificationFallback!=null)return;
        getLogger().severe("Configuration/verification unavailable; joins restricted. Restore configuration or use console ob verification-off temporarily.");
        verificationFallback=new org.bukkit.event.Listener(){
            @org.bukkit.event.EventHandler public void login(org.bukkit.event.player.PlayerLoginEvent event){event.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(messages.render("verification.configuration-unavailable")));}
        };
        getServer().getPluginManager().registerEvents(verificationFallback,this);
    }
    private void console(String key,String... values){
        if(themes.isEmpty())return;
        getServer().getConsoleSender().sendMessage(messages.render("console."+key,values));
    }
    private void section(String key){console("section","label",messages.raw("console.sections."+key));}
    private void field(String label,String value,ChatColor color){
        String style=switch(color){case GREEN->"active";case RED->"error";case YELLOW->"warning";case DARK_GRAY->"inactive";case AQUA->"info";default->"normal";};
        String text=ChatColor.stripColor(value);String translated=messages.raw("console.labels."+label);
        console("fields."+style,"label",String.format(java.util.Locale.ROOT,"%-23s",translated),"value",text);
    }
    private boolean feature(String label,boolean requested,boolean available,String detail){
        var state=StartupSummary.status(requested,available);
        field(label,state.label()+"  "+ChatColor.GRAY+detail,state==StartupSummary.Status.ACTIVE?ChatColor.GREEN:state==StartupSummary.Status.OFF?ChatColor.DARK_GRAY:ChatColor.YELLOW);
        return state==StartupSummary.Status.UNAVAILABLE;
    }
    private boolean installed(String name){return getServer().getPluginManager().isPluginEnabled(name);}
    private void integration(String name,String detail){
        Plugin dependency=getServer().getPluginManager().getPlugin(name);
        boolean enabled=dependency!=null&&dependency.isEnabled();
        field(name.toLowerCase(java.util.Locale.ROOT),enabled?"AVAILABLE  "+ChatColor.GRAY+StartupSummary.clean(dependency.getDescription().getVersion())+" · "+detail:"NOT LOADED  "+ChatColor.GRAY+detail,enabled?ChatColor.AQUA:ChatColor.DARK_GRAY);
    }
    private void startupBanner(YamlConfiguration cfg,boolean failed,long started){
        Session current=session;
        if(themes.isEmpty()){getLogger().warning("Startup configuration unavailable; see earlier errors.");return;}
        console("divider");console("title","version",StartupSummary.clean(getDescription().getVersion()));console("subtitle");console("creator");
        section("runtime");
        field("platform","Paper "+StartupSummary.clean(getServer().getMinecraftVersion())+" · Java "+Runtime.version().feature(),ChatColor.WHITE);
        field("configuration",cfg==null?"Unavailable":"Schema v"+cfg.getInt("config-version")+" · 8 feature files",cfg==null?ChatColor.RED:ChatColor.AQUA);
        field("authentication",getServer().getOnlineMode()?"Online-mode Java":"Offline-mode / trusted authentication required",getServer().getOnlineMode()?ChatColor.GREEN:ChatColor.YELLOW);
        field("credentials","Values hidden · never printed",ChatColor.DARK_GRAY);
        section("transport");
        if(current==null)field("transport","INACTIVE · check configuration and listener",ChatColor.RED);
        else{
            Settings s=current.settings;
            field("transport",s.mode().toUpperCase(java.util.Locale.ROOT)+"  "+(s.mode().equals("pull")?"· "+s.bind()+":"+s.port():"· outbound delivery"),ChatColor.AQUA);
            field("telemetry","Every "+s.seconds()+"s · local collection, async delivery",ChatColor.WHITE);
            field("remote","Not verified at startup",ChatColor.YELLOW);
            field("privacy","Vanish "+(s.hideVanished()?"on":"off")+" · Listing "+(s.respectListing()?"on":"off"),ChatColor.WHITE);
            feature("skins",s.savedSkins(),current.storedSkins!=null,"optional SkinsRestorer fallback");
        }
        section("services");
        boolean degraded=failed||current==null||cfg==null;
        if(cfg!=null){
            boolean verify=cfg.getBoolean("verification.enabled");
            boolean complete=cfg.getBoolean("verification.setup-complete");
            if(verify&&!complete){field("verification","SETUP REQUIRED · gate not active",ChatColor.YELLOW);degraded=true;}
            else degraded|=feature("verification",verify,verification!=null,verification==null?"Java / Bedrock / AuthMe":"5-minute reconnect-safe challenge");
            degraded|=feature("audit",cfg.getBoolean("audit.enabled"),audit!=null,"event journal");
            degraded|=feature("control",cfg.getBoolean("control.enabled"),control!=null,"moderation and policy");
            degraded|=feature("guard",cfg.getBoolean("guard.enabled"),guard!=null,guard==null?"local enforcement":"profiles: "+guard.profiles().size()+" · lockdown "+(guard.lockdown()?"ACTIVE":"off"));
            degraded|=feature("roles",cfg.getBoolean("roles.enabled"),roles!=null,"LuckPerms");
            degraded|=feature("chat",cfg.getBoolean("chat.enabled"),chat!=null,"public / private staff routes");
            degraded|=feature("recovery",cfg.getBoolean("recovery.enabled"),recovery!=null,"CoreProtect");
            degraded|=feature("lobby",verify&&cfg.getBoolean("verification.lobby.enabled"),verification!=null&&cfg.getBoolean("verification.lobby.enabled"),"optional safe return");
            field("dashboard",dashboards!=null?"ACTIVE · "+(cfg.getString("verification.java-interface","DIALOG").equalsIgnoreCase("INVENTORY")?"Java inventory":"Java dialog")+" / Bedrock forms":"UNAVAILABLE",dashboards!=null?ChatColor.GREEN:ChatColor.YELLOW);
            if(verify&&complete&&(control==null||current==null||!current.settings.mode().equals("pull"))){field("verification-transport","UNAVAILABLE · players may be restricted",ChatColor.RED);degraded=true;}
        }
        section("integrations");
        integration("floodgate","Bedrock identity / forms");
        integration("Geyser-Spigot","Bedrock client detection");
        integration("AuthMe","offline Java authentication");
        integration("LuckPerms",luckPermsAudit!=null?"audit hook active":"audit hook inactive");
        integration("WorldEdit",worldEditGuard!=null?"WE-compatible hook active":"no edit hook");
        integration("FastAsyncWorldEdit",worldEditGuard!=null?"WE-compatible hook registered":"no edit hook");
        integration("CoreProtect",recovery!=null?"recovery active":"recovery inactive");
        if(cfg!=null&&cfg.getBoolean("guard.enabled")&&cfg.getBoolean("guard.enforce-worldedit",true)&&(installed("WorldEdit")||installed("FastAsyncWorldEdit"))&&worldEditGuard==null){
            field("edit-protection","UNAVAILABLE · supported edit commands denied if Guard initialized",ChatColor.YELLOW);degraded=true;
        }
        section("summary");
        field("local-state",current==null?"INACTIVE":degraded?"DEGRADED · review messages above":"READY · local initialization complete",current==null?ChatColor.RED:degraded?ChatColor.YELLOW:ChatColor.GREEN);
        field("elapsed",String.format(java.util.Locale.ROOT,"%.2f s",(System.nanoTime()-started)/1_000_000_000d),ChatColor.AQUA);
        field("diagnostics","ob status  ·  ob reload  ·  all configuration files",ChatColor.WHITE);
        if(verificationFallback!=null||verifyRequested(cfg))field("emergency","Console: ob verification-off",ChatColor.YELLOW);
        console("divider");
    }
    private static boolean verifyRequested(YamlConfiguration cfg){return cfg!=null&&cfg.getBoolean("verification.enabled")&&cfg.getBoolean("verification.setup-complete");}
    private static boolean forceRequested(YamlConfiguration cfg){return verifyRequested(cfg)&&cfg.getBoolean("verification.force",true);}

    private static String safeError(Throwable e){
        return e instanceof CategoryConfig.LayoutException||e instanceof IllegalArgumentException?e.getMessage():"Unable to read configuration or bind the listener";
    }

    private Settings readSettings(YamlConfiguration cfg,boolean generateToken) throws Exception {
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
            if(!generateToken)throw new IllegalArgumentException("Restore your existing pairing token before reload");
            byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
            token=HexFormat.of().formatHex(secret);cfg.set("token",token);var root=CategoryConfig.readRoot(getDataFolder().toPath());root.set("token",token);CategoryConfig.save(getDataFolder().toPath().resolve("config.yml"),root);
            getLogger().info("Generated a pull token in config.yml. Keep it private.");
        }
        if(!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Token must be 64 lowercase hexadecimal characters");
        String transport=cfg.getString("transport","AUTO").toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("AUTO","HTTP","WEBSOCKET").contains(transport))throw new IllegalArgumentException("transport must be AUTO, HTTP or WEBSOCKET");
        return new Settings(mode,transport,endpoint,bind,port,token,seconds,cfg.getBoolean("hide-vanished",true),cfg.getBoolean("respect-listing-preference",true),cfg.getBoolean("skinsrestorer-fallback",true));
    }

    private void startSession(Settings settings) throws java.io.IOException {
        StoredSkins skins=null;
        if(settings.savedSkins()){
            Plugin sr=getServer().getPluginManager().getPlugin("SkinsRestorer");
            if(sr!=null&&sr.isEnabled())skins=StoredSkins.connect(sr);
        }
        Session next=new Session(settings,skins,audit==null?null:audit.endpoint,control,setup);
        session=next;
        try {task=getServer().getScheduler().runTaskTimer(this,()->metrics.measure("telemetry",()->collect(next)),1L,settings.seconds()*20L);}
        catch(RuntimeException e){stopSession();throw e;}
        getLogger().info("OrionBridge "+settings.mode()+" enabled; interval "+settings.seconds()+" seconds.");
    }

    private void stopSession(){
        if(task!=null){task.cancel();task=null;}
        setup.cancelPending();Session old=session;session=null;
        if(old!=null)old.close();
    }

    void setupCommand(org.bukkit.command.CommandSender sender,String[] args){
        if(!(sender instanceof org.bukkit.command.ConsoleCommandSender))return;
        if(args.length==2&&args[1].equalsIgnoreCase("cancel")){setup.cancel();messages.send(sender,"setup.cancelled");return;}
        if(session==null||session.pull==null){messages.send(sender,"setup.unavailable");return;}
        long now=System.currentTimeMillis();
        if(args.length==1){messages.send(sender,"setup.code","code",setup.open(now));return;}
        if(args.length==2&&args[1].equalsIgnoreCase("status")){
            var request=setup.peek(now);
            if(request==null){messages.send(sender,"setup.none");return;}
            var c=request.credentials();
            messages.send(sender,"setup.pending","id",request.id(),"guild",c.guild(),"server",c.server(),"actor",c.actor(),"feature",c.feature());return;
        }
        if(args.length==3&&args[1].equalsIgnoreCase("approve")){
            var request=setup.consume(args[2],now);
            if(request==null){messages.send(sender,"setup.none");return;}
            try{
                var c=request.credentials();
                if(!c.feature().equals("CREDENTIALS")){
                    if(activeConfig==null||control==null||!CategoryConfig.same(activeConfig,CategoryConfig.load(this)))throw new java.io.IOException("Finish credential setup and reload local edits before adding features");
                    if(java.util.Set.of("LINK","FORCE").contains(c.feature())&&!getServer().getOnlineMode()&&activeConfig.getString("verification.authme-realm","").isBlank()&&!installed("floodgate"))
                        throw new java.io.IOException("Offline Java needs a trusted AuthMe realm before linking");
                    if(c.feature().equals("FORCE")&&(verification==null||!verification.forced()&&getServer().getOnlinePlayers().stream().noneMatch(p->verification.authorized(p.getUniqueId()))))
                        throw new java.io.IOException("Test optional linking with an online authorized player before enforcing it");
                }
                String originalToken=session.settings.token();
                SetupConfig.provision(getDataFolder().toPath(),c);
                var result=reloadAll();
                if(session!=null&&session.pull!=null&&result!=ReloadResult.FAILED&&originalToken.equals(session.settings.token()))setup.approve(request,originalToken,System.currentTimeMillis());
                messages.send(sender,result==ReloadResult.APPLIED?"setup.applied":"setup.reload-needed");
            }catch(Exception e){getLogger().warning("Setup could not apply: "+(e instanceof java.io.IOException?e.getMessage():"Invalid setup settings"));messages.send(sender,"setup.failed");}
            return;
        }
        messages.send(sender,"setup.usage");
    }
    boolean transportActive(){return session!=null;}
    boolean editHookActive(){return worldEditGuard!=null;}
    boolean permissionHookActive(){return luckPermsAudit!=null;}
    DashboardMenus dashboards(){return dashboards;}
    boolean releaseVerification(){
        boolean released=verificationFallback!=null||verification!=null;
        clearFallback();
        if(verification!=null){verification.close();verification=null;}
        if(roles!=null)roles.close();
        publishSetupStatus();return released;
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
            Snapshot snapshot = new Snapshot(System.currentTimeMillis(), count, getServer().getMaxPlayers(), getServer().getMinecraftVersion(), MOTD_COLORS.matcher(getServer().getMotd()).replaceAll(""),
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
                default -> { if(c<32) s.append("\\u00").append(Character.forDigit(c>>4,16)).append(Character.forDigit(c&15,16)); else s.append(c); }
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

    private void stopServices(){
        setupStatus="{}";stopSession();
        closeMenus();
        if(worldEditGuard!=null)try{worldEditGuard.close();}catch(Exception ignored){}finally{worldEditGuard=null;}
        if(luckPermsAudit!=null)try{luckPermsAudit.close();}catch(Exception ignored){}finally{luckPermsAudit=null;}
        if(dashboards!=null){dashboards.close();dashboards=null;}
        if(chat!=null){chat.close();chat=null;}
        if(guard!=null){guard.close();retainedGuard=guard;guard=null;}
        if(roles!=null){roles.close();roles=null;}
        if(verification!=null){verification.close();verification=null;}
        if(moderation!=null){moderation.close();moderation=null;}
        if(audit!=null){audit.close();audit=null;}
        recovery=null;control=null;
    }
    @Override public void onDisable(){setup.cancel();stopServices();clearFallback();activeConfig=null;retainedGuard=null;}
}
