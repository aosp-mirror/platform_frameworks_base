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
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.DualToneHandler;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyChipBuilder;
import com.android.systemui.privacy.PrivacyChipEvent;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.qs.carrier.QSCarrierGroup;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.RingerModeTracker;

import java.util.ArrayList;
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
        ZenModeController.Callback, LifecycleOwner {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private TouchAnimator mPrivacyChipAlphaAnimator;
    private DualToneHandler mDualToneHandler;
    private final CommandQueue mCommandQueue;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private RingerModeTracker mRingerModeTracker;
    private boolean mAllIndicatorsEnabled;
    private boolean mMicCameraIndicatorsEnabled;

    private PrivacyItemController mPrivacyItemController;
    private final UiEventLogger mUiEventLogger;
    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    private boolean mHasTopCutout = false;
    private int mStatusBarPaddingTop = 0;
    private int mRoundedCornerPadding = 0;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mExpandedHeaderAlpha = 1.0f;
    private float mKeyguardExpansionFraction;
    private boolean mPrivacyChipLogged = false;

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }

        @Override
        public void onFlagAllChanged(boolean flag) {
            if (mAllIndicatorsEnabled != flag) {
                mAllIndicatorsEnabled = flag;
                update();
            }
        }

        @Override
        public void onFlagMicCameraChanged(boolean flag) {
            if (mMicCameraIndicatorsEnabled != flag) {
                mMicCameraIndicatorsEnabled = flag;
                update();
            }
        }

        private void update() {
            StatusIconContainer iconContainer = requireViewById(R.id.statusIcons);
            iconContainer.setIgnoredSlots(getIgnoredIconSlots());
            setChipVisibility(!mPrivacyChip.getPrivacyList().isEmpty());
        }
    };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController,
            CommandQueue commandQueue, RingerModeTracker ringerModeTracker,
            UiEventLogger uiEventLogger) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
        mCommandQueue = commandQueue;
        mRingerModeTracker = ringerModeTracker;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        // Ignore privacy icons because they show in the space above QQS
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer, mCommandQueue);

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mRingerContainer.setOnClickListener(this::onClick);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this::onClick);
        mCarrierGroup = findViewById(R.id.carrier_group);


        updateResources();

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mDateView = findViewById(R.id.date);
        mSpace = findViewById(R.id.space);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        // Don't need to worry about tuner settings for this icon
        mBatteryRemainingIcon.setIgnoreTunerUpdates(true);
        // QS will always show the estimate, and BatteryMeterView handles the case where
        // it's unavailable or charging
        mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);

        mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
        mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        if (getChipEnabled()) {
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_camera));
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_microphone));
            if (mAllIndicatorsEnabled) {
                ignored.add(mContext.getResources().getString(
                        com.android.internal.R.string.status_bar_location));
            }
        }

        return ignored;
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && getChipEnabled()) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_VIEW);
            }
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
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

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
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
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

        mRoundedCornerPadding = resources.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        mStatusBarPaddingTop = resources.getDimensionPixelSize(R.dimen.status_bar_padding_top);

        // Update height for a few views, especially due to landscape mode restricting space.
        mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());

        mSystemIconsView.getLayoutParams().height = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_offset_height);
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
        updatePrivacyChipAlphaAnimator();
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, mExpandedHeaderAlpha)
                .build();
    }

    private void updatePrivacyChipAlphaAnimator() {
        mPrivacyChipAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mPrivacyChip, "alpha", 1, 0, 1)
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
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }
        if (mPrivacyChipAlphaAnimator != null) {
            mPrivacyChip.setExpanded(expansionFraction > 0.5);
            mPrivacyChipAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (expansionFraction < 1 && expansionFraction > 0.99) {
            if (mHeaderQsPanel.switchTileLayout()) {
                updateResources();
            }
        }
        mKeyguardExpansionFraction = keyguardExpansionFraction;
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
        mRingerModeTracker.getRingerModeInternal().observe(this, ringer -> {
            mRingerMode = ringer;
            updateStatusText();
        });
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Handle padding of the clock
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> cornerCutoutPadding = StatusBarWindowView.cornerCutoutMargins(
                cutout, getDisplay());
        Pair<Integer, Integer> padding =
                StatusBarWindowView.paddingNeededForCutoutAndRoundedCorner(
                        cutout, cornerCutoutPadding, -1);
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start), 0,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end), 0);
        } else {
            mSystemIconsView.setPadding(padding.first, 0, padding.second, 0);

        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        boolean cornerCutout = cornerCutoutPadding != null
                && (cornerCutoutPadding.first == 0 || cornerCutoutPadding.second == 0);
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty() || cornerCutout) {
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
        mCutOutPaddingLeft = padding.first;
        mCutOutPaddingRight = padding.second;
        mWaterfallTopInset = cutout == null ? 0 : cutout.getWaterfallInsets().top;
        updateClockPadding();
        return super.onApplyWindowInsets(insets);
    }

    private void updateClockPadding() {
        int clockPaddingLeft = 0;
        int clockPaddingRight = 0;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int leftMargin = lp.leftMargin;
        int rightMargin = lp.rightMargin;

        // The clock might collide with cutouts, let's shift it out of the way.
        // We only do that if the inset is bigger than our own padding, since it's nicer to
        // align with
        if (mCutOutPaddingLeft > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingLeft, mRoundedCornerPadding);
            int contentMarginLeft = isLayoutRtl() ? mContentMarginEnd : mContentMarginStart;
            clockPaddingLeft = Math.max(cutoutPadding - contentMarginLeft - leftMargin, 0);
        }
        if (mCutOutPaddingRight > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingRight, mRoundedCornerPadding);
            int contentMarginRight = isLayoutRtl() ? mContentMarginStart : mContentMarginEnd;
            clockPaddingRight = Math.max(cutoutPadding - contentMarginRight - rightMargin, 0);
        }

        mSystemIconsView.setPadding(clockPaddingLeft,
                mWaterfallTopInset + mStatusBarPaddingTop,
                clockPaddingRight,
                0);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mRingerModeTracker.getRingerModeInternal().removeObservers(this);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        if (mHeaderQsPanel.switchTileLayout()) {
            updateResources();
        }
        mListening = listening;

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
            // Get the most up to date info
            mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
            mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mLifecycle.setCurrentState(Lifecycle.State.CREATED);
            mPrivacyItemController.removeCallback(mPICCallback);
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyChipBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_CLICK);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                mHost.collapsePanels();
            });
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
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
        int fillColor = mDualToneHandler.getSingleColor(intensity);
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

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mContentMarginStart = marginStart;
        mContentMarginEnd = marginEnd;
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mHeaderQsPanel) {
                // QS panel doesn't lays out some of its content full width
                mHeaderQsPanel.setContentMargins(marginStart, marginEnd);
            } else {
                MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
                lp.setMarginStart(marginStart);
                lp.setMarginEnd(marginEnd);
                view.setLayoutParams(lp);
            }
        }
        updateClockPadding();
    }

    public void setExpandedScrollAmount(int scrollY) {
        // The scrolling of the expanded qs has changed. Since the header text isn't part of it,
        // but would overlap content, we're fading it out.
        float newAlpha = 1.0f;
        if (mHeaderTextContainerView.getHeight() > 0) {
            newAlpha = MathUtils.map(0, mHeaderTextContainerView.getHeight() / 2.0f, 1.0f, 0.0f,
                    scrollY);
            newAlpha = Interpolators.ALPHA_OUT.getInterpolation(newAlpha);
        }
        mHeaderTextContainerView.setScrollY(scrollY);
        if (newAlpha != mExpandedHeaderAlpha) {
            mExpandedHeaderAlpha = newAlpha;
            mHeaderTextContainerView.setAlpha(MathUtils.lerp(0.0f, mExpandedHeaderAlpha,
                    mKeyguardExpansionFraction));
            updateHeaderTextContainerAlphaAnimator();
        }
    }

    private boolean getChipEnabled() {
        return mMicCameraIndicatorsEnabled || mAllIndicatorsEnabled;
    }
}
