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

import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout implements View.OnClickListener,
        BatteryController.BatteryStateChangeCallback {

    private boolean mExpanded;
    private boolean mListening;
    private boolean mOverscrolled;
    private boolean mKeyguardShowing;
    private boolean mCharging;

    private ViewGroup mSystemIconsContainer;
    private View mSystemIconsSuperContainer;
    private View mDateTime;
    private View mTime;
    private View mAmPm;
    private View mKeyguardCarrierText;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private View mDateCollapsed;
    private View mDateExpanded;
    private View mStatusIcons;
    private View mSignalCluster;
    private View mSettingsButton;
    private View mQsDetailHeader;
    private TextView mQsDetailHeaderTitle;
    private Switch mQsDetailHeaderSwitch;
    private View mEmergencyCallsOnly;
    private TextView mBatteryLevel;

    private boolean mShowEmergencyCallsOnly;
    private boolean mKeyguardUserSwitcherShowing;

    private int mCollapsedHeight;
    private int mExpandedHeight;
    private int mKeyguardHeight;

    private int mKeyguardWidth = ViewGroup.LayoutParams.MATCH_PARENT;
    private int mNormalWidth;
    private int mPadding;
    private int mMultiUserExpandedMargin;
    private int mMultiUserCollapsedMargin;
    private int mMultiUserKeyguardMargin;
    private int mSystemIconsSwitcherHiddenExpandedMargin;
    private int mClockMarginBottomExpanded;
    private int mMultiUserSwitchWidthCollapsed;
    private int mMultiUserSwitchWidthExpanded;
    private int mBatteryPaddingEnd;

    /**
     * In collapsed QS, the clock and avatar are scaled down a bit post-layout to allow for a nice
     * transition. These values determine that factor.
     */
    private float mClockCollapsedScaleFactor;
    private float mAvatarCollapsedScaleFactor;

    private ActivityStarter mActivityStarter;
    private BatteryController mBatteryController;
    private QSPanel mQSPanel;

    private final Rect mClipBounds = new Rect();

    public StatusBarHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsSuperContainer = findViewById(R.id.system_icons_super_container);
        mSystemIconsContainer = (ViewGroup) findViewById(R.id.system_icons_container);
        mSystemIconsSuperContainer.setOnClickListener(this);
        mDateTime = findViewById(R.id.datetime);
        mTime = findViewById(R.id.time_view);
        mAmPm = findViewById(R.id.am_pm_view);
        mKeyguardCarrierText = findViewById(R.id.keyguard_carrier_text);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) findViewById(R.id.multi_user_avatar);
        mDateCollapsed = findViewById(R.id.date_collapsed);
        mDateExpanded = findViewById(R.id.date_expanded);
        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeader.setAlpha(0);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mEmergencyCallsOnly = findViewById(R.id.header_emergency_calls_only);
        mBatteryLevel = (TextView) findViewById(R.id.battery_level);
        loadDimens();
        updateVisibilities();
        updateClockScale();
        updateAvatarScale();
        addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft)) {
                    // width changed, update clipping
                    setClipping(getHeight());
                }
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                mTime.setPivotX(rtl ? mTime.getWidth() : 0);
                mTime.setPivotY(mTime.getBaseline());
                mAmPm.setPivotX(rtl ? mAmPm.getWidth() : 0);
                mAmPm.setPivotY(mAmPm.getBaseline());
                updateAmPmTranslation();
            }
        });
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public boolean getOutline(View view, Outline outline) {
                outline.setRect(mClipBounds);
                return true;
            }
        });
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
        mKeyguardHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_keyguard);
        mNormalWidth = getLayoutParams().width;
        mPadding = getResources().getDimensionPixelSize(R.dimen.notification_side_padding);
        mMultiUserExpandedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_expanded_margin);
        mMultiUserCollapsedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_collapsed_margin);
        mMultiUserKeyguardMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_keyguard_margin);
        mSystemIconsSwitcherHiddenExpandedMargin = getResources().getDimensionPixelSize(
                R.dimen.system_icons_switcher_hidden_expanded_margin);
        mClockMarginBottomExpanded =
                getResources().getDimensionPixelSize(R.dimen.clock_expanded_bottom_margin);
        mMultiUserSwitchWidthCollapsed =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_collapsed);
        mMultiUserSwitchWidthExpanded =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_expanded);
        mAvatarCollapsedScaleFactor =
                getResources().getDimensionPixelSize(R.dimen.multi_user_avatar_collapsed_size)
                / (float) mMultiUserAvatar.getLayoutParams().width;
        mClockCollapsedScaleFactor =
                (float) getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size)
                / (float) getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mBatteryPaddingEnd =
                getResources().getDimensionPixelSize(R.dimen.battery_level_padding_end);
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
    }

    public int getCollapsedHeight() {
        return mKeyguardShowing ? mKeyguardHeight : mCollapsedHeight;
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateBatteryListening();
    }

    public void setExpanded(boolean expanded, boolean overscrolled) {
        boolean changed = expanded != mExpanded;
        boolean overscrollChanged = overscrolled != mOverscrolled;
        mExpanded = expanded;
        mOverscrolled = overscrolled;
        if (changed || overscrollChanged) {
            updateHeights();
            updateVisibilities();
            updateSystemIconsLayoutParams();
            updateZTranslation();
            updateClickTargets();
            updateWidth();
            updatePadding();
            updateMultiUserSwitch();
            if (mQSPanel != null) {
                mQSPanel.setExpanded(expanded && !overscrolled);
            }
            updateClockScale();
            updateAvatarScale();
            updateClockLp();
            updateBatteryLevelPaddingEnd();
        }
    }

    private void updateHeights() {
        boolean onKeyguardAndCollapsed = mKeyguardShowing && !mExpanded;
        int height;
        if (mExpanded) {
            height = mExpandedHeight;
        } else if (onKeyguardAndCollapsed) {
            height = mKeyguardHeight;
        } else {
            height = mCollapsedHeight;
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            setLayoutParams(lp);
        }
        int systemIconsContainerHeight = onKeyguardAndCollapsed ? mKeyguardHeight : mCollapsedHeight;
        lp = mSystemIconsSuperContainer.getLayoutParams();
        if (lp.height != systemIconsContainerHeight) {
            lp.height = systemIconsContainerHeight;
            mSystemIconsSuperContainer.setLayoutParams(lp);
        }
        lp = mMultiUserSwitch.getLayoutParams();
        if (lp.height != systemIconsContainerHeight) {
            lp.height = systemIconsContainerHeight;
            mMultiUserSwitch.setLayoutParams(lp);
        }
    }

    private void updateWidth() {
        int width = (mKeyguardShowing && !mExpanded) ? mKeyguardWidth : mNormalWidth;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (width != lp.width) {
            lp.width = width;
            setLayoutParams(lp);
        }
    }

    private void updateVisibilities() {
        boolean onKeyguardAndCollapsed = mKeyguardShowing && !mExpanded;
        if (onKeyguardAndCollapsed) {
            setBackground(null);
        } else {
            setBackgroundResource(R.drawable.notification_header_bg);
        }
        mDateTime.setVisibility(onKeyguardAndCollapsed ? View.INVISIBLE : View.VISIBLE);
        mKeyguardCarrierText.setVisibility(onKeyguardAndCollapsed ? View.VISIBLE : View.GONE);
        mDateCollapsed.setVisibility(mExpanded && !mOverscrolled ? View.GONE : View.VISIBLE);
        mDateExpanded.setVisibility(mExpanded && !mOverscrolled ? View.VISIBLE : View.GONE);
        mSettingsButton.setVisibility(mExpanded && !mOverscrolled ? View.VISIBLE : View.GONE);
        mQsDetailHeader.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(!mExpanded || mOverscrolled ? View.VISIBLE : View.GONE);
        }
        if (mSignalCluster != null) {
            mSignalCluster.setVisibility(!mExpanded || mOverscrolled ? View.VISIBLE : View.GONE);
        }
        mEmergencyCallsOnly.setVisibility(mExpanded && !mOverscrolled && mShowEmergencyCallsOnly
                ? VISIBLE : GONE);
        mMultiUserSwitch.setVisibility(mExpanded || !mKeyguardUserSwitcherShowing
                ? VISIBLE : GONE);
        mBatteryLevel.setVisibility(mKeyguardShowing && mCharging || mExpanded && !mOverscrolled
                ? View.VISIBLE : View.GONE);
    }

    private void updateSystemIconsLayoutParams() {
        RelativeLayout.LayoutParams lp = (LayoutParams) mSystemIconsSuperContainer.getLayoutParams();
        lp.addRule(RelativeLayout.START_OF, mExpanded && !mOverscrolled
                ? mSettingsButton.getId()
                : mMultiUserSwitch.getId());
        lp.removeRule(ALIGN_PARENT_START);
        if (mMultiUserSwitch.getVisibility() == GONE) {
            lp.setMarginEnd(mSystemIconsSwitcherHiddenExpandedMargin);
        } else {
            lp.setMarginEnd(0);
        }
        mSystemIconsSuperContainer.setLayoutParams(lp);
    }

    private void updateBatteryListening() {
        if (mListening) {
            mBatteryController.addStateChangedCallback(this);
        } else {
            mBatteryController.removeStateChangedCallback(this);
        }
    }

    private void updateAvatarScale() {
        if (!mExpanded || mOverscrolled) {
            mMultiUserSwitch.setScaleX(mAvatarCollapsedScaleFactor);
            mMultiUserSwitch.setScaleY(mAvatarCollapsedScaleFactor);
        } else {
            mMultiUserSwitch.setScaleX(1f);
            mMultiUserSwitch.setScaleY(1f);
        }
    }

    private void updateClockScale() {
        mAmPm.setScaleX(mClockCollapsedScaleFactor);
        mAmPm.setScaleY(mClockCollapsedScaleFactor);
        if (!mExpanded || mOverscrolled) {
            mTime.setScaleX(mClockCollapsedScaleFactor);
            mTime.setScaleY(mClockCollapsedScaleFactor);
        } else {
            mTime.setScaleX(1f);
            mTime.setScaleY(1f);
        }
        updateAmPmTranslation();
    }

    private void updateAmPmTranslation() {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        mAmPm.setTranslationX((rtl ? 1 : -1) * mTime.getWidth() * (1 - mTime.getScaleX()));
    }

    private void updateBatteryLevelPaddingEnd() {
        mBatteryLevel.setPaddingRelative(0, 0,
                mKeyguardShowing && !mExpanded ? 0 : mBatteryPaddingEnd, 0);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mBatteryLevel.setText(getResources().getString(R.string.battery_level_template, level));
        boolean changed = mCharging != charging;
        mCharging = charging;
        if (changed) {
            updateVisibilities();
        }
    }

    private void updateClickTargets() {
        setClickable(!mKeyguardShowing || mExpanded);
        mDateTime.setClickable(mExpanded);
        mMultiUserSwitch.setClickable(mExpanded);
        mSystemIconsSuperContainer.setClickable(mExpanded);
    }

    private void updateZTranslation() {

        // If we are on the Keyguard, we need to set our z position to zero, so we don't get
        // shadows.
        if (mKeyguardShowing && !mExpanded) {
            setZ(0);
        } else {
            setTranslationZ(0);
        }
    }

    private void updatePadding() {
        boolean padded = !mKeyguardShowing || mExpanded;
        int padding = padded ? mPadding : 0;
        setPaddingRelative(padding, 0, padding, 0);
    }

    private void updateClockLp() {
        int marginBottom = mExpanded && !mOverscrolled ? mClockMarginBottomExpanded : 0;
        LayoutParams lp = (LayoutParams) mDateTime.getLayoutParams();
        int rule = mExpanded && !mOverscrolled ? TRUE : 0;
        if (marginBottom != lp.bottomMargin
                || lp.getRules()[RelativeLayout.ALIGN_PARENT_BOTTOM] != rule) {
            lp.bottomMargin = marginBottom;
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, rule);
            mDateTime.setLayoutParams(lp);
        }
    }

    private void updateMultiUserSwitch() {
        int marginEnd;
        if (mExpanded && !mOverscrolled) {
            marginEnd = mMultiUserExpandedMargin;
        } else if (mKeyguardShowing) {
            marginEnd = mMultiUserKeyguardMargin;
        } else {
            marginEnd = mMultiUserCollapsedMargin;
        }
        int width = mExpanded && !mOverscrolled
                ? mMultiUserSwitchWidthExpanded
                : mMultiUserSwitchWidthCollapsed;
        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserSwitch.getLayoutParams();
        if (marginEnd != lp.getMarginEnd() || lp.width != width) {
            lp.setMarginEnd(marginEnd);
            lp.width = width;
            mMultiUserSwitch.setLayoutParams(lp);
        }
    }

    public void setExpansion(float t) {
        float height = mCollapsedHeight + t * (mExpandedHeight - mCollapsedHeight);
        if (height < mCollapsedHeight) {
            height = mCollapsedHeight;
        }
        if (height > mExpandedHeight) {
            height = mExpandedHeight;
        }
        setClipping(height);
    }

    private void setClipping(float height) {
        mClipBounds.set(getPaddingLeft(), 0, getWidth() - getPaddingRight(), (int) height);
        setClipBounds(mClipBounds);
        invalidateOutline();
    }

    public void attachSystemIcons(LinearLayout systemIcons) {
        mSystemIconsContainer.addView(systemIcons);
        mStatusIcons = systemIcons.findViewById(R.id.statusIcons);
        mSignalCluster = systemIcons.findViewById(R.id.signal_cluster);
    }

    public void onSystemIconsDetached() {
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(View.VISIBLE);
        }
        if (mSignalCluster != null) {
            mSignalCluster.setVisibility(View.VISIBLE);
        }
        mStatusIcons = null;
        mSignalCluster = null;
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        mKeyguardShowing = keyguardShowing;
        updateHeights();
        updateWidth();
        updateVisibilities();
        updateZTranslation();
        updatePadding();
        updateMultiUserSwitch();
        updateClickTargets();
        updateBatteryLevelPaddingEnd();
    }

    public void setUserInfoController(UserInfoController userInfoController) {
        userInfoController.addListener(new UserInfoController.OnUserInfoChangedListener() {
            @Override
            public void onUserInfoChanged(String name, Drawable picture) {
                mMultiUserAvatar.setImageDrawable(picture);
            }
        });
    }

    public void setOverlayParent(ViewGroup parent) {
        mMultiUserSwitch.setOverlayParent(parent);
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            startSettingsActivity();
        } else if (v == mSystemIconsSuperContainer) {
            startBatteryActivity();
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    private void startBatteryActivity() {
        mActivityStarter.startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
    }

    public void setQSPanel(QSPanel qsp) {
        mQSPanel = qsp;
        if (mQSPanel != null) {
            mQSPanel.setCallback(mQsPanelCallback);
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    public void setShowEmergencyCallsOnly(boolean show) {
        mShowEmergencyCallsOnly = show;
        if (mExpanded) {
            updateVisibilities();
        }
    }

    public void setKeyguardUserSwitcherShowing(boolean showing) {
        // STOPSHIP: NOT CALLED PROPERLY WHEN GOING TO FULL SHADE AND RETURNING!?!
        mKeyguardUserSwitcherShowing = showing;
        updateVisibilities();
        updateSystemIconsLayoutParams();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return !mKeyguardShowing || mExpanded;
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        // We don't want that everything lights up when we click on the header, so block the request
        // here.
    }

    private final QSPanel.Callback mQsPanelCallback = new QSPanel.Callback() {
        @Override
        public void onToggleStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleToggleStateChanged(state);
                }
            });
        }

        @Override
        public void onShowingDetail(final QSTile.DetailAdapter detail) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleShowingDetail(detail);
                }
            });
        }

        @Override
        public void onScanStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleScanStateChanged(state);
                }
            });
        }

        private void handleToggleStateChanged(boolean state) {
            mQsDetailHeaderSwitch.setChecked(state);
        }

        private void handleScanStateChanged(boolean state) {
            // TODO - waiting on framework asset
        }

        private void handleShowingDetail(final QSTile.DetailAdapter detail) {
            final boolean showingDetail = detail != null;
            transition(mDateTime, !showingDetail);
            transition(mQsDetailHeader, showingDetail);
            if (showingDetail) {
                mQsDetailHeaderTitle.setText(detail.getTitle());
                final Boolean toggleState = detail.getToggleState();
                if (toggleState == null) {
                    mQsDetailHeaderSwitch.setVisibility(INVISIBLE);
                    mQsDetailHeader.setClickable(false);
                } else {
                    mQsDetailHeaderSwitch.setVisibility(VISIBLE);
                    mQsDetailHeaderSwitch.setChecked(toggleState);
                    mQsDetailHeader.setClickable(true);
                    mQsDetailHeader.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            detail.setToggleState(!toggleState);
                        }
                    });
                }
            } else {
                mQsDetailHeader.setClickable(false);
            }
        }

        private void transition(final View v, final boolean in) {
            if (in) {
                v.bringToFront();
            }
            v.animate().alpha(in ? 1 : 0).withLayer().start();
        }
    };
}
