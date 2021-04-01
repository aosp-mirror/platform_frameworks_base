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
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.uwb.IUwbAdapter;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.RangingReport;
import android.uwb.SessionHandle;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;

/**
 * Implementation of {@link android.uwb.IUwbAdapter} binder service.
 */
public class UwbServiceImpl extends IUwbAdapter.Stub {
    private static final String TAG = "UwbServiceImpl";

    private final Context mContext;
    private final UwbInjector mUwbInjector;
    /**
     * Map for storing the callbacks wrapper for each session.
     */
    @GuardedBy("mCallbacksMap")
    private final Map<SessionHandle, UwbRangingCallbacksWrapper> mCallbacksMap = new ArrayMap<>();

    /**
     * Used for caching the vendor implementation of {@link IUwbAdapter} interface.
     */
    private IUwbAdapter mVendorUwbAdapter;

    /**
     * Wrapper for callback registered with vendor service. This wrapper is needed for performing
     * permission check before sending the callback to the external app.
     */
    private class UwbRangingCallbacksWrapper extends IUwbRangingCallbacks.Stub
            implements IBinder.DeathRecipient{
        private final SessionHandle mSessionHandle;
        private final IUwbRangingCallbacks mExternalCb;

        UwbRangingCallbacksWrapper(@NonNull SessionHandle sessionHandle,
                @NonNull IUwbRangingCallbacks externalCb) {
            mSessionHandle = sessionHandle;
            mExternalCb = externalCb;

            // Link to death for external callback.
            linkToDeath();
        }

        private void linkToDeath() {
            IBinder binder = mExternalCb.asBinder();
            try {
                binder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link to client death event.");
            }
        }

        private void removeClientAndUnlinkToDeath() {
            // Remove from the map.
            synchronized (mCallbacksMap) {
                mCallbacksMap.remove(mSessionHandle);
            }
            IBinder binder = mExternalCb.asBinder();
            binder.unlinkToDeath(this, 0);
        }


        @Override
        public void onRangingOpened(SessionHandle sessionHandle) throws RemoteException {
            mExternalCb.onRangingOpened(sessionHandle);
        }

        @Override
        public void onRangingOpenFailed(SessionHandle sessionHandle,
                int reason, PersistableBundle parameters) throws RemoteException {
            mExternalCb.onRangingOpenFailed(sessionHandle, reason, parameters);
        }

        @Override
        public void onRangingStarted(SessionHandle sessionHandle, PersistableBundle parameters)
                throws RemoteException {
            mExternalCb.onRangingStarted(sessionHandle, parameters);
        }

        @Override
        public void onRangingStartFailed(SessionHandle sessionHandle,
                int reason, PersistableBundle parameters) throws RemoteException {
            mExternalCb.onRangingStartFailed(sessionHandle, reason, parameters);
        }

        @Override
        public void onRangingReconfigured(SessionHandle sessionHandle, PersistableBundle parameters)
                throws RemoteException {
            mExternalCb.onRangingReconfigured(sessionHandle, parameters);
        }

        @Override
        public void onRangingReconfigureFailed(SessionHandle sessionHandle,
                int reason, PersistableBundle parameters) throws RemoteException {
            mExternalCb.onRangingReconfigureFailed(sessionHandle, reason, parameters);
        }

        @Override
        public void onRangingStopped(SessionHandle sessionHandle, int reason,
                PersistableBundle parameters)
                throws RemoteException {
            mExternalCb.onRangingStopped(sessionHandle, reason, parameters);
        }

        @Override
        public void onRangingStopFailed(SessionHandle sessionHandle, int reason,
                PersistableBundle parameters) throws RemoteException {
            mExternalCb.onRangingStopFailed(sessionHandle, reason, parameters);
        }

        @Override
        public void onRangingClosed(SessionHandle sessionHandle, int reason,
                PersistableBundle parameters) throws RemoteException {
            mExternalCb.onRangingClosed(sessionHandle, reason, parameters);
            removeClientAndUnlinkToDeath();
        }

        @Override
        public void onRangingResult(SessionHandle sessionHandle, RangingReport rangingReport)
                throws RemoteException {
            // TODO: Perform permission checks and noteOp.
            mExternalCb.onRangingResult(sessionHandle, rangingReport);
        }

        @Override
        public void binderDied() {
            Log.i(TAG, "Client died: ending session: " + mSessionHandle);
            try {
                stopRanging(mSessionHandle);
                closeRanging(mSessionHandle);
            } catch (RemoteException execption) {
                Log.w(TAG, "Remote exception while handling client death");
                removeClientAndUnlinkToDeath();
            }
        }
    }

    private IUwbAdapter getVendorUwbAdapter() throws IllegalStateException {
        if (mVendorUwbAdapter != null) return mVendorUwbAdapter;
        mVendorUwbAdapter = mUwbInjector.getVendorService();
        if (mVendorUwbAdapter == null) {
            throw new IllegalStateException("No vendor service found!");
        }
        Log.i(TAG, "Retrieved vendor service");
        return mVendorUwbAdapter;
    }

    UwbServiceImpl(@NonNull Context context, @NonNull UwbInjector uwbInjector) {
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
        UwbRangingCallbacksWrapper wrapperCb =
                new UwbRangingCallbacksWrapper(sessionHandle, rangingCallbacks);
        synchronized (mCallbacksMap) {
            mCallbacksMap.put(sessionHandle, wrapperCb);
        }
        getVendorUwbAdapter().openRanging(sessionHandle, wrapperCb, parameters);
    }

    @Override
    public void startRanging(SessionHandle sessionHandle, PersistableBundle parameters)
            throws RemoteException {
        // TODO: Perform permission checks.
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
