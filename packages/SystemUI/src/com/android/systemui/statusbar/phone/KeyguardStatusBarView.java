/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.ScreenDecorations.DisplayCutoutView.boundsFromDirection;
import static com.android.systemui.util.Utils.getStatusBarHeaderHeightKeyguard;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * The header group on Keyguard.
 */
public class KeyguardStatusBarView extends RelativeLayout {

    private static final int LAYOUT_NONE = 0;
    private static final int LAYOUT_CUTOUT = 1;
    private static final int LAYOUT_NO_CUTOUT = 2;

    private final ArrayList<Rect> mEmptyTintRect = new ArrayList<>();

    private boolean mShowPercentAvailable;
    private boolean mBatteryCharging;

    private TextView mCarrierLabel;
    private ImageView mMultiUserAvatar;
    private BatteryMeterView mBatteryView;
    private StatusIconContainer mStatusIconContainer;
    private ViewGroup mUserSwitcherContainer;

    private boolean mKeyguardUserSwitcherEnabled;
    private boolean mKeyguardUserAvatarEnabled;

    private boolean mIsPrivacyDotEnabled;
    private int mSystemIconsSwitcherHiddenExpandedMargin;
    private int mStatusBarPaddingEnd;
    private int mMinDotWidth;
    private View mSystemIconsContainer;

    private View mCutoutSpace;
    private ViewGroup mStatusIconArea;
    private int mLayoutState = LAYOUT_NONE;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    private DisplayCutout mDisplayCutout;
    private int mRoundedCornerPadding = 0;
    // right and left padding applied to this view to account for cutouts and rounded corners
    private Pair<Integer, Integer> mPadding = new Pair(0, 0);

    /**
     * The clipping on the top
     */
    private int mTopClipping;
    private final Rect mClipRect = new Rect(0, 0, 0, 0);
    private boolean mIsUserSwitcherEnabled;

