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

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    public @interface SearchStatusCode {}
    public static final int SEARCH_STATUS_UNKNOWN = -1;
    public static final int SEARCH_STATUS_OK = 0;
    public static final int SEARCH_STATUS_TIME_OUT = 1;
    public static final int SEARCH_STATUS_NO_INTERNET = 2;

    private final int mStatusCode;

    /** Auto set by system servier, and the provider cannot set it. */
    @NonNull
    private String mSource;

    @NonNull
    private final List<SearchResult> mSearchResults;

    private SearchResponse(Parcel in) {
        this.mStatusCode = in.readInt();
        this.mSource = in.readString();
        this.mSearchResults = in.createTypedArrayList(SearchResult.CREATOR);
    }

    private SearchResponse(@SearchStatusCode int statusCode,  String source,
                           List<SearchResult> searchResults) {
        mStatusCode = statusCode;
        mSource = source;
        mSearchResults = searchResults;
    }

    /** Gets the search status code. */
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Gets the search provider package name. */
    @NonNull
    public String getSource() {
        return mSource;
    }

    /** Gets the search results, which can be empty. */
    @NonNull
    public List<SearchResult> getSearchResults() {
        return mSearchResults;
    }

    /** Sets the search provider, and this will be set by the system server.
     *
     * @hide
     */
    public void setSource(@NonNull String source) {
        this.mSource = source;
    }

    private SearchResponse(Builder b) {
        mStatusCode = b.mStatusCode;
        mSource = requireNonNull(b.mSource);
        mSearchResults = requireNonNull(b.mSearchResults);
    }

    /**
     *
     * @see Creator
     *
     */
    @NonNull public static final Creator<SearchResponse> CREATOR = new Creator<SearchResponse>() {
        @Override
        public SearchResponse createFromParcel(Parcel p) {
            return new SearchResponse(p);
        }

        @Override
        public SearchResponse[] newArray(int size) {
            return new SearchResponse[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(this.mStatusCode);
        dest.writeString(this.mSource);
        dest.writeTypedList(this.mSearchResults);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        SearchResponse that = (SearchResponse) obj;
        return mStatusCode == that.mStatusCode
                && Objects.equals(mSource, that.mSource)
                && Objects.equals(mSearchResults, that.mSearchResults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatusCode, mSource, mSearchResults);
    }

    /**
     * Builder constructing SearchResponse.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private int mStatusCode;
        private String mSource;
        private List<SearchResult> mSearchResults;

        /**
         *
         * @param statusCode the search status code.
         *
         * @hide
         */
        @SystemApi
        public Builder(@SearchStatusCode int statusCode) {
            mStatusCode = statusCode;

            /** Init with a default value. */
            mSource = "DEFAULT";

            mSearchResults = new ArrayList<SearchResult>();
        }

        /** Sets the search status code. */
        @NonNull
        public Builder setStatusCode(@SearchStatusCode int statusCode) {
            this.mStatusCode = statusCode;
            return this;
        }

        /** Sets the search provider, and this will be set by the system server.
         *
         * @hide
         */
        @NonNull
        public Builder setSource(@NonNull String source) {
            this.mSource = source;
            return this;
        }

        /** Sets the search results. */
        @NonNull
        public Builder setSearchResults(@NonNull List<SearchResult> searchResults) {
            this.mSearchResults = searchResults;
            return this;
        }

        /** Builds a SearchResponse based-on the given parameters. */
        @NonNull
        public SearchResponse build() {
            if (mStatusCode < SEARCH_STATUS_UNKNOWN || mStatusCode > SEARCH_STATUS_NO_INTERNET
                    || mSearchResults == null) {
                throw new IllegalStateException("Please make sure all @NonNull args are assigned.");
            }

            return new SearchResponse(mStatusCode, mSource, mSearchResults);
        }
    }
}
