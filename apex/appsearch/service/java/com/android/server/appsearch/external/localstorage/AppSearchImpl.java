/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.appsearch.external.localstorage.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SchemaToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchResultToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchSpecToProtoConverter;

import com.google.android.icing.IcingSearchEngine;
import com.google.android.icing.proto.DeleteResultProto;
import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.GetAllNamespacesResultProto;
import com.google.android.icing.proto.GetOptimizeInfoResultProto;
import com.google.android.icing.proto.GetResultProto;
import com.google.android.icing.proto.GetSchemaResultProto;
import com.google.android.icing.proto.IcingSearchEngineOptions;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.OptimizeResultProto;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages interaction with the native IcingSearchEngine and other components to implement AppSearch
 * functionality.
 *
 * <p>Never create two instances using the same folder.
 *
 * <p>A single instance of {@link AppSearchImpl} can support all databases. Schemas and documents
 * are physically saved together in {@link IcingSearchEngine}, but logically isolated:
 *
 * <ul>
 *   <li>Rewrite SchemaType in SchemaProto by adding database name prefix and save into SchemaTypes
 *       set in {@link #setSchema}.
 *   <li>Rewrite namespace and SchemaType in DocumentProto by adding database name prefix and save
 *       to namespaces set in {@link #putDocument}.
 *   <li>Remove database name prefix when retrieve documents in {@link #getDocument} and {@link
 *       #query}.
 *   <li>Rewrite filters in {@link SearchSpecProto} to have all namespaces and schema types of the
 *       queried database when user using empty filters in {@link #query}.
 * </ul>
 *
 * <p>Methods in this class belong to two groups, the query group and the mutate group.
 *
 * <ul>
 *   <li>All methods are going to modify global parameters and data in Icing are executed under
 *       WRITE lock to keep thread safety.
 *   <li>All methods are going to access global parameters or query data from Icing are executed
 *       under READ lock to improve query performance.
 * </ul>
 *
 * <p>This class is thread safe.
 *
 * @hide
 */
@WorkerThread
public final class AppSearchImpl {
    private static final String TAG = "AppSearchImpl";

    @VisibleForTesting static final char DATABASE_DELIMITER = '/';

    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_DOC_COUNT = 1000;
    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_BYTES = 1_000_000; // 1MB
    @VisibleForTesting static final int CHECK_OPTIMIZE_INTERVAL = 100;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();

    @GuardedBy("mReadWriteLock")
    private final IcingSearchEngine mIcingSearchEngineLocked;

    @GuardedBy("mReadWriteLock")
    private final VisibilityStore mVisibilityStoreLocked;

    // The map contains schemaTypes and namespaces for all database. All values in the map have
    // the database name prefix.
    // TODO(b/172360376): Check if this can be replaced with an ArrayMap
    @GuardedBy("mReadWriteLock")
    private final Map<String, Set<String>> mSchemaMapLocked = new HashMap<>();

    // TODO(b/172360376): Check if this can be replaced with an ArrayMap
    @GuardedBy("mReadWriteLock")
    private final Map<String, Set<String>> mNamespaceMapLocked = new HashMap<>();

    /**
     * The counter to check when to call {@link #checkForOptimizeLocked(boolean)}. The interval is
     * {@link #CHECK_OPTIMIZE_INTERVAL}.
     */
    @GuardedBy("mReadWriteLock")
    private int mOptimizeIntervalCountLocked = 0;

    /**
     * Creates and initializes an instance of {@link AppSearchImpl} which writes data to the given
     * folder.
     */
    @NonNull
    public static AppSearchImpl create(@NonNull File icingDir) throws AppSearchException {
        Preconditions.checkNotNull(icingDir);
        AppSearchImpl appSearchImpl = new AppSearchImpl(icingDir);
        appSearchImpl.initializeVisibilityStore();
        return appSearchImpl;
    }

    private AppSearchImpl(@NonNull File icingDir) throws AppSearchException {
        boolean isReset = false;
        mReadWriteLock.writeLock().lock();

        try {
            // We synchronize here because we don't want to call IcingSearchEngine.initialize() more
            // than once. It's unnecessary and can be a costly operation.
            IcingSearchEngineOptions options =
                    IcingSearchEngineOptions.newBuilder()
                            .setBaseDir(icingDir.getAbsolutePath())
                            .build();
            mIcingSearchEngineLocked = new IcingSearchEngine(options);

            InitializeResultProto initializeResultProto = mIcingSearchEngineLocked.initialize();
            SchemaProto schemaProto = null;
            GetAllNamespacesResultProto getAllNamespacesResultProto = null;
            try {
                checkSuccess(initializeResultProto.getStatus());
                schemaProto = getSchemaProtoLocked();
                getAllNamespacesResultProto = mIcingSearchEngineLocked.getAllNamespaces();
                checkSuccess(getAllNamespacesResultProto.getStatus());
            } catch (AppSearchException e) {
                Log.w(TAG, "Error initializing, resetting IcingSearchEngine.", e);
                // Some error. Reset and see if it fixes it.
                reset();
                isReset = true;
            }

            // Populate schema map
            for (SchemaTypeConfigProto schema : schemaProto.getTypesList()) {
                String qualifiedSchemaType = schema.getSchemaType();
                addToMap(
                        mSchemaMapLocked,
                        getDatabaseName(qualifiedSchemaType),
                        qualifiedSchemaType);
            }

            // Populate namespace map
            for (String qualifiedNamespace : getAllNamespacesResultProto.getNamespacesList()) {
                addToMap(
                        mNamespaceMapLocked,
                        getDatabaseName(qualifiedNamespace),
                        qualifiedNamespace);
            }

            // TODO(b/155939114): It's possible to optimize after init, which would reduce the time
            //   to when we're able to serve queries. Consider moving this optimize call out.
            if (!isReset) {
                checkForOptimizeLocked(/* force= */ true);
            }

            mVisibilityStoreLocked = new VisibilityStore(this);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Initialize the visibility store in AppSearchImpl.
     *
     * @throws AppSearchException on IcingSearchEngine error.
     */
    void initializeVisibilityStore() throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            mVisibilityStoreLocked.initialize();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Updates the AppSearch schema for this app.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The name of the database where this schema lives.
     * @param schemas Schemas to set for this app.
     * @param forceOverride Whether to force-apply the schema even if it is incompatible. Documents
     *     which do not comply with the new schema will be deleted.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void setSchema(
            @NonNull String databaseName,
            @NonNull Set<AppSearchSchema> schemas,
            boolean forceOverride)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            SchemaProto.Builder existingSchemaBuilder = getSchemaProtoLocked().toBuilder();

            SchemaProto.Builder newSchemaBuilder = SchemaProto.newBuilder();
            for (AppSearchSchema schema : schemas) {
                SchemaTypeConfigProto schemaTypeProto = SchemaToProtoConverter.convert(schema);
                newSchemaBuilder.addTypes(schemaTypeProto);
            }

            // Combine the existing schema (which may have types from other databases) with this
            // database's new schema. Modifies the existingSchemaBuilder.
            RewrittenSchemaResults rewrittenSchemaResults =
                    rewriteSchema(databaseName, existingSchemaBuilder, newSchemaBuilder.build());

            // Apply schema
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(
                            existingSchemaBuilder.build(), forceOverride);

            // Determine whether it succeeded.
            try {
                checkSuccess(setSchemaResultProto.getStatus());
            } catch (AppSearchException e) {
                // Improve the error message by merging in information about incompatible types.
                if (setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                        || setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0) {
                    String newMessage =
                            e.getMessage()
                                    + "\n  Deleted types: "
                                    + setSchemaResultProto.getDeletedSchemaTypesList()
                                    + "\n  Incompatible types: "
                                    + setSchemaResultProto.getIncompatibleSchemaTypesList();
                    throw new AppSearchException(e.getResultCode(), newMessage, e.getCause());
                } else {
                    throw e;
                }
            }

            // Update derived data structures.
            mSchemaMapLocked.put(databaseName, rewrittenSchemaResults.mRewrittenQualifiedTypes);
            mVisibilityStoreLocked.updateSchemas(
                    databaseName, rewrittenSchemaResults.mDeletedQualifiedTypes);

            // Determine whether to schedule an immediate optimize.
            if (setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                    || (setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0
                            && forceOverride)) {
                // Any existing schemas which is not in 'schemas' will be deleted, and all
                // documents of these types were also deleted. And so well if we force override
                // incompatible schemas.
                checkForOptimizeLocked(/* force= */ true);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Update the visibility settings for this app.
     *
     * <p>This method belongs to the mutate group
     *
     * @param databaseName The name of the database where the visibility settings will apply.
     * @param schemasHiddenFromPlatformSurfaces Schemas that should be hidden from platform surfaces
     * @throws AppSearchException on IcingSearchEngine error
     */
    public void setVisibility(
            @NonNull String databaseName, @NonNull Set<String> schemasHiddenFromPlatformSurfaces)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            String databasePrefix = getDatabasePrefix(databaseName);
            Set<String> qualifiedSchemasHiddenFromPlatformSurface =
                    new ArraySet<>(schemasHiddenFromPlatformSurfaces.size());
            for (String schema : schemasHiddenFromPlatformSurfaces) {
                Set<String> existingSchemas = mSchemaMapLocked.get(databaseName);
                if (existingSchemas == null || !existingSchemas.contains(databasePrefix + schema)) {
                    throw new AppSearchException(
                            AppSearchResult.RESULT_NOT_FOUND,
                            "Unknown schema(s): "
                                    + schemasHiddenFromPlatformSurfaces
                                    + " provided during setVisibility.");
                }
                qualifiedSchemasHiddenFromPlatformSurface.add(databasePrefix + schema);
            }
            mVisibilityStoreLocked.setVisibility(
                    databaseName, qualifiedSchemasHiddenFromPlatformSurface);
        } finally {
            mReadWriteLock.writeLock().lock();
        }
    }

    /**
     * Adds a document to the AppSearch index.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName this document resides in.
     * @param document The document to index.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void putDocument(@NonNull String databaseName, @NonNull GenericDocument document)
            throws AppSearchException {
        DocumentProto.Builder documentBuilder =
                GenericDocumentToProtoConverter.convert(document).toBuilder();
        addPrefixToDocument(documentBuilder, getDatabasePrefix(databaseName));

        PutResultProto putResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            putResultProto = mIcingSearchEngineLocked.put(documentBuilder.build());
            addToMap(mNamespaceMapLocked, databaseName, documentBuilder.getNamespace());
            // The existing documents with same URI will be deleted, so there maybe some resources
            // could be released after optimize().
            checkForOptimizeLocked(/* force= */ false);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(putResultProto.getStatus());
    }

    /**
     * Retrieves a document from the AppSearch index by URI.
     *
     * <p>This method belongs to query group.
     *
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param uri The URI of the document to get.
     * @return The Document contents
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public GenericDocument getDocument(
            @NonNull String databaseName, @NonNull String namespace, @NonNull String uri)
            throws AppSearchException {
        GetResultProto getResultProto;
        mReadWriteLock.readLock().lock();
        try {
            getResultProto =
                    mIcingSearchEngineLocked.get(getDatabasePrefix(databaseName) + namespace, uri);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
        checkSuccess(getResultProto.getStatus());

        DocumentProto.Builder documentBuilder = getResultProto.getDocument().toBuilder();
        removeDatabasesFromDocument(documentBuilder);
        return GenericDocumentToProtoConverter.convert(documentBuilder.build());
    }

    /**
     * Executes a query against the AppSearch index and returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param databaseName The databaseName this query for.
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage query(
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            return doQueryLocked(Collections.singleton(databaseName), queryExpression, searchSpec);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Executes a global query, i.e. over all permitted databases, against the AppSearch index and
     * returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage globalQuery(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        // TODO(b/169883602): Check if the platform is querying us at a higher level. At this
        //  point, we should add all platform-surfaceable schemas assuming the querier has been
        //  verified.
        mReadWriteLock.readLock().lock();
        try {
            // We use the mNamespaceMap.keySet here because it's the smaller set of valid databases
            // that could exist.
            return doQueryLocked(mNamespaceMapLocked.keySet(), queryExpression, searchSpec);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private SearchResultPage doQueryLocked(
            @NonNull Set<String> databases,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        SearchSpecProto searchSpecProto = SearchSpecToProtoConverter.toSearchSpecProto(searchSpec);
        SearchSpecProto.Builder searchSpecBuilder =
                searchSpecProto.toBuilder().setQuery(queryExpression);

        ResultSpecProto resultSpec = SearchSpecToProtoConverter.toResultSpecProto(searchSpec);
        ScoringSpecProto scoringSpec = SearchSpecToProtoConverter.toScoringSpecProto(searchSpec);
        SearchResultProto searchResultProto;

        // rewriteSearchSpecForDatabases will return false if none of the databases that the
        // client is trying to search on exist, so we can return an empty SearchResult and skip
        // sending request to Icing.
        // We use the mNamespaceMap.keySet here because it's the smaller set of valid databases
        // that could exist.
        if (!rewriteSearchSpecForDatabasesLocked(searchSpecBuilder, databases)) {
            return new SearchResultPage(Bundle.EMPTY);
        }
        searchResultProto =
                mIcingSearchEngineLocked.search(searchSpecBuilder.build(), scoringSpec, resultSpec);
        checkSuccess(searchResultProto.getStatus());

        return rewriteSearchResultProto(searchResultProto);
    }

    /**
     * Fetches the next page of results of a previously executed query. Results can be empty if
     * next-page token is invalid or all pages have been returned.
     *
     * <p>This method belongs to query group.
     *
     * @param nextPageToken The token of pre-loaded results of previously executed query.
     * @return The next page of results of previously executed query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage getNextPage(long nextPageToken) throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            SearchResultProto searchResultProto =
                    mIcingSearchEngineLocked.getNextPage(nextPageToken);
            checkSuccess(searchResultProto.getStatus());
            return rewriteSearchResultProto(searchResultProto);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Invalidates the next-page token so that no more results of the related query can be returned.
     *
     * <p>This method belongs to query group.
     *
     * @param nextPageToken The token of pre-loaded results of previously executed query to be
     *     Invalidated.
     */
    public void invalidateNextPageToken(long nextPageToken) {
        mReadWriteLock.readLock().lock();
        try {
            mIcingSearchEngineLocked.invalidateNextPageToken(nextPageToken);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Removes the given document by URI.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName the document is in.
     * @param namespace Namespace of the document to remove.
     * @param uri URI of the document to remove.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void remove(@NonNull String databaseName, @NonNull String namespace, @NonNull String uri)
            throws AppSearchException {
        String qualifiedNamespace = getDatabasePrefix(databaseName) + namespace;
        DeleteResultProto deleteResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            deleteResultProto = mIcingSearchEngineLocked.delete(qualifiedNamespace, uri);
            checkForOptimizeLocked(/* force= */ false);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(deleteResultProto.getStatus());
    }

    /**
     * Removes documents by given query.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName the document is in.
     * @param queryExpression Query String to search.
     * @param searchSpec Defines what and how to remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByQuery(
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        SearchSpecProto searchSpecProto = SearchSpecToProtoConverter.toSearchSpecProto(searchSpec);
        SearchSpecProto.Builder searchSpecBuilder =
                searchSpecProto.toBuilder().setQuery(queryExpression);
        DeleteResultProto deleteResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            // Only rewrite SearchSpec for non empty database.
            // rewriteSearchSpecForNonEmptyDatabase will return false for empty database, we
            // should skip sending request to Icing and return in here.
            if (!rewriteSearchSpecForDatabasesLocked(
                    searchSpecBuilder, Collections.singleton(databaseName))) {
                return;
            }
            deleteResultProto = mIcingSearchEngineLocked.deleteByQuery(searchSpecBuilder.build());
            checkForOptimizeLocked(/* force= */ true);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        // It seems that the caller wants to get success if the data matching the query is not in
        // the DB because it was not there or was successfully deleted.
        checkCodeOneOf(
                deleteResultProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
    }

    /**
     * Clears documents and schema across all databaseNames.
     *
     * <p>This method belongs to mutate group.
     *
     * @throws AppSearchException on IcingSearchEngine error.
     */
    private void reset() throws AppSearchException {
        ResetResultProto resetResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            resetResultProto = mIcingSearchEngineLocked.reset();
            mOptimizeIntervalCountLocked = 0;
            mSchemaMapLocked.clear();
            mNamespaceMapLocked.clear();

            // Must be called after everything else since VisibilityStore may repopulate
            // IcingSearchEngine with an initial schema.
            mVisibilityStoreLocked.handleReset();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(resetResultProto.getStatus());
    }

    /** Wrapper around schema changes */
    @VisibleForTesting
    static class RewrittenSchemaResults {
        // Any database-qualified types that used to exist in the schema, but are deleted in the
        // new one.
        final Set<String> mDeletedQualifiedTypes = new ArraySet<>();

        // Database-qualified types that were part of the new schema.
        final Set<String> mRewrittenQualifiedTypes = new ArraySet<>();
    }

    /**
     * Rewrites all types mentioned in the given {@code newSchema} to prepend {@code prefix}.
     * Rewritten types will be added to the {@code existingSchema}.
     *
     * @param databaseName The name of the database where this schema lives.
     * @param existingSchema A schema that may contain existing types from across all database
     *     instances. Will be mutated to contain the properly rewritten schema types from {@code
     *     newSchema}.
     * @param newSchema Schema with types to add to the {@code existingSchema}.
     * @return a RewrittenSchemaResults contains all qualified schema type names in the given
     *     database as well as a set of schema types that were deleted from the database.
     */
    @VisibleForTesting
    static RewrittenSchemaResults rewriteSchema(
            @NonNull String databaseName,
            @NonNull SchemaProto.Builder existingSchema,
            @NonNull SchemaProto newSchema)
            throws AppSearchException {
        String prefix = getDatabasePrefix(databaseName);
        HashMap<String, SchemaTypeConfigProto> newTypesToProto = new HashMap<>();
        // Rewrite the schema type to include the typePrefix.
        for (int typeIdx = 0; typeIdx < newSchema.getTypesCount(); typeIdx++) {
            SchemaTypeConfigProto.Builder typeConfigBuilder =
                    newSchema.getTypes(typeIdx).toBuilder();

            // Rewrite SchemaProto.types.schema_type
            String newSchemaType = prefix + typeConfigBuilder.getSchemaType();
            typeConfigBuilder.setSchemaType(newSchemaType);

            // Rewrite SchemaProto.types.properties.schema_type
            for (int propertyIdx = 0;
                    propertyIdx < typeConfigBuilder.getPropertiesCount();
                    propertyIdx++) {
                PropertyConfigProto.Builder propertyConfigBuilder =
                        typeConfigBuilder.getProperties(propertyIdx).toBuilder();
                if (!propertyConfigBuilder.getSchemaType().isEmpty()) {
                    String newPropertySchemaType = prefix + propertyConfigBuilder.getSchemaType();
                    propertyConfigBuilder.setSchemaType(newPropertySchemaType);
                    typeConfigBuilder.setProperties(propertyIdx, propertyConfigBuilder);
                }
            }

            newTypesToProto.put(newSchemaType, typeConfigBuilder.build());
        }

        // newTypesToProto is modified below, so we need a copy first
        RewrittenSchemaResults rewrittenSchemaResults = new RewrittenSchemaResults();
        rewrittenSchemaResults.mRewrittenQualifiedTypes.addAll(newTypesToProto.keySet());

        // Combine the existing schema (which may have types from other databases) with this
        // database's new schema. Modifies the existingSchemaBuilder.
        // Check if we need to replace any old schema types with the new ones.
        for (int i = 0; i < existingSchema.getTypesCount(); i++) {
            String schemaType = existingSchema.getTypes(i).getSchemaType();
            SchemaTypeConfigProto newProto = newTypesToProto.remove(schemaType);
            if (newProto != null) {
                // Replacement
                existingSchema.setTypes(i, newProto);
            } else if (databaseName.equals(getDatabaseName(schemaType))) {
                // All types existing before but not in newSchema should be removed.
                existingSchema.removeTypes(i);
                --i;
                rewrittenSchemaResults.mDeletedQualifiedTypes.add(schemaType);
            }
        }
        // We've been removing existing types from newTypesToProto, so everything that remains is
        // new.
        existingSchema.addAllTypes(newTypesToProto.values());

        return rewrittenSchemaResults;
    }

    /**
     * Prepends {@code prefix} to all types and namespaces mentioned anywhere in {@code
     * documentBuilder}.
     *
     * @param documentBuilder The document to mutate
     * @param prefix The prefix to add
     */
    @VisibleForTesting
    static void addPrefixToDocument(
            @NonNull DocumentProto.Builder documentBuilder, @NonNull String prefix) {
        // Rewrite the type name to include/remove the prefix.
        String newSchema = prefix + documentBuilder.getSchema();
        documentBuilder.setSchema(newSchema);

        // Rewrite the namespace to include/remove the prefix.
        documentBuilder.setNamespace(prefix + documentBuilder.getNamespace());

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
                    addPrefixToDocument(derivedDocumentBuilder, prefix);
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }
    }

    /**
     * Removes any database names from types and namespaces mentioned anywhere in {@code
     * documentBuilder}.
     *
     * @param documentBuilder The document to mutate
     */
    @VisibleForTesting
    static void removeDatabasesFromDocument(@NonNull DocumentProto.Builder documentBuilder)
            throws AppSearchException {
        // Rewrite the type name and namespace to remove the prefix.
        documentBuilder.setSchema(removeDatabasePrefix(documentBuilder.getSchema()));
        documentBuilder.setNamespace(removeDatabasePrefix(documentBuilder.getNamespace()));

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
                    removeDatabasesFromDocument(derivedDocumentBuilder);
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }
    }

    /**
     * Rewrites the schemaTypeFilters and namespacesFilters that exist in {@code databaseNames}.
     *
     * <p>If the searchSpec has empty filter lists, all existing databases from {@code
     * databaseNames} will be added.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @return false if none of the requested databases exist.
     */
    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    boolean rewriteSearchSpecForDatabasesLocked(
            @NonNull SearchSpecProto.Builder searchSpecBuilder,
            @NonNull Set<String> databaseNames) {
        // Create a copy since retainAll() modifies the original set.
        Set<String> existingDatabases = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingDatabases.retainAll(databaseNames);

        if (existingDatabases.isEmpty()) {
            // None of the databases exist, empty query.
            return false;
        }

        // Cache the schema type filters and namespaces before clearing everything.
        List<String> schemaTypeFilters = searchSpecBuilder.getSchemaTypeFiltersList();
        searchSpecBuilder.clearSchemaTypeFilters();

        List<String> namespaceFilters = searchSpecBuilder.getNamespaceFiltersList();
        searchSpecBuilder.clearNamespaceFilters();

        // Rewrite filters to include a database prefix.
        for (String databaseName : existingDatabases) {
            Set<String> existingSchemaTypes = mSchemaMapLocked.get(databaseName);
            String databaseNamePrefix = getDatabasePrefix(databaseName);
            if (schemaTypeFilters.isEmpty()) {
                // Include all schema types
                searchSpecBuilder.addAllSchemaTypeFilters(existingSchemaTypes);
            } else {
                // Qualify the given schema types
                for (int i = 0; i < schemaTypeFilters.size(); i++) {
                    String qualifiedType = databaseNamePrefix + schemaTypeFilters.get(i);
                    if (existingSchemaTypes.contains(qualifiedType)) {
                        searchSpecBuilder.addSchemaTypeFilters(qualifiedType);
                    }
                }
            }

            Set<String> existingNamespaces = mNamespaceMapLocked.get(databaseName);
            if (namespaceFilters.isEmpty()) {
                // Include all namespaces
                searchSpecBuilder.addAllNamespaceFilters(existingNamespaces);
            } else {
                // Qualify the given namespaces.
                for (int i = 0; i < namespaceFilters.size(); i++) {
                    String qualifiedNamespace = databaseNamePrefix + namespaceFilters.get(i);
                    if (existingNamespaces.contains(qualifiedNamespace)) {
                        searchSpecBuilder.addNamespaceFilters(qualifiedNamespace);
                    }
                }
            }
        }

        return true;
    }

    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    SchemaProto getSchemaProtoLocked() throws AppSearchException {
        GetSchemaResultProto schemaProto = mIcingSearchEngineLocked.getSchema();
        // TODO(b/161935693) check GetSchemaResultProto is success or not. Call reset() if it's not.
        // TODO(b/161935693) only allow GetSchemaResultProto NOT_FOUND on first run
        checkCodeOneOf(schemaProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        return schemaProto.getSchema();
    }

    /** Returns true if {@code databaseName} has a {@code schemaType} */
    @GuardedBy("mReadWriteLock")
    boolean hasSchemaTypeLocked(@NonNull String databaseName, @NonNull String schemaType) {
        Preconditions.checkNotNull(databaseName);
        Preconditions.checkNotNull(schemaType);

        Set<String> schemaTypes = mSchemaMapLocked.get(databaseName);
        if (schemaTypes == null) {
            return false;
        }

        return schemaTypes.contains(getDatabasePrefix(databaseName) + schemaType);
    }

    /** Returns a set of all databases AppSearchImpl knows about. */
    @GuardedBy("mReadWriteLock")
    @NonNull
    Set<String> getDatabasesLocked() {
        return mSchemaMapLocked.keySet();
    }

    @NonNull
    private static String getDatabasePrefix(@NonNull String databaseName) {
        // TODO(b/170370381): Reconsider the way we separate database names for security reasons.
        return databaseName + DATABASE_DELIMITER;
    }

    @NonNull
    private static String removeDatabasePrefix(@NonNull String prefixedString)
            throws AppSearchException {
        int delimiterIndex;
        if ((delimiterIndex = prefixedString.indexOf(DATABASE_DELIMITER)) != -1) {
            // Add 1 to include the char size of the DATABASE_DELIMITER
            return prefixedString.substring(delimiterIndex + 1);
        }
        throw new AppSearchException(
                AppSearchResult.RESULT_UNKNOWN_ERROR,
                "The prefixed value doesn't contains a valid database name.");
    }

    @NonNull
    private static String getDatabaseName(@NonNull String prefixedValue) throws AppSearchException {
        int delimiterIndex = prefixedValue.indexOf(DATABASE_DELIMITER);
        if (delimiterIndex == -1) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_UNKNOWN_ERROR,
                    "The databaseName prefixed value doesn't contains a valid database name.");
        }
        return prefixedValue.substring(0, delimiterIndex);
    }

    private static void addToMap(
            Map<String, Set<String>> map, String databaseName, String prefixedValue) {
        Set<String> values = map.get(databaseName);
        if (values == null) {
            values = new ArraySet<>();
            map.put(databaseName, values);
        }
        values.add(prefixedValue);
    }

    /**
     * Checks the given status code and throws an {@link AppSearchException} if code is an error.
     *
     * @throws AppSearchException on error codes.
     */
    private static void checkSuccess(StatusProto statusProto) throws AppSearchException {
        checkCodeOneOf(statusProto, StatusProto.Code.OK);
    }

    /**
     * Checks the given status code is one of the provided codes, and throws an {@link
     * AppSearchException} if it is not.
     */
    private static void checkCodeOneOf(StatusProto statusProto, StatusProto.Code... codes)
            throws AppSearchException {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == statusProto.getCode()) {
                // Everything's good
                return;
            }
        }

        if (statusProto.getCode() == StatusProto.Code.WARNING_DATA_LOSS) {
            // TODO: May want to propagate WARNING_DATA_LOSS up to AppSearchSession so they can
            //  choose to log the error or potentially pass it on to clients.
            Log.w(TAG, "Encountered WARNING_DATA_LOSS: " + statusProto.getMessage());
            return;
        }

        throw statusProtoToAppSearchException(statusProto);
    }

    /**
     * Checks whether {@link IcingSearchEngine#optimize()} should be called to release resources.
     *
     * <p>This method should be only called in mutate methods and get the WRITE lock to keep thread
     * safety.
     *
     * <p>{@link IcingSearchEngine#optimize()} should be called only if {@link
     * GetOptimizeInfoResultProto} shows there is enough resources could be released.
     *
     * <p>{@link IcingSearchEngine#getOptimizeInfo()} should be called once per {@link
     * #CHECK_OPTIMIZE_INTERVAL} of remove executions.
     *
     * @param force whether we should directly call {@link IcingSearchEngine#getOptimizeInfo()}.
     */
    @GuardedBy("mReadWriteLock")
    private void checkForOptimizeLocked(boolean force) throws AppSearchException {
        ++mOptimizeIntervalCountLocked;
        if (force || mOptimizeIntervalCountLocked >= CHECK_OPTIMIZE_INTERVAL) {
            mOptimizeIntervalCountLocked = 0;
            GetOptimizeInfoResultProto optimizeInfo = getOptimizeInfoResultLocked();
            checkSuccess(optimizeInfo.getStatus());
            // Second threshold, decide when to call optimize().
            if (optimizeInfo.getOptimizableDocs() >= OPTIMIZE_THRESHOLD_DOC_COUNT
                    || optimizeInfo.getEstimatedOptimizableBytes() >= OPTIMIZE_THRESHOLD_BYTES) {
                // TODO(b/155939114): call optimize in the same thread will slow down api calls
                //  significantly. Move this call to background.
                OptimizeResultProto optimizeResultProto = mIcingSearchEngineLocked.optimize();
                checkSuccess(optimizeResultProto.getStatus());
            }
            // TODO(b/147699081): Return OptimizeResultProto & log lost data detail once we add
            //  a field to indicate lost_schema and lost_documents in OptimizeResultProto.
            //  go/icing-library-apis.
        }
    }

    /** Remove the rewritten schema types from any result documents. */
    private static SearchResultPage rewriteSearchResultProto(
            @NonNull SearchResultProto searchResultProto) throws AppSearchException {
        SearchResultProto.Builder resultsBuilder = searchResultProto.toBuilder();
        for (int i = 0; i < searchResultProto.getResultsCount(); i++) {
            if (searchResultProto.getResults(i).hasDocument()) {
                SearchResultProto.ResultProto.Builder resultBuilder =
                        searchResultProto.getResults(i).toBuilder();
                DocumentProto.Builder documentBuilder = resultBuilder.getDocument().toBuilder();
                removeDatabasesFromDocument(documentBuilder);
                resultBuilder.setDocument(documentBuilder);
                resultsBuilder.setResults(i, resultBuilder);
            }
        }
        return SearchResultToProtoConverter.convertToSearchResultPage(resultsBuilder);
    }

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    GetOptimizeInfoResultProto getOptimizeInfoResultLocked() {
        return mIcingSearchEngineLocked.getOptimizeInfo();
    }

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    VisibilityStore getVisibilityStoreLocked() {
        return mVisibilityStoreLocked;
    }

    /**
     * Converts an erroneous status code to an AppSearchException. Callers should ensure that the
     * status code is not OK or WARNING_DATA_LOSS.
     *
     * @param statusProto StatusProto with error code and message to translate into
     *     AppSearchException.
     * @return AppSearchException with the parallel error code.
     */
    private static AppSearchException statusProtoToAppSearchException(StatusProto statusProto) {
        switch (statusProto.getCode()) {
            case INVALID_ARGUMENT:
                return new AppSearchException(
                        AppSearchResult.RESULT_INVALID_ARGUMENT, statusProto.getMessage());
            case NOT_FOUND:
                return new AppSearchException(
                        AppSearchResult.RESULT_NOT_FOUND, statusProto.getMessage());
            case FAILED_PRECONDITION:
                // Fallthrough
            case ABORTED:
                // Fallthrough
            case INTERNAL:
                return new AppSearchException(
                        AppSearchResult.RESULT_INTERNAL_ERROR, statusProto.getMessage());
            case OUT_OF_SPACE:
                return new AppSearchException(
                        AppSearchResult.RESULT_OUT_OF_SPACE, statusProto.getMessage());
            default:
                // Some unknown/unsupported error
                return new AppSearchException(
                        AppSearchResult.RESULT_UNKNOWN_ERROR,
                        "Unknown IcingSearchEngine status code: " + statusProto.getCode());
        }
    }
}
