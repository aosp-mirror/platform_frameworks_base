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

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class DefaultClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    private final SysuiColorExtractor mColorExtractor;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Root view of preview.
     */
    private View mView;

    /**
     * Text clock in preview view hierarchy.
     */
    private TextView mTextTime;

    /**
     * Date showing below time in preview view hierarchy.
     */
    private TextView mTextDate;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public DefaultClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = mLayoutInflater.inflate(R.layout.default_clock_preview, null);
        mTextTime = mView.findViewById(R.id.time);
        mTextDate = mView.findViewById(R.id.date);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTextTime = null;
        mTextDate = null;
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_default);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.default_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        // Use the big clock view for the preview
        View view = getBigClockView();

        // Initialize state of plugin before generating preview.
        setDarkAmount(1f);
        setTextColor(Color.WHITE);
        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        onTimeTick();

        return mRenderer.createPreview(view, width, height);
    }

    @Override
    public View getView() {
        return null;
    }

    @Override
    public View getBigClockView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return totalHeight / 2;
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
    public void onTimeTick() {
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
