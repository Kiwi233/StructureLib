package com.gtnewhorizon.structurelib;

import static com.gtnewhorizon.structurelib.StructureLib.RANDOM;

import com.gtnewhorizon.structurelib.entity.fx.WeightlessParticleFX;
import com.gtnewhorizon.structurelib.net.SetChannelDataMessage;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import org.lwjgl.opengl.GL11;

public class ClientProxy extends CommonProxy {

    private static final short[] RGBA_NO_TINT = {255, 255, 255, 255};
    private static final short[] RGBA_RED_TINT = {255, 128, 128, 0};
    private static final Map<HintParticleInfo, HintGroup> allHints = new HashMap<>();
    /**
     * All batches of hints.
     */
    private static final List<HintGroup> allGroups = new ArrayList<>();

    public static int HOLOGRAM_LIFETIME = 200;
    /**
     * Current batch of hints. Belongs to the same logical multiblock.
     */
    private static HintGroup currentHints;
    /**
     * Collection of all hints. Sorted from farthest (index 0) to nearest (index size - 1)
     * <p>
     * 10k should be more than enough, if not a bit wasteful
     */
    private static final List<HintParticleInfo> allHintsForRender = new ArrayList<>(10000);
    /**
     * If the diff to current player position is too great sort the allHints.
     * Initial value is very far off below y=0 but not huge enough to make a NaN.
     * <p>
     * We are using this supposedly immutable object as a mutable object here.
     * So be aware.
     */
    private static final Vec3 lastPlayerPos = Vec3.createVectorHelper(0, -1e30, 0);
    /**
     * if true rebuild the allHints list from all hintOwners (and sort it)
     */
    private static boolean allHintsDirty = false;
    /**
     * counter keeping track of how many renderThrough hint particle we have. enable optimization if no renderThrough
     */
    private static int renderThrough;
    /**
     * A throttle map for local player. Integrated server will use the throttle map in super class instead.
     */
    private static final Map<Object, Long> localThrottleMap = new HashMap<>();

    @Override
    public void hintParticleTinted(World w, int x, int y, int z, IIcon[] icons, short[] RGBa) {
        ensureHinting();
        HintParticleInfo info = new HintParticleInfo(w, x, y, z, icons, RGBa);

        // check and remove colliding holograms
        if (ConfigurationHandler.INSTANCE.isRemoveCollidingHologram()) {
            HintGroup dupe = allHints.get(info);
            if (dupe != null && dupe != currentHints) {
                allGroups.remove(dupe);
                removeGroup(dupe);
            }
        }
        allHints.put(info, currentHints);
        currentHints.getHints().add(info);
        allHintsDirty = true;

        EntityFX particle = new WeightlessParticleFX(
                w,
                x + RANDOM.nextFloat() * 0.5F,
                y + RANDOM.nextFloat() * 0.5F,
                z + RANDOM.nextFloat() * 0.5F,
                0,
                0,
                0);
        particle.setRBGColorF(0, 0.6F * RANDOM.nextFloat(), 0.8f);
        Minecraft.getMinecraft().effectRenderer.addEffect(particle);
    }

    @Override
    public void hintParticleTinted(World w, int x, int y, int z, Block block, int meta, short[] RGBa) {
        hintParticleTinted(w, x, y, z, createIIconFromBlock(block, meta), RGBa);
    }

    @Override
    public void hintParticle(World w, int x, int y, int z, IIcon[] icons) {
        hintParticleTinted(w, x, y, z, icons, RGBA_NO_TINT);
    }

    @Override
    public void hintParticle(World w, int x, int y, int z, Block block, int meta) {
        hintParticleTinted(w, x, y, z, createIIconFromBlock(block, meta), RGBA_NO_TINT);
    }

    @Override
    public boolean updateHintParticleTint(EntityPlayer player, World w, int x, int y, int z, short[] rgBa) {
        if (player instanceof EntityPlayerMP) return super.updateHintParticleTint(player, w, x, y, z, rgBa);
        if (player != getCurrentPlayer()) return false; // how?
        HintParticleInfo hint = getHintParticleInfo(w, x, y, z);
        if (hint == null) {
            return false;
        }
        hint.setTint(rgBa);
        return true;
    }

    private static HintParticleInfo getHintParticleInfo(World w, int x, int y, int z) {
        HintParticleInfo info = new HintParticleInfo(w, x, y, z, null, null);
        HintGroup existing = allHints.get(info);
        if (existing != null) {
            for (HintParticleInfo hint : existing.getHints()) {
                if (hint.equals(info)) {
                    return hint;
                }
            }
        }
        return null;
    }

