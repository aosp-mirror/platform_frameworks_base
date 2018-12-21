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

package com.android.settingslib.widget;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

/**
 * BarChartInfo is responsible for storing information about {@link BarChartPreference}.
 */
public class BarChartInfo {
    @StringRes
    private final int mTitle;
    @StringRes
    private final int mDetails;
    @StringRes
    private final int mEmptyText;
    private final View.OnClickListener mDetailsOnClickListener;

    private BarViewInfo[] mBarViewInfos;

    /**
     * Gets the resource id for the title shown in {@link BarChartPreference}.
     *
     * @return the string resource id for title.
     */
    public int getTitle() {
        return mTitle;
    }

    /**
     * Gets the resource id for the details shown in {@link BarChartPreference}.
     *
     * @return the string resource id for details.
     */
    public int getDetails() {
        return mDetails;
    }

    /**
     * Gets the resource id for the empty text shown in {@link BarChartPreference} when there is no
     * any bar view in {@link BarChartPreference}.
     *
     * @return the string resource id for empty text.
     */
    public int getEmptyText() {
        return mEmptyText;
    }

    /**
     * Gets the click listener for the details view.
     *
     * @return click listener for details view.
     */
    public View.OnClickListener getDetailsOnClickListener() {
        return mDetailsOnClickListener;
    }

    /**
     * Gets an array which contains up to four {@link BarViewInfo}
     *
     * @return an array holding the current all {@link BarViewInfo} state of the bar chart.
     */
    public BarViewInfo[] getBarViewInfos() {
        return mBarViewInfos;
    }

    void setBarViewInfos(BarViewInfo[] barViewInfos) {
        mBarViewInfos = barViewInfos;
    }

    private BarChartInfo(Builder builder) {
        mTitle = builder.mTitle;
        mDetails = builder.mDetails;
        mEmptyText = builder.mEmptyText;
        mDetailsOnClickListener = builder.mDetailsOnClickListener;

        if (builder.mBarViewInfos != null) {
            mBarViewInfos = builder.mBarViewInfos.stream().toArray(BarViewInfo[]::new);
        }
    }

    /**
     * Builder class for {@link BarChartInfo}
     */
    public static class Builder {
        @StringRes
        private int mTitle;
        @StringRes
        private int mDetails;
        @StringRes
        private int mEmptyText;
        private View.OnClickListener mDetailsOnClickListener;
        private List<BarViewInfo> mBarViewInfos;

        /**
         * Creates an instance of a {@link BarChartInfo} based on the current builder settings.
         *
         * @return The {@link BarChartInfo}.
         */
        public BarChartInfo build() {
            if (mTitle == 0) {
                throw new IllegalStateException("You must call Builder#setTitle() once.");
            }
            return new BarChartInfo(this);
        }

        /**
         * Sets the string resource id for the title.
         */
        public Builder setTitle(@StringRes int title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets the string resource id for the details.
         */
        public Builder setDetails(@StringRes int details) {
            mDetails = details;
            return this;
        }

        /**
         * Sets the string resource id for the empty text.
         */
        public Builder setEmptyText(@StringRes int emptyText) {
            mEmptyText = emptyText;
            return this;
        }

        /**
         * Sets the click listener for details view.
         */
        public Builder setDetailsOnClickListener(
                @Nullable View.OnClickListener clickListener) {
            mDetailsOnClickListener = clickListener;
            return this;
        }

        /**
         * Adds a {@link BarViewInfo} for {@link BarChartPreference}.
         * Maximum of 4 {@link BarViewInfo} can be added.
         */
        public Builder addBarViewInfo(@NonNull BarViewInfo barViewInfo) {
            if (mBarViewInfos == null) {
                mBarViewInfos = new ArrayList<>();
            }
            if (mBarViewInfos.size() >= BarChartPreference.MAXIMUM_BAR_VIEWS) {
                throw new IllegalStateException("We only support up to four bar views");
            }
            mBarViewInfos.add(barViewInfo);
            return this;
        }
    }
}
