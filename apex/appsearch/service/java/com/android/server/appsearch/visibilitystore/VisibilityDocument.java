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
    private static final String NOT_PLATFORM_SURFACEABLE_PROPERTY = "notPlatformSurfaceable";

    /** Property that holds nested documents of package accessible schemas. */
    private static final String PACKAGE_ACCESSIBLE_PROPERTY = "packageAccessible";

    /**
     * Schema for the VisibilityStore's documents.
     *
     * <p>NOTE: If you update this, also update
     * {@link com.android.server.appsearch.external.localstorage.VisibilityStore#SCHEMA_VERSION}
     */
    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    NOT_PLATFORM_SURFACEABLE_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .addProperty(new AppSearchSchema.DocumentPropertyConfig.Builder(
                    PACKAGE_ACCESSIBLE_PROPERTY, PackageAccessibleDocument.SCHEMA_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build())
            .build();

    public VisibilityDocument(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    @Nullable
    public String[] getNotPlatformSurfaceableSchemas() {
        return getPropertyStringArray(NOT_PLATFORM_SURFACEABLE_PROPERTY);
    }

    @Nullable
    public GenericDocument[] getPackageAccessibleSchemas() {
        return getPropertyDocumentArray(PACKAGE_ACCESSIBLE_PROPERTY);
    }

    /** Builder for {@link VisibilityDocument}. */
    public static class Builder extends GenericDocument.Builder<VisibilityDocument.Builder> {
        public Builder(@NonNull String namespace, @NonNull String id) {
            super(namespace, id, SCHEMA_TYPE);
        }

        /** Sets which prefixed schemas have opted out of platform surfacing. */
        @NonNull
        public Builder setSchemasNotPlatformSurfaceable(
                @NonNull String[] notPlatformSurfaceableSchemas) {
            return setPropertyString(
                    NOT_PLATFORM_SURFACEABLE_PROPERTY, notPlatformSurfaceableSchemas);
        }

        /** Sets which prefixed schemas have configured package access. */
        @NonNull
        public Builder setPackageAccessibleSchemas(
                @NonNull PackageAccessibleDocument[] packageAccessibleSchemas) {
            return setPropertyDocument(PACKAGE_ACCESSIBLE_PROPERTY, packageAccessibleSchemas);
        }
    }
}
