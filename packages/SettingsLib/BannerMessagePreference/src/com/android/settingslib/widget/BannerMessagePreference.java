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
import android.content.res.ColorStateList;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.banner.R;

import com.google.android.material.button.MaterialButton;
/**
 * Banner message is a banner displaying important information (permission request, page error etc),
 * and provide actions for user to address. It requires a user action to be dismissed.
 */
public class BannerMessagePreference extends Preference implements GroupSectionDividerMixin {

    public enum AttentionLevel {
        HIGH(0,
                R.color.banner_background_attention_high,
                R.color.banner_accent_attention_high,
                R.color.settingslib_banner_button_background_high),
        MEDIUM(1,
                R.color.banner_background_attention_medium,
                R.color.banner_accent_attention_medium,
                R.color.settingslib_banner_button_background_medium),
        LOW(2,
                R.color.banner_background_attention_low,
                R.color.banner_accent_attention_low,
                R.color.settingslib_banner_button_background_low),
        NORMAL(3,
                R.color.banner_background_attention_normal,
                R.color.banner_accent_attention_normal,
                R.color.settingslib_banner_button_background_normal);

        // Corresponds to the enum valye of R.attr.attentionLevel
        private final int mAttrValue;
        @ColorRes private final int mBackgroundColorResId;
        @ColorRes private final int mAccentColorResId;
        @ColorRes private final int mButtonBackgroundColorResId;

        AttentionLevel(int attrValue, @ColorRes int backgroundColorResId,
                @ColorRes int accentColorResId,
                @ColorRes int buttonBackgroundColorResId) {
            mAttrValue = attrValue;
            mBackgroundColorResId = backgroundColorResId;
            mAccentColorResId = accentColorResId;
            mButtonBackgroundColorResId = buttonBackgroundColorResId;
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

        public @ColorRes int getButtonBackgroundColorResId() {
            return mButtonBackgroundColorResId;
        }
    }

    private static final String TAG = "BannerPreference";
    private static final boolean IS_AT_LEAST_S = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

    private final BannerMessagePreference.ButtonInfo mPositiveButtonInfo =
            new BannerMessagePreference.ButtonInfo();
    private final BannerMessagePreference.ButtonInfo mNegativeButtonInfo =
            new BannerMessagePreference.ButtonInfo();
    private final BannerMessagePreference.DismissButtonInfo mDismissButtonInfo =
            new BannerMessagePreference.DismissButtonInfo();

    // Default attention level is High.
    private AttentionLevel mAttentionLevel = AttentionLevel.HIGH;
    private CharSequence mSubtitle;
    private CharSequence mHeader;
    private int mButtonOrientation;

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
        int resId = SettingsThemeHelper.isExpressiveTheme(context)
                ? R.layout.settingslib_expressive_banner_message
                : R.layout.settingslib_banner_message;
        setLayoutResource(resId);

        if (IS_AT_LEAST_S) {
            if (attrs != null) {
                // Get attention level and subtitle from layout XML
                TypedArray a =
                        context.obtainStyledAttributes(attrs, R.styleable.BannerMessagePreference);
                int mAttentionLevelValue =
                        a.getInt(R.styleable.BannerMessagePreference_attentionLevel, 0);
                mAttentionLevel = AttentionLevel.fromAttr(mAttentionLevelValue);
                mSubtitle = a.getString(R.styleable.BannerMessagePreference_subtitle);
                mHeader = a.getString(R.styleable.BannerMessagePreference_bannerHeader);
                mButtonOrientation = a.getInt(R.styleable.BannerMessagePreference_buttonOrientation,
                        LinearLayout.HORIZONTAL);
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
        if (titleView != null) {
            titleView.setText(title);
            titleView.setVisibility(title == null ? View.GONE : View.VISIBLE);
        }

        final TextView summaryView = (TextView) holder.findViewById(R.id.banner_summary);
        if (summaryView != null) {
            summaryView.setText(getSummary());
            summaryView.setVisibility(TextUtils.isEmpty(getSummary()) ? View.GONE : View.VISIBLE);
        }

        mPositiveButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_positive_btn);
        mNegativeButtonInfo.mButton = (Button) holder.findViewById(R.id.banner_negative_btn);

        final Resources.Theme theme = context.getTheme();
        @ColorInt final int accentColor =
                context.getResources().getColor(mAttentionLevel.getAccentColorResId(), theme);

        final ImageView iconView = (ImageView) holder.findViewById(R.id.banner_icon);
        if (iconView != null) {
            Drawable icon = getIcon();
            iconView.setImageDrawable(
                    icon == null
                            ? getContext().getDrawable(R.drawable.ic_warning)
                            : icon);
            if (mAttentionLevel != AttentionLevel.NORMAL
                    && !SettingsThemeHelper.isExpressiveTheme(context)) {
                iconView.setColorFilter(
                        new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN));
            }
        }