    @Override
    public boolean markHintParticleError(EntityPlayer player, World w, int x, int y, int z) {
        if (player instanceof EntityPlayerMP) return super.markHintParticleError(player, w, x, y, z);
        if (player != getCurrentPlayer()) return false; // how?
        HintParticleInfo hint = getHintParticleInfo(w, x, y, z);
        if (hint == null) {
            return false;
        }
        hint.setTint(RGBA_RED_TINT);
        hint.setRenderThrough();
        return true;
    }

    @Override
    public void addThrottledChat(
            Object throttleKey,
            EntityPlayer player,
            IChatComponent text,
            short intervalRequired,
            boolean forceUpdateLastSend) {
        if (player instanceof EntityPlayerMP)
            super.addThrottledChat(throttleKey, player, text, intervalRequired, forceUpdateLastSend);
        else if (player != null)
            addThrottledChat(throttleKey, player, text, intervalRequired, forceUpdateLastSend, localThrottleMap);
    }

    static void removeGroup(HintGroup group) {
        for (HintParticleInfo hintParticleInfo : group.getHints()) {
            allHints.remove(hintParticleInfo);
            if (hintParticleInfo.renderThrough) renderThrough--;
        }
        allHintsDirty = true;
    }

    private static IIcon[] createIIconFromBlock(Block block, int meta) {
        IIcon[] ret = new IIcon[6];
        for (int i = 0; i < 6; i++) {
            ret[i] = block.getIcon(i, meta);
        }
        return ret;
    }

    @Override
    public void addClientSideChatMessages(String... messages) {
        GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
        for (String s : messages) {
            chat.printChatMessage(new ChatComponentText(s));
        }
    }

