/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.appsearch.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;

import java.util.Set;

/**
 * Manages interaction with {@link FakeIcing} and other components to implement AppSearch
 * functionality.
 */
public final class AppSearchImpl {
    private final Context mContext;
    private final @UserIdInt int mUserId;
    private final FakeIcing mFakeIcing = new FakeIcing();

    AppSearchImpl(@NonNull Context context, @UserIdInt int userId) {
        mContext = context;
        mUserId = userId;
    }

    /**
     * Updates the AppSearch schema for this app.
     *
     * @param callingUid The uid of the app calling AppSearch.
     * @param origSchema The schema to set for this app.
     * @param forceOverride Whether to force-apply the schema even if it is incompatible. Documents
     *     which do not comply with the new schema will be deleted.
     */
    public void setSchema(int callingUid, @NonNull SchemaProto origSchema, boolean forceOverride) {
        // Rewrite schema type names to include the calling app's package and uid.
        String typePrefix = getTypePrefix(callingUid);
        SchemaProto.Builder schemaBuilder = origSchema.toBuilder();
        rewriteSchemaTypes(typePrefix, schemaBuilder);

        // TODO(b/145635424): Save in schema type map
        // TODO(b/145635424): Apply the schema to Icing and report results
    }

    /**
     * Rewrites all types mentioned in the given {@code schemaBuilder} to prepend
     * {@code typePrefix}.
     *
     * @param typePrefix The prefix to add
     * @param schemaBuilder The schema to mutate
     */
    @VisibleForTesting
    void rewriteSchemaTypes(
            @NonNull String typePrefix, @NonNull SchemaProto.Builder schemaBuilder) {
        for (int typeIdx = 0; typeIdx < schemaBuilder.getTypesCount(); typeIdx++) {
            SchemaTypeConfigProto.Builder typeConfigBuilder =
                    schemaBuilder.getTypes(typeIdx).toBuilder();

            // Rewrite SchemaProto.types.schema_type
            String newSchemaType = typePrefix + typeConfigBuilder.getSchemaType();
            typeConfigBuilder.setSchemaType(newSchemaType);

            // Rewrite SchemaProto.types.properties.schema_type
            for (int propertyIdx = 0;
                    propertyIdx < typeConfigBuilder.getPropertiesCount();
                    propertyIdx++) {
                PropertyConfigProto.Builder propertyConfigBuilder =
                        typeConfigBuilder.getProperties(propertyIdx).toBuilder();
                if (!propertyConfigBuilder.getSchemaType().isEmpty()) {
                    String newPropertySchemaType =
                            typePrefix + propertyConfigBuilder.getSchemaType();
                    propertyConfigBuilder.setSchemaType(newPropertySchemaType);
                    typeConfigBuilder.setProperties(propertyIdx, propertyConfigBuilder);
                }
            }

            schemaBuilder.setTypes(typeIdx, typeConfigBuilder);
        }
    }

    /**
     * Adds a document to the AppSearch index.
     *
     * @param callingUid The uid of the app calling AppSearch.
     * @param origDocument The document to index.
     */
    public void putDocument(int callingUid, @NonNull DocumentProto origDocument) {
        // Rewrite the type names to include the app's prefix
        String typePrefix = getTypePrefix(callingUid);
        DocumentProto.Builder documentBuilder = origDocument.toBuilder();
        rewriteDocumentTypes(typePrefix, documentBuilder, /*add=*/ true);
        mFakeIcing.put(documentBuilder.build());
    }

    /**
     * Retrieves a document from the AppSearch index by URI.
     *
     * @param callingUid The uid of the app calling AppSearch.
     * @param uri The URI of the document to get.
     * @return The Document contents, or {@code null} if no such URI exists in the system.
     */
    @Nullable
    public DocumentProto getDocument(int callingUid, @NonNull String uri) {
        String typePrefix = getTypePrefix(callingUid);
        DocumentProto document = mFakeIcing.get(uri);

        // TODO(b/146526096): Since FakeIcing doesn't currently handle namespaces, we perform a
        //  post-filter to make sure we don't return documents we shouldn't. This should be removed
        //  once the real Icing Lib is implemented.
        if (!document.getNamespace().equals(typePrefix)) {
            return null;
        }

        // Rewrite the type names to remove the app's prefix
        DocumentProto.Builder documentBuilder = document.toBuilder();
        rewriteDocumentTypes(typePrefix, documentBuilder, /*add=*/ false);
        return documentBuilder.build();
    }

