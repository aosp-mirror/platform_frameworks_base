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
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.OngoingPrivacyDialog;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private final Handler mHandler = new Handler();
    private final BatteryController mBatteryController;
    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

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
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;

    private PrivacyItemController mPrivacyItemController;
    /** Counts how many times the long press tooltip has been shown to the user. */
    private int mShownCount;

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };
    private boolean mHasTopCutout = false;

    private final PercentSettingObserver mPercentSettingObserver =
            new PercentSettingObserver(new Handler(mContext.getMainLooper()));

    /**
     * Runnable for automatically fading out the long press tooltip (as if it were animating away).
     */
    private final Runnable mAutoFadeOutTooltipRunnable = () -> hideLongPressTooltip(false);

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void privacyChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }
    };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            BatteryController batteryController, StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mBatteryController = batteryController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mShownCount = getStoredShownCount();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

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

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mDateView = findViewById(R.id.date);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this);
        mSpace = findViewById(R.id.space);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        // Don't need to worry about tuner settings for this icon
        mBatteryRemainingIcon.setIgnoreTunerUpdates(true);
        updateShowPercent();
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
            updateTooltipShow();
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible) {
            mPrivacyChip.setVisibility(View.VISIBLE);
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.stat_sys_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.stat_sys_ringer_silent);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    private int fillColorForIntensity(float intensity, Context context) {
        if (intensity == 0) {
            return context.getColor(R.color.light_mode_icon_color_single_tone);
        }
        return context.getColor(R.color.dark_mode_icon_color_single_tone);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // Update color schemes in landscape to use wallpaperTextColor
        boolean shouldUseWallpaperTextColor =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
    }



    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);

        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        // Update height for a few views, especially due to landscape mode restricting space.
        mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());

        mSystemIconsView.getLayoutParams().height = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_offset_height);
        } else {
            lp.height = Math.max(getMinimumHeight(),
                    resources.getDimensionPixelSize(
                            com.android.internal.R.dimen.quick_qs_total_height));
        }

        setLayoutParams(lp);

        if (mPrivacyChip != null) {
            MarginLayoutParams lm = (MarginLayoutParams) mPrivacyChip.getLayoutParams();
            int sideMargins = lm.leftMargin;
            int topBottomMargins = resources.getDimensionPixelSize(
                    R.dimen.ongoing_appops_top_chip_margin);
            lm.setMargins(sideMargins, topBottomMargins, sideMargins, topBottomMargins);
            mPrivacyChip.setLayoutParams(lm);
        }

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
        super.onAttachedToWindow();
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT), false, mPercentSettingObserver,
                ActivityManager.getCurrentUser());
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                cutout, getDisplay());
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start), 0,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end), 0);
        } else {
            mSystemIconsView.setPadding(padding.first, 0, padding.second, 0);

        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty()) {
                mHasTopCutout = false;
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                mHasTopCutout = true;
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        mContext.getContentResolver().unregisterContentObserver(mPercentSettingObserver);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mPrivacyItemController.removeCallback(mPICCallback);
            mContext.unregisterReceiver(mRingerReceiver);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS),0);
        } else if (v == mPrivacyChip) {
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            mUiHandler.post(() -> {
                Dialog mDialog = new OngoingPrivacyDialog(mContext,
                        mPrivacyChip.getBuilder()).createDialog();
                mDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                SystemUIDialog.setShowForAllUsers(mDialog, true);
                SystemUIDialog.registerDismissListener(mDialog);
                SystemUIDialog.setWindowOnTop(mDialog);
                mUiHandler.post(() -> mDialog.show());
                mHost.collapsePanels();
            });
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();

    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
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
     * immediately fade it out and fade in the updated status.
     */
    private void showStatus() {
        mStatusContainer.setAlpha(0f);

        mStatusContainer.animate()
                .alpha(1f)
                .setDuration(FADE_ANIMATION_DURATION_MS)
                .start();
    }

    /** Fades out the status text. */
    private void hideStatusText() {
        mStatusContainer.animate()
                .alpha(0f)
                .setDuration(FADE_ANIMATION_DURATION_MS)
                .start();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
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


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = fillColorForIntensity(intensity, getContext());
        mBatteryRemainingIcon.setColorsFromContext(mHost.getContext());
        mBatteryRemainingIcon.onDarkChanged(tintArea, intensity, fillColor);
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

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == mSystemIconsView || v == mQuickQsStatusIcons || v == mHeaderQsPanel
                    || v == mPrivacyChip) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    private void updateShowPercent() {
        final boolean systemSetting = 0 != Settings.System
                .getIntForUser(getContext().getContentResolver(),
                        SHOW_BATTERY_PERCENT, 0, ActivityManager.getCurrentUser());

        mBatteryRemainingIcon.setPercentShowMode(systemSetting
                ? BatteryMeterView.MODE_ESTIMATE : BatteryMeterView.MODE_ON);
    }

    private final class PercentSettingObserver extends ContentObserver {
        PercentSettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent();
        }
    }
}
