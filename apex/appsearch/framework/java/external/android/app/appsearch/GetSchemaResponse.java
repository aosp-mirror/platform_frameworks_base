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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Bundle;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Set;

/** The response class of {@link AppSearchSession#getSchema} */
public class GetSchemaResponse {
    private static final String VERSION_FIELD = "version";
    private static final String SCHEMAS_FIELD = "schemas";

    private final Bundle mBundle;

    GetSchemaResponse(@NonNull Bundle bundle) {
        mBundle = Preconditions.checkNotNull(bundle);
    }

    /**
     * Returns the {@link Bundle} populated by this builder.
     *
     * @hide
     */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    /**
     * Returns the overall database schema version.
     *
     * <p>If the database is empty, 0 will be returned.
     */
    @IntRange(from = 0)
    public int getVersion() {
        return mBundle.getInt(VERSION_FIELD);
    }

    /**
     * Return the schemas most recently successfully provided to {@link AppSearchSession#setSchema}.
     *
     * <p>It is inefficient to call this method repeatedly.
     */
    @NonNull
    public Set<AppSearchSchema> getSchemas() {
        ArrayList<Bundle> schemaBundles = mBundle.getParcelableArrayList(SCHEMAS_FIELD);
        Set<AppSearchSchema> schemas = new ArraySet<>(schemaBundles.size());
        for (int i = 0; i < schemaBundles.size(); i++) {
            schemas.add(new AppSearchSchema(schemaBundles.get(i)));
        }
        return schemas;
    }

    /** Builder for {@link GetSchemaResponse} objects. */
    public static final class Builder {
        private int mVersion;
        private boolean mBuilt = false;
        private final ArrayList<Bundle> mSchemaBundles = new ArrayList<>();

        /**
         * Sets the database overall schema version.
         *
         * <p>Default version is 0
         */
        @NonNull
        public Builder setVersion(@IntRange(from = 0) int version) {
            mVersion = version;
            return this;
        }

        /** Adds one {@link AppSearchSchema} to the schema list. */
        @NonNull
        public Builder addSchema(@NonNull AppSearchSchema schema) {
            mSchemaBundles.add(schema.getBundle());
            return this;
        }

        /** Builds a {@link GetSchemaResponse} object. */
        @NonNull
        public GetSchemaResponse build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Bundle bundle = new Bundle();
            bundle.putInt(VERSION_FIELD, mVersion);
            bundle.putParcelableArrayList(SCHEMAS_FIELD, mSchemaBundles);
            mBuilt = true;
            return new GetSchemaResponse(bundle);
        }
    }
}
