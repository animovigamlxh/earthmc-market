package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.component.DataComponents;

/** On-demand diagnostics; never dumps the actual skin texture or private profile UUID. */
public final class HeadDebug {
    private HeadDebug() {}
    private static void say(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[EM Market] " + text));
    }
    public static void aimed(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null || mc.hitResult == null) return;
        HitResult hit=mc.hitResult;
        if(hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ItemFrame frame) {
            var stack=frame.getItem();
            say(mc,"HeadDebug frame @ " + frame.blockPosition().toShortString());
            say(mc,"Item="+stack.getItem()+" name="+stack.getHoverName().getString());
            Object profile=stack.get(DataComponents.PROFILE);
            say(mc,"Profile present="+(profile!=null)+" profileType="+type(profile));
            say(mc,"Fingerprint="+signature(mc,frame.blockPosition()));
            return;
        }
        if(!(hit instanceof BlockHitResult blockHit)) {
            say(mc,"Aim at a placed head block or an item frame and retry."); return;
        }
        BlockPos pos=blockHit.getBlockPos();
        var state=mc.level.getBlockState(pos);
        say(mc,"HeadDebug block="+state.getBlock()+" pos="+pos.toShortString());
        var be=mc.level.getBlockEntity(pos);
        say(mc,"BlockEntity="+type(be)+" available="+(be!=null));
        if(be instanceof SkullBlockEntity) {
            // Reflective accessor report helps confirm exactly what 26.2 exposes at runtime.
            for (String method : new String[]{"getOwnerProfile","getProfile","getOwner"}) {
                try {
                    var m=be.getClass().getMethod(method);
                    Object v=m.invoke(be);
                    say(mc,method+"(): "+(v!=null?"present "+type(v):"null"));
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    say(mc,method+"(): unavailable");
                }
            }
        }
        if(be instanceof SkullBlockEntity) {
            for(Class<?> type=be.getClass();type!=null && type!=Object.class;type=type.getSuperclass()) {
                for(var f:type.getDeclaredFields()) {
                    String n=f.getName().toLowerCase(java.util.Locale.ROOT);
                    if(java.lang.reflect.Modifier.isStatic(f.getModifiers()) || (!n.contains("owner") && !n.contains("profile") && !f.getType().getSimpleName().contains("Profile")))continue;
                    try { f.setAccessible(true);Object v=f.get(be);
                        say(mc,"Profile field="+f.getName()+" type="+type(v)+" present="+(v!=null));
                    } catch(ReflectiveOperationException | RuntimeException ignored) {say(mc,"Profile field inaccessible");}
                }
            }
        }
        HeadFrameScanner.Head scanned=HeadFrameScanner.blockAt(mc,pos);
        if(scanned==null) {say(mc,"Scanner: not a vanilla PLAYER_HEAD/PLAYER_WALL_HEAD");return;}
        say(mc,"Scanner name="+scanned.displayName()+" skinAvailable="+scanned.fingerprint().startsWith("skin:"));
        say(mc,"Fingerprint="+(scanned.fingerprint().isBlank()?"unknown":shortHash(scanned.fingerprint())));
        String dim=mc.level.dimension().toString();
        HeadCatalogue.SavedHead saved=HeadCatalogue.get(dim,pos);
        say(mc,"Saved="+(saved!=null)+" town="+(saved==null?"?":saved.town)+" nation="+(saved==null?"?":saved.nation));
    }
    private static String signature(Minecraft mc,BlockPos pos) {
        var saved=HeadCatalogue.get(mc.level.dimension().toString(),pos);
        return saved==null||saved.fingerprint==null||saved.fingerprint.isBlank()?"unknown":shortHash(saved.fingerprint);
    }
    private static String shortHash(String value) {return value.length()>22?value.substring(0,22)+"...":value;}
    private static String type(Object obj) {return obj==null?"none":obj.getClass().getSimpleName();}
}
