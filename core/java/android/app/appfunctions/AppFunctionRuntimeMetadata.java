/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Represents runtime function metadata of an app function.
 *
 * <p>This is a temporary solution for app function indexing, as later we would like to index the
 * actual function signature entity class shape instead of just the schema info.
 *
 * @hide
 */
// TODO(b/357551503): Link to canonical docs rather than duplicating once they
// are available.
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public class AppFunctionRuntimeMetadata extends GenericDocument {
    public static final String RUNTIME_SCHEMA_TYPE = "AppFunctionRuntimeMetadata";
    public static final String APP_FUNCTION_INDEXER_PACKAGE = "android";
    public static final String APP_FUNCTION_RUNTIME_METADATA_DB = "appfunctions-db";
    public static final String APP_FUNCTION_RUNTIME_NAMESPACE = "app_functions_runtime";
    public static final String PROPERTY_FUNCTION_ID = "functionId";
    public static final String PROPERTY_PACKAGE_NAME = "packageName";
    public static final String PROPERTY_ENABLED = "enabled";
    public static final String PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID =
            "appFunctionStaticMetadataQualifiedId";
    private static final String TAG = "AppSearchAppFunction";
    private static final String RUNTIME_SCHEMA_TYPE_SEPARATOR = "-";

    public AppFunctionRuntimeMetadata(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    /** Returns a per-app runtime metadata schema name, to store all functions for that package. */
    public static String getRuntimeSchemaNameForPackage(@NonNull String pkg) {
        return RUNTIME_SCHEMA_TYPE + RUNTIME_SCHEMA_TYPE_SEPARATOR + Objects.requireNonNull(pkg);
    }

    /** Returns the package name from the runtime metadata schema name. */
    @NonNull
    public static String getPackageNameFromSchema(String metadataSchemaType) {
        String[] split = metadataSchemaType.split(RUNTIME_SCHEMA_TYPE_SEPARATOR);
        if (split.length > 2) {
            throw new IllegalArgumentException(
                    "Invalid schema type: " + metadataSchemaType + " for app function runtime");
        }
        if (split.length < 2) {
            return APP_FUNCTION_INDEXER_PACKAGE;
        }
        return split[1];
    }

    /** Returns the document id for an app function's runtime metadata. */
    public static String getDocumentIdForAppFunction(
            @NonNull String pkg, @NonNull String functionId) {
        return pkg + "/" + functionId;
    }

    /**
     * Different packages have different visibility requirements. To allow for different visibility,
     * we need to have per-package app function schemas.
     *
     * <p>This schema should be set visible to callers from the package owner itself and for callers
     * with {@link android.Manifest.permission#EXECUTE_APP_FUNCTIONS} or {@link
     * android.Manifest.permission#EXECUTE_APP_FUNCTIONS_TRUSTED} permissions.
     *
     * @param packageName The package name to create a schema for.
     */
    @NonNull
    public static AppSearchSchema createAppFunctionRuntimeSchema(@NonNull String packageName) {
        return getAppFunctionRuntimeSchemaBuilder(getRuntimeSchemaNameForPackage(packageName))
                .addParentType(RUNTIME_SCHEMA_TYPE)
                .build();
    }

    /**
     * Creates a parent schema for all app function runtime schemas.
     *
     * <p>This schema should be set visible to the owner itself and for callers with {@link
     * android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@link
     * android.permission.EXECUTE_APP_FUNCTIONS} permissions.
     */
    public static AppSearchSchema createParentAppFunctionRuntimeSchema() {
        return getAppFunctionRuntimeSchemaBuilder(RUNTIME_SCHEMA_TYPE).build();
    }

    private static AppSearchSchema.Builder getAppFunctionRuntimeSchemaBuilder(
            @NonNull String schemaType) {
        return new AppSearchSchema.Builder(schemaType)
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_FUNCTION_ID)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_PACKAGE_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.BooleanPropertyConfig.Builder(PROPERTY_ENABLED)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(
                                        PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setJoinableValueType(
                                        AppSearchSchema.StringPropertyConfig
                                                .JOINABLE_VALUE_TYPE_QUALIFIED_ID)
                                .build());
    }

    /** Returns the function id. This might look like "com.example.message#send_message". */
    @NonNull
    public String getFunctionId() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_FUNCTION_ID));
    }

    /** Returns the package name of the package that owns this function. */
    @NonNull
    public String getPackageName() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_PACKAGE_NAME));
    }

    /**
     * Returns if the function is set to be enabled or not. If not set, the {@link
     * AppFunctionStaticMetadataHelper#STATIC_PROPERTY_ENABLED_BY_DEFAULT} value would be used.
     */
    @Nullable
    public Boolean getEnabled() {
        // We can't use getPropertyBoolean here. getPropertyBoolean returns false instead of null
        // if the value is missing.
        boolean[] enabled = getPropertyBooleanArray(PROPERTY_ENABLED);
        if (enabled == null || enabled.length == 0) {
            return null;
        }
        return enabled[0];
    }

    /** Returns the qualified id linking to the static metadata of the app function. */
    @Nullable
    @VisibleForTesting
    public String getAppFunctionStaticMetadataQualifiedId() {
        return getPropertyString(PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID);
    }

    public static final class Builder extends GenericDocument.Builder<Builder> {
        /**
         * Creates a Builder for a {@link AppFunctionRuntimeMetadata}.
         *
         * @param packageName the name of the package that owns the function.
         * @param functionId the id of the function.
         */
        public Builder(@NonNull String packageName, @NonNull String functionId) {
            super(
                    APP_FUNCTION_RUNTIME_NAMESPACE,
                    getDocumentIdForAppFunction(
                            Objects.requireNonNull(packageName),
                            Objects.requireNonNull(functionId)),
                    getRuntimeSchemaNameForPackage(packageName));
            setPropertyString(PROPERTY_PACKAGE_NAME, packageName);
            setPropertyString(PROPERTY_FUNCTION_ID, functionId);

            // Set qualified id automatically
            setPropertyString(
                    PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID,
                    AppFunctionStaticMetadataHelper.getStaticMetadataQualifiedId(
                            packageName, functionId));
        }

        /**
         * Sets an indicator specifying if the function is enabled or not. This would override the
         * default enabled state in the static metadata ({@link
         * AppFunctionStaticMetadataHelper#STATIC_PROPERTY_ENABLED_BY_DEFAULT}). Sets this to
         * null to clear the override.
         */
        @NonNull
        public Builder setEnabled(@Nullable Boolean enabled) {
            if (enabled == null) {
                setPropertyBoolean(PROPERTY_ENABLED);
            } else {
                setPropertyBoolean(PROPERTY_ENABLED, enabled);
            }
            return this;
        }

        /** Creates the {@link AppFunctionRuntimeMetadata} GenericDocument. */
        @NonNull
        public AppFunctionRuntimeMetadata build() {
            return new AppFunctionRuntimeMetadata(super.build());
        }
    }
}
