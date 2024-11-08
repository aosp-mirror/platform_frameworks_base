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
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.footer.R;

import java.net.URISyntaxException;

/**
 * A custom preference acting as "footer" of a page. It has a field for icon and text. It is added
 * to screen as the last preference.
 */
public class FooterPreference extends Preference implements GroupSectionDividerMixin {
    private static final String TAG = "FooterPreference";

    public static final String KEY_FOOTER = "footer_preference";
    private static final String INTENT_URL_PREFIX = "intent:";
    static final int ORDER_FOOTER = Integer.MAX_VALUE - 1;
    @VisibleForTesting View.OnClickListener mLearnMoreListener;
    @VisibleForTesting int mIconVisibility = View.VISIBLE;
    private CharSequence mContentDescription;
    private CharSequence mLearnMoreText;
    private FooterLearnMoreSpan mLearnMoreSpan;

    public FooterPreference(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.settingslib.widget.theme.R.attr.footerPreferenceStyle);
        init();
    }

    public FooterPreference(Context context) {
        this(context, null);
    }

    private void linkifyTitle(TextView title) {
        final CharSequence text = getTitle();
        if (!(text instanceof Spanned)) {
            return;
        }
        final ClickableSpan[] spans =
                ((Spanned) text).getSpans(0, text.length(), ClickableSpan.class);
        if (spans.length == 0) {
            return;
        }
        SpannableString spannable = new SpannableString(text);
        for (ClickableSpan clickable : spans) {
            if (!(clickable instanceof URLSpan)) {
                continue;
            }
            final URLSpan urlSpan = (URLSpan) clickable;
            final String url = urlSpan.getURL();
            if (url == null || !url.startsWith(INTENT_URL_PREFIX)) {
                continue;
            }
            final int start = spannable.getSpanStart(urlSpan);
            final int end = spannable.getSpanEnd(urlSpan);
            spannable.removeSpan(urlSpan);
            try {
                final Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                final ClickableSpan clickableSpan =
                        new ClickableSpan() {
                            @Override
                            public void onClick(@NonNull View textView) {
                                // May throw ActivityNotFoundException. Just let it propagate.
                                getContext().startActivity(intent);
                            }
                        };
                spannable.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (URISyntaxException e) {
                Log.e(TAG, "Invalid URI " + url, e);
            }
        }
        title.setText(spannable);
        title.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = holder.itemView.findViewById(android.R.id.title);
        if (title != null) {
            if (!TextUtils.isEmpty(mContentDescription)) {
                title.setContentDescription(mContentDescription);
            }
            linkifyTitle(title);
        }

        TextView learnMore = holder.itemView.findViewById(R.id.settingslib_learn_more);
        if (learnMore != null) {
            if (mLearnMoreListener != null) {
                learnMore.setVisibility(View.VISIBLE);
                if (TextUtils.isEmpty(mLearnMoreText)) {
                    mLearnMoreText = learnMore.getText();
                } else {
                    learnMore.setText(mLearnMoreText);
                }
                SpannableString learnMoreText = new SpannableString(mLearnMoreText);
                if (mLearnMoreSpan != null) {
                    learnMoreText.removeSpan(mLearnMoreSpan);
                }
                mLearnMoreSpan = new FooterLearnMoreSpan(mLearnMoreListener);
                learnMoreText.setSpan(mLearnMoreSpan, 0, learnMoreText.length(), 0);
                learnMore.setText(learnMoreText);
            } else {
                learnMore.setVisibility(View.GONE);
            }
        }

        View icon = holder.itemView.findViewById(R.id.icon_frame);
        if (icon != null) {
            icon.setVisibility(mIconVisibility);
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

    /** Return the content description of footer preference. */
    @VisibleForTesting
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the learn more text.
     *
     * @param learnMoreText The string of the learn more text.
     */
    public void setLearnMoreText(CharSequence learnMoreText) {
        if (!TextUtils.equals(mLearnMoreText, learnMoreText)) {
            mLearnMoreText = learnMoreText;
            notifyChanged();
        }
    }

    /** Assign an action for the learn more link. */
    public void setLearnMoreAction(View.OnClickListener listener) {
        if (mLearnMoreListener != listener) {
            mLearnMoreListener = listener;
            notifyChanged();
        }
    }

    /** Set visibility of footer icon. */
    public void setIconVisibility(int iconVisibility) {
        if (mIconVisibility == iconVisibility) {
            return;
        }
        mIconVisibility = iconVisibility;
        notifyChanged();
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
        setSelectable(false);
    }

    /** The builder is convenient to creat a dynamic FooterPreference. */
    public static class Builder {
        private Context mContext;
        private String mKey;
        private CharSequence mTitle;
        private CharSequence mContentDescription;
        private CharSequence mLearnMoreText;

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
         * To set learn more string of the learn more text. This can use for talkback environment if
         * developer wants to have a customization content.
         *
         * @param learnMoreText The resource id of the learn more string.
         */
        public Builder setLearnMoreText(CharSequence learnMoreText) {
            mLearnMoreText = learnMoreText;
            return this;
        }

        /**
         * To set learn more string of the {@link FooterPreference}. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param learnMoreTextResId The resource id of the learn more string.
         */
        public Builder setLearnMoreText(@StringRes int learnMoreTextResId) {
            mLearnMoreText = mContext.getText(learnMoreTextResId);
            return this;
        }

        /** To generate the {@link FooterPreference}. */
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

            if (!TextUtils.isEmpty(mLearnMoreText)) {
                footerPreference.setLearnMoreText(mLearnMoreText);
            }
            return footerPreference;
        }
    }

    /** A {@link URLSpan} that opens a support page when clicked */
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
