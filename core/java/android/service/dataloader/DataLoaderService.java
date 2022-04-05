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

package android.service.dataloader;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.DataLoaderParams;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.InstallationFile;
import android.content.pm.InstallationFileParcel;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.ExceptionUtils;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.IOException;
import java.util.Collection;

/**
 * The base class for implementing a data loader service.
 * <p>
 * After calling commit() on the install session, the DataLoaderService is started and bound to
 * provide the actual data bytes for the streaming session.
 * The service will automatically be rebound until the streaming session has enough data to
 * proceed with the installation.
 *
 * @see android.content.pm.DataLoaderParams
 * @see android.content.pm.PackageInstaller.SessionParams#setDataLoaderParams
 *
 * @hide
 */
@SystemApi
public abstract class DataLoaderService extends Service {
    private static final String TAG = "DataLoaderService";
    private final DataLoaderBinderService mBinder = new DataLoaderBinderService();

    /**
     * DataLoader interface. Each instance corresponds to a single installation session.
     * @hide
     */
    @SystemApi
    public interface DataLoader {
        /**
         * A virtual constructor.
         *
         * @param dataLoaderParams parameters set in the installation session
         * {@link android.content.pm.PackageInstaller.SessionParams#setDataLoaderParams}
         * @param connector Wrapper providing access to the installation image.
         * @return true if initialization of a DataLoader was successful. False will notify the
         * Installer {@link android.content.pm.PackageInstaller#STATUS_PENDING_STREAMING} and
         * interrupt the session commit. The Installer is supposed to make sure DataLoader can
         * proceed and then commit the session
         * {@link android.content.pm.PackageInstaller.Session#commit}.
         */
        boolean onCreate(@NonNull DataLoaderParams dataLoaderParams,
                @NonNull FileSystemConnector connector);

        /**
         * Prepare installation image. After this method succeeds installer will validate the files
         * and continue installation.
         * The method should block until the files are prepared for installation.
         * This can take up to session lifetime (~day). If the session lifetime is exceeded then
         * any attempts to write new data will fail.
         *
         * Example implementation:
         * <code>
         *     String localPath = "/data/local/tmp/base.apk";
         *     session.addFile(LOCATION_DATA_APP, "base", 123456, localPath.getBytes(UTF_8), null);
         *     ...
         *     // onPrepareImage
         *     for (InstallationFile file : addedFiles) {
         *         String localPath = new String(file.getMetadata(), UTF_8);
         *         File source = new File(localPath);
         *         ParcelFileDescriptor fd = ParcelFileDescriptor.open(source, MODE_READ_ONLY);
         *         try {
         *             mConnector.writeData(file.getName(), 0, fd.getStatSize(), fd);
         *         } finally {
         *             IoUtils.closeQuietly(fd);
         *         }
         *     }
         * </code>
         * It is recommended to stream data into installation session directly from source, e.g.
         * cloud data storage, to save local disk space.
         *
         * @param addedFiles   list of files created in this installation session
         * {@link android.content.pm.PackageInstaller.Session#addFile}
         * @param removedFiles list of files removed in this installation session
         * {@link android.content.pm.PackageInstaller.Session#removeFile}
         * @return false if unable to create and populate all addedFiles. Installation will fail.
         */
        boolean onPrepareImage(@NonNull Collection<InstallationFile> addedFiles,
                @NonNull Collection<String> removedFiles);
    }

    /**
     * DataLoader factory method.
     * An installation session uses it to create an instance of DataLoader.
     * @hide
     */
    @SystemApi
    public @Nullable DataLoader onCreateDataLoader(@NonNull DataLoaderParams dataLoaderParams) {
        return null;
    }

    /**
     * @hide
     */
    public final @NonNull IBinder onBind(@NonNull Intent intent) {
        return (IBinder) mBinder;
    }

    private class DataLoaderBinderService extends IDataLoader.Stub {
        @Override
        public void create(int id, @NonNull DataLoaderParamsParcel params,
                @NonNull FileSystemControlParcel control,
                @NonNull IDataLoaderStatusListener listener)
                throws RuntimeException {
            try {
                nativeCreateDataLoader(id, control, params, listener);
            } catch (Exception ex) {
                Slog.e(TAG, "Failed to create native loader for " + id, ex);
                destroy(id);
                throw new RuntimeException(ex);
            } finally {
                if (control.incremental != null) {
                    IoUtils.closeQuietly(control.incremental.cmd);
                    IoUtils.closeQuietly(control.incremental.pendingReads);
                    IoUtils.closeQuietly(control.incremental.log);
                    IoUtils.closeQuietly(control.incremental.blocksWritten);
                }
            }
        }

        @Override
        public void start(int id) {
            if (!nativeStartDataLoader(id)) {
                Slog.e(TAG, "Failed to start loader: " + id);
            }
        }

        @Override
        public void stop(int id) {
            if (!nativeStopDataLoader(id)) {
                Slog.w(TAG, "Failed to stop loader: " + id);
            }
        }

        @Override
        public void destroy(int id) {
            if (!nativeDestroyDataLoader(id)) {
                Slog.w(TAG, "Failed to destroy loader: " + id);
            }
        }

        @Override
        public void prepareImage(int id, InstallationFileParcel[] addedFiles,
                String[] removedFiles) {
            if (!nativePrepareImage(id, addedFiles, removedFiles)) {
                Slog.w(TAG, "Failed to prepare image for data loader: " + id);
            }
        }
    }

    /**
     * Provides access to the installation image.
     *
     * @hide
     */
    @SystemApi
    public static final class FileSystemConnector {
        /**
         * Create a wrapper for a native instance.
         *
         * @hide
         */
        FileSystemConnector(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        /**
         * Write data to an installation file from an arbitrary FD.
         *
         * @param name        name of file previously added to the installation session
         * {@link InstallationFile#getName()}.
         * @param offsetBytes offset into the file to begin writing at, or 0 to start at the
         *                    beginning of the file.
         * @param lengthBytes total size of the file being written, used to preallocate the
         *                    underlying disk space, or -1 if unknown. The system may clear various
         *                    caches as needed to allocate this space.
         * @param incomingFd  FD to read bytes from.
         * @throws IOException if trouble opening the file for writing, such as lack of disk space
         *                     or unavailable media.
         */
        @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
        public void writeData(@NonNull String name, long offsetBytes, long lengthBytes,
                @NonNull ParcelFileDescriptor incomingFd) throws IOException {
            try {
                nativeWriteData(mNativeInstance, name, offsetBytes, lengthBytes, incomingFd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            }
        }

        private final long mNativeInstance;
    }

    /* Native methods */
    private native boolean nativeCreateDataLoader(int storageId,
            @NonNull FileSystemControlParcel control,
            @NonNull DataLoaderParamsParcel params,
            IDataLoaderStatusListener listener);

    private native boolean nativeStartDataLoader(int storageId);

    private native boolean nativeStopDataLoader(int storageId);

    private native boolean nativeDestroyDataLoader(int storageId);

    private native boolean nativePrepareImage(int storageId,
            InstallationFileParcel[] addedFiles, String[] removedFiles);

    private static native void nativeWriteData(long nativeInstance, String name, long offsetBytes,
            long lengthBytes, ParcelFileDescriptor incomingFd);

}
