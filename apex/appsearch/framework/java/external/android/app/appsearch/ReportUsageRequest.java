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
        private String mNamespace;
        private String mUri;
        private Long mUsageTimeMillis;
        private boolean mBuilt = false;

        /**
         * TODO(b/181887768): This method exists only for dogfooder transition and must be removed.
         *
         * @deprecated Please supply the namespace in {@link #Builder(String)} instead. This method
         *     exists only for dogfooder transition and must be removed.
         */
        @Deprecated
        public Builder() {
            mNamespace = GenericDocument.DEFAULT_NAMESPACE;
        }

        /** Creates a {@link ReportUsageRequest.Builder} instance. */
        public Builder(@NonNull String namespace) {
            mNamespace = Preconditions.checkNotNull(namespace);
        }

        /**
         * Sets which namespace the document being used belongs to.
         *
         * <p>If this is not set, it defaults to an empty string.
         *
         * <p>TODO(b/181887768): This method exists only for dogfooder transition and must be
         * removed.
         *
         * @throws IllegalStateException if the builder has already been used
         * @deprecated Please supply the namespace in {@link #Builder(String)} instead. This method
         *     exists only for dogfooder transition and must
         */
        @Deprecated
        @NonNull
        public ReportUsageRequest.Builder setNamespace(@NonNull String namespace) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(namespace);
            mNamespace = namespace;
            return this;
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
