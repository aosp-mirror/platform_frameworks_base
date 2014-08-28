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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout implements View.OnClickListener,
        BatteryController.BatteryStateChangeCallback, NextAlarmController.NextAlarmChangeCallback {

    private boolean mExpanded;
    private boolean mListening;

    private ViewGroup mSystemIconsContainer;
    private View mSystemIconsSuperContainer;
    private View mDateGroup;
    private View mClock;
    private TextView mTime;
    private TextView mAmPm;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private TextView mDateCollapsed;
    private TextView mDateExpanded;
    private LinearLayout mSystemIcons;
    private View mStatusIcons;
    private View mSignalCluster;
    private View mSettingsButton;
    private View mQsDetailHeader;
    private TextView mQsDetailHeaderTitle;
    private Switch mQsDetailHeaderSwitch;
    private ImageView mQsDetailHeaderProgress;
    private TextView mEmergencyCallsOnly;
    private TextView mBatteryLevel;
    private TextView mAlarmStatus;

    private boolean mShowEmergencyCallsOnly;
    private boolean mAlarmShowing;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private int mCollapsedHeight;
    private int mExpandedHeight;

    private int mMultiUserExpandedMargin;
    private int mMultiUserCollapsedMargin;

    private int mClockMarginBottomExpanded;
    private int mClockMarginBottomCollapsed;
    private int mMultiUserSwitchWidthCollapsed;
    private int mMultiUserSwitchWidthExpanded;

    private int mClockCollapsedSize;
    private int mClockExpandedSize;

    /**
     * In collapsed QS, the clock and avatar are scaled down a bit post-layout to allow for a nice
     * transition. These values determine that factor.
     */
    private float mClockCollapsedScaleFactor;
    private float mAvatarCollapsedScaleFactor;

    private ActivityStarter mActivityStarter;
    private BatteryController mBatteryController;
    private NextAlarmController mNextAlarmController;
    private QSPanel mQSPanel;


    private final Rect mClipBounds = new Rect();

    private boolean mCaptureValues;
    private boolean mSignalClusterDetached;
    private final LayoutValues mCollapsedValues = new LayoutValues();
    private final LayoutValues mExpandedValues = new LayoutValues();
    private final LayoutValues mCurrentValues = new LayoutValues();

    private float mCurrentT;
    private boolean mShowingDetail;

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
        mTime = (TextView) findViewById(R.id.time_view);
        mAmPm = (TextView) findViewById(R.id.am_pm_view);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) findViewById(R.id.multi_user_avatar);
        mDateCollapsed = (TextView) findViewById(R.id.date_collapsed);
        mDateExpanded = (TextView) findViewById(R.id.date_expanded);
        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeader.setAlpha(0);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mQsDetailHeaderProgress = (ImageView) findViewById(R.id.qs_detail_header_progress);
        mEmergencyCallsOnly = (TextView) findViewById(R.id.header_emergency_calls_only);
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
            if (mExpanded) {
                captureLayoutValues(mExpandedValues);
            } else {
                captureLayoutValues(mCollapsedValues);
            }
            mCaptureValues = false;
            updateLayoutValues(mCurrentT);
        }
        mAlarmStatus.setX(mDateGroup.getLeft() + mDateCollapsed.getRight());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mBatteryLevel, R.dimen.battery_level_text_size);
        FontSizeUtils.updateFontSize(mEmergencyCallsOnly,
                R.dimen.qs_emergency_calls_only_text_size);
        FontSizeUtils.updateFontSize(mDateCollapsed, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mDateExpanded, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mAlarmStatus, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(this, android.R.id.title, R.dimen.qs_detail_header_text_size);
        FontSizeUtils.updateFontSize(this, android.R.id.toggle, R.dimen.qs_detail_header_text_size);
        FontSizeUtils.updateFontSize(mAmPm, R.dimen.qs_time_collapsed_size);
        FontSizeUtils.updateFontSize(this, R.id.empty_time_view, R.dimen.qs_time_expanded_size);

        mClockCollapsedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size);
        mClockExpandedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mClockCollapsedScaleFactor = (float) mClockCollapsedSize / (float) mClockExpandedSize;

        updateClockScale();
        updateClockCollapsedMargin();
    }

    private void updateClockCollapsedMargin() {
        Resources res = getResources();
        int padding = res.getDimensionPixelSize(R.dimen.clock_collapsed_bottom_margin);
        int largePadding = res.getDimensionPixelSize(
                R.dimen.clock_collapsed_bottom_margin_large_text);
        float largeFactor = (MathUtils.constrain(getResources().getConfiguration().fontScale, 1.0f,
                FontSizeUtils.LARGE_TEXT_SCALE) - 1f) / (FontSizeUtils.LARGE_TEXT_SCALE - 1f);
        mClockMarginBottomCollapsed = Math.round((1 - largeFactor) * padding + largeFactor * largePadding);
        requestLayout();
    }

    private void requestCaptureValues() {
        mCaptureValues = true;
        requestLayout();
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
        mMultiUserExpandedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_expanded_margin);
        mMultiUserCollapsedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_collapsed_margin);
        mClockMarginBottomExpanded =
                getResources().getDimensionPixelSize(R.dimen.clock_expanded_bottom_margin);
        updateClockCollapsedMargin();
        mMultiUserSwitchWidthCollapsed =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_collapsed);
        mMultiUserSwitchWidthExpanded =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_expanded);
        mAvatarCollapsedScaleFactor =
                getResources().getDimensionPixelSize(R.dimen.multi_user_avatar_collapsed_size)
                / (float) mMultiUserAvatar.getLayoutParams().width;
        mClockCollapsedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size);
        mClockExpandedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mClockCollapsedScaleFactor = (float) mClockCollapsedSize / (float) mClockExpandedSize;

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
        return mCollapsedHeight;
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

    public void setExpanded(boolean expanded) {
        boolean changed = expanded != mExpanded;
        mExpanded = expanded;
        if (changed) {
            updateEverything();
        }
    }

    public void updateEverything() {
        updateHeights();
        updateVisibilities();
        updateSystemIconsLayoutParams();
        updateClickTargets();
        updateMultiUserSwitch();
        if (mQSPanel != null) {
            mQSPanel.setExpanded(mExpanded);
        }
        updateClockScale();
        updateAvatarScale();
        updateClockLp();
        requestCaptureValues();
    }

    private void updateHeights() {
        int height = mExpanded ? mExpandedHeight : mCollapsedHeight;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            setLayoutParams(lp);
        }
    }

    private void updateVisibilities() {
        mDateCollapsed.setVisibility(mExpanded && mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
        mDateExpanded.setVisibility(mExpanded && mAlarmShowing ? View.INVISIBLE : View.VISIBLE);
        mAlarmStatus.setVisibility(mExpanded && mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
        mSettingsButton.setVisibility(mExpanded ? View.VISIBLE : View.INVISIBLE);
        mQsDetailHeader.setVisibility(mExpanded && mShowingDetail? View.VISIBLE : View.INVISIBLE);
        if (mSignalCluster != null) {
            updateSignalClusterDetachment();
        }
        mEmergencyCallsOnly.setVisibility(mExpanded && mShowEmergencyCallsOnly ? VISIBLE : GONE);
        mBatteryLevel.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
    }

    private void updateSignalClusterDetachment() {
        boolean detached = mExpanded;
        if (detached != mSignalClusterDetached) {
            if (detached) {
                getOverlay().add(mSignalCluster);
            } else {
                reattachSignalCluster();
            }
        }
        mSignalClusterDetached = detached;
    }

    private void reattachSignalCluster() {
        getOverlay().remove(mSignalCluster);
        mSystemIcons.addView(mSignalCluster, 1);
    }

    private void updateSystemIconsLayoutParams() {
        RelativeLayout.LayoutParams lp = (LayoutParams) mSystemIconsSuperContainer.getLayoutParams();
        int rule = mExpanded
                ? mSettingsButton.getId()
                : mMultiUserSwitch.getId();
        if (rule != lp.getRules()[RelativeLayout.START_OF]) {
            lp.addRule(RelativeLayout.START_OF, rule);
            mSystemIconsSuperContainer.setLayoutParams(lp);
        }
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
        if (mExpanded) {
            mMultiUserAvatar.setScaleX(1f);
            mMultiUserAvatar.setScaleY(1f);
        } else {
            mMultiUserAvatar.setScaleX(mAvatarCollapsedScaleFactor);
            mMultiUserAvatar.setScaleY(mAvatarCollapsedScaleFactor);
        }
    }

    private void updateClockScale() {
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, mExpanded
                ? mClockExpandedSize
                : mClockCollapsedSize);
        mTime.setScaleX(1f);
        mTime.setScaleY(1f);
        updateAmPmTranslation();
    }

    private void updateAmPmTranslation() {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        mAmPm.setTranslationX((rtl ? 1 : -1) * mTime.getWidth() * (1 - mTime.getScaleX()));
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mBatteryLevel.setText(getResources().getString(R.string.battery_level_template, level));
    }

    @Override
    public void onPowerSaveChanged() {
        // could not care less
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            mAlarmStatus.setText(KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm));
        }
        mAlarmShowing = nextAlarm != null;
        updateEverything();
        requestCaptureValues();
    }

    private void updateClickTargets() {
        mMultiUserSwitch.setClickable(mExpanded);
        mMultiUserSwitch.setFocusable(mExpanded);
        mSystemIconsSuperContainer.setClickable(mExpanded);
        mSystemIconsSuperContainer.setFocusable(mExpanded);
        mAlarmStatus.setClickable(mNextAlarm != null && mNextAlarm.getShowIntent() != null);
    }

    private void updateClockLp() {
        int marginBottom = mExpanded
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
        if (mExpanded) {
            marginEnd = mMultiUserExpandedMargin;
            width = mMultiUserSwitchWidthExpanded;
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

    public void setExpansion(float t) {
        if (!mExpanded) {
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
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(View.GONE);
        }
    }

    public void onSystemIconsDetached() {
        if (mSignalClusterDetached) {
            reattachSignalCluster();
            mSignalClusterDetached = false;
        }
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(View.VISIBLE);
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
                mActivityStarter.startActivity(showIntent.getIntent(), true /* dismissShade */,
                        false /* afterKeyguardGone */);
            }
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */, false /* afterKeyguardGone */);
    }

    private void startBatteryActivity() {
        mActivityStarter.startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                true /* dismissShade */, false /* afterKeyguardGone */);
    }

    public void setQSPanel(QSPanel qsp) {
        mQSPanel = qsp;
        if (mQSPanel != null) {
            mQSPanel.setCallback(mQsPanelCallback);
        }
        mMultiUserSwitch.setQsPanel(qsp);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    public void setShowEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
                requestCaptureValues();
            }
        }
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        // We don't want that everything lights up when we click on the header, so block the request
        // here.
    }

    private void captureLayoutValues(LayoutValues target) {
        target.timeScale = mExpanded ? 1f : mClockCollapsedScaleFactor;
        target.clockY = mClock.getBottom();
        target.dateY = mDateGroup.getTop();
        target.emergencyCallsOnlyAlpha = getAlphaForVisibility(mEmergencyCallsOnly);
        target.alarmStatusAlpha = getAlphaForVisibility(mAlarmStatus);
        target.dateCollapsedAlpha = getAlphaForVisibility(mDateCollapsed);
        target.dateExpandedAlpha = getAlphaForVisibility(mDateExpanded);
        target.avatarScale = mMultiUserAvatar.getScaleX();
        target.avatarX = mMultiUserSwitch.getLeft() + mMultiUserAvatar.getLeft();
        target.avatarY = mMultiUserSwitch.getTop() + mMultiUserAvatar.getTop();
        if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
            target.batteryX = mSystemIconsSuperContainer.getLeft()
                    + mSystemIconsContainer.getRight();
        } else {
            target.batteryX = mSystemIconsSuperContainer.getLeft()
                    + mSystemIconsContainer.getLeft();
        }
        target.batteryY = mSystemIconsSuperContainer.getTop() + mSystemIconsContainer.getTop();
        target.batteryLevelAlpha = getAlphaForVisibility(mBatteryLevel);
        target.settingsAlpha = getAlphaForVisibility(mSettingsButton);
        target.settingsTranslation = mExpanded
                ? 0
                : mMultiUserSwitch.getLeft() - mSettingsButton.getLeft();
        target.signalClusterAlpha = mSignalClusterDetached ? 0f : 1f;
        target.settingsRotation = !mExpanded ? 90f : 0f;
    }

    private float getAlphaForVisibility(View v) {
        return v == null || v.getVisibility() == View.VISIBLE ? 1f : 0f;
    }

    private void applyAlpha(View v, float alpha) {
        if (v == null || v.getVisibility() == View.GONE) {
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
        mClock.setY(values.clockY - mClock.getHeight());
        mDateGroup.setY(values.dateY);
        mAlarmStatus.setY(values.dateY - mAlarmStatus.getPaddingTop());
        mMultiUserAvatar.setScaleX(values.avatarScale);
        mMultiUserAvatar.setScaleY(values.avatarScale);
        mMultiUserAvatar.setX(values.avatarX - mMultiUserSwitch.getLeft());
        mMultiUserAvatar.setY(values.avatarY - mMultiUserSwitch.getTop());
        if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
            mSystemIconsSuperContainer.setX(values.batteryX - mSystemIconsContainer.getRight());
        } else {
            mSystemIconsSuperContainer.setX(values.batteryX - mSystemIconsContainer.getLeft());
        }
        mSystemIconsSuperContainer.setY(values.batteryY - mSystemIconsContainer.getTop());
        if (mSignalCluster != null && mExpanded) {
            if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
                mSignalCluster.setX(mSystemIconsSuperContainer.getX()
                        - mSignalCluster.getWidth());
            } else {
                mSignalCluster.setX(mSystemIconsSuperContainer.getX()
                        + mSystemIconsSuperContainer.getWidth());
            }
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
        if (!mExpanded) {
            mTime.setScaleX(1f);
            mTime.setScaleY(1f);
        }
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
        private boolean mScanState;

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
            if (mScanState == state) return;
            mScanState = state;
            final Animatable anim = (Animatable) mQsDetailHeaderProgress.getDrawable();
            if (state) {
                mQsDetailHeaderProgress.animate().alpha(1f);
                anim.start();
            } else {
                mQsDetailHeaderProgress.animate().alpha(0f);
                anim.stop();
            }
        }

        private void handleShowingDetail(final QSTile.DetailAdapter detail) {
            final boolean showingDetail = detail != null;
            transition(mClock, !showingDetail);
            transition(mDateGroup, !showingDetail);
            if (mAlarmShowing) {
                transition(mAlarmStatus, !showingDetail);
            }
            transition(mQsDetailHeader, showingDetail);
            mShowingDetail = showingDetail;
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
                v.setVisibility(VISIBLE);
            }
            v.animate()
                    .alpha(in ? 1 : 0)
                    .withLayer()
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!in) {
                                v.setVisibility(INVISIBLE);
                            }
                        }
                    })
                    .start();
        }
    };
}
