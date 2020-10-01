/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.StringRes;
import android.content.Context;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.R;

/**
 * A custom preference acting as "footer" of a page. It has a field for icon and text. It is added
 * to screen as the last preference.
 */
public class FooterPreference extends Preference {

    static final int ORDER_FOOTER = Integer.MAX_VALUE - 1;
    public static final String KEY_FOOTER = "footer_preference";

    public FooterPreference(Context context, AttributeSet attrs) {
        super(context, attrs, TypedArrayUtils.getAttr(
                context, R.attr.footerPreferenceStyle, android.R.attr.preferenceStyle));
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

    private void init() {
        if (getIcon() == null) {
            setIcon(R.drawable.ic_info_outline_24);
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

        public Builder(@NonNull Context context) {
            mContext = context;
        }

        /**
         * To set the key value of the {@link FooterPreference}.
         * @param key The key value.
         */
        public Builder setKey(@NonNull String key) {
            mKey = key;
            return this;
        }

        /**
         * To set the title of the {@link FooterPreference}.
         * @param title The title.
         */
        public Builder setTitle(CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * To set the title of the {@link FooterPreference}.
         * @param titleResId The resource id of the title.
         */
        public Builder setTitle(@StringRes int titleResId) {
            mTitle = mContext.getText(titleResId);
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
            return footerPreference;
        }
    }
}
