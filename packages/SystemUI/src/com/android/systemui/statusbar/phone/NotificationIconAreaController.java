package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
public class NotificationIconAreaController {
    private final NotificationColorUtil mNotificationColorUtil;

    private int mIconSize;
    private int mIconHPadding;
    private int mIconTint = Color.WHITE;

    private PhoneStatusBar mPhoneStatusBar;
    protected View mNotificationIconArea;
    private NotificationShelf mNotificationIconAreaScroller;
    private NotificationIconContainer mNotificationIcons;
    private NotificationIconContainer mNotificationIconsScroller;
    private final Rect mTintArea = new Rect();
    private NotificationStackScrollLayout mNotificationScrollLayout;
    private Context mContext;

    public NotificationIconAreaController(Context context, PhoneStatusBar phoneStatusBar) {
        mPhoneStatusBar = phoneStatusBar;
        mNotificationColorUtil = NotificationColorUtil.getInstance(context);
        mContext = context;

        initializeNotificationAreaViews(context);
    }

    protected View inflateIconArea(LayoutInflater inflater) {
        return inflater.inflate(R.layout.notification_icon_area, null);
    }

    /**
     * Initializes the views that will represent the notification area.
     */
    protected void initializeNotificationAreaViews(Context context) {
        reloadDimens(context);

        LayoutInflater layoutInflater = LayoutInflater.from(context);
        mNotificationIconArea = inflateIconArea(layoutInflater);
        mNotificationIcons = (NotificationIconContainer) mNotificationIconArea.findViewById(
                R.id.notificationIcons);

        mNotificationIconAreaScroller = mPhoneStatusBar.getNotificationShelf();
        mNotificationIconsScroller = mNotificationIconAreaScroller.getNotificationIconContainer();

        mNotificationScrollLayout = mPhoneStatusBar.getNotificationScrollLayout();
    }

    public void onDensityOrFontScaleChanged(Context context) {
        reloadDimens(context);
        final LinearLayout.LayoutParams params = generateIconLayoutParams();
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            child.setLayoutParams(params);
        }
    }

    @NonNull
    private LinearLayout.LayoutParams generateIconLayoutParams() {
        return new LinearLayout.LayoutParams(
                mIconSize + 2 * mIconHPadding, getHeight());
    }

    private void reloadDimens(Context context) {
        Resources res = context.getResources();
        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = res.getDimensionPixelSize(R.dimen.status_bar_icon_padding);
    }

    /**
     * Returns the view that represents the notification area.
     */
    public View getNotificationInnerAreaView() {
        return mNotificationIconArea;
    }

    /**
     * See {@link StatusBarIconController#setIconsDarkArea}.
     *
     * @param tintArea the area in which to tint the icons, specified in screen coordinates
     */
    public void setTintArea(Rect tintArea) {
        if (tintArea == null) {
            mTintArea.setEmpty();
        } else {
            mTintArea.set(tintArea);
        }
        applyNotificationIconsTint();
    }

    /**
     * Sets the color that should be used to tint any icons in the notification area. If this
     * method is not called, the default tint is {@link Color#WHITE}.
     */
    public void setIconTint(int iconTint) {
        mIconTint = iconTint;
        mNotificationIcons.setIconTint(mIconTint);
        applyNotificationIconsTint();
    }

    protected int getHeight() {
        return mPhoneStatusBar.getStatusBarHeight();
    }

    protected boolean shouldShowNotification(NotificationData.Entry entry,
            NotificationData notificationData) {
        if (notificationData.isAmbient(entry.key)
                && !NotificationData.showNotificationEvenIfUnprovisioned(entry.notification)) {
            return false;
        }
        if (!PhoneStatusBar.isTopLevelChild(entry)) {
            return false;
        }
        if (entry.row.getVisibility() == View.GONE) {
            return false;
        }

        return true;
    }

    /**
     * Updates the notifications with the given list of notifications to display.
     */
    public void updateNotificationIcons(NotificationData notificationData) {

        updateIconsForLayout(notificationData, entry -> entry.icon, mNotificationIcons);
        updateIconsForLayout(notificationData, entry -> entry.expandedIcon,
                mNotificationIconsScroller);

        applyNotificationIconsTint();
        ArrayList<NotificationData.Entry> activeNotifications
                = notificationData.getActiveNotifications();
        for (int i = 0; i < activeNotifications.size(); i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            boolean isPreL = Boolean.TRUE.equals(entry.expandedIcon.getTag(R.id.icon_is_pre_L));
            boolean colorize = !isPreL
                    || NotificationUtils.isGrayscale(entry.expandedIcon, mNotificationColorUtil);
            if (colorize) {
                int color = entry.getContrastedColor(mContext);
                entry.expandedIcon.setImageTintList(ColorStateList.valueOf(color));
            }
        }
    }

    /**
     * Updates the notification icons for a host layout. This will ensure that the notification
     * host layout will have the same icons like the ones in here.
     *
     * @param notificationData the notification data to look up which notifications are relevant
     * @param function A function to look up an icon view based on an entry
     * @param hostLayout which layout should be updated
     */
    private void updateIconsForLayout(NotificationData notificationData,
            Function<NotificationData.Entry, StatusBarIconView> function,
            ViewGroup hostLayout) {
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(
                mNotificationScrollLayout.getChildCount());

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < mNotificationScrollLayout.getChildCount(); i++) {
            View view = mNotificationScrollLayout.getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                NotificationData.Entry ent = ((ExpandableNotificationRow) view).getEntry();
                if (shouldShowNotification(ent, notificationData)) {
                    toShow.add(function.apply(ent));
                }
            }
        }

        ArrayList<View> toRemove = new ArrayList<>();
        for (int i = 0; i < hostLayout.getChildCount(); i++) {
            View child = hostLayout.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        final int toRemoveCount = toRemove.size();
        for (int i = 0; i < toRemoveCount; i++) {
            hostLayout.removeView(toRemove.get(i));
        }

        final LinearLayout.LayoutParams params = generateIconLayoutParams();
        for (int i = 0; i < toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                hostLayout.addView(v, i, params);
            }
        }

        // Re-sort notification icons
        final int childCount = hostLayout.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View actual = hostLayout.getChildAt(i);
            StatusBarIconView expected = toShow.get(i);
            if (actual == expected) {
                continue;
            }
            hostLayout.removeView(expected);
            hostLayout.addView(expected, i);
        }
    }

    /**
     * Applies {@link #mIconTint} to the notification icons.
     */
    private void applyNotificationIconsTint() {
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
            boolean colorize = !isPreL || NotificationUtils.isGrayscale(v, mNotificationColorUtil);
            if (colorize) {
                v.setImageTintList(ColorStateList.valueOf(
                        StatusBarIconController.getTint(mTintArea, v, mIconTint)));
            }
        }
    }
}
