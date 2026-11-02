package dev.openai.createcheckjei;

import mezz.jei.api.JeiPlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Adapter for JEI 19.38.0.366 (NeoForge 1.21.1).
 *
 * The provided JEI jar was inspected with javap: BookmarkOverlay has a
 * bookmarkList field; BookmarkList contains bookmarkFactory; BookmarkFactory
 * has a public create(ITypedIngredient); BookmarkList has add(IBookmark).
 * Calling the real JEI factory is required because IngredientBookmark takes
 * (ITypedIngredient, Object) and has no one-argument constructor.
 *
 * This is a version-specific reflection adapter, NOT a public JEI API.
 * No direct references to JEI GUI internal types are emitted at compile time.
 */
@JeiPlugin
public final class JeiBookmarkBridge implements IModPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateChecklistJEI/Bookmarks");
    @Nullable private static IJeiRuntime jei;
    @Nullable private static LegacyAccess cachedAccess;
    private static boolean warned;

    private record LegacyAccess(Object list, Object factory, Method create, Method add) {
        /** Returns true when this bookmark is newly inserted; never toggles. */
        boolean addIngredient(Object typedIngredient) throws ReflectiveOperationException {
            Object bookmark = create.invoke(factory, typedIngredient);
            if (bookmark == null) throw new IllegalStateException("JEI factory returned a null bookmark");
            Object result = add.invoke(list, bookmark);
            return result instanceof Boolean b && b;
        }
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(CreateChecklistJeiMod.MOD_ID, "bookmark_integration");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jei = runtime;
        cachedAccess = null;
        warned = false;
    }

    @Override
    public void onRuntimeUnavailable() {
        jei = null;
        cachedAccess = null;
        warned = false;
    }

    public static boolean available() { return jei != null; }

    @Nullable
    public static ItemStack getHoveredBookmarkItem() {
        IJeiRuntime runtime = jei;
        return runtime == null ? null : runtime.getBookmarkOverlay().getItemStackUnderMouse();
    }

    /** Returns -1 when the old JEI internal structures cannot be accessed. */
    public static int addBookmarks(Collection<ChecklistReader.MaterialRow> rows) {
        IJeiRuntime runtime = jei;
        if (runtime == null) return -1;
        LegacyAccess access = cachedAccess;
        if (access == null) {
            try {
                access = resolveLegacyAccess(runtime);
                cachedAccess = access;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                warnOnce("Cannot access JEI 19.38 bookmark structures", ex);
                return -1;
            }
        }

        int added = 0;
        for (ChecklistReader.MaterialRow row : rows) {
            if (row.complete()) continue;
            ItemStack stack = row.icon().copyWithCount(1);
            var optional = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack, true);
            if (optional.isEmpty()) continue;
            try {
                // BookmarkList.add() skips duplicates, updates JEI's overlay,
                // and saves through JEI's native bookmark config implementation.
                boolean wasAdded = access.addIngredient(optional.get());
                // Also track quantities for an item that was already bookmarked.
                QuantityTooltip.remember(row);
                if (wasAdded) added++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                warnOnce("Failed while inserting a JEI bookmark; " + added + " were already added", ex);
                return -1;
            }
        }
        return added;
    }

    private static LegacyAccess resolveLegacyAccess(IJeiRuntime runtime) throws ReflectiveOperationException {
        // Confirmed in the uploaded JEI 19.38.0.366 jar; no broad graph traversal.
        Object overlay = runtime.getBookmarkOverlay();
        if (!"mezz.jei.gui.overlay.bookmarks.BookmarkOverlay".equals(overlay.getClass().getName())) {
            throw new IllegalStateException("Unexpected JEI bookmark overlay: " + overlay.getClass().getName());
        }
        Field listField = overlay.getClass().getDeclaredField("bookmarkList");
        listField.setAccessible(true);
        Object list = listField.get(overlay);
        if (list == null || !"mezz.jei.gui.bookmarks.BookmarkList".equals(list.getClass().getName())) {
            throw new IllegalStateException("Unexpected JEI bookmark list");
        }
        Field factoryField = list.getClass().getDeclaredField("bookmarkFactory");
        factoryField.setAccessible(true);
        Object factory = factoryField.get(list);
        if (factory == null || !"mezz.jei.gui.bookmarks.BookmarkFactory".equals(factory.getClass().getName())) {
            throw new IllegalStateException("Unexpected JEI bookmark factory");
        }
        ClassLoader loader = list.getClass().getClassLoader();
        Class<?> typedClass = Class.forName("mezz.jei.api.ingredients.ITypedIngredient", false, loader);
        Class<?> bookmarkClass = Class.forName("mezz.jei.gui.bookmarks.IBookmark", false, loader);
        Method create = factory.getClass().getMethod("create", typedClass);
        Method add = list.getClass().getMethod("add", bookmarkClass);
        if (add.getReturnType() != boolean.class) throw new IllegalStateException("Unexpected JEI add result type");
        LOGGER.info("Detected JEI 19.38 native bookmark factory and list");
        return new LegacyAccess(list, factory, create, add);
    }

    private static void warnOnce(String message, Throwable cause) {
        if (warned) return;
        warned = true;
        if (cause instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            cause = invocation.getCause();
        }
        LOGGER.error(message, cause);
    }
}
