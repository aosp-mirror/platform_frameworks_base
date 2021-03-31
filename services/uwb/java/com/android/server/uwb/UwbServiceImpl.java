/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.uwb;

import android.annotation.NonNull;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;
import android.uwb.IUwbAdapter;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.SessionHandle;

/**
 * Implementation of {@link android.uwb.IUwbAdapter} binder service.
 */
public class UwbServiceImpl extends IUwbAdapter.Stub {
    private static final String TAG = "UwbServiceImpl";

    private final Context mContext;
    private final UwbInjector mUwbInjector;

    /**
     * Used for caching the vendor implementation of {@link IUwbAdapter} interface.
     */
    private IUwbAdapter mVendorUwbAdapter;

    private IUwbAdapter getVendorUwbAdapter() throws IllegalStateException {
        if (mVendorUwbAdapter != null) return mVendorUwbAdapter;
        mVendorUwbAdapter = mUwbInjector.getVendorService();
        if (mVendorUwbAdapter == null) {
            throw new IllegalStateException("No vendor service found!");
        }
        Log.i(TAG, "Retrieved vendor service");
        return mVendorUwbAdapter;
    }

    public UwbServiceImpl(@NonNull Context context, @NonNull UwbInjector uwbInjector) {
        mContext = context;
        mUwbInjector = uwbInjector;
    }

    @Override
    public void registerAdapterStateCallbacks(IUwbAdapterStateCallbacks adapterStateCallbacks)
            throws RemoteException {
        getVendorUwbAdapter().registerAdapterStateCallbacks(adapterStateCallbacks);
    }

    @Override
    public void unregisterAdapterStateCallbacks(IUwbAdapterStateCallbacks adapterStateCallbacks)
            throws RemoteException {
        getVendorUwbAdapter().unregisterAdapterStateCallbacks(adapterStateCallbacks);
    }

    @Override
    public long getTimestampResolutionNanos() throws RemoteException {
        return getVendorUwbAdapter().getTimestampResolutionNanos();
    }

    @Override
    public PersistableBundle getSpecificationInfo() throws RemoteException {
        return getVendorUwbAdapter().getSpecificationInfo();
    }

    @Override
    public void openRanging(SessionHandle sessionHandle, IUwbRangingCallbacks rangingCallbacks,
            PersistableBundle parameters) throws RemoteException {
        getVendorUwbAdapter().openRanging(sessionHandle, rangingCallbacks, parameters);
    }

    @Override
    public void startRanging(SessionHandle sessionHandle, PersistableBundle parameters)
            throws RemoteException {
        getVendorUwbAdapter().startRanging(sessionHandle, parameters);
    }

    @Override
    public void reconfigureRanging(SessionHandle sessionHandle, PersistableBundle parameters)
            throws RemoteException {
        getVendorUwbAdapter().reconfigureRanging(sessionHandle, parameters);
    }

    @Override
    public void stopRanging(SessionHandle sessionHandle) throws RemoteException {
        getVendorUwbAdapter().stopRanging(sessionHandle);
    }

    @Override
    public void closeRanging(SessionHandle sessionHandle) throws RemoteException {
        getVendorUwbAdapter().closeRanging(sessionHandle);
    }
}
