/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.graphics.Paint.Style;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;

import com.android.keyguard.R;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Controller for Bubble clock that can appear on lock screen and AOD.
 */
public class BubbleClockController implements ClockPlugin {

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    private View mView;
    private TextClock mDigitalClock;
    private ImageClock mAnalogClock;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private View mLockClockContainer;
    private TextClock mLockClock;

    /**
     * Controller for transition to dark state.
     */
    private CrossFadeDarkController mDarkController;

    private BubbleClockController() { }

    /**
     * Create a BubbleClockController instance.
     *
     * @param layoutInflater Inflater used to inflate custom clock views.
     */
    public static BubbleClockController build(LayoutInflater layoutInflater) {
        BubbleClockController controller = new BubbleClockController();
        controller.createViews(layoutInflater);
        return controller;
    }

    private void createViews(LayoutInflater layoutInflater) {
        mView = layoutInflater.inflate(R.layout.bubble_clock, null);
        mDigitalClock = (TextClock) mView.findViewById(R.id.digital_clock);
        mAnalogClock = (ImageClock) mView.findViewById(R.id.analog_clock);

        mLockClockContainer = layoutInflater.inflate(R.layout.digital_clock, null);
        mLockClock = (TextClock) mLockClockContainer.findViewById(R.id.lock_screen_clock);
        mLockClock.setVisibility(View.GONE);

        mDarkController = new CrossFadeDarkController(mDigitalClock, mLockClock);
    }

    @Override
    public View getView() {
        return mLockClockContainer;
    }

    @Override
    public View getBigClockView() {
        return mView;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        mLockClock.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        if (colorPalette == null || colorPalette.length == 0) {
            return;
        }
        final int length = colorPalette.length;
        mDigitalClock.setTextColor(colorPalette[Math.max(0, length - 6)]);
        mAnalogClock.setClockColors(colorPalette[Math.max(0, length - 6)],
                colorPalette[Math.max(0, length - 3)]);
    }

    @Override
    public void dozeTimeTick() {
        mAnalogClock.onTimeChanged();
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mDarkController.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mAnalogClock.onTimeZoneChanged(timeZone);
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
