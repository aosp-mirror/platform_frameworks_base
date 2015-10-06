package android.widget;

import com.android.internal.view.menu.MenuBuilder;

import android.annotation.NonNull;

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
     * {@link #onItemHoverEnter(MenuBuilder, int)} for the newly-hovered item.
     *
     * @param menu the item's parent menu
     * @param position the position of the item within the menu
     */
    void onItemHoverExit(@NonNull MenuBuilder menu, int position);

    /**
     * Called when hover enters a menu item.
     *
     * @param menu the item's parent menu
     * @param position the position of the item within the menu
     */
    void onItemHoverEnter(@NonNull MenuBuilder menu, int position);
}
