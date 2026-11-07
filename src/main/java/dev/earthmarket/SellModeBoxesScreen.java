package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** View every remembered box containing this item, including abandoned towns. */
final class SellModeBoxesScreen extends Screen {
    private static final int PAGE_SIZE=9;
    private final Screen previous;
    private final SellModeData.Offer offer;
    private int page=0;
    private Button prev,next;
    SellModeBoxesScreen(Screen previous,SellModeData.Offer offer) {
        super(Component.literal("Stored locations")); this.previous=previous;this.offer=offer;
    }
    @Override protected void init() {
        int x=Math.max(5,(width-500)/2),y=Math.max(5,(height-300)/2);
        prev=addRenderableWidget(Button.builder(Component.literal("< Prev"),b->{page--;refresh();})
            .bounds(x+10,y+269,90,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("Next >"),b->{page++;refresh();})
            .bounds(x+395,y+269,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->minecraft.gui.setScreen(previous))
            .bounds(x+205,y+269,90,20).build());
        refresh();
    }
    private void refresh(){prev.active=page>0;next.active=(page+1)*PAGE_SIZE<offer.boxes().size();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        int x=Math.max(5,(width-500)/2),y=Math.max(5,(height-300)/2);
        g.fill(x,y,x+500,y+300,0xF0101923);g.outline(x,y,500,300,0xFF6492AA);
        super.extractRenderState(g,mx,my,delta);
        g.text(font,"Chest Tracker locations - "+offer.name(),x+12,y+10,0xFFFFFFFF,true);
        g.text(font,"Historical snapshots, ownership unrestricted; open chest to update actual stock.",x+12,y+26,0xFFFFC384,false);
        List<SellModeData.Box> boxes=offer.boxes();
        for(int i=0;i<PAGE_SIZE;i++) {
            int idx=page*PAGE_SIZE+i;if(idx>=boxes.size())break;
            var b=boxes.get(idx);int ry=y+48+i*23;
            g.fill(x+8,ry-3,x+492,ry+17,i%2==0?0x5535475C:0x442A394C);
            var p=b.position();
            String when=b.observed()==null?"unknown":DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(b.observed());
            g.text(font,b.quantity()+"x  "+p.getX()+", "+p.getY()+", "+p.getZ()+"  "+b.dimension()+"  |  "+when,
                x+13,ry,0xFFE1E8F1,false);
        }
        g.text(font,"Page "+(page+1)+" / "+Math.max(1,(boxes.size()+PAGE_SIZE-1)/PAGE_SIZE),x+112,y+275,0xFFC4D3E2,false);
    }
    @Override public void onClose(){minecraft.gui.setScreen(previous);}
}
