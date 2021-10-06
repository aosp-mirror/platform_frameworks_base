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

package android.os.incremental;

/**
 * Set up files and directories used in an installation session. Currently only used by Incremental
 * Installation. For Incremental installation, the expected outcome of this function is: 0) All the
 * files are in defaultStorage 1) All APK files are in the same directory, bound to mApkStorage, and
 * bound to the InstallerSession's stage dir. The files are linked from mApkStorage to
 * defaultStorage. 2) All lib files are in the sub directories as their names suggest, and in the
 * same parent directory as the APK files. The files are linked from mApkStorage to defaultStorage.
 * 3) OBB files are in another directory that is different from APK files and lib files, bound to
 * mObbStorage. The files are linked from mObbStorage to defaultStorage.
 *
 * @throws IllegalStateException the session is not an Incremental installation session.
 */

import static android.content.pm.PackageInstaller.LOCATION_DATA_APP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.DataLoaderParams;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.IPackageLoadingProgressCallback;
import android.content.pm.InstallationFileParcel;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * This class manages storage instances used during a package installation session.
 * @hide
 */
public final class IncrementalFileStorages {
    private static final String TAG = "IncrementalFileStorages";

    private static final String SYSTEM_DATA_LOADER_PACKAGE = "android";

    private @NonNull final IncrementalManager mIncrementalManager;
    private @NonNull final File mStageDir;
    private @Nullable IncrementalStorage mInheritedStorage;
    private @Nullable IncrementalStorage mDefaultStorage;

    /**
     * Set up files and directories used in an installation session. Only used by Incremental.
     * All the files will be created in defaultStorage.
     *
     * @throws IllegalStateException the session is not an Incremental installation session.
     * @throws IOException if fails to setup files or directories.
     */
    public static IncrementalFileStorages initialize(Context context,
            @NonNull File stageDir,
            @Nullable File inheritedDir,
            @NonNull DataLoaderParams dataLoaderParams,
            @Nullable IDataLoaderStatusListener statusListener,
            @Nullable StorageHealthCheckParams healthCheckParams,
            @Nullable IStorageHealthListener healthListener,
            @NonNull List<InstallationFileParcel> addedFiles,
            @NonNull PerUidReadTimeouts[] perUidReadTimeouts,
            @Nullable IPackageLoadingProgressCallback progressCallback) throws IOException {
        IncrementalManager incrementalManager = (IncrementalManager) context.getSystemService(
                Context.INCREMENTAL_SERVICE);
        if (incrementalManager == null) {
            throw new IOException("Failed to obtain incrementalManager.");
        }

        final IncrementalFileStorages result = new IncrementalFileStorages(stageDir, inheritedDir,
                incrementalManager, dataLoaderParams);
        for (InstallationFileParcel file : addedFiles) {
            if (file.location == LOCATION_DATA_APP) {
                try {
                    result.addApkFile(file);
                } catch (IOException e) {
                    throw new IOException(
                            "Failed to add file to IncFS: " + file.name + ", reason: ", e);
                }
            } else {
                throw new IOException("Unknown file location: " + file.location);
            }
        }
        // Register progress loading callback after files have been added
        if (progressCallback != null) {
            incrementalManager.registerLoadingProgressCallback(stageDir.getAbsolutePath(),
                    progressCallback);
        }
        result.startLoading(dataLoaderParams, statusListener, healthCheckParams, healthListener,
                perUidReadTimeouts);

        return result;
    }

