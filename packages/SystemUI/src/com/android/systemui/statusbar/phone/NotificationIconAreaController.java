package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.util.ArrayList;

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
public class NotificationIconAreaController {
    private final NotificationColorUtil mNotificationColorUtil;

    private int mIconSize;
    private int mIconHPadding;
    private int mClockLocation;
    private boolean mShowClock;
    private int mIconTint = Color.WHITE;

    private PhoneStatusBar mPhoneStatusBar;
    protected View mNotificationIconArea;
    private IconMerger mNotificationIcons;
    private ImageView mMoreIcon;
    private final Rect mTintArea = new Rect();

    public NotificationIconAreaController(Context context, PhoneStatusBar phoneStatusBar) {
        mPhoneStatusBar = phoneStatusBar;
        mNotificationColorUtil = NotificationColorUtil.getInstance(context);

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

        mNotificationIcons =
                (IconMerger) mNotificationIconArea.findViewById(R.id.notificationIcons);

        mMoreIcon = (ImageView) mNotificationIconArea.findViewById(R.id.moreIcon);
        if (mMoreIcon != null) {
            mMoreIcon.setImageTintList(ColorStateList.valueOf(mIconTint));
            mNotificationIcons.setOverflowIndicator(mMoreIcon);
        }
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
        if (mMoreIcon != null) {
            mMoreIcon.setImageTintList(ColorStateList.valueOf(mIconTint));
        }
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
        final LinearLayout.LayoutParams params = generateIconLayoutParams();

        ArrayList<NotificationData.Entry> activeNotifications =
                notificationData.getActiveNotifications();
        final int size = activeNotifications.size();
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(size);

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < size; i++) {
            NotificationData.Entry ent = activeNotifications.get(i);
            if (shouldShowNotification(ent, notificationData)) {
                toShow.add(ent.icon);
            }
        }

        ArrayList<View> toRemove = new ArrayList<>();
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        final int toRemoveCount = toRemove.size();
        for (int i = 0; i < toRemoveCount; i++) {
            mNotificationIcons.removeView(toRemove.get(i));
        }

        for (int i = 0; i < toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }

        // Re-sort notification icons
        final int childCount = mNotificationIcons.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View actual = mNotificationIcons.getChildAt(i);
            StatusBarIconView expected = toShow.get(i);
            if (actual == expected) {
                continue;
            }
            mNotificationIcons.removeView(expected);
            mNotificationIcons.addView(expected, i);
        }

        applyNotificationIconsTint();
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

    public void setClockAndDateStatus(int width, int mode, boolean enabled) {
        if (mNotificationIcons != null) {
            mNotificationIcons.setClockAndDateStatus(width, mode, enabled);
        }
        mClockLocation = mode;
        mShowClock = enabled;
    }
}
