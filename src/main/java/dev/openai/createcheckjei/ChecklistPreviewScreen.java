package dev.openai.createcheckjei;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Scrollable selection + confirmation. Never writes back to the player's clipboard. */
public final class ChecklistPreviewScreen extends Screen {
    private final List<ChecklistReader.ChecklistSource> sources;
    private final Set<String> selected = new HashSet<>();
    private int sourceIndex;
    private int page;
    private int rowsPerPage;
    private int left;
    private int top;
    private int boxWidth;
    private int boxHeight;
    private String status = "";

    public ChecklistPreviewScreen(List<ChecklistReader.ChecklistSource> sources) {
        super(Component.translatable("screen.createcheckjei.title"));
        this.sources = List.copyOf(sources);
        selectMissing();
    }

    private ChecklistReader.ChecklistSource current() { return sources.get(sourceIndex); }
    private List<ChecklistReader.MaterialRow> rows() {
        return sources.isEmpty() ? List.of() : current().rows();
    }
    private String id(ChecklistReader.MaterialRow row) { return row.key(); }

    private void selectMissing() {
        selected.clear();
        if (sources.isEmpty()) return;
        for (ChecklistReader.MaterialRow row : rows()) if (!row.complete()) selected.add(id(row));
    }

    @Override
    protected void init() {
        clearWidgets();
        boxWidth = Math.min(404, width - 20);
        rowsPerPage = Math.max(3, Math.min(8, (height - 148) / 23));
        boxHeight = 115 + rowsPerPage * 23;
        left = (width - boxWidth) / 2;
        top = Math.max(8, (height - boxHeight) / 2);
        int firstRowY = top + 58;
        int bottomY = top + boxHeight - 30;

        if (!sources.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> switchSource(-1))
                .bounds(left + 9, top + 29, 22, 20).build()).active = sources.size() > 1;
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> switchSource(1))
                .bounds(left + boxWidth - 31, top + 29, 22, 20).build()).active = sources.size() > 1;
        }
        int start = page * rowsPerPage;
        for (int i = start; i < Math.min(rows().size(), start + rowsPerPage); i++) {
            ChecklistReader.MaterialRow row = rows().get(i);
            if (row.complete()) continue;
            addRenderableWidget(Button.builder(Component.literal(selected.contains(id(row)) ? "√" : "-"),
                    b -> { if (!selected.add(id(row))) selected.remove(id(row)); rebuildWidgets(); })
                .bounds(left + 8, firstRowY + (i - start) * 23, 21, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
            .bounds(left + 8, bottomY - 25, 22, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
            .bounds(left + boxWidth - 30, bottomY - 25, 22, 20).build()).active = page < totalPages() - 1;
        addRenderableWidget(Button.builder(Component.translatable("screen.createcheckjei.select_all"), b -> {
                selectMissing(); rebuildWidgets();
            }).bounds(left + 8, bottomY, 84, 20).build()).active = !sources.isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("screen.createcheckjei.add"), b -> addSelected())
            .bounds(left + 98, bottomY, boxWidth - 193, 20).build()).active = !selected.isEmpty() && JeiBookmarkBridge.available();
        addRenderableWidget(Button.builder(Component.translatable("screen.createcheckjei.ignore"), b -> onClose())
            .bounds(left + boxWidth - 88, bottomY, 80, 20).build());
    }

    private int totalPages() { return Math.max(1, (rows().size() + rowsPerPage - 1) / rowsPerPage); }
    private void changePage(int offset) {
        page = Math.max(0, Math.min(totalPages() - 1, page + offset));
        rebuildWidgets();
    }
    private void switchSource(int direction) {
        sourceIndex = Math.floorMod(sourceIndex + direction, sources.size());
        page = 0;
        selectMissing();
        status = "";
        rebuildWidgets();
    }

    private void addSelected() {
        if (sources.isEmpty() || !JeiBookmarkBridge.available()) return;
        List<ChecklistReader.MaterialRow> toAdd = new ArrayList<>();
        for (ChecklistReader.MaterialRow row : rows()) {
            if (!row.complete() && selected.contains(id(row))) toAdd.add(row);
        }
        int count = JeiBookmarkBridge.addBookmarks(toAdd);
        if (count < 0) {
            status = Component.translatable("screen.createcheckjei.legacy_unavailable").getString();
            return;
        }
        if (count >= 0) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                    Component.translatable("message.createcheckjei.added", count, toAdd.size()), false);
            }
            onClose();
        }
    }

    /**
     * Minecraft 1.21.1's Screen.render() already calls renderBackground().
     * Keep vanilla's blur, but draw our opaque panel AFTER the blur pass.
     * Never call renderBackground() a second time from render(): doing so blurs
     * text, item icons and buttons already painted to the screen.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);
        renderPanel(graphics);
    }

    /** Custom UI content is rendered at native GUI scale / integer coordinates. */
    private void renderPanel(GuiGraphics graphics) {
        graphics.fill(left - 2, top - 2, left + boxWidth + 2, top + boxHeight + 2, 0xFFE6DCC5);
        graphics.fill(left, top, left + boxWidth, top + boxHeight, 0xFF202A33);
        graphics.drawCenteredString(font, title, width / 2, top + 9, 0xFFFFFFFF);
        if (sources.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.createcheckjei.not_found"),
                width / 2, top + 66, 0xFFFFB4A9);
        } else {
            ChecklistReader.ChecklistSource src = current();
            String summary = src.position() + "  (" + (sourceIndex + 1) + "/" + sources.size() + ")  缺少 " + src.totalMissing() + " 件";
            graphics.drawCenteredString(font, font.plainSubstrByWidth(summary, boxWidth - 76),
                width / 2, top + 35, 0xFFE5D9BD);
            graphics.drawString(font, "物品", left + 34, top + 49, 0xFFB6D4E5, false);
            graphics.drawString(font, "尚缺数量", left + boxWidth - 76, top + 49, 0xFFB6D4E5, false);
            int start = page * rowsPerPage;
            for (int i = start; i < Math.min(rows().size(), start + rowsPerPage); i++) {
                ChecklistReader.MaterialRow row = rows().get(i);
                int y = top + 58 + (i - start) * 23;
                graphics.fill(left + 5, y, left + boxWidth - 5, y + 21, i % 2 == 0 ? 0xFF323C48 : 0xFF29343D);
                if (row.complete()) graphics.drawString(font, "✔", left + 14, y + 6, 0xFF6CD49E, false);
                ItemStack icon = row.icon();
                graphics.renderItem(icon, left + 34, y + 2);
                String name = font.plainSubstrByWidth(icon.getHoverName().getString(), boxWidth - 143);
                graphics.drawString(font, name, left + 56, y + 6, row.complete() ? 0xFF93A89C : 0xFFFFFFFF, false);
                String amount = row.complete() ? "0 已完成" : "x" + row.missingCount();
                graphics.drawString(font, amount, left + boxWidth - 74, y + 6, row.complete() ? 0xFF76D19B : 0xFFFFD080, false);
            }
            graphics.drawCenteredString(font, "" + (page + 1) + " / " + totalPages(), width / 2,
                top + boxHeight - 54, 0xFFE5D9BD);
        }
        if (!JeiBookmarkBridge.available()) {
            graphics.drawCenteredString(font, Component.translatable("screen.createcheckjei.jei_unavailable"),
                width / 2, top + boxHeight - 43, 0xFFFF8888);
        }
        if (!status.isEmpty()) graphics.drawCenteredString(font, status, width / 2, top + boxHeight - 43, 0xFFFF8888);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Screen.render(): our renderBackground() (world blur -> sharp panel),
        // then native Minecraft buttons. Tooltips are drawn last below.
        super.render(graphics, mouseX, mouseY, delta);
        if (!sources.isEmpty()) {
            int start = page * rowsPerPage;
            for (int i = start; i < Math.min(rows().size(), start + rowsPerPage); i++) {
                int y = top + 58 + (i - start) * 23;
                if (mouseX >= left + 33 && mouseX <= left + boxWidth - 7 && mouseY >= y && mouseY < y + 21) {
                    graphics.renderTooltip(font, rows().get(i).icon(), mouseX, mouseY);
                    break;
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            changePage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
