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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Provides operations to open or create an IncrementalStorage, using IIncrementalManagerNative
 * service. Example Usage:
 *
 * <blockquote><pre>
 * IncrementalManager manager = (IncrementalManager) getSystemService(Context.INCREMENTAL_SERVICE);
 * IncrementalStorage storage = manager.openStorage("/path/to/incremental/dir");
 * </pre></blockquote>
 *
 * @hide
 */
@SystemService(Context.INCREMENTAL_SERVICE)
public final class IncrementalManager {
    private static final String TAG = "IncrementalManager";

    public static final int CREATE_MODE_TEMPORARY_BIND =
            IIncrementalManagerNative.CREATE_MODE_TEMPORARY_BIND;
    public static final int CREATE_MODE_PERMANENT_BIND =
            IIncrementalManagerNative.CREATE_MODE_PERMANENT_BIND;
    public static final int CREATE_MODE_CREATE =
            IIncrementalManagerNative.CREATE_MODE_CREATE;
    public static final int CREATE_MODE_OPEN_EXISTING =
            IIncrementalManagerNative.CREATE_MODE_OPEN_EXISTING;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CREATE_MODE_"}, value = {
            CREATE_MODE_TEMPORARY_BIND,
            CREATE_MODE_PERMANENT_BIND,
            CREATE_MODE_CREATE,
            CREATE_MODE_OPEN_EXISTING,
    })
    public @interface CreateMode {
    }

    private final @Nullable IIncrementalManagerNative mNativeService;
    @GuardedBy("mStorages")
    private final SparseArray<IncrementalStorage> mStorages = new SparseArray<>();

    public IncrementalManager(IIncrementalManagerNative nativeService) {
        mNativeService = nativeService;
    }

    /**
     * Returns a storage object given a storage ID.
     *
     * @param storageId The storage ID to identify the storage object.
     * @return IncrementalStorage object corresponding to storage ID.
     */
    // TODO(b/136132412): remove this
    @Nullable
    public IncrementalStorage getStorage(int storageId) {
        synchronized (mStorages) {
            return mStorages.get(storageId);
        }
    }

    /**
     * Opens or create an Incremental File System mounted directory and returns an
     * IncrementalStorage object.
     *
     * @param path                Absolute path to mount Incremental File System on.
     * @param params              IncrementalDataLoaderParams object to configure data loading.
     * @param createMode          Mode for opening an old Incremental File System mount or creating
     *                            a new mount.
     * @param autoStartDataLoader Set true to immediately start data loader after creating storage.
     * @return IncrementalStorage object corresponding to the mounted directory.
     */
    @Nullable
    public IncrementalStorage createStorage(@NonNull String path,
            @NonNull IncrementalDataLoaderParams params, @CreateMode int createMode,
            boolean autoStartDataLoader) {
        try {
            final int id = mNativeService.createStorage(path, params.getData(), createMode);
            if (id < 0) {
                return null;
            }
            final IncrementalStorage storage = new IncrementalStorage(mNativeService, id);
            synchronized (mStorages) {
                mStorages.put(id, storage);
            }
            if (autoStartDataLoader) {
                storage.startLoading();
            }
            return storage;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens an existing Incremental File System mounted directory and returns an IncrementalStorage
     * object.
     *
     * @param path Absolute target path that Incremental File System has been mounted on.
     * @return IncrementalStorage object corresponding to the mounted directory.
     */
    @Nullable
    public IncrementalStorage openStorage(@NonNull String path) {
        try {
            final int id = mNativeService.openStorage(path);
            if (id < 0) {
                return null;
            }
            final IncrementalStorage storage = new IncrementalStorage(mNativeService, id);
            synchronized (mStorages) {
                mStorages.put(id, storage);
            }
            return storage;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens or creates an IncrementalStorage that is linked to another IncrementalStorage.
     *
     * @return IncrementalStorage object corresponding to the linked storage.
     */
    @Nullable
    public IncrementalStorage createStorage(@NonNull String path,
            @NonNull IncrementalStorage linkedStorage, @CreateMode int createMode) {
        try {
            final int id = mNativeService.createLinkedStorage(
                    path, linkedStorage.getId(), createMode);
            if (id < 0) {
                return null;
            }
            final IncrementalStorage storage = new IncrementalStorage(mNativeService, id);
            synchronized (mStorages) {
                mStorages.put(id, storage);
            }
            return storage;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Iterates through path parents to find the base dir of an Incremental Storage.
     *
     * @param file Target file to search storage for.
     * @return Absolute path which is a bind-mount point of Incremental File System.
     */
    @Nullable
    private Path getStoragePathForFile(File file) {
        File currentPath = new File(file.getParent());
        while (currentPath.getParent() != null) {
            IncrementalStorage storage = openStorage(currentPath.getAbsolutePath());
            if (storage != null) {
                return currentPath.toPath();
            }
            currentPath = new File(currentPath.getParent());
        }
        return null;
    }

    /**
     * Renames an Incremental path to a new path. If source path is a file, make a link from the old
     * Incremental file to the new one. If source path is a dir, unbind old dir from Incremental
     * Storage and bind the new one.
     * <ol>
     *     <li> For renaming a dir, dest dir will be created if not exists, and does not need to
     *          be on the same Incremental storage as the source. </li>
     *     <li> For renaming a file, dest file must be on the same Incremental storage as source.
     *     </li>
     * </ol>
     *
     * @param sourcePath Absolute path to the source. Should be the same type as the destPath (file
     *                   or dir). Expected to already exist and is an Incremental path.
     * @param destPath   Absolute path to the destination.
     * @throws IllegalArgumentException when 1) source does not exist, or 2) source and dest type
     *                                  mismatch (one is file and the other is dir), or 3) source
     *                                  path is not on Incremental File System,
     * @throws IOException              when 1) cannot find the root path of the Incremental storage
     *                                  of source, or 2) cannot retrieve the Incremental storage
     *                                  instance of the source, or 3) renaming a file, but dest is
     *                                  not on the same Incremental Storage, or 4) renaming a dir,
     *                                  dest dir does not exist but fails to be created.
     *                                  <p>
     *                                  TODO(b/136132412): add unit tests
     */
    public void rename(@NonNull String sourcePath, @NonNull String destPath) throws IOException {
        final File source = new File(sourcePath);
        final File dest = new File(destPath);
        if (!source.exists()) {
            throw new IllegalArgumentException("Path not exist: " + sourcePath);
        }
        if (dest.exists()) {
            throw new IllegalArgumentException("Target path already exists: " + destPath);
        }
        if (source.isDirectory() && dest.exists() && dest.isFile()) {
            throw new IllegalArgumentException(
                    "Trying to rename a dir but destination is a file: " + destPath);
        }
        if (source.isFile() && dest.exists() && dest.isDirectory()) {
            throw new IllegalArgumentException(
                    "Trying to rename a file but destination is a dir: " + destPath);
        }
        if (!isIncrementalPath(sourcePath)) {
            throw new IllegalArgumentException("Not an Incremental path: " + sourcePath);
        }

        Path storagePath = Paths.get(sourcePath);
        if (source.isFile()) {
            storagePath = getStoragePathForFile(source);
        }
        if (storagePath == null || storagePath.toAbsolutePath() == null) {
            throw new IOException("Invalid source storage path for: " + sourcePath);
        }
        final IncrementalStorage storage = openStorage(storagePath.toAbsolutePath().toString());
        if (storage == null) {
            throw new IOException("Failed to retrieve storage from Incremental Service.");
        }

        if (source.isFile()) {
            renameFile(storage, storagePath, source, dest);
        } else {
            renameDir(storage, storagePath, source, dest);
        }
    }

    private void renameFile(IncrementalStorage storage, Path storagePath,
            File source, File dest) throws IOException {
        Path sourcePath = source.toPath();
        Path destPath = dest.toPath();
        if (!sourcePath.startsWith(storagePath)) {
            throw new IOException("Path: " + source.getAbsolutePath() + " is not on storage at: "
                    + storagePath.toString());
        }
        if (!destPath.startsWith(storagePath)) {
            throw new IOException("Path: " + dest.getAbsolutePath() + " is not on storage at: "
                    + storagePath.toString());
        }
        final Path sourceRelativePath = storagePath.relativize(sourcePath);
        final Path destRelativePath = storagePath.relativize(destPath);
        storage.moveFile(sourceRelativePath.toString(), destRelativePath.toString());

    }

    private void renameDir(IncrementalStorage storage, Path storagePath,
            File source, File dest) throws IOException {
        Path destPath = dest.toPath();
        boolean usedMkdir = false;
        try {
            Os.mkdir(dest.getAbsolutePath(), 0755);
            usedMkdir = true;
        } catch (ErrnoException e) {
            // Traditional mkdir fails but maybe we can create it on Incremental File System if
            // the dest path is on the same Incremental storage as the source.
            if (destPath.startsWith(storagePath)) {
                storage.makeDirectories(storagePath.relativize(destPath).toString());
            } else {
                throw new IOException("Failed to create directory: " + dest.getAbsolutePath(), e);
            }
        }
        try {
            storage.moveDir(source.getAbsolutePath(), dest.getAbsolutePath());
        } catch (Exception ex) {
            if (usedMkdir) {
                try {
                    Os.remove(dest.getAbsolutePath());
                } catch (ErrnoException ignored) {
                }
            }
            throw new IOException(
                    "Failed to move " + source.getAbsolutePath() + " to " + dest.getAbsolutePath());
        }
    }

    /**
     * Closes a storage specified by the absolute path. If the path is not Incremental, do nothing.
     * Unbinds the target dir and deletes the corresponding storage instance.
     */
    public void closeStorage(@NonNull String path) {
        try {
            final int id = mNativeService.openStorage(path);
            if (id < 0) {
                return;
            }
            mNativeService.deleteStorage(id);
            synchronized (mStorages) {
                mStorages.remove(id);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if path is mounted on Incremental File System.
     */
    public static boolean isIncrementalPath(@NonNull String path) {
        // TODO(b/136132412): add jni implementation
        return false;
    }
}
