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

package com.android.server.incremental;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.DataLoaderManager;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.incremental.IIncrementalManager;
import android.util.Slog;

import java.io.FileDescriptor;

/**
 * This service has the following purposes:
 * 1) Starts the IIncrementalManager binder service.
 * 1) Starts the native IIncrementalManagerService binder service.
 * 2) Handles shell commands for "incremental" service.
 * 3) Handles binder calls from the native IIncrementalManagerService binder service and pass
 *    them to a data loader binder service.
 */

public class IncrementalManagerService extends IIncrementalManager.Stub  {
    private static final String TAG = "IncrementalManagerService";
    private static final String BINDER_SERVICE_NAME = "incremental";
    // DataLoaderManagerService should have been started before us
    private @NonNull DataLoaderManager mDataLoaderManager;
    private long mNativeInstance;
    private final @NonNull Context mContext;

    /**
     * Starts IIncrementalManager binder service and register to Service Manager.
     * Starts the native IIncrementalManagerNative binder service.
     */
    public static IncrementalManagerService start(Context context) {
        IncrementalManagerService self = new IncrementalManagerService(context);
        if (self.mNativeInstance == 0) {
            return null;
        }
        return self;
    }

    private IncrementalManagerService(Context context) {
        mContext = context;
        mDataLoaderManager = mContext.getSystemService(DataLoaderManager.class);
        ServiceManager.addService(BINDER_SERVICE_NAME, this);
        // Starts and register IIncrementalManagerNative service
        // TODO(b/136132412): add jni implementation
    }
    /**
     * Notifies native IIncrementalManager service that system is ready.
     */
    public void systemReady() {
        // TODO(b/136132412): add jni implementation
    }

    /**
     * Finds data loader service provider and binds to it. This requires PackageManager.
     */
    @Override
    public boolean prepareDataLoader(int mountId, FileSystemControlParcel control,
            DataLoaderParamsParcel params,
            IDataLoaderStatusListener listener) {
        Bundle dataLoaderParams = new Bundle();
        dataLoaderParams.putCharSequence("packageName", params.packageName);
        dataLoaderParams.putParcelable("control", control);
        dataLoaderParams.putParcelable("params", params);
        DataLoaderManager dataLoaderManager = mContext.getSystemService(DataLoaderManager.class);
        if (dataLoaderManager == null) {
            Slog.e(TAG, "Failed to find data loader manager service");
            return false;
        }
        if (!dataLoaderManager.initializeDataLoader(mountId, dataLoaderParams, listener)) {
            Slog.e(TAG, "Failed to initialize data loader");
            return false;
        }
        return true;
    }


    @Override
    public boolean startDataLoader(int mountId) {
        IDataLoader dataLoader = mDataLoaderManager.getDataLoader(mountId);
        if (dataLoader == null) {
            Slog.e(TAG, "Start failed to retrieve data loader for ID=" + mountId);
            return false;
        }
        try {
            // TODO: fix file list
            dataLoader.start(null);
            return true;
        } catch (RemoteException ex) {
            return false;
        }
    }

    @Override
    public void destroyDataLoader(int mountId) {
        IDataLoader dataLoader = mDataLoaderManager.getDataLoader(mountId);
        if (dataLoader == null) {
            Slog.e(TAG, "Destroy failed to retrieve data loader for ID=" + mountId);
            return;
        }
        try {
            dataLoader.destroy();
        } catch (RemoteException ex) {
            return;
        }
    }

    // TODO: remove this
    @Override
    public void newFileForDataLoader(int mountId, long inode, byte[] metadata) {
        IDataLoader dataLoader = mDataLoaderManager.getDataLoader(mountId);
        if (dataLoader == null) {
            Slog.e(TAG, "Failed to retrieve data loader for ID=" + mountId);
            return;
        }
    }

    @Override
    public void showHealthBlockedUI(int mountId) {
        // TODO(b/136132412): implement this
    }

    @Override
    public void onShellCommand(@NonNull FileDescriptor in, @NonNull FileDescriptor out,
            FileDescriptor err, @NonNull String[] args, ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) {
        (new IncrementalManagerShellCommand(mContext)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }
}
