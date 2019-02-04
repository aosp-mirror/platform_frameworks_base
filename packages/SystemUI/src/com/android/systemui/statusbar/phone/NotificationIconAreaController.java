package com.android.systemui.statusbar.phone;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
public class NotificationIconAreaController implements DarkReceiver,
        StatusBarStateController.StateListener {

    public static final String LOW_PRIORITY = "low_priority";

    private final ContrastColorUtil mContrastColorUtil;
    private final NotificationEntryManager mEntryManager;
    private final Runnable mUpdateStatusBarIcons = this::updateStatusBarIcons;
    private final StatusBarStateController mStatusBarStateController;
    @VisibleForTesting
    final NotificationListener.NotificationSettingsListener mSettingsListener =
            new NotificationListener.NotificationSettingsListener() {
                @Override
                public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
                    if (NotificationUtils.useNewInterruptionModel(mContext)) {
                        mShowLowPriority = !hideSilentStatusIcons;
                        if (mNotificationScrollLayout != null) {
                            updateStatusBarIcons();
                        }
                    }
                }
            };

    private int mIconSize;
    private int mIconHPadding;
    private int mIconTint = Color.WHITE;

    private StatusBar mStatusBar;
    protected View mNotificationIconArea;
    private NotificationIconContainer mNotificationIcons;
    private NotificationIconContainer mShelfIcons;
    private final Rect mTintArea = new Rect();
    private ViewGroup mNotificationScrollLayout;
    private Context mContext;
    private boolean mFullyDark;
    private boolean mShowLowPriority = true;

    /**
     * Ratio representing being awake or in ambient mode, where 1 is dark and 0 awake.
     */
    private float mDarkAmount;
    /**
     * Maximum translation to avoid burn in.
     */
    private int mBurnInOffset;
    /**
     * Height of the keyguard status bar (not the one after unlocking.)
     */
    private int mKeyguardStatusBarHeight;

    private final ViewClippingUtil.ClippingParameters mClippingParameters =
            view -> view instanceof StatusBarWindowView;

    public NotificationIconAreaController(Context context, StatusBar statusBar,
            StatusBarStateController statusBarStateController,
            NotificationListener notificationListener) {
        mStatusBar = statusBar;
        mContrastColorUtil = ContrastColorUtil.getInstance(context);
        mContext = context;
        mEntryManager = Dependency.get(NotificationEntryManager.class);
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(this);
        notificationListener.addNotificationSettingsListener(mSettingsListener);

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
        mNotificationIcons = mNotificationIconArea.findViewById(R.id.notificationIcons);

        mNotificationScrollLayout = mStatusBar.getNotificationScrollLayout();
    }

    public void setupShelf(NotificationShelf shelf) {
        mShelfIcons = shelf.getShelfIcons();
        shelf.setCollapsedIcons(mNotificationIcons);
    }

    public void onDensityOrFontScaleChanged(Context context) {
        reloadDimens(context);
        final FrameLayout.LayoutParams params = generateIconLayoutParams();
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            child.setLayoutParams(params);
        }
        for (int i = 0; i < mShelfIcons.getChildCount(); i++) {
            View child = mShelfIcons.getChildAt(i);
            child.setLayoutParams(params);
        }
    }

    @NonNull
    private FrameLayout.LayoutParams generateIconLayoutParams() {
        return new FrameLayout.LayoutParams(
                mIconSize + 2 * mIconHPadding, getHeight());
    }

    private void reloadDimens(Context context) {
        Resources res = context.getResources();
        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = res.getDimensionPixelSize(R.dimen.status_bar_icon_padding);
        mBurnInOffset = res.getDimensionPixelSize(R.dimen.default_burn_in_prevention_offset);
        mKeyguardStatusBarHeight = res
                .getDimensionPixelSize(R.dimen.status_bar_header_height_keyguard);
    }

    /**
     * Returns the view that represents the notification area.
     */
    public View getNotificationInnerAreaView() {
        return mNotificationIconArea;
    }

    /**
     * See {@link com.android.systemui.statusbar.policy.DarkIconDispatcher#setIconsDarkArea}.
     * Sets the color that should be used to tint any icons in the notification area.
     *
     * @param tintArea the area in which to tint the icons, specified in screen coordinates
     * @param darkIntensity
     */
    public void onDarkChanged(Rect tintArea, float darkIntensity, int iconTint) {
        if (tintArea == null) {
            mTintArea.setEmpty();
        } else {
            mTintArea.set(tintArea);
        }
        if (mNotificationIconArea != null) {
            if (DarkIconDispatcher.isInArea(tintArea, mNotificationIconArea)) {
                mIconTint = iconTint;
            }
        } else {
            mIconTint = iconTint;
        }

        applyNotificationIconsTint();
    }

    protected int getHeight() {
        return mStatusBar.getStatusBarHeight();
    }

    protected boolean shouldShowNotificationIcon(NotificationEntry entry,
            boolean showAmbient, boolean showLowPriority, boolean hideDismissed,
            boolean hideRepliedMessages) {
        if (mEntryManager.getNotificationData().isAmbient(entry.key) && !showAmbient) {
            return false;
        }
        if (!showLowPriority && !entry.isHighPriority()) {
            return false;
        }
        if (!entry.isTopLevelChild()) {
            return false;
        }
        if (entry.getRow().getVisibility() == View.GONE) {
            return false;
        }
        if (entry.isRowDismissed() && hideDismissed) {
            return false;
        }

        if (hideRepliedMessages && entry.isLastMessageFromReply()) {
            return false;
        }

        // showAmbient == show in shade but not shelf
        if (!showAmbient && entry.shouldSuppressStatusBar()) {
            return false;
        }

        return true;
    }

    /**
     * Updates the notifications with the given list of notifications to display.
     */
    public void updateNotificationIcons() {

        updateStatusBarIcons();
        updateShelfIcons();

        applyNotificationIconsTint();
    }

    private void updateShelfIcons() {
        updateIconsForLayout(entry -> entry.expandedIcon, mShelfIcons,
                true /* showAmbient */, !mFullyDark /* showLowPriority */,
                false /* hideDismissed */, mFullyDark /* hideRepliedMessages */);
    }

    public void updateStatusBarIcons() {
        updateIconsForLayout(entry -> entry.icon, mNotificationIcons,
                false /* showAmbient */, mShowLowPriority /* showLowPriority */,
                true /* hideDismissed */,
                true /* hideRepliedMessages */);
    }

    @VisibleForTesting
    boolean shouldShouldLowPriorityIcons() {
        return mShowLowPriority;
    }

    /**
     * Updates the notification icons for a host layout. This will ensure that the notification
     * host layout will have the same icons like the ones in here.
     * @param function A function to look up an icon view based on an entry
     * @param hostLayout which layout should be updated
     * @param showAmbient should ambient notification icons be shown
     * @param hideDismissed should dismissed icons be hidden
     * @param hideRepliedMessages should messages that have been replied to be hidden
     */
    private void updateIconsForLayout(Function<NotificationEntry, StatusBarIconView> function,
            NotificationIconContainer hostLayout, boolean showAmbient, boolean showLowPriority,
            boolean hideDismissed, boolean hideRepliedMessages) {
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(
                mNotificationScrollLayout.getChildCount());

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < mNotificationScrollLayout.getChildCount(); i++) {
            View view = mNotificationScrollLayout.getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                NotificationEntry ent = ((ExpandableNotificationRow) view).getEntry();
                if (shouldShowNotificationIcon(ent, showAmbient, showLowPriority, hideDismissed,
                        hideRepliedMessages)) {
                    toShow.add(function.apply(ent));
                }
            }
        }

        // In case we are changing the suppression of a group, the replacement shouldn't flicker
        // and it should just be replaced instead. We therefore look for notifications that were
        // just replaced by the child or vice-versa to suppress this.

        ArrayMap<String, ArrayList<StatusBarIcon>> replacingIcons = new ArrayMap<>();
        ArrayList<View> toRemove = new ArrayList<>();
        for (int i = 0; i < hostLayout.getChildCount(); i++) {
            View child = hostLayout.getChildAt(i);
            if (!(child instanceof StatusBarIconView)) {
                continue;
            }
            if (!toShow.contains(child)) {
                boolean iconWasReplaced = false;
                StatusBarIconView removedIcon = (StatusBarIconView) child;
                String removedGroupKey = removedIcon.getNotification().getGroupKey();
                for (int j = 0; j < toShow.size(); j++) {
                    StatusBarIconView candidate = toShow.get(j);
                    if (candidate.getSourceIcon().sameAs((removedIcon.getSourceIcon()))
                            && candidate.getNotification().getGroupKey().equals(removedGroupKey)) {
                        if (!iconWasReplaced) {
                            iconWasReplaced = true;
                        } else {
                            iconWasReplaced = false;
                            break;
                        }
                    }
                }
                if (iconWasReplaced) {
                    ArrayList<StatusBarIcon> statusBarIcons = replacingIcons.get(removedGroupKey);
                    if (statusBarIcons == null) {
                        statusBarIcons = new ArrayList<>();
                        replacingIcons.put(removedGroupKey, statusBarIcons);
                    }
                    statusBarIcons.add(removedIcon.getStatusBarIcon());
                }
                toRemove.add(removedIcon);
            }
        }
        // removing all duplicates
        ArrayList<String> duplicates = new ArrayList<>();
        for (String key : replacingIcons.keySet()) {
            ArrayList<StatusBarIcon> statusBarIcons = replacingIcons.get(key);
            if (statusBarIcons.size() != 1) {
                duplicates.add(key);
            }
        }
        replacingIcons.removeAll(duplicates);
        hostLayout.setReplacingIcons(replacingIcons);

        final int toRemoveCount = toRemove.size();
        for (int i = 0; i < toRemoveCount; i++) {
            hostLayout.removeView(toRemove.get(i));
        }

        final FrameLayout.LayoutParams params = generateIconLayoutParams();
        for (int i = 0; i < toShow.size(); i++) {
            StatusBarIconView v = toShow.get(i);
            // The view might still be transiently added if it was just removed and added again
            hostLayout.removeTransientView(v);
            if (v.getParent() == null) {
                if (hideDismissed) {
                    v.setOnDismissListener(mUpdateStatusBarIcons);
                }
                hostLayout.addView(v, i, params);
            }
        }

        hostLayout.setChangingViewPositions(true);
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
        hostLayout.setChangingViewPositions(false);
        hostLayout.setReplacingIcons(null);
    }

    /**
     * Applies {@link #mIconTint} to the notification icons.
     */
    private void applyNotificationIconsTint() {
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            final StatusBarIconView iv = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            if (iv.getWidth() != 0) {
                updateTintForIcon(iv);
            } else {
                iv.executeOnLayout(() -> updateTintForIcon(iv));
            }
        }
    }

    private void updateTintForIcon(StatusBarIconView v) {
        boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
        int color = StatusBarIconView.NO_COLOR;
        boolean colorize = !isPreL || NotificationUtils.isGrayscale(v, mContrastColorUtil);
        if (colorize) {
            color = DarkIconDispatcher.getTint(mTintArea, v, mIconTint);
        }
        v.setStaticDrawableColor(color);
        v.setDecorColor(mIconTint);
    }

    public void setDark(boolean dark) {
        mNotificationIcons.setDark(dark, false, 0);
        mShelfIcons.setDark(dark, false, 0);
    }

    public void showIconIsolated(StatusBarIconView icon, boolean animated) {
        mNotificationIcons.showIconIsolated(icon, animated);
    }

    public void setIsolatedIconLocation(Rect iconDrawingRect, boolean requireStateUpdate) {
        mNotificationIcons.setIsolatedIconLocation(iconDrawingRect, requireStateUpdate);
    }

    /**
     * Moves icons whenever the device wakes up in AOD, to avoid burn in.
     */
    public void dozeTimeTick() {
        if (mNotificationIcons.getVisibility() != View.VISIBLE) {
            return;
        }

        if (mDarkAmount == 0 && !mStatusBarStateController.isDozing()) {
            mNotificationIcons.setTranslationX(0);
            mNotificationIcons.setTranslationY(0);
            return;
        }

        int yOffset = (mKeyguardStatusBarHeight - getHeight()) / 2;
        int translationX = getBurnInOffset(mBurnInOffset, true /* xAxis */);
        int translationY = getBurnInOffset(mBurnInOffset, false /* xAxis */) + yOffset;
        mNotificationIcons.setTranslationX(translationX);
        mNotificationIcons.setTranslationY(translationY);
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        dozeTimeTick();
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        boolean wasOrIsAwake = mDarkAmount == 0 || linear == 0;
        boolean wasOrIsDozing = mDarkAmount == 1 || linear == 1;
        mDarkAmount = linear;
        if (wasOrIsAwake) {
            ViewClippingUtil.setClippingDeactivated(mNotificationIcons, mDarkAmount != 0,
                    mClippingParameters);
        }
        if (wasOrIsAwake || wasOrIsDozing) {
            dozeTimeTick();
        }

        boolean fullyDark = mDarkAmount == 1f;
        if (mFullyDark != fullyDark) {
            mFullyDark = fullyDark;
            updateShelfIcons();
        }
    }
}
