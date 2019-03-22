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
 * Controller for Bubble clock that can appear on lock screen and AOD.
 */
public class BubbleClockController implements ClockPlugin {

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

    /**
     * Create a BubbleClockController instance.
     *
     * @param layoutInflater Inflater used to inflate custom clock views.
     */
    public BubbleClockController(Resources res, LayoutInflater inflater) {
        mResources = res;
        mLayoutInflater = inflater;
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.bubble_clock, null);
        mDigitalClock = (TextClock) mView.findViewById(R.id.digital_clock);
        mAnalogClock = (ImageClock) mView.findViewById(R.id.analog_clock);

        mLockClockContainer = mLayoutInflater.inflate(R.layout.digital_clock, null);
        mLockClock = (TextClock) mLockClockContainer.findViewById(R.id.lock_screen_clock);
        mLockClock.setVisibility(View.GONE);

        mDarkController = new CrossFadeDarkController(mDigitalClock, mLockClock);
    }

    @Override
    public String getName() {
        return "bubble";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_bubble);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.bubble_thumbnail);
    }

    @Override
    public View getView() {
        if (mLockClockContainer == null) {
            createViews();
        }
        return mLockClockContainer;
    }

    @Override
    public View getBigClockView() {
        if (mView == null) {
            createViews();
        }
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
    public void setDarkAmount(float darkAmount) {
        mDarkController.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeTick() {
        mAnalogClock.onTimeChanged();
        mDigitalClock.refresh();
        mLockClock.refresh();
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
