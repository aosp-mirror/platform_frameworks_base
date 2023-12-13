/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_DYNAMIC_SUMMARY;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_DYNAMIC_TITLE;
import static com.android.settingslib.drawer.TileUtils.EXTRA_CATEGORY_KEY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_ORDER;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_BACKGROUND_ARGB;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_BACKGROUND_HINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_PENDING_INTENT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SWITCH_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE_URI;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * A controller that manages events for switch.
 */
public abstract class EntryController {

    private String mAuthority;

    /**
     * Returns the key for this switch.
     */
    public abstract String getKey();

    /**
     * Returns the {@link MetaData} for this switch.
     */
    protected abstract MetaData getMetaData();

    /**
     * Notify registered observers that title was updated and attempt to sync changes.
     */
    public void notifyTitleChanged(Context context) {
        if (this instanceof DynamicTitle) {
            notifyChanged(context, METHOD_GET_DYNAMIC_TITLE);
        }
    }

    /**
     * Notify registered observers that summary was updated and attempt to sync changes.
     */
    public void notifySummaryChanged(Context context) {
        if (this instanceof DynamicSummary) {
            notifyChanged(context, METHOD_GET_DYNAMIC_SUMMARY);
        }
    }

    void setAuthority(String authority) {
        mAuthority = authority;
    }

    Bundle getBundle() {
        final MetaData metaData = getMetaData();
        if (metaData == null) {
            throw new NullPointerException("Should not return null in getMetaData()");
        }

        final Bundle bundle = metaData.build();
        final String uriString = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(mAuthority)
                .build()
                .toString();
        bundle.putString(META_DATA_PREFERENCE_KEYHINT, getKey());
        if (this instanceof ProviderIcon) {
            bundle.putString(META_DATA_PREFERENCE_ICON_URI, uriString);
        }
        if (this instanceof DynamicTitle) {
            bundle.putString(META_DATA_PREFERENCE_TITLE_URI, uriString);
        }
        if (this instanceof DynamicSummary) {
            bundle.putString(META_DATA_PREFERENCE_SUMMARY_URI, uriString);
        }
        if (this instanceof ProviderSwitch) {
            bundle.putString(META_DATA_PREFERENCE_SWITCH_URI, uriString);
        }
        return bundle;
    }

    private void notifyChanged(Context context, String method) {
        final Uri uri = TileUtils.buildUri(mAuthority, method, getKey());
        context.getContentResolver().notifyChange(uri, null);
    }

    /**
     * Collects all meta data of the item.
     */
    protected static class MetaData {
        private String mCategory;
        private int mOrder;
        @DrawableRes
        private int mIcon;
        private int mIconBackgroundHint;
        private int mIconBackgroundArgb;
        private Boolean mIconTintable;
        @StringRes
        private int mTitleId;
        private String mTitle;
        @StringRes
        private int mSummaryId;
        private String mSummary;
        private PendingIntent mPendingIntent;

        /**
         * @param category the category of the switch. This value must be from {@link CategoryKey}.
         */
        public MetaData(@NonNull String category) {
            mCategory = category;
        }

        /**
         * Set the order of the item that should be displayed on screen. Bigger value items displays
         * closer on top.
         */
        public MetaData setOrder(int order) {
            mOrder = order;
            return this;
        }

        /** Set the icon that should be displayed for the item. */
        public MetaData setIcon(@DrawableRes int icon) {
            mIcon = icon;
            return this;
        }

        /** Set the icon background color. The value may or may not be used by Settings app. */
        public MetaData setIconBackgoundHint(int hint) {
            mIconBackgroundHint = hint;
            return this;
        }

        /** Set the icon background color as raw ARGB. */
        public MetaData setIconBackgoundArgb(int argb) {
            mIconBackgroundArgb = argb;
            return this;
        }

        /** Specify whether the icon is tintable. */
        public MetaData setIconTintable(boolean tintable) {
            mIconTintable = tintable;
            return this;
        }

        /** Set the title that should be displayed for the item. */
        public MetaData setTitle(@StringRes int id) {
            mTitleId = id;
            return this;
        }

        /** Set the title that should be displayed for the item. */
        public MetaData setTitle(String title) {
            mTitle = title;
            return this;
        }

        /** Set the summary text that should be displayed for the item. */
        public MetaData setSummary(@StringRes int id) {
            mSummaryId = id;
            return this;
        }

        /** Set the summary text that should be displayed for the item. */
        public MetaData setSummary(String summary) {
            mSummary = summary;
            return this;
        }

        public MetaData setPendingIntent(PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        protected Bundle build() {
            final Bundle bundle = new Bundle();
            bundle.putString(EXTRA_CATEGORY_KEY, mCategory);

            if (mOrder != 0) {
                bundle.putInt(META_DATA_KEY_ORDER, mOrder);
            }

            if (mIcon != 0) {
                bundle.putInt(META_DATA_PREFERENCE_ICON, mIcon);
            }
            if (mIconBackgroundHint != 0) {
                bundle.putInt(META_DATA_PREFERENCE_ICON_BACKGROUND_HINT, mIconBackgroundHint);
            }
            if (mIconBackgroundArgb != 0) {
                bundle.putInt(META_DATA_PREFERENCE_ICON_BACKGROUND_ARGB, mIconBackgroundArgb);
            }
            if (mIconTintable != null) {
                bundle.putBoolean(META_DATA_PREFERENCE_ICON_TINTABLE, mIconTintable);
            }

            if (mTitleId != 0) {
                bundle.putInt(META_DATA_PREFERENCE_TITLE, mTitleId);
            } else if (mTitle != null) {
                bundle.putString(META_DATA_PREFERENCE_TITLE, mTitle);
            }

            if (mSummaryId != 0) {
                bundle.putInt(META_DATA_PREFERENCE_SUMMARY, mSummaryId);
            } else if (mSummary != null) {
                bundle.putString(META_DATA_PREFERENCE_SUMMARY, mSummary);
            }

            if (mPendingIntent != null) {
                bundle.putParcelable(META_DATA_PREFERENCE_PENDING_INTENT, mPendingIntent);
            }

            return bundle;
        }
    }
}