        if (IS_AT_LEAST_S) {
            @ColorInt final int backgroundColor =
                    context.getResources().getColor(
                            mAttentionLevel.getBackgroundColorResId(), theme);

            @ColorInt final int btnBackgroundColor =
                    context.getResources().getColor(mAttentionLevel.getButtonBackgroundColorResId(),
                            theme);
            ColorStateList strokeColor = context.getResources().getColorStateList(
                    mAttentionLevel.getButtonBackgroundColorResId(), theme);

            holder.setDividerAllowedAbove(false);
            holder.setDividerAllowedBelow(false);
            View backgroundView = holder.findViewById(R.id.banner_background);
            if (backgroundView != null && !SettingsThemeHelper.isExpressiveTheme(context)) {
                backgroundView.getBackground().setTint(backgroundColor);
            }

            mPositiveButtonInfo.mColor = accentColor;
            mNegativeButtonInfo.mColor = accentColor;
            if (mAttentionLevel != AttentionLevel.NORMAL) {
                mPositiveButtonInfo.mBackgroundColor = btnBackgroundColor;
                mNegativeButtonInfo.mStrokeColor = strokeColor;
            }

            mDismissButtonInfo.mButton = (ImageButton) holder.findViewById(R.id.banner_dismiss_btn);
            mDismissButtonInfo.setUpButton();

            final TextView subtitleView = (TextView) holder.findViewById(R.id.banner_subtitle);
            subtitleView.setText(mSubtitle);
            subtitleView.setVisibility(mSubtitle == null ? View.GONE : View.VISIBLE);

            TextView headerView = (TextView) holder.findViewById(R.id.banner_header);
            if (headerView != null) {
                headerView.setText(mHeader);
                headerView.setVisibility(TextUtils.isEmpty(mHeader) ? View.GONE : View.VISIBLE);
            }


        } else {
            holder.setDividerAllowedAbove(true);
            holder.setDividerAllowedBelow(true);
        }

        mPositiveButtonInfo.setUpButton();
        mNegativeButtonInfo.setUpButton();
        View buttonFrame = holder.findViewById(R.id.banner_buttons_frame);
        if (buttonFrame != null) {
            buttonFrame.setVisibility(
                    mPositiveButtonInfo.shouldBeVisible() || mNegativeButtonInfo.shouldBeVisible()
                            ? View.VISIBLE : View.GONE);

            LinearLayout linearLayout = (LinearLayout) buttonFrame;
            if (mButtonOrientation != linearLayout.getOrientation()) {
                int childCount = linearLayout.getChildCount();
                //reverse the order of the buttons
                for (int i = childCount - 1; i >= 0; i--) {
                    View child = linearLayout.getChildAt(i);
                    linearLayout.removeViewAt(i);
                    linearLayout.addView(child);
                }
                linearLayout.setOrientation(mButtonOrientation);
            }
        }
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
    public BannerMessagePreference setPositiveButtonText(CharSequence positiveButtonText) {
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
    public BannerMessagePreference setNegativeButtonText(CharSequence negativeButtonText) {
        if (!TextUtils.equals(negativeButtonText, mNegativeButtonInfo.mText)) {
            mNegativeButtonInfo.mText = negativeButtonText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets button orientation.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public BannerMessagePreference setButtonOrientation(int orientation) {
        if (mButtonOrientation != orientation) {
            mButtonOrientation = orientation;
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
    public BannerMessagePreference setSubtitle(CharSequence subtitle) {
        if (!TextUtils.equals(subtitle, mSubtitle)) {
            mSubtitle = subtitle;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the header.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public BannerMessagePreference setHeader(@StringRes int textResId) {
        return setHeader(getContext().getString(textResId));
    }

    /**
     * Sets the header.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public BannerMessagePreference setHeader(CharSequence header) {
        if (!TextUtils.equals(header, mHeader)) {
            mHeader = header;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the attention level. This will update the color theme of the preference.
     */
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
        @ColorInt private int mBackgroundColor;
        private ColorStateList mStrokeColor;

        void setUpButton() {
            mButton.setText(mText);
            mButton.setOnClickListener(mListener);

            MaterialButton btn = null;
            if (mButton instanceof MaterialButton) {
                btn = (MaterialButton) mButton;
            }

            if (IS_AT_LEAST_S) {
                if (btn != null && SettingsThemeHelper.isExpressiveTheme(btn.getContext())) {
                    if (mBackgroundColor != 0) {
                        btn.setBackgroundColor(mBackgroundColor);
                    }
                    if (mStrokeColor != null) {
                        btn.setStrokeColor(mStrokeColor);
                    }
                } else {
                    mButton.setTextColor(mColor);
                }
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
