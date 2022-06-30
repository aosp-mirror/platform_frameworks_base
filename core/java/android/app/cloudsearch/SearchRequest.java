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
import java.util.Objects;

/**
 * A {@link SearchRequest} is the data class having all the information passed to search providers.
 *
 * @hide
 */
@SystemApi
public final class SearchRequest implements Parcelable {

    /**
     * Query for search.
     */
    @NonNull
    private final String mQuery;

    /**
     * Expected result offset for pagination.
     *
     * The default value is 0.
    */
    private final int mResultOffset;

    /**
     * Expected search result number.
     *
     * The default value is 10.
     */
    private final int mResultNumber;

    /**
     * The max acceptable latency.
     *
     * The default value is 200 milliseconds.
     */
    private final float mMaxLatencyMillis;

    @Nullable
    private String mId = null;

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
    public @interface SearchConstraintKey {}
    /** If this is a presubmit suggestion, Boolean value expected.
     *  presubmit is the input before the user finishes the entire query, i.e. push "ENTER" or
     *  "SEARCH" button. After the user finishes the entire query, the behavior is postsubmit.
     */
    public static final String CONSTRAINT_IS_PRESUBMIT_SUGGESTION =
            "android.app.cloudsearch.IS_PRESUBMIT_SUGGESTION";
    /** The target search provider list of package names(separated by ;), String value expected.
     * If this is not provided or its value is empty, then no filter will be applied.
     */
    public static final String CONSTRAINT_SEARCH_PROVIDER_FILTER =
            "android.app.cloudsearch.SEARCH_PROVIDER_FILTER";

    @NonNull
    private Bundle mSearchConstraints;

    /** Auto set by system servier, and the caller cannot set it.
     *
     * The caller's package name.
     *
     */
    @NonNull
    private String mCallerPackageName;

    private SearchRequest(Parcel in) {
        this.mQuery = in.readString();
        this.mResultOffset = in.readInt();
        this.mResultNumber = in.readInt();
        this.mMaxLatencyMillis = in.readFloat();
        this.mSearchConstraints = in.readBundle();
        this.mId = in.readString();
        this.mCallerPackageName = in.readString();
    }

    private SearchRequest(String query, int resultOffset, int resultNumber, float maxLatencyMillis,
            Bundle searchConstraints, String callerPackageName) {
        mQuery = query;
        mResultOffset = resultOffset;
        mResultNumber = resultNumber;
        mMaxLatencyMillis = maxLatencyMillis;
        mSearchConstraints = searchConstraints;
        mCallerPackageName = callerPackageName;
    }

    /** Returns the original query. */
    @NonNull
    public String getQuery() {
        return mQuery;
    }

    /** Returns the result offset. */
    public int getResultOffset() {
        return mResultOffset;
    }

    /** Returns the expected number of search results. */
    public int getResultNumber() {
        return mResultNumber;
    }

    /** Returns the maximum latency requirement. */
    public float getMaxLatencyMillis() {
        return mMaxLatencyMillis;
    }

    /** Returns the search constraints. */
    @NonNull
    public Bundle getSearchConstraints() {
        return mSearchConstraints;
    }

    /** Gets the caller's package name. */
    @NonNull
    public String getCallerPackageName() {
        return mCallerPackageName;
    }

    /** Returns the search request id, which is used to identify the request. */
    @NonNull
    public String getRequestId() {
        if (mId == null || mId.length() == 0) {
            mId = String.valueOf(toString().hashCode());
        }

        return mId;
    }

    /** Sets the caller, and this will be set by the system server.
     *
     * @hide
     */
    public void setCallerPackageName(@NonNull String callerPackageName) {
        this.mCallerPackageName = callerPackageName;
    }

    private SearchRequest(Builder b) {
        mQuery = requireNonNull(b.mQuery);
        mResultOffset = b.mResultOffset;
        mResultNumber = b.mResultNumber;
        mMaxLatencyMillis = b.mMaxLatencyMillis;
        mSearchConstraints = requireNonNull(b.mSearchConstraints);
        mCallerPackageName = requireNonNull(b.mCallerPackageName);
    }

