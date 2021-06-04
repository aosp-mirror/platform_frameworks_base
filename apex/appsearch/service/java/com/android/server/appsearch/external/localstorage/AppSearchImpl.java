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

import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.addPrefixToDocument;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.createPrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getDatabaseName;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPackageName;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.removePrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.removePrefixesFromDocument;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.ResultCodeToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SchemaToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchResultToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SearchSpecToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.SetSchemaResponseToProtoConverter;
import com.android.server.appsearch.external.localstorage.converter.TypePropertyPathToProtoConverter;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.RemoveStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;
import com.android.server.appsearch.visibilitystore.VisibilityStore;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_DOC_COUNT = 1000;
    @VisibleForTesting static final int OPTIMIZE_THRESHOLD_BYTES = 1_000_000; // 1MB
    @VisibleForTesting static final int CHECK_OPTIMIZE_INTERVAL = 100;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();

    private final LogUtil mLogUtil = new LogUtil(TAG);

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    final IcingSearchEngine mIcingSearchEngineLocked;

    @GuardedBy("mReadWriteLock")
    private final VisibilityStore mVisibilityStoreLocked;

    // This map contains schema types and SchemaTypeConfigProtos for all package-database
    // prefixes. It maps each package-database prefix to an inner-map. The inner-map maps each
    // prefixed schema type to its respective SchemaTypeConfigProto.
    @GuardedBy("mReadWriteLock")
    private final Map<String, Map<String, SchemaTypeConfigProto>> mSchemaMapLocked =
            new ArrayMap<>();

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
     *
     * <p>Clients can pass a {@link AppSearchLogger} here through their AppSearchSession, but it
     * can't be saved inside {@link AppSearchImpl}, because the impl will be shared by all the
     * sessions for the same package in JetPack.
     *
     * <p>Instead, logger instance needs to be passed to each individual method, like create, query
     * and putDocument.
     *
     * @param logger collects stats for initialization if provided.
     */
    @NonNull
    public static AppSearchImpl create(
            @NonNull File icingDir,
            @NonNull Context userContext,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        Objects.requireNonNull(icingDir);
        Objects.requireNonNull(userContext);

        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        InitializeStats.Builder initStatsBuilder = null;
        if (logger != null) {
            initStatsBuilder = new InitializeStats.Builder();
        }

        AppSearchImpl appSearchImpl = new AppSearchImpl(icingDir, userContext, initStatsBuilder);

        long prepareVisibilityStoreLatencyStartMillis = SystemClock.elapsedRealtime();
        appSearchImpl.initializeVisibilityStore();
        long prepareVisibilityStoreLatencyEndMillis = SystemClock.elapsedRealtime();

        if (logger != null) {
            initStatsBuilder
                    .setTotalLatencyMillis(
                            (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                    .setPrepareVisibilityStoreLatencyMillis(
                            (int)
                                    (prepareVisibilityStoreLatencyEndMillis
                                            - prepareVisibilityStoreLatencyStartMillis));
            logger.logStats(initStatsBuilder.build());
        }

        return appSearchImpl;
    }

    /** @param initStatsBuilder collects stats for initialization if provided. */
    private AppSearchImpl(
            @NonNull File icingDir,
            @NonNull Context userContext,
            @Nullable InitializeStats.Builder initStatsBuilder)
            throws AppSearchException {
        mReadWriteLock.writeLock().lock();

        try {
            // We synchronize here because we don't want to call IcingSearchEngine.initialize() more
            // than once. It's unnecessary and can be a costly operation.
            IcingSearchEngineOptions options =
                    IcingSearchEngineOptions.newBuilder()
                            .setBaseDir(icingDir.getAbsolutePath())
                            .build();
            mLogUtil.piiTrace("Constructing IcingSearchEngine, request", options);
            mIcingSearchEngineLocked = new IcingSearchEngine(options);
            mLogUtil.piiTrace(
                    "Constructing IcingSearchEngine, response",
                    Objects.hashCode(mIcingSearchEngineLocked));

            mVisibilityStoreLocked = new VisibilityStore(this, userContext);

            // The core initialization procedure. If any part of this fails, we bail into
            // resetLocked(), deleting all data (but hopefully allowing AppSearchImpl to come up).
            try {
                mLogUtil.piiTrace("icingSearchEngine.initialize, request");
                InitializeResultProto initializeResultProto = mIcingSearchEngineLocked.initialize();
                mLogUtil.piiTrace(
                        "icingSearchEngine.initialize, response",
                        initializeResultProto.getStatus(),
                        initializeResultProto);

                if (initStatsBuilder != null) {
                    initStatsBuilder
                            .setStatusCode(
                                    statusProtoToResultCode(initializeResultProto.getStatus()))
                            // TODO(b/173532925) how to get DeSyncs value
                            .setHasDeSync(false);
                    AppSearchLoggerHelper.copyNativeStats(
                            initializeResultProto.getInitializeStats(), initStatsBuilder);
                }

                checkSuccess(initializeResultProto.getStatus());

                long prepareSchemaAndNamespacesLatencyStartMillis = SystemClock.elapsedRealtime();
                SchemaProto schemaProto = getSchemaProtoLocked();

                mLogUtil.piiTrace("init:getAllNamespaces, request");
                GetAllNamespacesResultProto getAllNamespacesResultProto =
                        mIcingSearchEngineLocked.getAllNamespaces();
                mLogUtil.piiTrace(
                        "init:getAllNamespaces, response",
                        getAllNamespacesResultProto.getNamespacesCount(),
                        getAllNamespacesResultProto);

                if (initStatsBuilder != null) {
                    initStatsBuilder
                            .setStatusCode(
                                    statusProtoToResultCode(
                                            getAllNamespacesResultProto.getStatus()))
                            .setPrepareSchemaAndNamespacesLatencyMillis(
                                    (int)
                                            (SystemClock.elapsedRealtime()
                                                    - prepareSchemaAndNamespacesLatencyStartMillis));
                }

                checkSuccess(getAllNamespacesResultProto.getStatus());

                // Populate schema map
                for (SchemaTypeConfigProto schema : schemaProto.getTypesList()) {
                    String prefixedSchemaType = schema.getSchemaType();
                    addToMap(mSchemaMapLocked, getPrefix(prefixedSchemaType), schema);
                }

                // Populate namespace map
                for (String prefixedNamespace : getAllNamespacesResultProto.getNamespacesList()) {
                    addToMap(mNamespaceMapLocked, getPrefix(prefixedNamespace), prefixedNamespace);
                }

                // logging prepare_schema_and_namespaces latency
                if (initStatsBuilder != null) {
                    initStatsBuilder.setPrepareSchemaAndNamespacesLatencyMillis(
                            (int)
                                    (SystemClock.elapsedRealtime()
                                            - prepareSchemaAndNamespacesLatencyStartMillis));
                }

                mLogUtil.piiTrace("Init completed successfully");

            } catch (AppSearchException e) {
                // Some error. Reset and see if it fixes it.
                Log.e(TAG, "Error initializing, resetting IcingSearchEngine.", e);
                if (initStatsBuilder != null) {
                    initStatsBuilder.setStatusCode(e.getResultCode());
                }
                resetLocked(initStatsBuilder);
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
            mLogUtil.piiTrace("Initializing VisibilityStore, request");
            mVisibilityStoreLocked.initialize();
            mLogUtil.piiTrace("Initializing VisibilityStore, response");
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
            persistToDisk(PersistType.Code.FULL);
            mLogUtil.piiTrace("icingSearchEngine.close, request");
            mIcingSearchEngineLocked.close();
            mLogUtil.piiTrace("icingSearchEngine.close, response");
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
            SchemaProto finalSchema = existingSchemaBuilder.build();
            mLogUtil.piiTrace("setSchema, request", finalSchema.getTypesCount(), finalSchema);
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(finalSchema, forceOverride);
            mLogUtil.piiTrace(
                    "setSchema, response", setSchemaResultProto.getStatus(), setSchemaResultProto);

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
            for (SchemaTypeConfigProto schemaTypeConfigProto :
                    rewrittenSchemaResults.mRewrittenPrefixedTypes.values()) {
                addToMap(mSchemaMapLocked, prefix, schemaTypeConfigProto);
            }

            for (String schemaType : rewrittenSchemaResults.mDeletedPrefixedTypes) {
                removeFromMap(mSchemaMapLocked, prefix, schemaType);
            }

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
                    packageName,
                    databaseName,
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
            mLogUtil.piiTrace("getAllNamespaces, request");
            // We can't just use mNamespaceMap here because we have no way to prune namespaces from
            // mNamespaceMap when they have no more documents (e.g. after setting schema to empty or
            // using deleteByQuery).
            GetAllNamespacesResultProto getAllNamespacesResultProto =
                    mIcingSearchEngineLocked.getAllNamespaces();
            mLogUtil.piiTrace(
                    "getAllNamespaces, response",
                    getAllNamespacesResultProto.getNamespacesCount(),
                    getAllNamespacesResultProto);
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
            DocumentProto finalDocument = documentBuilder.build();

            mLogUtil.piiTrace("putDocument, request", finalDocument.getUri(), finalDocument);
            PutResultProto putResultProto = mIcingSearchEngineLocked.put(documentBuilder.build());
            mLogUtil.piiTrace("putDocument, response", putResultProto.getStatus(), putResultProto);
            addToMap(mNamespaceMapLocked, prefix, documentBuilder.getNamespace());

            // Logging stats
            if (pStatsBuilder != null) {
                pStatsBuilder
                        .getGeneralStatsBuilder()
                        .setStatusCode(statusProtoToResultCode(putResultProto.getStatus()));
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
     * Retrieves a document from the AppSearch index by namespace and document ID.
     *
     * <p>This method belongs to query group.
     *
     * @param packageName The package that owns this document.
     * @param databaseName The databaseName this document resides in.
     * @param namespace The namespace this document resides in.
     * @param id The ID of the document to get.
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
            @NonNull String id,
            @NonNull Map<String, List<String>> typePropertyPaths)
            throws AppSearchException {
        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();
            String prefix = createPrefix(packageName, databaseName);
            List<TypePropertyMask> nonPrefixedPropertyMasks =
                    TypePropertyPathToProtoConverter.toTypePropertyMaskList(typePropertyPaths);
            List<TypePropertyMask> prefixedPropertyMasks =
                    new ArrayList<>(nonPrefixedPropertyMasks.size());
            for (int i = 0; i < nonPrefixedPropertyMasks.size(); ++i) {
                TypePropertyMask typePropertyMask = nonPrefixedPropertyMasks.get(i);
                String nonPrefixedType = typePropertyMask.getSchemaType();
                String prefixedType =
                        nonPrefixedType.equals(
                                        GetByDocumentIdRequest.PROJECTION_SCHEMA_TYPE_WILDCARD)
                                ? nonPrefixedType
                                : prefix + nonPrefixedType;
                prefixedPropertyMasks.add(
                        typePropertyMask.toBuilder().setSchemaType(prefixedType).build());
            }
            GetResultSpecProto getResultSpec =
                    GetResultSpecProto.newBuilder()
                            .addAllTypePropertyMasks(prefixedPropertyMasks)
                            .build();

            String finalNamespace = createPrefix(packageName, databaseName) + namespace;
            if (mLogUtil.isPiiTraceEnabled()) {
                mLogUtil.piiTrace(
                        "getDocument, request", finalNamespace + ", " + id + "," + getResultSpec);
            }
            GetResultProto getResultProto =
                    mIcingSearchEngineLocked.get(finalNamespace, id, getResultSpec);
            mLogUtil.piiTrace("getDocument, response", getResultProto.getStatus(), getResultProto);
            checkSuccess(getResultProto.getStatus());

            // The schema type map cannot be null at this point. It could only be null if no
            // schema had ever been set for that prefix. Given we have retrieved a document from
            // the index, we know a schema had to have been set.
            Map<String, SchemaTypeConfigProto> schemaTypeMap = mSchemaMapLocked.get(prefix);
            DocumentProto.Builder documentBuilder = getResultProto.getDocument().toBuilder();
            removePrefixesFromDocument(documentBuilder);
            return GenericDocumentToProtoConverter.toGenericDocument(
                    documentBuilder.build(), prefix, schemaTypeMap);
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
     * @param logger logger to collect query stats
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage query(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchStats.Builder sStatsBuilder = null;
        if (logger != null) {
            sStatsBuilder =
                    new SearchStats.Builder(SearchStats.VISIBILITY_SCOPE_LOCAL, packageName)
                            .setDatabase(databaseName);
        }

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
                    searchSpec,
                    sStatsBuilder);
        } finally {
            mReadWriteLock.readLock().unlock();
            if (logger != null) {
                sStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis));
                logger.logStats(sStatsBuilder.build());
            }
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
     * @param logger logger to collect globalQuery stats
     * @return The results of performing this search. It may contain an empty list of results if no
     *     documents matched the query.
     * @throws AppSearchException on IcingSearchEngine error.
     */
    @NonNull
    public SearchResultPage globalQuery(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull String callerPackageName,
            int callerUid,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchStats.Builder sStatsBuilder = null;
        if (logger != null) {
            sStatsBuilder =
                    new SearchStats.Builder(SearchStats.VISIBILITY_SCOPE_GLOBAL, callerPackageName);
        }

        mReadWriteLock.readLock().lock();
        try {
            throwIfClosedLocked();

            // Convert package filters to prefix filters
            Set<String> packageFilters = new ArraySet<>(searchSpec.getFilterPackageNames());
            Set<String> prefixFilters = new ArraySet<>();
            if (packageFilters.isEmpty()) {
                // Client didn't restrict their search over packages. Try to query over all
                // packages/prefixes
                prefixFilters = mNamespaceMapLocked.keySet();
            } else {
                // Client did restrict their search over packages. Only include the prefixes that
                // belong to the specified packages.
                for (String prefix : mNamespaceMapLocked.keySet()) {
                    String packageName = getPackageName(prefix);
                    if (packageFilters.contains(packageName)) {
                        prefixFilters.add(prefix);
                    }
                }
            }

            // Convert schema filters to prefixed schema filters
            ArraySet<String> prefixedSchemaFilters = new ArraySet<>();
            for (String prefix : prefixFilters) {
                List<String> schemaFilters = searchSpec.getFilterSchemas();
                if (schemaFilters.isEmpty()) {
                    // Client didn't specify certain schemas to search over, check all schemas
                    prefixedSchemaFilters.addAll(mSchemaMapLocked.get(prefix).keySet());
                } else {
                    // Client specified some schemas to search over, check each one
                    for (int i = 0; i < schemaFilters.size(); i++) {
                        prefixedSchemaFilters.add(prefix + schemaFilters.get(i));
                    }
                }
            }

            // Remove the schemas the client is not allowed to search over
            Iterator<String> prefixedSchemaIt = prefixedSchemaFilters.iterator();
            while (prefixedSchemaIt.hasNext()) {
                String prefixedSchema = prefixedSchemaIt.next();
                String packageName = getPackageName(prefixedSchema);

                boolean allow;
                if (packageName.equals(callerPackageName)) {
                    // Callers can always retrieve their own data
                    allow = true;
                } else {
                    String databaseName = getDatabaseName(prefixedSchema);
                    allow =
                            mVisibilityStoreLocked.isSchemaSearchableByCaller(
                                    packageName,
                                    databaseName,
                                    prefixedSchema,
                                    callerPackageName,
                                    callerUid);
                }

                if (!allow) {
                    prefixedSchemaIt.remove();
                }
            }

            return doQueryLocked(
                    prefixFilters,
                    prefixedSchemaFilters,
                    queryExpression,
                    searchSpec,
                    sStatsBuilder);
        } finally {
            mReadWriteLock.readLock().unlock();

            if (logger != null) {
                sStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis));
                logger.logStats(sStatsBuilder.build());
            }
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
            @NonNull SearchSpec searchSpec,
            @Nullable SearchStats.Builder sStatsBuilder)
            throws AppSearchException {
        long rewriteSearchSpecLatencyStartMillis = SystemClock.elapsedRealtime();

        SearchSpecProto.Builder searchSpecBuilder =
                SearchSpecToProtoConverter.toSearchSpecProto(searchSpec).toBuilder()
                        .setQuery(queryExpression);
        // rewriteSearchSpecForPrefixesLocked will return false if there is nothing to search
        // over given their search filters, so we can return an empty SearchResult and skip
        // sending request to Icing.
        if (!rewriteSearchSpecForPrefixesLocked(
                searchSpecBuilder, prefixes, allowedPrefixedSchemas)) {
            if (sStatsBuilder != null) {
                sStatsBuilder.setRewriteSearchSpecLatencyMillis(
                        (int)
                                (SystemClock.elapsedRealtime()
                                        - rewriteSearchSpecLatencyStartMillis));
            }
            return new SearchResultPage(Bundle.EMPTY);
        }

        // rewriteSearchSpec, rewriteResultSpec and convertScoringSpec are all counted in
        // rewriteSearchSpecLatencyMillis
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
        SearchSpecProto finalSearchSpec = searchSpecBuilder.build();
        ResultSpecProto finalResultSpec = resultSpecBuilder.build();

        long rewriteSearchSpecLatencyEndMillis = SystemClock.elapsedRealtime();

        if (mLogUtil.isPiiTraceEnabled()) {
            mLogUtil.piiTrace(
                    "search, request",
                    finalSearchSpec.getQuery(),
                    finalSearchSpec + ", " + scoringSpec + ", " + finalResultSpec);
        }
        SearchResultProto searchResultProto =
                mIcingSearchEngineLocked.search(finalSearchSpec, scoringSpec, finalResultSpec);
        mLogUtil.piiTrace(
                "search, response", searchResultProto.getResultsCount(), searchResultProto);

        if (sStatsBuilder != null) {
            sStatsBuilder
                    .setStatusCode(statusProtoToResultCode(searchResultProto.getStatus()))
                    .setRewriteSearchSpecLatencyMillis(
                            (int)
                                    (rewriteSearchSpecLatencyEndMillis
                                            - rewriteSearchSpecLatencyStartMillis));
            AppSearchLoggerHelper.copyNativeStats(searchResultProto.getQueryStats(), sStatsBuilder);
        }

        checkSuccess(searchResultProto.getStatus());

        long rewriteSearchResultLatencyStartMillis = SystemClock.elapsedRealtime();
        SearchResultPage resultPage = rewriteSearchResultProto(searchResultProto, mSchemaMapLocked);
        if (sStatsBuilder != null) {
            sStatsBuilder.setRewriteSearchResultLatencyMillis(
                    (int) (SystemClock.elapsedRealtime() - rewriteSearchResultLatencyStartMillis));
        }

        return resultPage;
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

            mLogUtil.piiTrace("getNextPage, request", nextPageToken);
            SearchResultProto searchResultProto =
                    mIcingSearchEngineLocked.getNextPage(nextPageToken);
            mLogUtil.piiTrace(
                    "getNextPage, response",
                    searchResultProto.getResultsCount(),
                    searchResultProto);
            checkSuccess(searchResultProto.getStatus());
            return rewriteSearchResultProto(searchResultProto, mSchemaMapLocked);
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

            mLogUtil.piiTrace("invalidateNextPageToken, request", nextPageToken);
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
            @NonNull String documentId,
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
                            .setDocumentUri(documentId)
                            .setUsageTimestampMs(usageTimestampMillis)
                            .setUsageType(usageType)
                            .build();

            mLogUtil.piiTrace("reportUsage, request", report.getDocumentUri(), report);
            ReportUsageResultProto result = mIcingSearchEngineLocked.reportUsage(report);
            mLogUtil.piiTrace("reportUsage, response", result.getStatus(), result);
            checkSuccess(result.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Removes the given document by id.
     *
     * <p>This method belongs to mutate group.
     *
     * @param packageName The package name that owns the document.
     * @param databaseName The databaseName the document is in.
     * @param namespace Namespace of the document to remove.
     * @param id ID of the document to remove.
     * @param removeStatsBuilder builder for {@link RemoveStats} to hold stats for remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void remove(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String namespace,
            @NonNull String id,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            String prefixedNamespace = createPrefix(packageName, databaseName) + namespace;
            if (mLogUtil.isPiiTraceEnabled()) {
                mLogUtil.piiTrace("removeById, request", prefixedNamespace + ", " + id);
            }
            DeleteResultProto deleteResultProto =
                    mIcingSearchEngineLocked.delete(prefixedNamespace, id);
            mLogUtil.piiTrace(
                    "removeById, response", deleteResultProto.getStatus(), deleteResultProto);

            if (removeStatsBuilder != null) {
                removeStatsBuilder.setStatusCode(
                        statusProtoToResultCode(deleteResultProto.getStatus()));
                AppSearchLoggerHelper.copyNativeStats(
                        deleteResultProto.getDeleteStats(), removeStatsBuilder);
            }
            checkSuccess(deleteResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
            if (removeStatsBuilder != null) {
                removeStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis));
            }
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
     * @param removeStatsBuilder builder for {@link RemoveStats} to hold stats for remove
     * @throws AppSearchException on IcingSearchEngine error.
     */
    public void removeByQuery(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @Nullable RemoveStats.Builder removeStatsBuilder)
            throws AppSearchException {
        long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
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
            SearchSpecProto finalSearchSpec = searchSpecBuilder.build();
            mLogUtil.piiTrace("removeByQuery, request", finalSearchSpec);
            DeleteByQueryResultProto deleteResultProto =
                    mIcingSearchEngineLocked.deleteByQuery(finalSearchSpec);
            mLogUtil.piiTrace(
                    "removeByQuery, response", deleteResultProto.getStatus(), deleteResultProto);

            if (removeStatsBuilder != null) {
                removeStatsBuilder.setStatusCode(
                        statusProtoToResultCode(deleteResultProto.getStatus()));
                // TODO(b/187206766) also log query stats here once IcingLib returns it
                AppSearchLoggerHelper.copyNativeStats(
                        deleteResultProto.getDeleteStats(), removeStatsBuilder);
            }

            // It seems that the caller wants to get success if the data matching the query is
            // not in the DB because it was not there or was successfully deleted.
            checkCodeOneOf(
                    deleteResultProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        } finally {
            mReadWriteLock.writeLock().unlock();
            if (removeStatsBuilder != null) {
                removeStatsBuilder.setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis));
            }
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
        mLogUtil.piiTrace("getStorageInfo, request");
        StorageInfoResultProto storageInfoResult = mIcingSearchEngineLocked.getStorageInfo();
        mLogUtil.piiTrace(
                "getStorageInfo, response", storageInfoResult.getStatus(), storageInfoResult);
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
     * <p>If the app crashes after a call to PersistToDisk with {@link PersistType.Code#FULL}, Icing
     * would be able to fully recover all data written up to this point without a costly recovery
     * process.
     *
     * <p>If the app crashes after a call to PersistToDisk with {@link PersistType.Code#LITE}, Icing
     * would trigger a costly recovery process in next initialization. After that, Icing would still
     * be able to recover all written data - excepting Usage data. Usage data is only guaranteed to
     * be safe after a call to PersistToDisk with {@link PersistType.Code#FULL}
     *
     * <p>If the app crashes after an update/delete request has been made, but before any call to
     * PersistToDisk, then all data in Icing will be lost.
     *
     * @param persistType the amount of data to persist. {@link PersistType.Code#LITE} will only
     *     persist the minimal amount of data to ensure all data can be recovered. {@link
     *     PersistType.Code#FULL} will persist all data necessary to prevent data loss without
     *     needing data recovery.
     * @throws AppSearchException on any error that AppSearch persist data to disk.
     */
    public void persistToDisk(@NonNull PersistType.Code persistType) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();

            mLogUtil.piiTrace("persistToDisk, request", persistType);
            PersistToDiskResultProto persistToDiskResultProto =
                    mIcingSearchEngineLocked.persistToDisk(persistType);
            mLogUtil.piiTrace(
                    "persistToDisk, response",
                    persistToDiskResultProto.getStatus(),
                    persistToDiskResultProto);
            checkSuccess(persistToDiskResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Remove all {@link AppSearchSchema}s and {@link GenericDocument}s under the given package.
     *
     * @param packageName The name of package to be removed.
     * @throws AppSearchException if we cannot remove the data.
     */
    public void clearPackageData(@NonNull String packageName) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();
            Set<String> existingPackages = getPackageToDatabases().keySet();
            if (existingPackages.contains(packageName)) {
                existingPackages.remove(packageName);
                prunePackageData(existingPackages);
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Remove all {@link AppSearchSchema}s and {@link GenericDocument}s that doesn't belong to any
     * of the given installed packages
     *
     * @param installedPackages The name of all installed package.
     * @throws AppSearchException if we cannot remove the data.
     */
    public void prunePackageData(@NonNull Set<String> installedPackages) throws AppSearchException {
        mReadWriteLock.writeLock().lock();
        try {
            throwIfClosedLocked();
            Map<String, Set<String>> packageToDatabases = getPackageToDatabases();
            if (installedPackages.containsAll(packageToDatabases.keySet())) {
                // No package got removed. We are good.
                return;
            }

            // Prune schema proto
            SchemaProto existingSchema = getSchemaProtoLocked();
            SchemaProto.Builder newSchemaBuilder = SchemaProto.newBuilder();
            for (int i = 0; i < existingSchema.getTypesCount(); i++) {
                String packageName = getPackageName(existingSchema.getTypes(i).getSchemaType());
                if (installedPackages.contains(packageName)) {
                    newSchemaBuilder.addTypes(existingSchema.getTypes(i));
                }
            }

            SchemaProto finalSchema = newSchemaBuilder.build();

            // Apply schema, set force override to true to remove all schemas and documents that
            // doesn't belong to any of these installed packages.
            mLogUtil.piiTrace(
                    "clearPackageData.setSchema, request",
                    finalSchema.getTypesCount(),
                    finalSchema);
            SetSchemaResultProto setSchemaResultProto =
                    mIcingSearchEngineLocked.setSchema(
                            finalSchema, /*ignoreErrorsAndDeleteDocuments=*/ true);
            mLogUtil.piiTrace(
                    "clearPackageData.setSchema, response",
                    setSchemaResultProto.getStatus(),
                    setSchemaResultProto);

            // Determine whether it succeeded.
            checkSuccess(setSchemaResultProto.getStatus());

            // Prune cached maps
            for (Map.Entry<String, Set<String>> entry : packageToDatabases.entrySet()) {
                String packageName = entry.getKey();
                Set<String> databaseNames = entry.getValue();
                if (!installedPackages.contains(packageName) && databaseNames != null) {
                    for (String databaseName : databaseNames) {
                        String removedPrefix = createPrefix(packageName, databaseName);
                        mSchemaMapLocked.remove(removedPrefix);
                        mNamespaceMapLocked.remove(removedPrefix);
                    }
                }
            }
            // TODO(b/145759910) clear visibility setting for package.
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
    private void resetLocked(@Nullable InitializeStats.Builder initStatsBuilder)
            throws AppSearchException {
        mLogUtil.piiTrace("icingSearchEngine.reset, request");
        ResetResultProto resetResultProto = mIcingSearchEngineLocked.reset();
        mLogUtil.piiTrace(
                "icingSearchEngine.reset, response",
                resetResultProto.getStatus(),
                resetResultProto);
        mOptimizeIntervalCountLocked = 0;
        mSchemaMapLocked.clear();
        mNamespaceMapLocked.clear();

        if (initStatsBuilder != null) {
            initStatsBuilder
                    .setHasReset(true)
                    .setResetStatusCode(statusProtoToResultCode(resetResultProto.getStatus()));
        }

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

        // Map of prefixed schema types to SchemaTypeConfigProtos that were part of the new schema.
        final Map<String, SchemaTypeConfigProto> mRewrittenPrefixedTypes = new ArrayMap<>();
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
        rewrittenSchemaResults.mRewrittenPrefixedTypes.putAll(newTypesToProto);

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
            if (existingNamespaces != null) {
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
            Map<String, SchemaTypeConfigProto> prefixedSchemaMap = mSchemaMapLocked.get(prefix);
            if (prefixedSchemaMap != null) {
                allowedPrefixedSchemas.addAll(prefixedSchemaMap.keySet());
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
            if (prefixedNamespaces == null) {
                continue;
            }
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
            if (prefixedNamespaces == null) {
                continue;
            }
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
            if (prefixedNamespaces == null) {
                continue;
            }
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
        mLogUtil.piiTrace("getSchema, request");
        GetSchemaResultProto schemaProto = mIcingSearchEngineLocked.getSchema();
        mLogUtil.piiTrace("getSchema, response", schemaProto.getStatus(), schemaProto);
        // TODO(b/161935693) check GetSchemaResultProto is success or not. Call reset() if it's not.
        // TODO(b/161935693) only allow GetSchemaResultProto NOT_FOUND on first run
        checkCodeOneOf(schemaProto.getStatus(), StatusProto.Code.OK, StatusProto.Code.NOT_FOUND);
        return schemaProto.getSchema();
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

    private static void addToMap(
            Map<String, Map<String, SchemaTypeConfigProto>> map,
            String prefix,
            SchemaTypeConfigProto schemaTypeConfigProto) {
        Map<String, SchemaTypeConfigProto> schemaTypeMap = map.get(prefix);
        if (schemaTypeMap == null) {
            schemaTypeMap = new ArrayMap<>();
            map.put(prefix, schemaTypeMap);
        }
        schemaTypeMap.put(schemaTypeConfigProto.getSchemaType(), schemaTypeConfigProto);
    }

    private static void removeFromMap(
            Map<String, Map<String, SchemaTypeConfigProto>> map, String prefix, String schemaType) {
        Map<String, SchemaTypeConfigProto> schemaTypeMap = map.get(prefix);
        if (schemaTypeMap != null) {
            schemaTypeMap.remove(schemaType);
        }
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

        throw new AppSearchException(
                ResultCodeToProtoConverter.toResultCode(statusProto.getCode()),
                statusProto.getMessage());
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
            mLogUtil.piiTrace("optimize, request");
            OptimizeResultProto optimizeResultProto = mIcingSearchEngineLocked.optimize();
            mLogUtil.piiTrace(
                    "optimize, response", optimizeResultProto.getStatus(), optimizeResultProto);
            checkSuccess(optimizeResultProto.getStatus());
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /** Remove the rewritten schema types from any result documents. */
    @NonNull
    @VisibleForTesting
    static SearchResultPage rewriteSearchResultProto(
            @NonNull SearchResultProto searchResultProto,
            @NonNull Map<String, Map<String, SchemaTypeConfigProto>> schemaMap)
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
                resultsBuilder, packageNames, databaseNames, schemaMap);
    }

    @GuardedBy("mReadWriteLock")
    @VisibleForTesting
    GetOptimizeInfoResultProto getOptimizeInfoResultLocked() {
        mLogUtil.piiTrace("getOptimizeInfo, request");
        GetOptimizeInfoResultProto result = mIcingSearchEngineLocked.getOptimizeInfo();
        mLogUtil.piiTrace("getOptimizeInfo, response", result.getStatus(), result);
        return result;
    }

    @GuardedBy("mReadWriteLock")
    @NonNull
    @VisibleForTesting
    VisibilityStore getVisibilityStoreLocked() {
        return mVisibilityStoreLocked;
    }

    /**
     * Converts an erroneous status code from the Icing status enums to the AppSearchResult enums.
     *
     * <p>Callers should ensure that the status code is not OK or WARNING_DATA_LOSS.
     *
     * @param statusProto StatusProto with error code to translate into an {@link AppSearchResult}
     *     code.
     * @return {@link AppSearchResult} error code
     */
    private static @AppSearchResult.ResultCode int statusProtoToResultCode(
            @NonNull StatusProto statusProto) {
        return ResultCodeToProtoConverter.toResultCode(statusProto.getCode());
    }
}
