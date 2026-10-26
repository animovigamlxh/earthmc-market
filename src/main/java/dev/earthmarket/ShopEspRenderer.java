package dev.earthmarket;

import java.util.Optional;
import java.util.OptionalDouble;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** One-target through-wall outline using 26.2's extraction/draw pipeline. */
public final class ShopEspRenderer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("earthmc_market", "pipeline/shop_esp"))
            .withDepthStencilState(Optional.empty())
            .build());
    private static final StagedVertexBuffer BUFFER =
        new StagedVertexBuffer(() -> "EarthMC Market ESP", RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f WHITE = new Vector4f(1f,1f,1f,1f);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static BlockPos extractedTarget;
    private static BlockPos extractedTraceTarget;
    private ShopEspRenderer() {}

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(ShopEspRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ShopEspRenderer::render);
    }
    private static void extract(LevelExtractionContext context) {
        BlockPos active = ShopLocator.renderTarget();
        extractedTarget = active == null ? null : active.immutable();
        EarthMarketClient.DisplayQuote quote = NavigationGuide.traceTarget();
        Minecraft mc = Minecraft.getInstance();
        extractedTraceTarget = quote != null && mc.level != null
            && (quote.dimension() == null || quote.dimension().equals(mc.level.dimension().toString()))
            ? new BlockPos(quote.x(), quote.y(), quote.z()) : null;
    }
    private static void render(LevelRenderContext context) {
        BlockPos target = extractedTarget;
        BlockPos trace = extractedTraceTarget;
        if (target == null && trace == null) return;
        RenderPipeline pipeline = PIPELINE;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
            topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        PoseStack poses = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc matrix = poses.last().pose();
        VertexConsumer consumer = BUFFER.getVertexBuilder(draw);
        // Draw a Wurst-style tracer, even if the destination chunk is not loaded.
        // Truncate long tracers to avoid sending geometry far outside the view volume.
        if (trace != null) tracer(matrix, consumer, camera, trace);
        if (target != null) {
        float x=target.getX(), y=target.getY(), z=target.getZ();
        float width=0.035f;
        boolean blink=(System.currentTimeMillis()/300L)%2==0;
        float red=blink ? 0.05f : 1f;
        float green=blink ? 1f : 0.9f;
        float blue=1f;
        for(int iy=0;iy<2;iy++) for(int iz=0;iz<2;iz++)
            box(matrix,consumer,x,y+iy,z+iz,x+1,y+iy+width,z+iz+width,red,green,blue,1f);
        for(int ix=0;ix<2;ix++) for(int iz=0;iz<2;iz++)
            box(matrix,consumer,x+ix,y,z+iz,x+ix+width,y+1,z+iz+width,red,green,blue,1f);
        for(int ix=0;ix<2;ix++) for(int iy=0;iy<2;iy++)
            box(matrix,consumer,x+ix,y+iy,z,x+ix+width,y+iy+width,z+1,red,green,blue,1f);
        }
        poses.popPose();
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if(info != null) draw(info,pipeline);
        BUFFER.endFrame();
    }
    /**
     * Start the tracer on the player's sight line, NOT on the line from the
     * camera toward the shop. This keeps the tracer rooted near the crosshair
     * even when the shop is outside the view or directly behind the player.
     */
    private static void tracer(Matrix4fc mat, VertexConsumer b, Vec3 eye, BlockPos destination) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double dx = destination.getX() + .5 - eye.x;
        double dy = destination.getY() + .5 - eye.y;
        double dz = destination.getZ() + .5 - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 2.0 || !Double.isFinite(distance)) return;
        double ux = dx / distance, uy = dy / distance, uz = dz / distance;

        // Camera/player orientation: yaw=0 faces positive Z; pitch positive looks down.
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;
        // Fixed point on the sight line; the segment becomes visible even if
        // the target is off screen, exactly like an ESP tracer.
        double anchor = 0.65;
        float ax = (float) (eye.x + fx * anchor);
        float ay = (float) (eye.y + fy * anchor);
        float az = (float) (eye.z + fz * anchor);
        double end = Math.min(distance, 80.0);
        float bx = (float) (eye.x + ux * end);
        float by = (float) (eye.y + uy * end);
        float bz = (float) (eye.z + uz * end);

        double lx = bx - ax, ly = by - ay, lz = bz - az;
        double lineLen = Math.sqrt(lx*lx + ly*ly + lz*lz);
        if (lineLen < 0.1 || !Double.isFinite(lineLen)) return;
        lx /= lineLen; ly /= lineLen; lz /= lineLen;
        double horizontal = Math.hypot(lx, lz);
        double px = horizontal > 1e-5 ? -lz / horizontal : 1.0;
        double pz = horizontal > 1e-5 ? lx / horizontal : 0.0;
        double qx = ly * pz;
        double qy = lz * px - lx * pz;
        double qz = -ly * px;
        float w = 0.035f;
        float r = 0.13f, g = 1.0f, blue = 0.6f, alpha = 1f;
        tracerRibbon(mat,b,ax,ay,az,bx,by,bz,(float)px,0f,(float)pz,w,r,g,blue,alpha);
        tracerRibbon(mat,b,ax,ay,az,bx,by,bz,(float)qx,(float)qy,(float)qz,w,r,g,blue,alpha);
    }

    /** Draw both sides so back-face culling doesn't make the line disappear. */
    private static void tracerRibbon(Matrix4fc m, VertexConsumer b,
                                     float ax,float ay,float az,float bx,float by,float bz,
                                     float px,float py,float pz,float width,
                                     float r,float g,float blue,float alpha) {
        float ox=px*width, oy=py*width, oz=pz*width;
        quad(m,b,ax+ox,ay+oy,az+oz,bx+ox,by+oy,bz+oz,
             bx-ox,by-oy,bz-oz,ax-ox,ay-oy,az-oz,r,g,blue,alpha);
        quad(m,b,ax-ox,ay-oy,az-oz,bx-ox,by-oy,bz-oz,
             bx+ox,by+oy,bz+oz,ax+ox,ay+oy,az+oz,r,g,blue,alpha);
    }
    private static void box(Matrix4fc mat, VertexConsumer b, float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float bl,float a) {
        quad(mat,b,x0,y0,z1,x1,y0,z1,x1,y1,z1,x0,y1,z1,r,g,bl,a);
        quad(mat,b,x1,y0,z0,x0,y0,z0,x0,y1,z0,x1,y1,z0,r,g,bl,a);
        quad(mat,b,x0,y0,z0,x0,y0,z1,x0,y1,z1,x0,y1,z0,r,g,bl,a);
        quad(mat,b,x1,y0,z1,x1,y0,z0,x1,y1,z0,x1,y1,z1,r,g,bl,a);
        quad(mat,b,x0,y1,z1,x1,y1,z1,x1,y1,z0,x0,y1,z0,r,g,bl,a);
        quad(mat,b,x0,y0,z0,x1,y0,z0,x1,y0,z1,x0,y0,z1,r,g,bl,a);
    }
    private static void quad(Matrix4fc m,VertexConsumer b,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float blue,float alpha) {
        b.addVertex(m,ax,ay,az).setColor(r,g,blue,alpha);
        b.addVertex(m,bx,by,bz).setColor(r,g,blue,alpha);
        b.addVertex(m,cx,cy,cz).setColor(r,g,blue,alpha);
        b.addVertex(m,dx,dy,dz).setColor(r,g,blue,alpha);
    }
    private static void draw(StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice transforms=RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrixCopy(), WHITE, OFFSET, TEXTURE_MATRIX);
        RenderTarget target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView color=target.getColorTextureView();
        if(color == null) return;
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "EarthMC Market shop ESP",color,Optional.empty(),
                target.getDepthTextureView(),OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",transforms);
            pass.setVertexBuffer(0,info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(),info.indexType());
            pass.drawIndexed(info.indexCount(),1,info.firstIndex(),info.baseVertex(),0);
        }
    }
    public static void close() { BUFFER.close(); }
}