    @Override
    public EntityPlayer getCurrentPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }

    @Override
    public boolean isCurrentPlayer(EntityPlayer player) {
        return player == Minecraft.getMinecraft().thePlayer;
    }

    @Override
    public void startHinting(World w) {
        if (!w.isRemote) return;
        if (currentHints != null) endHinting(w);
        currentHints = new HintGroup();
    }

    private void ensureHinting() {
        if (currentHints == null) currentHints = new HintGroup();
    }

    @Override
    public void endHinting(World w) {
        if (!w.isRemote || currentHints == null) return;
        while (!allGroups.isEmpty() && allGroups.size() >= ConfigurationHandler.INSTANCE.getMaxCoexistingHologram()) {
            allGroups.remove(0);
        }
        if (!currentHints.getHints().isEmpty()) allGroups.add(currentHints);
        // we use the player existence time here as some worlds don't really advance the time ticker
        currentHints.setCreationTime(getCurrentPlayer().ticksExisted);
        currentHints = null;
    }

    @Override
    public long getOverworldTime() {
        // there is no overworld, better just hope current world time is ok...
        return Minecraft.getMinecraft().theWorld.getTotalWorldTime();
    }

    @Override
    public void uploadChannels(ItemStack trigger) {
        StructureLib.net.sendToServer(new SetChannelDataMessage(trigger));
    }

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(new FMLEventHandler());
        MinecraftForge.EVENT_BUS.register(new ForgeEventHandler());
    }

    static void markTextureUsed(IIcon icon) {
        if (StructureLib.COMPAT instanceof IStructureCompat)
            ((IStructureCompat) StructureLib.COMPAT).markTextureUsed(icon);
    }

    private static class HintGroup {
        private final List<HintParticleInfo> hints = new LinkedList<>();
        private int creationTime = -1;

        public List<HintParticleInfo> getHints() {
            return hints;
        }

        public void setCreationTime(int creationTime) {
            this.creationTime = creationTime;
        }

        public int getCreationTime() {
            return creationTime;
        }
    }

    private static class HintParticleInfo {
        private final World w;
        // these are the block coordinate for e.g. w.getBlock()
        private final int x, y, z;
        // these are the lower bounds for rendering. the upper bound would be X + size
        // currently size is fixed to be 0.5
        // it is not made into a constants because this will allow for easy changing during debug
        private final double X, Y, Z;
        private final IIcon[] icons;
        private short[] tint;
        private boolean renderThrough;

        private final long creationTime = System.currentTimeMillis(); // use tick time instead maybe

        public HintParticleInfo(World w, int x, int y, int z, IIcon[] icons, short[] tint) {
            this.w = w;
            this.x = x;
            this.y = y;
            this.z = z;
            X = x + 0.25;
            Y = y + 0.25;
            Z = z + 0.25;
            this.icons = icons;
            this.tint = tint;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HintParticleInfo)) return false;

            HintParticleInfo that = (HintParticleInfo) o;

            return x == that.x && y == that.y && z == that.z;
        }

        public void setTint(short[] tint) {
            this.tint = tint;
        }

        public void setRenderThrough() {
            if (!this.renderThrough) {
                ClientProxy.renderThrough += 1;
                allHintsDirty = true;
            }
            this.renderThrough = true;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }

        public boolean isInFrustrum(Frustrum frustrum) {
            return frustrum.isBoxInFrustum(X, Y, Z, X + 0.5, Y + 0.5, Z + 0.5);
        }

        public void draw(Tessellator tes, double eyeX, double eyeY, double eyeZ) {
            double size = 0.5;

            int brightness = w.blockExists(x, 0, z) ? w.getLightBrightnessForSkyBlocks(x, y, z, 0) : 0;
            tes.setBrightness(brightness);

            tes.setColorRGBA(
                    (int) (tint[0] * .9F),
                    (int) (tint[1] * .95F),
                    (int) (tint[2] * 1F),
                    ConfigurationHandler.INSTANCE.getHintTransparency());

            for (int i = 0; i < 6; i++) {
                if (icons[i] == null) continue;

                markTextureUsed(icons[i]);

                double u = icons[i].getMinU();
                double U = icons[i].getMaxU();
                double v = icons[i].getMinV();
                double V = icons[i].getMaxV();

                // cube is a very special model. its facings can be rendered correctly by viewer distance without using
                // surface normals and view vector
                // here we do a 2 pass render.
                // first pass we draw obstructed faces (i.e. faces that are further away from player)
                // second pass we draw unobstructed faces
                for (int j = 0; j < 2; j++) {
                    switch (i) { // {DOWN, UP, NORTH, SOUTH, WEST, EAST}
                        case 0:
                            if ((Y >= eyeY) != (j == 1)) continue;
                            tes.setNormal(0, -1, 0);
                            tes.addVertexWithUV(X, Y, Z + size, u, V);
                            tes.addVertexWithUV(X, Y, Z, u, v);
                            tes.addVertexWithUV(X + size, Y, Z, U, v);
                            tes.addVertexWithUV(X + size, Y, Z + size, U, V);
                            break;
                        case 1:
                            if ((Y + size <= eyeY) != (j == 1)) continue;
                            tes.setNormal(0, 1, 0);
                            tes.addVertexWithUV(X, Y + size, Z, u, v);
                            tes.addVertexWithUV(X, Y + size, Z + size, u, V);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z, U, v);
                            break;
                        case 2:
                            if ((Z >= eyeZ) != (j == 1)) continue;
                            tes.setNormal(0, 0, -1);
                            tes.addVertexWithUV(X, Y, Z, U, V);
                            tes.addVertexWithUV(X, Y + size, Z, U, v);
                            tes.addVertexWithUV(X + size, Y + size, Z, u, v);
                            tes.addVertexWithUV(X + size, Y, Z, u, V);
                            break;
                        case 3:
                            if ((Z <= eyeZ) != (j == 1)) continue;
                            tes.setNormal(0, 0, 1);
                            tes.addVertexWithUV(X + size, Y, Z + size, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, U, v);
                            tes.addVertexWithUV(X, Y + size, Z + size, u, v);
                            tes.addVertexWithUV(X, Y, Z + size, u, V);
                            break;
                        case 4:
                            if ((X >= eyeX) != (j == 1)) continue;
                            tes.setNormal(-1, 0, 0);
                            tes.addVertexWithUV(X, Y, Z + size, U, V);
                            tes.addVertexWithUV(X, Y + size, Z + size, U, v);
                            tes.addVertexWithUV(X, Y + size, Z, u, v);
                            tes.addVertexWithUV(X, Y, Z, u, V);
                            break;
                        case 5:
                            if ((X + size <= eyeX) != (j == 1)) continue;
                            tes.setNormal(1, 0, 0);
                            tes.addVertexWithUV(X + size, Y, Z, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z, U, v);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, u, v);
                            tes.addVertexWithUV(X + size, Y, Z + size, u, V);
                            break;
                    }
                }
            }
        }

        public long getCreationTime() {
            return creationTime;
        }

        public double getSquareDistanceTo(Vec3 point) {
            return point.squareDistanceTo(X, Y, Z);
        }
    }

    public static class FMLEventHandler {
        private void resetPlayerLocation() {
            lastPlayerPos.xCoord = Minecraft.getMinecraft().thePlayer.posX;
            lastPlayerPos.yCoord = Minecraft.getMinecraft().thePlayer.posY;
            lastPlayerPos.zCoord = Minecraft.getMinecraft().thePlayer.posZ;
        }

        @SubscribeEvent
        public void onClientTick(ClientTickEvent e) {
            if (e.phase == Phase.END && Minecraft.getMinecraft().thePlayer != null) {
                Vec3 playerPos = Minecraft.getMinecraft().thePlayer.getPosition(1);
                boolean sortRequired = false;
                // The allGroups is implicitly sorted by creationTime
                // here we exploit this ordering to reduce iteration size
                int deadline = Minecraft.getMinecraft().thePlayer.ticksExisted
                        - ConfigurationHandler.INSTANCE.getHintLifespan();
                int i;
                for (i = 0; i < allGroups.size(); i++) {
                    if (allGroups.get(i).getCreationTime() > deadline) {
                        break;
                    }
                }
                if (i != 0) {
                    sortRequired = true;
                    // clear this block and go no further
                    List<HintGroup> toRemove = allGroups.subList(0, i);
                    toRemove.forEach(ClientProxy::removeGroup);
                    toRemove.clear();
                }
                if (allHintsDirty) {
                    allHintsForRender.clear();
                    // we need greatly efficient sequential access and mildly efficient sorting
                    // rebuild is sort of rare, and it's expected to lag anyway
                    // so no min-heap here
                    for (HintGroup c : allGroups) allHintsForRender.addAll(c.getHints());
                    sortRequired = true;
                }
                if (sortRequired || playerPos.squareDistanceTo(lastPlayerPos) > 1e-2) {
                    // only redo sort if player moved some distance
                    // default is 0.1 block
                    // if there was a full rebuild, go sort it as well
                    allHintsForRender.sort(Comparator.comparingDouble(info -> -info.getSquareDistanceTo(playerPos)));
                    resetPlayerLocation();
                }
            }
        }
    }

    public static class ForgeEventHandler {
        @SubscribeEvent
        public void onWorldLoad(WorldEvent.Load e) {
            if (e.world.isRemote) {
                // flush hints. we are in a different world now.
                allHintsForRender.clear();
                allGroups.clear();
                lastPlayerPos.yCoord = -1e30;
                renderThrough = 0;
                // clear throttles. hopefully a world switch is enough long as a cool down.
                localThrottleMap.clear();
            }
        }

        @SubscribeEvent
        public void onRenderWorldLast(RenderWorldLastEvent e) {
            if (allHintsForRender.isEmpty()) return;

            // seriously, I'm not a OpenGL expert, so I'm probably doing a lot of very stupid stuff here.
            // Please consider contributing patches if you find something to optimize.

            Profiler p = Minecraft.getMinecraft().mcProfiler;

            p.startSection("HintParticle");
            p.startSection("Prepare");
            Frustrum frustrum = new Frustrum();
            Entity entitylivingbase = Minecraft.getMinecraft().renderViewEntity;
            double d0 = entitylivingbase.lastTickPosX
                    + (entitylivingbase.posX - entitylivingbase.lastTickPosX) * e.partialTicks;
            double d1 = entitylivingbase.lastTickPosY
                    + (entitylivingbase.posY - entitylivingbase.lastTickPosY) * e.partialTicks;
            double d2 = entitylivingbase.lastTickPosZ
                    + (entitylivingbase.posZ - entitylivingbase.lastTickPosZ) * e.partialTicks;
            frustrum.setPosition(d0, d1, d2);

            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT); // TODO figure out original states
            // we need the back facing rendered because the thing is transparent
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND); // enable blend so it is transparent
            GL11.glBlendFunc(GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA);
            // depth test begin as enabled
            boolean renderThrough = false;
            Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.locationBlocksTexture);
            GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
            Tessellator tes = Tessellator.instance;
            tes.startDrawingQuads();
            for (int i = 0, allHintsForRenderSize = allHintsForRender.size(); i < allHintsForRenderSize; i++) {
                HintParticleInfo hint = allHintsForRender.get(i);
                if (!hint.isInFrustrum(frustrum)) continue;
                if (renderThrough != hint.renderThrough) {
                    if (i > 0) {
                        p.endStartSection("Draw");
                        tes.draw();
                        tes.startDrawingQuads();
                        p.endStartSection("Prepare");
                    }
                    if (hint.renderThrough) GL11.glDisable(GL11.GL_DEPTH_TEST);
                    else GL11.glEnable(GL11.GL_DEPTH_TEST);
                    renderThrough = hint.renderThrough;
                }
                // TODO verify if we need to add eyeHeight
                hint.draw(tes, d0, d1, d2);
            }
            p.endStartSection("Draw");
            tes.draw();
            p.endSection();

            GL11.glPopAttrib();
            GL11.glPopMatrix();
            p.endSection();
        }
    }
}
