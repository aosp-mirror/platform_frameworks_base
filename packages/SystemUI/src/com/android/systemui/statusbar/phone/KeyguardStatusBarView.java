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

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * The header group on Keyguard.
 */
public class KeyguardStatusBarView extends RelativeLayout
        implements BatteryStateChangeCallback, OnUserInfoChangedListener, ConfigurationListener {

    private static final int LAYOUT_NONE = 0;
    private static final int LAYOUT_CUTOUT = 1;
    private static final int LAYOUT_NO_CUTOUT = 2;

    private boolean mBatteryCharging;
    private boolean mKeyguardUserSwitcherShowing;
    private boolean mBatteryListening;

    private TextView mCarrierLabel;
    private View mSystemIconsSuperContainer;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private BatteryMeterView mBatteryView;

    private BatteryController mBatteryController;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private UserSwitcherController mUserSwitcherController;

    private int mSystemIconsSwitcherHiddenExpandedMargin;
    private int mSystemIconsBaseMargin;
    private View mSystemIconsContainer;
    private TintedIconManager mIconManager;

    private View mCutoutSpace;
    private ViewGroup mStatusIconArea;
    private int mLayoutState = LAYOUT_NONE;

    public KeyguardStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsContainer = findViewById(R.id.system_icons_container);
        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = findViewById(R.id.multi_user_avatar);
        mCarrierLabel = findViewById(R.id.keyguard_carrier_text);
        mBatteryView = mSystemIconsContainer.findViewById(R.id.battery);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusIconArea = findViewById(R.id.status_icon_area);

        loadDimens();
        updateUserSwitcher();
        mBatteryController = Dependency.get(BatteryController.class);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserAvatar.getLayoutParams();
        lp.width = lp.height = getResources().getDimensionPixelSize(
                R.dimen.multi_user_avatar_keyguard_size);
        mMultiUserAvatar.setLayoutParams(lp);

        // Multi-user switch
        lp = (MarginLayoutParams) mMultiUserSwitch.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(
                R.dimen.multi_user_switch_width_keyguard);
        lp.setMarginEnd(getResources().getDimensionPixelSize(
                R.dimen.multi_user_switch_keyguard_margin));
        mMultiUserSwitch.setLayoutParams(lp);

        // System icons
        lp = (MarginLayoutParams) mStatusIconArea.getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        lp = (MarginLayoutParams) mSystemIconsContainer.getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        lp.setMarginStart(getResources().getDimensionPixelSize(
                R.dimen.system_icons_super_container_margin_start));
        mSystemIconsContainer.setLayoutParams(lp);
        mSystemIconsContainer.setPaddingRelative(mSystemIconsContainer.getPaddingStart(),
                mSystemIconsContainer.getPaddingTop(),
                getResources().getDimensionPixelSize(R.dimen.system_icons_keyguard_padding_end),
                mSystemIconsContainer.getPaddingBottom());

        // Respect font size setting.
        mCarrierLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));
        lp = (MarginLayoutParams) mCarrierLabel.getLayoutParams();
        lp.setMarginStart(
                getResources().getDimensionPixelSize(R.dimen.keyguard_carrier_text_margin));
        mCarrierLabel.setLayoutParams(lp);

        lp = (MarginLayoutParams) getLayoutParams();
        lp.height =  getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_keyguard);
        setLayoutParams(lp);
    }

    private void loadDimens() {
        Resources res = getResources();
        mSystemIconsSwitcherHiddenExpandedMargin = res.getDimensionPixelSize(
                R.dimen.system_icons_switcher_hidden_expanded_margin);
        mSystemIconsBaseMargin = res.getDimensionPixelSize(
                R.dimen.system_icons_super_container_avatarless_margin_end);
    }

    private void updateVisibilities() {
        if (mMultiUserSwitch.getParent() != mStatusIconArea && !mKeyguardUserSwitcherShowing) {
            if (mMultiUserSwitch.getParent() != null) {
                getOverlay().remove(mMultiUserSwitch);
            }
            mStatusIconArea.addView(mMultiUserSwitch, 0);
        } else if (mMultiUserSwitch.getParent() == mStatusIconArea && mKeyguardUserSwitcherShowing) {
            mStatusIconArea.removeView(mMultiUserSwitch);
        }
        if (mKeyguardUserSwitcher == null) {
            // If we have no keyguard switcher, the screen width is under 600dp. In this case,
            // we don't show the multi-user avatar unless there is more than 1 user on the device.
            if (mUserSwitcherController != null
                    && mUserSwitcherController.getSwitchableUserCount() > 1) {
                mMultiUserSwitch.setVisibility(View.VISIBLE);
            } else {
                mMultiUserSwitch.setVisibility(View.GONE);
            }
        }
        mBatteryView.setForceShowPercent(mBatteryCharging);
    }

    private void updateSystemIconsLayoutParams() {
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mSystemIconsContainer.getLayoutParams();
        // If the avatar icon is gone, we need to have some end margin to display the system icons
        // correctly.
        int baseMarginEnd = mMultiUserSwitch.getVisibility() == View.GONE
                ? mSystemIconsBaseMargin
                : 0;
        int marginEnd = mKeyguardUserSwitcherShowing ? mSystemIconsSwitcherHiddenExpandedMargin :
                baseMarginEnd;
        if (marginEnd != lp.getMarginEnd()) {
            lp.setMarginEnd(marginEnd);
            mSystemIconsContainer.setLayoutParams(lp);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mLayoutState = LAYOUT_NONE;
        if (updateLayoutConsideringCutout()) {
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    private boolean updateLayoutConsideringCutout() {
        DisplayCutout dc = getRootWindowInsets().getDisplayCutout();
        Pair<Integer, Integer> cornerCutoutMargins =
                PhoneStatusBarView.cornerCutoutMargins(dc, getDisplay());
        updateCornerCutoutPadding(cornerCutoutMargins);
        if (dc == null || cornerCutoutMargins != null) {
            return updateLayoutParamsNoCutout();
        } else {
            return updateLayoutParamsForCutout(dc);
        }
    }

    private void updateCornerCutoutPadding(Pair<Integer, Integer> cornerCutoutMargins) {
        if (cornerCutoutMargins != null) {
            setPadding(cornerCutoutMargins.first, 0, cornerCutoutMargins.second, 0);
        } else {
            setPadding(0, 0, 0, 0);
        }
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

    private boolean updateLayoutParamsForCutout(DisplayCutout dc) {
        if (mLayoutState == LAYOUT_CUTOUT) {
            return false;
        }
        mLayoutState = LAYOUT_CUTOUT;

        if (mCutoutSpace == null) {
            updateLayoutParamsNoCutout();
        }

        Rect bounds = new Rect();
        boundsFromDirection(dc, Gravity.TOP, bounds);

        mCutoutSpace.setVisibility(View.VISIBLE);
        RelativeLayout.LayoutParams lp = (LayoutParams) mCutoutSpace.getLayoutParams();
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

    public void setListening(boolean listening) {
        if (listening == mBatteryListening) {
            return;
        }
        mBatteryListening = listening;
        if (mBatteryListening) {
            mBatteryController.addCallback(this);
        } else {
            mBatteryController.removeCallback(this);
        }
    }

    private void updateUserSwitcher() {
        boolean keyguardSwitcherAvailable = mKeyguardUserSwitcher != null;
        mMultiUserSwitch.setClickable(keyguardSwitcherAvailable);
        mMultiUserSwitch.setFocusable(keyguardSwitcherAvailable);
        mMultiUserSwitch.setKeyguardMode(keyguardSwitcherAvailable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback(this);
        mUserSwitcherController = Dependency.get(UserSwitcherController.class);
        mMultiUserSwitch.setUserSwitcherController(mUserSwitcherController);
        userInfoController.reloadUserInfo();
        Dependency.get(ConfigurationController.class).addCallback(this);
        mIconManager = new TintedIconManager(findViewById(R.id.statusIcons));
        Dependency.get(StatusBarIconController.class).addIconGroup(mIconManager);
        onThemeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(UserInfoController.class).removeCallback(this);
        Dependency.get(StatusBarIconController.class).removeIconGroup(mIconManager);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mMultiUserAvatar.setImageDrawable(picture);
    }

    public void setQSPanel(QSPanel qsp) {
        mMultiUserSwitch.setQsPanel(qsp);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        if (mBatteryCharging != charging) {
            mBatteryCharging = charging;
            updateVisibilities();
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        // could not care less
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
        mMultiUserSwitch.setKeyguardUserSwitcher(keyguardUserSwitcher);
        updateUserSwitcher();
    }

    public void setKeyguardUserSwitcherShowing(boolean showing, boolean animate) {
        mKeyguardUserSwitcherShowing = showing;
        if (animate) {
            animateNextLayoutChange();
        }
        updateVisibilities();
        updateLayoutConsideringCutout();
        updateSystemIconsLayoutParams();
    }

    private void animateNextLayoutChange() {
        final int systemIconsCurrentX = mSystemIconsContainer.getLeft();
        final boolean userSwitcherVisible = mMultiUserSwitch.getParent() == mStatusIconArea;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                boolean userSwitcherHiding = userSwitcherVisible
                        && mMultiUserSwitch.getParent() != mStatusIconArea;
                mSystemIconsContainer.setX(systemIconsCurrentX);
                mSystemIconsContainer.animate()
                        .translationX(0)
                        .setDuration(400)
                        .setStartDelay(userSwitcherHiding ? 300 : 0)
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .start();
                if (userSwitcherHiding) {
                    getOverlay().add(mMultiUserSwitch);
                    mMultiUserSwitch.animate()
                            .alpha(0f)
                            .setDuration(300)
                            .setStartDelay(0)
                            .setInterpolator(Interpolators.ALPHA_OUT)
                            .withEndAction(() -> {
                                mMultiUserSwitch.setAlpha(1f);
                                getOverlay().remove(mMultiUserSwitch);
                            })
                            .start();

                } else {
                    mMultiUserSwitch.setAlpha(0f);
                    mMultiUserSwitch.animate()
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
            mMultiUserSwitch.animate().cancel();
            mMultiUserSwitch.setAlpha(1f);
        } else {
            updateVisibilities();
            updateSystemIconsLayoutParams();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void onThemeChanged() {
        @ColorInt int textColor = Utils.getColorAttr(mContext, R.attr.wallpaperTextColor);
        @ColorInt int iconColor = Utils.getDefaultColor(mContext, Color.luminance(textColor) < 0.5 ?
                R.color.dark_mode_icon_color_single_tone :
                R.color.light_mode_icon_color_single_tone);
        float intensity = textColor == Color.WHITE ? 0 : 1;
        mCarrierLabel.setTextColor(iconColor);
        mBatteryView.setFillColor(iconColor);
        mIconManager.setTint(iconColor);
        Rect tintArea = new Rect(0, 0, 0, 0);

        applyDarkness(R.id.battery, tintArea, intensity, iconColor);
        applyDarkness(R.id.clock, tintArea, intensity, iconColor);
        // Reload user avatar
        ((UserInfoControllerImpl) Dependency.get(UserInfoController.class))
                .onDensityOrFontScaleChanged();
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }
}
