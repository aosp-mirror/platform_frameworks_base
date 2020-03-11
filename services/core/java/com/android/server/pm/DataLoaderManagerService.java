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
import android.content.pm.ApplicationInfo;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderManager;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
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
    private final DataLoaderManagerBinderService mBinderService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private SparseArray<DataLoaderServiceConnection> mServiceConnections = new SparseArray<>();

    public DataLoaderManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new DataLoaderManagerBinderService();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DATA_LOADER_MANAGER_SERVICE, mBinderService);
    }

    final class DataLoaderManagerBinderService extends IDataLoaderManager.Stub {
        @Override
        public boolean initializeDataLoader(int dataLoaderId, DataLoaderParamsParcel params,
                FileSystemControlParcel control, IDataLoaderStatusListener listener) {
            synchronized (mLock) {
                if (mServiceConnections.get(dataLoaderId) != null) {
                    Slog.e(TAG, "Data loader of ID=" + dataLoaderId + " already exists.");
                    return false;
                }
            }
            ComponentName componentName = new ComponentName(params.packageName, params.className);
            ComponentName dataLoaderComponent = resolveDataLoaderComponentName(componentName);
            if (dataLoaderComponent == null) {
                return false;
            }
            // Binds to the specific data loader service
            DataLoaderServiceConnection connection =
                    new DataLoaderServiceConnection(dataLoaderId, params, control, listener);
            Intent intent = new Intent();
            intent.setComponent(dataLoaderComponent);
            if (!mContext.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE,
                    UserHandle.of(UserHandle.getCallingUserId()))) {
                Slog.e(TAG, "Failed to bind to data loader binder service.");
                mContext.unbindService(connection);
                return false;
            }
            return true;
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
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(resolved.getPackageName(), 0);
                    if (!ai.isPrivilegedApp()) {
                        Slog.w(TAG,
                                "Data loader: " + resolved + " is not a privileged app, skipping.");
                        continue;
                    }
                    return resolved;
                } catch (PackageManager.NameNotFoundException ex) {
                    Slog.w(TAG,
                            "Privileged data loader: " + resolved + " not found, skipping.");
                }

            }
            Slog.e(TAG, "Didn't find any matching data loader service provider.");
            return null;
        }

        /**
         * Returns the binder object of a data loader, specified by its ID.
         */
        @Override
        public @Nullable IDataLoader getDataLoader(int dataLoaderId) {
            synchronized (mLock) {
                DataLoaderServiceConnection serviceConnection = mServiceConnections.get(
                        dataLoaderId, null);
                if (serviceConnection == null) {
                    return null;
                }
                return serviceConnection.getDataLoader();
            }
        }

        /**
         * Destroys a data loader binder service, specified by its ID.
         */
        @Override
        public void destroyDataLoader(int dataLoaderId) {
            synchronized (mLock) {
                DataLoaderServiceConnection serviceConnection = mServiceConnections.get(
                        dataLoaderId, null);

                if (serviceConnection == null) {
                    return;
                }
                serviceConnection.destroy();
            }
        }
    }

    class DataLoaderServiceConnection implements ServiceConnection {
        final int mId;
        final DataLoaderParamsParcel mParams;
        final FileSystemControlParcel mControl;
        final IDataLoaderStatusListener mListener;
        IDataLoader mDataLoader;

        DataLoaderServiceConnection(int id, DataLoaderParamsParcel params,
                FileSystemControlParcel control, IDataLoaderStatusListener listener) {
            mId = id;
            mParams = params;
            mControl = control;
            mListener = listener;
            mDataLoader = null;
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mDataLoader = IDataLoader.Stub.asInterface(service);
            synchronized (mLock) {
                mServiceConnections.append(mId, this);
            }
            try {
                mDataLoader.create(mId, mParams, mControl, mListener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to create data loader service.", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            remove();
        }

        IDataLoader getDataLoader() {
            return mDataLoader;
        }

        void destroy() {
            try {
                mDataLoader.destroy();
            } catch (RemoteException ignored) {
            }
            mContext.unbindService(this);
        }

        private void remove() {
            synchronized (mLock) {
                mServiceConnections.remove(mId);
            }
        }
    }
}
