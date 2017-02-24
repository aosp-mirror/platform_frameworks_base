/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.UserManager;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.telephony.SubscriptionInfo;
import android.util.AttributeSet;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardStatusView;
import com.android.settingslib.Utils;
import com.android.systemui.ActivityStarter;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS.BaseStatusBarHeader;
import com.android.systemui.plugins.qs.QS.Callback;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.qs.TouchAnimator;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.tuner.TunerService;

import java.util.List;

public class QuickStatusBarHeader extends BaseStatusBarHeader implements
        NextAlarmChangeCallback, OnClickListener, OnUserInfoChangedListener, EmergencyListener,
        SignalCallback {
    private static final float EXPAND_INDICATOR_THRESHOLD = .93f;

    private ActivityStarter mActivityStarter;
    private NextAlarmController mNextAlarmController;
    private UserInfoController mUserInfoController;
    private SettingsButton mSettingsButton;
    protected View mSettingsContainer;

    private TextView mAlarmStatus;
    private View mAlarmStatusCollapsed;
    private ViewGroup mDateTimeGroup;
    private ViewGroup mDateTimeAlarmGroup;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mAlarmShowing;

    private TextView mEmergencyOnly;

    protected ExpandableIndicator mExpandIndicator;

    private boolean mListening;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    protected QuickQSPanel mHeaderQsPanel;
    private boolean mShowEmergencyCallsOnly;
    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private boolean mAlwaysShowMultiUserSwitch;

    private TouchAnimator mAnimator;
    protected TouchAnimator mSettingsAlpha;
    private float mExpansionAmount;
    protected QSTileHost mHost;

    protected View mEdit;
    private boolean mShowEditIcon;

    private boolean mShowFullAlarm;
    private float mDateTimeTranslation;
    private SparseBooleanArray mRoamingsBySubId = new SparseBooleanArray();
    private boolean mIsRoaming;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getResources();

        mEmergencyOnly = (TextView) findViewById(R.id.header_emergency_calls_only);

        mShowEditIcon = res.getBoolean(R.bool.config_showQuickSettingsEditingIcon);

        mEdit = findViewById(android.R.id.edit);
        mEdit.setVisibility(mShowEditIcon ? VISIBLE : GONE);

        if (mShowEditIcon) {
            findViewById(android.R.id.edit).setOnClickListener(view ->
                    Dependency.get(ActivityStarter.class).postQSRunnableDismissingKeyguard(() ->
                            mQsPanel.showEdit(view)));
        }

        mDateTimeAlarmGroup = (ViewGroup) findViewById(R.id.date_time_alarm_group);
        mDateTimeAlarmGroup.findViewById(R.id.empty_time_view).setVisibility(View.GONE);
        mDateTimeGroup = (ViewGroup) findViewById(R.id.date_time_group);
        mDateTimeGroup.setPivotX(0);
        mDateTimeGroup.setPivotY(0);
        mDateTimeTranslation = res.getDimension(R.dimen.qs_date_time_translation);
        mShowFullAlarm = res.getBoolean(R.bool.quick_settings_show_full_alarm);

        mExpandIndicator = (ExpandableIndicator) findViewById(R.id.expand_indicator);
        mExpandIndicator.setVisibility(
                res.getBoolean(R.bool.config_showQuickSettingsExpandIndicator)
                ? VISIBLE : GONE);

        mHeaderQsPanel = (QuickQSPanel) findViewById(R.id.quick_qs_panel);
        mHeaderQsPanel.setVisibility(res.getBoolean(R.bool.config_showQuickSettingsRow)
                ? VISIBLE : GONE);

        mSettingsButton = (SettingsButton) findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);

        mAlarmStatusCollapsed = findViewById(R.id.alarm_status_collapsed);
        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setOnClickListener(this);

        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) mMultiUserSwitch.findViewById(R.id.multi_user_avatar);
        mAlwaysShowMultiUserSwitch = res.getBoolean(R.bool.config_alwaysShowMultiUserSwitcher);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);
        ((RippleDrawable) mExpandIndicator.getBackground()).setForceSoftware(true);

        updateResources();

        // Set the light/dark theming on the header status UI to match the current theme.
        SignalClusterView cluster = (SignalClusterView) findViewById(R.id.signal_cluster);
        int colorForeground = Utils.getColorAttr(getContext(), android.R.attr.colorForeground);
        float intensity = colorForeground == Color.WHITE ? 0 : 1;
        cluster.onDarkChanged(new Rect(0, 0, 0, 0), intensity, colorForeground);

        BatteryMeterView battery = (BatteryMeterView) findViewById(R.id.battery);
        battery.setForceShowPercent(true);
        int colorSecondary = Utils.getColorAttr(getContext(), android.R.attr.textColorSecondary);
        battery.setRawColors(colorForeground, colorSecondary);

        mNextAlarmController = Dependency.get(NextAlarmController.class);
        mUserInfoController = Dependency.get(UserInfoController.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        FontSizeUtils.updateFontSize(mAlarmStatus, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mEmergencyOnly, R.dimen.qs_emergency_calls_only_text_size);

        Builder builder = new Builder()
                .addFloat(mShowFullAlarm ? mAlarmStatus : findViewById(R.id.date), "alpha", 0, 1)
                .addFloat(mEmergencyOnly, "alpha", 0, 1);
        if (mShowFullAlarm) {
            builder.addFloat(mAlarmStatusCollapsed, "alpha", 1, 0);
        }
        mAnimator = builder.build();

        updateSettingsAnimator();
    }

    private void updateSettingsAnimator() {
        mSettingsAlpha = createSettingsAlphaAnimator();

        final boolean isRtl = isLayoutRtl();
        if (isRtl && mDateTimeGroup.getWidth() == 0) {
            mDateTimeGroup.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mDateTimeGroup.setPivotX(getWidth());
                    mDateTimeGroup.removeOnLayoutChangeListener(this);
                }
            });
        } else {
            mDateTimeGroup.setPivotX(isRtl ? mDateTimeGroup.getWidth() : 0);
        }
    }

    @Nullable
    private TouchAnimator createSettingsAlphaAnimator() {
        // If the settings icon is not shown and the user switcher is always shown, then there
        // is nothing to animate.
        if (!mShowEditIcon && mAlwaysShowMultiUserSwitch) {
            return null;
        }

        TouchAnimator.Builder animatorBuilder = new TouchAnimator.Builder();

        if (mShowEditIcon) {
            animatorBuilder.addFloat(mEdit, "alpha", 0, 1);
        }

        if (!mAlwaysShowMultiUserSwitch) {
            animatorBuilder.addFloat(mMultiUserSwitch, "alpha", 0, 1);
        }

        return animatorBuilder.build();
    }

    @Override
    public int getCollapsedHeight() {
        return getHeight();
    }

    @Override
    public int getExpandedHeight() {
        return getHeight();
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            String alarmString = KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm);
            mAlarmStatus.setText(alarmString);
            mAlarmStatus.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
            mAlarmStatusCollapsed.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
        }
        if (mAlarmShowing != (nextAlarm != null)) {
            mAlarmShowing = nextAlarm != null;
            updateEverything();
        }
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        updateDateTimePosition();
        mAnimator.setPosition(headerExpansionFraction);

        if (mSettingsAlpha != null) {
            mSettingsAlpha.setPosition(headerExpansionFraction);
        }

        updateAlarmVisibilities();

        mExpandIndicator.setExpanded(headerExpansionFraction > EXPAND_INDICATOR_THRESHOLD);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    private void updateAlarmVisibilities() {
        mAlarmStatus.setVisibility(mAlarmShowing && mShowFullAlarm ? View.VISIBLE : View.INVISIBLE);
        mAlarmStatusCollapsed.setVisibility(mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        updateListeners();
    }

    @Override
    public View getExpandView() {
        return findViewById(R.id.expand_indicator);
    }

    @Override
    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            setClickable(false);
        });
    }

    private void updateVisibilities() {
        updateAlarmVisibilities();
        updateDateTimePosition();
        mEmergencyOnly.setVisibility(mExpanded && (mShowEmergencyCallsOnly || mIsRoaming)
                ? View.VISIBLE : View.INVISIBLE);
        mSettingsContainer.findViewById(R.id.tuner_icon).setVisibility(
                TunerService.isTunerEnabled(mContext) ? View.VISIBLE : View.INVISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);

        mMultiUserSwitch.setVisibility((mExpanded || mAlwaysShowMultiUserSwitch)
                && mMultiUserSwitch.hasMultipleUsers() && !isDemo
                ? View.VISIBLE : View.INVISIBLE);

        if (mShowEditIcon) {
            mEdit.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void updateDateTimePosition() {
        mDateTimeAlarmGroup.setTranslationY(mShowEmergencyCallsOnly || mIsRoaming
                ? mExpansionAmount * mDateTimeTranslation : 0);
    }

    private void updateListeners() {
        if (mListening) {
            mNextAlarmController.addCallback(this);
            mUserInfoController.addCallback(this);
            if (Dependency.get(NetworkController.class).hasVoiceCallingFeature()) {
                Dependency.get(NetworkController.class).addEmergencyListener(this);
                Dependency.get(NetworkController.class).addCallback(this);
            }
        } else {
            mNextAlarmController.removeCallback(this);
            mUserInfoController.removeCallback(this);
            Dependency.get(NetworkController.class).removeEmergencyListener(this);
            Dependency.get(NetworkController.class).removeCallback(this);
        }
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
        }
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            if (mSettingsButton.isTunerClick()) {
                Dependency.get(ActivityStarter.class).postQSRunnableDismissingKeyguard(() -> {
                    if (TunerService.isTunerEnabled(mContext)) {
                        TunerService.showResetRequest(mContext, () -> {
                            // Relaunch settings so that the tuner disappears.
                            startSettingsActivity();
                        });
                    } else {
                        Toast.makeText(getContext(), R.string.tuner_toast,
                                Toast.LENGTH_LONG).show();
                        TunerService.setTunerEnabled(mContext, true);
                    }
                    startSettingsActivity();

                });
            } else {
                startSettingsActivity();
            }
        } else if (v == mAlarmStatus && mNextAlarm != null) {
            PendingIntent showIntent = mNextAlarm.getShowIntent();
            mActivityStarter.startPendingIntentDismissingKeyguard(showIntent);
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        mRoamingsBySubId.clear();
        updateRoaming();
    }

    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
            String description, boolean isWide, int subId, boolean roaming) {
        mRoamingsBySubId.put(subId, roaming);
        updateRoaming();
    }

    private void updateRoaming() {
        boolean isRoaming = calculateRoaming();
        if (mIsRoaming != isRoaming) {
            mIsRoaming = isRoaming;
            mEmergencyOnly.setText(mIsRoaming ? R.string.accessibility_data_connection_roaming
                    : com.android.internal.R.string.emergency_calls_only);
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    private boolean calculateRoaming() {
        for (int i = 0; i < mRoamingsBySubId.size(); i++) {
            if (mRoamingsBySubId.valueAt(i)) return true;
        }
        return false;
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mMultiUserAvatar.setImageDrawable(picture);
    }
}
