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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteException;

/**
 * Data loader manager takes care of data loaders of different packages. It provides methods to
 * initialize a data loader binder service (binding and creating it), to return a binder of the data
 * loader binder service and to destroy a data loader binder service.
 * @see com.android.server.pm.DataLoaderManagerService
 * @hide
 */
public class DataLoaderManager {
    private static final String TAG = "DataLoaderManager";
    private final IDataLoaderManager mService;

    public DataLoaderManager(IDataLoaderManager service) {
        mService = service;
    }

    /**
     * Finds a data loader binder service and binds to it. This requires PackageManager.
     *
     * @param dataLoaderId ID for the new data loader binder service.
     * @param params       Bundle that contains parameters to configure the data loader service.
     *                     Must contain:
     *                     key: "packageName", value: String, package name of data loader service
     *                     package;
     *                     key: "extras", value: Bundle, client-specific data structures
     *
     * @param listener     Callback for the data loader service to report status back to the
     *                     caller.
     * @return false if 1) target ID collides with a data loader that is already bound to data
     * loader manager; 2) package name is not specified; 3) fails to find data loader package;
     * or 4) fails to bind to the specified data loader service, otherwise return true.
     */
    public boolean initializeDataLoader(int dataLoaderId, @NonNull Bundle params,
            @NonNull IDataLoaderStatusListener listener) {
        try {
            return mService.initializeDataLoader(dataLoaderId, params, listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a binder interface of the data loader binder service, given its ID.
     */
    @Nullable
    public IDataLoader getDataLoader(int dataLoaderId) {
        try {
            return mService.getDataLoader(dataLoaderId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Destroys the data loader binder service and removes it from data loader manager service.
     */
    @Nullable
    public void destroyDataLoader(int dataLoaderId) {
        try {
            mService.destroyDataLoader(dataLoaderId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
