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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SearchResponse} includes search results and associated meta information.
 *
 * @hide
 */
@SystemApi
public final class SearchResponse implements Parcelable {
    /** @hide */
    @IntDef(prefix = {"SEARCH_STATUS_"},
            value = {SEARCH_STATUS_UNKNOWN,
                    SEARCH_STATUS_OK,
                    SEARCH_STATUS_TIME_OUT,
                    SEARCH_STATUS_NO_INTERNET})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SearchStatusCode {
    }

    public static final int SEARCH_STATUS_UNKNOWN = -1;
    public static final int SEARCH_STATUS_OK = 0;
    public static final int SEARCH_STATUS_TIME_OUT = 1;
    public static final int SEARCH_STATUS_NO_INTERNET = 2;

    private SearchResponse() {
    }

    /** Gets the search status code. */
    public int getStatusCode() {
        return SEARCH_STATUS_UNKNOWN;
    }

    /** Gets the search provider package name. */
    @NonNull
    public String getSource() {
        return "";
    }

    /** Gets the search results, which can be empty. */
    @NonNull
    public List<SearchResult> getSearchResults() {
        return new ArrayList<SearchResult>();
    }

    /**
     * Sets the search provider, and this will be set by the system server.
     *
     * @hide
     */
    public void setSource(@NonNull String source) {
    }

    /**
     * @see Creator
     */
    @NonNull
    public static final Creator<SearchResponse> CREATOR = new Creator<SearchResponse>() {
        @Override
        public SearchResponse createFromParcel(Parcel p) {
            return new SearchResponse();
        }

        @Override
        public SearchResponse[] newArray(int size) {
            return new SearchResponse[size];
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
     * Builder constructing SearchResponse.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        /**
         * @param statusCode the search status code.
         * @hide
         */
        @SystemApi
        public Builder(@SearchStatusCode int statusCode) {
        }

        /** Sets the search status code. */
        @NonNull
        public Builder setStatusCode(@SearchStatusCode int statusCode) {
            return this;
        }

        /**
         * Sets the search provider, and this will be set by the system server.
         *
         * @hide
         */
        @NonNull
        public Builder setSource(@NonNull String source) {
            return this;
        }

        /** Sets the search results. */
        @NonNull
        public Builder setSearchResults(@NonNull List<SearchResult> searchResults) {
            return this;
        }

        /** Builds a SearchResponse based-on the given parameters. */
        @NonNull
        public SearchResponse build() {
            return new SearchResponse();
        }
    }
}
