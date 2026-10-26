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
    private ShopEspRenderer() {}

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(ShopEspRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ShopEspRenderer::render);
    }
    private static void extract(LevelExtractionContext context) {
        BlockPos active = ShopLocator.renderTarget();
        extractedTarget = active == null ? null : active.immutable();
    }
    private static void render(LevelRenderContext context) {
        BlockPos target = extractedTarget;
        if (target == null) return;
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
        poses.popPose();
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if(info != null) draw(info,pipeline);
        BUFFER.endFrame();
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
