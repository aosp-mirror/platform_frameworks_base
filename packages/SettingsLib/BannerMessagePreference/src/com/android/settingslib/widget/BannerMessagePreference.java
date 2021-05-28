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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.os.BuildCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * Banner message is a banner displaying important information (permission request, page error etc),
 * and provide actions for user to address. It requires a user action to be dismissed.
 */
public class BannerMessagePreference extends Preference {

    public enum AttentionLevel {
        HIGH(0, R.color.banner_background_attention_high, R.color.banner_accent_attention_high),
        MEDIUM(1,
               R.color.banner_background_attention_medium,
               R.color.banner_accent_attention_medium),
        LOW(2, R.color.banner_background_attention_low, R.color.banner_accent_attention_low);

        // Corresponds to the enum valye of R.attr.attentionLevel
        private final int mAttrValue;
        @ColorRes private final int mBackgroundColorResId;
        @ColorRes private final int mAccentColorResId;

        AttentionLevel(int attrValue, @ColorRes int backgroundColorResId,
                @ColorRes int accentColorResId) {
            mAttrValue = attrValue;
            mBackgroundColorResId = backgroundColorResId;
            mAccentColorResId = accentColorResId;
        }

        static AttentionLevel fromAttr(int attrValue) {
            for (AttentionLevel level : values()) {
                if (level.mAttrValue == attrValue) {
                    return level;
                }
            }
            throw new IllegalArgumentException();
        }

        public @ColorRes int getAccentColorResId() {
            return mAccentColorResId;
        }

        public @ColorRes int getBackgroundColorResId() {
            return mBackgroundColorResId;
        }
    }

    private static final String TAG = "BannerPreference";
    private static final boolean IS_AT_LEAST_S = BuildCompat.isAtLeastS();

    private final BannerMessagePreference.ButtonInfo mPositiveButtonInfo =
            new BannerMessagePreference.ButtonInfo();
    private final BannerMessagePreference.ButtonInfo mNegativeButtonInfo =
            new BannerMessagePreference.ButtonInfo();
    private final BannerMessagePreference.DismissButtonInfo mDismissButtonInfo =
            new BannerMessagePreference.DismissButtonInfo();

    // Default attention level is High.
    private AttentionLevel mAttentionLevel = AttentionLevel.HIGH;
    private String mSubtitle;

    public BannerMessagePreference(Context context) {
        super(context);
        init(context, null /* attrs */);
    }

    public BannerMessagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public BannerMessagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public BannerMessagePreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setSelectable(false);
        setLayoutResource(R.layout.settingslib_banner_message);

