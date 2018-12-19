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
import androidx.annotation.StringRes;

import java.util.Comparator;

/**
 * A class responsible for saving bar view information.
 */
public class BarViewInfo implements Comparable<BarViewInfo> {

    private final Drawable mIcon;
    private View.OnClickListener mListener;
    private @StringRes int mSummaryRes;
    // A number indicates this bar's height. The larger number shows a higher bar view.
    private int mBarNumber;
    // A real height of bar view.
    private int mBarHeight;

    /**
     * Construct a BarViewInfo instance.
     *
     * @param icon the icon of bar view.
     * @param barNumber the number of bar view. The larger number show a more height of bar view.
     * @param summaryRes the resource identifier of the string resource to be displayed
     * @return BarViewInfo object.
     */
    public BarViewInfo(Drawable icon, @IntRange(from = 0) int barNumber,
            @StringRes int summaryRes) {
        mIcon = icon;
        mBarNumber = barNumber;
        mSummaryRes = summaryRes;
    }

    /**
     * Set number for bar view.
     *
     * @param barNumber the number of bar view. The larger number shows a higher bar view.
     */
    public void setBarNumber(@IntRange(from = 0) int barNumber) {
        mBarNumber = barNumber;
    }

    /**
     * Set summary resource for bar view
     *
     * @param resId the resource identifier of the string resource to be displayed
     */
    public void setSummary(@StringRes int resId) {
        mSummaryRes = resId;
    }

    /**
     * Set a click listner for bar view.
     *
     * @param listener the click listner is attached on bar view.
     */
    public void setClickListener(@Nullable View.OnClickListener listener) {
        mListener = listener;
    }

    /**
     * Get the icon of bar view.
     *
     * @return Drawable the icon of bar view.
     */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Get the OnClickListener of bar view.
     *
     * @return View.OnClickListener the click listner of bar view.
     */
    public View.OnClickListener getListener() {
        return mListener;
    }

    /**
     * Get the real height of bar view.
     *
     * @return the real height of bar view.
     */
    public int getBarHeight() {
        return mBarHeight;
    }

    /**
     * Get summary resource of bar view.
     *
     * @return summary resource of bar view.
     */
    public int getSummaryRes() {
        return mSummaryRes;
    }

    /**
     * Get the number of app uses this permisssion.
     *
     * @return the number of app uses this permission.
     */
    public int getBarNumber() {
        return mBarNumber;
    }

    @Override
    public int compareTo(BarViewInfo other) {
        // Descending order
        return Comparator.comparingInt((BarViewInfo barViewInfo) -> barViewInfo.mBarNumber)
                .compare(other, this);
    }

    /**
     * Set a real height for bar view.
     *
     * <p>This method should not be called by outside. It usually should be called by
     * {@link BarChartPreference#caculateAllBarViewsHeight}
     *
     * @param barHeight the real bar height for bar view.
     */
    void setBarHeight(@IntRange(from = 0) int barHeight) {
        mBarHeight = barHeight;
    }
}
