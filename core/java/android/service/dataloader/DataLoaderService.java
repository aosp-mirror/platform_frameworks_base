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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.DataLoaderParams;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.IPackageInstallerSessionFileSystemConnector;
import android.content.pm.InstallationFile;
import android.content.pm.NamedParcelFileDescriptor;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ExceptionUtils;
import android.util.Slog;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * The base class for implementing data loader service to control data loaders. Expecting
 * Incremental Service to bind to a children class of this.
 *
 * WARNING: This is a system API to aid internal development.
 * Use at your own risk. It will change or be removed without warning.
 *
 * TODO(b/136132412): update with latest API design
 *
 * @hide
 */
@SystemApi
public abstract class DataLoaderService extends Service {
    private static final String TAG = "IncrementalDataLoaderService";
    private final DataLoaderBinderService mBinder = new DataLoaderBinderService();

    /** @hide */
    public static final int DATA_LOADER_READY =
            IDataLoaderStatusListener.DATA_LOADER_READY;
    /** @hide */
    public static final int DATA_LOADER_NOT_READY =
            IDataLoaderStatusListener.DATA_LOADER_NOT_READY;
    /** @hide */
    public static final int DATA_LOADER_RUNNING =
            IDataLoaderStatusListener.DATA_LOADER_RUNNING;
    /** @hide */
    public static final int DATA_LOADER_STOPPED =
            IDataLoaderStatusListener.DATA_LOADER_STOPPED;
    /** @hide */
    public static final int DATA_LOADER_SLOW_CONNECTION =
            IDataLoaderStatusListener.DATA_LOADER_SLOW_CONNECTION;
    /** @hide */
    public static final int DATA_LOADER_NO_CONNECTION =
            IDataLoaderStatusListener.DATA_LOADER_NO_CONNECTION;
    /** @hide */
    public static final int DATA_LOADER_CONNECTION_OK =
            IDataLoaderStatusListener.DATA_LOADER_CONNECTION_OK;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"DATA_LOADER_"}, value = {
            DATA_LOADER_READY,
            DATA_LOADER_NOT_READY,
            DATA_LOADER_RUNNING,
            DATA_LOADER_STOPPED,
            DATA_LOADER_SLOW_CONNECTION,
            DATA_LOADER_NO_CONNECTION,
            DATA_LOADER_CONNECTION_OK
    })
    public @interface DataLoaderStatus {
    }

    /**
     * Managed DataLoader interface. Each instance corresponds to a single Incremental File System
     * instance.
     * @hide
     */
    public abstract static class DataLoader {
        /**
         * A virtual constructor used to do simple initialization. Not ready to serve any data yet.
         * All heavy-lifting has to be done in onStart.
         *
         * @param params    Data loader configuration parameters.
         * @param connector IncFS API wrapper.
         * @param listener  Used for reporting internal state to IncrementalService.
         * @return True if initialization of a Data Loader was successful. False will be reported to
         * IncrementalService and can cause an unmount of an IFS instance.
         */
        public abstract boolean onCreate(@NonNull DataLoaderParams params,
                @NonNull FileSystemConnector connector,
                @NonNull StatusListener listener);

        /**
         * Start the data loader. After this method returns data loader is considered to be ready to
         * receive callbacks from IFS, supply data via connector and send status updates via
         * callbacks.
         *
         * @return True if Data Loader was able to start. False will be reported to
         * IncrementalService and can cause an unmount of an IFS instance.
         */
        public abstract boolean onStart();

        /**
         * Stop the data loader. Use to stop any additional threads and free up resources. Data
         * loader is not longer responsible for supplying data. Start/Stop pair can be called
         * multiple times e.g. if IFS detects corruption and data needs to be re-loaded.
         */
        public abstract void onStop();

        /**
         * Virtual destructor. Use to cleanup all internal state. After this method returns, the
         * data loader can no longer use connector or callbacks. For any additional operations with
         * this instance of IFS a new DataLoader will be created using createDataLoader method.
         */
        public abstract void onDestroy();
    }

    /**
     * DataLoader factory method.
     *
     * @return An instance of a DataLoader.
     * @hide
     */
    public abstract @Nullable DataLoader onCreateDataLoader();

    /**
     * @hide
     */
    public final @NonNull IBinder onBind(@NonNull Intent intent) {
        return (IBinder) mBinder;
    }

    private class DataLoaderBinderService extends IDataLoader.Stub {
        private int mId;

        @Override
        public void create(int id, @NonNull Bundle options,
                @NonNull IDataLoaderStatusListener listener)
                    throws IllegalArgumentException, RuntimeException {
            mId = id;
            final DataLoaderParamsParcel params =  options.getParcelable("params");
            if (params == null) {
                throw new IllegalArgumentException("Must specify Incremental data loader params");
            }
            final FileSystemControlParcel control =
                    options.getParcelable("control");
            if (control == null) {
                throw new IllegalArgumentException("Must specify Incremental control parcel");
            }
            mStatusListener = listener;
            try {
                if (!nativeCreateDataLoader(id, control, params, listener)) {
                    Slog.e(TAG, "Failed to create native loader for " + mId);
                }
            } catch (Exception ex) {
                destroy();
                throw new RuntimeException(ex);
            } finally {
                // Closing FDs.
                if (control.incremental.cmd != null) {
                    try {
                        control.incremental.cmd.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to close IncFs CMD file descriptor " + e);
                    }
                }
                if (control.incremental.log != null) {
                    try {
                        control.incremental.log.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to close IncFs LOG file descriptor " + e);
                    }
                }
                NamedParcelFileDescriptor[] fds = params.dynamicArgs;
                for (NamedParcelFileDescriptor nfd : fds) {
                    try {
                        nfd.fd.close();
                    } catch (IOException e) {
                        Slog.e(TAG,
                                "Failed to close DynamicArgs parcel file descriptor " + e);
                    }
                }
            }
        }

        @Override
        public void start(List<InstallationFile> fileInfos) {
            if (!nativeStartDataLoader(mId)) {
                Slog.e(TAG, "Failed to start loader: loader not found for " + mId);
            }
        }

        @Override
        public void stop() {
            if (!nativeStopDataLoader(mId)) {
                Slog.w(TAG, "Failed to stop loader: loader not found for " + mId);
            }
        }

        @Override
        public void destroy() {
            if (!nativeDestroyDataLoader(mId)) {
                Slog.w(TAG, "Failed to destroy loader: loader not found for " + mId);
            }
        }
    }

    /**
     *
     * Used by the DataLoaderService implementations.
     *
     * @hide
     */
    public static final class FileSystemConnector {
        /**
         * Creates a wrapper for an installation session connector.
         * @hide
         */
        FileSystemConnector(IPackageInstallerSessionFileSystemConnector connector) {
            mConnector = connector;
        }

        /**
         * Write data to an installation file from an arbitrary FD.
         *
         * @param name name of file previously added to the installation session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *            start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *            preallocate the underlying disk space, or -1 if unknown.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @param incomingFd FD to read bytes from.
         * @throws IOException if trouble opening the file for writing, such as
         *             lack of disk space or unavailable media.
         */
        public void writeData(String name, long offsetBytes, long lengthBytes,
                ParcelFileDescriptor incomingFd) throws IOException {
            try {
                mConnector.writeData(name, offsetBytes, lengthBytes, incomingFd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        private final IPackageInstallerSessionFileSystemConnector mConnector;
    }

    /**
     * Wrapper for native reporting DataLoader statuses.
     * @hide
     */
    public static final class StatusListener {
        /**
         * Creates a wrapper for a native instance.
         * @hide
         */
        StatusListener(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        /**
         * Report the status of DataLoader. Used for system-wide notifications e.g., disabling
         * applications which rely on this data loader to function properly.
         *
         * @param status status to report.
         * @return True if status was reported successfully.
         */
        public boolean onStatusChanged(@DataLoaderStatus int status) {
            return nativeReportStatus(mNativeInstance, status);
        }

        private final long mNativeInstance;
    }

    private IDataLoaderStatusListener mStatusListener = null;

    /* Native methods */
    private native boolean nativeCreateDataLoader(int storageId,
            @NonNull FileSystemControlParcel control,
            @NonNull DataLoaderParamsParcel params,
            IDataLoaderStatusListener listener);

    private native boolean nativeStartDataLoader(int storageId);

    private native boolean nativeStopDataLoader(int storageId);

    private native boolean nativeDestroyDataLoader(int storageId);

    private static native boolean nativeReportStatus(long nativeInstance, int status);
}
