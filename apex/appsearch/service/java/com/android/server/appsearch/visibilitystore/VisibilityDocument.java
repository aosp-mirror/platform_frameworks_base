/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.appsearch.visibilitystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;

/** Holds the visibility settings that apply to a package's databases. */
class VisibilityDocument extends GenericDocument {
    /** Schema type for documents that hold AppSearch's metadata, e.g. visibility settings */
    public static final String SCHEMA_TYPE = "VisibilityType";

    /**
     * Property that holds the list of platform-hidden schemas, as part of the visibility settings.
     */
    private static final String NOT_DISPLAYED_BY_SYSTEM_PROPERTY = "notPlatformSurfaceable";

    /** Property that holds nested documents of package accessible schemas. */
    private static final String VISIBLE_TO_PACKAGES_PROPERTY = "packageAccessible";

    /**
     * Schema for the VisibilityStore's documents.
     *
     * <p>NOTE: If you update this, also update
     * {@link com.android.server.appsearch.external.localstorage.VisibilityStore#SCHEMA_VERSION}
     */
    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    NOT_DISPLAYED_BY_SYSTEM_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .addProperty(new AppSearchSchema.DocumentPropertyConfig.Builder(
                    VISIBLE_TO_PACKAGES_PROPERTY, VisibleToPackagesDocument.SCHEMA_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .build();

    public VisibilityDocument(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    @Nullable
    public String[] getNotDisplayedBySystem() {
        return getPropertyStringArray(NOT_DISPLAYED_BY_SYSTEM_PROPERTY);
    }

    @Nullable
    public GenericDocument[] getVisibleToPackages() {
        return getPropertyDocumentArray(VISIBLE_TO_PACKAGES_PROPERTY);
    }

    /** Builder for {@link VisibilityDocument}. */
    public static class Builder extends GenericDocument.Builder<VisibilityDocument.Builder> {
        public Builder(@NonNull String namespace, @NonNull String id) {
            super(namespace, id, SCHEMA_TYPE);
        }

        /** Sets which prefixed schemas have opted out of platform surfacing. */
        @NonNull
        public Builder setNotDisplayedBySystem(@NonNull String[] notDisplayedBySystemSchemas) {
            return setPropertyString(NOT_DISPLAYED_BY_SYSTEM_PROPERTY, notDisplayedBySystemSchemas);
        }

        /** Sets which prefixed schemas have configured package access. */
        @NonNull
        public Builder setVisibleToPackages(
                @NonNull VisibleToPackagesDocument[] visibleToPackagesDocuments) {
            return setPropertyDocument(VISIBLE_TO_PACKAGES_PROPERTY, visibleToPackagesDocuments);
        }
    }
}
