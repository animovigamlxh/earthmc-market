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
                Object profile=skullProfile(skull);
                String owner=profileName(profile);
                String identity=profileIdentity(profile);
                String name=owner==null || owner.equalsIgnoreCase("HeadDatabase")?"Player Head (placed)":owner+"'s Head";
                out.add(new Head(pos,name,identity.isBlank()?"":"skin:"+sha256(identity),
                    !identity.isBlank(),DEFAULT_COLOR,false,"placed-block"));
            }
        }
        return out;
    }

    /** Fallback for player heads whose block entities have not been advertised in the
     * chunk's block-entity map. Only called on a small, per-tick budget. */
    public static Head blockAt(Minecraft mc, BlockPos pos) {
        if(mc==null || mc.level==null || !mc.level.hasChunkAt(pos))return null;
        var state=mc.level.getBlockState(pos);
        if(!state.is(Blocks.PLAYER_HEAD) && !state.is(Blocks.PLAYER_WALL_HEAD))return null;
        Object profile=null;
        var entity=mc.level.getBlockEntity(pos);
        if(entity instanceof SkullBlockEntity skull) profile=skullProfile(skull);
        String owner=profileName(profile);
        String name=owner==null || owner.equalsIgnoreCase("HeadDatabase")?"Player Head (placed)":owner+"'s Head";
        String identity=profileIdentity(profile);
        String fingerprint=identity.isBlank()?"":"skin:"+sha256(identity);
        return new Head(pos,name,fingerprint,!identity.isBlank(),DEFAULT_COLOR,false,"placed-block");
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
            String profileIdentity = profileIdentity(profile);
            String stableName = profileName == null ? "" : profileName;
            boolean specific = !profileIdentity.isBlank() || (!stableName.isBlank() && !stableName.equalsIgnoreCase("HeadDatabase"));
            String fingerprint = !profileIdentity.isBlank()?"skin:"+sha256(profileIdentity):(!stableName.isBlank() && !stableName.equalsIgnoreCase("HeadDatabase")?"owner:"+stableName.toLowerCase(java.util.Locale.ROOT):"");
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

    /** Minecraft 26.x stores placed-head skins in a ResolvableProfile on the block entity.
     * The profile is frequently nested; inspecting only a getOwnerProfile() method (which
     * can be static) or only ResolvableProfile.properties() loses the actual skin data. */
    private static Object skullProfile(SkullBlockEntity skull) {
        // Nonstatic instance accessors only: never invoke static profile factories on a skull.
        for(String method:new String[]{"getOwner", "getProfile", "getOwnerProfile"}) {
            Object value=invokeNoArg(skull,method);
            if(value!=null && isProfileLike(value))return value;
        }
        for(Class<?> type=skull.getClass();type!=null && type!=Object.class;type=type.getSuperclass()) {
            for(var field:type.getDeclaredFields()) {
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
                String name=field.getName().toLowerCase(java.util.Locale.ROOT);
                String fieldType=field.getType().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                if(!(name.contains("owner")||name.contains("profile")||fieldType.contains("profile")))continue;
                try {
                    field.setAccessible(true);
                    Object value=unwrap(field.get(skull));
                    if(value!=null && isProfileLike(value))return value;
                } catch(ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return null;
    }
    private static boolean isProfileLike(Object object) {
        if(object==null)return false;
        String n=object.getClass().getName();
        return n.contains("Profile") && !n.contains("ProfileResolver");
    }
    private static Object unwrap(Object value) {
        if(value instanceof java.util.Optional<?> optional)return optional.orElse(null);
        return value;
    }
    private static Object invokeNoArg(Object object,String name) {
        if(object==null)return null;
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try {
                var m=type.getDeclaredMethod(name);
                if(java.lang.reflect.Modifier.isStatic(m.getModifiers()))continue;
                m.setAccessible(true);
                return unwrap(m.invoke(object));
            } catch(ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }
    private static Object gameProfile(Object profile) {
        if(profile==null)return null;
        // ResolvableProfile (26.x) wraps com.mojang.authlib.GameProfile.
        for(String method:new String[]{"partialProfile","getGameProfile","profile","getProfile"}) {
            Object inner=invokeNoArg(profile,method);
            if(inner!=null && inner!=profile && isProfileLike(inner))return inner;
        }
        for(Class<?> type=profile.getClass();type!=null && type!=Object.class;type=type.getSuperclass()) {
            for(var field:type.getDeclaredFields()) {
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
                if(!field.getType().getName().equals("com.mojang.authlib.GameProfile"))continue;
                try {field.setAccessible(true);Object inner=field.get(profile);if(inner!=null)return inner;}
                catch(ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return profile;
    }
    /** Texture fingerprint first. A shared HeadDatabase owner/UUID must NOT identify a skin. */
    private static String profileIdentity(Object profile) {
        if(profile==null)return "";
        Object actual=gameProfile(profile);
        String texture=textureProperty(actual);
        if(texture.isBlank() && actual!=profile)texture=textureProperty(profile);
        if(!texture.isBlank())return "texture:"+texture;
        // 26.x ResolvableProfile may instead contain a per-skin override (player skin patch).
        Object patch=invokeNoArg(profile,"skinPatch");
        if(patch==null)patch=invokeNoArg(profile,"getOverride");
        if(patch!=null) {
            for(String method:new String[]{"body","texture","skin","getBody","getTexture"}) {
                Object v=invokeNoArg(patch,method);
                if(v!=null && !v.toString().isBlank() && !v.toString().equals("Optional.empty"))
                    return "skin-patch:"+v;
            }
        }
        String name=profileName(profile);
        if(name==null || name.equalsIgnoreCase("HeadDatabase"))return "";
        Object uuid=invokeNoArg(actual,"id");
        if(uuid==null)uuid=invokeNoArg(actual,"getId");
        if(uuid instanceof java.util.UUID)return "uuid:"+uuid;
        return "name:"+name.toLowerCase(java.util.Locale.ROOT);
    }
    private static String textureProperty(Object profile) {
        if(profile==null)return "";
        Object props=invokeNoArg(profile,"properties");
        if(props==null)props=invokeNoArg(profile,"getProperties");
        if(props==null)return "";
        Object textures=invokeOneArg(props,"get","textures");
        String value=textureValue(textures);
        if(!value.isBlank())return value;
        return textureValue(props);
    }
    private static Object invokeOneArg(Object object,String method,Object argument) {
        if(object==null)return null;
        try {return object.getClass().getMethod(method,String.class).invoke(object,argument);}
        catch(ReflectiveOperationException | RuntimeException ignored) {return null;}
    }
    private static String textureValue(Object prop) {
        if(prop==null)return "";
        if(prop instanceof Iterable<?> iterable) {
            for(Object element:iterable) {String value=textureValue(element);if(!value.isBlank())return value;}
            return "";
        }
        // Guava property collection may be a map: iterate only textures properties.
        if(prop instanceof java.util.Map<?,?> map) {
            Object textures=map.get("textures");
            return textureValue(textures);
        }
        Object name=invokeNoArg(prop,"name");
        if(name==null)name=invokeNoArg(prop,"getName");
        if(name!=null && !"textures".equalsIgnoreCase(name.toString()))return "";
        Object value=invokeNoArg(prop,"value");
        if(value==null)value=invokeNoArg(prop,"getValue");
        if(value instanceof String text && !text.isBlank())return text;
        return "";
    }
    private static String sha256(String value) {
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }

    private static String profileName(Object profile) {
        if(profile==null)return null;
        for(Object source:new Object[]{profile,gameProfile(profile)}) {
            if(source==null)continue;
            for(String method:new String[]{"name","getName"}) {
                Object v=invokeNoArg(source,method);
                if(v instanceof String name && !name.isBlank())return name;
            }
        }
        return null;
    }
}
