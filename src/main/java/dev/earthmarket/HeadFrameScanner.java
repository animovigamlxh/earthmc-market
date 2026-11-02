package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Read-only: inspect item frames already synchronized to the client. No interaction packets. */
public final class HeadFrameScanner {
    private HeadFrameScanner() {}
    public record Head(BlockPos position, String displayName, String fingerprint, boolean specific,
                       int nameColor, boolean nameBold, String nameSource) {
        public Head(BlockPos position, String displayName, String fingerprint, boolean specific) {
            this(position, displayName, fingerprint, specific, 0xFF82E7B4, false, "legacy");
        }
    }
    private record Label(Component component, double distance) {}
    private static final int DEFAULT_COLOR = 0xFF82E7B4;
    private static final int[] MC_COLORS = {0x000000,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,
        0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};
    private static final String[] MC_NAMES = {"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray",
        "dark_gray","blue","green","aqua","red","light_purple","yellow","white"};
    private static int color(Component c) {
        if(c==null)return DEFAULT_COLOR;
        String value = c.getStyle().getColor()==null ? "" : c.getStyle().getColor().toString().toLowerCase(java.util.Locale.ROOT);
        try {
            if(value.startsWith("#")) return 0xFF000000 | Integer.parseInt(value.substring(1),16);
            for(int i=0;i<MC_NAMES.length;i++) if(MC_NAMES[i].equals(value))return 0xFF000000|MC_COLORS[i];
        } catch(NumberFormatException ignored) {}
        for(Component sibling:c.getSiblings()) {
            int found=color(sibling);
            if(found!=DEFAULT_COLOR)return found;
        }
        return DEFAULT_COLOR;
    }
    private static boolean bold(Component c) {
        if(c==null)return false;
        if(c.getStyle().isBold())return true;
        for(Component sibling:c.getSiblings())if(bold(sibling))return true;
        return false;
    }
    private static Component nearbyDisplayLabel(ItemFrame frame, List<Entity> entities) {
        Label best=null; boolean tied=false;
        for(Entity entity:entities) {
            if(entity==frame || !entity.getClass().getName().contains("TextDisplay"))continue;
            double dx=entity.getX()-frame.getX(),dy=entity.getY()-frame.getY(),dz=entity.getZ()-frame.getZ();
            if(Math.abs(dx)>0.95 || Math.abs(dz)>0.95 || dy < 0.12 || dy > 2.9)continue;
            try {
                Method m=entity.getClass().getMethod("getText");
                Object value=m.invoke(entity);
                if(!(value instanceof Component component) || component.getString().isBlank())continue;
                double distance=dx*dx+dz*dz+dy*dy*0.1;
                if(best==null || distance<best.distance()-0.06){best=new Label(component,distance);tied=false;}
                else if(Math.abs(distance-best.distance())<=0.06)tied=true;
            } catch(ReflectiveOperationException ignored) {}
        }
        return best!=null && !tied ? best.component() : null;
    }

    /** Loaded-chunk block heads. Scans block-entity maps, not every block position. */
    public static List<Head> nearbyBlocks(Minecraft mc) {
        List<Head> out = new ArrayList<>();
        if(mc==null || mc.player==null || mc.level==null) return out;
        int cx=mc.player.getBlockX()>>4, cz=mc.player.getBlockZ()>>4;
        for(int x=cx-3;x<=cx+3;x++) for(int z=cz-3;z<=cz+3;z++) {
            if(!mc.level.hasChunkAt(new BlockPos(x*16,mc.player.getBlockY(),z*16)))continue;
            for(var entry:mc.level.getChunk(x,z).getBlockEntities().entrySet()) {
                if(!(entry.getValue() instanceof SkullBlockEntity skull))continue;
                BlockPos pos=entry.getKey();
                if(pos==null || mc.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>48*48)continue;
                var block=mc.level.getBlockState(pos).getBlock();
                if(block!=Blocks.PLAYER_HEAD && block!=Blocks.PLAYER_WALL_HEAD)continue;
                Object profile=null;
                for(String method:new String[]{"getOwnerProfile","getProfile","getOwner"}) {
                    try { profile=skull.getClass().getMethod(method).invoke(skull); break; }
                    catch(ReflectiveOperationException ignored) {}
                }
                String owner=profileName(profile);
                String name=owner==null?"Player Head (placed)":owner+"'s Head";
                String identity=profile==null?"":profile.toString();
                out.add(new Head(pos,name,identity.isBlank()?"":"head:"+Integer.toHexString(identity.hashCode()),
                    !identity.isBlank(),DEFAULT_COLOR,false,"placed-block"));
            }
        }
        return out;
    }

    public static List<Head> nearby(Minecraft mc) {
        List<Head> result = new ArrayList<>();
        if (mc == null || mc.level == null || mc.player == null) return result;
        // Snapshot entities once; do not rescan the full entity set for each item frame.
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) entities.add(entity);
        for (Entity entity : entities) {
            if (!(entity instanceof ItemFrame frame)) continue;
            if (frame.distanceToSqr(mc.player) > 48.0 * 48.0) continue;
            ItemStack stack = frame.getItem();
            if (stack == null || stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) continue;
            BlockPos position = frame.blockPosition();
            Component itemLabel = stack.getHoverName();
            String visibleName = itemLabel.getString().strip();
            boolean hasName = stack.get(DataComponents.CUSTOM_NAME) != null;
            Object profile = stack.get(DataComponents.PROFILE);
            String profileName = profileName(profile);
            if ((!hasName || visibleName.isBlank() || visibleName.equalsIgnoreCase("Player Head") || visibleName.equals("玩家头颅"))
                && profileName != null) visibleName = profileName + "'s Head";
            if (visibleName.isBlank()) visibleName = "Player Head";
            // Component serialization is a fallback discriminator; not claimed to be a stable Minecraft ID.
            String profileIdentity = profile == null ? "" : profile.toString();
            String stableName = profileName == null ? "" : profileName;
            boolean specific = hasName || profile != null;
            String fingerprint = specific ? "head:" + Integer.toHexString((stableName + "|" + profileIdentity + "|" + visibleName).hashCode()) : "";
            int nameColor=color(itemLabel);
            boolean nameBold=bold(itemLabel);
            String source="item";
            Component displayLabel=nearbyDisplayLabel(frame,entities);
            if(displayLabel!=null) {
                String text=displayLabel.getString().strip();
                // Nearby floating words may be a decoration or a label for another item.
                // Use only a unique label directly aligned with this item frame.
                if(!text.isBlank() && text.length()<=120) {
                    visibleName=text;
                    nameColor=color(displayLabel);
                    nameBold=bold(displayLabel);
                    source="text-display";
                }
            }
            result.add(new Head(position, visibleName, fingerprint, specific, nameColor, nameBold, source));
        }
        return result;
    }

    private static String profileName(Object profile) {
        if (profile == null) return null;
        for (String method : new String[] {"name", "getName"}) {
            try {
                Method m = profile.getClass().getMethod(method);
                Object value = m.invoke(profile);
                if (value instanceof java.util.Optional<?> optional) value = optional.orElse(null);
                if (value instanceof String name && !name.isBlank()) return name;
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
}
