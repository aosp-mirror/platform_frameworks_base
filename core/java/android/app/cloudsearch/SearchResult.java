/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.app.cloudsearch;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link SearchResult} includes all the information for one result item.
 *
 * @hide
 */
@SystemApi
public final class SearchResult implements Parcelable {

    /**
     * List of public static KEYS for Bundles in mExtraInfos.
     * mExtraInfos contains various information specified for different data types.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = {"EXTRAINFO_"},
            value = {EXTRAINFO_APP_DOMAIN_URL,
                    EXTRAINFO_APP_ICON,
                    EXTRAINFO_APP_DEVELOPER_NAME,
                    EXTRAINFO_APP_SIZE_BYTES,
                    EXTRAINFO_APP_STAR_RATING,
                    EXTRAINFO_APP_IARC,
                    EXTRAINFO_APP_REVIEW_COUNT,
                    EXTRAINFO_APP_CONTAINS_ADS_DISCLAIMER,
                    EXTRAINFO_APP_CONTAINS_IAP_DISCLAIMER,
                    EXTRAINFO_SHORT_DESCRIPTION,
                    EXTRAINFO_LONG_DESCRIPTION,
                    EXTRAINFO_SCREENSHOTS,
                    EXTRAINFO_APP_BADGES,
                    EXTRAINFO_ACTION_BUTTON_TEXT_PREREGISTERING,
                    EXTRAINFO_ACTION_BUTTON_IMAGE_PREREGISTERING,
                    EXTRAINFO_ACTION_APP_CARD,
                    EXTRAINFO_ACTION_INSTALL_BUTTON,
                    EXTRAINFO_APP_PACKAGE_NAME,
                    EXTRAINFO_APP_INSTALL_COUNT,
                    EXTRAINFO_WEB_URL,
                    EXTRAINFO_WEB_ICON})
    public @interface SearchResultExtraInfoKey {
    }

    /** This App developer website's domain URL, String value expected. */
    public static final String EXTRAINFO_APP_DOMAIN_URL = "android.app.cloudsearch.APP_DOMAIN_URL";
    /** This App icon, android.graphics.drawable.Icon expected. */
    public static final String EXTRAINFO_APP_ICON = "android.app.cloudsearch.APP_ICON";
    /** This App developer's name, String value expected. */
    public static final String EXTRAINFO_APP_DEVELOPER_NAME =
            "android.app.cloudsearch.APP_DEVELOPER_NAME";
    /** This App's pkg size in bytes, Double value expected. */
    public static final String EXTRAINFO_APP_SIZE_BYTES = "android.app.cloudsearch.APP_SIZE_BYTES";
    /** This App developer's name, Double value expected. */
    public static final String EXTRAINFO_APP_STAR_RATING =
            "android.app.cloudsearch.APP_STAR_RATING";
    /**
     * This App's IARC rating, String value expected.
     * IARC (International Age Rating Coalition) is partnered globally with major
     * content rating organizations to provide a centralized and one-stop-shop for
     * rating content on a global scale.
     */
    public static final String EXTRAINFO_APP_IARC = "android.app.cloudsearch.APP_IARC";
    /** This App's review count, Double value expected. */
    public static final String EXTRAINFO_APP_REVIEW_COUNT =
            "android.app.cloudsearch.APP_REVIEW_COUNT";
    /** If this App contains the Ads Disclaimer, Boolean value expected. */
    public static final String EXTRAINFO_APP_CONTAINS_ADS_DISCLAIMER =
            "android.app.cloudsearch.APP_CONTAINS_ADS_DISCLAIMER";
    /** If this App contains the IAP Disclaimer, Boolean value expected. */
    public static final String EXTRAINFO_APP_CONTAINS_IAP_DISCLAIMER =
            "android.app.cloudsearch.APP_CONTAINS_IAP_DISCLAIMER";
    /** This App's short description, String value expected. */
    public static final String EXTRAINFO_SHORT_DESCRIPTION =
            "android.app.cloudsearch.SHORT_DESCRIPTION";
    /** This App's long description, String value expected. */
    public static final String EXTRAINFO_LONG_DESCRIPTION =
            "android.app.cloudsearch.LONG_DESCRIPTION";
    /** This App's screenshots, {@code List<ImageLoadingBundle>} value expected. */
    public static final String EXTRAINFO_SCREENSHOTS = "android.app.cloudsearch.SCREENSHOTS";
    /** Editor's choices for this App, {@code ArrayList<String>} value expected. */
    public static final String EXTRAINFO_APP_BADGES = "android.app.cloudsearch.APP_BADGES";
    /** Pre-registration game's action button text, String value expected. */
    @SuppressLint("IntentName")
    public static final String EXTRAINFO_ACTION_BUTTON_TEXT_PREREGISTERING =
            "android.app.cloudsearch.ACTION_BUTTON_TEXT";
    /** Pre-registration game's action button image, ImageLoadingBundle value expected. */
    @SuppressLint("IntentName")
    public static final String EXTRAINFO_ACTION_BUTTON_IMAGE_PREREGISTERING =
            "android.app.cloudsearch.ACTION_BUTTON_IMAGE";
    /** Intent for tapping the app card, PendingIntent expected. */
    @SuppressLint("IntentName")
    public static final String EXTRAINFO_ACTION_APP_CARD =
            "android.app.cloudsearch.ACTION_APP_CARD";
    /** Intent for tapping the install button, PendingIntent expected. */
    @SuppressLint("IntentName")
    public static final String EXTRAINFO_ACTION_INSTALL_BUTTON =
            "android.app.cloudsearch.ACTION_INSTALL_BUTTON";
    /** App's package name, String value expected. */
    public static final String EXTRAINFO_APP_PACKAGE_NAME =
            "android.app.cloudsearch.APP_PACKAGE_NAME";
    /** App's install count, double value expected. */
    public static final String EXTRAINFO_APP_INSTALL_COUNT =
            "android.app.cloudsearch.APP_INSTALL_COUNT";
    /** Web content's URL, String value expected. */
    public static final String EXTRAINFO_WEB_URL = "android.app.cloudsearch.WEB_URL";
    /** Web content's domain icon, android.graphics.drawable.Icon expected. */
    public static final String EXTRAINFO_WEB_ICON = "android.app.cloudsearch.WEB_ICON";

