/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License.
 */
package com.android.systemui.battery;

import static com.android.systemui.DejankUtils.whitelistIpcs;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.graph.CircleBatteryDrawable;
import com.android.settingslib.graph.FullCircleBatteryDrawable;
import com.android.settingslib.graph.RLandscapeBatteryDrawable;
import com.android.settingslib.graph.LandscapeBatteryDrawable;
import com.android.settingslib.graph.RLandscapeBatteryDrawableStyleA;
import com.android.settingslib.graph.LandscapeBatteryDrawableStyleA;
import com.android.settingslib.graph.RLandscapeBatteryDrawableStyleB;
import com.android.settingslib.graph.LandscapeBatteryDrawableStyleB;
import com.android.settingslib.graph.LandscapeBatteryDrawableBuddy;
import com.android.settingslib.graph.LandscapeBatteryDrawableLine;
import com.android.settingslib.graph.LandscapeBatteryDrawableSignal;
import com.android.settingslib.graph.LandscapeBatteryDrawableMusku;
import com.android.settingslib.graph.LandscapeBatteryDrawablePill;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;
import java.util.ArrayList;

public class BatteryMeterView extends LinearLayout implements DarkReceiver {

    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_RLANDSCAPE = 1;
    public static final int BATTERY_STYLE_LANDSCAPE = 2;
    public static final int BATTERY_STYLE_CIRCLE = 3;
    public static final int BATTERY_STYLE_DOTTED_CIRCLE = 4;
    public static final int BATTERY_STYLE_BIG_CIRCLE = 5;
    public static final int BATTERY_STYLE_BIG_DOTTED_CIRCLE = 6;
    public static final int BATTERY_STYLE_FULL_CIRCLE = 7;
    public static final int BATTERY_STYLE_TEXT = 8;
    public static final int BATTERY_STYLE_HIDDEN = 9;
    public static final int BATTERY_STYLE_LANDSCAPE_BUDDY = 10;
    public static final int BATTERY_STYLE_LANDSCAPE_LINE = 11;
    public static final int BATTERY_STYLE_LANDSCAPE_MUSKU = 12;
    public static final int BATTERY_STYLE_LANDSCAPE_PILL = 13;
    public static final int BATTERY_STYLE_LANDSCAPE_SIGNAL = 14;
    public static final int BATTERY_STYLE_RLANDSCAPE_STYLE_A = 15;
    public static final int BATTERY_STYLE_LANDSCAPE_STYLE_A = 16;
    public static final int BATTERY_STYLE_RLANDSCAPE_STYLE_B = 17;
    public static final int BATTERY_STYLE_LANDSCAPE_STYLE_B = 18;

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3; // Not to be used

    private final AccessorizedBatteryDrawable mThemedDrawable;
    private final CircleBatteryDrawable mCircleDrawable;
    private final FullCircleBatteryDrawable mFullCircleDrawable;
    private final RLandscapeBatteryDrawable mRLandscapeDrawable;
    private final LandscapeBatteryDrawable mLandscapeDrawable;
    private final RLandscapeBatteryDrawableStyleA mRLandscapeDrawableStyleA;
    private final LandscapeBatteryDrawableStyleA mLandscapeDrawableStyleA;
    private final RLandscapeBatteryDrawableStyleB mRLandscapeDrawableStyleB;
    private final LandscapeBatteryDrawableStyleB mLandscapeDrawableStyleB;
    private final LandscapeBatteryDrawableBuddy mLandscapeDrawableBuddy;
    private final LandscapeBatteryDrawableLine mLandscapeDrawableLine;
    private final LandscapeBatteryDrawableMusku mLandscapeDrawableMusku;
    private final LandscapeBatteryDrawablePill mLandscapeDrawablePill;
    private final LandscapeBatteryDrawableSignal mLandscapeDrawableSignal;
    private final ImageView mBatteryIconView;
    private TextView mBatteryPercentView;

    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    private String mEstimateText = null;
    private boolean mCharging;
    private boolean mIsOverheated;
    private boolean mDisplayShieldEnabled;
    // Error state where we know nothing about the current battery state
    private boolean mBatteryStateUnknown;
    // Lazily-loaded since this is expected to be a rare-if-ever state
    private Drawable mUnknownStateDrawable;

