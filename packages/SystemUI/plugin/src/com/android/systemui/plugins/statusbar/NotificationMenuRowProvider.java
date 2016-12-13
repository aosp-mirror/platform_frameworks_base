
package com.android.systemui.plugins.statusbar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;

import com.android.systemui.plugins.Plugin;

public interface NotificationMenuRowProvider extends Plugin {

    public static final String ACTION = "com.android.systemui.action.PLUGIN_NOTIFICATION_MENU_ROW";

    public static final int VERSION = 1;

    /**
     * Returns a list of items to populate the menu 'behind' a notification.
     */
    public ArrayList<MenuItem> getMenuItems(Context context);

    public interface OnMenuClickListener {
        public void onMenuClicked(View row, int x, int y, MenuItem menu);

        public void onMenuReset(View row);
    }

    public static class MenuItem {
        public Drawable icon;
        public String menuDescription;
        public View menuView;

        public MenuItem(Drawable i, String s) {
            icon = i;
            menuDescription = s;
        }

        public boolean onTouch(View v, int x, int y) {
            return false;
        }
    }
}
