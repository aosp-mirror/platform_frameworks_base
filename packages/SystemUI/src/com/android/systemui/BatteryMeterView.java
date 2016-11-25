/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.systemui;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.tuner.TunerService;

public class BatteryMeterView extends ImageView implements
        BatteryController.BatteryStateChangeCallback, TunerService.Tunable {

    private static final String STATUS_BAR_BATTERY_STYLE =
            Settings.Secure.STATUS_BAR_BATTERY_STYLE;
    private static final String STATUS_BAR_CHARGE_COLOR =
            Settings.Secure.STATUS_BAR_CHARGE_COLOR;

    private BatteryMeterDrawable mDrawable;
    private final String mSlotBattery;
    private BatteryController mBatteryController;

    private final Context mContext;
    private final int mFrameColor;

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.batterymeter_frame_color));
        mDrawable = new BatteryMeterDrawable(context, new Handler(), frameColor);
        atts.recycle();

        mSlotBattery = context.getString(
                com.android.internal.R.string.status_bar_battery);
        setImageDrawable(mDrawable);

        mContext = context;
        mFrameColor = frameColor;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            ArraySet<String> icons = StatusBarIconController.getIconBlacklist(newValue);
            setVisibility(icons.contains(mSlotBattery) ? View.GONE : View.VISIBLE);
        } else if (STATUS_BAR_BATTERY_STYLE.equals(key)) {
            updateBatteryStyle(newValue);
        } else if (STATUS_BAR_CHARGE_COLOR.equals(key)) {
            updateBoltColor();
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController.addStateChangedCallback(this);
        mDrawable.startListening();
        TunerService.get(getContext()).addTunable(this, StatusBarIconController.ICON_BLACKLIST,
                STATUS_BAR_BATTERY_STYLE, STATUS_BAR_CHARGE_COLOR);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBatteryController.removeStateChangedCallback(this);
        mDrawable.stopListening();
        TunerService.get(getContext()).removeTunable(this);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setContentDescription(
                getContext().getString(charging ? R.string.accessibility_battery_level_charging
                        : R.string.accessibility_battery_level, level));
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {

    }

    public void setBatteryController(BatteryController mBatteryController) {
        this.mBatteryController = mBatteryController;
        mDrawable.setBatteryController(mBatteryController);
    }

    public void setDarkIntensity(float f) {
        mDrawable.setDarkIntensity(f);
    }

    private void updateBatteryStyle(String styleStr) {
        final int style = styleStr == null ?
                BatteryMeterDrawable.BATTERY_STYLE_PORTRAIT : Integer.parseInt(styleStr);

        switch (style) {
            case BatteryMeterDrawable.BATTERY_STYLE_TEXT:
            case BatteryMeterDrawable.BATTERY_STYLE_HIDDEN:
                setVisibility(View.GONE);
                setImageDrawable(null);
                break;
            default:
                mDrawable = new BatteryMeterDrawable(mContext, new Handler(), mFrameColor, style);
                setImageDrawable(mDrawable);
                setVisibility(View.VISIBLE);
                break;
        }
        restoreDrawableAttributes();
        requestLayout();
    }

    private void updateBoltColor() {
        final int style = Settings.Secure.getInt(getContext().getContentResolver(), STATUS_BAR_BATTERY_STYLE, 0);
        if (style == BatteryMeterDrawable.BATTERY_STYLE_TEXT || style == BatteryMeterDrawable.BATTERY_STYLE_HIDDEN) {
            return;
        } else {
        mDrawable = new BatteryMeterDrawable(mContext, new Handler(), mFrameColor, style);
        setImageDrawable(mDrawable);
        setVisibility(View.VISIBLE);
        restoreDrawableAttributes();
        requestLayout();
        }
    }

    private void restoreDrawableAttributes() {
        mDrawable.setBatteryController(mBatteryController);
        mDrawable.startListening();
    }
}