        if (IS_AT_LEAST_S) {
            if (attrs != null) {
                // Get attention level and subtitle from layout XML
                TypedArray a =
                        context.obtainStyledAttributes(attrs, R.styleable.BannerMessagePreference);
                int mAttentionLevelValue =
                        a.getInt(R.styleable.BannerMessagePreference_attentionLevel, 0);
                mAttentionLevel = AttentionLevel.fromAttr(mAttentionLevelValue);
                mSubtitle = a.getString(R.styleable.BannerMessagePreference_subtitle);
                a.recycle();
            }
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context context = getContext();

        final TextView titleView = (TextView) holder.findViewById(R.id.banner_title);
        CharSequence title = getTitle();
        titleView.setText(title);
        titleView.setVisibility(title == null ? View.GONE : View.VISIBLE);

        final TextView summaryView = (TextView) holder.findViewById(R.id.banner_summary);
        summaryView.setText(getSummary());

        mPositiveButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_positive_btn);
        mNegativeButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_negative_btn);

        if (IS_AT_LEAST_S) {
            final Resources.Theme theme = context.getTheme();
            @ColorInt final int accentColor =
                    context.getResources().getColor(mAttentionLevel.getAccentColorResId(), theme);
            @ColorInt final int backgroundColor =
                    context.getResources().getColor(
                            mAttentionLevel.getBackgroundColorResId(), theme);

            holder.setDividerAllowedAbove(false);
            holder.setDividerAllowedBelow(false);
            holder.itemView.getBackground().setTint(backgroundColor);

            mPositiveButtonInfo.mColor = accentColor;
            mNegativeButtonInfo.mColor = accentColor;

            mDismissButtonInfo.mButton = (ImageButton) holder.findViewById(R.id.banner_dismiss_btn);
            mDismissButtonInfo.setUpButton();

            final TextView subtitleView = (TextView) holder.findViewById(R.id.banner_subtitle);
            subtitleView.setText(mSubtitle);
            subtitleView.setVisibility(mSubtitle == null ? View.GONE : View.VISIBLE);

            final ImageView iconView = (ImageView) holder.findViewById(R.id.banner_icon);
            if (iconView != null) {
                Drawable icon = getIcon();
                iconView.setImageDrawable(
                        icon == null
                                ? getContext().getDrawable(R.drawable.ic_warning)
                                : icon);
                iconView.setColorFilter(
                        new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN));
            }
        } else {
            holder.setDividerAllowedAbove(true);
            holder.setDividerAllowedBelow(true);
        }

        mPositiveButtonInfo.setUpButton();
        mNegativeButtonInfo.setUpButton();
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
     * Set the visibility state of dismiss button.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public BannerMessagePreference setDismissButtonVisible(boolean isVisible) {
        if (isVisible != mDismissButtonInfo.mIsVisible) {
            mDismissButtonInfo.mIsVisible = isVisible;
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
     * Register a callback to be invoked when the dismiss button is clicked.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public BannerMessagePreference setDismissButtonOnClickListener(
            View.OnClickListener listener) {
        if (listener != mDismissButtonInfo.mListener) {
            mDismissButtonInfo.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in positive button.
     */
    public BannerMessagePreference setPositiveButtonText(@StringRes int textResId) {
        return setPositiveButtonText(getContext().getString(textResId));
    }

    /**
     * Sets the text to be displayed in positive button.
     */
    public BannerMessagePreference setPositiveButtonText(String positiveButtonText) {
        if (!TextUtils.equals(positiveButtonText, mPositiveButtonInfo.mText)) {
            mPositiveButtonInfo.mText = positiveButtonText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in negative button.
     */
    public BannerMessagePreference setNegativeButtonText(@StringRes int textResId) {
        return setNegativeButtonText(getContext().getString(textResId));
    }

    /**
     * Sets the text to be displayed in negative button.
     */
    public BannerMessagePreference setNegativeButtonText(String negativeButtonText) {
        if (!TextUtils.equals(negativeButtonText, mNegativeButtonInfo.mText)) {
            mNegativeButtonInfo.mText = negativeButtonText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the subtitle.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public BannerMessagePreference setSubtitle(@StringRes int textResId) {
        return setSubtitle(getContext().getString(textResId));
    }

    /**
     * Sets the subtitle.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public BannerMessagePreference setSubtitle(String subtitle) {
        if (!TextUtils.equals(subtitle, mSubtitle)) {
            mSubtitle = subtitle;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the attention level. This will update the color theme of the preference.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public BannerMessagePreference setAttentionLevel(AttentionLevel attentionLevel) {
        if (attentionLevel == mAttentionLevel) {
            return this;
        }

        if (attentionLevel != null) {
            mAttentionLevel = attentionLevel;
            notifyChanged();
        }
        return this;
    }

    static class ButtonInfo {
        private Button mButton;
        private CharSequence mText;
        private View.OnClickListener mListener;
        private boolean mIsVisible = true;
        @ColorInt private int mColor;

        void setUpButton() {
            mButton.setText(mText);
            mButton.setOnClickListener(mListener);

            if (IS_AT_LEAST_S) {
                mButton.setTextColor(mColor);
            }

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

    static class DismissButtonInfo {
        private ImageButton mButton;
        private View.OnClickListener mListener;
        private boolean mIsVisible = true;

        void setUpButton() {
            mButton.setOnClickListener(mListener);
            if (shouldBeVisible()) {
                mButton.setVisibility(View.VISIBLE);
            } else {
                mButton.setVisibility(View.GONE);
            }
        }

        /**
         * By default, dismiss button is visible if it has a click listener.
         */
        private boolean shouldBeVisible() {
            return mIsVisible && (mListener != null);
        }
    }
}
