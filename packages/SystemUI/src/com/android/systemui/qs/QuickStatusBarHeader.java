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

import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.StatusBarMobileView;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout {

    private boolean mExpanded;
    private boolean mQsDisabled;

    private TouchAnimator mAlphaAnimator;
    private TouchAnimator mTranslationAnimator;

    protected QuickQSPanel mHeaderQsPanel;
    private View mDatePrivacyView;
    private View mClockIconsView;
    private View mContainer;

    private View mQSCarriers;
    private Clock mClockView;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private StatusIconContainer mIconContainer;


    private TintedIconManager mTintedIconManager;
    private QSExpansionPathInterpolator mQSExpansionPathInterpolator;

    private int mStatusBarPaddingTop = 0;
    private int mRoundedCornerPadding = 0;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mClockIconsAlpha = 1.0f;
    private float mDatePrivacyAlpha = 1.0f;
    private float mKeyguardExpansionFraction;
    private int mTextColorPrimary = Color.TRANSPARENT;
    private int mTopViewMeasureHeight;

    private final String mMobileSlotName;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMobileSlotName = context.getString(com.android.internal.R.string.status_bar_mobile);
    }

    /**
     * How much the view containing the clock and QQS will translate down when QS is fully expanded.
     *
     * This matches the measured height of the view containing the date and privacy icons.
     */
    public int getOffsetTranslation() {
        return mTopViewMeasureHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mDatePrivacyView = findViewById(R.id.quick_status_bar_date_privacy);
        mClockIconsView = findViewById(R.id.quick_qs_status_icons);
        mQSCarriers = findViewById(R.id.carrier_group);
        mContainer = findViewById(R.id.container);
        mIconContainer = findViewById(R.id.statusIcons);

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
    }

    void onAttach(TintedIconManager iconManager,
            QSExpansionPathInterpolator qsExpansionPathInterpolator) {
        mTintedIconManager = iconManager;
        int fillColor = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.textColorPrimary);

        // Set the correct tint for the status icons so they contrast
        iconManager.setTint(fillColor);

        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        updateAnimators();
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mDatePrivacyView.getMeasuredHeight() != mTopViewMeasureHeight) {
            mTopViewMeasureHeight = mDatePrivacyView.getMeasuredHeight();
            updateAnimators();
        }
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

    void updateResources() {
        Resources resources = mContext.getResources();

        mRoundedCornerPadding = resources.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        mStatusBarPaddingTop = resources.getDimensionPixelSize(R.dimen.status_bar_padding_top);

        int qsOffsetHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);

        mDatePrivacyView.getLayoutParams().height =
                Math.max(qsOffsetHeight, mDatePrivacyView.getMinimumHeight());
        mDatePrivacyView.setLayoutParams(mDatePrivacyView.getLayoutParams());

        mClockIconsView.getLayoutParams().height =
                Math.max(qsOffsetHeight, mClockIconsView.getMinimumHeight());
        mClockIconsView.setLayoutParams(mClockIconsView.getLayoutParams());

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = mClockIconsView.getLayoutParams().height;
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
            if (mTintedIconManager != null) {
                mTintedIconManager.setTint(textColor);
            }
            mBatteryRemainingIcon.updateColors(mTextColorPrimary, textColorSecondary,
                    mTextColorPrimary);
        }
        updateHeadersPadding();
        updateAnimators();
    }

    private void updateAnimators() {
        updateAlphaAnimator();
        int offset = mTopViewMeasureHeight;

        mTranslationAnimator = new TouchAnimator.Builder()
                .addFloat(mContainer, "translationY", 0, offset)
                .setInterpolator(mQSExpansionPathInterpolator != null
                        ? mQSExpansionPathInterpolator.getYInterpolator()
                        : null)
                .build();
    }

    private void updateAlphaAnimator() {
        StatusBarMobileView icon =
                ((StatusBarMobileView) mIconContainer.getViewForSlot(mMobileSlotName));
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                .addFloat(mQSCarriers, "alpha", 0, 1)
                .addFloat(mDatePrivacyView, "alpha", 0, mDatePrivacyAlpha);
        if (icon != null) {
            builder.addFloat(icon, "alpha", 1, 0);
            builder.setListener(new TouchAnimator.ListenerAdapter() {
                @Override
                public void onAnimationAtEnd() {
                    icon.forceHidden(true);
                    icon.setVisibleState(STATE_HIDDEN);
                }

                @Override
                public void onAnimationStarted() {
                    icon.forceHidden(false);
                    icon.setVisibleState(STATE_ICON);
                }
            });
        }
        mAlphaAnimator = builder.build();
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

        if (mAlphaAnimator != null) {
            mAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mTranslationAnimator != null) {
            mTranslationAnimator.setPosition(keyguardExpansionFraction);
        }
        // If forceExpanded (we are opening QS from lockscreen), the animators have been set to
        // position = 1f.
        if (forceExpanded) {
            setTranslationY(panelTranslationY);
        } else {
            setTranslationY(0);
        }

        mKeyguardExpansionFraction = keyguardExpansionFraction;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mClockIconsView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Handle padding of the views
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> cornerCutoutPadding = StatusBarWindowView.cornerCutoutMargins(
                cutout, getDisplay());
        Pair<Integer, Integer> padding =
                StatusBarWindowView.paddingNeededForCutoutAndRoundedCorner(
                        cutout, cornerCutoutPadding, -1);
        mDatePrivacyView.setPadding(padding.first, 0, padding.second, 0);
        mClockIconsView.setPadding(padding.first, 0, padding.second, 0);
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
        updateHeadersPadding();
        return super.onApplyWindowInsets(insets);
    }

    private void updateHeadersPadding() {
        int paddingLeft = 0;
        int paddingRight = 0;

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
            paddingLeft = Math.max(cutoutPadding - contentMarginLeft - leftMargin, 0);
        }
        if (mCutOutPaddingRight > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingRight, mRoundedCornerPadding);
            int contentMarginRight = isLayoutRtl() ? mContentMarginStart : mContentMarginEnd;
            paddingRight = Math.max(cutoutPadding - contentMarginRight - rightMargin, 0);
        }

        mDatePrivacyView.setPadding(paddingLeft,
                mWaterfallTopInset + mStatusBarPaddingTop,
                paddingRight,
                0);
        mClockIconsView.setPadding(paddingLeft,
                mWaterfallTopInset + mStatusBarPaddingTop,
                paddingRight,
                0);
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    /** */
    public void setContentMargins(int marginStart, int marginEnd,
            QuickQSPanelController quickQSPanelController) {
        mContentMarginStart = marginStart;
        mContentMarginEnd = marginEnd;
        // The clock and QQS are not direct children, but the container should be just a wrapper to
        // be able to move them together. So we set the margins to the actual views.
        quickQSPanelController.setContentMargins(0, 0);
        setContentMargins(mDatePrivacyView, marginStart, marginEnd);
        setContentMargins(mClockIconsView, marginStart, marginEnd);
        updateHeadersPadding();
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }

    /**
     * When QS is scrolling, mClockIconsAlpha should scroll away and fade out.
     *
     * For a given scroll level, this method does the following:
     * <ol>
     *     <li>Determine the alpha that {@code mClockIconsView} should have when the panel is fully
     *         expanded.</li>
     *     <li>Set the scroll of {@code mClockIconsView} to the same of {@code QSPanel}.</li>
     *     <li>Set the alpha of {@code mClockIconsView} to that determined by the expansion of
     *         the panel, interpolated between 1 (no expansion) and {@code mClockIconsAlpha} (fully
     *         expanded), matching the animator.</li>
     * </ol>
     *
     * @param scrollY the scroll of the QSPanel container
     */
    public void setExpandedScrollAmount(int scrollY) {
        // The scrolling of the expanded qs has changed. Since the header text isn't part of it,
        // but would overlap content, we're fading it out.
        float newAlpha = 1.0f;
        if (mClockIconsView.getHeight() > 0) {
            newAlpha = MathUtils.map(0, mClockIconsView.getHeight() / 2.0f, 1.0f, 0.0f,
                    scrollY);
            newAlpha = Interpolators.ALPHA_OUT.getInterpolation(newAlpha);
        }
        mClockIconsView.setScrollY(scrollY);
        if (newAlpha != mClockIconsAlpha) {
            mClockIconsAlpha = newAlpha;
            mClockIconsView.setAlpha(MathUtils.lerp(1.0f, mClockIconsAlpha,
                    mKeyguardExpansionFraction));
            updateAlphaAnimator();
        }
    }
}
