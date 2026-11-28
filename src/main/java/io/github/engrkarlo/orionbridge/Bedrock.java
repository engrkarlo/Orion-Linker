package io.github.engrkarlo.orionbridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Optional bridge: no Geyser classes are loaded on servers without the bridge. */
final class Bedrock {
    private record Bridge(ClassLoader loader,Object target,Class<?> api,boolean floodgate,UUID player){}
    private static Bridge bridge(Player p)throws ReflectiveOperationException{
        Plugin flood=Bukkit.getPluginManager().getPlugin("floodgate");if(flood!=null&&flood.isEnabled()){ClassLoader loader=flood.getClass().getClassLoader();Class<?> api=Class.forName("org.geysermc.floodgate.api.FloodgateApi",true,loader);Object target=api.getMethod("getInstance").invoke(null);if(Boolean.TRUE.equals(api.getMethod("isFloodgatePlayer",UUID.class).invoke(target,p.getUniqueId())))return new Bridge(loader,target,api,true,p.getUniqueId());}
        Plugin geyser=Bukkit.getPluginManager().getPlugin("Geyser-Spigot");if(geyser!=null&&geyser.isEnabled()){ClassLoader loader=geyser.getClass().getClassLoader();Class<?> api=Class.forName("org.geysermc.geyser.api.GeyserApi",true,loader);Object target=api.getMethod("api").invoke(null);Object conn=api.getMethod("connectionByUuid",UUID.class).invoke(target,p.getUniqueId());if(conn!=null)return new Bridge(loader,conn,Class.forName("org.geysermc.geyser.api.connection.GeyserConnection",true,loader),false,p.getUniqueId());}return null;
    }
    static String xuid(Player player){
        try{Bridge b=bridge(player);if(b==null)return "";Object account=b.floodgate()?invoke(b.target(),"getPlayer",b.player()):b.target();if(account==null)return "";
            Object value=invoke(account,b.floodgate()?"getXuid":"xuid");return value==null?"":value.toString();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){return "";}
    }
    enum Detection {JAVA,BEDROCK,UNKNOWN}
    static Detection detect(Player p){try{return bridge(p)==null?Detection.JAVA:Detection.BEDROCK;}catch(ReflectiveOperationException|RuntimeException|LinkageError e){return Detection.UNKNOWN;}}
    record Button(String text,String image){}
    static boolean show(Player p,OrionBridge plugin,String title,String content,List<Button> buttons,Consumer<Integer> callback,Runnable closed){
        return form(p,plugin,"SimpleForm",title,builder->{invoke(builder,"content",content);for(Button button:buttons){
            String image=button.image();
            if(image==null||image.isBlank()){invoke(builder,"button",button.text());continue;}
            try{
                Class<?> kind=Class.forName("org.geysermc.cumulus.util.FormImage$Type",true,builder.getClass().getClassLoader());
                Object type=kind.getField(image.startsWith("https://")?"URL":"PATH").get(null);
                invoke(builder,"button",button.text(),type,image);
            }catch(ReflectiveOperationException|RuntimeException e){invoke(builder,"button",button.text());}
        }},response->{try{callback.accept(((Number)invoke(response,"clickedButtonId")).intValue());}catch(ReflectiveOperationException ignored){}},closed);
    }
    static boolean modal(Player p,OrionBridge plugin,String title,String content,String yes,String no,Consumer<Integer> callback,Runnable closed){
        return form(p,plugin,"ModalForm",title,builder->{invoke(builder,"content",content);invoke(builder,"button1",yes);invoke(builder,"button2",no);},response->{try{callback.accept(((Number)invoke(response,"clickedButtonId")).intValue());}catch(ReflectiveOperationException ignored){}},closed);
    }
    static boolean choose(Player p,OrionBridge plugin,String title,String label,List<String> options,Consumer<String> callback,Runnable closed){
        return form(p,plugin,"CustomForm",title,builder->invoke(builder,"dropdown",label,options),response->{try{int index=((Number)invoke(response,"asDropdown",0)).intValue();if(index>=0&&index<options.size())callback.accept(options.get(index));}catch(ReflectiveOperationException ignored){}},closed);
    }
    static boolean toggle(Player p,OrionBridge plugin,String title,String label,boolean initial,Consumer<Boolean> callback,Runnable closed){
        return form(p,plugin,"CustomForm",title,builder->invoke(builder,"toggle",label,initial),response->{try{callback.accept((Boolean)invoke(response,"asToggle",0));}catch(ReflectiveOperationException ignored){}},closed);
    }
    static boolean page(Player p,OrionBridge plugin,String title,String label,int current,int max,Consumer<Integer> callback,Runnable closed){
        return form(p,plugin,"CustomForm",title,builder->invoke(builder,"slider",label,1f,(float)max,1f,(float)current),response->{try{float value=((Number)invoke(response,"asSlider",0)).floatValue();if(Float.isFinite(value)&&value>=1&&value<=max&&value==Math.rint(value))callback.accept((int)value);}catch(ReflectiveOperationException ignored){}},closed);
    }
    static boolean input(Player p,OrionBridge plugin,String title,String hint,String placeholder,Consumer<String> callback,Runnable closed){return form(p,plugin,"CustomForm",title,builder->invoke(builder,"input",hint,placeholder,""),response->{try{Object value=invoke(response,"asInput",0);if(value instanceof String s)callback.accept(s);}catch(ReflectiveOperationException ignored){}},closed);}
    private interface Configure{void run(Object builder)throws ReflectiveOperationException;}
    private static boolean form(Player p,OrionBridge plugin,String type,String title,Configure config,Consumer<Object> cb,Runnable closed){try{Bridge b=bridge(p);if(b==null)return false;Class<?> form=Class.forName("org.geysermc.cumulus.form."+type,true,b.loader());Object builder=form.getMethod("builder").invoke(null);invoke(builder,"title",title);config.run(builder);InteractionGate gate=new InteractionGate();Consumer<Object> result=r->{if(gate.claim())plugin.main(()->cb.accept(r));};invoke(builder,"validResultHandler",result);try{invoke(builder,"closedOrInvalidResultHandler",(Runnable)()->{if(gate.claim())plugin.main(closed);});}catch(ReflectiveOperationException ignored){}Object built=invoke(builder,"build");Object sent=b.floodgate()?invoke(b.target(),"sendForm",b.player(),built):invoke(b.target(),"sendForm",built);return !(sent instanceof Boolean flag)||flag;}catch(ReflectiveOperationException|RuntimeException|LinkageError e){return false;}}
    private static final ClassValue<Map<String,List<Method>>> METHODS=new ClassValue<>(){protected Map<String,List<Method>> computeValue(Class<?> type){Map<String,List<Method>> methods=new HashMap<>();for(Method m:type.getMethods())methods.computeIfAbsent(m.getName(),k->new ArrayList<>()).add(m);return methods;}};
    private static Object invoke(Object target,String name,Object... args)throws ReflectiveOperationException{for(Method m:METHODS.get(target.getClass()).getOrDefault(name,List.of())){if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;Class<?>[] types=m.getParameterTypes();boolean compatible=true;for(int i=0;i<args.length;i++){Class<?> type=types[i];if(type==int.class)type=Integer.class;else if(type==float.class)type=Float.class;else if(type==boolean.class)type=Boolean.class;if(args[i]!=null&&!type.isInstance(args[i])){compatible=false;break;}}if(compatible){if(!m.canAccess(target)&&!m.trySetAccessible())continue;return m.invoke(target,args);}}throw new NoSuchMethodException(name);}
}
