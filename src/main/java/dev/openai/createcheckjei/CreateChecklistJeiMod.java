package dev.openai.createcheckjei;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure client-side integration: no dedicated-server mod installation required. */
@Mod(value = CreateChecklistJeiMod.MOD_ID, dist = Dist.CLIENT)
public final class CreateChecklistJeiMod {
    public static final String MOD_ID = "createcheckjei";

    private static final KeyMapping OPEN = new KeyMapping(
        "key.createcheckjei.open", InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_K, "key.categories.createcheckjei"
    );

    private static final Set<String> PROMPTED = new HashSet<>();
    private static int tickCount;
    private static boolean hadPlayer;

    public CreateChecklistJeiMod(IEventBus modBus) {
        modBus.addListener(CreateChecklistJeiMod::registerKeys);
        NeoForge.EVENT_BUS.addListener(CreateChecklistJeiMod::clientTick);
        NeoForge.EVENT_BUS.addListener(QuantityTooltip::onTooltip);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (hadPlayer) {
                PROMPTED.clear();
                tickCount = 0;
                hadPlayer = false;
            }
            return;
        }
        hadPlayer = true;
        while (OPEN.consumeClick()) {
            if (mc.screen == null) {
                List<ChecklistReader.ChecklistSource> found = ChecklistReader.find(mc.player);
                mc.setScreen(new ChecklistPreviewScreen(found));
            }
        }
        if (mc.screen != null || (++tickCount % 40 != 0)) return;
        List<ChecklistReader.ChecklistSource> sources = ChecklistReader.find(mc.player);
        for (ChecklistReader.ChecklistSource source : sources) {
            if (source.missingKinds() == 0) continue;
            if (PROMPTED.add(source.fingerprint())) {
                mc.setScreen(new ChecklistPreviewScreen(sources));
                return;
            }
        }
    }
}
