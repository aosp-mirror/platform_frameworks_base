/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.AlarmClock;
import android.support.annotation.VisibleForTesting;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;

import java.util.Locale;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private final Handler mHandler = new Handler();

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mDate;
    private View mHeaderTextContainerView;
    /** View containing the next alarm and ringer mode info. */
    private View mStatusContainer;
    /** Tooltip for educating users that they can long press on icons to see more details. */
    private View mLongPressTooltipView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private BatteryMeterView mBatteryMeterView;
    private Clock mClockView;
    private DateView mDateView;

    private NextAlarmController mAlarmController;
    /** Counts how many times the long press tooltip has been shown to the user. */
    private int mShownCount;

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };

    /**
     * Runnable for automatically fading out the long press tooltip (as if it were animating away).
     */
    private final Runnable mAutoFadeOutTooltipRunnable = () -> hideLongPressTooltip(false);

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAlarmController = Dependency.get(NextAlarmController.class);
        mShownCount = getStoredShownCount();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mDate = findViewById(R.id.date);
        mDate.setOnClickListener(this);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        mIconManager = new TintedIconManager(findViewById(R.id.statusIcons));

        // Views corresponding to the header info section (e.g. tooltip and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mLongPressTooltipView = findViewById(R.id.long_press_tooltip);
        mStatusContainer = findViewById(R.id.status_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);

        updateResources();

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = fillColorForIntensity(intensity, getContext());

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);

        mBatteryMeterView = findViewById(R.id.battery);
        mBatteryMeterView.setForceShowPercent(true);
        mClockView = findViewById(R.id.clock);
        mDateView = findViewById(R.id.date);
    }

    private void updateStatusText() {
        boolean ringerVisible = false;
        if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
            mRingerModeIcon.setImageResource(R.drawable.stat_sys_ringer_vibrate);
            mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
            ringerVisible = true;
        } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
            mRingerModeIcon.setImageResource(R.drawable.stat_sys_ringer_silent);
            mRingerModeTextView.setText(R.string.qs_status_phone_muted);
            ringerVisible = true;
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE : View.GONE);
        updateTooltipShow();
    }


    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    private int fillColorForIntensity(float intensity, Context context) {
        if (intensity == 0) {
            return context.getColor(R.color.light_mode_icon_color_dual_tone_fill);
        }
        return context.getColor(R.color.dark_mode_icon_color_dual_tone_fill);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // Update color schemes in landscape to use wallpaperTextColor
        boolean shouldUseWallpaperTextColor =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        mBatteryMeterView.useWallpaperTextColor(shouldUseWallpaperTextColor);
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
        mDateView.useWallpaperTextColor(shouldUseWallpaperTextColor);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        Resources resources = mContext.getResources();

        // Update height for a few views, especially due to landscape mode restricting space.
        mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());

        mSystemIconsView.getLayoutParams().height = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        getLayoutParams().height = resources.getDimensionPixelSize(mQsDisabled
                ? com.android.internal.R.dimen.quick_qs_offset_height
                : com.android.internal.R.dimen.quick_qs_total_height);
        setLayoutParams(getLayoutParams());

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 1)
                .setStartDelay(.5f)
                .build();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param isKeyguardShowing whether or not we're showing the keyguard (a.k.a. lockscreen)
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean isKeyguardShowing, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = isKeyguardShowing ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (isKeyguardShowing) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        // Check the original expansion fraction - we don't want to show the tooltip until the
        // panel is pulled all the way out.
        if (expansionFraction == 1f) {
            // QS is fully expanded, bring in the tooltip.
            showLongPressTooltip();
        }
    }

    /** Returns the latest stored tooltip shown count from SharedPreferences. */
    private int getStoredShownCount() {
        return Prefs.getInt(
                mContext,
                Prefs.Key.QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT,
                TOOLTIP_NOT_YET_SHOWN_COUNT);
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        Dependency.get(StatusBarIconController.class).addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                insets.getDisplayCutout(), getDisplay());
        if (padding == null) {
            setPadding(0, 0, 0, 0);
        } else {
            setPadding(padding.first, 0, padding.second, 0);
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        Dependency.get(StatusBarIconController.class).removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;

        if (listening) {
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
        } else {
            mAlarmController.removeCallback(this);
            mContext.unregisterReceiver(mRingerReceiver);
        }
    }

    @Override
    public void onClick(View v) {
        if(v == mDate){
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS),0);
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    private void updateTooltipShow() {
        if (hasStatusText()) {
            hideLongPressTooltip(true /* shouldShowStatusText */);
        } else {
            hideStatusText();
        }
        updateHeaderTextContainerAlphaAnimator();
    }

    private boolean hasStatusText() {
        return mNextAlarmTextView.getVisibility() == View.VISIBLE
                || mRingerModeTextView.getVisibility() == View.VISIBLE;
    }

    /**
     * Animates in the long press tooltip (as long as the next alarm text isn't currently occupying
     * the space).
     */
    public void showLongPressTooltip() {
        // If we have status text to show, don't bother fading in the tooltip.
        if (hasStatusText()) {
            return;
        }

        if (mShownCount < MAX_TOOLTIP_SHOWN_COUNT) {
            mLongPressTooltipView.animate().cancel();
            mLongPressTooltipView.setVisibility(View.VISIBLE);
            mLongPressTooltipView.animate()
                    .alpha(1f)
                    .setDuration(FADE_ANIMATION_DURATION_MS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mHandler.postDelayed(
                                    mAutoFadeOutTooltipRunnable, AUTO_FADE_OUT_DELAY_MS);
                        }
                    })
                    .start();

            // Increment and drop the shown count in prefs for the next time we're deciding to
            // fade in the tooltip. We first sanity check that the tooltip count hasn't changed yet
            // in prefs (say, from a long press).
            if (getStoredShownCount() <= mShownCount) {
                Prefs.putInt(mContext, Prefs.Key.QS_LONG_PRESS_TOOLTIP_SHOWN_COUNT, ++mShownCount);
            }
        }
    }

    /**
     * Fades out the long press tooltip if it's partially visible - short circuits any running
     * animation. Additionally has the ability to fade in the status info text.
     *
     * @param shouldShowStatusText whether we should fade in the status text
     */
    private void hideLongPressTooltip(boolean shouldShowStatusText) {
        mLongPressTooltipView.animate().cancel();
        if (mLongPressTooltipView.getVisibility() == View.VISIBLE
                && mLongPressTooltipView.getAlpha() != 0f) {
            mHandler.removeCallbacks(mAutoFadeOutTooltipRunnable);
            mLongPressTooltipView.animate()
                    .alpha(0f)
                    .setDuration(FADE_ANIMATION_DURATION_MS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (DEBUG) Log.d(TAG, "hideLongPressTooltip: Hid long press tip");
                            mLongPressTooltipView.setVisibility(View.INVISIBLE);

                            if (shouldShowStatusText) {
                                showStatus();
                            }
                        }
                    })
                    .start();
        } else {
            mLongPressTooltipView.setVisibility(View.INVISIBLE);
            if (shouldShowStatusText) {
                showStatus();
            }
        }
    }

    /**
     * Fades in the updated status text. Note that if there's already a status showing, this will
     * immediately hide it and fade in the updated status.
     */
    private void showStatus() {
        mStatusContainer.setAlpha(0f);
        mStatusContainer.setVisibility(View.VISIBLE);

        // Animate the alarm back in. Make sure to clear the animator listener for the animation!
        mStatusContainer.animate()
                .alpha(1f)
                .setDuration(FADE_ANIMATION_DURATION_MS)
                .setListener(null)
                .start();
    }

    /** Fades out and hides the status text. */
    private void hideStatusText() {
        if (mStatusContainer.getVisibility() == View.VISIBLE) {
            mStatusContainer.animate()
                    .alpha(0f)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (DEBUG) Log.d(TAG, "hideAlarmText: Hid alarm text");

                            // Reset the alpha regardless of how the animation ends for the next
                            // time we show this view/want to animate it.
                            mStatusContainer.setVisibility(View.INVISIBLE);
                            mStatusContainer.setAlpha(1f);
                        }
                    })
                    .start();
        }
    }

    public void updateEverything() {
        post(() -> setClickable(false));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);

        // Use SystemUI context to get battery meter colors, and let it use the default tint (white)
        mBatteryMeterView.setColorsFromContext(mHost.getContext());
        mBatteryMeterView.onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

}
