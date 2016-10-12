package android.widget;

import android.annotation.NonNull;
import android.view.MenuItem;

import com.android.internal.view.menu.MenuBuilder;

/**
 * An interface notified when a menu item is hovered. Useful for cases when hover should trigger
 * some behavior at a higher level, like managing the opening and closing of submenus.
 *
 * @hide
 */
public interface MenuItemHoverListener {
    /**
     * Called when hover exits a menu item.
     * <p>
     * If hover is moving to another item, this method will be called before
     * {@link #onItemHoverEnter(MenuBuilder, MenuItem)} for the newly-hovered item.
     *
     * @param menu the item's parent menu
     * @param item the hovered menu item
     */
    void onItemHoverExit(@NonNull MenuBuilder menu, @NonNull MenuItem item);

    /**
     * Called when hover enters a menu item.
     *
     * @param menu the item's parent menu
     * @param item the hovered menu item
     */
    void onItemHoverEnter(@NonNull MenuBuilder menu, @NonNull MenuItem item);
}
