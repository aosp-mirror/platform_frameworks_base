/*
 * Copyright 2021 The Android Open Source Project
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

package android.app.appsearch;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

/**
 * A request to report usage of a document.
 *
 * <p>See {@link AppSearchSession#reportUsage} for a detailed description of usage reporting.
 *
 * @see AppSearchSession#reportUsage
 */
public final class ReportUsageRequest {
    private final String mNamespace;
    private final String mUri;
    private final long mUsageTimeMillis;

    ReportUsageRequest(@NonNull String namespace, @NonNull String uri, long usageTimeMillis) {
        mNamespace = Preconditions.checkNotNull(namespace);
        mUri = Preconditions.checkNotNull(uri);
        mUsageTimeMillis = usageTimeMillis;
    }

    /** Returns the namespace of the document that was used. */
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    /** Returns the URI of document that was used. */
    @NonNull
    public String getUri() {
        return mUri;
    }

    /**
     * Returns the timestamp in milliseconds of the usage report (the time at which the document was
     * used).
     *
     * <p>The value is in the {@link System#currentTimeMillis} time base.
     */
    public long getUsageTimeMillis() {
        return mUsageTimeMillis;
    }

    /** Builder for {@link ReportUsageRequest} objects. */
    public static final class Builder {
        private final String mNamespace;
        private String mUri;
        private Long mUsageTimeMillis;
        private boolean mBuilt = false;

        /** Creates a {@link ReportUsageRequest.Builder} instance. */
        public Builder(@NonNull String namespace) {
            mNamespace = Preconditions.checkNotNull(namespace);
        }

        /**
         * Sets the URI of the document being used.
         *
         * <p>This field is required.
         *
         * @throws IllegalStateException if the builder has already been used
         */
        @NonNull
        public ReportUsageRequest.Builder setUri(@NonNull String uri) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(uri);
            mUri = uri;
            return this;
        }

        /**
         * Sets the timestamp in milliseconds of the usage report (the time at which the document
         * was used).
         *
         * <p>The value is in the {@link System#currentTimeMillis} time base.
         *
         * <p>If unset, this defaults to the current timestamp at the time that the {@link
         * ReportUsageRequest} is constructed.
         *
         * @throws IllegalStateException if the builder has already been used
         */
        @NonNull
        public ReportUsageRequest.Builder setUsageTimeMillis(long usageTimeMillis) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mUsageTimeMillis = usageTimeMillis;
            return this;
        }

        /**
         * Builds a new {@link ReportUsageRequest}.
         *
         * @throws NullPointerException if {@link #setUri} has never been called
         * @throws IllegalStateException if the builder has already been used
         */
        @NonNull
        public ReportUsageRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(mUri, "ReportUsageRequest is missing a URI");
            if (mUsageTimeMillis == null) {
                mUsageTimeMillis = System.currentTimeMillis();
            }
            mBuilt = true;
            return new ReportUsageRequest(mNamespace, mUri, mUsageTimeMillis);
        }
    }
}
