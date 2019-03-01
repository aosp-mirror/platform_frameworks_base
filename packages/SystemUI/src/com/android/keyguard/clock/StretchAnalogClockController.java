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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint.Style;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;

import com.android.keyguard.R;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Controller for Stretch clock that can appear on lock screen and AOD.
 */
public class StretchAnalogClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    private View mBigClockView;
    private TextClock mDigitalClock;
    private StretchAnalogClock mAnalogClock;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private View mView;
    private TextClock mLockClock;

    /**
     * Controller for transition to dark state.
     */
    private CrossFadeDarkController mDarkController;

    /**
     * Create a BubbleClockController instance.
     *
     * @param layoutInflater Inflater used to inflate custom clock views.
     */
    public StretchAnalogClockController(Resources res, LayoutInflater inflater) {
        mResources = res;
        mLayoutInflater = inflater;
    }

    private void createViews() {
        mBigClockView = mLayoutInflater.inflate(R.layout.stretchanalog_clock, null);
        mAnalogClock = mBigClockView.findViewById(R.id.analog_clock);
        mDigitalClock = mBigClockView.findViewById(R.id.digital_clock);

        mView = mLayoutInflater.inflate(R.layout.digital_clock, null);
        mLockClock = mView.findViewById(R.id.lock_screen_clock);
        mLockClock.setVisibility(View.GONE);

        mDarkController = new CrossFadeDarkController(mDigitalClock, mLockClock);
    }

    @Override
    public String getName() {
        return "stretch";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_stretch);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.stretch_thumbnail);
    }

    @Override
    public View getView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public View getBigClockView() {
        if (mBigClockView == null) {
            createViews();
        }
        return mBigClockView;
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
        mDigitalClock.setTextColor(colorPalette[Math.max(0, length - 5)]);
        mAnalogClock.setClockColor(colorPalette[Math.max(0, length - 5)],
                colorPalette[Math.max(0, length - 2)]);
    }

    @Override
    public void onTimeTick() {
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
