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
import android.app.appsearch.PackageIdentifier;

/**
 * Holds configuration about a package+cert that can access a schema.
 *
 * @see android.app.appsearch.SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage
 */
class PackageAccessibleDocument extends GenericDocument {
    /** Schema type for nested documents that hold package accessible information. */
    public static final String SCHEMA_TYPE = "PackageAccessibleType";

    /** Property that holds the package name that can access a schema. */
    private static final String PACKAGE_NAME_PROPERTY = "packageName";

    /** Property that holds the SHA 256 certificate of the app that can access a schema. */
    private static final String SHA_256_CERT_PROPERTY = "sha256Cert";

    /** Property that holds the prefixed schema type that is accessible by some package. */
    private static final String ACCESSIBLE_SCHEMA_PROPERTY = "accessibleSchema";

    /**
     * Schema for package accessible documents, these will be nested in a top-level
     * {@link VisibilityDocument}.
     *
     * <p>NOTE: If you update this, also update
     * {@link com.android.server.appsearch.external.localstorage.VisibilityStore#SCHEMA_VERSION}
     */
    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(PACKAGE_NAME_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            .addProperty(new AppSearchSchema.BytesPropertyConfig.Builder(SHA_256_CERT_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    ACCESSIBLE_SCHEMA_PROPERTY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build())
            .build();

    public PackageAccessibleDocument(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    @Nullable
    public String getAccessibleSchemaType() {
        return getPropertyString(ACCESSIBLE_SCHEMA_PROPERTY);
    }

    /** Gets which package is able to access {@link #getAccessibleSchemaType} */
    @NonNull
    public PackageIdentifier getPackageIdentifier() {
        String packageName = getPropertyString(PACKAGE_NAME_PROPERTY);
        byte[] sha256Cert = getPropertyBytes(SHA_256_CERT_PROPERTY);
        return new PackageIdentifier(packageName, sha256Cert);
    }

    /** Builder for {@link PackageAccessibleDocument} instances. */
    public static class Builder extends GenericDocument.Builder<PackageAccessibleDocument.Builder> {
        public Builder(@NonNull String namespace, @NonNull String id) {
            super(namespace, id, SCHEMA_TYPE);
        }

        /** Sets which prefixed schema type is accessible by the package */
        @NonNull
        public Builder setAccessibleSchemaType(@NonNull String schemaType) {
            return setPropertyString(ACCESSIBLE_SCHEMA_PROPERTY, schemaType);
        }

        /** Sets which package is able to access the {@link #setAccessibleSchemaType}. */
        @NonNull
        public Builder setPackageIdentifier(@NonNull PackageIdentifier packageIdentifier) {
            return setPropertyString(PACKAGE_NAME_PROPERTY, packageIdentifier.getPackageName())
                    .setPropertyBytes(SHA_256_CERT_PROPERTY,
                            packageIdentifier.getSha256Certificate());
        }

        @Override
        @NonNull
        public PackageAccessibleDocument build() {
            return new PackageAccessibleDocument(super.build());
        }
    }
}
