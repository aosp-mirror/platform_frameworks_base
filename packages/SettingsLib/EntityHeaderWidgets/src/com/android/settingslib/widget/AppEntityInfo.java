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

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * AppEntityInfo is responsible for storing app information shown in {@link R.xml.app_view.xml}.
 */
public class AppEntityInfo {

    private final Drawable mIcon;
    private final CharSequence mTitle;
    private final CharSequence mSummary;
    private final View.OnClickListener mClickListener;

    /**
     * Gets the drawable for the icon of app entity.
     *
     * @return the drawable for the icon of app entity.
     */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Gets the text for the title of app enitity.
     *
     * @return the text for the title of app enitity.
     */
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Gets the text for the summary of app enitity.
     *
     * @return the text for the summary of app enitity.
     */
    public CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Gets the click listener for the app entity view.
     *
     * @return click listener for the app entity view.
     */
    public View.OnClickListener getClickListener() {
        return mClickListener;
    }

    private AppEntityInfo(Builder builder) {
        mIcon = builder.mIcon;
        mTitle = builder.mTitle;
        mSummary = builder.mSummary;
        mClickListener = builder.mClickListener;
    }

    /**
     * Builder class for {@link AppEntityInfo}
     */
    public static class Builder {

        private Drawable mIcon;
        private CharSequence mTitle;
        private CharSequence mSummary;
        private View.OnClickListener mClickListener;

        /**
         * Creates an instance of a {@link AppEntityInfo} based on the current builder settings.
         *
         * @return The {@link AppEntityInfo}.
         */
        public AppEntityInfo build() {
            return new AppEntityInfo(this);
        }

        /**
         * Sets the drawable for the icon.
         */
        public Builder setIcon(@NonNull Drawable icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the text for the title.
         */
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets the text for the summary.
         */
        public Builder setSummary(@Nullable CharSequence summary) {
            mSummary = summary;
            return this;
        }

        /**
         * Sets the click listener for app entity view.
         */
        public Builder setOnClickListener(@Nullable View.OnClickListener clickListener) {
            mClickListener = clickListener;
            return this;
        }
    }
}
