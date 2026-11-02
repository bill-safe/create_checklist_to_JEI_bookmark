package dev.openai.createcheckjei;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.component.WrittenBookContent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads Create clipboard components, with an optional structured hover-data fallback for printed books. */
public final class ChecklistReader {
    private ChecklistReader() {}
    private static final Pattern BOOK_AMOUNT = Pattern.compile("(?m)\\bx([0-9]+)\\b");

    public record MaterialRow(ItemStack icon, int missingCount) {
        public boolean complete() { return missingCount == 0; }
        public String key() { return BuiltInRegistries.ITEM.getKey(icon.getItem()).toString(); }
    }

    public record ChecklistSource(String position, List<MaterialRow> rows, String fingerprint) {
        public long missingKinds() { return rows.stream().filter(r -> !r.complete()).count(); }
        public long totalMissing() { return rows.stream().mapToLong(MaterialRow::missingCount).sum(); }
    }

    public static List<ChecklistSource> find(LocalPlayer player) {
        List<ChecklistSource> found = new ArrayList<>();
        // Keep held items first; do not process main-hand stack a second time in the hotbar.
        add(found, player.getMainHandItem(), "主手");
        add(found, player.getOffhandItem(), "副手");
        int selected = player.getInventory().selected;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            if (i == selected) continue;
            ItemStack item = player.getInventory().items.get(i);
            String where = i < 9 ? "快捷栏 " + (i + 1) : "背包格 " + (i - 8);
            add(found, item, where);
        }
        return found;
    }

    private static void add(List<ChecklistSource> found, ItemStack stack, String where) {
        read(stack, where).ifPresent(found::add);
    }

    public static Optional<ChecklistSource> read(ItemStack stack, String location) {
        if (stack.isEmpty()) return Optional.empty();
        Optional<ChecklistSource> printedBook = readPrintedBook(stack, location);
        if (printedBook.isPresent()) return printedBook;
        ClipboardContent content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
        // Create's Schematicannon prints a READ_ONLY WRITTEN clipboard.
        // Filtering on the component, read-only flag and real item rows avoids unrelated notes.
        if (content == null || !content.readOnly() || !"WRITTEN".equals(content.type().name())) {
            return Optional.empty();
        }

        Map<Item, MutableCount> items = new LinkedHashMap<>();
        boolean hasMaterialRows = false;
        for (List<ClipboardEntry> page : ClipboardEntry.readAll(content)) {
            for (ClipboardEntry entry : page) {
                if (entry.icon == null || entry.icon.isEmpty()) continue;
                hasMaterialRows = true;
                Item item = entry.icon.getItem();
                MutableCount row = items.computeIfAbsent(item, ignored -> new MutableCount(entry.icon.copyWithCount(1)));
                if (!entry.checked && entry.itemAmount > 0) {
                    // A printed checklist stores REMAINING count, not initial required count.
                    row.missing = Math.min(Integer.MAX_VALUE, (long) row.missing + entry.itemAmount);
                }
            }
        }
        if (!hasMaterialRows) return Optional.empty();

        return assembleSource(location, items);
    }

    /** Create can also print the checklist as a WRITTEN BOOK instead of a clipboard. */
    private static Optional<ChecklistSource> readPrintedBook(ItemStack stack, String location) {
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        // Identify Create's own output by author; ordinary unrelated books are ignored.
        if (book == null || !"Schematicannon".equals(book.author())) return Optional.empty();
        Map<Item, MutableCount> items = new LinkedHashMap<>();
        for (Component page : book.getPages(false)) {
            for (Component entry : page.getSiblings()) {
                ItemStack icon = findIconInHover(entry);
                if (icon.isEmpty()) continue;
                Matcher matcher = BOOK_AMOUNT.matcher(entry.getString());
                if (!matcher.find()) continue;
                int amount;
                try {
                    amount = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                // Create marks complete book entries with a checkmark, and gives them
                // the ORIGINAL required amount. Their remaining amount is zero.
                boolean complete = entry.getString().contains("✔");
                MutableCount row = items.computeIfAbsent(icon.getItem(), ignored ->
                    new MutableCount(icon.copyWithCount(1)));
                if (!complete) row.missing = Math.min(Integer.MAX_VALUE, row.missing + amount);
            }
        }
        if (items.isEmpty()) return Optional.empty();
        return assembleSource(location + "（书本）", items);
    }

    private static ItemStack findIconInHover(Component component) {
        HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover != null) {
            HoverEvent.ItemStackInfo stackInfo = hover.getValue(HoverEvent.Action.SHOW_ITEM);
            if (stackInfo != null) return stackInfo.getItemStack();
        }
        for (Component child : component.getSiblings()) {
            ItemStack nested = findIconInHover(child);
            if (!nested.isEmpty()) return nested;
        }
        return ItemStack.EMPTY;
    }

    private static Optional<ChecklistSource> assembleSource(String location, Map<Item, MutableCount> items) {
        List<MaterialRow> result = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (MutableCount mc : items.values()) {
            MaterialRow row = new MaterialRow(mc.icon, (int) mc.missing);
            result.add(row);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(row.icon().getItem());
            signature.append(id).append(';');
        }
        // Exclude quantities from signatures to avoid repetitive prompts when Create updates counts.
        return Optional.of(new ChecklistSource(location, List.copyOf(result), signature.toString()));
    }

    private static final class MutableCount {
        final ItemStack icon;
        long missing;
        MutableCount(ItemStack icon) { this.icon = icon; }
    }
}
