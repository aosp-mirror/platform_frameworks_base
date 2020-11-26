/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * Banner message is a banner displaying important information (permission request, page error etc),
 * and provide actions for user to address. It requires a user action to be dismissed.
 */
public class BannerMessagePreference extends Preference {

    private static final String TAG = "BannerPreference";
    private BannerMessagePreference.ButtonInfo mPositiveButtonInfo;
    private BannerMessagePreference.ButtonInfo mNegativeButtonInfo;

    public BannerMessagePreference(Context context) {
        super(context);
        init();
    }

    public BannerMessagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BannerMessagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public BannerMessagePreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(true);
        holder.setDividerAllowedBelow(true);

        mPositiveButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_positive_btn);
        mNegativeButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_negative_btn);

        mPositiveButtonInfo.setUpButton();
        mNegativeButtonInfo.setUpButton();

        final TextView titleView = (TextView) holder.findViewById(R.id.banner_title);
        final TextView summaryView = (TextView) holder.findViewById(R.id.banner_summary);

        titleView.setText(getTitle());
        summaryView.setText(getSummary());
    }

    private void init() {
        mPositiveButtonInfo = new BannerMessagePreference.ButtonInfo();
        mNegativeButtonInfo = new BannerMessagePreference.ButtonInfo();
        setSelectable(false);
        setLayoutResource(R.layout.banner_message);
    }

    /**
     * Set the visibility state of positive button.
     */
    public BannerMessagePreference setPositiveButtonVisible(boolean isVisible) {
        if (isVisible != mPositiveButtonInfo.mIsVisible) {
            mPositiveButtonInfo.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Set the visibility state of negative button.
     */
    public BannerMessagePreference setNegativeButtonVisible(boolean isVisible) {
        if (isVisible != mNegativeButtonInfo.mIsVisible) {
            mNegativeButtonInfo.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when positive button is clicked.
     */
    public BannerMessagePreference setPositiveButtonOnClickListener(
            View.OnClickListener listener) {
        if (listener != mPositiveButtonInfo.mListener) {
            mPositiveButtonInfo.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when negative button is clicked.
     */
    public BannerMessagePreference setNegativeButtonOnClickListener(
            View.OnClickListener listener) {
        if (listener != mNegativeButtonInfo.mListener) {
            mNegativeButtonInfo.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in positive button.
     */
    public BannerMessagePreference setPositiveButtonText(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mPositiveButtonInfo.mText)) {
            mPositiveButtonInfo.mText = newText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in negative button.
     */
    public BannerMessagePreference setNegativeButtonText(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mNegativeButtonInfo.mText)) {
            mNegativeButtonInfo.mText = newText;
            notifyChanged();
        }
        return this;
    }

    static class ButtonInfo {
        private Button mButton;
        private CharSequence mText;
        private View.OnClickListener mListener;
        private boolean mIsVisible = true;

        void setUpButton() {
            mButton.setText(mText);
            mButton.setOnClickListener(mListener);

            if (shouldBeVisible()) {
                mButton.setVisibility(View.VISIBLE);
            } else {
                mButton.setVisibility(View.GONE);
            }
        }

        /**
         * By default, two buttons are visible.
         * If user didn't set a text for a button, then it should not be shown.
         */
        private boolean shouldBeVisible() {
            return mIsVisible && (!TextUtils.isEmpty(mText));
        }
    }
}
