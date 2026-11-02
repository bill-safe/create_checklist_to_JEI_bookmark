package dev.openai.createcheckjei;

import com.mojang.datafixers.util.Either;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

import java.util.HashMap;
import java.util.Map;

/** Adds a count line ONLY when JEI's native bookmarked ingredient is hovered. */
public final class QuantityTooltip {
    private static final Map<Item, Integer> LAST_IMPORTED_MISSING = new HashMap<>();
    private QuantityTooltip() {}

    public static void remember(ChecklistReader.MaterialRow row) {
        if (!row.complete()) LAST_IMPORTED_MISSING.put(row.icon().getItem(), row.missingCount());
    }

    public static void onTooltip(RenderTooltipEvent.GatherComponents event) {
        if (LAST_IMPORTED_MISSING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null || mc.screen instanceof ChecklistPreviewScreen) return;

        ItemStack tooltipItem = event.getItemStack();
        if (tooltipItem.isEmpty()) return;
        Integer missing = LAST_IMPORTED_MISSING.get(tooltipItem.getItem());
        if (missing == null) return;

        // Make sure it's a JEI BOOKMARK hovered, not the same item inside the player's inventory.
        ItemStack hoveredBookmark = JeiBookmarkBridge.getHoveredBookmarkItem();
        if (hoveredBookmark == null || hoveredBookmark.isEmpty()) return;
        if (!ItemStack.isSameItemSameComponents(hoveredBookmark, tooltipItem)) return;

        event.getTooltipElements().add(Either.left(
            Component.translatable("tooltip.createcheckjei.missing", missing)
                .withStyle(ChatFormatting.GOLD)
        ));
    }
}
