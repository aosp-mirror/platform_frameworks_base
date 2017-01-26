
package com.android.systemui.plugins.statusbar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
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

    public interface GutsInteractionListener {
        public void onInteraction(View view);

        public void closeGuts(View view);
    }

    public interface GutsContent {
        public void setInteractionListener(GutsInteractionListener listener);

        public View getContentView();

        public boolean handleCloseControls();
    }

    public interface SnoozeGutsContent extends GutsContent {
        public void setSnoozeListener(SnoozeListener listener);

        public void setStatusBarNotification(StatusBarNotification sbn);
    }

    public interface SnoozeListener {
        public void snoozeNotification(StatusBarNotification sbn, SnoozeOption snoozeOption);
    }

    public static class MenuItem {
        public Drawable icon;
        public String menuDescription;
        public View menuView;
        public GutsContent gutsContent;

        public MenuItem(Drawable i, String s, GutsContent content) {
            icon = i;
            menuDescription = s;
            gutsContent = content;
        }

        public View getGutsView() {
            return gutsContent.getContentView();
        }

        public boolean onTouch(View v, int x, int y) {
            return false;
        }
    }

    public static class SnoozeOption {
        public SnoozeCriterion criterion;
        public int snoozeForMinutes;
        public CharSequence description;
        public CharSequence confirmation;

        public SnoozeOption(SnoozeCriterion crit, int minsToSnoozeFor, CharSequence desc,
                CharSequence confirm) {
            criterion = crit;
            snoozeForMinutes = minsToSnoozeFor;
            description = desc;
            confirmation = confirm;
        }
    }
}
