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

package com.android.server.appsearch;

import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPackageName;

import android.annotation.NonNull;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import com.google.android.icing.proto.DocumentStorageInfoProto;
import com.google.android.icing.proto.NamespaceStorageInfoProto;
import com.google.android.icing.proto.StorageInfoProto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Saves the storage info read from file for a user. */
public final class UserStorageInfo {
    public static final String STORAGE_INFO_FILE = "appsearch_storage";
    private static final String TAG = "AppSearchUserStorage";
    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final File mStorageInfoFile;

    // Saves storage usage byte size for each package under the user, keyed by package name.
    private Map<String, Long> mPackageStorageSizeMap;
    // Saves storage usage byte size for all packages under the user.
    private long mTotalStorageSizeBytes;

    public UserStorageInfo(@NonNull File fileParentPath) {
        Objects.requireNonNull(fileParentPath);
        mStorageInfoFile = new File(fileParentPath, STORAGE_INFO_FILE);
        readStorageInfoFromFile();
    }

    /**
     * Updates storage info file with the latest storage info queried through
     * {@link AppSearchImpl}.
     */
    public void updateStorageInfoFile(@NonNull AppSearchImpl appSearchImpl) {
        Objects.requireNonNull(appSearchImpl);
        mReadWriteLock.writeLock().lock();
        try (FileOutputStream out = new FileOutputStream(mStorageInfoFile)) {
            appSearchImpl.getRawStorageInfoProto().writeTo(out);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to dump storage info into file", e);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Gets storage usage byte size for a package with a given package name.
     *
     * <p> Please note the storage info cached in file may be stale.
     */
    public long getSizeBytesForPackage(@NonNull String packageName) {
        Objects.requireNonNull(packageName);
        return mPackageStorageSizeMap.getOrDefault(packageName, 0L);
    }

    /**
     * Gets total storage usage byte size for all packages under the user.
     *
     * <p> Please note the storage info cached in file may be stale.
     */
    public long getTotalSizeBytes() {
        return mTotalStorageSizeBytes;
    }

    @VisibleForTesting
    void readStorageInfoFromFile() {
        if (mStorageInfoFile.exists()) {
            mReadWriteLock.readLock().lock();
            try (InputStream in = new FileInputStream(mStorageInfoFile)) {
                StorageInfoProto storageInfo = StorageInfoProto.parseFrom(in);
                mTotalStorageSizeBytes = storageInfo.getTotalStorageSize();
                mPackageStorageSizeMap = calculatePackageStorageInfoMap(storageInfo);
                return;
            } catch (Throwable e) {
                Log.w(TAG, "Failed to read storage info from file", e);
            } finally {
                mReadWriteLock.readLock().unlock();
            }
        }
        mTotalStorageSizeBytes = 0;
        mPackageStorageSizeMap = Collections.emptyMap();
    }

    /** Calculates storage usage byte size for packages from a {@link StorageInfoProto}. */
    // TODO(b/198553756): Storage cache effort has created two copies of the storage
    // calculation/interpolation logic.
    @NonNull
    @VisibleForTesting
    Map<String, Long> calculatePackageStorageInfoMap(@NonNull StorageInfoProto storageInfo) {
        Map<String, Long> packageStorageInfoMap = new ArrayMap<>();
        if (storageInfo.hasDocumentStorageInfo()) {
            DocumentStorageInfoProto documentStorageInfo = storageInfo.getDocumentStorageInfo();
            List<NamespaceStorageInfoProto> namespaceStorageInfoList =
                    documentStorageInfo.getNamespaceStorageInfoList();

            Map<String, Integer> packageDocumentCountMap = new ArrayMap<>();
            long totalDocuments = 0;
            for (int i = 0; i < namespaceStorageInfoList.size(); i++) {
                NamespaceStorageInfoProto namespaceStorageInfo = namespaceStorageInfoList.get(i);
                String packageName = getPackageName(namespaceStorageInfo.getNamespace());
                int namespaceDocuments = namespaceStorageInfo.getNumAliveDocuments()
                        + namespaceStorageInfo.getNumExpiredDocuments();
                totalDocuments += namespaceDocuments;
                packageDocumentCountMap.put(packageName,
                        packageDocumentCountMap.getOrDefault(packageName, 0)
                                + namespaceDocuments);
            }

            long totalStorageSize = storageInfo.getTotalStorageSize();
            for (Map.Entry<String, Integer> entry : packageDocumentCountMap.entrySet()) {
                // Since we don't have the exact size of all the documents, we do an estimation.
                // Note that while the total storage takes into account schema, index, etc. in
                // addition to documents, we'll only calculate the percentage based on number of
                // documents under packages.
                packageStorageInfoMap.put(entry.getKey(),
                        (long) (entry.getValue() * 1.0 / totalDocuments * totalStorageSize));
            }
        }
        return Collections.unmodifiableMap(packageStorageInfoMap);
    }
}
