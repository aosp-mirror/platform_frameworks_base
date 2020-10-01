package com.android.systemui.statusbar.phone;

import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
public class NotificationIconAreaController implements DarkReceiver,
        StatusBarStateController.StateListener,
        NotificationWakeUpCoordinator.WakeUpListener {

    public static final String HIGH_PRIORITY = "high_priority";
    private static final long AOD_ICONS_APPEAR_DURATION = 200;

    private final ContrastColorUtil mContrastColorUtil;
    private final Runnable mUpdateStatusBarIcons = this::updateStatusBarIcons;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationMediaManager mMediaManager;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final KeyguardBypassController mBypassController;
    private final DozeParameters mDozeParameters;
    private final BubbleController mBubbleController;

    private int mIconSize;
    private int mIconHPadding;
    private int mIconTint = Color.WHITE;
    private int mCenteredIconTint = Color.WHITE;

    private StatusBar mStatusBar;
    protected View mNotificationIconArea;
    private NotificationIconContainer mNotificationIcons;
    private NotificationIconContainer mShelfIcons;
    protected View mCenteredIconArea;
    private NotificationIconContainer mCenteredIcon;
    private NotificationIconContainer mAodIcons;
    private StatusBarIconView mCenteredIconView;
    private final Rect mTintArea = new Rect();
    private ViewGroup mNotificationScrollLayout;
    private Context mContext;
    private int mAodIconAppearTranslation;

    private boolean mAnimationsEnabled;
    private int mAodIconTint;
    private boolean mFullyHidden;
    private boolean mAodIconsVisible;
    private boolean mIsPulsing;
    private boolean mShowLowPriority = true;

    @VisibleForTesting
    final NotificationListener.NotificationSettingsListener mSettingsListener =
            new NotificationListener.NotificationSettingsListener() {
                @Override
                public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
                        mShowLowPriority = !hideSilentStatusIcons;
                        if (mNotificationScrollLayout != null) {
                            updateStatusBarIcons();
                        }
                }
            };

    public NotificationIconAreaController(
            Context context,
            StatusBar statusBar,
            StatusBarStateController statusBarStateController,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            NotificationMediaManager notificationMediaManager,
            NotificationListener notificationListener,
            DozeParameters dozeParameters,
            BubbleController bubbleController) {
        mStatusBar = statusBar;
        mContrastColorUtil = ContrastColorUtil.getInstance(context);
        mContext = context;
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(this);
        mMediaManager = notificationMediaManager;
        mDozeParameters = dozeParameters;
        mWakeUpCoordinator = wakeUpCoordinator;
        wakeUpCoordinator.addListener(this);
        mBypassController = keyguardBypassController;
        mBubbleController = bubbleController;
        notificationListener.addNotificationSettingsListener(mSettingsListener);

        initializeNotificationAreaViews(context);
        reloadAodColor();
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

        mCenteredIconArea = layoutInflater.inflate(R.layout.center_icon_area, null);
        mCenteredIcon = mCenteredIconArea.findViewById(R.id.centeredIcon);

        initAodIcons();
    }

    public void initAodIcons() {
        boolean changed = mAodIcons != null;
        if (changed) {
            mAodIcons.setAnimationsEnabled(false);
            mAodIcons.removeAllViews();
        }
        mAodIcons = mStatusBar.getNotificationShadeWindowView().findViewById(
                R.id.clock_notification_icon_container);
        mAodIcons.setOnLockScreen(true);
        updateAodIconsVisibility(false /* animate */);
        updateAnimations();
        if (changed) {
            updateAodNotificationIcons();
        }
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
        for (int i = 0; i < mCenteredIcon.getChildCount(); i++) {
            View child = mCenteredIcon.getChildAt(i);
            child.setLayoutParams(params);
        }
        for (int i = 0; i < mAodIcons.getChildCount(); i++) {
            View child = mAodIcons.getChildAt(i);
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
        mAodIconAppearTranslation = res.getDimensionPixelSize(
                R.dimen.shelf_appear_translation);
    }

    /**
     * Returns the view that represents the notification area.
     */
    public View getNotificationInnerAreaView() {
        return mNotificationIconArea;
    }

    /**
     * Returns the view that represents the centered notification area.
     */
    public View getCenteredNotificationAreaView() {
        return mCenteredIconArea;
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

        if (mCenteredIconArea != null) {
            if (DarkIconDispatcher.isInArea(tintArea, mCenteredIconArea)) {
                mCenteredIconTint = iconTint;
            }
        } else {
            mCenteredIconTint = iconTint;
        }

        applyNotificationIconsTint();
    }

    protected int getHeight() {
        return mStatusBar.getStatusBarHeight();
    }

    protected boolean shouldShowNotificationIcon(NotificationEntry entry,
            boolean showAmbient, boolean showLowPriority, boolean hideDismissed,
            boolean hideRepliedMessages, boolean hideCurrentMedia, boolean hideCenteredIcon,
            boolean hidePulsing, boolean onlyShowCenteredIcon) {

        final boolean isCenteredNotificationIcon = mCenteredIconView != null
                && entry.getIcons().getCenteredIcon() != null
                && Objects.equals(entry.getIcons().getCenteredIcon(), mCenteredIconView);
        if (onlyShowCenteredIcon) {
            return isCenteredNotificationIcon;
        }
        if (hideCenteredIcon && isCenteredNotificationIcon && !entry.isRowHeadsUp()) {
            return false;
        }
        if (entry.getRanking().isAmbient() && !showAmbient) {
            return false;
        }
        if (hideCurrentMedia && entry.getKey().equals(mMediaManager.getMediaNotificationKey())) {
            return false;
        }
        if (!showLowPriority && entry.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
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
        if (hidePulsing && entry.showingPulsing()
                && (!mWakeUpCoordinator.getNotificationsFullyHidden()
                        || !entry.isPulseSuppressed())) {
            return false;
        }
        if (mBubbleController.isBubbleExpanded(entry)) {
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
        updateCenterIcon();
        updateAodNotificationIcons();

        applyNotificationIconsTint();
    }

    private void updateShelfIcons() {
        updateIconsForLayout(entry -> entry.getIcons().getShelfIcon(), mShelfIcons,
                true /* showAmbient */,
                true /* showLowPriority */,
                false /* hideDismissed */,
                false /* hideRepliedMessages */,
                false /* hideCurrentMedia */,
                false /* hide centered icon */,
                false /* hidePulsing */,
                false /* onlyShowCenteredIcon */);
    }

    public void updateStatusBarIcons() {
        updateIconsForLayout(entry -> entry.getIcons().getStatusBarIcon(), mNotificationIcons,
                false /* showAmbient */,
                mShowLowPriority,
                true /* hideDismissed */,
                true /* hideRepliedMessages */,
                false /* hideCurrentMedia */,
                true /* hide centered icon */,
                false /* hidePulsing */,
                false /* onlyShowCenteredIcon */);
    }

    private void updateCenterIcon() {
        updateIconsForLayout(entry -> entry.getIcons().getCenteredIcon(), mCenteredIcon,
                false /* showAmbient */,
                true /* showLowPriority */,
                false /* hideDismissed */,
                false /* hideRepliedMessages */,
                false /* hideCurrentMedia */,
                false /* hide centered icon */,
                false /* hidePulsing */,
                true/* onlyShowCenteredIcon */);
    }

    public void updateAodNotificationIcons() {
        updateIconsForLayout(entry -> entry.getIcons().getAodIcon(), mAodIcons,
                false /* showAmbient */,
                true /* showLowPriority */,
                true /* hideDismissed */,
                true /* hideRepliedMessages */,
                true /* hideCurrentMedia */,
                true /* hide centered icon */,
                mBypassController.getBypassEnabled() /* hidePulsing */,
                false /* onlyShowCenteredIcon */);
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
     * @param showLowPriority should icons from silent notifications be shown
     * @param hideDismissed should dismissed icons be hidden
     * @param hideRepliedMessages should messages that have been replied to be hidden
     * @param hidePulsing should pulsing notifications be hidden
     */
    private void updateIconsForLayout(Function<NotificationEntry, StatusBarIconView> function,
            NotificationIconContainer hostLayout, boolean showAmbient, boolean showLowPriority,
            boolean hideDismissed, boolean hideRepliedMessages, boolean hideCurrentMedia,
            boolean hideCenteredIcon, boolean hidePulsing, boolean onlyShowCenteredIcon) {
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(
                mNotificationScrollLayout.getChildCount());

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < mNotificationScrollLayout.getChildCount(); i++) {
            View view = mNotificationScrollLayout.getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                NotificationEntry ent = ((ExpandableNotificationRow) view).getEntry();
                if (shouldShowNotificationIcon(ent, showAmbient, showLowPriority, hideDismissed,
                        hideRepliedMessages, hideCurrentMedia, hideCenteredIcon, hidePulsing,
                        onlyShowCenteredIcon)) {
                    StatusBarIconView iconView = function.apply(ent);
                    if (iconView != null) {
                        toShow.add(iconView);
                    }
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
     * Applies {@link #mCenteredIconTint} to the center notification icon.
     */
    private void applyNotificationIconsTint() {
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            final StatusBarIconView iv = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            if (iv.getWidth() != 0) {
                updateTintForIcon(iv, mIconTint);
            } else {
                iv.executeOnLayout(() -> updateTintForIcon(iv, mIconTint));
            }
        }

        for (int i = 0; i < mCenteredIcon.getChildCount(); i++) {
            final StatusBarIconView iv = (StatusBarIconView) mCenteredIcon.getChildAt(i);
            if (iv.getWidth() != 0) {
                updateTintForIcon(iv, mCenteredIconTint);
            } else {
                iv.executeOnLayout(() -> updateTintForIcon(iv, mCenteredIconTint));
            }
        }

        updateAodIconColors();
    }

    private void updateTintForIcon(StatusBarIconView v, int tint) {
        boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
        int color = StatusBarIconView.NO_COLOR;
        boolean colorize = !isPreL || NotificationUtils.isGrayscale(v, mContrastColorUtil);
        if (colorize) {
            color = DarkIconDispatcher.getTint(mTintArea, v, tint);
        }
        v.setStaticDrawableColor(color);
        v.setDecorColor(tint);
    }

    /**
     * Shows the icon view given in the center.
     */
    public void showIconCentered(NotificationEntry entry) {
        StatusBarIconView icon = entry == null ? null : entry.getIcons().getCenteredIcon();
        if (!Objects.equals(mCenteredIconView, icon)) {
            mCenteredIconView = icon;
            updateNotificationIcons();
        }
    }

    public void showIconIsolated(StatusBarIconView icon, boolean animated) {
        mNotificationIcons.showIconIsolated(icon, animated);
    }

    public void setIsolatedIconLocation(Rect iconDrawingRect, boolean requireStateUpdate) {
        mNotificationIcons.setIsolatedIconLocation(iconDrawingRect, requireStateUpdate);
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        boolean animate = mDozeParameters.getAlwaysOn()
                && !mDozeParameters.getDisplayNeedsBlanking();
        mAodIcons.setDozing(isDozing, animate, 0);
    }

    public void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
        updateAnimations();
    }

    @Override
    public void onStateChanged(int newState) {
        updateAodIconsVisibility(false /* animate */);
        updateAnimations();
    }

    private void updateAnimations() {
        boolean inShade = mStatusBarStateController.getState() == StatusBarState.SHADE;
        mAodIcons.setAnimationsEnabled(mAnimationsEnabled && !inShade);
        mCenteredIcon.setAnimationsEnabled(mAnimationsEnabled && inShade);
        mNotificationIcons.setAnimationsEnabled(mAnimationsEnabled && inShade);
    }

    public void onThemeChanged() {
        reloadAodColor();
        updateAodIconColors();
    }

    public void appearAodIcons() {
        if (mDozeParameters.shouldControlScreenOff()) {
            mAodIcons.setTranslationY(-mAodIconAppearTranslation);
            mAodIcons.setAlpha(0);
            animateInAodIconTranslation();
            mAodIcons.animate()
                    .alpha(1)
                    .setInterpolator(Interpolators.LINEAR)
                    .setDuration(AOD_ICONS_APPEAR_DURATION)
                    .start();
        } else {
            mAodIcons.setAlpha(1.0f);
            mAodIcons.setTranslationY(0);
        }
    }

    private void animateInAodIconTranslation() {
        mAodIcons.animate()
                .setInterpolator(Interpolators.DECELERATE_QUINT)
                .translationY(0)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .start();
    }

    private void reloadAodColor() {
        mAodIconTint = Utils.getColorAttrDefaultColor(mContext,
                R.attr.wallpaperTextColor);
    }
    private void updateAodIconColors() {
        for (int i = 0; i < mAodIcons.getChildCount(); i++) {
            final StatusBarIconView iv = (StatusBarIconView) mAodIcons.getChildAt(i);
            if (iv.getWidth() != 0) {
                updateTintForIcon(iv, mAodIconTint);
            } else {
                iv.executeOnLayout(() -> updateTintForIcon(iv, mAodIconTint));
            }
        }
    }

    @Override
    public void onFullyHiddenChanged(boolean fullyHidden) {
        boolean animate = true;
        if (!mBypassController.getBypassEnabled()) {
            animate = mDozeParameters.getAlwaysOn() && !mDozeParameters.getDisplayNeedsBlanking();
            // We only want the appear animations to happen when the notifications get fully hidden,
            // since otherwise the unhide animation overlaps
            animate &= fullyHidden;
        }
        updateAodIconsVisibility(animate);
        updateAodNotificationIcons();
    }

    @Override
    public void onPulseExpansionChanged(boolean expandingChanged) {
        if (expandingChanged) {
            updateAodIconsVisibility(true /* animate */);
        }
    }

    private void updateAodIconsVisibility(boolean animate) {
        boolean visible = mBypassController.getBypassEnabled()
                || mWakeUpCoordinator.getNotificationsFullyHidden();
        if (mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            visible = false;
        }
        if (visible && mWakeUpCoordinator.isPulseExpanding()) {
            visible = false;
        }
        if (mAodIconsVisible != visible) {
            mAodIconsVisible = visible;
            mAodIcons.animate().cancel();
            if (animate) {
                boolean wasFullyInvisible = mAodIcons.getVisibility() != View.VISIBLE;
                if (mAodIconsVisible) {
                    if (wasFullyInvisible) {
                        // No fading here, let's just appear the icons instead!
                        mAodIcons.setVisibility(View.VISIBLE);
                        mAodIcons.setAlpha(1.0f);
                        appearAodIcons();
                    } else {
                        // Let's make sure the icon are translated to 0, since we cancelled it above
                        animateInAodIconTranslation();
                        // We were fading out, let's fade in instead
                        CrossFadeHelper.fadeIn(mAodIcons);
                    }
                } else {
                    // Let's make sure the icon are translated to 0, since we cancelled it above
                    animateInAodIconTranslation();
                    CrossFadeHelper.fadeOut(mAodIcons);
                }
            } else {
                mAodIcons.setAlpha(1.0f);
                mAodIcons.setTranslationY(0);
                mAodIcons.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }
}
