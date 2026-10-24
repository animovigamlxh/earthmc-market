package dev.earthmarket;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** Depth-independent chest tracker style outline, drawn on the render thread. */
public final class ShopEspRenderer {
    private static final RenderPipeline ESP_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("earthmc_market", "pipeline/shop_esp"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build());
    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    private ShopEspRenderer() {}

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ShopEspRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        BlockPos target = ShopLocator.renderTarget();
        if (target == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.hasChunkAt(target)) return;
        PoseStack pose = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        BufferBuilder buffer = new BufferBuilder(ALLOCATOR, ESP_PIPELINE.getVertexFormatMode(), ESP_PIPELINE.getVertexFormat());
        float x = target.getX(), y = target.getY(), z = target.getZ();
        // Each edge is a thin rectangular prism; 12 edges, visible even through blocks.
        float width = 0.025f;
        boolean blink = (System.currentTimeMillis() / 280L) % 2 == 0;
        float red = blink ? 0.10f : 1.0f, green = blink ? 1.0f : 0.8f;
        float blue = 0.95f, alpha = 0.94f;
        Matrix4fc matrix = pose.last().pose();
        for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++)
            box(matrix, buffer, x, y + iy, z + iz, x + 1, y + iy + width, z + iz + width, red, green, blue, alpha);
        for (int ix = 0; ix < 2; ix++) for (int iz = 0; iz < 2; iz++)
            box(matrix, buffer, x + ix, y, z + iz, x + ix + width, y + 1, z + iz + width, red, green, blue, alpha);
        for (int ix = 0; ix < 2; ix++) for (int iy = 0; iy < 2; iy++)
            box(matrix, buffer, x + ix, y + iy, z, x + ix + width, y + iy + width, z + 1, red, green, blue, alpha);
        pose.popPose();
        MeshData mesh = buffer.buildOrThrow();
        try { draw(mc, mesh); } finally { mesh.close(); }
    }

    private static void box(Matrix4fc mat, BufferBuilder b, float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float bl,float a) {
        quad(mat,b,x0,y0,z1,x1,y0,z1,x1,y1,z1,x0,y1,z1,r,g,bl,a);
        quad(mat,b,x1,y0,z0,x0,y0,z0,x0,y1,z0,x1,y1,z0,r,g,bl,a);
        quad(mat,b,x0,y0,z0,x0,y0,z1,x0,y1,z1,x0,y1,z0,r,g,bl,a);
        quad(mat,b,x1,y0,z1,x1,y0,z0,x1,y1,z0,x1,y1,z1,r,g,bl,a);
        quad(mat,b,x0,y1,z1,x1,y1,z1,x1,y1,z0,x0,y1,z0,r,g,bl,a);
        quad(mat,b,x0,y0,z0,x1,y0,z0,x1,y0,z1,x0,y0,z1,r,g,bl,a);
    }
    private static void quad(Matrix4fc m,BufferBuilder b,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float blue,float alpha) {
        b.addVertex(m,ax,ay,az).setColor(r,g,blue,alpha);
        b.addVertex(m,bx,by,bz).setColor(r,g,blue,alpha);
        b.addVertex(m,cx,cy,cz).setColor(r,g,blue,alpha);
        b.addVertex(m,dx,dy,dz).setColor(r,g,blue,alpha);
    }
    private static void draw(Minecraft mc, MeshData mesh) {
        MeshData.DrawState state = mesh.drawState();
        VertexFormat format = state.format();
        int size = state.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> "EarthMC shop ESP", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mapped = encoder.mapBuffer(vertexBuffer.currentBuffer().slice(0,mesh.vertexBuffer().remaining()),false,true)) {
            MemoryUtil.memCopy(mesh.vertexBuffer(),mapped.data());
        }
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (ESP_PIPELINE.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            mesh.sortQuads(ALLOCATOR,RenderSystem.getProjectionType().vertexSorting());
            indices = ESP_PIPELINE.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
            indexType = mesh.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(ESP_PIPELINE.getVertexFormatMode());
            indices = sequential.getBuffer(state.indexCount());
            indexType = sequential.type();
        }
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(),WHITE,ZERO,IDENTITY);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "EarthMC shop ESP render", mc.getMainRenderTarget().getColorTextureView(),OptionalInt.empty(),
                mc.getMainRenderTarget().getDepthTextureView(),OptionalDouble.empty())) {
            pass.setPipeline(ESP_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0,vertexBuffer.currentBuffer());
            pass.setIndexBuffer(indices,indexType);
            pass.drawIndexed(0,0,state.indexCount(),1);
        }
        vertexBuffer.rotate();
    }
}