    private int mBatteryStyle = BATTERY_STYLE_PORTRAIT;
    private int mShowBatteryPercent;
    private int mShowBatteryEstimate = 0;
    private boolean mBatteryPercentCharging;
    private int mTextChargingSymbol;

    private DualToneHandler mDualToneHandler;

    private final ArrayList<BatteryMeterViewCallbacks> mCallbacks = new ArrayList<>();

    private int mNonAdaptedSingleToneColor;
    private int mNonAdaptedForegroundColor;
    private int mNonAdaptedBackgroundColor;

    private BatteryEstimateFetcher mBatteryEstimateFetcher;

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);
        mThemedDrawable = new AccessorizedBatteryDrawable(context, frameColor);
        mCircleDrawable = new CircleBatteryDrawable(context, frameColor);
        mFullCircleDrawable = new FullCircleBatteryDrawable(context, frameColor);
        mRLandscapeDrawable = new RLandscapeBatteryDrawable(context, frameColor);
        mLandscapeDrawable = new LandscapeBatteryDrawable(context, frameColor);
        mRLandscapeDrawableStyleA = new RLandscapeBatteryDrawableStyleA(context, frameColor);
        mLandscapeDrawableStyleA = new LandscapeBatteryDrawableStyleA(context, frameColor);
        mRLandscapeDrawableStyleB = new RLandscapeBatteryDrawableStyleB(context, frameColor);
        mLandscapeDrawableStyleB = new LandscapeBatteryDrawableStyleB(context, frameColor);
        mLandscapeDrawableBuddy = new LandscapeBatteryDrawableBuddy(context, frameColor);
        mLandscapeDrawableLine = new LandscapeBatteryDrawableLine(context, frameColor);
        mLandscapeDrawableMusku = new LandscapeBatteryDrawableMusku(context, frameColor);
        mLandscapeDrawablePill = new LandscapeBatteryDrawablePill(context, frameColor);
        mLandscapeDrawableSignal = new LandscapeBatteryDrawableSignal(context, frameColor);
        atts.recycle();

        setupLayoutTransition();

        mBatteryIconView = new ImageView(context);
        mBatteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);
        updateDrawable();

        Resources res = getContext().getResources();
        int batteryHeight;
	int batteryWidth;
	switch(mBatteryStyle) {
               case BATTERY_STYLE_CIRCLE:
               case BATTERY_STYLE_DOTTED_CIRCLE:
               case BATTERY_STYLE_FULL_CIRCLE:
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width);
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width);
                    break;
               case BATTERY_STYLE_LANDSCAPE:
               case BATTERY_STYLE_RLANDSCAPE:
               case BATTERY_STYLE_RLANDSCAPE_STYLE_A:
               case BATTERY_STYLE_LANDSCAPE_STYLE_A:
               case BATTERY_STYLE_RLANDSCAPE_STYLE_B:
               case BATTERY_STYLE_LANDSCAPE_STYLE_B:
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape);
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape);
                    break;
                case BATTERY_STYLE_BIG_CIRCLE:
                case BATTERY_STYLE_BIG_DOTTED_CIRCLE:
                    batteryHeight = res.getDimensionPixelSize(R.dimen.big_battery_height);
                    batteryWidth = res.getDimensionPixelSize(R.dimen.big_battery_height);
                    break;
               case BATTERY_STYLE_LANDSCAPE_SIGNAL:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_signal);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_signal);
                    break;
               case BATTERY_STYLE_LANDSCAPE_LINE:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_line);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_line);
                    break;
               case BATTERY_STYLE_LANDSCAPE_PILL:
               case BATTERY_STYLE_LANDSCAPE_MUSKU:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_pill_musku);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_pill_musku);
                    break;
               case BATTERY_STYLE_LANDSCAPE_BUDDY:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_buddy);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_buddy);
                    break;
               case BATTERY_STYLE_PORTRAIT:
               default:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
                    break;
	}

        final MarginLayoutParams mlp = new MarginLayoutParams(batteryWidth, batteryHeight);
        mlp.setMargins(0, 0, 0,
                getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);

        updatePercentView();
        updateVisibility();

        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        if (isNightMode()) {
            onDarkChanged(new ArrayList<Rect>(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);
        }

        setClipChildren(false);
        setClipToPadding(false);
    }

    private boolean isNightMode() {
        return (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        // Animates appearing/disappearing of the battery percentage text using fade-in/fade-out
        // and disables all other animation types
        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        transition.setAnimator(LayoutTransition.CHANGE_APPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGING, null);

        setLayoutTransition(transition);
    }

    public int getBatteryStyle() {
        return mBatteryStyle;
    }

    public void setBatteryStyle(int batteryStyle) {
        if (batteryStyle == mBatteryStyle) return;
        mBatteryStyle = batteryStyle;
        updateBatteryStyle();
    }

    protected void updateBatteryStyle() {
        updateDrawable();
        scaleBatteryMeterViews();
        updatePercentView();
        updateVisibility();
    }

    public void setBatteryPercent(int showBatteryPercent) {
        if (showBatteryPercent == mShowBatteryPercent) return;
        mShowBatteryPercent = showBatteryPercent;
        updatePercentView();
    }

    public void setBatteryPercentCharging(boolean batteryPercentCharging) {
        if (batteryPercentCharging == mBatteryPercentCharging) return;
        mBatteryPercentCharging = batteryPercentCharging;
        updatePercentView();
    }

    public void updateTextChargingSymbol(int textChargingSymbol) {
        if (textChargingSymbol == mTextChargingSymbol) return;
        mTextChargingSymbol = textChargingSymbol;
        updatePercentView();
    }

    public int getBatteryEstimate() {
        return mShowBatteryEstimate;
    }

    public void setBatteryEstimate(int showBatteryEstimate) {
        if (showBatteryEstimate == mShowBatteryEstimate) return;
        mShowBatteryEstimate = showBatteryEstimate;
        updatePercentView();
        updateVisibility();
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * 3 - Estimate
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        if (mode == mShowPercentMode) return;
        mShowPercentMode = mode;
        updateShowPercent();
        updatePercentText();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateBatteryStyle();
        mThemedDrawable.notifyDensityChanged();
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    void onBatteryLevelChanged(int level, boolean pluggedIn) {
        if (mLevel != level) {
            mLevel = level;
            mThemedDrawable.setBatteryLevel(mLevel);
            mCircleDrawable.setBatteryLevel(mLevel);
            mFullCircleDrawable.setBatteryLevel(mLevel);
            mRLandscapeDrawable.setBatteryLevel(mLevel);
            mLandscapeDrawable.setBatteryLevel(mLevel);
            mRLandscapeDrawableStyleA.setBatteryLevel(mLevel);
            mLandscapeDrawableStyleA.setBatteryLevel(mLevel);
            mRLandscapeDrawableStyleB.setBatteryLevel(mLevel);
            mLandscapeDrawableStyleB.setBatteryLevel(mLevel);
            mLandscapeDrawableBuddy.setBatteryLevel(mLevel);
            mLandscapeDrawableLine.setBatteryLevel(mLevel);
            mLandscapeDrawableMusku.setBatteryLevel(mLevel);
            mLandscapeDrawablePill.setBatteryLevel(mLevel);
            mLandscapeDrawableSignal.setBatteryLevel(mLevel);
            updatePercentText();
        }
        if (mCharging != pluggedIn) {
            mCharging = pluggedIn;
            mThemedDrawable.setCharging(mCharging);
            mCircleDrawable.setCharging(mCharging);
            mFullCircleDrawable.setCharging(mCharging);
            mRLandscapeDrawable.setCharging(mCharging);
            mLandscapeDrawable.setCharging(mCharging);
            mRLandscapeDrawableStyleA.setCharging(mCharging);
            mLandscapeDrawableStyleA.setCharging(mCharging);
            mRLandscapeDrawableStyleB.setCharging(mCharging);
            mLandscapeDrawableStyleB.setCharging(mCharging);
            mLandscapeDrawableBuddy.setCharging(mCharging);
            mLandscapeDrawableLine.setCharging(mCharging);
            mLandscapeDrawableMusku.setCharging(mCharging);
            mLandscapeDrawablePill.setCharging(mCharging);
            mLandscapeDrawableSignal.setCharging(mCharging);
            updateShowPercent();
            updatePercentText();
        }
    }

    void onPowerSaveChanged(boolean isPowerSave) {
        mCircleDrawable.setPowerSaveEnabled(isPowerSave);
        mThemedDrawable.setPowerSaveEnabled(isPowerSave);
        mFullCircleDrawable.setPowerSaveEnabled(isPowerSave);
        mRLandscapeDrawable.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawable.setPowerSaveEnabled(isPowerSave);
        mRLandscapeDrawableStyleA.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableStyleA.setPowerSaveEnabled(isPowerSave);
        mRLandscapeDrawableStyleB.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableStyleB.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableBuddy.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableLine.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableMusku.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawablePill.setPowerSaveEnabled(isPowerSave);
        mLandscapeDrawableSignal.setPowerSaveEnabled(isPowerSave);
    }

    void onIsOverheatedChanged(boolean isOverheated) {
        boolean valueChanged = mIsOverheated != isOverheated;
        mIsOverheated = isOverheated;
        if (valueChanged) {
            updateContentDescription();
            // The battery drawable is a different size depending on whether it's currently
            // overheated or not, so we need to re-scale the view when overheated changes.
            scaleBatteryMeterViews();
        }
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
        updateShowPercent();
    }

    /**
     * Sets the fetcher that should be used to get the estimated time remaining for the user's
     * battery.
     */
    void setBatteryEstimateFetcher(BatteryEstimateFetcher fetcher) {
        mBatteryEstimateFetcher = fetcher;
    }

    void setDisplayShieldEnabled(boolean displayShieldEnabled) {
        mDisplayShieldEnabled = displayShieldEnabled;
    }

    void updatePercentText() {
        if (mBatteryStateUnknown) {
            return;
        }

        if (mBatteryPercentView != null) {
            setPercentTextAtCurrentLevel();
        } else {
            updateContentDescription();
        }
        if (mBatteryEstimateFetcher != null && mShowBatteryEstimate != 0 && !mCharging) {
                mBatteryEstimateFetcher.fetchBatteryTimeRemainingEstimate(
                        (String estimate) -> {
                if (mBatteryPercentView == null) {
                    return;
                }
                if (estimate != null) {
                    mBatteryPercentView.setText(estimate);
                    setContentDescription(getContext().getString(
                            R.string.accessibility_battery_level_with_estimate,
                            mLevel, estimate));
                } else {
                    setPercentTextAtCurrentLevel();
                    setContentDescription(getContext().getString(
                            R.string.accessibility_battery_level,
                            mLevel));
                }
            });
        }
    }

    private void setPercentTextAtCurrentLevel() {
        if (mBatteryPercentView == null) {
              return;
        }

        String text = NumberFormat.getPercentInstance().format(mLevel / 100f);

        if (mCharging && (mBatteryPercentView != null && (mBatteryStyle == BATTERY_STYLE_TEXT
            || mBatteryStyle == BATTERY_STYLE_HIDDEN)) && mTextChargingSymbol > 0) {
            switch (mTextChargingSymbol) {
                case 1:
                default:
                    text = "₹" + text;
                   break;
                case 2:
                    text = "~" + text;
                    break;
                case 3:
                    text = "+" + text;
                    break;
                case 4:
                    text = "*" + text;
                    break;
                case 5:
                    text = "⚡" + text;
                    break;
                case 6:
                    text = "$" + text;
                    break;
                case 7:
                    text = "€" + text;
                    break;
                case 8:
                    text = "¢" + text;
                    break;
            }
        }
        mBatteryPercentView.setText(text);
    }

    private void updateContentDescription() {
        Context context = getContext();

        String contentDescription;
        if (mBatteryStateUnknown) {
            contentDescription = context.getString(R.string.accessibility_battery_unknown);
        } else if (mShowPercentMode == MODE_ESTIMATE && !TextUtils.isEmpty(mEstimateText)) {
            contentDescription = context.getString(
                    mIsOverheated
                            ? R.string.accessibility_battery_level_charging_paused_with_estimate
                            : R.string.accessibility_battery_level_with_estimate,
                    mLevel,
                    mEstimateText);
        } else if (mIsOverheated) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging_paused, mLevel);
        } else if (mCharging) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging, mLevel);
        } else {
            contentDescription = context.getString(R.string.accessibility_battery_level, mLevel);
        }

        setContentDescription(contentDescription);
    }

    private void removeBatteryPercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
    }

    void updateShowPercent() {
        boolean drawPercentInside = mShowBatteryPercent == 1
                                    && !mCharging && !mBatteryStateUnknown;
        boolean showPercent = mShowBatteryPercent >= 2
                                    || mBatteryStyle == BATTERY_STYLE_TEXT
                                    || mShowPercentMode == MODE_ON;
        showPercent = showPercent && !mBatteryStateUnknown
                                    && mBatteryStyle != BATTERY_STYLE_HIDDEN;

        mCircleDrawable.setShowPercent(drawPercentInside);
        mFullCircleDrawable.setShowPercent(drawPercentInside);
        mRLandscapeDrawableStyleA.setShowPercent(drawPercentInside);
        mLandscapeDrawableStyleA.setShowPercent(drawPercentInside);
        mRLandscapeDrawableStyleB.setShowPercent(drawPercentInside);
        mLandscapeDrawableStyleB.setShowPercent(drawPercentInside);
        mLandscapeDrawableBuddy.setShowPercent(drawPercentInside);
        mLandscapeDrawableLine.setShowPercent(drawPercentInside);
        mLandscapeDrawableMusku.setShowPercent(drawPercentInside);
        mLandscapeDrawablePill.setShowPercent(drawPercentInside);
        mLandscapeDrawableSignal.setShowPercent(drawPercentInside);
        mThemedDrawable.showPercent(drawPercentInside);

        if (showPercent || (mBatteryPercentCharging && mCharging)
                || mShowBatteryEstimate != 0) {
            if (mBatteryPercentView == null) {
                mBatteryPercentView = loadPercentView();
                if (mPercentageStyleId != 0) { // Only set if specified as attribute
                    mBatteryPercentView.setTextAppearance(mPercentageStyleId);
                }
                if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
                updatePercentText();
                addView(mBatteryPercentView,
                        new ViewGroup.LayoutParams(
                                LayoutParams.WRAP_CONTENT,
                                LayoutParams.MATCH_PARENT));
            }
            if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT) {
                mBatteryPercentView.setPaddingRelative(0, 0, 0, 0);
            } else {
                Resources res = getContext().getResources();
                mBatteryPercentView.setPaddingRelative(
                        res.getDimensionPixelSize(R.dimen.battery_level_padding_start), 0, 0, 0);
                setLayoutDirection(mShowBatteryPercent > 2 ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            }

        } else {
            removeBatteryPercentView();
        }
    }

    private void updateVisibility() {
        if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT) {
            mBatteryIconView.setVisibility(View.GONE);
            mBatteryIconView.setImageDrawable(null);
        } else {
            mBatteryIconView.setVisibility(View.VISIBLE);
            scaleBatteryMeterViews();
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onHiddenBattery(mBatteryStyle == BATTERY_STYLE_HIDDEN);
        }
    }

    private Drawable getUnknownStateDrawable() {
        if (mUnknownStateDrawable == null) {
            mUnknownStateDrawable = mContext.getDrawable(R.drawable.ic_battery_unknown);
            mUnknownStateDrawable.setTint(mTextColor);
        }

        return mUnknownStateDrawable;
    }

    void onBatteryUnknownStateChanged(boolean isUnknown) {
        if (mBatteryStateUnknown == isUnknown) {
            return;
        }

        mBatteryStateUnknown = isUnknown;
        updateContentDescription();

        if (mBatteryStateUnknown) {
            mBatteryIconView.setImageDrawable(getUnknownStateDrawable());
        } else {
            updateDrawable();
        }

        updateShowPercent();
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    void scaleBatteryMeterViews() {
        if (mBatteryIconView == null) {
            return;
        }
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight;
        int batteryWidth;
        switch(mBatteryStyle) {
	       case BATTERY_STYLE_CIRCLE:
               case BATTERY_STYLE_DOTTED_CIRCLE:
               case BATTERY_STYLE_FULL_CIRCLE:
	            batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width);
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width);
                  break;
               case BATTERY_STYLE_LANDSCAPE:
               case BATTERY_STYLE_RLANDSCAPE:
               case BATTERY_STYLE_RLANDSCAPE_STYLE_A:
               case BATTERY_STYLE_LANDSCAPE_STYLE_A:
               case BATTERY_STYLE_RLANDSCAPE_STYLE_B:
               case BATTERY_STYLE_LANDSCAPE_STYLE_B:
	            batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape);
		    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape);
	         break;
              case BATTERY_STYLE_BIG_CIRCLE:
              case BATTERY_STYLE_BIG_DOTTED_CIRCLE:
	            batteryHeight = res.getDimensionPixelSize(R.dimen.big_battery_height);
	     	    batteryWidth = res.getDimensionPixelSize(R.dimen.big_battery_height);
	         break;
               case BATTERY_STYLE_LANDSCAPE_SIGNAL:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_signal);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_signal);
                 break;
               case BATTERY_STYLE_LANDSCAPE_LINE:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_line);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_line);
                 break;
               case BATTERY_STYLE_LANDSCAPE_PILL:
               case BATTERY_STYLE_LANDSCAPE_MUSKU:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_pill_musku);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_pill_musku);
                 break;
               case BATTERY_STYLE_LANDSCAPE_BUDDY:
                    batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape_buddy);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape_buddy);
                 break;
               case BATTERY_STYLE_PORTRAIT:
	       default:
	            batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
                    batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
                 break;
        }

        float mainBatteryHeight = batteryHeight * iconScaleFactor;
        float mainBatteryWidth = batteryWidth * iconScaleFactor;

        boolean displayShield = mDisplayShieldEnabled && mIsOverheated;
        float fullBatteryIconHeight =
                BatterySpecs.getFullBatteryHeight(mainBatteryHeight, displayShield);
        float fullBatteryIconWidth =
                BatterySpecs.getFullBatteryWidth(mainBatteryWidth, displayShield);

        int marginTop;
        if (displayShield) {
            // If the shield is displayed, we need some extra marginTop so that the bottom of the
            // main icon is still aligned with the bottom of all the other system icons.
            int shieldHeightAddition = Math.round(fullBatteryIconHeight - mainBatteryHeight);
            // However, the other system icons have some embedded bottom padding that the battery
            // doesn't have, so we shouldn't move the battery icon down by the full amount.
            // See b/258672854.
            marginTop = shieldHeightAddition
                    - res.getDimensionPixelSize(R.dimen.status_bar_battery_extra_vertical_spacing);
        } else {
            marginTop = 0;
        }

        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);


        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                Math.round(fullBatteryIconWidth),
                Math.round(fullBatteryIconHeight));
        scaledLayoutParams.setMargins(0, marginTop, 0, marginBottom);

        if (mBatteryIconView != null) {
            mBatteryIconView.setLayoutParams(scaledLayoutParams);
        }
    }

    private void updateDrawable() {
        boolean displayShield = mDisplayShieldEnabled && mIsOverheated;
        switch (mBatteryStyle) {
            case BATTERY_STYLE_PORTRAIT:
                mThemedDrawable.setDisplayShield(displayShield);
                mBatteryIconView.invalidateDrawable(mThemedDrawable);
                mBatteryIconView.setImageDrawable(mThemedDrawable);
                break;
            case BATTERY_STYLE_RLANDSCAPE:
                mBatteryIconView.invalidateDrawable(mRLandscapeDrawable);
                mBatteryIconView.setImageDrawable(mRLandscapeDrawable);
                break;
            case BATTERY_STYLE_LANDSCAPE:
                mBatteryIconView.invalidateDrawable(mLandscapeDrawable);
                mBatteryIconView.setImageDrawable(mLandscapeDrawable);
                break;
            case BATTERY_STYLE_RLANDSCAPE_STYLE_A:
                mBatteryIconView.setImageDrawable(mRLandscapeDrawableStyleA);
                mBatteryIconView.setVisibility(View.VISIBLE);
                scaleBatteryMeterViews();
                break;
            case BATTERY_STYLE_LANDSCAPE_STYLE_A:
                mBatteryIconView.setImageDrawable(mLandscapeDrawableStyleA);
                mBatteryIconView.setVisibility(View.VISIBLE);
                scaleBatteryMeterViews();
                break;
            case BATTERY_STYLE_RLANDSCAPE_STYLE_B:
                 mBatteryIconView.setImageDrawable(mRLandscapeDrawableStyleB);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_STYLE_B:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawableStyleB);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_BUDDY:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawableBuddy);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_LINE:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawableLine);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_MUSKU:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawableMusku);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_PILL:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawablePill);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_LANDSCAPE_SIGNAL:
                 mBatteryIconView.setImageDrawable(mLandscapeDrawableSignal);
                 mBatteryIconView.setVisibility(View.VISIBLE);
                 scaleBatteryMeterViews();
                 break;
            case BATTERY_STYLE_FULL_CIRCLE:
                mBatteryIconView.invalidateDrawable(mFullCircleDrawable);
                mBatteryIconView.setImageDrawable(mFullCircleDrawable);
                break;
            case BATTERY_STYLE_CIRCLE:
            case BATTERY_STYLE_DOTTED_CIRCLE:
            case BATTERY_STYLE_BIG_CIRCLE:
            case BATTERY_STYLE_BIG_DOTTED_CIRCLE:
                mCircleDrawable.setMeterStyle(mBatteryStyle);
                mBatteryIconView.invalidateDrawable(mCircleDrawable);
                mBatteryIconView.setImageDrawable(mCircleDrawable);
                break;
            case BATTERY_STYLE_HIDDEN:
            case BATTERY_STYLE_TEXT:
                return;
            default:
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        float intensity = DarkIconDispatcher.isInAreas(areas, this) ? darkIntensity : 0;
        mNonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        mNonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        mNonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                mNonAdaptedSingleToneColor);
    }

    /**
     * Sets icon and text colors. This will be overridden by {@code onDarkChanged} events,
     * if registered.
     *
     * @param foregroundColor
     * @param backgroundColor
     * @param singleToneColor
     */
    public void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mCircleDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mThemedDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mFullCircleDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mRLandscapeDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mRLandscapeDrawableStyleA.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableStyleA.setColors(foregroundColor, backgroundColor, singleToneColor);
        mRLandscapeDrawableStyleB.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableStyleB.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableBuddy.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableLine.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableMusku.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawablePill.setColors(foregroundColor, backgroundColor, singleToneColor);
        mLandscapeDrawableSignal.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }

        if (mUnknownStateDrawable != null) {
            mUnknownStateDrawable.setTint(singleToneColor);
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        String powerSave = mThemedDrawable == null ?
                null : mThemedDrawable.getPowerSaveEnabled() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    getPowerSave: " + powerSave);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mBatteryStateUnknown: " + mBatteryStateUnknown);
        pw.println("    mLevel: " + mLevel);
        pw.println("    mMode: " + mShowPercentMode);
    }

    @VisibleForTesting
    CharSequence getBatteryPercentViewText() {
        return mBatteryPercentView.getText();
    }

    /** An interface that will fetch the estimated time remaining for the user's battery. */
    public interface BatteryEstimateFetcher {
        void fetchBatteryTimeRemainingEstimate(
                BatteryController.EstimateFetchCompletion completion);
    }

    public interface BatteryMeterViewCallbacks {
        default void onHiddenBattery(boolean hidden) {}
    }

    public void addCallback(BatteryMeterViewCallbacks callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(BatteryMeterViewCallbacks callbacks) {
        mCallbacks.remove(callbacks);
    }
}
