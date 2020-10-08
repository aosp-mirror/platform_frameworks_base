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

package com.android.server.appsearch.external.localbackend;

import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import com.android.internal.util.Preconditions;

import com.google.android.icing.IcingSearchEngine;
import com.google.android.icing.proto.DeleteByNamespaceResultProto;
import com.google.android.icing.proto.DeleteBySchemaTypeResultProto;
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
import java.util.HashMap;
import java.util.HashSet;
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
 * <ul>
 *      <li>Rewrite SchemaType in SchemaProto by adding database name prefix and save into
 *          SchemaTypes set in {@link #setSchema}.
 *      <li>Rewrite namespace and SchemaType in DocumentProto by adding database name prefix and
 *          save to namespaces set in {@link #putDocument}.
 *      <li>Remove database name prefix when retrieve documents in {@link #getDocument} and
 *          {@link #query}.
 *      <li>Rewrite filters in {@link SearchSpecProto} to have all namespaces and schema types of
 *          the queried database when user using empty filters in {@link #query}.
 * </ul>
 *
 * <p>Methods in this class belong to two groups, the query group and the mutate group.
 * <ul>
 *     <li>All methods are going to modify global parameters and data in Icing are executed under
 *         WRITE lock to keep thread safety.
 *     <li>All methods are going to access global parameters or query data from Icing are executed
 *         under READ lock to improve query performance.
 * </ul>
 *
 * <p>This class is thread safe.
 * @hide
 */

@WorkerThread
public final class AppSearchImpl {
    private static final String TAG = "AppSearchImpl";

    @VisibleForTesting
    static final int OPTIMIZE_THRESHOLD_DOC_COUNT = 1000;
    @VisibleForTesting
    static final int OPTIMIZE_THRESHOLD_BYTES = 1_000_000; // 1MB
    @VisibleForTesting
    static final int CHECK_OPTIMIZE_INTERVAL = 100;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final IcingSearchEngine mIcingSearchEngine;

    // The map contains schemaTypes and namespaces for all database. All values in the map have
    // been already added database name prefix.
    private final Map<String, Set<String>> mSchemaMap = new HashMap<>();
    private final Map<String, Set<String>> mNamespaceMap = new HashMap<>();

    /**
     * The counter to check when to call {@link #checkForOptimize(boolean)}. The interval is
     * {@link #CHECK_OPTIMIZE_INTERVAL}.
     */
    private int mOptimizeIntervalCount = 0;

    /**
     * Creates and initializes an instance of {@link AppSearchImpl} which writes data to the given
     * folder.
     */
    @NonNull
    public static AppSearchImpl create(@NonNull File icingDir) throws AppSearchException {
        Preconditions.checkNotNull(icingDir);
        return new AppSearchImpl(icingDir);
    }

    private AppSearchImpl(@NonNull File icingDir) throws AppSearchException {
        boolean isReset = false;
        mReadWriteLock.writeLock().lock();
        try {
            // We synchronize here because we don't want to call IcingSearchEngine.initialize() more
            // than once. It's unnecessary and can be a costly operation.
            IcingSearchEngineOptions options = IcingSearchEngineOptions.newBuilder()
                    .setBaseDir(icingDir.getAbsolutePath()).build();
            mIcingSearchEngine = new IcingSearchEngine(options);

            InitializeResultProto initializeResultProto = mIcingSearchEngine.initialize();
            SchemaProto schemaProto = null;
            GetAllNamespacesResultProto getAllNamespacesResultProto = null;
            try {
                checkSuccess(initializeResultProto.getStatus());
                schemaProto = getSchemaProto();
                getAllNamespacesResultProto = mIcingSearchEngine.getAllNamespaces();
                checkSuccess(getAllNamespacesResultProto.getStatus());
            } catch (AppSearchException e) {
                // Some error. Reset and see if it fixes it.
                reset();
                isReset = true;
            }
            for (SchemaTypeConfigProto schema : schemaProto.getTypesList()) {
                String qualifiedSchemaType = schema.getSchemaType();
                addToMap(mSchemaMap, getDatabaseName(qualifiedSchemaType), qualifiedSchemaType);
            }
            for (String qualifiedNamespace : getAllNamespacesResultProto.getNamespacesList()) {
                addToMap(mNamespaceMap, getDatabaseName(qualifiedNamespace), qualifiedNamespace);
            }
            // TODO(b/155939114): It's possible to optimize after init, which would reduce the time
            //   to when we're able to serve queries. Consider moving this optimize call out.
            if (!isReset) {
                checkForOptimize(/* force= */ true);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Updates the AppSearch schema for this app.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName  The name of the database where this schema lives.
     * @param origSchema    The schema to set for this app.
     * @param forceOverride Whether to force-apply the schema even if it is incompatible. Documents
     *                      which do not comply with the new schema will be deleted.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void setSchema(@NonNull String databaseName, @NonNull SchemaProto origSchema,
            boolean forceOverride) throws AppSearchException {
        SchemaProto schemaProto = getSchemaProto();

        SchemaProto.Builder existingSchemaBuilder = schemaProto.toBuilder();

        // Combine the existing schema (which may have types from other databases) with this
        // database's new schema. Modifies the existingSchemaBuilder.
        Set<String> newTypeNames = rewriteSchema(databaseName, existingSchemaBuilder, origSchema);

        SetSchemaResultProto setSchemaResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            // Apply schema
            setSchemaResultProto =
                    mIcingSearchEngine.setSchema(existingSchemaBuilder.build(), forceOverride);

            // Determine whether it succeeded.
            try {
                checkSuccess(setSchemaResultProto.getStatus());
            } catch (AppSearchException e) {
                // Improve the error message by merging in information about incompatible types.
                if (setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                        || setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0) {
                    String newMessage = e.getMessage()
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
            mSchemaMap.put(databaseName, newTypeNames);

            // Determine whether to schedule an immediate optimize.
            if (setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                    || (setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0
                    && forceOverride)) {
                // Any existing schemas which is not in origSchema will be deleted, and all
                // documents of these types were also deleted. And so well if we force override
                // incompatible schemas.
                checkForOptimize(/* force= */true);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Adds a document to the AppSearch index.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName this document resides in.
     * @param document     The document to index.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void putDocument(@NonNull String databaseName, @NonNull DocumentProto document)
            throws AppSearchException {
        DocumentProto.Builder documentBuilder = document.toBuilder();
        rewriteDocumentTypes(getDatabasePrefix(databaseName), documentBuilder, /*add=*/ true);

        PutResultProto putResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            putResultProto = mIcingSearchEngine.put(documentBuilder.build());
            addToMap(mNamespaceMap, databaseName, documentBuilder.getNamespace());
            // The existing documents with same URI will be deleted, so there maybe some resources
            // could be released after optimize().
            checkForOptimize(/* force= */false);
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
     * @param namespace    The namespace this document resides in.
     * @param uri          The URI of the document to get.
     * @return The Document contents, or {@code null} if no such URI exists in the system.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @Nullable
    public DocumentProto getDocument(@NonNull String databaseName, @NonNull String namespace,
            @NonNull String uri) throws AppSearchException {
        GetResultProto getResultProto;
        mReadWriteLock.readLock().lock();
        try {
            getResultProto = mIcingSearchEngine.get(
                    getDatabasePrefix(databaseName) + namespace, uri);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
        checkSuccess(getResultProto.getStatus());

        DocumentProto.Builder documentBuilder = getResultProto.getDocument().toBuilder();
        rewriteDocumentTypes(getDatabasePrefix(databaseName), documentBuilder, /*add=*/ false);
        return documentBuilder.build();
    }

    /**
     * Executes a query against the AppSearch index and returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param databaseName The databaseName this query for.
     * @param searchSpec   Defines what and how to search
     * @param resultSpec   Defines what results to show
     * @param scoringSpec  Defines how to order results
     * @return The results of performing this search  The proto might have no {@code results} if no
     * documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultProto query(
            @NonNull String databaseName,
            @NonNull SearchSpecProto searchSpec,
            @NonNull ResultSpecProto resultSpec,
            @NonNull ScoringSpecProto scoringSpec) throws AppSearchException {
        SearchSpecProto.Builder searchSpecBuilder = searchSpec.toBuilder();
        SearchResultProto searchResultProto;
        mReadWriteLock.readLock().lock();
        try {
            // Only rewrite SearchSpec for non empty database.
            // rewriteSearchSpecForNonEmptyDatabase will return false for empty database, we
            // should just return an empty SearchResult and skip sending request to Icing.
            if (!rewriteSearchSpecForNonEmptyDatabase(databaseName, searchSpecBuilder)) {
                return SearchResultProto.newBuilder()
                        .setStatus(StatusProto.newBuilder()
                                .setCode(StatusProto.Code.OK)
                                .build())
                        .build();
            }
            searchResultProto = mIcingSearchEngine.search(
                    searchSpecBuilder.build(), scoringSpec, resultSpec);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
        checkSuccess(searchResultProto.getStatus());
        if (searchResultProto.getResultsCount() == 0) {
            return searchResultProto;
        }
        return rewriteSearchResultProto(databaseName, searchResultProto);
    }

    /**
     * Fetches the next page of results of a previously executed query. Results can be empty if
     * next-page token is invalid or all pages have been returned.
     *
     * @param databaseName The databaseName of the previously executed query.
     * @param nextPageToken The token of pre-loaded results of previously executed query.
     * @return The next page of results of previously executed query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultProto getNextPage(@NonNull String databaseName, long nextPageToken)
            throws AppSearchException {
        SearchResultProto searchResultProto = mIcingSearchEngine.getNextPage(nextPageToken);
        checkSuccess(searchResultProto.getStatus());
        if (searchResultProto.getResultsCount() == 0) {
            return searchResultProto;
        }
        return rewriteSearchResultProto(databaseName, searchResultProto);
    }

    /**
     * Invalidates the next-page token so that no more results of the related query can be returned.
     * @param nextPageToken The token of pre-loaded results of previously executed query to be
     *                      Invalidated.
     */
    public void invalidateNextPageToken(long nextPageToken) {
        mIcingSearchEngine.invalidateNextPageToken(nextPageToken);
    }

    /**
     * Removes the given document by URI.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName the document is in.
     * @param namespace    Namespace of the document to remove.
     * @param uri          URI of the document to remove.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void remove(@NonNull String databaseName, @NonNull String namespace,
            @NonNull String uri) throws AppSearchException {
        String qualifiedNamespace = getDatabasePrefix(databaseName) + namespace;
        DeleteResultProto deleteResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            deleteResultProto = mIcingSearchEngine.delete(qualifiedNamespace, uri);
            checkForOptimize(/* force= */false);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(deleteResultProto.getStatus());
    }

    /**
     * Removes all documents having the given {@code schemaType} in given database.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName that contains documents of schemaType.
     * @param schemaType   The schemaType of documents to remove.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByType(@NonNull String databaseName, @NonNull String schemaType)
            throws AppSearchException {
        String qualifiedType = getDatabasePrefix(databaseName) + schemaType;
        DeleteBySchemaTypeResultProto deleteBySchemaTypeResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            Set<String> existingSchemaTypes = mSchemaMap.get(databaseName);
            if (existingSchemaTypes == null || !existingSchemaTypes.contains(qualifiedType)) {
                return;
            }
            deleteBySchemaTypeResultProto = mIcingSearchEngine.deleteBySchemaType(qualifiedType);
            checkForOptimize(/* force= */true);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(deleteBySchemaTypeResultProto.getStatus());
    }

    /**
     * Removes all documents having the given {@code namespace} in given database.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName that contains documents of namespace.
     * @param namespace    The namespace of documents to remove.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByNamespace(@NonNull String databaseName, @NonNull String namespace)
            throws AppSearchException {
        String qualifiedNamespace = getDatabasePrefix(databaseName) + namespace;
        DeleteByNamespaceResultProto deleteByNamespaceResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            Set<String> existingNamespaces = mNamespaceMap.get(databaseName);
            if (existingNamespaces == null || !existingNamespaces.contains(qualifiedNamespace)) {
                return;
            }
            deleteByNamespaceResultProto = mIcingSearchEngine.deleteByNamespace(qualifiedNamespace);
            checkForOptimize(/* force= */true);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(deleteByNamespaceResultProto.getStatus());
    }

    /**
     * Clears the given database by removing all documents and types.
     *
     * <p>The schemas will remain. To clear everything including schemas, please call
     * {@link #setSchema} with an empty schema and {@code forceOverride} set to true.
     *
     * <p>This method belongs to mutate group.
     *
     * @param databaseName The databaseName to remove all documents from.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeAll(@NonNull String databaseName)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            Set<String> existingNamespaces = mNamespaceMap.get(databaseName);
            if (existingNamespaces == null) {
                return;
            }
            for (String namespace : existingNamespaces) {
                DeleteByNamespaceResultProto deleteByNamespaceResultProto =
                        mIcingSearchEngine.deleteByNamespace(namespace);
                // There's no way for AppSearch to know that all documents in a particular
                // namespace have been deleted, but if you try to delete an empty namespace, Icing
                // returns NOT_FOUND. Just ignore that code.
                checkCodeOneOf(
                        deleteByNamespaceResultProto.getStatus(),
                        StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
            }
            mNamespaceMap.remove(databaseName);
            checkForOptimize(/* force= */true);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Clears documents and schema across all databaseNames.
     *
     * <p>This method belongs to mutate group.
     *
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @VisibleForTesting
    public void reset() throws AppSearchException {
        ResetResultProto resetResultProto;
        mReadWriteLock.writeLock().lock();
        try {
            resetResultProto = mIcingSearchEngine.reset();
            mOptimizeIntervalCount = 0;
            mSchemaMap.clear();
            mNamespaceMap.clear();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        checkSuccess(resetResultProto.getStatus());
    }

    /**
     * Rewrites all types mentioned in the given {@code newSchema} to prepend {@code prefix}.
     * Rewritten types will be added to the {@code existingSchema}.
     *
     * @param databaseName   The name of the database where this schema lives.
     * @param existingSchema A schema that may contain existing types from across all database
     *                       instances. Will be mutated to contain the properly rewritten schema
     *                       types from {@code newSchema}.
     * @param newSchema      Schema with types to add to the {@code existingSchema}.
     * @return a Set contains all remaining qualified schema type names in given database.
     */
    @VisibleForTesting
    Set<String> rewriteSchema(@NonNull String databaseName,
            @NonNull SchemaProto.Builder existingSchema,
            @NonNull SchemaProto newSchema) throws AppSearchException {
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
                    String newPropertySchemaType =
                            prefix + propertyConfigBuilder.getSchemaType();
                    propertyConfigBuilder.setSchemaType(newPropertySchemaType);
                    typeConfigBuilder.setProperties(propertyIdx, propertyConfigBuilder);
                }
            }

            newTypesToProto.put(newSchemaType, typeConfigBuilder.build());
        }

        Set<String> newSchemaTypesName = newTypesToProto.keySet();

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
            }
        }
        // We've been removing existing types from newTypesToProto, so everything that remains is
        // new.
        existingSchema.addAllTypes(newTypesToProto.values());

        return newSchemaTypesName;
    }

    /**
     * Rewrites all types and namespaces mentioned anywhere in {@code documentBuilder} to prepend
     * or remove {@code prefix}.
     *
     * @param prefix          The prefix to add or remove
     * @param documentBuilder The document to mutate
     * @param add             Whether to add prefix to the types and namespaces. If {@code false},
     *                        prefix will be removed.
     * @throws IllegalStateException If {@code add=false} and the document has a type or namespace
     *                               that doesn't start with {@code prefix}.
     */
    @VisibleForTesting
    void rewriteDocumentTypes(
            @NonNull String prefix,
            @NonNull DocumentProto.Builder documentBuilder,
            boolean add) {
        // Rewrite the type name to include/remove the prefix.
        String newSchema;
        if (add) {
            newSchema = prefix + documentBuilder.getSchema();
        } else {
            newSchema = removePrefix(prefix, "schemaType", documentBuilder.getSchema());
        }
        documentBuilder.setSchema(newSchema);

        // Rewrite the namespace to include/remove the prefix.
        if (add) {
            documentBuilder.setNamespace(prefix + documentBuilder.getNamespace());
        } else {
            documentBuilder.setNamespace(
                    removePrefix(prefix, "namespace", documentBuilder.getNamespace()));
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
                    rewriteDocumentTypes(prefix, derivedDocumentBuilder, add);
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }
    }

    /**
     * Rewrites searchSpec by adding schemaTypeFilter and namespacesFilter
     *
     * <p>If user input empty filter lists, will look up {@link #mSchemaMap} and
     * {@link #mNamespaceMap} and put all values belong to current database to narrow down Icing
     * search area.
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     * @return false if the current database is brand new and contains nothing. We should just
     * return an empty query result to user.
     */
    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    boolean rewriteSearchSpecForNonEmptyDatabase(@NonNull String databaseName,
            @NonNull SearchSpecProto.Builder searchSpecBuilder) {
        Set<String> existingSchemaTypes = mSchemaMap.get(databaseName);
        Set<String> existingNamespaces = mNamespaceMap.get(databaseName);
        if (existingSchemaTypes == null || existingSchemaTypes.isEmpty()
                || existingNamespaces == null || existingNamespaces.isEmpty()) {
            return false;
        }
        // Rewrite any existing schema types specified in the searchSpec, or add schema types to
        // limit the search to this database instance.
        if (searchSpecBuilder.getSchemaTypeFiltersCount() > 0) {
            for (int i = 0; i < searchSpecBuilder.getSchemaTypeFiltersCount(); i++) {
                String qualifiedType = getDatabasePrefix(databaseName)
                        + searchSpecBuilder.getSchemaTypeFilters(i);
                if (existingSchemaTypes.contains(qualifiedType)) {
                    searchSpecBuilder.setSchemaTypeFilters(i, qualifiedType);
                }
            }
        } else {
            searchSpecBuilder.addAllSchemaTypeFilters(existingSchemaTypes);
        }

        // Rewrite any existing namespaces specified in the searchSpec, or add namespaces to
        // limit the search to this database instance.
        if (searchSpecBuilder.getNamespaceFiltersCount() > 0) {
            for (int i = 0; i < searchSpecBuilder.getNamespaceFiltersCount(); i++) {
                String qualifiedNamespace = getDatabasePrefix(databaseName)
                        + searchSpecBuilder.getNamespaceFilters(i);
                searchSpecBuilder.setNamespaceFilters(i, qualifiedNamespace);
            }
        } else {
            searchSpecBuilder.addAllNamespaceFilters(existingNamespaces);
        }
        return true;
    }

    @VisibleForTesting
    SchemaProto getSchemaProto() throws AppSearchException {
        GetSchemaResultProto schemaProto = mIcingSearchEngine.getSchema();
        // TODO(b/161935693) check GetSchemaResultProto is success or not. Call reset() if it's not.
        // TODO(b/161935693) only allow GetSchemaResultProto NOT_FOUND on first run
        checkCodeOneOf(schemaProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        return schemaProto.getSchema();
    }

    @NonNull
    private String getDatabasePrefix(@NonNull String databaseName) {
        return databaseName + "/";
    }

    @NonNull
    private String getDatabaseName(@NonNull String prefixedValue) throws AppSearchException {
        int delimiterIndex = prefixedValue.indexOf('/');
        if (delimiterIndex == -1) {
            throw new AppSearchException(AppSearchResult.RESULT_UNKNOWN_ERROR,
                    "The databaseName prefixed value doesn't contains a valid database name.");
        }
        return prefixedValue.substring(0, delimiterIndex);
    }

    @NonNull
    private static String removePrefix(@NonNull String prefix, @NonNull String inputType,
            @NonNull String input) {
        if (!input.startsWith(prefix)) {
            throw new IllegalStateException(
                    "Unexpected " + inputType + " \"" + input
                            + "\" does not start with \"" + prefix + "\"");
        }
        return input.substring(prefix.length());
    }

    @GuardedBy("mReadWriteLock")
    private void addToMap(Map<String, Set<String>> map, String databaseName, String prefixedValue) {
        Set<String> values = map.get(databaseName);
        if (values == null) {
            values = new HashSet<>();
            map.put(databaseName, values);
        }
        values.add(prefixedValue);
    }

    /**
     * Checks the given status code and throws an {@link AppSearchException} if code is an error.
     *
     * @throws AppSearchException on error codes.
     */
    private void checkSuccess(StatusProto statusProto) throws AppSearchException {
        checkCodeOneOf(statusProto, StatusProto.Code.OK);
    }

    /**
     * Checks the given status code is one of the provided codes, and throws an
     * {@link AppSearchException} if it is not.
     */
    private void checkCodeOneOf(StatusProto statusProto, StatusProto.Code... codes)
            throws AppSearchException {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == statusProto.getCode()) {
                // Everything's good
                return;
            }
        }

        if (statusProto.getCode() == StatusProto.Code.WARNING_DATA_LOSS) {
            // TODO: May want to propagate WARNING_DATA_LOSS up to AppSearchManager so they can
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
     * <p>{@link IcingSearchEngine#optimize()} should be called only if
     * {@link GetOptimizeInfoResultProto} shows there is enough resources could be released.
     * <p>{@link IcingSearchEngine#getOptimizeInfo()} should be called once per
     * {@link #CHECK_OPTIMIZE_INTERVAL} of remove executions.
     *
     * @param force whether we should directly call {@link IcingSearchEngine#getOptimizeInfo()}.
     */
    @GuardedBy("mReadWriteLock")
    private void checkForOptimize(boolean force) throws AppSearchException {
        ++mOptimizeIntervalCount;
        if (force || mOptimizeIntervalCount >= CHECK_OPTIMIZE_INTERVAL) {
            mOptimizeIntervalCount = 0;
            GetOptimizeInfoResultProto optimizeInfo = getOptimizeInfoResult();
            checkSuccess(optimizeInfo.getStatus());
            // Second threshold, decide when to call optimize().
            if (optimizeInfo.getOptimizableDocs() >= OPTIMIZE_THRESHOLD_DOC_COUNT
                    || optimizeInfo.getEstimatedOptimizableBytes()
                    >= OPTIMIZE_THRESHOLD_BYTES) {
                // TODO(b/155939114): call optimize in the same thread will slow down api calls
                //  significantly. Move this call to background.
                OptimizeResultProto optimizeResultProto = mIcingSearchEngine.optimize();
                checkSuccess(optimizeResultProto.getStatus());
            }
            // TODO(b/147699081): Return OptimizeResultProto & log lost data detail once we add
            //  a field to indicate lost_schema and lost_documents in OptimizeResultProto.
            //  go/icing-library-apis.
        }
    }

    /** Remove the rewritten schema types from any result documents.*/
    private SearchResultProto rewriteSearchResultProto(@NonNull String databaseName,
            @NonNull SearchResultProto searchResultProto) {
        SearchResultProto.Builder searchResultsBuilder = searchResultProto.toBuilder();
        for (int i = 0; i < searchResultsBuilder.getResultsCount(); i++) {
            if (searchResultProto.getResults(i).hasDocument()) {
                SearchResultProto.ResultProto.Builder resultBuilder =
                        searchResultsBuilder.getResults(i).toBuilder();
                DocumentProto.Builder documentBuilder = resultBuilder.getDocument().toBuilder();
                rewriteDocumentTypes(
                        getDatabasePrefix(databaseName), documentBuilder, /*add=*/false);
                resultBuilder.setDocument(documentBuilder);
                searchResultsBuilder.setResults(i, resultBuilder);
            }
        }
        return searchResultsBuilder.build();
    }

    @VisibleForTesting
    GetOptimizeInfoResultProto getOptimizeInfoResult() {
        return mIcingSearchEngine.getOptimizeInfo();
    }

    /**
     * Converts an erroneous status code to an AppSearchException. Callers should ensure that
     * the status code is not OK or WARNING_DATA_LOSS.
     *
     * @param statusProto StatusProto with error code and message to translate into
     *                    AppSearchException.
     * @return AppSearchException with the parallel error code.
     */
    private AppSearchException statusProtoToAppSearchException(StatusProto statusProto) {
        switch (statusProto.getCode()) {
            case INVALID_ARGUMENT:
                return new AppSearchException(AppSearchResult.RESULT_INVALID_ARGUMENT,
                        statusProto.getMessage());
            case NOT_FOUND:
                return new AppSearchException(AppSearchResult.RESULT_NOT_FOUND,
                        statusProto.getMessage());
            case FAILED_PRECONDITION:
                // Fallthrough
            case ABORTED:
                // Fallthrough
            case INTERNAL:
                return new AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR,
                        statusProto.getMessage());
            case OUT_OF_SPACE:
                return new AppSearchException(AppSearchResult.RESULT_OUT_OF_SPACE,
                        statusProto.getMessage());
            default:
                // Some unknown/unsupported error
                return new AppSearchException(AppSearchResult.RESULT_UNKNOWN_ERROR,
                        "Unknown IcingSearchEngine status code: " + statusProto.getCode());
        }
    }
}
