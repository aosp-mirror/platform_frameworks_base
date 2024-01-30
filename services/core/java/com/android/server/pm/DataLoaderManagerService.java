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

package com.android.server.pm;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderManager;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.SystemService;

import java.util.List;

/**
 * Data loader manager service manages data loader binder services.
 *
 * @hide
 */
public class DataLoaderManagerService extends SystemService {
    private static final String TAG = "DataLoaderManager";
    private final Context mContext;
    private final HandlerThread mThread;
    private final Handler mHandler;
    private final DataLoaderManagerBinderService mBinderService;
    private final SparseArray<DataLoaderServiceConnection> mServiceConnections =
            new SparseArray<>();

    public DataLoaderManagerService(Context context) {
        super(context);
        mContext = context;

        mThread = new HandlerThread(TAG);
        mThread.start();

        mHandler = new Handler(mThread.getLooper());

        mBinderService = new DataLoaderManagerBinderService();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DATA_LOADER_MANAGER_SERVICE, mBinderService);
    }

    final class DataLoaderManagerBinderService extends IDataLoaderManager.Stub {
        @Override
        public boolean bindToDataLoader(int dataLoaderId, DataLoaderParamsParcel params,
                long bindDelayMs, IDataLoaderStatusListener listener) {
            synchronized (mServiceConnections) {
                if (mServiceConnections.get(dataLoaderId) != null) {
                    return true;
                }
            }
            ComponentName componentName = new ComponentName(params.packageName, params.className);
            ComponentName dataLoaderComponent = resolveDataLoaderComponentName(componentName);
            if (dataLoaderComponent == null) {
                Slog.e(TAG, "Invalid component: " + componentName + " for ID=" + dataLoaderId);
                return false;
            }

            // Binds to the specific data loader service.
            final DataLoaderServiceConnection connection = new DataLoaderServiceConnection(
                    dataLoaderId, listener);

            final Intent intent = new Intent();
            intent.setComponent(dataLoaderComponent);

            return mHandler.postDelayed(() -> {
                if (!mContext.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE,
                        mHandler, UserHandle.of(UserHandle.getCallingUserId()))) {
                    Slog.e(TAG,
                            "Failed to bind to: " + dataLoaderComponent + " for ID="
                                    + dataLoaderId);
                    mContext.unbindService(connection);
                }
            }, bindDelayMs);
        }

        /**
         * Find the ComponentName of the data loader service provider, given its package name.
         *
         * @param componentName the name of the provider.
         * @return ComponentName of the data loader service provider. Null if provider not found.
         */
        private @Nullable ComponentName resolveDataLoaderComponentName(
                ComponentName componentName) {
            final PackageManager pm = mContext.getPackageManager();
            if (pm == null) {
                Slog.e(TAG, "PackageManager is not available.");
                return null;
            }
            Intent intent = new Intent(Intent.ACTION_LOAD_DATA);
            intent.setComponent(componentName);
            List<ResolveInfo> services =
                    pm.queryIntentServicesAsUser(intent, 0, UserHandle.getCallingUserId());
            if (services == null || services.isEmpty()) {
                Slog.e(TAG,
                        "Failed to find data loader service provider in " + componentName);
                return null;
            }

            int numServices = services.size();
            for (int i = 0; i < numServices; i++) {
                ResolveInfo ri = services.get(i);
                ComponentName resolved = new ComponentName(
                        ri.serviceInfo.packageName, ri.serviceInfo.name);
                // There should only be one matching provider inside the given package.
                // If there's more than one, return the first one found.
                return resolved;
            }
            Slog.e(TAG, "Didn't find any matching data loader service provider.");
            return null;
        }

        /**
         * Returns the binder object of a data loader, specified by its ID.
         */
        @Override
        public @Nullable IDataLoader getDataLoader(int dataLoaderId) {
            synchronized (mServiceConnections) {
                DataLoaderServiceConnection serviceConnection = mServiceConnections.get(
                        dataLoaderId, null);
                if (serviceConnection == null) {
                    return null;
                }
                return serviceConnection.getDataLoader();
            }
        }

        /**
         * Unbinds from a data loader binder service, specified by its ID. DataLoader will receive
         * destroy notification.
         */
        @Override
        public void unbindFromDataLoader(int dataLoaderId) {
            synchronized (mServiceConnections) {
                DataLoaderServiceConnection serviceConnection = mServiceConnections.get(
                        dataLoaderId, null);
                if (serviceConnection == null) {
                    return;
                }
                serviceConnection.destroy();
            }
        }
    }

    private class DataLoaderServiceConnection implements ServiceConnection, IBinder.DeathRecipient {
        final int mId;
        final IDataLoaderStatusListener mListener;
        IDataLoader mDataLoader;

        DataLoaderServiceConnection(int id, IDataLoaderStatusListener listener) {
            mId = id;
            mListener = listener;
            mDataLoader = null;

            callListener(IDataLoaderStatusListener.DATA_LOADER_BINDING);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mDataLoader = IDataLoader.Stub.asInterface(service);
            if (mDataLoader == null) {
                onNullBinding(className);
                return;
            }
            if (!append()) {
                // Another connection already bound for this ID.
                mContext.unbindService(this);
                return;
            }
            try {
                service.linkToDeath(this, /*flags=*/0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to DataLoader's death: " + mId, e);
                onBindingDied(className);
                return;
            }
            callListener(IDataLoaderStatusListener.DATA_LOADER_BOUND);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Slog.i(TAG, "DataLoader " + mId + " disconnected, but will try to recover");
            unbindAndReportDestroyed();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Slog.i(TAG, "DataLoader " + mId + " died");
            unbindAndReportDestroyed();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Slog.i(TAG, "DataLoader " + mId + " failed to start");
            unbindAndReportDestroyed();
        }

        @Override
        public void binderDied() {
            Slog.i(TAG, "DataLoader " + mId + " died");
            unbindAndReportDestroyed();
        }

        IDataLoader getDataLoader() {
            return mDataLoader;
        }

        private void unbindAndReportDestroyed() {
            if (unbind()) {
                callListener(IDataLoaderStatusListener.DATA_LOADER_DESTROYED);
            }
        }

        void destroy() {
            if (mDataLoader != null) {
                try {
                    mDataLoader.destroy(mId);
                } catch (RemoteException ignored) {
                }
                mDataLoader = null;
            }
            unbind();
        }

        boolean unbind() {
            try {
                mContext.unbindService(this);
            } catch (Exception ignored) {
            }
            return remove();
        }

        private boolean append() {
            synchronized (mServiceConnections) {
                DataLoaderServiceConnection bound = mServiceConnections.get(mId);
                if (bound == this) {
                    return true;
                }
                if (bound != null) {
                    // Another connection already bound for this ID.
                    return false;
                }
                mServiceConnections.append(mId, this);
                return true;
            }
        }

        private boolean remove() {
            synchronized (mServiceConnections) {
                if (mServiceConnections.get(mId) == this) {
                    mServiceConnections.remove(mId);
                    return true;
                }
            }
            return false;
        }

        private void callListener(int status) {
            if (mListener != null) {
                try {
                    mListener.onStatusChanged(mId, status);
                } catch (RemoteException ignored) {
                }
            }
        }
    }
}
