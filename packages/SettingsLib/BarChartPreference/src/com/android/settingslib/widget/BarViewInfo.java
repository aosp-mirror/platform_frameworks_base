/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;

import java.util.Comparator;

/**
 * A class responsible for saving bar view information.
 */
public class BarViewInfo implements Comparable<BarViewInfo> {

    private final Drawable mIcon;
    private View.OnClickListener mClickListener;
    private CharSequence mTitle;
    private CharSequence mSummary;
    private @Nullable CharSequence mContentDescription;
    // A number indicates this bar's height. The larger number shows a higher bar view.
    private int mHeight;
    // A real height of bar view.
    private int mNormalizedHeight;

    /**
     * Construct a BarViewInfo instance.
     *
     * @param icon      The icon of bar view.
     * @param barHeight The height of bar view. Larger number shows a higher bar view.
     * @param title     The string for title.  If this is null, use the height of the bar.
     * @param summary   The string for summary.
     * @param contentDescription Optional text that briefly describes the contents of the icon.
     */
    public BarViewInfo(Drawable icon, @IntRange(from = 0) int barHeight,
            @Nullable CharSequence title, CharSequence summary,
            @Nullable CharSequence contentDescription) {
        mIcon = icon;
        mHeight = barHeight;
        mTitle = title;
        mSummary = summary;
        mContentDescription = contentDescription;
    }

    /**
     * Set a click listener for bar view.
     */
    public void setClickListener(@Nullable View.OnClickListener listener) {
        mClickListener = listener;
    }

    @Override
    public int compareTo(BarViewInfo other) {
        // Descending order
        return Comparator.comparingInt((BarViewInfo barViewInfo) -> barViewInfo.mHeight)
                .compare(other, this);
    }

    void setHeight(@IntRange(from = 0) int height) {
        mHeight = height;
    }

    void setTitle(CharSequence title) {
        mTitle = title;
    }

    void setSummary(CharSequence summary) {
        mSummary = summary;
    }

    Drawable getIcon() {
        return mIcon;
    }

    int getHeight() {
        return mHeight;
    }

    View.OnClickListener getClickListener() {
        return mClickListener;
    }

    @Nullable
    CharSequence getTitle() {
        return mTitle;
    }

    CharSequence getSummary() {
        return mSummary;
    }

    public @Nullable CharSequence getContentDescription() {
        return mContentDescription;
    }

    void setNormalizedHeight(@IntRange(from = 0) int barHeight) {
        mNormalizedHeight = barHeight;
    }

    int getNormalizedHeight() {
        return mNormalizedHeight;
    }
}