    public KeyguardStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsContainer = findViewById(R.id.system_icons_container);
        mMultiUserAvatar = findViewById(R.id.multi_user_avatar);
        mCarrierLabel = findViewById(R.id.keyguard_carrier_text);
        mBatteryView = mSystemIconsContainer.findViewById(R.id.battery);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusIconArea = findViewById(R.id.status_icon_area);
        mStatusIconContainer = findViewById(R.id.statusIcons);
        mUserSwitcherContainer = findViewById(R.id.user_switcher_container);
        mIsPrivacyDotEnabled = mContext.getResources().getBoolean(R.bool.config_enablePrivacyDot);
        loadDimens();
    }

    public ViewGroup getUserSwitcherContainer() {
        return mUserSwitcherContainer;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        loadDimens();

        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserAvatar.getLayoutParams();
        lp.width = lp.height = getResources().getDimensionPixelSize(
                R.dimen.multi_user_avatar_keyguard_size);
        mMultiUserAvatar.setLayoutParams(lp);

        // System icons
        updateSystemIconsLayoutParams();

        // mStatusIconArea
        mStatusIconArea.setPaddingRelative(
                mStatusIconArea.getPaddingStart(),
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                mStatusIconArea.getPaddingEnd(),
                mStatusIconArea.getPaddingBottom()
        );

        // mStatusIconContainer
        mStatusIconContainer.setPaddingRelative(
                mStatusIconContainer.getPaddingStart(),
                mStatusIconContainer.getPaddingTop(),
                getResources().getDimensionPixelSize(R.dimen.signal_cluster_battery_padding),
                mStatusIconContainer.getPaddingBottom()
        );

        // Respect font size setting.
        mCarrierLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));
        lp = (MarginLayoutParams) mCarrierLabel.getLayoutParams();

        int marginStart = calculateMargin(
                getResources().getDimensionPixelSize(R.dimen.keyguard_carrier_text_margin),
                mPadding.first);
        lp.setMarginStart(marginStart);

        mCarrierLabel.setLayoutParams(lp);
        updateKeyguardStatusBarHeight();
    }

    public void setUserSwitcherEnabled(boolean enabled) {
        mIsUserSwitcherEnabled = enabled;
    }

    private void updateKeyguardStatusBarHeight() {
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.height = getStatusBarHeaderHeightKeyguard(mContext);
        setLayoutParams(lp);
    }

    void loadDimens() {
        Resources res = getResources();
        mSystemIconsSwitcherHiddenExpandedMargin = res.getDimensionPixelSize(
                R.dimen.system_icons_switcher_hidden_expanded_margin);
        mStatusBarPaddingEnd = res.getDimensionPixelSize(
                R.dimen.status_bar_padding_end);
        mMinDotWidth = res.getDimensionPixelSize(
                R.dimen.ongoing_appops_dot_min_padding);
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);
        mShowPercentAvailable = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_battery_percentage_setting_available);
        mRoundedCornerPadding = res.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
    }

    private void updateVisibilities() {
        // Multi user avatar is disabled in favor of the user switcher chip
        if (!mKeyguardUserAvatarEnabled) {
            if (mMultiUserAvatar.getParent() == mStatusIconArea) {
                mStatusIconArea.removeView(mMultiUserAvatar);
            } else if (mMultiUserAvatar.getParent() != null) {
                getOverlay().remove(mMultiUserAvatar);
            }

            return;
        }

        if (mMultiUserAvatar.getParent() != mStatusIconArea
                && !mKeyguardUserSwitcherEnabled) {
            if (mMultiUserAvatar.getParent() != null) {
                getOverlay().remove(mMultiUserAvatar);
            }
            mStatusIconArea.addView(mMultiUserAvatar, 0);
        } else if (mMultiUserAvatar.getParent() == mStatusIconArea
                && mKeyguardUserSwitcherEnabled) {
            mStatusIconArea.removeView(mMultiUserAvatar);
        }
        if (!mKeyguardUserSwitcherEnabled) {
            // If we have no keyguard switcher, the screen width is under 600dp. In this case,
            // we only show the multi-user switch if it's enabled through UserManager as well as
            // by the user.
            if (mIsUserSwitcherEnabled) {
                mMultiUserAvatar.setVisibility(View.VISIBLE);
            } else {
                mMultiUserAvatar.setVisibility(View.GONE);
            }
        }
        mBatteryView.setForceShowPercent(mBatteryCharging && mShowPercentAvailable);
    }

    private void updateSystemIconsLayoutParams() {
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mSystemIconsContainer.getLayoutParams();

        // Use status_bar_padding_end to replace original
        // system_icons_super_container_avatarless_margin_end to prevent different end alignment
        // between PhoneStatusBarView and KeyguardStatusBarView
        int baseMarginEnd = mStatusBarPaddingEnd;
        int marginEnd =
                mKeyguardUserSwitcherEnabled ? mSystemIconsSwitcherHiddenExpandedMargin
                        : baseMarginEnd;

        // Align PhoneStatusBar right margin/padding, only use
        // 1. status bar layout: mPadding(consider round_corner + privacy dot)
        // 2. icon container: R.dimen.status_bar_padding_end

        if (marginEnd != lp.getMarginEnd()) {
            lp.setMarginEnd(marginEnd);
            mSystemIconsContainer.setLayoutParams(lp);
        }
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    WindowInsets updateWindowInsets(
            WindowInsets insets,
            StatusBarContentInsetsProvider insetsProvider) {
        mLayoutState = LAYOUT_NONE;
        if (updateLayoutConsideringCutout(insetsProvider)) {
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    private boolean updateLayoutConsideringCutout(StatusBarContentInsetsProvider insetsProvider) {
        mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        updateKeyguardStatusBarHeight();
        updatePadding(insetsProvider);
        if (mDisplayCutout == null || insetsProvider.currentRotationHasCornerCutout()) {
            return updateLayoutParamsNoCutout();
        } else {
            return updateLayoutParamsForCutout();
        }
    }

    private void updatePadding(StatusBarContentInsetsProvider insetsProvider) {
        final int waterfallTop =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        mPadding = insetsProvider.getStatusBarContentInsetsForCurrentRotation();

        // consider privacy dot space
        final int minLeft = (isLayoutRtl() && mIsPrivacyDotEnabled)
                ? Math.max(mMinDotWidth, mPadding.first) : mPadding.first;
        final int minRight = (!isLayoutRtl() && mIsPrivacyDotEnabled)
                ? Math.max(mMinDotWidth, mPadding.second) : mPadding.second;

        setPadding(minLeft, waterfallTop, minRight, 0);
    }

    private boolean updateLayoutParamsNoCutout() {
        if (mLayoutState == LAYOUT_NO_CUTOUT) {
            return false;
        }
        mLayoutState = LAYOUT_NO_CUTOUT;

        if (mCutoutSpace != null) {
            mCutoutSpace.setVisibility(View.GONE);
        }

        RelativeLayout.LayoutParams lp = (LayoutParams) mCarrierLabel.getLayoutParams();
        lp.addRule(RelativeLayout.START_OF, R.id.status_icon_area);

        lp = (LayoutParams) mStatusIconArea.getLayoutParams();
        lp.removeRule(RelativeLayout.RIGHT_OF);
        lp.width = LayoutParams.WRAP_CONTENT;

        LinearLayout.LayoutParams llp =
                (LinearLayout.LayoutParams) mSystemIconsContainer.getLayoutParams();
        llp.setMarginStart(getResources().getDimensionPixelSize(
                R.dimen.system_icons_super_container_margin_start));
        return true;
    }

    private boolean updateLayoutParamsForCutout() {
        if (mLayoutState == LAYOUT_CUTOUT) {
            return false;
        }
        mLayoutState = LAYOUT_CUTOUT;

        if (mCutoutSpace == null) {
            updateLayoutParamsNoCutout();
        }

        Rect bounds = new Rect();
        boundsFromDirection(mDisplayCutout, Gravity.TOP, bounds);

        mCutoutSpace.setVisibility(View.VISIBLE);
        RelativeLayout.LayoutParams lp = (LayoutParams) mCutoutSpace.getLayoutParams();
        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);

        lp = (LayoutParams) mCarrierLabel.getLayoutParams();
        lp.addRule(RelativeLayout.START_OF, R.id.cutout_space_view);

        lp = (LayoutParams) mStatusIconArea.getLayoutParams();
        lp.addRule(RelativeLayout.RIGHT_OF, R.id.cutout_space_view);
        lp.width = LayoutParams.MATCH_PARENT;

        LinearLayout.LayoutParams llp =
                (LinearLayout.LayoutParams) mSystemIconsContainer.getLayoutParams();
        llp.setMarginStart(0);
        return true;
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    void onUserInfoChanged(Drawable picture) {
        mMultiUserAvatar.setImageDrawable(picture);
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    void onBatteryLevelChanged(boolean charging) {
        if (mBatteryCharging != charging) {
            mBatteryCharging = charging;
            updateVisibilities();
        }
    }

    void setKeyguardUserSwitcherEnabled(boolean enabled) {
        mKeyguardUserSwitcherEnabled = enabled;
    }

    void setKeyguardUserAvatarEnabled(boolean enabled) {
        mKeyguardUserAvatarEnabled = enabled;
        updateVisibilities();
    }

    @VisibleForTesting
    boolean isKeyguardUserAvatarEnabled() {
        return mKeyguardUserAvatarEnabled;
    }

    private void animateNextLayoutChange() {
        final int systemIconsCurrentX = mSystemIconsContainer.getLeft();
        final boolean userAvatarVisible = mMultiUserAvatar.getParent() == mStatusIconArea;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                boolean userAvatarHiding = userAvatarVisible
                        && mMultiUserAvatar.getParent() != mStatusIconArea;
                mSystemIconsContainer.setX(systemIconsCurrentX);
                mSystemIconsContainer.animate()
                        .translationX(0)
                        .setDuration(400)
                        .setStartDelay(userAvatarHiding ? 300 : 0)
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .start();
                if (userAvatarHiding) {
                    getOverlay().add(mMultiUserAvatar);
                    mMultiUserAvatar.animate()
                            .alpha(0f)
                            .setDuration(300)
                            .setStartDelay(0)
                            .setInterpolator(Interpolators.ALPHA_OUT)
                            .withEndAction(() -> {
                                mMultiUserAvatar.setAlpha(1f);
                                getOverlay().remove(mMultiUserAvatar);
                            })
                            .start();

                } else {
                    mMultiUserAvatar.setAlpha(0f);
                    mMultiUserAvatar.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .setStartDelay(200)
                            .setInterpolator(Interpolators.ALPHA_IN);
                }
                return true;
            }
        });

    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE) {
            mSystemIconsContainer.animate().cancel();
            mSystemIconsContainer.setTranslationX(0);
            mMultiUserAvatar.animate().cancel();
            mMultiUserAvatar.setAlpha(1f);
        } else {
            updateVisibilities();
            updateSystemIconsLayoutParams();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    void onThemeChanged(StatusBarIconController.TintedIconManager iconManager) {
        mBatteryView.setColorsFromContext(mContext);
        updateIconsAndTextColors(iconManager);
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    void onOverlayChanged() {
        int theme = Utils.getThemeAttr(mContext, com.android.internal.R.attr.textAppearanceSmall);
        mCarrierLabel.setTextAppearance(theme);
        mBatteryView.updatePercentView();

        TextView userSwitcherName = mUserSwitcherContainer.findViewById(R.id.current_user_name);
        if (userSwitcherName != null) {
            userSwitcherName.setTextAppearance(theme);
        }
    }

    private void updateIconsAndTextColors(StatusBarIconController.TintedIconManager iconManager) {
        @ColorInt int textColor = Utils.getColorAttrDefaultColor(mContext,
                R.attr.wallpaperTextColor);
        @ColorInt int iconColor = Utils.getColorStateListDefaultColor(mContext,
                Color.luminance(textColor) < 0.5 ? R.color.dark_mode_icon_color_single_tone :
                R.color.light_mode_icon_color_single_tone);
        float intensity = textColor == Color.WHITE ? 0 : 1;
        mCarrierLabel.setTextColor(iconColor);

        TextView userSwitcherName = mUserSwitcherContainer.findViewById(R.id.current_user_name);
        if (userSwitcherName != null) {
            userSwitcherName.setTextColor(Utils.getColorStateListDefaultColor(
                    mContext,
                    R.color.light_mode_icon_color_single_tone));
        }

        if (iconManager != null) {
            iconManager.setTint(iconColor);
        }

        applyDarkness(R.id.battery, mEmptyTintRect, intensity, iconColor);
        applyDarkness(R.id.clock, mEmptyTintRect, intensity, iconColor);
    }

    private void applyDarkness(int id, ArrayList<Rect> tintAreas, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintAreas, intensity, color);
        }
    }

    /**
     * Calculates the margin that isn't already accounted for in the view's padding.
     */
    private int calculateMargin(int margin, int padding) {
        if (padding >= margin) {
            return 0;
        } else {
            return margin - padding;
        }
    }

    /** Should only be called from {@link KeyguardStatusBarViewController}. */
    void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusBarView:");
        pw.println("  mBatteryCharging: " + mBatteryCharging);
        pw.println("  mLayoutState: " + mLayoutState);
        pw.println("  mKeyguardUserSwitcherEnabled: " + mKeyguardUserSwitcherEnabled);
        if (mBatteryView != null) {
            mBatteryView.dump(pw, args);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateClipping();
    }

    /**
     * Set the clipping on the top of the view.
     *
     * Should only be called from {@link KeyguardStatusBarViewController}.
     */
    void setTopClipping(int topClipping) {
        if (topClipping != mTopClipping) {
            mTopClipping = topClipping;
            updateClipping();
        }
    }

    private void updateClipping() {
        mClipRect.set(0, mTopClipping, getWidth(), getHeight());
        setClipBounds(mClipRect);
    }
}
