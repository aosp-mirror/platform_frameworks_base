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
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class represents the results of setSchema().
 *
 * @hide
 */
public class SetSchemaResult {

    public static final String DELETED_SCHEMA_TYPES_FIELD = "deletedSchemaTypes";
    public static final String INCOMPATIBLE_SCHEMA_TYPES_FIELD = "incompatibleSchemaTypes";
    public static final String RESULT_CODE_FIELD = "resultCode";
    private final List<String> mDeletedSchemaTypes;
    private final List<String> mIncompatibleSchemaTypes;
    private final Bundle mBundle;

    SetSchemaResult(@NonNull Bundle bundle) {
        mBundle = Preconditions.checkNotNull(bundle);
        mDeletedSchemaTypes =
                Preconditions.checkNotNull(mBundle.getStringArrayList(DELETED_SCHEMA_TYPES_FIELD));
        mIncompatibleSchemaTypes =
                Preconditions.checkNotNull(
                        mBundle.getStringArrayList(INCOMPATIBLE_SCHEMA_TYPES_FIELD));
    }

    /** Returns the {@link Bundle} of this class. */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    /** returns all deleted schema types in this setSchema call. */
    @NonNull
    public List<String> getDeletedSchemaTypes() {
        return Collections.unmodifiableList(mDeletedSchemaTypes);
    }

    /** returns all incompatible schema types in this setSchema call. */
    @NonNull
    public List<String> getIncompatibleSchemaTypes() {
        return Collections.unmodifiableList(mIncompatibleSchemaTypes);
    }

    /**
     * returns the {@link android.app.appsearch.AppSearchResult.ResultCode} of the {@link
     * AppSearchSession#setSchema} call.
     */
    public int getResultCode() {
        return mBundle.getInt(RESULT_CODE_FIELD);
    }

    /** Builder for {@link SetSchemaResult} objects. */
    public static final class Builder {
        private final ArrayList<String> mDeletedSchemaTypes = new ArrayList<>();
        private final ArrayList<String> mIncompatibleSchemaTypes = new ArrayList<>();
        @AppSearchResult.ResultCode private int mResultCode;
        private boolean mBuilt = false;

        /** Adds a deletedSchemaTypes to the {@link SetSchemaResult}. */
        @NonNull
        public Builder addDeletedSchemaType(@NonNull String deletedSchemaType) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mDeletedSchemaTypes.add(Preconditions.checkNotNull(deletedSchemaType));
            return this;
        }

        /** Adds a incompatible SchemaTypes to the {@link SetSchemaResult}. */
        @NonNull
        public Builder addIncompatibleSchemaType(@NonNull String incompatibleSchemaTypes) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mIncompatibleSchemaTypes.add(Preconditions.checkNotNull(incompatibleSchemaTypes));
            return this;
        }

        /**
         * Sets the {@link android.app.appsearch.AppSearchResult.ResultCode} of the {@link
         * AppSearchSession#setSchema} call to the {@link SetSchemaResult}
         */
        @NonNull
        public Builder setResultCode(@AppSearchResult.ResultCode int resultCode) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mResultCode = resultCode;
            return this;
        }

        /** Builds a {@link SetSchemaResult}. */
        @NonNull
        public SetSchemaResult build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(
                    SetSchemaResult.DELETED_SCHEMA_TYPES_FIELD, mDeletedSchemaTypes);
            bundle.putStringArrayList(
                    SetSchemaResult.INCOMPATIBLE_SCHEMA_TYPES_FIELD, mIncompatibleSchemaTypes);
            bundle.putInt(RESULT_CODE_FIELD, mResultCode);
            mBuilt = true;
            return new SetSchemaResult(bundle);
        }
    }
}
