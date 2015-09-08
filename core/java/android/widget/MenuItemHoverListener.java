package android.widget;

import com.android.internal.view.menu.MenuBuilder;

/**
 * An interface notified when a menu item is hovered. Useful for cases when hover should trigger
 * some behavior at a higher level, like managing the opening and closing of submenus.
 *
 * @hide
 */
public interface MenuItemHoverListener {
    public void onItemHovered(MenuBuilder menu, int position);
}
