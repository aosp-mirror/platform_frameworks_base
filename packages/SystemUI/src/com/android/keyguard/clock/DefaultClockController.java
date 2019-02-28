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
import android.widget.TextView;

import com.android.keyguard.R;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class DefaultClockController implements ClockPlugin {

    /**
     * Root view of preview.
     */
    private View mView;
    /**
     * Text clock in preview view hierarchy.
     */
    private TextView mTextTime;
    private TextView mTextDate;

    private DefaultClockController() {}

    /**
     * Create a DefaultClockController instance.
     *
     * @param inflater Inflater used to inflate custom clock views.
     */
    public static DefaultClockController build(LayoutInflater inflater) {
        DefaultClockController controller = new DefaultClockController();
        controller.createViews(inflater);
        return controller;
    }

    private void createViews(LayoutInflater inflater) {
        mView = inflater.inflate(R.layout.default_clock_preview, null);
        mTextTime = mView.findViewById(R.id.time);
        mTextDate = mView.findViewById(R.id.date);
    }

    @Override
    public View getView() {
        return null;
    }

    @Override
    public View getBigClockView() {
        return mView;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        mTextTime.setTextColor(color);
        mTextDate.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {}

    @Override
    public void dozeTimeTick() {
    }

    @Override
    public void setDarkAmount(float darkAmount) {}

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {}

    @Override
    public boolean shouldShowStatusArea() {
        return true;
    }
}