    private IncrementalFileStorages(@NonNull File stageDir,
            @Nullable File inheritedDir,
            @NonNull IncrementalManager incrementalManager,
            @NonNull DataLoaderParams dataLoaderParams) throws IOException {
        try {
            mStageDir = stageDir;
            mIncrementalManager = incrementalManager;
            if (inheritedDir != null && IncrementalManager.isIncrementalPath(
                    inheritedDir.getAbsolutePath())) {
                mInheritedStorage = mIncrementalManager.openStorage(
                        inheritedDir.getAbsolutePath());
                if (mInheritedStorage != null) {
                    boolean systemDataLoader = SYSTEM_DATA_LOADER_PACKAGE.equals(
                            dataLoaderParams.getComponentName().getPackageName());
                    if (systemDataLoader && !mInheritedStorage.isFullyLoaded()) {
                        // System data loader does not support incomplete storages.
                        throw new IOException("Inherited storage has missing pages.");
                    }

                    mDefaultStorage = mIncrementalManager.createStorage(stageDir.getAbsolutePath(),
                            mInheritedStorage, IncrementalManager.CREATE_MODE_CREATE
                                    | IncrementalManager.CREATE_MODE_TEMPORARY_BIND);
                    if (mDefaultStorage == null) {
                        throw new IOException(
                                "Couldn't create linked incremental storage at " + stageDir);
                    }
                    return;
                }
            }

            mDefaultStorage = mIncrementalManager.createStorage(stageDir.getAbsolutePath(),
                    dataLoaderParams, IncrementalManager.CREATE_MODE_CREATE
                            | IncrementalManager.CREATE_MODE_TEMPORARY_BIND);
            if (mDefaultStorage == null) {
                throw new IOException(
                        "Couldn't create incremental storage at " + stageDir);
            }
        } catch (IOException e) {
            cleanUp();
            throw e;
        }
    }

    private void addApkFile(@NonNull InstallationFileParcel apk) throws IOException {
        final String apkName = apk.name;
        final File targetFile = new File(mStageDir, apkName);
        if (!targetFile.exists()) {
            mDefaultStorage.makeFile(apkName, apk.size, null, apk.metadata, apk.signature, null);
        }
    }

    /**
     * Starts or re-starts loading of data.
     */
    public void startLoading(
            @NonNull DataLoaderParams dataLoaderParams,
            @Nullable IDataLoaderStatusListener statusListener,
            @Nullable StorageHealthCheckParams healthCheckParams,
            @Nullable IStorageHealthListener healthListener,
            @NonNull PerUidReadTimeouts[] perUidReadTimeouts) throws IOException {
        if (!mDefaultStorage.startLoading(dataLoaderParams, statusListener, healthCheckParams,
                healthListener, perUidReadTimeouts)) {
            throw new IOException(
                    "Failed to start or restart loading data for Incremental installation.");
        }
    }

    /**
     * Creates file in default storage and sets its content.
     */
    public void makeFile(@NonNull String name, @NonNull byte[] content) throws IOException {
        mDefaultStorage.makeFile(name, content.length, UUID.randomUUID(), null, null, content);
    }

    /**
     * Creates a hardlink from inherited storage to default.
     */
    public boolean makeLink(@NonNull String relativePath, @NonNull String fromBase,
            @NonNull String toBase) throws IOException {
        if (mInheritedStorage == null) {
            return false;
        }
        final File sourcePath = new File(fromBase, relativePath);
        final File destPath = new File(toBase, relativePath);
        mInheritedStorage.makeLink(sourcePath.getAbsolutePath(), mDefaultStorage,
                destPath.getAbsolutePath());
        return true;
    }

    /**
     * Permanently disables readlogs.
     */
    public void disallowReadLogs() {
        mDefaultStorage.disallowReadLogs();
    }

    /**
     * Resets the states and unbinds storage instances for an installation session.
     */
    public void cleanUpAndMarkComplete() {
        IncrementalStorage defaultStorage = cleanUp();
        if (defaultStorage != null) {
            defaultStorage.onInstallationComplete();
        }
    }

    private IncrementalStorage cleanUp() {
        IncrementalStorage defaultStorage = mDefaultStorage;
        mInheritedStorage = null;
        mDefaultStorage = null;
        if (defaultStorage == null) {
            return null;
        }

        try {
            mIncrementalManager.unregisterLoadingProgressCallbacks(mStageDir.getAbsolutePath());
            defaultStorage.unBind(mStageDir.getAbsolutePath());
        } catch (IOException ignored) {
        }
        return defaultStorage;
    }
}
