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
import android.content.pm.InstallationFileParcel;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * This class manages storage instances used during a package installation session.
 * @hide
 */
public final class IncrementalFileStorages {
    private static final String TAG = "IncrementalFileStorages";

    private @NonNull final IncrementalManager mIncrementalManager;
    private @NonNull final File mStageDir;
    private @Nullable IncrementalStorage mDefaultStorage;

    /**
     * Set up files and directories used in an installation session. Only used by Incremental.
     * All the files will be created in defaultStorage.
     * TODO(b/133435829): code clean up
     *
     * @throws IllegalStateException the session is not an Incremental installation session.
     * @throws IOException if fails to setup files or directories.
     */
    public static IncrementalFileStorages initialize(Context context,
            @NonNull File stageDir,
            @NonNull DataLoaderParams dataLoaderParams,
            @Nullable IDataLoaderStatusListener statusListener,
            @Nullable StorageHealthCheckParams healthCheckParams,
            @Nullable IStorageHealthListener healthListener,
            List<InstallationFileParcel> addedFiles) throws IOException {
        // TODO(b/136132412): sanity check if session should not be incremental
        IncrementalManager incrementalManager = (IncrementalManager) context.getSystemService(
                Context.INCREMENTAL_SERVICE);
        if (incrementalManager == null) {
            // TODO(b/146080380): add incremental-specific error code
            throw new IOException("Failed to obtain incrementalManager.");
        }

        final IncrementalFileStorages result = new IncrementalFileStorages(stageDir,
                incrementalManager, dataLoaderParams, statusListener, healthCheckParams,
                healthListener);
        for (InstallationFileParcel file : addedFiles) {
            if (file.location == LOCATION_DATA_APP) {
                try {
                    result.addApkFile(file);
                } catch (IOException e) {
                    // TODO(b/146080380): add incremental-specific error code
                    throw new IOException(
                            "Failed to add file to IncFS: " + file.name + ", reason: ", e);
                }
            } else {
                throw new IOException("Unknown file location: " + file.location);
            }
        }

        result.startLoading();

        return result;
    }

    private IncrementalFileStorages(@NonNull File stageDir,
            @NonNull IncrementalManager incrementalManager,
            @NonNull DataLoaderParams dataLoaderParams,
            @Nullable IDataLoaderStatusListener statusListener,
            @Nullable StorageHealthCheckParams healthCheckParams,
            @Nullable IStorageHealthListener healthListener) throws IOException {
        try {
            mStageDir = stageDir;
            mIncrementalManager = incrementalManager;
            if (dataLoaderParams.getComponentName().getPackageName().equals("local")) {
                final String incrementalPath = dataLoaderParams.getArguments();
                if (TextUtils.isEmpty(incrementalPath)) {
                    throw new IOException("Failed to create storage: incrementalPath is empty");
                }
                mDefaultStorage = mIncrementalManager.openStorage(incrementalPath);
                if (mDefaultStorage == null) {
                    throw new IOException(
                            "Couldn't open incremental storage at " + incrementalPath);
                }
                mDefaultStorage.bind(stageDir.getAbsolutePath());
            } else {
                mDefaultStorage = mIncrementalManager.createStorage(stageDir.getAbsolutePath(),
                        dataLoaderParams, IncrementalManager.CREATE_MODE_CREATE
                                | IncrementalManager.CREATE_MODE_TEMPORARY_BIND, false,
                        statusListener, healthCheckParams, healthListener);
                if (mDefaultStorage == null) {
                    throw new IOException(
                            "Couldn't create incremental storage at " + stageDir);
                }
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
            mDefaultStorage.makeFile(apkName, apk.size, null, apk.metadata, apk.signature);
        }
    }

    /**
     * Starts or re-starts loading of data.
     */
    public void startLoading() throws IOException {
        if (!mDefaultStorage.startLoading()) {
            throw new IOException("Failed to start loading data for Incremental installation.");
        }
    }

    /**
     * Resets the states and unbinds storage instances for an installation session.
     * TODO(b/136132412): make sure unnecessary binds are removed but useful storages are kept
     */
    public void cleanUp() {
        if (mDefaultStorage == null) {
            return;
        }

        try {
            mDefaultStorage.unBind(mStageDir.getAbsolutePath());
        } catch (IOException ignored) {
        }
        mDefaultStorage = null;
    }
}
