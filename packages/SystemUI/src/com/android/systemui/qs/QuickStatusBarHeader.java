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

import android.annotation.ColorInt;
import android.app.AlarmManager.AlarmClockInfo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.Pair;
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
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.policy.Clock;

import java.util.Locale;
import java.util.Objects;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements LifecycleOwner {

    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private boolean mExpanded;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;

    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    private int mStatusBarPaddingTop = 0;
    private int mRoundedCornerPadding = 0;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mExpandedHeaderAlpha = 1.0f;
    private float mKeyguardExpansionFraction;
    private int mTextColorPrimary = Color.TRANSPARENT;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mClockView = findViewById(R.id.clock);
        mSpace = findViewById(R.id.space);
        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);

        updateResources();

        // Don't need to worry about tuner settings for this icon
        mBatteryRemainingIcon.setIgnoreTunerUpdates(true);
        // QS will always show the estimate, and BatteryMeterView handles the case where
        // it's unavailable or charging
        mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);
    }

    void onAttach(TintedIconManager iconManager) {
        int fillColor = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.textColorPrimary);

        // Set the correct tint for the status icons so they contrast
        iconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    void updateStatusText(int ringerMode, AlarmClockInfo nextAlarm, boolean zenOverridingRinger,
            boolean use24HourFormat) {
        boolean changed = updateRingerStatus(ringerMode, zenOverridingRinger)
                || updateAlarmStatus(nextAlarm, use24HourFormat);

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private boolean updateRingerStatus(int ringerMode, boolean zenOverridingRinger) {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!zenOverridingRinger) {
            if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
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

    private boolean updateAlarmStatus(AlarmClockInfo nextAlarm, boolean use24HourFormat) {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (nextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(nextAlarm, use24HourFormat));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
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

    void updateResources() {
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

        int textColor = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
        if (textColor != mTextColorPrimary) {
            int textColorSecondary = Utils.getColorAttrDefaultColor(mContext,
                    android.R.attr.textColorSecondary);
            mTextColorPrimary = textColor;
            mClockView.setTextColor(textColor);
            mBatteryRemainingIcon.updateColors(mTextColorPrimary, textColorSecondary,
                    mTextColorPrimary);
        }

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
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

    /** */
    public void setExpanded(boolean expanded, QuickQSPanelController quickQSPanelController) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        quickQSPanelController.setExpanded(expanded);
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
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
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

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmClockInfo info, boolean use24HourFormat) {
        if (info == null) {
            return "";
        }
        String skeleton = use24HourFormat ? "EHm" : "Ehma";
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

    /** */
    public void setContentMargins(int marginStart, int marginEnd,
            QuickQSPanelController quickQSPanelController) {
        mContentMarginStart = marginStart;
        mContentMarginEnd = marginEnd;
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mHeaderQsPanel) {
                // QS panel doesn't lays out some of its content full width
                quickQSPanelController.setContentMargins(marginStart, marginEnd);
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
}
