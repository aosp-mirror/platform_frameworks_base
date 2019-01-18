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

import com.android.keyguard.R;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Plugin for a custom Typographic clock face that displays the time in words.
 */
public class TypeClockController implements ClockPlugin {

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    private View mView;
    private TypographicClock mTypeClock;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private View mLockClockContainer;

    /**
     * Controller for transition into dark state.
     */
    private CrossFadeDarkController mDarkController;

    private TypeClockController() {}

    /**
     * Create a TypeClockController instance.
     *
     * @param inflater Inflater used to inflate custom clock views.
     */
    public static TypeClockController build(LayoutInflater inflater) {
        TypeClockController controller = new TypeClockController();
        controller.createViews(inflater);
        return controller;
    }

    private void createViews(LayoutInflater inflater) {
        mView = inflater.inflate(R.layout.type_clock, null);
        mTypeClock = mView.findViewById(R.id.type_clock);

        // For now, this view is used to hide the default digital clock.
        // Need better transition to lock screen.
        mLockClockContainer = inflater.inflate(R.layout.digital_clock, null);
        mLockClockContainer.setVisibility(View.GONE);
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
        mTypeClock.setTextColor(color);
    }

    @Override
    public void dozeTimeTick() {
        mTypeClock.onTimeChanged();
    }

    @Override
    public void setDarkAmount(float darkAmount) {}

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTypeClock.onTimeZoneChanged(timeZone);
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
