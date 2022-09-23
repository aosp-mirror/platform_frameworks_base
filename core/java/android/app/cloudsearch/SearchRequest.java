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
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link SearchRequest} is the data class having all the information passed to search providers.
 *
 * @hide
 */
@SystemApi
public final class SearchRequest implements Parcelable {

    /**
     * List of public static KEYS for the Bundle to  mSearchConstraints. mSearchConstraints
     * contains various constraints specifying the search intent.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = {"CONSTRAINT_"},
            value = {CONSTRAINT_IS_PRESUBMIT_SUGGESTION,
                    CONSTRAINT_SEARCH_PROVIDER_FILTER})
    public @interface SearchConstraintKey {
    }

    /**
     * If this is a presubmit suggestion, Boolean value expected.
     * presubmit is the input before the user finishes the entire query, i.e. push "ENTER" or
     * "SEARCH" button. After the user finishes the entire query, the behavior is postsubmit.
     */
    public static final String CONSTRAINT_IS_PRESUBMIT_SUGGESTION =
            "android.app.cloudsearch.IS_PRESUBMIT_SUGGESTION";
    /**
     * The target search provider list of package names(separated by ;), String value expected.
     * If this is not provided or its value is empty, then no filter will be applied.
     */
    public static final String CONSTRAINT_SEARCH_PROVIDER_FILTER =
            "android.app.cloudsearch.SEARCH_PROVIDER_FILTER";

    private SearchRequest() {
    }

    /** Returns the original query. */
    @NonNull
    public String getQuery() {
        return "";
    }

    /** Returns the result offset. */
    public int getResultOffset() {
        return 0;
    }

    /** Returns the expected number of search results. */
    public int getResultNumber() {
        return 0;
    }

    /** Returns the maximum latency requirement. */
    public float getMaxLatencyMillis() {
        return 0;
    }

    /** Returns the search constraints. */
    @NonNull
    public Bundle getSearchConstraints() {
        return Bundle.EMPTY;
    }

    /** Gets the caller's package name. */
    @NonNull
    public String getCallerPackageName() {
        return "";
    }

    /** Returns the search request id, which is used to identify the request. */
    @NonNull
    public String getRequestId() {
        return "";
    }

    /**
     * Sets the caller, and this will be set by the system server.
     *
     * @hide
     */
    public void setCallerPackageName(@NonNull String callerPackageName) {
    }

    /**
     * @see Creator
     */
    @NonNull
    public static final Creator<SearchRequest> CREATOR = new Creator<SearchRequest>() {
        @Override
        public SearchRequest createFromParcel(Parcel p) {
            return new SearchRequest();
        }

        @Override
        public SearchRequest[] newArray(int size) {
            return new SearchRequest[size];
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
    public String toString() {
        return "";
    }

    @Override
    public int hashCode() {
        return 0;
    }

    /**
     * The builder for {@link SearchRequest}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        /**
         * @param query the query for search.
         * @hide
         */
        @SystemApi
        public Builder(@NonNull String query) {
        }

        /** Sets the input query. */
        @NonNull
        public Builder setQuery(@NonNull String query) {
            return this;
        }

        /** Sets the search result offset. */
        @NonNull
        public Builder setResultOffset(int resultOffset) {
            return this;
        }

        /** Sets the expected number of search result. */
        @NonNull
        public Builder setResultNumber(int resultNumber) {
            return this;
        }

        /** Sets the maximum acceptable search latency. */
        @NonNull
        public Builder setMaxLatencyMillis(float maxLatencyMillis) {
            return this;
        }

        /**
         * Sets the search constraints, such as the user location, the search type(presubmit or
         * postsubmit), and the target search providers.
         */
        @NonNull
        public Builder setSearchConstraints(@Nullable Bundle searchConstraints) {
            return this;
        }

        /**
         * Sets the caller, and this will be set by the system server.
         *
         * @hide
         */
        @NonNull
        @TestApi
        public Builder setCallerPackageName(@NonNull String callerPackageName) {
            return this;
        }

        /** Builds a SearchRequest based-on the given params. */
        @NonNull
        public SearchRequest build() {
            return new SearchRequest();
        }
    }
}
