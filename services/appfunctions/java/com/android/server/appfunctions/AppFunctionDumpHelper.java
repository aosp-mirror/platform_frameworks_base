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

package com.android.server.appfunctions;

import static android.app.appfunctions.AppFunctionRuntimeMetadata.PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.APP_FUNCTION_INDEXER_PACKAGE;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.PROPERTY_FUNCTION_ID;

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.RequiresPermission;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.util.IndentingPrintWriter;
import android.app.appfunctions.AppFunctionRuntimeMetadata;
import android.app.appfunctions.AppFunctionStaticMetadataHelper;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.JoinSpec;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class AppFunctionDumpHelper {
    private static final String TAG = AppFunctionDumpHelper.class.getSimpleName();

    private AppFunctionDumpHelper() {}

    /** Dumps the state of all app functions for all users. */
    @BinderThread
    @RequiresPermission(
            anyOf = {Manifest.permission.CREATE_USERS, Manifest.permission.MANAGE_USERS})
    public static void dumpAppFunctionsState(@NonNull Context context, @NonNull PrintWriter w) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            w.println("Couldn't retrieve UserManager.");
            return;
        }

        IndentingPrintWriter pw = new IndentingPrintWriter(w);

        List<UserInfo> userInfos = userManager.getAliveUsers();
        for (UserInfo userInfo : userInfos) {
            pw.println(
                    "AppFunction state for user " + userInfo.getUserHandle().getIdentifier() + ":");
            pw.increaseIndent();
            dumpAppFunctionsStateForUser(
                    context.createContextAsUser(userInfo.getUserHandle(), /* flags= */ 0), pw);
            pw.decreaseIndent();
        }
    }

    private static void dumpAppFunctionsStateForUser(
            @NonNull Context context, @NonNull IndentingPrintWriter pw) {
        AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            pw.println("Couldn't retrieve AppSearchManager.");
            return;
        }

        try (FutureGlobalSearchSession searchSession =
                new FutureGlobalSearchSession(appSearchManager, Runnable::run)) {
            pw.println();

            try (FutureSearchResults futureSearchResults =
                    searchSession.search("", buildAppFunctionMetadataSearchSpec()).get(); ) {
                List<SearchResult> searchResultsList;
                do {
                    searchResultsList = futureSearchResults.getNextPage().get();
                    for (SearchResult searchResult : searchResultsList) {
                        dumpAppFunctionMetadata(pw, searchResult);
                    }
                } while (!searchResultsList.isEmpty());
            }

        } catch (Exception e) {
            pw.println("Failed to dump AppFunction state: " + e);
        }
    }

    private static SearchSpec buildAppFunctionMetadataSearchSpec() {
        SearchSpec runtimeMetadataSearchSpec =
                new SearchSpec.Builder()
                        .addFilterPackageNames(APP_FUNCTION_INDEXER_PACKAGE)
                        .addFilterSchemas(AppFunctionRuntimeMetadata.RUNTIME_SCHEMA_TYPE)
                        .build();
        JoinSpec joinSpec =
                new JoinSpec.Builder(PROPERTY_APP_FUNCTION_STATIC_METADATA_QUALIFIED_ID)
                        .setNestedSearch(/* queryExpression= */ "", runtimeMetadataSearchSpec)
                        .build();

        return new SearchSpec.Builder()
                .addFilterPackageNames(APP_FUNCTION_INDEXER_PACKAGE)
                .addFilterSchemas(AppFunctionStaticMetadataHelper.STATIC_SCHEMA_TYPE)
                .setJoinSpec(joinSpec)
                .build();
    }

    private static void dumpAppFunctionMetadata(
            IndentingPrintWriter pw, SearchResult joinedSearchResult) {
        pw.println(
                "AppFunctionMetadata for: "
                        + joinedSearchResult
                                .getGenericDocument()
                                .getPropertyString(PROPERTY_FUNCTION_ID));
        pw.increaseIndent();

        pw.println("Static Metadata:");
        pw.increaseIndent();
        writeGenericDocumentProperties(pw, joinedSearchResult.getGenericDocument());
        pw.decreaseIndent();

        pw.println("Runtime Metadata:");
        pw.increaseIndent();
        if (!joinedSearchResult.getJoinedResults().isEmpty()) {
            writeGenericDocumentProperties(
                    pw, joinedSearchResult.getJoinedResults().getFirst().getGenericDocument());
        } else {
            pw.println("No runtime metadata found.");
        }
        pw.decreaseIndent();

        pw.decreaseIndent();
    }

    private static void writeGenericDocumentProperties(
            IndentingPrintWriter pw, GenericDocument genericDocument) {
        Set<String> propertyNames = genericDocument.getPropertyNames();
        pw.println("{");
        pw.increaseIndent();
        for (String propertyName : propertyNames) {
            Object propertyValue = genericDocument.getProperty(propertyName);
            pw.print("\"" + propertyName + "\"" + ": [");

            if (propertyValue instanceof GenericDocument[]) {
                GenericDocument[] documentValues = (GenericDocument[]) propertyValue;
                for (int i = 0; i < documentValues.length; i++) {
                    GenericDocument documentValue = documentValues[i];
                    writeGenericDocumentProperties(pw, documentValue);
                    if (i != documentValues.length - 1) {
                        pw.print(", ");
                    }
                    pw.println();
                }
            } else {
                int propertyArrLength = Array.getLength(propertyValue);
                for (int i = 0; i < propertyArrLength; i++) {
                    Object propertyElement = Array.get(propertyValue, i);
                    if (propertyElement instanceof String) {
                        pw.print("\"" + propertyElement + "\"");
                    } else if (propertyElement instanceof byte[]) {
                        pw.print(Arrays.toString((byte[]) propertyElement));
                    } else if (propertyElement != null) {
                        pw.print(propertyElement.toString());
                    }
                    if (i != propertyArrLength - 1) {
                        pw.print(", ");
                    }
                }
            }
            pw.println("]");
        }
        pw.decreaseIndent();
        pw.println("}");
    }
}
