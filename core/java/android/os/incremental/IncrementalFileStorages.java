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

import static dalvik.system.VMRuntime.getInstructionSet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.InstallationFile;
import android.os.IVold;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
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
    private @Nullable IncrementalStorage mApkStorage;
    private @Nullable IncrementalStorage mObbStorage;
    private @Nullable String mDefaultDir;
    private @Nullable String mObbDir;
    private @NonNull IncrementalManager mIncrementalManager;
    private @Nullable ArraySet<String> mLibDirs;
    private @NonNull String mPackageName;
    private @NonNull File mStageDir;

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
    public IncrementalFileStorages(@NonNull String packageName,
            @NonNull File stageDir,
            @NonNull IncrementalManager incrementalManager,
            @NonNull IncrementalDataLoaderParams incrementalDataLoaderParams) {
        mPackageName = packageName;
        mStageDir = stageDir;
        mIncrementalManager = incrementalManager;
        if (incrementalDataLoaderParams.getPackageName().equals("local")) {
            final String incrementalPath = incrementalDataLoaderParams.getStaticArgs();
            mDefaultStorage = mIncrementalManager.openStorage(incrementalPath);
            mDefaultDir = incrementalPath;
            return;
        }
        mDefaultDir = getTempDir();
        if (mDefaultDir == null) {
            return;
        }
        mDefaultStorage = mIncrementalManager.createStorage(mDefaultDir,
                incrementalDataLoaderParams,
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
        } else if (file.getFileType() == InstallationFile.FILE_TYPE_OBB) {
            addObbFile(file);
        } else if (file.getFileType() == InstallationFile.FILE_TYPE_LIB) {
            addLibFile(file);
        } else {
            throw new IOException("Unknown file type: " + file.getFileType());
        }
    }

    private void addApkFile(@NonNull InstallationFile apk) throws IOException {
        // Create a storage for APK files and lib files
        final String stageDirPath = mStageDir.getAbsolutePath();
        if (mApkStorage == null) {
            mApkStorage = mIncrementalManager.createStorage(stageDirPath, mDefaultStorage,
                    IncrementalManager.CREATE_MODE_CREATE
                            | IncrementalManager.CREATE_MODE_TEMPORARY_BIND);
            mApkStorage.bind(stageDirPath);
        }

        if (!new File(mDefaultDir, apk.getName()).exists()) {
            mDefaultStorage.makeFile(apk.getName(), apk.getSize(),
                    apk.getMetadata());
        }
        // Assuming APK files are already named properly, e.g., "base.apk"
        mDefaultStorage.makeLink(apk.getName(), mApkStorage, apk.getName());
    }

    private void addLibFile(@NonNull InstallationFile lib) throws IOException {
        // TODO(b/136132412): remove this after we have incfs support for lib file mapping
        if (mApkStorage == null) {
            throw new IOException("Cannot add lib file without adding an apk file first");
        }
        if (mLibDirs == null) {
            mLibDirs = new ArraySet<>();
        }
        String current = "";
        final Path libDirPath = Paths.get(lib.getName()).getParent();
        final int numDirComponents = libDirPath.getNameCount();
        for (int i = 0; i < numDirComponents; i++) {
            String dirName = libDirPath.getName(i).toString();
            try {
                dirName = getInstructionSet(dirName);
            } catch (IllegalArgumentException ignored) {
            }
            current += dirName;
            if (!mLibDirs.contains(current)) {
                mDefaultStorage.makeDirectory(current);
                mApkStorage.makeDirectory(current);
                mLibDirs.add(current);
            }
            current += '/';
        }
        String libFilePath = current + Paths.get(lib.getName()).getFileName();
        mDefaultStorage.makeFile(libFilePath, lib.getSize(), lib.getMetadata());
        mDefaultStorage.makeLink(libFilePath, mApkStorage, libFilePath);
    }

    private void addObbFile(@NonNull InstallationFile obb) throws IOException {
        if (mObbStorage == null) {
            // Create a storage for OBB files
            mObbDir = getTempDir();
            if (mObbDir == null) {
                throw new IOException("Failed to create obb storage directory.");
            }
            mObbStorage = mIncrementalManager.createStorage(
                    mObbDir, mDefaultStorage,
                    IncrementalManager.CREATE_MODE_CREATE
                            | IncrementalManager.CREATE_MODE_TEMPORARY_BIND);
        }
        mDefaultStorage.makeFile(obb.getName(), obb.getSize(), obb.getMetadata());
        mDefaultStorage.makeLink(obb.getName(), mObbStorage, obb.getName());
    }

    private boolean hasObb() {
        return (mObbStorage != null && mObbDir != null);
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
        if (!hasObb()) {
            return;
        }
        final String mainObbDir = String.format("/storage/emulated/0/Android/obb/%s", mPackageName);
        final String packageObbDirRoot =
                String.format("/mnt/runtime/%s/emulated/0/Android/obb/", mPackageName);
        final String[] obbDirs = {
                packageObbDirRoot + "read",
                packageObbDirRoot + "write",
                packageObbDirRoot + "full",
                packageObbDirRoot + "default",
                String.format("/data/media/0/Android/obb/%s", mPackageName),
                mainObbDir,
        };
        try {
            Slog.i(TAG, "Creating obb directory '" + mainObbDir + "'");
            final IVold vold = IVold.Stub.asInterface(ServiceManager.getServiceOrThrow("vold"));
            vold.mkdirs(mainObbDir);
            for (String d : obbDirs) {
                mObbStorage.bindPermanent(d);
            }
        } catch (ServiceManager.ServiceNotFoundException ex) {
            Slog.e(TAG, "vold service is not found.");
            cleanUp();
        } catch (IOException | RemoteException ex) {
            Slog.e(TAG, "Failed to create obb dir at: " + mainObbDir, ex);
            cleanUp();
        }
    }

    /**
     * Resets the states and unbinds storage instances for an installation session.
     * TODO(b/136132412): make sure unnecessary binds are removed but useful storages are kept
     */
    public void cleanUp() {
        if (mDefaultStorage != null && mDefaultDir != null) {
            try {
                mDefaultStorage.unBind(mDefaultDir);
            } catch (IOException ignored) {
            }
            mDefaultDir = null;
            mDefaultStorage = null;
        }
        if (mApkStorage != null && mStageDir != null) {
            try {
                mApkStorage.unBind(mStageDir.getAbsolutePath());
            } catch (IOException ignored) {
            }
            mApkStorage = null;
        }
        if (mObbStorage != null && mObbDir != null) {
            try {
                mObbStorage.unBind(mObbDir);
            } catch (IOException ignored) {
            }
            mObbDir = null;
            mObbStorage = null;
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
