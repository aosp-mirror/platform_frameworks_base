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

import com.android.keyguard.R;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Plugin for a custom Typographic clock face that displays the time in words.
 */
public class TypeClockController implements ClockPlugin {

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
    private TypographicClock mTypeClock;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private TypographicClock mLockClock;

    /**
     * Controller for transition into dark state.
     */
    private CrossFadeDarkController mDarkController;

    /**
     * Create a TypeClockController instance.
     *
     * @param inflater Inflater used to inflate custom clock views.
     */
    TypeClockController(Resources res, LayoutInflater inflater) {
        mResources = res;
        mLayoutInflater = inflater;
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.type_aod_clock, null);
        mTypeClock = mView.findViewById(R.id.type_clock);

        // For now, this view is used to hide the default digital clock.
        // Need better transition to lock screen.
        mLockClock = (TypographicClock) mLayoutInflater.inflate(R.layout.typographic_clock, null);
        mLockClock.setVisibility(View.GONE);

        mDarkController = new CrossFadeDarkController(mView, mLockClock);
    }

    @Override
    public String getName() {
        return "type";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_type);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.type_thumbnail);
    }

    @Override
    public View getView() {
        if (mLockClock == null) {
            createViews();
        }
        return mLockClock;
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
        mTypeClock.setTextColor(color);
        mLockClock.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        if (colorPalette == null || colorPalette.length == 0) {
            return;
        }
        final int color = colorPalette[Math.max(0, colorPalette.length - 5)];
        mTypeClock.setClockColor(color);
        mLockClock.setClockColor(color);
    }

    @Override
    public void onTimeTick() {
        mTypeClock.onTimeChanged();
        mLockClock.onTimeChanged();
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        if (mDarkController != null) {
            mDarkController.setDarkAmount(darkAmount);
        }
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTypeClock.onTimeZoneChanged(timeZone);
        mLockClock.onTimeZoneChanged(timeZone);
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
