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
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * A custom preference acting as "footer" of a page. It has a field for icon and text. It is added
 * to screen as the last preference.
 */
public class FooterPreference extends Preference {

    public static final String KEY_FOOTER = "footer_preference";
    static final int ORDER_FOOTER = Integer.MAX_VALUE - 1;
    @VisibleForTesting
    View.OnClickListener mLearnMoreListener;
    private CharSequence mContentDescription;
    private CharSequence mLearnMoreContentDescription;

    public FooterPreference(Context context, AttributeSet attrs) {
        super(context, attrs, R.attr.footerPreferenceStyle);
        init();
    }

    public FooterPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = holder.itemView.findViewById(android.R.id.title);
        title.setMovementMethod(new LinkMovementMethod());
        title.setClickable(false);
        title.setLongClickable(false);
        if (!TextUtils.isEmpty(mContentDescription)) {
            title.setContentDescription(mContentDescription);
        }

        TextView learnMore = holder.itemView.findViewById(R.id.settingslib_learn_more);
        if (learnMore != null && mLearnMoreListener != null) {
            learnMore.setVisibility(View.VISIBLE);
            SpannableString learnMoreText = new SpannableString(learnMore.getText());
            learnMoreText.setSpan(new FooterLearnMoreSpan(mLearnMoreListener), 0,
                    learnMoreText.length(), 0);
            learnMore.setText(learnMoreText);
            if (!TextUtils.isEmpty(mLearnMoreContentDescription)) {
                learnMore.setContentDescription(mLearnMoreContentDescription);
            }
        } else {
            learnMore.setVisibility(View.GONE);
        }
    }

    @Override
    public void setSummary(CharSequence summary) {
        setTitle(summary);
    }

    @Override
    public void setSummary(int summaryResId) {
        setTitle(summaryResId);
    }

    @Override
    public CharSequence getSummary() {
        return getTitle();
    }

    /**
     * To set content description of the {@link FooterPreference}. This can use for talkback
     * environment if developer wants to have a customization content.
     *
     * @param contentDescription The resource id of the content description.
     */
    public void setContentDescription(CharSequence contentDescription) {
        if (!TextUtils.equals(mContentDescription, contentDescription)) {
            mContentDescription = contentDescription;
            notifyChanged();
        }
    }

    /**
     * Return the content description of footer preference.
     */
    @VisibleForTesting
    CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * To set content description of the learn more text. This can use for talkback
     * environment if developer wants to have a customization content.
     *
     * @param learnMoreContentDescription The resource id of the content description.
     */
    public void setLearnMoreContentDescription(CharSequence learnMoreContentDescription) {
        if (!TextUtils.equals(mContentDescription, learnMoreContentDescription)) {
            mLearnMoreContentDescription = learnMoreContentDescription;
            notifyChanged();
        }
    }

    /**
     * Return the content description of learn more link.
     */
    @VisibleForTesting
    CharSequence getLearnMoreContentDescription() {
        return mLearnMoreContentDescription;
    }

    /**
     * Assign an action for the learn more link.
     */
    public void setLearnMoreAction(View.OnClickListener listener) {
        if (mLearnMoreListener != listener) {
            mLearnMoreListener = listener;
            notifyChanged();
        }
    }

    private void init() {
        setLayoutResource(R.layout.preference_footer);
        if (getIcon() == null) {
            setIcon(R.drawable.settingslib_ic_info_outline_24);
        }
        setOrder(ORDER_FOOTER);
        if (TextUtils.isEmpty(getKey())) {
            setKey(KEY_FOOTER);
        }
    }

    /**
     * The builder is convenient to creat a dynamic FooterPreference.
     */
    public static class Builder {
        private Context mContext;
        private String mKey;
        private CharSequence mTitle;
        private CharSequence mContentDescription;
        private CharSequence mLearnMoreContentDescription;

        public Builder(@NonNull Context context) {
            mContext = context;
        }

        /**
         * To set the key value of the {@link FooterPreference}.
         *
         * @param key The key value.
         */
        public Builder setKey(@NonNull String key) {
            mKey = key;
            return this;
        }

        /**
         * To set the title of the {@link FooterPreference}.
         *
         * @param title The title.
         */
        public Builder setTitle(CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * To set the title of the {@link FooterPreference}.
         *
         * @param titleResId The resource id of the title.
         */
        public Builder setTitle(@StringRes int titleResId) {
            mTitle = mContext.getText(titleResId);
            return this;
        }

        /**
         * To set content description of the {@link FooterPreference}. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param contentDescription The resource id of the content description.
         */
        public Builder setContentDescription(CharSequence contentDescription) {
            mContentDescription = contentDescription;
            return this;
        }

        /**
         * To set content description of the {@link FooterPreference}. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param contentDescriptionResId The resource id of the content description.
         */
        public Builder setContentDescription(@StringRes int contentDescriptionResId) {
            mContentDescription = mContext.getText(contentDescriptionResId);
            return this;
        }

        /**
         * To set content description of the learn more text. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param learnMoreContentDescription The resource id of the content description.
         */
        public Builder setLearnMoreContentDescription(CharSequence learnMoreContentDescription) {
            mLearnMoreContentDescription = learnMoreContentDescription;
            return this;
        }

        /**
         * To set content description of the {@link FooterPreference}. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param learnMoreContentDescriptionResId The resource id of the content description.
         */
        public Builder setLearnMoreContentDescription(
                @StringRes int learnMoreContentDescriptionResId) {
            mLearnMoreContentDescription = mContext.getText(learnMoreContentDescriptionResId);
            return this;
        }


        /**
         * To generate the {@link FooterPreference}.
         */
        public FooterPreference build() {
            final FooterPreference footerPreference = new FooterPreference(mContext);
            footerPreference.setSelectable(false);
            if (TextUtils.isEmpty(mTitle)) {
                throw new IllegalArgumentException("Footer title cannot be empty!");
            }
            footerPreference.setTitle(mTitle);
            if (!TextUtils.isEmpty(mKey)) {
                footerPreference.setKey(mKey);
            }

            if (!TextUtils.isEmpty(mContentDescription)) {
                footerPreference.setContentDescription(mContentDescription);
            }

            if (!TextUtils.isEmpty(mLearnMoreContentDescription)) {
                footerPreference.setLearnMoreContentDescription(mLearnMoreContentDescription);
            }
            return footerPreference;
        }
    }

    /**
     * A {@link URLSpan} that opens a support page when clicked
     */
    static class FooterLearnMoreSpan extends URLSpan {

        private final View.OnClickListener mClickListener;

        FooterLearnMoreSpan(View.OnClickListener clickListener) {
            // sets the url to empty string so we can prevent any other span processing from
            // clearing things we need in this string.
            super("");
            mClickListener = clickListener;
        }

        @Override
        public void onClick(View widget) {
            if (mClickListener != null) {
                mClickListener.onClick(widget);
            }
        }
    }
}
