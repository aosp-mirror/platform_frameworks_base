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
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByUriRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.appsearch.external.localstorage.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.ResultCodeToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SchemaToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchResultToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchSpecToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SetSchemaResponseToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.TypePropertyPathToProtoConverter;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;

import com.google.android.icing.IcingSearchEngine;
import com.google.android.icing.proto.DeleteByQueryResultProto;
import com.google.android.icing.proto.DeleteResultProto;
import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.DocumentStorageInfoProto;
import com.google.android.icing.proto.GetAllNamespacesResultProto;
import com.google.android.icing.proto.GetOptimizeInfoResultProto;
import com.google.android.icing.proto.GetResultProto;
import com.google.android.icing.proto.GetResultSpecProto;
import com.google.android.icing.proto.GetSchemaResultProto;
import com.google.android.icing.proto.IcingSearchEngineOptions;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.NamespaceStorageInfoProto;
import com.google.android.icing.proto.OptimizeResultProto;
import com.google.android.icing.proto.PersistToDiskResultProto;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.PutResultProto;
import com.google.android.icing.proto.ReportUsageResultProto;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.StorageInfoResultProto;
import com.google.android.icing.proto.TypePropertyMask;
import com.google.android.icing.proto.UsageReport;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
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
 * <p>A single instance of {@link AppSearchImpl} can support all packages and databases. This is
 * done by combining the package and database name into a unique prefix and prefixing the schemas
 * and documents stored under that owner. Schemas and documents are physically saved together in
 * {@link IcingSearchEngine}, but logically isolated:
 *
 * <ul>
 *   <li>Rewrite SchemaType in SchemaProto by adding the package-database prefix and save into
 *       SchemaTypes set in {@link #setSchema}.
 *   <li>Rewrite namespace and SchemaType in DocumentProto by adding package-database prefix and
 *       save to namespaces set in {@link #putDocument}.
 *   <li>Remove package-database prefix when retrieving documents in {@link #getDocument} and {@link
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
public final class AppSearchImpl implements Closeable {
    private static final String TAG = "AppSearchImpl";

    @VisibleForTesting static final char DATABASE_DELIMITER = '/';

    @VisibleForTesting static final char PACKAGE_DELIMITER = '$';

    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_DOC_COUNT = 1000;
    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_BYTES = 1_000_000; // 1MB
    @VisibleForTesting static final int CHECK_OPTIMIZE_INTERVAL = 100;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();

    @GuardedBy("mReadWriteLock")
    private final IcingSearchEngine mIcingSearchEngineLocked;

    @GuardedBy("mReadWriteLock")
    private final VisibilityStore mVisibilityStoreLocked;

    // This map contains schemaTypes for all package-database prefixes. All values in the map are
    // prefixed with the package-database prefix.
    // TODO(b/172360376): Check if this can be replaced with an ArrayMap
    @GuardedBy("mReadWriteLock")
    private final Map<String, Set<String>> mSchemaMapLocked = new HashMap<>();

    // This map contains namespaces for all package-database prefixes. All values in the map are
    // prefixed with the package-database prefix.
    // TODO(b/172360376): Check if this can be replaced with an ArrayMap
    @GuardedBy("mReadWriteLock")
    private final Map<String, Set<String>> mNamespaceMapLocked = new HashMap<>();

    /**
     * The counter to check when to call {@link #checkForOptimize}. The interval is {@link
     * #CHECK_OPTIMIZE_INTERVAL}.
     */
    @GuardedBy("mReadWriteLock")
    private int mOptimizeIntervalCountLocked = 0;

    /** Whether this instance has been closed, and therefore unusable. */
    @GuardedBy("mReadWriteLock")
    private boolean mClosedLocked = false;

    /**
     * Creates and initializes an instance of {@link AppSearchImpl} which writes data to the given
     * folder.
     */
    @NonNull
    public static AppSearchImpl create(
            @NonNull File icingDir,
            @NonNull Context context,
            int userId,
            @NonNull String globalQuerierPackage)
            throws AppSearchException {
        Preconditions.checkNotNull(icingDir);
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(globalQuerierPackage);
        AppSearchImpl appSearchImpl =
                new AppSearchImpl(icingDir, context, userId, globalQuerierPackage);
        appSearchImpl.initializeVisibilityStore();
        return appSearchImpl;
    }

    private AppSearchImpl(
            @NonNull File icingDir,
            @NonNull Context context,
            int userId,
            @NonNull String globalQuerierPackage)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();

        try {
            // We synchronize here because we don't want to call IcingSearchEngine.initialize() more
            // than once. It's unnecessary and can be a costly operation.
            IcingSearchEngineOptions options =
                    IcingSearchEngineOptions.newBuilder()
                            .setBaseDir(icingDir.getAbsolutePath())
                            .build();
            mIcingSearchEngineLocked = new IcingSearchEngine(options);

            mVisibilityStoreLocked =
                    new VisibilityStore(this, context, userId, globalQuerierPackage);

            InitializeResultProto initializeResultProto = mIcingSearchEngineLocked.initialize();
            SchemaProto schemaProto;
            GetAllNamespacesResultProto getAllNamespacesResultProto;
            try {
                checkSuccess(initializeResultProto.getStatus());
                schemaProto = getSchemaProtoLocked();
                getAllNamespacesResultProto = mIcingSearchEngineLocked.getAllNamespaces();
                checkSuccess(getAllNamespacesResultProto.getStatus());
            } catch (AppSearchException e) {
                Log.w(TAG, "Error initializing, resetting IcingSearchEngine.", e);
                // Some error. Reset and see if it fixes it.
                resetLocked();
                return;
            }

            // Populate schema map
            for (SchemaTypeConfigProto schema : schemaProto.getTypesList()) {
                String prefixedSchemaType = schema.getSchemaType();
                addToMap(mSchemaMapLocked, getPrefix(prefixedSchemaType), prefixedSchemaType);
            }

            // Populate namespace map
            for (String prefixedNamespace : getAllNamespacesResultProto.getNamespacesList()) {
                addToMap(mNamespaceMapLocked, getPrefix(prefixedNamespace), prefixedNamespace);
            }
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
            throwIfClosedLocked();

            mVisibilityStoreLocked.initialize();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private void throwIfClosedLocked() {
        if (mClosedLocked) {
            throw new IllegalStateException("Trying to use a closed AppSearchImpl instance.");
        }
    }

    /**
     * Persists data to disk and closes the instance.
     *
     * <p>This instance is no longer usable after it's been closed. Call {@link #create} to create a
     * new, usable instance.
     */
    @Override
    public void close() {
        mReadWriteLock.writeLock().lock();
        try {
            if (mClosedLocked) {
                return;
            }

            persistToDisk();
            mIcingSearchEngineLocked.close();
            mClosedLocked = true;
        } catch (AppSearchException e) {
            Log.w(TAG, "Error when closing AppSearchImpl.", e);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Updates the AppSearch schema for this app.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the schemas.
     * @param databaseName The name of the database where this schema lives.
     * @param schemas Schemas to set for this app.
     * @param schemasNotPlatformSurfaceable Schema types that should not be surfaced on platform
     *     surfaces.
     * @param schemasPackageAccessible Schema types that are visible to the specified packages.
     * @param forceOverride Whether to force-apply the schema even if it is incompatible. Documents
     *     which do not comply with the new schema will be deleted.
     * @param version The overall version number of the request.
     * @return The response contains deleted schema types and incompatible schema types of this
     *     call.
     * @throws AppSearchException On IcingSearchEngine error. If the status code is
     *     FAILED_PRECONDITION for the incompatible change, the exception will be converted to the
     *     SetSchemaResponse.
     */
    @NonNull
    public SetSchemaResponse setSchema(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull List<AppSearchSchema> schemas,
            @NonNull List<String> schemasNotPlatformSurfaceable,
            @NonNull Map<String, List<PackageIdentifier>> schemasPackageAccessible,
            boolean forceOverride,
            int version)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            SchemaProto.Builder existingSchemaBuilder = getSchemaProtoLocked().toBuilder();

            SchemaProto.Builder newSchemaBuilder = SchemaProto.newBuilder();
            for (int i = 0; i < schemas.size(); i++) {
                AppSearchSchema schema = schemas.get(i);
                SchemaTypeConfigProto schemaTypeProto =
                        SchemaToProtoConverter.toSchemaTypeConfigProto(schema, version);
                newSchemaBuilder.addTypes(schemaTypeProto);
            }

            String prefix = createPrefix(packageName, databaseName);
            // Combine the existing schema (which may have types from other prefixes) with this
            // prefix's new schema. Modifies the existingSchemaBuilder.
            RewrittenSchemaResults rewrittenSchemaResults =
                    rewriteSchema(prefix, existingSchemaBuilder, newSchemaBuilder.build());

            // Apply schema
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(
                            existingSchemaBuilder.build(), forceOverride);

            // Determine whether it succeeded.
            try {
                checkSuccess(setSchemaResultProto.getStatus());
            } catch (AppSearchException e) {
                // Swallow the exception for the incompatible change case. We will propagate
                // those deleted schemas and incompatible types to the SetSchemaResponse.
                boolean isFailedPrecondition =
                        setSchemaResultProto.getStatus().getCode()
                                == StatusProto.Code.FAILED_PRECONDITION;
                boolean isIncompatible =
                        setSchemaResultProto.getDeletedSchemaTypesCount() > 0
                                || setSchemaResultProto.getIncompatibleSchemaTypesCount() > 0;
                if (isFailedPrecondition && isIncompatible) {
                    return SetSchemaResponseToProtoConverter.toSetSchemaResponse(
                            setSchemaResultProto, prefix);
                } else {
                    throw e;
                }
            }

            // Update derived data structures.
            mSchemaMapLocked.put(prefix, rewrittenSchemaResults.mRewrittenPrefixedTypes);

            Set<String> prefixedSchemasNotPlatformSurfaceable =
                    new ArraySet<>(schemasNotPlatformSurfaceable.size());
            for (int i = 0; i < schemasNotPlatformSurfaceable.size(); i++) {
                prefixedSchemasNotPlatformSurfaceable.add(
                        prefix + schemasNotPlatformSurfaceable.get(i));
            }

            Map<String, List<PackageIdentifier>> prefixedSchemasPackageAccessible =
                    new ArrayMap<>(schemasPackageAccessible.size());
            for (Map.Entry<String, List<PackageIdentifier>> entry :
                    schemasPackageAccessible.entrySet()) {
                prefixedSchemasPackageAccessible.put(prefix + entry.getKey(), entry.getValue());
            }

            mVisibilityStoreLocked.setVisibility(
                    prefix,
                    prefixedSchemasNotPlatformSurfaceable,
                    prefixedSchemasPackageAccessible);

            return SetSchemaResponseToProtoConverter.toSetSchemaResponse(
                    setSchemaResultProto, prefix);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Retrieves the AppSearch schema for this package name, database.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName Package name that owns this schema
     * @param databaseName The name of the database where this schema lives.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public GetSchemaResponse getSchema(@NonNull String packageName, @NonNull String databaseName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            SchemaProto fullSchema = getSchemaProtoLocked();

            String prefix = createPrefix(packageName, databaseName);
            GetSchemaResponse.Builder responseBuilder = new GetSchemaResponse.Builder();
            for (int i = 0; i < fullSchema.getTypesCount(); i++) {
                String typePrefix = getPrefix(fullSchema.getTypes(i).getSchemaType());
                if (!prefix.equals(typePrefix)) {
                    continue;
                }
                // Rewrite SchemaProto.types.schema_type
                SchemaTypeConfigProto.Builder typeConfigBuilder =
                        fullSchema.getTypes(i).toBuilder();
                String newSchemaType = typeConfigBuilder.getSchemaType().substring(prefix.length());
                typeConfigBuilder.setSchemaType(newSchemaType);

                // Rewrite SchemaProto.types.properties.schema_type
                for (int propertyIdx = 0;
                        propertyIdx < typeConfigBuilder.getPropertiesCount();
                        propertyIdx++) {
                    PropertyConfigProto.Builder propertyConfigBuilder =
                            typeConfigBuilder.getProperties(propertyIdx).toBuilder();
                    if (!propertyConfigBuilder.getSchemaType().isEmpty()) {
                        String newPropertySchemaType =
                                propertyConfigBuilder.getSchemaType().substring(prefix.length());
                        propertyConfigBuilder.setSchemaType(newPropertySchemaType);
                        typeConfigBuilder.setProperties(propertyIdx, propertyConfigBuilder);
                    }
                }

                AppSearchSchema schema =
                        SchemaToProtoConverter.toAppSearchSchema(typeConfigBuilder);

                // TODO(b/183050495) find a place to store the version for the database, rather
                // than read from a schema.
                responseBuilder.setVersion(fullSchema.getTypes(i).getVersion());
                responseBuilder.addSchema(schema);
            }
            return responseBuilder.build();
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieves the list of namespaces with at least one document for this package name, database.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName Package name that owns this schema
     * @param databaseName The name of the database where this schema lives.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public List<String> getNamespaces(@NonNull String packageName, @NonNull String databaseName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            // We can't just use mNamespaceMap here because we have no way to prune namespaces from
            // mNamespaceMap when they have no more documents (e.g. after setting schema to empty or
            // using deleteByQuery).
            GetAllNamespacesResultProto getAllNamespacesResultProto =
                    mIcingSearchEngineLocked.getAllNamespaces();
            checkSuccess(getAllNamespacesResultProto.getStatus());
            String prefix = createPrefix(packageName, databaseName);
            List<String> results = new ArrayList<>();
            for (int i = 0; i < getAllNamespacesResultProto.getNamespacesCount(); i++) {
                String prefixedNamespace = getAllNamespacesResultProto.getNamespaces(i);
                if (prefixedNamespace.startsWith(prefix)) {
                    results.add(prefixedNamespace.substring(prefix.length()));
                }
            }
            return results;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Adds a document to the AppSearch index.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param document The document to index.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void putDocument(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull GenericDocument document,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        PutDocumentStats.Builder pStatsBuilder = null;
        if (logger != null) {
            pStatsBuilder = new PutDocumentStats.Builder(packageName, databaseName);
        }
        long totalStartTimeMillis = SystemClock.elapsedRealtime();

        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            // Generate Document Proto
            long generateDocumentProtoStartTimeMillis = SystemClock.elapsedRealtime();
            DocumentProto.Builder documentBuilder =
                    GenericDocumentToProtoConverter.toDocumentProto(document).toBuilder();
            long generateDocumentProtoEndTimeMillis = SystemClock.elapsedRealtime();

            // Rewrite Document Type
            long rewriteDocumentTypeStartTimeMillis = SystemClock.elapsedRealtime();
            String prefix = createPrefix(packageName, databaseName);
            addPrefixToDocument(documentBuilder, prefix);
            long rewriteDocumentTypeEndTimeMillis = SystemClock.elapsedRealtime();

            PutResultProto putResultProto = mIcingSearchEngineLocked.put(documentBuilder.build());
            addToMap(mNamespaceMapLocked, prefix, documentBuilder.getNamespace());

            // Logging stats
            if (logger != null) {
                pStatsBuilder
                        .getGeneralStatsBuilder()
                        .setStatusCode(
                                statusProtoToAppSearchException(putResultProto.getStatus())
                                        .getResultCode());
                pStatsBuilder
                        .setGenerateDocumentProtoLatencyMillis(
                                (int)
                                        (generateDocumentProtoEndTimeMillis
                                                - generateDocumentProtoStartTimeMillis))
                        .setRewriteDocumentTypesLatencyMillis(
                                (int)
                                        (rewriteDocumentTypeEndTimeMillis
                                                - rewriteDocumentTypeStartTimeMillis));
                AppSearchLoggerHelper.copyNativeStats(
                        putResultProto.getPutDocumentStats(), pStatsBuilder);
            }

            checkSuccess(putResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();

            if (logger != null) {
                long totalEndTimeMillis = SystemClock.elapsedRealtime();
                pStatsBuilder
                        .getGeneralStatsBuilder()
                        .setTotalLatencyMillis((int) (totalEndTimeMillis - totalStartTimeMillis));
                logger.logStats(pStatsBuilder.build());
            }
        }
    }

    /**
     * Retrieves a document from the AppSearch index by URI.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName The package that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param uri The URI of the document to get.
     * @param typePropertyPaths A map of schema type to a list of property paths to return in the
     *     result.
     * @return The Document contents
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public GenericDocument getDocument(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String uri,
            @NonNull Map<String, List<String>> typePropertyPaths)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            List<TypePropertyMask> nonPrefixedPropertyMasks =
                    TypePropertyPathToProtoConverter.toTypePropertyMaskList(typePropertyPaths);
            List<TypePropertyMask> prefixedPropertyMasks =
                    new ArrayList<>(nonPrefixedPropertyMasks.size());
            for (int i = 0; i < nonPrefixedPropertyMasks.size(); ++i) {
                TypePropertyMask typePropertyMask = nonPrefixedPropertyMasks.get(i);
                String nonPrefixedType = typePropertyMask.getSchemaType();
                String prefixedType =
                        nonPrefixedType.equals(GetByUriRequest.PROJECTION_SCHEMA_TYPE_WILDCARD)
                                ? nonPrefixedType
                                : createPrefix(packageName, databaseName) + nonPrefixedType;
                prefixedPropertyMasks.add(
                        typePropertyMask.toBuilder().setSchemaType(prefixedType).build());
            }
            GetResultSpecProto getResultSpec =
                    GetResultSpecProto.newBuilder()
                            .addAllTypePropertyMasks(prefixedPropertyMasks)
                            .build();

            GetResultProto getResultProto =
                    mIcingSearchEngineLocked.get(
                            createPrefix(packageName, databaseName) + namespace,
                            uri,
                            getResultSpec);
            checkSuccess(getResultProto.getStatus());

            DocumentProto.Builder documentBuilder = getResultProto.getDocument().toBuilder();
            removePrefixesFromDocument(documentBuilder);
            return GenericDocumentToProtoConverter.toGenericDocument(documentBuilder.build());
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Executes a query against the AppSearch index and returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName The package name that is performing the query.
     * @param databaseName The databaseName this query for.
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage query(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            List<String> filterPackageNames = searchSpec.getFilterPackageNames();
            if (!filterPackageNames.isEmpty() && !filterPackageNames.contains(packageName)) {
                // Client wanted to query over some packages that weren't its own. This isn't
                // allowed through local query so we can return early with no results.
                return new SearchResultPage(Bundle.EMPTY);
            }

            String prefix = createPrefix(packageName, databaseName);
            Set<String> allowedPrefixedSchemas = getAllowedPrefixSchemasLocked(prefix, searchSpec);

            return doQueryLocked(
                    Collections.singleton(createPrefix(packageName, databaseName)),
                    allowedPrefixedSchemas,
                    queryExpression,
                    searchSpec);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Executes a global query, i.e. over all permitted prefixes, against the AppSearch index and
     * returns results.
     *
     * <p>This method belongs to query group.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @param callerPackageName Package name of the caller, should belong to the {@code callerUid}.
     * @param callerUid UID of the client making the globalQuery call.
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage globalQuery(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull String callerPackageName,
            int callerUid)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            Set<String> packageFilters = new ArraySet<>(searchSpec.getFilterPackageNames());
            Set<String> prefixFilters = new ArraySet<>();
            Set<String> allPrefixes = mNamespaceMapLocked.keySet();
            if (packageFilters.isEmpty()) {
                // Client didn't restrict their search over packages. Try to query over all
                // packages/prefixes
                prefixFilters = allPrefixes;
            } else {
                // Client did restrict their search over packages. Only include the prefixes that
                // belong to the specified packages.
                for (String prefix : allPrefixes) {
                    String packageName = getPackageName(prefix);
                    if (packageFilters.contains(packageName)) {
                        prefixFilters.add(prefix);
                    }
                }
            }

            // Find which schemas the client is allowed to query over.
            Set<String> allowedPrefixedSchemas = new ArraySet<>();
            List<String> schemaFilters = searchSpec.getFilterSchemas();
            for (String prefix : prefixFilters) {
                String packageName = getPackageName(prefix);

                if (!schemaFilters.isEmpty()) {
                    for (String schema : schemaFilters) {
                        // Client specified some schemas to search over, check each one
                        String prefixedSchema = prefix + schema;
                        if (packageName.equals(callerPackageName)
                                || mVisibilityStoreLocked.isSchemaSearchableByCaller(
                                        prefix, prefixedSchema, callerUid)) {
                            allowedPrefixedSchemas.add(prefixedSchema);
                        }
                    }
                } else {
                    // Client didn't specify certain schemas to search over, check all schemas
                    Set<String> prefixedSchemas = mSchemaMapLocked.get(prefix);
                    if (prefixedSchemas != null) {
                        for (String prefixedSchema : prefixedSchemas) {
                            if (packageName.equals(callerPackageName)
                                    || mVisibilityStoreLocked.isSchemaSearchableByCaller(
                                            prefix, prefixedSchema, callerUid)) {
                                allowedPrefixedSchemas.add(prefixedSchema);
                            }
                        }
                    }
                }
            }

            return doQueryLocked(
                    prefixFilters, allowedPrefixedSchemas, queryExpression, searchSpec);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Returns a mapping of package names to all the databases owned by that package.
     *
     * <p>This method is inefficient to call repeatedly.
     */
    @NonNull
    public Map<String, Set<String>> getPackageToDatabases() {
        mReadWriteLock.readLock().lock();
        try {
            Map<String, Set<String>> packageToDatabases = new ArrayMap<>();
            for (String prefix : mSchemaMapLocked.keySet()) {
                String packageName = getPackageName(prefix);

                Set<String> databases = packageToDatabases.get(packageName);
                if (databases == null) {
                    databases = new ArraySet<>();
                    packageToDatabases.put(packageName, databases);
                }

                String databaseName = getDatabaseName(prefix);
                databases.add(databaseName);
            }

            return packageToDatabases;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private SearchResultPage doQueryLocked(
            @NonNull Set<String> prefixes,
            @NonNull Set<String> allowedPrefixedSchemas,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        SearchSpecProto.Builder searchSpecBuilder =
                SearchSpecToProtoConverter.toSearchSpecProto(searchSpec).toBuilder()
                        .setQuery(queryExpression);
        // rewriteSearchSpecForPrefixesLocked will return false if there is nothing to search
        // over given their search filters, so we can return an empty SearchResult and skip
        // sending request to Icing.
        if (!rewriteSearchSpecForPrefixesLocked(
                searchSpecBuilder, prefixes, allowedPrefixedSchemas)) {
            return new SearchResultPage(Bundle.EMPTY);
        }

        ResultSpecProto.Builder resultSpecBuilder =
                SearchSpecToProtoConverter.toResultSpecProto(searchSpec).toBuilder();

        int groupingType = searchSpec.getResultGroupingTypeFlags();
        if ((groupingType & SearchSpec.GROUPING_TYPE_PER_PACKAGE) != 0
                && (groupingType & SearchSpec.GROUPING_TYPE_PER_NAMESPACE) != 0) {
            addPerPackagePerNamespaceResultGroupingsLocked(
                    resultSpecBuilder, prefixes, searchSpec.getResultGroupingLimit());
        } else if ((groupingType & SearchSpec.GROUPING_TYPE_PER_PACKAGE) != 0) {
            addPerPackageResultGroupingsLocked(
                    resultSpecBuilder, prefixes, searchSpec.getResultGroupingLimit());
        } else if ((groupingType & SearchSpec.GROUPING_TYPE_PER_NAMESPACE) != 0) {
            addPerNamespaceResultGroupingsLocked(
                    resultSpecBuilder, prefixes, searchSpec.getResultGroupingLimit());
        }
        rewriteResultSpecForPrefixesLocked(resultSpecBuilder, prefixes, allowedPrefixedSchemas);

        ScoringSpecProto scoringSpec = SearchSpecToProtoConverter.toScoringSpecProto(searchSpec);
        SearchResultProto searchResultProto =
                mIcingSearchEngineLocked.search(
                        searchSpecBuilder.build(), scoringSpec, resultSpecBuilder.build());
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
            throwIfClosedLocked();

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
            throwIfClosedLocked();

            mIcingSearchEngineLocked.invalidateNextPageToken(nextPageToken);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /** Reports a usage of the given document at the given timestamp. */
    public void reportUsage(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String uri,
            long usageTimestampMillis,
            boolean systemUsage)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            String prefixedNamespace = createPrefix(packageName, databaseName) + namespace;
            UsageReport.UsageType usageType =
                    systemUsage
                            ? UsageReport.UsageType.USAGE_TYPE2
                            : UsageReport.UsageType.USAGE_TYPE1;
            UsageReport report =
                    UsageReport.newBuilder()
                            .setDocumentNamespace(prefixedNamespace)
                            .setDocumentUri(uri)
                            .setUsageTimestampMs(usageTimestampMillis)
                            .setUsageType(usageType)
                            .build();

            ReportUsageResultProto result = mIcingSearchEngineLocked.reportUsage(report);
            checkSuccess(result.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Removes the given document by URI.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the document.
     * @param databaseName The databaseName the document is in.
     * @param namespace Namespace of the document to remove.
     * @param uri URI of the document to remove.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void remove(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String uri)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            String prefixedNamespace = createPrefix(packageName, databaseName) + namespace;
            DeleteResultProto deleteResultProto =
                    mIcingSearchEngineLocked.delete(prefixedNamespace, uri);

            checkSuccess(deleteResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Removes documents by given query.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the documents.
     * @param databaseName The databaseName the document is in.
     * @param queryExpression Query String to search.
     * @param searchSpec Defines what and how to remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByQuery(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            List<String> filterPackageNames = searchSpec.getFilterPackageNames();
            if (!filterPackageNames.isEmpty() && !filterPackageNames.contains(packageName)) {
                // We're only removing documents within the parameter `packageName`. If we're not
                // restricting our remove-query to this package name, then there's nothing for us to
                // remove.
                return;
            }

            SearchSpecProto searchSpecProto =
                    SearchSpecToProtoConverter.toSearchSpecProto(searchSpec);
            SearchSpecProto.Builder searchSpecBuilder =
                    searchSpecProto.toBuilder().setQuery(queryExpression);

            String prefix = createPrefix(packageName, databaseName);
            Set<String> allowedPrefixedSchemas = getAllowedPrefixSchemasLocked(prefix, searchSpec);

            // rewriteSearchSpecForPrefixesLocked will return false if there is nothing to search
            // over given their search filters, so we can return early and skip sending request
            // to Icing.
            if (!rewriteSearchSpecForPrefixesLocked(
                    searchSpecBuilder, Collections.singleton(prefix), allowedPrefixedSchemas)) {
                return;
            }
            DeleteByQueryResultProto deleteResultProto =
                    mIcingSearchEngineLocked.deleteByQuery(searchSpecBuilder.build());

            // It seems that the caller wants to get success if the data matching the query is
            // not in the DB because it was not there or was successfully deleted.
            checkCodeOneOf(
                    deleteResultProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /** Estimates the storage usage info for a specific package. */
    @NonNull
    public StorageInfo getStorageInfoForPackage(@NonNull String packageName)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            Set<String> databases = packageToDatabases.get(packageName);
            if (databases == null) {
                // Package doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }

            // Accumulate all the namespaces we're interested in.
            Set<String> wantedPrefixedNamespaces = new ArraySet<>();
            for (String database : databases) {
                Set<String> prefixedNamespaces =
                        mNamespaceMapLocked.get(createPrefix(packageName, database));
                if (prefixedNamespaces != null) {
                    wantedPrefixedNamespaces.addAll(prefixedNamespaces);
                }
            }
            if (wantedPrefixedNamespaces.isEmpty()) {
                return new StorageInfo.Builder().build();
            }

            return getStorageInfoForNamespacesLocked(wantedPrefixedNamespaces);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /** Estimates the storage usage info for a specific database in a package. */
    @NonNull
    public StorageInfo getStorageInfoForDatabase(
            @NonNull String packageName, @NonNull String databaseName) throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            Set<String> databases = packageToDatabases.get(packageName);
            if (databases == null) {
                // Package doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }
            if (!databases.contains(databaseName)) {
                // Database doesn't exist, no storage info to report
                return new StorageInfo.Builder().build();
            }

            Set<String> wantedPrefixedNamespaces =
                    mNamespaceMapLocked.get(createPrefix(packageName, databaseName));
            if (wantedPrefixedNamespaces == null || wantedPrefixedNamespaces.isEmpty()) {
                return new StorageInfo.Builder().build();
            }

            return getStorageInfoForNamespacesLocked(wantedPrefixedNamespaces);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    @NonNull
    private StorageInfo getStorageInfoForNamespacesLocked(@NonNull Set<String> prefixedNamespaces)
            throws AppSearchException {
        StorageInfoResultProto storageInfoResult = mIcingSearchEngineLocked.getStorageInfo();
        checkSuccess(storageInfoResult.getStatus());
        if (!storageInfoResult.hasStorageInfo()
                || !storageInfoResult.getStorageInfo().hasDocumentStorageInfo()) {
            return new StorageInfo.Builder().build();
        }
        long totalStorageSize = storageInfoResult.getStorageInfo().getTotalStorageSize();

        DocumentStorageInfoProto documentStorageInfo =
                storageInfoResult.getStorageInfo().getDocumentStorageInfo();
        int totalDocuments =
                documentStorageInfo.getNumAliveDocuments()
                        + documentStorageInfo.getNumExpiredDocuments();

        if (totalStorageSize == 0 || totalDocuments == 0) {
            // Maybe we can exit early and also avoid a divide by 0 error.
            return new StorageInfo.Builder().build();
        }

        // Accumulate stats across the package's namespaces.
        int aliveDocuments = 0;
        int expiredDocuments = 0;
        int aliveNamespaces = 0;
        List<NamespaceStorageInfoProto> namespaceStorageInfos =
                documentStorageInfo.getNamespaceStorageInfoList();
        for (int i = 0; i < namespaceStorageInfos.size(); i++) {
            NamespaceStorageInfoProto namespaceStorageInfo = namespaceStorageInfos.get(i);
            // The namespace from icing lib is already the prefixed format
            if (prefixedNamespaces.contains(namespaceStorageInfo.getNamespace())) {
                if (namespaceStorageInfo.getNumAliveDocuments() > 0) {
                    aliveNamespaces++;
                    aliveDocuments += namespaceStorageInfo.getNumAliveDocuments();
                }
                expiredDocuments += namespaceStorageInfo.getNumExpiredDocuments();
            }
        }
        int namespaceDocuments = aliveDocuments + expiredDocuments;

        // Since we don't have the exact size of all the documents, we do an estimation. Note
        // that while the total storage takes into account schema, index, etc. in addition to
        // documents, we'll only calculate the percentage based on number of documents a
        // client has.
        return new StorageInfo.Builder()
                .setSizeBytes((long) (namespaceDocuments * 1.0 / totalDocuments * totalStorageSize))
                .setAliveDocumentsCount(aliveDocuments)
                .setAliveNamespacesCount(aliveNamespaces)
                .build();
    }

    /**
     * Persists all update/delete requests to the disk.
     *
     * <p>If the app crashes after a call to PersistToDisk(), Icing would be able to fully recover
     * all data written up to this point without a costly recovery process.
     *
     * <p>If the app crashes before a call to PersistToDisk(), Icing would trigger a costly recovery
     * process in next initialization. After that, Icing would still be able to recover all written
     * data.
     *
     * @throws AppSearchException on any error that AppSearch persist data to disk.
     */
    public void persistToDisk() throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            PersistToDiskResultProto persistToDiskResultProto =
                    mIcingSearchEngineLocked.persistToDisk(PersistType.Code.FULL);
            checkSuccess(persistToDiskResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Clears documents and schema across all packages and databaseNames.
     *
     * <p>This method also clear all data in {@link VisibilityStore}, an {@link
     * #initializeVisibilityStore()} must be called after this.
     *
     * <p>This method belongs to mutate group.
     *
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @GuardedBy("mReadWriteLock")
    private void resetLocked() throws AppSearchException {
        ResetResultProto resetResultProto = mIcingSearchEngineLocked.reset();
        mOptimizeIntervalCountLocked = 0;
        mSchemaMapLocked.clear();
        mNamespaceMapLocked.clear();

        // Must be called after everything else since VisibilityStore may repopulate
        // IcingSearchEngine with an initial schema.
        mVisibilityStoreLocked.handleReset();
        checkSuccess(resetResultProto.getStatus());
    }

    /** Wrapper around schema changes */
    @VisibleForTesting
    static class RewrittenSchemaResults {
        // Any prefixed types that used to exist in the schema, but are deleted in the new one.
        final Set<String> mDeletedPrefixedTypes = new ArraySet<>();

        // Prefixed types that were part of the new schema.
        final Set<String> mRewrittenPrefixedTypes = new ArraySet<>();
    }

    /**
     * Rewrites all types mentioned in the given {@code newSchema} to prepend {@code prefix}.
     * Rewritten types will be added to the {@code existingSchema}.
     *
     * @param prefix The full prefix to prepend to the schema.
     * @param existingSchema A schema that may contain existing types from across all prefixes. Will
     *     be mutated to contain the properly rewritten schema types from {@code newSchema}.
     * @param newSchema Schema with types to add to the {@code existingSchema}.
     * @return a RewrittenSchemaResults that contains all prefixed schema type names in the given
     *     prefix as well as a set of schema types that were deleted.
     */
    @VisibleForTesting
    static RewrittenSchemaResults rewriteSchema(
            @NonNull String prefix,
            @NonNull SchemaProto.Builder existingSchema,
            @NonNull SchemaProto newSchema)
            throws AppSearchException {
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
        rewrittenSchemaResults.mRewrittenPrefixedTypes.addAll(newTypesToProto.keySet());

        // Combine the existing schema (which may have types from other prefixes) with this
        // prefix's new schema. Modifies the existingSchemaBuilder.
        // Check if we need to replace any old schema types with the new ones.
        for (int i = 0; i < existingSchema.getTypesCount(); i++) {
            String schemaType = existingSchema.getTypes(i).getSchemaType();
            SchemaTypeConfigProto newProto = newTypesToProto.remove(schemaType);
            if (newProto != null) {
                // Replacement
                existingSchema.setTypes(i, newProto);
            } else if (prefix.equals(getPrefix(schemaType))) {
                // All types existing before but not in newSchema should be removed.
                existingSchema.removeTypes(i);
                --i;
                rewrittenSchemaResults.mDeletedPrefixedTypes.add(schemaType);
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
     * Removes any prefixes from types and namespaces mentioned anywhere in {@code documentBuilder}.
     *
     * @param documentBuilder The document to mutate
     * @return Prefix name that was removed from the document.
     * @throws AppSearchException if there are unexpected database prefixing errors.
     */
    @NonNull
    @VisibleForTesting
    static String removePrefixesFromDocument(@NonNull DocumentProto.Builder documentBuilder)
            throws AppSearchException {
        // Rewrite the type name and namespace to remove the prefix.
        String schemaPrefix = getPrefix(documentBuilder.getSchema());
        String namespacePrefix = getPrefix(documentBuilder.getNamespace());

        if (!schemaPrefix.equals(namespacePrefix)) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR,
                    "Found unexpected"
                            + " multiple prefix names in document: "
                            + schemaPrefix
                            + ", "
                            + namespacePrefix);
        }

        documentBuilder.setSchema(removePrefix(documentBuilder.getSchema()));
        documentBuilder.setNamespace(removePrefix(documentBuilder.getNamespace()));

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
                    String nestedPrefix = removePrefixesFromDocument(derivedDocumentBuilder);
                    if (!nestedPrefix.equals(schemaPrefix)) {
                        throw new AppSearchException(
                                AppSearchResult.RESULT_INTERNAL_ERROR,
                                "Found unexpected multiple prefix names in document: "
                                        + schemaPrefix
                                        + ", "
                                        + nestedPrefix);
                    }
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }

        return schemaPrefix;
    }

    /**
     * Rewrites the search spec filters with {@code prefixes}.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @param searchSpecBuilder Client-provided SearchSpec
     * @param prefixes Prefixes that we should prepend to all our filters
     * @param allowedPrefixedSchemas Prefixed schemas that the client is allowed to query over. This
     *     supersedes the schema filters that may exist on the {@code searchSpecBuilder}.
     * @return false if none there would be nothing to search over.
     */
    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    boolean rewriteSearchSpecForPrefixesLocked(
            @NonNull SearchSpecProto.Builder searchSpecBuilder,
            @NonNull Set<String> prefixes,
            @NonNull Set<String> allowedPrefixedSchemas) {
        // Create a copy since retainAll() modifies the original set.
        Set<String> existingPrefixes = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingPrefixes.retainAll(prefixes);

        if (existingPrefixes.isEmpty()) {
            // None of the prefixes exist, empty query.
            return false;
        }

        if (allowedPrefixedSchemas.isEmpty()) {
            // Not allowed to search over any schemas, empty query.
            return false;
        }

        // Clear the schema type filters since we'll be rewriting them with the
        // allowedPrefixedSchemas.
        searchSpecBuilder.clearSchemaTypeFilters();
        searchSpecBuilder.addAllSchemaTypeFilters(allowedPrefixedSchemas);

        // Cache the namespaces before clearing everything.
        List<String> namespaceFilters = searchSpecBuilder.getNamespaceFiltersList();
        searchSpecBuilder.clearNamespaceFilters();

        // Rewrite non-schema filters to include a prefix.
        for (String prefix : existingPrefixes) {
            // TODO(b/169883602): We currently grab every namespace for every prefix. We can
            //  optimize this by checking if a prefix has any allowedSchemaTypes. If not, that
            //  means we don't want to query over anything in that prefix anyways, so we don't
            //  need to grab its namespaces either.

            // Empty namespaces on the search spec means to query over all namespaces.
            Set<String> existingNamespaces = mNamespaceMapLocked.get(prefix);
            if (namespaceFilters.isEmpty()) {
                // Include all namespaces
                searchSpecBuilder.addAllNamespaceFilters(existingNamespaces);
            } else {
                // Prefix the given namespaces.
                for (int i = 0; i < namespaceFilters.size(); i++) {
                    String prefixedNamespace = prefix + namespaceFilters.get(i);
                    if (existingNamespaces.contains(prefixedNamespace)) {
                        searchSpecBuilder.addNamespaceFilters(prefixedNamespace);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Returns the set of allowed prefixed schemas that the {@code prefix} can query while taking
     * into account the {@code searchSpec} schema filters.
     *
     * <p>This only checks intersection of schema filters on the search spec with those that the
     * prefix owns itself. This does not check global query permissions.
     */
    @GuardedBy("mReadWriteLock")
    private Set<String> getAllowedPrefixSchemasLocked(
            @NonNull String prefix, @NonNull SearchSpec searchSpec) {
        Set<String> allowedPrefixedSchemas = new ArraySet<>();

        // Add all the schema filters the client specified.
        List<String> schemaFilters = searchSpec.getFilterSchemas();
        for (int i = 0; i < schemaFilters.size(); i++) {
            allowedPrefixedSchemas.add(prefix + schemaFilters.get(i));
        }

        if (allowedPrefixedSchemas.isEmpty()) {
            // If the client didn't specify any schema filters, search over all of their schemas
            Set<String> prefixedSchemas = mSchemaMapLocked.get(prefix);
            if (prefixedSchemas != null) {
                allowedPrefixedSchemas.addAll(prefixedSchemas);
            }
        }
        return allowedPrefixedSchemas;
    }

    /**
     * Rewrites the typePropertyMasks that exist in {@code prefixes}.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @param resultSpecBuilder ResultSpecs as specified by client
     * @param prefixes Prefixes that we should prepend to all our filters
     * @param allowedPrefixedSchemas Prefixed schemas that the client is allowed to query over.
     */
    @VisibleForTesting
    @GuardedBy("mReadWriteLock")
    void rewriteResultSpecForPrefixesLocked(
            @NonNull ResultSpecProto.Builder resultSpecBuilder,
            @NonNull Set<String> prefixes,
            @NonNull Set<String> allowedPrefixedSchemas) {
        // Create a copy since retainAll() modifies the original set.
        Set<String> existingPrefixes = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingPrefixes.retainAll(prefixes);

        List<TypePropertyMask> prefixedTypePropertyMasks = new ArrayList<>();
        // Rewrite filters to include a database prefix.
        for (String prefix : existingPrefixes) {
            // Qualify the given schema types
            for (TypePropertyMask typePropertyMask : resultSpecBuilder.getTypePropertyMasksList()) {
                String unprefixedType = typePropertyMask.getSchemaType();
                boolean isWildcard =
                        unprefixedType.equals(SearchSpec.PROJECTION_SCHEMA_TYPE_WILDCARD);
                String prefixedType = isWildcard ? unprefixedType : prefix + unprefixedType;
                if (isWildcard || allowedPrefixedSchemas.contains(prefixedType)) {
                    prefixedTypePropertyMasks.add(
                            typePropertyMask.toBuilder().setSchemaType(prefixedType).build());
                }
            }
        }
        resultSpecBuilder
                .clearTypePropertyMasks()
                .addAllTypePropertyMasks(prefixedTypePropertyMasks);
    }

    /**
     * Adds result groupings for each namespace in each package being queried for.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @param resultSpecBuilder ResultSpecs as specified by client
     * @param prefixes Prefixes that we should prepend to all our filters
     * @param maxNumResults The maximum number of results for each grouping to support.
     */
    @GuardedBy("mReadWriteLock")
    private void addPerPackagePerNamespaceResultGroupingsLocked(
            @NonNull ResultSpecProto.Builder resultSpecBuilder,
            @NonNull Set<String> prefixes,
            int maxNumResults) {
        Set<String> existingPrefixes = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingPrefixes.retainAll(prefixes);

        // Create a map for package+namespace to prefixedNamespaces. This is NOT necessarily the
        // same as the list of namespaces. If one package has multiple databases, each with the same
        // namespace, then those should be grouped together.
        Map<String, List<String>> packageAndNamespaceToNamespaces = new ArrayMap<>();
        for (String prefix : existingPrefixes) {
            Set<String> prefixedNamespaces = mNamespaceMapLocked.get(prefix);
            String packageName = getPackageName(prefix);
            // Create a new prefix without the database name. This will allow us to group namespaces
            // that have the same name and package but a different database name together.
            String emptyDatabasePrefix = createPrefix(packageName, /*databaseName*/ "");
            for (String prefixedNamespace : prefixedNamespaces) {
                String namespace;
                try {
                    namespace = removePrefix(prefixedNamespace);
                } catch (AppSearchException e) {
                    // This should never happen. Skip this namespace if it does.
                    Log.e(TAG, "Prefixed namespace " + prefixedNamespace + " is malformed.");
                    continue;
                }
                String emptyDatabasePrefixedNamespace = emptyDatabasePrefix + namespace;
                List<String> namespaceList =
                        packageAndNamespaceToNamespaces.get(emptyDatabasePrefixedNamespace);
                if (namespaceList == null) {
                    namespaceList = new ArrayList<>();
                    packageAndNamespaceToNamespaces.put(
                            emptyDatabasePrefixedNamespace, namespaceList);
                }
                namespaceList.add(prefixedNamespace);
            }
        }

        for (List<String> namespaces : packageAndNamespaceToNamespaces.values()) {
            resultSpecBuilder.addResultGroupings(
                    ResultSpecProto.ResultGrouping.newBuilder()
                            .addAllNamespaces(namespaces)
                            .setMaxResults(maxNumResults));
        }
    }

    /**
     * Adds result groupings for each package being queried for.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @param resultSpecBuilder ResultSpecs as specified by client
     * @param prefixes Prefixes that we should prepend to all our filters
     * @param maxNumResults The maximum number of results for each grouping to support.
     */
    @GuardedBy("mReadWriteLock")
    private void addPerPackageResultGroupingsLocked(
            @NonNull ResultSpecProto.Builder resultSpecBuilder,
            @NonNull Set<String> prefixes,
            int maxNumResults) {
        Set<String> existingPrefixes = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingPrefixes.retainAll(prefixes);

        // Build up a map of package to namespaces.
        Map<String, List<String>> packageToNamespacesMap = new ArrayMap<>();
        for (String prefix : existingPrefixes) {
            Set<String> prefixedNamespaces = mNamespaceMapLocked.get(prefix);
            String packageName = getPackageName(prefix);
            List<String> packageNamespaceList = packageToNamespacesMap.get(packageName);
            if (packageNamespaceList == null) {
                packageNamespaceList = new ArrayList<>();
                packageToNamespacesMap.put(packageName, packageNamespaceList);
            }
            packageNamespaceList.addAll(prefixedNamespaces);
        }

        for (List<String> prefixedNamespaces : packageToNamespacesMap.values()) {
            resultSpecBuilder.addResultGroupings(
                    ResultSpecProto.ResultGrouping.newBuilder()
                            .addAllNamespaces(prefixedNamespaces)
                            .setMaxResults(maxNumResults));
        }
    }

    /**
     * Adds result groupings for each namespace being queried for.
     *
     * <p>This method should be only called in query methods and get the READ lock to keep thread
     * safety.
     *
     * @param resultSpecBuilder ResultSpecs as specified by client
     * @param prefixes Prefixes that we should prepend to all our filters
     * @param maxNumResults The maximum number of results for each grouping to support.
     */
    @GuardedBy("mReadWriteLock")
    private void addPerNamespaceResultGroupingsLocked(
            @NonNull ResultSpecProto.Builder resultSpecBuilder,
            @NonNull Set<String> prefixes,
            int maxNumResults) {
        Set<String> existingPrefixes = new ArraySet<>(mNamespaceMapLocked.keySet());
        existingPrefixes.retainAll(prefixes);

        // Create a map of namespace to prefixedNamespaces. This is NOT necessarily the
        // same as the list of namespaces. If a namespace exists under different packages and/or
        // different databases, they should still be grouped together.
        Map<String, List<String>> namespaceToPrefixedNamespaces = new ArrayMap<>();
        for (String prefix : existingPrefixes) {
            Set<String> prefixedNamespaces = mNamespaceMapLocked.get(prefix);
            for (String prefixedNamespace : prefixedNamespaces) {
                String namespace;
                try {
                    namespace = removePrefix(prefixedNamespace);
                } catch (AppSearchException e) {
                    // This should never happen. Skip this namespace if it does.
                    Log.e(TAG, "Prefixed namespace " + prefixedNamespace + " is malformed.");
                    continue;
                }
                List<String> groupedPrefixedNamespaces =
                        namespaceToPrefixedNamespaces.get(namespace);
                if (groupedPrefixedNamespaces == null) {
                    groupedPrefixedNamespaces = new ArrayList<>();
                    namespaceToPrefixedNamespaces.put(namespace, groupedPrefixedNamespaces);
                }
                groupedPrefixedNamespaces.add(prefixedNamespace);
            }
        }

        for (List<String> namespaces : namespaceToPrefixedNamespaces.values()) {
            resultSpecBuilder.addResultGroupings(
                    ResultSpecProto.ResultGrouping.newBuilder()
                            .addAllNamespaces(namespaces)
                            .setMaxResults(maxNumResults));
        }
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

    /** Returns a set of all prefixes AppSearchImpl knows about. */
    // TODO(b/180058203): Remove this method once platform has switched away from using this method.
    @GuardedBy("mReadWriteLock")
    @NonNull
    Set<String> getPrefixesLocked() {
        return mSchemaMapLocked.keySet();
    }

    @NonNull
    static String createPrefix(@NonNull String packageName, @NonNull String databaseName) {
        return packageName + PACKAGE_DELIMITER + databaseName + DATABASE_DELIMITER;
    }

    /**
     * Returns the package name that's contained within the {@code prefix}.
     *
     * @param prefix Prefix string that contains the package name inside of it. The package name
     *     must be in the front of the string, and separated from the rest of the string by the
     *     {@link #PACKAGE_DELIMITER}.
     * @return Valid package name.
     */
    @NonNull
    private static String getPackageName(@NonNull String prefix) {
        int delimiterIndex = prefix.indexOf(PACKAGE_DELIMITER);
        if (delimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain package delimiter: " + prefix);
            return "";
        }
        return prefix.substring(0, delimiterIndex);
    }

    /**
     * Returns the database name that's contained within the {@code prefix}.
     *
     * @param prefix Prefix string that contains the database name inside of it. The database name
     *     must be between the {@link #PACKAGE_DELIMITER} and {@link #DATABASE_DELIMITER}
     * @return Valid database name.
     */
    @NonNull
    private static String getDatabaseName(@NonNull String prefix) {
        int packageDelimiterIndex = prefix.indexOf(PACKAGE_DELIMITER);
        int databaseDelimiterIndex = prefix.indexOf(DATABASE_DELIMITER);
        if (packageDelimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain package delimiter: " + prefix);
            return "";
        }
        if (databaseDelimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain database delimiter: " + prefix);
            return "";
        }
        return prefix.substring(packageDelimiterIndex + 1, databaseDelimiterIndex);
    }

    @NonNull
    private static String removePrefix(@NonNull String prefixedString) throws AppSearchException {
        // The prefix is made up of the package, then the database. So we only need to find the
        // database cutoff.
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
    private static String getPrefix(@NonNull String prefixedString) throws AppSearchException {
        int databaseDelimiterIndex = prefixedString.indexOf(DATABASE_DELIMITER);
        if (databaseDelimiterIndex == -1) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_UNKNOWN_ERROR,
                    "The databaseName prefixed value doesn't contain a valid database name.");
        }

        // Add 1 to include the char size of the DATABASE_DELIMITER
        return prefixedString.substring(0, databaseDelimiterIndex + 1);
    }

    private static void addToMap(
            Map<String, Set<String>> map, String prefix, String prefixedValue) {
        Set<String> values = map.get(prefix);
        if (values == null) {
            values = new ArraySet<>();
            map.put(prefix, values);
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
     * <p>This method should be only called after a mutation to local storage backend which deletes
     * a mass of data and could release lots resources after {@link IcingSearchEngine#optimize()}.
     *
     * <p>This method will trigger {@link IcingSearchEngine#getOptimizeInfo()} to check resources
     * that could be released for every {@link #CHECK_OPTIMIZE_INTERVAL} mutations.
     *
     * <p>{@link IcingSearchEngine#optimize()} should be called only if {@link
     * GetOptimizeInfoResultProto} shows there is enough resources could be released.
     *
     * @param mutationSize The number of how many mutations have been executed for current request.
     *     An inside counter will accumulates it. Once the counter reaches {@link
     *     #CHECK_OPTIMIZE_INTERVAL}, {@link IcingSearchEngine#getOptimizeInfo()} will be triggered
     *     and the counter will be reset.
     */
    public void checkForOptimize(int mutationSize) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            mOptimizeIntervalCountLocked += mutationSize;
            if (mOptimizeIntervalCountLocked >= CHECK_OPTIMIZE_INTERVAL) {
                checkForOptimize();
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Checks whether {@link IcingSearchEngine#optimize()} should be called to release resources.
     *
     * <p>This method will directly trigger {@link IcingSearchEngine#getOptimizeInfo()} to check
     * resources that could be released.
     *
     * <p>{@link IcingSearchEngine#optimize()} should be called only if {@link
     * GetOptimizeInfoResultProto} shows there is enough resources could be released.
     */
    public void checkForOptimize() throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            GetOptimizeInfoResultProto optimizeInfo = getOptimizeInfoResultLocked();
            checkSuccess(optimizeInfo.getStatus());
            mOptimizeIntervalCountLocked = 0;
            // Second threshold, decide when to call optimize().
            if (optimizeInfo.getOptimizableDocs() >= OPTIMIZE_THRESHOLD_DOC_COUNT
                    || optimizeInfo.getEstimatedOptimizableBytes() >= OPTIMIZE_THRESHOLD_BYTES) {
                optimize();
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
        // TODO(b/147699081): Return OptimizeResultProto & log lost data detail once we add
        //  a field to indicate lost_schema and lost_documents in OptimizeResultProto.
        //  go/icing-library-apis.
    }

    /**
     * Triggers {@link IcingSearchEngine#optimize()} directly.
     *
     * <p>This method should be only called as a scheduled task in AppSearch Platform backend.
     */
    public void optimize() throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            OptimizeResultProto optimizeResultProto = mIcingSearchEngineLocked.optimize();
            checkSuccess(optimizeResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /** Remove the rewritten schema types from any result documents. */
    @NonNull
    @VisibleForTesting
    static SearchResultPage rewriteSearchResultProto(@NonNull SearchResultProto searchResultProto)
            throws AppSearchException {
        // Parallel array of package names for each document search result.
        List<String> packageNames = new ArrayList<>(searchResultProto.getResultsCount());

        // Parallel array of database names for each document search result.
        List<String> databaseNames = new ArrayList<>(searchResultProto.getResultsCount());

        SearchResultProto.Builder resultsBuilder = searchResultProto.toBuilder();
        for (int i = 0; i < searchResultProto.getResultsCount(); i++) {
            SearchResultProto.ResultProto.Builder resultBuilder =
                    searchResultProto.getResults(i).toBuilder();
            DocumentProto.Builder documentBuilder = resultBuilder.getDocument().toBuilder();
            String prefix = removePrefixesFromDocument(documentBuilder);
            packageNames.add(getPackageName(prefix));
            databaseNames.add(getDatabaseName(prefix));
            resultBuilder.setDocument(documentBuilder);
            resultsBuilder.setResults(i, resultBuilder);
        }
        return SearchResultToProtoConverter.toSearchResultPage(
                resultsBuilder, packageNames, databaseNames);
    }

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    GetOptimizeInfoResultProto getOptimizeInfoResultLocked() {
        return mIcingSearchEngineLocked.getOptimizeInfo();
    }

    @GuardedBy("mReadWriteLock")
    @NonNull
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
        return new AppSearchException(
                ResultCodeToProtoConverter.toResultCode(statusProto.getCode()),
                statusProto.getMessage());
    }
}