    private SearchResult() {
    }

    /** Gets the search result title. */
    @NonNull
    public String getTitle() {
        return "";
    }

    /** Gets the search result snippet. */
    @NonNull
    public String getSnippet() {
        return "";
    }

    /** Gets the ranking score provided by the original search provider. */
    public float getScore() {
        return 0;
    }

    /** Gets the extra information associated with the search result. */
    @NonNull
    public Bundle getExtraInfos() {
        return Bundle.EMPTY;
    }

    /**
     * @see Creator
     */
    @NonNull
    public static final Creator<SearchResult> CREATOR = new Creator<SearchResult>() {
        @Override
        public SearchResult createFromParcel(Parcel p) {
            return new SearchResult();
        }

        @Override
        public SearchResult[] newArray(int size) {
            return new SearchResult[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return false;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    /**
     * Builder constructing SearchResult.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        /**
         * @param title      the title to the search result.
         * @param extraInfos the extra infos associated with the search result.
         * @hide
         */
        @SystemApi
        public Builder(@NonNull String title, @NonNull Bundle extraInfos) {
        }

        /** Sets the title to the search result. */
        @NonNull
        public Builder setTitle(@NonNull String title) {
            return this;
        }

        /** Sets the snippet to the search result. */
        @NonNull
        public Builder setSnippet(@NonNull String snippet) {
            return this;
        }

        /** Sets the ranking score to the search result. */
        @NonNull
        public Builder setScore(float score) {
            return this;
        }

        /** Adds extra information to the search result for rendering in the UI. */
        @NonNull
        public Builder setExtraInfos(@NonNull Bundle extraInfos) {
            return this;
        }

        /** Builds a SearchResult based-on the given parameters. */
        @NonNull
        public SearchResult build() {
            return new SearchResult();
        }
    }
}
