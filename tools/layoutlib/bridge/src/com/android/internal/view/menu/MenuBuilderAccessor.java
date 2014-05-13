package com.android.internal.view.menu;

import java.util.ArrayList;

/**
 * To access non public members of {@link MenuBuilder}
 */
public class MenuBuilderAccessor {
    public static ArrayList<MenuItemImpl> getNonActionItems(MenuBuilder builder) {
        return builder.getNonActionItems();
    }
}
