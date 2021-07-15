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

package com.android.server.appsearch.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.StatsManager;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;
import android.util.StatsEvent;

import com.android.server.appsearch.AppSearchUserInstance;
import com.android.server.appsearch.AppSearchUserInstanceManager;

import com.google.android.icing.proto.DocumentStorageInfoProto;
import com.google.android.icing.proto.IndexStorageInfoProto;
import com.google.android.icing.proto.SchemaStoreStorageInfoProto;
import com.google.android.icing.proto.StorageInfoProto;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Implements statsd pullers for AppSearch.
 *
 * <p>This class registers pullers to statsd, which will be called once a day to obtain AppSearch
 * statistics that cannot be sent to statsd in real time by {@link PlatformLogger}.
 *
 * @hide
 */
public final class StatsCollector implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = "AppSearchStatsCollector";

    private static volatile StatsCollector sStatsCollector;
    private final StatsManager mStatsManager;

    /**
     * Gets an instance of {@link StatsCollector} to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static StatsCollector getInstance(@NonNull Context context,
            @NonNull Executor executor) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(executor);
        if (sStatsCollector == null) {
            synchronized (StatsCollector.class) {
                if (sStatsCollector == null) {
                    sStatsCollector = new StatsCollector(context, executor);
                }
            }
        }
        return sStatsCollector;
    }

    private StatsCollector(@NonNull Context context, @NonNull Executor executor) {
        mStatsManager = context.getSystemService(StatsManager.class);
        if (mStatsManager != null) {
            registerAtom(AppSearchStatsLog.APP_SEARCH_STORAGE_INFO, /*policy=*/ null, executor);
            Log.d(TAG, "atoms registered");
        } else {
            Log.e(TAG, "could not get StatsManager, atoms not registered");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link StatsManager#PULL_SUCCESS} with list of atoms (potentially empty) if pull
     * succeeded, {@link StatsManager#PULL_SKIP} if pull was too frequent or atom ID is
     * unexpected.
     */
    @Override
    public int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
        Objects.requireNonNull(data);
        switch (atomTag) {
            case AppSearchStatsLog.APP_SEARCH_STORAGE_INFO:
                return pullAppSearchStorageInfo(data);
            default:
                Log.e(TAG, "unexpected atom ID " + atomTag);
                return StatsManager.PULL_SKIP;
        }
    }

    private static int pullAppSearchStorageInfo(@NonNull List<StatsEvent> data) {
        AppSearchUserInstanceManager userInstanceManager =
                AppSearchUserInstanceManager.getInstance();
        List<UserHandle> userHandles = userInstanceManager.getAllUserHandles();
        for (int i = 0; i < userHandles.size(); i++) {
            UserHandle userHandle = userHandles.get(i);
            try {
                AppSearchUserInstance userInstance = userInstanceManager.getUserInstance(
                        userHandle);
                StorageInfoProto storageInfoProto =
                        userInstance.getAppSearchImpl().getRawStorageInfoProto();
                data.add(buildStatsEvent(userHandle.getIdentifier(), storageInfoProto));
            } catch (Throwable t) {
                Log.e(TAG,
                        "Failed to pull the storage info for user " + userHandle.toString(),
                        t);
            }
        }

        // Skip the report if there is no data.
        if (data.isEmpty()) {
            return StatsManager.PULL_SKIP;
        }

        return StatsManager.PULL_SUCCESS;
    }

    /**
     * Registers and configures the callback for the pulled atom.
     *
     * @param atomId   The id of the atom
     * @param policy   Optional metadata specifying the timeout, cool down time etc. statsD would
     *                 use default values if it is null
     * @param executor The executor in which to run the callback
     */
    private void registerAtom(int atomId, @Nullable StatsManager.PullAtomMetadata policy,
            @NonNull Executor executor) {
        mStatsManager.setPullAtomCallback(atomId, policy, executor, /*callback=*/this);
    }

    private static StatsEvent buildStatsEvent(@UserIdInt int userId,
            @NonNull StorageInfoProto storageInfoProto) {
        return AppSearchStatsLog.buildStatsEvent(
                AppSearchStatsLog.APP_SEARCH_STORAGE_INFO,
                userId,
                storageInfoProto.getTotalStorageSize(),
                getDocumentStorageInfoBytes(storageInfoProto.getDocumentStorageInfo()),
                getSchemaStoreStorageInfoBytes(storageInfoProto.getSchemaStoreStorageInfo()),
                getIndexStorageInfoBytes(storageInfoProto.getIndexStorageInfo()));
    }

    private static byte[] getDocumentStorageInfoBytes(
            @NonNull DocumentStorageInfoProto proto) {
        // Make sure we only log the fields defined in the atom in case new fields are added in
        // IcingLib
        DocumentStorageInfoProto.Builder builder = DocumentStorageInfoProto.newBuilder();
        builder.setNumAliveDocuments(proto.getNumAliveDocuments())
                .setNumDeletedDocuments(proto.getNumDeletedDocuments())
                .setNumExpiredDocuments(proto.getNumExpiredDocuments())
                .setDocumentStoreSize(proto.getDocumentStoreSize())
                .setDocumentLogSize(proto.getDocumentLogSize())
                .setKeyMapperSize(proto.getKeyMapperSize())
                .setDocumentIdMapperSize(proto.getDocumentIdMapperSize())
                .setScoreCacheSize(proto.getScoreCacheSize())
                .setFilterCacheSize(proto.getFilterCacheSize())
                .setCorpusMapperSize(proto.getCorpusMapperSize())
                .setCorpusScoreCacheSize(proto.getCorpusScoreCacheSize())
                .setNamespaceIdMapperSize(proto.getNamespaceIdMapperSize())
                .setNumNamespaces(proto.getNumNamespaces());
        return builder.build().toByteArray();
    }

    private static byte[] getSchemaStoreStorageInfoBytes(
            @NonNull SchemaStoreStorageInfoProto proto) {
        // Make sure we only log the fields defined in the atom in case new fields are added in
        // IcingLib
        SchemaStoreStorageInfoProto.Builder builder = SchemaStoreStorageInfoProto.newBuilder();
        builder.setSchemaStoreSize(proto.getSchemaStoreSize())
                .setNumSchemaTypes(proto.getNumSchemaTypes())
                .setNumTotalSections(proto.getNumTotalSections())
                .setNumSchemaTypesSectionsExhausted(proto.getNumSchemaTypesSectionsExhausted());
        return builder.build().toByteArray();
    }

    private static byte[] getIndexStorageInfoBytes(
            @NonNull IndexStorageInfoProto proto) {
        // Make sure we only log the fields defined in the atom in case new fields are added in
        // IcingLib
        IndexStorageInfoProto.Builder builder = IndexStorageInfoProto.newBuilder();
        builder.setIndexSize(proto.getIndexSize())
                .setLiteIndexLexiconSize(proto.getLiteIndexLexiconSize())
                .setLiteIndexHitBufferSize(proto.getLiteIndexHitBufferSize())
                .setMainIndexLexiconSize(proto.getMainIndexLexiconSize())
                .setMainIndexStorageSize(proto.getMainIndexStorageSize())
                .setMainIndexBlockSize(proto.getMainIndexBlockSize())
                .setNumBlocks(proto.getNumBlocks())
                .setMinFreeFraction(proto.getMinFreeFraction());
        return builder.build().toByteArray();
    }
}