    /**
     * @see Creator
     *
     */
    @NonNull
    public static final Creator<SearchRequest> CREATOR = new Creator<SearchRequest>() {
        @Override
        public SearchRequest createFromParcel(Parcel p) {
            return new SearchRequest(p);
        }

        @Override
        public SearchRequest[] newArray(int size) {
            return new SearchRequest[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(this.mQuery);
        dest.writeInt(this.mResultOffset);
        dest.writeInt(this.mResultNumber);
        dest.writeFloat(this.mMaxLatencyMillis);
        dest.writeBundle(this.mSearchConstraints);
        dest.writeString(getRequestId());
        dest.writeString(this.mCallerPackageName);
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

        SearchRequest that = (SearchRequest) obj;
        return Objects.equals(mQuery, that.mQuery)
                && mResultOffset == that.mResultOffset
                && mResultNumber == that.mResultNumber
                && mMaxLatencyMillis == that.mMaxLatencyMillis
                && Objects.equals(mSearchConstraints, that.mSearchConstraints)
                && Objects.equals(mCallerPackageName, that.mCallerPackageName);
    }

    @Override
    public String toString() {
        boolean isPresubmit =
                mSearchConstraints.containsKey(CONSTRAINT_IS_PRESUBMIT_SUGGESTION)
                        && mSearchConstraints.getBoolean(CONSTRAINT_IS_PRESUBMIT_SUGGESTION);

        String searchProvider = "EMPTY";
        if (mSearchConstraints.containsKey(CONSTRAINT_SEARCH_PROVIDER_FILTER)) {
            searchProvider = mSearchConstraints.getString(CONSTRAINT_SEARCH_PROVIDER_FILTER);
        }

        return String.format("SearchRequest: {query:%s,offset:%d;number:%d;max_latency:%f;"
                        + "is_presubmit:%b;search_provider:%s;callerPackageName:%s}", mQuery,
                mResultOffset, mResultNumber, mMaxLatencyMillis, isPresubmit, searchProvider,
                mCallerPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mQuery, mResultOffset, mResultNumber, mMaxLatencyMillis,
                mSearchConstraints, mCallerPackageName);
    }

    /**
     * The builder for {@link SearchRequest}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private String mQuery;
        private int mResultOffset;
        private int mResultNumber;
        private float mMaxLatencyMillis;
        private Bundle mSearchConstraints;
        private String mCallerPackageName;

        /**
         *
         * @param query the query for search.
         *
         * @hide
         */
        @SystemApi
        public Builder(@NonNull String query) {
            mQuery = query;

            mResultOffset = 0;
            mResultNumber = 10;
            mMaxLatencyMillis = 200;
            mSearchConstraints = Bundle.EMPTY;
            mCallerPackageName = "DEFAULT_CALLER";
        }

        /** Sets the input query. */
        @NonNull
        public Builder setQuery(@NonNull String query) {
            this.mQuery = query;
            return this;
        }

        /** Sets the search result offset. */
        @NonNull
        public Builder setResultOffset(int resultOffset) {
            this.mResultOffset = resultOffset;
            return this;
        }

        /** Sets the expected number of search result. */
        @NonNull
        public Builder setResultNumber(int resultNumber) {
            this.mResultNumber = resultNumber;
            return this;
        }

        /** Sets the maximum acceptable search latency. */
        @NonNull
        public Builder setMaxLatencyMillis(float maxLatencyMillis) {
            this.mMaxLatencyMillis = maxLatencyMillis;
            return this;
        }

        /** Sets the search constraints, such as the user location, the search type(presubmit or
         * postsubmit), and the target search providers. */
        @NonNull
        public Builder setSearchConstraints(@Nullable Bundle searchConstraints) {
            this.mSearchConstraints = searchConstraints;
            return this;
        }

        /** Sets the caller, and this will be set by the system server.
         *
         * @hide
         */
        @NonNull
        @TestApi
        public Builder setCallerPackageName(@NonNull String callerPackageName) {
            this.mCallerPackageName = callerPackageName;
            return this;
        }

        /** Builds a SearchRequest based-on the given params. */
        @NonNull
        public SearchRequest build() {
            if (mQuery == null || mResultOffset < 0 || mResultNumber < 1 || mMaxLatencyMillis < 0
                    || mSearchConstraints == null) {
                throw new IllegalStateException("Please make sure all required args are valid.");
            }

            return new SearchRequest(mQuery, mResultOffset, mResultNumber, mMaxLatencyMillis,
                               mSearchConstraints, mCallerPackageName);
        }
    }
}