    /**
     * Executes a query against the AppSearch index and returns results.
     *
     * @param callingUid The uid of the app calling AppSearch.
     * @param searchSpec Defines what and how to search
     * @param resultSpec Defines what results to show
     * @param scoringSpec Defines how to order results
     * @return The results of performing this search  The proto might have no {@code results} if no
     *     documents matched the query.
     */
    @NonNull
    public SearchResultProto query(
            int callingUid,
            @NonNull SearchSpecProto searchSpec,
            @NonNull ResultSpecProto resultSpec,
            @NonNull ScoringSpecProto scoringSpec) {
        String typePrefix = getTypePrefix(callingUid);
        SearchResultProto searchResults = mFakeIcing.query(searchSpec.getQuery());
        if (searchResults.getResultsCount() == 0) {
            return searchResults;
        }
        Set<String> qualifiedSearchFilters = null;
        if (searchSpec.getSchemaTypeFiltersCount() > 0) {
            qualifiedSearchFilters = new ArraySet<>(searchSpec.getSchemaTypeFiltersCount());
            for (String schema : searchSpec.getSchemaTypeFiltersList()) {
                String qualifiedSchema = typePrefix + schema;
                qualifiedSearchFilters.add(qualifiedSchema);
            }
        }
        // Rewrite the type names to remove the app's prefix
        SearchResultProto.Builder searchResultsBuilder = searchResults.toBuilder();
        for (int i = 0; i < searchResultsBuilder.getResultsCount(); i++) {
            if (searchResults.getResults(i).hasDocument()) {
                SearchResultProto.ResultProto.Builder resultBuilder =
                        searchResultsBuilder.getResults(i).toBuilder();

                // TODO(b/145631811): Since FakeIcing doesn't currently handle namespaces, we
                //  perform a post-filter to make sure we don't return documents we shouldn't. This
                //  should be removed once the real Icing Lib is implemented.
                if (!resultBuilder.getDocument().getNamespace().equals(typePrefix)) {
                    searchResultsBuilder.removeResults(i);
                    i--;
                    continue;
                }

                // TODO(b/145631811): Since FakeIcing doesn't currently handle type names, we
                //  perform a post-filter to make sure we don't return documents we shouldn't. This
                //  should be removed once the real Icing Lib is implemented.
                if (qualifiedSearchFilters != null
                        && !qualifiedSearchFilters.contains(
                                resultBuilder.getDocument().getSchema())) {
                    searchResultsBuilder.removeResults(i);
                    i--;
                    continue;
                }

                DocumentProto.Builder documentBuilder = resultBuilder.getDocument().toBuilder();
                rewriteDocumentTypes(typePrefix, documentBuilder, /*add=*/false);
                resultBuilder.setDocument(documentBuilder);
                searchResultsBuilder.setResults(i, resultBuilder);
            }
        }
        return searchResultsBuilder.build();
    }

    /**
     * Deletes all documents owned by the calling app.
     *
     * @param callingUid The uid of the app calling AppSearch.
     */
    public void deleteAll(int callingUid) {
        String namespace = getTypePrefix(callingUid);
        mFakeIcing.deleteByNamespace(namespace);
    }

    /**
     * Rewrites all types mentioned anywhere in {@code documentBuilder} to prepend or remove
     * {@code typePrefix}.
     *
     * @param typePrefix The prefix to add or remove
     * @param documentBuilder The document to mutate
     * @param add Whether to add typePrefix to the types. If {@code false}, typePrefix will be
     *     removed from the types.
     * @throws IllegalArgumentException If {@code add=false} and the document has a type that
     *     doesn't start with {@code typePrefix}.
     */
    @VisibleForTesting
    void rewriteDocumentTypes(
            @NonNull String typePrefix,
            @NonNull DocumentProto.Builder documentBuilder,
            boolean add) {
        // Rewrite the type name to include/remove the app's prefix
        String newSchema;
        if (add) {
            newSchema = typePrefix + documentBuilder.getSchema();
        } else {
            newSchema = removePrefix(typePrefix, documentBuilder.getSchema());
        }
        documentBuilder.setSchema(newSchema);

        // Add/remove namespace. If we ever allow users to set their own namespaces, this will have
        // to change to prepend the prefix instead of setting the whole namespace. We will also have
        // to store the namespaces in a map similar to the type map so we can rewrite queries with
        // empty namespaces.
        if (add) {
            documentBuilder.setNamespace(typePrefix);
        } else if (!documentBuilder.getNamespace().equals(typePrefix)) {
            throw new IllegalStateException(
                    "Unexpected namespace \"" + documentBuilder.getNamespace()
                            + "\" (expected \"" + typePrefix + "\")");
        } else {
            documentBuilder.clearNamespace();
        }

        // Recurse into derived documents
        for (int propertyIdx = 0;
                propertyIdx < documentBuilder.getPropertiesCount();
                propertyIdx++) {
            int documentCount = documentBuilder.getProperties(propertyIdx).getDocumentValuesCount();
            if (documentCount > 0) {
                PropertyProto.Builder propertyBuilder =
                        documentBuilder.getProperties(propertyIdx).toBuilder();
                for (int documentIdx = 0; documentIdx < documentCount; documentIdx++) {
                    DocumentProto.Builder derivedDocumentBuilder =
                            propertyBuilder.getDocumentValues(documentIdx).toBuilder();
                    rewriteDocumentTypes(typePrefix, derivedDocumentBuilder, add);
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }
    }

   /**
     * Returns a type prefix in a format like {@code com.example.package@1000/} or
     * {@code com.example.sharedname:5678@1000/}.
     */
    @NonNull
    private String getTypePrefix(int callingUid) {
        // For regular apps, this call will return the package name. If callingUid is an
        // android:sharedUserId, this value may be another type of name and have a :uid suffix.
        String callingUidName = mContext.getPackageManager().getNameForUid(callingUid);
        if (callingUidName == null) {
            // Not sure how this is possible --- maybe app was uninstalled?
            throw new IllegalStateException("Failed to look up package name for uid " + callingUid);
        }
        return callingUidName + "@" + mUserId + "/";
    }

    @NonNull
    private static String removePrefix(@NonNull String prefix, @NonNull String input) {
        if (!input.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "Input \"" + input + "\" does not start with \"" + prefix + "\"");
        }
        return input.substring(prefix.length());
    }
}
