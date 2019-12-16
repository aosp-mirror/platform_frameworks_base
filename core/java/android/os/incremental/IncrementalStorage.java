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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;

/**
 * Provides operations on an Incremental File System directory, using IncrementalServiceNative.
 * Example usage:
 *
 * <blockquote><pre>
 * IncrementalManager manager = (IncrementalManager) getSystemService(Context.INCREMENTAL_SERVICE);
 * IncrementalStorage storage = manager.openStorage("/path/to/incremental/dir");
 * storage.makeDirectory("subdir");
 * </pre></blockquote>
 *
 * @hide
 */
public final class IncrementalStorage {
    private static final String TAG = "IncrementalStorage";
    private final int mId;
    private final IIncrementalManagerNative mService;


    public IncrementalStorage(@NonNull IIncrementalManagerNative is, int id) {
        mService = is;
        mId = id;
    }

    public int getId() {
        return mId;
    }

    /**
     * Temporarily bind-mounts the current storage directory to a target directory. The bind-mount
     * will NOT be preserved between device reboots.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void bind(@NonNull String targetPath) throws IOException {
        bind("", targetPath);
    }

    /**
     * Temporarily bind-mounts a subdir under the current storage directory to a target directory.
     * The bind-mount will NOT be preserved between device reboots.
     *
     * @param sourcePathUnderStorage Source path as a relative path under current storage
     *                               directory.
     * @param targetPath             Absolute path to the target directory.
     */
    public void bind(@NonNull String sourcePathUnderStorage, @NonNull String targetPath)
            throws IOException {
        try {
            int res = mService.makeBindMount(mId, sourcePathUnderStorage, targetPath,
                    IIncrementalManagerNative.BIND_TEMPORARY);
            if (res < 0) {
                throw new IOException("bind() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }


    /**
     * Permanently bind-mounts the current storage directory to a target directory. The bind-mount
     * WILL be preserved between device reboots.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void bindPermanent(@NonNull String targetPath) throws IOException {
        bindPermanent("", targetPath);
    }

    /**
     * Permanently bind-mounts a subdir under the current storage directory to a target directory.
     * The bind-mount WILL be preserved between device reboots.
     *
     * @param sourcePathUnderStorage Relative path under the current storage directory.
     * @param targetPath             Absolute path to the target directory.
     */
    public void bindPermanent(@NonNull String sourcePathUnderStorage, @NonNull String targetPath)
            throws IOException {
        try {
            int res = mService.makeBindMount(mId, sourcePathUnderStorage, targetPath,
                    IIncrementalManagerNative.BIND_PERMANENT);
            if (res < 0) {
                throw new IOException("bind() permanent failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unbinds a bind mount.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void unBind(@NonNull String targetPath) throws IOException {
        try {
            int res = mService.deleteBindMount(mId, targetPath);
            if (res < 0) {
                throw new IOException("unbind() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a sub-directory under the current storage directory.
     *
     * @param pathUnderStorage Relative path of the sub-directory, e.g., "subdir"
     */
    public void makeDirectory(@NonNull String pathUnderStorage) throws IOException {
        try {
            int res = mService.makeDirectory(mId, pathUnderStorage);
            if (res < 0) {
                throw new IOException("makeDirectory() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a sub-directory under the current storage directory. If its parent dirs do not exist,
     * create the parent dirs as well.
     *
     * @param pathUnderStorage Relative path of the sub-directory, e.g., "subdir/subsubdir"
     */
    public void makeDirectories(@NonNull String pathUnderStorage) throws IOException {
        try {
            int res = mService.makeDirectories(mId, pathUnderStorage);
            if (res < 0) {
                throw new IOException("makeDirectory() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a file under the current storage directory.
     *
     * @param pathUnderStorage Relative path of the new file.
     * @param size             Size of the new file in bytes.
     * @param metadata         Metadata bytes.
     */
    public void makeFile(@NonNull String pathUnderStorage, long size,
            @Nullable byte[] metadata) throws IOException {
        try {
            int res = mService.makeFile(mId, pathUnderStorage, size, metadata);
            if (res < 0) {
                throw new IOException("makeFile() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a file in Incremental storage. The content of the file is mapped from a range inside
     * a source file in the same storage.
     *
     * @param destRelativePath   Target relative path under storage.
     * @param sourceRelativePath Source relative path under storage.
     * @param rangeStart         Starting offset (in bytes) in the source file.
     * @param rangeEnd           Ending offset (in bytes) in the source file.
     */
    public void makeFileFromRange(@NonNull String destRelativePath,
            @NonNull String sourceRelativePath, long rangeStart, long rangeEnd) throws IOException {
        try {
            int res = mService.makeFileFromRange(mId, destRelativePath, sourceRelativePath,
                    rangeStart, rangeEnd);
            if (res < 0) {
                throw new IOException("makeFileFromRange() failed, errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a hard-link between two paths, which can be under different storages but in the same
     * Incremental File System.
     *
     * @param sourcePathUnderStorage The relative path of the source.
     * @param destStorage            The target storage of the link target.
     * @param destPathUnderStorage   The relative path of the target.
     */
    public void makeLink(@NonNull String sourcePathUnderStorage, IncrementalStorage destStorage,
            @NonNull String destPathUnderStorage) throws IOException {
        try {
            int res = mService.makeLink(mId, sourcePathUnderStorage, destStorage.getId(),
                    destPathUnderStorage);
            if (res < 0) {
                throw new IOException("makeLink() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes a hard-link under the current storage directory.
     *
     * @param pathUnderStorage The relative path of the target.
     */
    public void unlink(@NonNull String pathUnderStorage) throws IOException {
        try {
            int res = mService.unlink(mId, pathUnderStorage);
            if (res < 0) {
                throw new IOException("unlink() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Rename an old file name to a new file name under the current storage directory.
     *
     * @param sourcePathUnderStorage Old file path as a relative path to the storage directory.
     * @param destPathUnderStorage   New file path as a relative path to the storage directory.
     */
    public void moveFile(@NonNull String sourcePathUnderStorage,
            @NonNull String destPathUnderStorage) throws IOException {
        try {
            int res = mService.makeLink(mId, sourcePathUnderStorage, mId, destPathUnderStorage);
            if (res < 0) {
                throw new IOException("moveFile() failed at makeLink(), errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        try {
            mService.unlink(mId, sourcePathUnderStorage);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Move a directory, which is bind-mounted to a given storage, to a new location. The bind mount
     * will be persistent between reboots.
     *
     * @param sourcePath The old path of the directory as an absolute path.
     * @param destPath   The new path of the directory as an absolute path, expected to already
     *                   exist.
     */
    public void moveDir(@NonNull String sourcePath, @NonNull String destPath) throws IOException {
        if (!new File(destPath).exists()) {
            throw new IOException("moveDir() requires that destination dir already exists.");
        }
        try {
            int res = mService.makeBindMount(mId, "", destPath,
                    IIncrementalManagerNative.BIND_PERMANENT);
            if (res < 0) {
                throw new IOException("moveDir() failed at making bind mount, errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        try {
            mService.deleteBindMount(mId, sourcePath);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Checks whether a file under the current storage directory is fully loaded.
     *
     * @param pathUnderStorage The relative path of the file.
     * @return True if the file is fully loaded.
     */
    public boolean isFileFullyLoaded(@NonNull String pathUnderStorage) {
        return isFileRangeLoaded(pathUnderStorage, 0, -1);
    }

    /**
     * Checks whether a range in a file if loaded.
     *
     * @param pathUnderStorage The relative path of the file.
     * @param start            The starting offset of the range.
     * @param end              The ending offset of the range.
     * @return True if the file is fully loaded.
     */
    public boolean isFileRangeLoaded(@NonNull String pathUnderStorage, long start, long end) {
        try {
            return mService.isFileRangeLoaded(mId, pathUnderStorage, start, end);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Returns the metadata object of an IncFs File.
     *
     * @param pathUnderStorage The relative path of the file.
     * @return Byte array that contains metadata bytes.
     */
    @Nullable
    public byte[] getFileMetadata(@NonNull String pathUnderStorage) {
        try {
            return mService.getFileMetadata(mId, pathUnderStorage);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Informs the data loader service associated with the current storage to start data loader
     *
     * @return True if data loader is successfully started.
     */
    public boolean startLoading() {
        try {
            return mService.startLoading(mId);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }
}
