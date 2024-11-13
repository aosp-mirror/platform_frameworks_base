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
import android.app.appsearch.util.DocumentIdUtil;

import java.util.Objects;

/**
 * Contains constants and helper related to static metadata represented with {@code
 * com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata}.
 *
 * <p>The constants listed here **must not change** and be kept consistent with the canonical static
 * metadata class.
 *
 * @hide
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public class AppFunctionStaticMetadataHelper {
    public static final String STATIC_SCHEMA_TYPE = "AppFunctionStaticMetadata";
    public static final String STATIC_PROPERTY_ENABLED_BY_DEFAULT = "enabledByDefault";
    public static final String STATIC_PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS =
        "restrictCallersWithExecuteAppFunctions";

    public static final String APP_FUNCTION_STATIC_NAMESPACE = "app_functions";
    public static final String PROPERTY_FUNCTION_ID = "functionId";
    public static final String PROPERTY_PACKAGE_NAME = "packageName";

    // These are constants that has to be kept the same with {@code
    // com.android.server.appsearch.appsindexer.appsearchtypes.AppSearchHelper}.
    public static final String APP_FUNCTION_STATIC_METADATA_DB = "apps-db";
    public static final String APP_FUNCTION_INDEXER_PACKAGE = "android";

    /** Returns a per-app static metadata schema name, to store all functions for that package. */
    public static String getStaticSchemaNameForPackage(@NonNull String pkg) {
        return STATIC_SCHEMA_TYPE + "-" + Objects.requireNonNull(pkg);
    }

    /** Returns the document id for an app function's static metadata. */
    public static String getDocumentIdForAppFunction(
            @NonNull String pkg, @NonNull String functionId) {
        return pkg + "/" + functionId;
    }

    /**
     * Returns the fully qualified Id used in AppSearch for the given package and function id app
     * function static metadata.
     */
    public static String getStaticMetadataQualifiedId(String packageName, String functionId) {
        return DocumentIdUtil.createQualifiedId(
                APP_FUNCTION_INDEXER_PACKAGE,
                APP_FUNCTION_STATIC_METADATA_DB,
                APP_FUNCTION_STATIC_NAMESPACE,
                getDocumentIdForAppFunction(packageName, functionId));
    }
}
