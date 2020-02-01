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
 * Set up files and directories used in an installation session.
 * Currently only used by Incremental Installation.
 * For Incremental installation, the expected outcome of this function is:
 * 0) All the files are in defaultStorage
 * 1) All APK files are in the same directory, bound to mApkStorage, and bound to the
 * InstallerSession's stage dir. The files are linked from mApkStorage to defaultStorage.
 * 2) All lib files are in the sub directories as their names suggest, and in the same parent
 * directory as the APK files. The files are linked from mApkStorage to defaultStorage.
 * 3) OBB files are in another directory that is different from APK files and lib files, bound
 * to mObbStorage. The files are linked from mObbStorage to defaultStorage.
 *
 * @throws IllegalStateException the session is not an Incremental installation session.
 */

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.DataLoaderParams;
import android.content.pm.InstallationFile;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * This class manages storage instances used during a package installation session.
 * @hide
 */
public final class IncrementalFileStorages {
    private static final String TAG = "IncrementalFileStorages";
    private @Nullable IncrementalStorage mDefaultStorage;
    private @Nullable String mDefaultDir;
    private @NonNull IncrementalManager mIncrementalManager;
    private @NonNull File mStageDir;

    /**
     * Set up files and directories used in an installation session. Only used by Incremental.
     * All the files will be created in defaultStorage.
     * TODO(b/133435829): code clean up
     *
     * @throws IllegalStateException the session is not an Incremental installation session.
     */
    public IncrementalFileStorages(@NonNull String packageName,
            @NonNull File stageDir,
            @NonNull IncrementalManager incrementalManager,
            @NonNull DataLoaderParams dataLoaderParams) {
        mStageDir = stageDir;
        mIncrementalManager = incrementalManager;
        if (dataLoaderParams.getComponentName().getPackageName().equals("local")) {
            final String incrementalPath = dataLoaderParams.getArguments();
            mDefaultStorage = mIncrementalManager.openStorage(incrementalPath);
            mDefaultDir = incrementalPath;
            return;
        }
        mDefaultDir = getTempDir();
        if (mDefaultDir == null) {
            return;
        }
        mDefaultStorage = mIncrementalManager.createStorage(mDefaultDir,
                dataLoaderParams,
                IncrementalManager.CREATE_MODE_CREATE
                        | IncrementalManager.CREATE_MODE_TEMPORARY_BIND, false);
    }

    /**
     * Adds a file into the installation session. Makes sure it will be placed inside
     * a proper storage instance, based on its file type.
     */
    public void addFile(@NonNull InstallationFile file) throws IOException {
        if (mDefaultStorage == null) {
            throw new IOException("Cannot add file because default storage does not exist");
        }
        if (file.getFileType() == InstallationFile.FILE_TYPE_APK) {
            addApkFile(file);
        } else {
            throw new IOException("Unknown file type: " + file.getFileType());
        }
    }

    private void addApkFile(@NonNull InstallationFile apk) throws IOException {
        final String stageDirPath = mStageDir.getAbsolutePath();
        mDefaultStorage.bind(stageDirPath);
        String apkName = apk.getName();
        File targetFile = Paths.get(stageDirPath, apkName).toFile();
        if (!targetFile.exists()) {
            mDefaultStorage.makeFile(apkName, apk.getSize(), null,
                    apk.getMetadata(), 0, null, null, null);
        }
        if (targetFile.exists()) {
            Slog.i(TAG, "!!! created: " + targetFile.getAbsolutePath());
        }
    }

    /**
     * Starts loading data for default storage.
     * TODO(b/136132412): update the implementation with latest API design.
     */
    public boolean startLoading() {
        if (mDefaultStorage == null) {
            return false;
        }
        return mDefaultStorage.startLoading();
    }

    /**
     * Sets up obb storage directory and create bindings.
     */
    public void finishSetUp() {
    }

    /**
     * Resets the states and unbinds storage instances for an installation session.
     * TODO(b/136132412): make sure unnecessary binds are removed but useful storages are kept
     */
    public void cleanUp() {
        if (mDefaultStorage != null && mDefaultDir != null) {
            try {
                mDefaultStorage.unBind(mDefaultDir);
                mDefaultStorage.unBind(mStageDir.getAbsolutePath());
            } catch (IOException ignored) {
            }
            mDefaultDir = null;
            mDefaultStorage = null;
        }
    }

    private String getTempDir() {
        final String tmpDirRoot = "/data/incremental/tmp";
        final Random random = new Random();
        final Path tmpDir =
                Paths.get(tmpDirRoot, String.valueOf(random.nextInt(Integer.MAX_VALUE - 1)));
        try {
            Files.createDirectories(tmpDir);
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to create dir", ex);
            return null;
        }
        return tmpDir.toAbsolutePath().toString();
    }
}
