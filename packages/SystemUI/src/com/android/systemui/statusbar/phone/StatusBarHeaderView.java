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

import android.app.AlarmClockInfo;
import android.app.PendingIntent;
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

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout implements View.OnClickListener,
        BatteryController.BatteryStateChangeCallback, NextAlarmController.NextAlarmChangeCallback {

    private boolean mExpanded;
    private boolean mListening;
    private boolean mOverscrolled;
    private boolean mKeyguardShowing;
    private boolean mCharging;

    private ViewGroup mSystemIconsContainer;
    private View mSystemIconsSuperContainer;
    private View mDateGroup;
    private View mClock;
    private View mTime;
    private View mAmPm;
    private View mKeyguardCarrierText;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private View mDateCollapsed;
    private View mDateExpanded;
    private LinearLayout mSystemIcons;
    private View mStatusIcons;
    private View mSignalCluster;
    private View mSettingsButton;
    private View mQsDetailHeader;
    private TextView mQsDetailHeaderTitle;
    private Switch mQsDetailHeaderSwitch;
    private View mEmergencyCallsOnly;
    private TextView mBatteryLevel;
    private TextView mAlarmStatus;

    private boolean mShowEmergencyCallsOnly;
    private boolean mKeyguardUserSwitcherShowing;
    private boolean mAlarmShowing;
    private AlarmClockInfo mNextAlarm;

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
    private int mClockMarginBottomCollapsed;
    private int mMultiUserSwitchWidthCollapsed;
    private int mMultiUserSwitchWidthExpanded;
    private int mMultiUserSwitchWidthKeyguard;
    private int mBatteryPaddingEnd;
    private int mBatteryMarginExpanded;
    private int mBatteryMarginKeyguard;

    /**
     * In collapsed QS, the clock and avatar are scaled down a bit post-layout to allow for a nice
     * transition. These values determine that factor.
     */
    private float mClockCollapsedScaleFactor;
    private float mAvatarCollapsedScaleFactor;
    private float mAvatarKeyguardScaleFactor;

    private ActivityStarter mActivityStarter;
    private BatteryController mBatteryController;
    private NextAlarmController mNextAlarmController;
    private QSPanel mQSPanel;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;

    private final Rect mClipBounds = new Rect();

    private boolean mCaptureValues;
    private boolean mSignalClusterDetached;
    private final LayoutValues mCollapsedValues = new LayoutValues();
    private final LayoutValues mExpandedValues = new LayoutValues();
    private final LayoutValues mCurrentValues = new LayoutValues();

    private float mCurrentT;

    public StatusBarHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsSuperContainer = findViewById(R.id.system_icons_super_container);
        mSystemIconsContainer = (ViewGroup) findViewById(R.id.system_icons_container);
        mSystemIconsSuperContainer.setOnClickListener(this);
        mDateGroup = findViewById(R.id.date_group);
        mClock = findViewById(R.id.clock);
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
        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setOnClickListener(this);
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
            public void getOutline(View view, Outline outline) {
                outline.setRect(mClipBounds);
            }
        });
        requestCaptureValues();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mCaptureValues) {
            if (mExpanded && !mOverscrolled) {
                captureLayoutValues(mExpandedValues);
            } else {
                captureLayoutValues(mCollapsedValues);
            }
            mCaptureValues = false;
            updateLayoutValues(mCurrentT);
        }
        mAlarmStatus.setX(mDateGroup.getLeft() + mDateCollapsed.getRight());
    }

    private void requestCaptureValues() {
        mCaptureValues = true;
        requestLayout();
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
        mClockMarginBottomCollapsed =
                getResources().getDimensionPixelSize(R.dimen.clock_collapsed_bottom_margin);
        mMultiUserSwitchWidthCollapsed =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_collapsed);
        mMultiUserSwitchWidthExpanded =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_expanded);
        mMultiUserSwitchWidthKeyguard =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_keyguard);
        mAvatarCollapsedScaleFactor =
                getResources().getDimensionPixelSize(R.dimen.multi_user_avatar_collapsed_size)
                / (float) mMultiUserAvatar.getLayoutParams().width;
        mAvatarKeyguardScaleFactor =
                getResources().getDimensionPixelSize(R.dimen.multi_user_avatar_keyguard_size)
                        / (float) mMultiUserAvatar.getLayoutParams().width;
        mClockCollapsedScaleFactor =
                (float) getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size)
                / (float) getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mBatteryPaddingEnd =
                getResources().getDimensionPixelSize(R.dimen.battery_level_padding_end);
        mBatteryMarginExpanded =
                getResources().getDimensionPixelSize(R.dimen.header_battery_margin_expanded);
        mBatteryMarginKeyguard =
                getResources().getDimensionPixelSize(R.dimen.header_battery_margin_keyguard);
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
    }

    public void setNextAlarmController(NextAlarmController nextAlarmController) {
        mNextAlarmController = nextAlarmController;
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
        updateListeners();
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
            updateBatteryLevelLp();
            requestCaptureValues();
        }
    }

    private void updateHeights() {
        boolean onKeyguardAndCollapsed = mKeyguardShowing && !mExpanded;
        int height;
        if (mExpanded && !mOverscrolled) {
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
        mDateGroup.setVisibility(onKeyguardAndCollapsed ? View.INVISIBLE : View.VISIBLE);
        mClock.setVisibility(onKeyguardAndCollapsed ? View.INVISIBLE : View.VISIBLE);
        mKeyguardCarrierText.setVisibility(onKeyguardAndCollapsed ? View.VISIBLE : View.GONE);
        mDateCollapsed.setVisibility(mExpanded && !mOverscrolled && mAlarmShowing
                ? View.VISIBLE : View.INVISIBLE);
        mDateExpanded.setVisibility(mExpanded && !mOverscrolled && mAlarmShowing
                ? View.INVISIBLE : View.VISIBLE);
        mAlarmStatus.setVisibility(mExpanded && !mOverscrolled && mAlarmShowing
                ? View.VISIBLE : View.INVISIBLE);
        mSettingsButton.setVisibility(mExpanded && !mOverscrolled ? View.VISIBLE : View.INVISIBLE);
        mQsDetailHeader.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(mKeyguardShowing && (!mExpanded || mOverscrolled)
                    ? View.VISIBLE : View.GONE);
        }
        if (mSignalCluster != null) {
            updateSignalClusterDetachment();
        }
        mEmergencyCallsOnly.setVisibility(mExpanded && !mOverscrolled && mShowEmergencyCallsOnly
                ? VISIBLE : GONE);
        mMultiUserSwitch.setVisibility(mExpanded || !mKeyguardUserSwitcherShowing
                ? VISIBLE : GONE);
        mBatteryLevel.setVisibility(mKeyguardShowing && mCharging || mExpanded && !mOverscrolled
                ? View.VISIBLE : View.GONE);
        if (mExpanded && !mOverscrolled && mKeyguardUserSwitcherShowing) {
            mKeyguardUserSwitcher.hide();
        }
    }

    private void updateSignalClusterDetachment() {
        boolean detached = mExpanded && !mOverscrolled;
        if (detached != mSignalClusterDetached) {
            if (detached) {
                getOverlay().add(mSignalCluster);
            } else {
                getOverlay().remove(mSignalCluster);
                mSystemIcons.addView(mSignalCluster, 1);
            }
        }
        mSignalClusterDetached = detached;
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

    private void updateListeners() {
        if (mListening) {
            mBatteryController.addStateChangedCallback(this);
            mNextAlarmController.addStateChangedCallback(this);
        } else {
            mBatteryController.removeStateChangedCallback(this);
            mNextAlarmController.removeStateChangedCallback(this);
        }
    }

    private void updateAvatarScale() {
        if (mExpanded && !mOverscrolled) {
            mMultiUserAvatar.setScaleX(1f);
            mMultiUserAvatar.setScaleY(1f);
        } else if (mKeyguardShowing) {
            mMultiUserAvatar.setScaleX(mAvatarKeyguardScaleFactor);
            mMultiUserAvatar.setScaleY(mAvatarKeyguardScaleFactor);
        } else {
            mMultiUserAvatar.setScaleX(mAvatarCollapsedScaleFactor);
            mMultiUserAvatar.setScaleY(mAvatarCollapsedScaleFactor);
        }
    }

    private void updateClockScale() {
        mAmPm.setScaleX(mClockCollapsedScaleFactor);
        mAmPm.setScaleY(mClockCollapsedScaleFactor);
        mTime.setScaleX(getTimeScale());
        mTime.setScaleY(getTimeScale());
        updateAmPmTranslation();
    }

    private float getTimeScale() {
        return !mExpanded || mOverscrolled ? mClockCollapsedScaleFactor : 1f;
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
            requestCaptureValues();
        }
    }

    @Override
    public void onPowerSaveChanged() {
        // could not care less
    }

    @Override
    public void onNextAlarmChanged(AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            mAlarmStatus.setText(KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm));
        }
        mAlarmShowing = nextAlarm != null;
        updateVisibilities();
        requestCaptureValues();
    }


    private void updateClickTargets() {
        setClickable(!mKeyguardShowing || mExpanded);

        boolean keyguardSwitcherAvailable =
                mKeyguardUserSwitcher != null && mKeyguardShowing && !mExpanded;
        mMultiUserSwitch.setClickable(mExpanded || keyguardSwitcherAvailable);
        mMultiUserSwitch.setKeyguardMode(keyguardSwitcherAvailable);
        mSystemIconsSuperContainer.setClickable(mExpanded);
        mAlarmStatus.setClickable(mNextAlarm != null && mNextAlarm.getShowIntent() != null);
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
        int marginBottom = mExpanded && !mOverscrolled
                ? mClockMarginBottomExpanded
                : mClockMarginBottomCollapsed;
        LayoutParams lp = (LayoutParams) mDateGroup.getLayoutParams();
        if (marginBottom != lp.bottomMargin) {
            lp.bottomMargin = marginBottom;
            mDateGroup.setLayoutParams(lp);
        }
    }

    private void updateMultiUserSwitch() {
        int marginEnd;
        int width;
        if (mExpanded && !mOverscrolled) {
            marginEnd = mMultiUserExpandedMargin;
            width = mMultiUserSwitchWidthExpanded;
        } else if (mKeyguardShowing) {
            marginEnd = mMultiUserKeyguardMargin;
            width = mMultiUserSwitchWidthKeyguard;
        } else {
            marginEnd = mMultiUserCollapsedMargin;
            width = mMultiUserSwitchWidthCollapsed;
        }
        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserSwitch.getLayoutParams();
        if (marginEnd != lp.getMarginEnd() || lp.width != width) {
            lp.setMarginEnd(marginEnd);
            lp.width = width;
            mMultiUserSwitch.setLayoutParams(lp);
        }
    }

    private void updateBatteryLevelLp() {
        int marginStart = mExpanded && !mOverscrolled
                ? mBatteryMarginExpanded
                : mBatteryMarginKeyguard;
        MarginLayoutParams lp = (MarginLayoutParams) mBatteryLevel.getLayoutParams();
        if (marginStart != lp.getMarginStart()) {
            lp.setMarginStart(marginStart);
            mBatteryLevel.setLayoutParams(lp);
        }
    }

    public void setExpansion(float t) {
        if (mOverscrolled) {
            t = 0f;
        }
        mCurrentT = t;
        float height = mCollapsedHeight + t * (mExpandedHeight - mCollapsedHeight);
        if (height < mCollapsedHeight) {
            height = mCollapsedHeight;
        }
        if (height > mExpandedHeight) {
            height = mExpandedHeight;
        }
        setClipping(height);
        updateLayoutValues(t);
    }

    private void updateLayoutValues(float t) {
        if (mCaptureValues) {
            return;
        }
        mCurrentValues.interpoloate(mCollapsedValues, mExpandedValues, t);
        applyLayoutValues(mCurrentValues);
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
        mSystemIcons = systemIcons;
        updateVisibilities();
    }

    public void onSystemIconsDetached() {
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(View.VISIBLE);
            mStatusIcons.setAlpha(1f);
        }
        if (mSignalCluster != null) {
            mSignalCluster.setVisibility(View.VISIBLE);
            mSignalCluster.setAlpha(1f);
            mSignalCluster.setTranslationX(0f);
            mSignalCluster.setTranslationY(0f);
        }
        mStatusIcons = null;
        mSignalCluster = null;
        mSystemIcons = null;
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
        updateAvatarScale();
        mCaptureValues = true;
    }

    public void setUserInfoController(UserInfoController userInfoController) {
        userInfoController.addListener(new UserInfoController.OnUserInfoChangedListener() {
            @Override
            public void onUserInfoChanged(String name, Drawable picture) {
                mMultiUserAvatar.setImageDrawable(picture);
            }
        });
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            startSettingsActivity();
        } else if (v == mSystemIconsSuperContainer) {
            startBatteryActivity();
        } else if (v == mAlarmStatus && mNextAlarm != null) {
            PendingIntent showIntent = mNextAlarm.getShowIntent();
            if (showIntent != null && showIntent.isActivity()) {
                mActivityStarter.startActivity(showIntent.getIntent());
            }
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
        mMultiUserSwitch.setQsPanel(qsp);
    }

    public void setKeyguarUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
        mMultiUserSwitch.setKeyguardUserSwitcher(keyguardUserSwitcher);
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

    private void captureLayoutValues(LayoutValues target) {
        target.timeScale = mTime.getScaleX();
        target.clockY = mClock.getTop();
        target.dateY = mDateGroup.getTop();
        target.emergencyCallsOnlyAlpha = getAlphaForVisibility(mEmergencyCallsOnly);
        target.alarmStatusAlpha = getAlphaForVisibility(mAlarmStatus);
        target.dateCollapsedAlpha = getAlphaForVisibility(mDateCollapsed);
        target.dateExpandedAlpha = getAlphaForVisibility(mDateExpanded);
        target.avatarScale = mMultiUserAvatar.getScaleX();
        target.avatarX = mMultiUserSwitch.getLeft() + mMultiUserAvatar.getLeft();
        target.avatarY = mMultiUserSwitch.getTop() + mMultiUserAvatar.getTop();
        target.batteryX = mSystemIconsSuperContainer.getLeft() + mSystemIconsContainer.getRight();
        target.batteryY = mSystemIconsSuperContainer.getTop() + mSystemIconsContainer.getTop();
        target.batteryLevelAlpha = getAlphaForVisibility(mBatteryLevel);
        target.settingsAlpha = getAlphaForVisibility(mSettingsButton);
        target.settingsTranslation = mExpanded && !mOverscrolled
                ? 0
                : mMultiUserSwitch.getLeft() - mSettingsButton.getLeft();
        target.signalClusterAlpha = mSignalClusterDetached ? 0f : 1f;
        target.settingsRotation = !mExpanded || mOverscrolled ? 90f : 0f;
    }

    private float getAlphaForVisibility(View v) {
        return v == null || v.getVisibility() == View.VISIBLE ? 1f : 0f;
    }

    private void applyAlpha(View v, float alpha) {
        if (v == null) {
            return;
        }
        if (alpha == 0f) {
            v.setVisibility(View.INVISIBLE);
        } else {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(alpha);
        }
    }

    private void applyLayoutValues(LayoutValues values) {
        mTime.setScaleX(values.timeScale);
        mTime.setScaleY(values.timeScale);
        mClock.setY(values.clockY);
        mDateGroup.setY(values.dateY);
        mAlarmStatus.setY(values.dateY - mAlarmStatus.getPaddingTop());
        mMultiUserAvatar.setScaleX(values.avatarScale);
        mMultiUserAvatar.setScaleY(values.avatarScale);
        mMultiUserAvatar.setX(values.avatarX - mMultiUserSwitch.getLeft());
        mMultiUserAvatar.setY(values.avatarY - mMultiUserSwitch.getTop());
        mSystemIconsSuperContainer.setX(values.batteryX - mSystemIconsContainer.getRight());
        mSystemIconsSuperContainer.setY(values.batteryY - mSystemIconsContainer.getTop());
        if (mSignalCluster != null && mExpanded && !mOverscrolled) {
            mSignalCluster.setX(mSystemIconsSuperContainer.getX()
                    - mSignalCluster.getWidth());
            mSignalCluster.setY(
                    mSystemIconsSuperContainer.getY() + mSystemIconsSuperContainer.getHeight()/2
                            - mSignalCluster.getHeight()/2);
        } else if (mSignalCluster != null) {
            mSignalCluster.setTranslationX(0f);
            mSignalCluster.setTranslationY(0f);
        }
        mSettingsButton.setTranslationY(mSystemIconsSuperContainer.getTranslationY());
        mSettingsButton.setTranslationX(values.settingsTranslation);
        mSettingsButton.setRotation(values.settingsRotation);
        applyAlpha(mEmergencyCallsOnly, values.emergencyCallsOnlyAlpha);
        applyAlpha(mAlarmStatus, values.alarmStatusAlpha);
        applyAlpha(mDateCollapsed, values.dateCollapsedAlpha);
        applyAlpha(mDateExpanded, values.dateExpandedAlpha);
        applyAlpha(mBatteryLevel, values.batteryLevelAlpha);
        applyAlpha(mSettingsButton, values.settingsAlpha);
        applyAlpha(mSignalCluster, values.signalClusterAlpha);
        updateAmPmTranslation();
    }

    /**
     * Captures all layout values (position, visibility) for a certain state. This is used for
     * animations.
     */
    private static final class LayoutValues {

        float dateExpandedAlpha;
        float dateCollapsedAlpha;
        float emergencyCallsOnlyAlpha;
        float alarmStatusAlpha;
        float timeScale = 1f;
        float clockY;
        float dateY;
        float avatarScale;
        float avatarX;
        float avatarY;
        float batteryX;
        float batteryY;
        float batteryLevelAlpha;
        float settingsAlpha;
        float settingsTranslation;
        float signalClusterAlpha;
        float settingsRotation;

        public void interpoloate(LayoutValues v1, LayoutValues v2, float t) {
            timeScale = v1.timeScale * (1 - t) + v2.timeScale * t;
            clockY = v1.clockY * (1 - t) + v2.clockY * t;
            dateY = v1.dateY * (1 - t) + v2.dateY * t;
            avatarScale = v1.avatarScale * (1 - t) + v2.avatarScale * t;
            avatarX = v1.avatarX * (1 - t) + v2.avatarX * t;
            avatarY = v1.avatarY * (1 - t) + v2.avatarY * t;
            batteryX = v1.batteryX * (1 - t) + v2.batteryX * t;
            batteryY = v1.batteryY * (1 - t) + v2.batteryY * t;
            settingsTranslation = v1.settingsTranslation * (1 - t) + v2.settingsTranslation * t;

            float t1 = Math.max(0, t - 0.5f) * 2;
            settingsRotation = v1.settingsRotation * (1 - t1) + v2.settingsRotation * t1;
            emergencyCallsOnlyAlpha =
                    v1.emergencyCallsOnlyAlpha * (1 - t1) + v2.emergencyCallsOnlyAlpha * t1;

            float t2 = Math.min(1, 2 * t);
            signalClusterAlpha = v1.signalClusterAlpha * (1 - t2) + v2.signalClusterAlpha * t2;

            float t3 = Math.max(0, t - 0.7f) / 0.3f;
            batteryLevelAlpha = v1.batteryLevelAlpha * (1 - t3) + v2.batteryLevelAlpha * t3;
            settingsAlpha = v1.settingsAlpha * (1 - t3) + v2.settingsAlpha * t3;
            dateExpandedAlpha = v1.dateExpandedAlpha * (1 - t3) + v2.dateExpandedAlpha * t3;
            dateCollapsedAlpha = v1.dateCollapsedAlpha * (1 - t3) + v2.dateCollapsedAlpha * t3;
            alarmStatusAlpha = v1.alarmStatusAlpha * (1 - t3) + v2.alarmStatusAlpha * t3;
        }
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
            transition(mClock, !showingDetail);
            transition(mDateGroup, !showingDetail);
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
                            detail.setToggleState(!mQsDetailHeaderSwitch.isChecked());
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
