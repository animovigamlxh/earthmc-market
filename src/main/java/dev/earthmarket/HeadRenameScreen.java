package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Manual label is kept separately from the server-provided skull profile. */
public final class HeadRenameScreen extends Screen {
    private final Screen previous;
    private final HeadCatalogue.SavedHead head;
    private final boolean askOnly;
    private EditBox input;
    public HeadRenameScreen(Screen previous,HeadCatalogue.SavedHead head,boolean askOnly) {
        super(Component.literal("Rename head (local only)"));
        this.previous=previous;this.head=head;this.askOnly=askOnly;
    }
    @Override protected void init() {
        int x=width/2,y=height/2;
        input=new EditBox(font,x-145,y-12,290,20,Component.literal("Custom head label"));
        input.setMaxLength(80);
        input.setValue(head.manualName==null?head.name:head.manualName);
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("Save"),b -> {
            HeadCatalogue.rename(head,input.getValue());
            minecraft.gui.setScreen(new HeadShopScreen(null,askOnly));
        }).bounds(x-145,y+17,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"),b -> {
            HeadCatalogue.rename(head,"");
            minecraft.gui.setScreen(new HeadShopScreen(null,askOnly));
        }).bounds(x-45,y+17,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b -> minecraft.gui.setScreen(previous))
            .bounds(x+55,y+17,90,20).build());
        setInitialFocus(input);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractRenderState(g,mx,my,delta);
        g.text(font,"Local label for head at "+head.x+","+head.y+","+head.z,width/2-145,height/2-35,0xFFFFFFFF,false);
        g.text(font,"Does not change the head on the server.",width/2-145,height/2+47,0xFFAAAAAA,false);
    }
    @Override public void onClose(){minecraft.gui.setScreen(previous);}
}
