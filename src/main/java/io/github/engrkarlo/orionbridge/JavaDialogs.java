package io.github.engrkarlo.orionbridge;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

final class JavaDialogs {
    record Button(Component name,Component tooltip){}
    private static ClickCallback.Options options(){return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(60)).build();}
    static boolean show(Player p,OrionBridge plugin,Component title,Component body,ItemStack preview,List<Button> entries,int columns,int width,int footer,Consumer<Integer> callback){
        try{
            List<ActionButton> actions=new ArrayList<>();InteractionGate gate=new InteractionGate();
            for(int i=0;i<entries.size();i++){int index=i;Button b=entries.get(i);
                actions.add(ActionButton.create(b.name(),b.tooltip(),width,DialogAction.customClick((view,audience)->{
                    if(audience instanceof Player viewer&&viewer.getUniqueId().equals(p.getUniqueId())&&gate.claim())plugin.main(()->callback.accept(index));
                },options())));
            }
            if(actions.isEmpty())return false;
            ActionButton exit=footer>=0&&footer<actions.size()&&actions.size()>1?actions.remove(footer):null;
            List<DialogBody> bodies=new ArrayList<>();if(preview!=null)bodies.add(DialogBody.item(preview,null,false,true,24,24));bodies.add(DialogBody.plainMessage(body));
            p.closeInventory();
            p.showDialog(Dialog.create(builder->builder.empty()
                .base(DialogBase.builder(title).body(bodies).build())
                .type(DialogType.multiAction(actions,exit,columns))));
            return true;
        }catch(RuntimeException|LinkageError e){plugin.getLogger().warning("Java dialog unavailable: "+e.getClass().getSimpleName());return false;}
    }
    static boolean input(Player p,OrionBridge plugin,Component title,Component label,Component submit,Component cancel,int width,Consumer<String> callback){return input(p,plugin,title,label,submit,cancel,width,100,callback);}
    static boolean input(Player p,OrionBridge plugin,Component title,Component label,Component submit,Component cancel,int width,int buttonWidth,Consumer<String> callback){
        try{
            var input=DialogInput.text("answer",width,label,true,"",255,null);
            var yes=ActionButton.create(submit,null,buttonWidth,DialogAction.customClick((view,audience)->{
                if(audience instanceof Player viewer&&viewer.getUniqueId().equals(p.getUniqueId())&&view!=null){String answer=view.getText("answer");if(answer!=null)plugin.main(()->callback.accept(answer));}
            },options()));
            p.closeInventory();p.showDialog(Dialog.create(builder->builder.empty()
                .base(DialogBase.builder(title).inputs(List.of(input)).build())
                .type(DialogType.confirmation(yes,ActionButton.create(cancel,null,buttonWidth,null)))));
            return true;
        }catch(RuntimeException|LinkageError e){plugin.getLogger().warning("Java input dialog unavailable: "+e.getClass().getSimpleName());return false;}
    }
}
