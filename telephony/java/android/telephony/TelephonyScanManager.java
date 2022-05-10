/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telephony;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.ITelephony;
import com.android.telephony.Rlog;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages the radio access network scan requests and callbacks.
 */
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)
public final class TelephonyScanManager {

    private static final String TAG = "TelephonyScanManager";

    /** @hide */
    public static final String SCAN_RESULT_KEY = "scanResult";

    /** @hide */
    public static final int CALLBACK_SCAN_RESULTS = 1;
    /** @hide */
    public static final int CALLBACK_SCAN_ERROR = 2;
    /** @hide */
    public static final int CALLBACK_SCAN_COMPLETE = 3;
    /** @hide */
    public static final int CALLBACK_RESTRICTED_SCAN_RESULTS = 4;
    /** @hide */
    public static final int CALLBACK_TELEPHONY_DIED = 5;

    /** @hide */
    public static final int INVALID_SCAN_ID = -1;

    /**
     * The caller of
     * {@link
     * TelephonyManager#requestNetworkScan(NetworkScanRequest, Executor, NetworkScanCallback)}
     * should implement and provide this callback so that the scan results or errors can be
     * returned.
     */
    public static abstract class NetworkScanCallback {
        /** Returns the scan results to the user, this callback will be called multiple times. */
        public void onResults(List<CellInfo> results) {}

        /**
         * Informs the user that the scan has stopped.
         *
         * This callback will be called when the scan is finished or cancelled by the user.
         * The related NetworkScanRequest will be deleted after this callback.
         */
        public void onComplete() {}

        /**
         * Informs the user that there is some error about the scan.
         *
         * This callback will be called whenever there is any error about the scan, and the scan
         * will be terminated. onComplete() will NOT be called.
         *
         * @param error Error code when the scan is failed, as defined in {@link NetworkScan}.
         */
        public void onError(@NetworkScan.ScanErrorCode int error) {}
    }

    private static class NetworkScanInfo {
        private final NetworkScanRequest mRequest;
        private final Executor mExecutor;
        private final NetworkScanCallback mCallback;

        NetworkScanInfo(
                NetworkScanRequest request, Executor executor, NetworkScanCallback callback) {
            mRequest = request;
            mExecutor = executor;
            mCallback = callback;
        }
    }

    private final Looper mLooper;
    private final Handler mHandler;
    private final Messenger mMessenger;
    private final SparseArray<NetworkScanInfo> mScanInfo = new SparseArray<NetworkScanInfo>();
    private final Binder.DeathRecipient mDeathRecipient;

    public TelephonyScanManager() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mLooper = thread.getLooper();
        mHandler = new Handler(mLooper) {
            @Override
            public void handleMessage(Message message) {
                checkNotNull(message, "message cannot be null");
                if (message.what == CALLBACK_TELEPHONY_DIED) {
                    // If there are no objects in mScanInfo then binder death will simply return.
                    synchronized (mScanInfo) {
                        for (int i = 0; i < mScanInfo.size(); i++) {
                            NetworkScanInfo nsi = mScanInfo.valueAt(i);
                            // At this point we go into panic mode and ignore errors that would
                            // normally stop the show in order to try and clean up as gracefully
                            // as possible.
                            if (nsi == null) continue; // shouldn't be possible
                            Executor e = nsi.mExecutor;
                            NetworkScanCallback cb = nsi.mCallback;
                            if (e == null || cb == null) continue;
                            try {
                                e.execute(
                                        () -> cb.onError(NetworkScan.ERROR_MODEM_UNAVAILABLE));
                            } catch (java.util.concurrent.RejectedExecutionException ignore) {
                                // ignore so that we can continue
                            }
                        }

                        mScanInfo.clear();
                    }
                    return;
                }

                NetworkScanInfo nsi;
                synchronized (mScanInfo) {
                    nsi = mScanInfo.get(message.arg2);
                }
                if (nsi == null) {
                    throw new RuntimeException(
                        "Failed to find NetworkScanInfo with id " + message.arg2);
                }

                final NetworkScanCallback callback = nsi.mCallback;
                final Executor executor = nsi.mExecutor;

                switch (message.what) {
                    case CALLBACK_RESTRICTED_SCAN_RESULTS:
                    case CALLBACK_SCAN_RESULTS:
                        try {
                            final Bundle b = message.getData();
                            final Parcelable[] parcelables = b.getParcelableArray(SCAN_RESULT_KEY);
                            CellInfo[] ci = new CellInfo[parcelables.length];
                            for (int i = 0; i < parcelables.length; i++) {
                                ci[i] = (CellInfo) parcelables[i];
                            }
                            executor.execute(() -> {
                                Rlog.d(TAG, "onResults: " + ci.toString());
                                callback.onResults(Arrays.asList(ci));
                            });
                        } catch (Exception e) {
                            Rlog.e(TAG, "Exception in networkscan callback onResults", e);
                        }
                        break;
                    case CALLBACK_SCAN_ERROR:
                        try {
                            final int errorCode = message.arg1;
                            executor.execute(() -> {
                                Rlog.d(TAG, "onError: " + errorCode);
                                callback.onError(errorCode);
                            });
                            synchronized (mScanInfo) {
                                mScanInfo.remove(message.arg2);
                            }
                        } catch (Exception e) {
                            Rlog.e(TAG, "Exception in networkscan callback onError", e);
                        }
                        break;
                    case CALLBACK_SCAN_COMPLETE:
                        try {
                            executor.execute(() -> {
                                Rlog.d(TAG, "onComplete");
                                callback.onComplete();
                            });
                            synchronized (mScanInfo) {
                                mScanInfo.remove(message.arg2);
                            }
                        } catch (Exception e) {
                            Rlog.e(TAG, "Exception in networkscan callback onComplete", e);
                        }
                        break;
                    default:
                        Rlog.e(TAG, "Unhandled message " + Integer.toHexString(message.what));
                        break;
                }
            }
        };
        mMessenger = new Messenger(mHandler);
        mDeathRecipient = new Binder.DeathRecipient() {
            @Override
            public void binderDied() {
                mHandler.obtainMessage(CALLBACK_TELEPHONY_DIED).sendToTarget();
            }
        };
    }

    /**
     * Request a network scan.
     *
     * This method is asynchronous, so the network scan results will be returned by callback.
     * The returned NetworkScan will contain a callback method which can be used to stop the scan.
     *
     * <p>
     * Requires Permission:
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} and
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     * @param renounceFineLocationAccess Set this to true if the caller would not like to receive
     * location related information which will be sent if the caller already possess
     * {@link android.Manifest.permission.ACCESS_FINE_LOCATION} and do not renounce the permission
     * @param request Contains all the RAT with bands/channels that need to be scanned.
     * @param callback Returns network scan results or errors.
     * @param callingPackage The package name of the caller
     * @param callingFeatureId The feature id inside of the calling package
     * @return A NetworkScan obj which contains a callback which can stop the scan.
     * @hide
     */
    public NetworkScan requestNetworkScan(int subId,
            boolean renounceFineLocationAccess,
            NetworkScanRequest request, Executor executor, NetworkScanCallback callback,
            String callingPackage, @Nullable String callingFeatureId) {
        try {
            Objects.requireNonNull(request, "Request was null");
            Objects.requireNonNull(callback, "Callback was null");
            Objects.requireNonNull(executor, "Executor was null");
            final ITelephony telephony = getITelephony();
            if (telephony == null) return null;

            // The lock must be taken before calling requestNetworkScan because the resulting
            // scanId can be invoked asynchronously on another thread at any time after
            // requestNetworkScan invoked, leaving a critical section between that call and adding
            // the record to the ScanInfo cache.
            synchronized (mScanInfo) {
                int scanId = telephony.requestNetworkScan(
                        subId, renounceFineLocationAccess, request, mMessenger,
                        new Binder(), callingPackage,
                        callingFeatureId);
                if (scanId == INVALID_SCAN_ID) {
                    Rlog.e(TAG, "Failed to initiate network scan");
                    return null;
                }
                // We link to death whenever a scan is started to ensure that we are linked
                // at the point that phone process death might matter.
                // We never unlink because:
                // - Duplicate links to death with the same callback do not result in
                //   extraneous callbacks (the tracking de-dupes).
                // - Receiving binderDeath() when no scans are active is a no-op.
                telephony.asBinder().linkToDeath(mDeathRecipient, 0);
                saveScanInfo(scanId, request, executor, callback);
                return new NetworkScan(scanId, subId);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "requestNetworkScan RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "requestNetworkScan NPE", ex);
        }
        return null;
    }

    @GuardedBy("mScanInfo")
    private void saveScanInfo(
            int id, NetworkScanRequest request, Executor executor, NetworkScanCallback callback) {
        mScanInfo.put(id, new NetworkScanInfo(request, executor, callback));
    }

    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(
            TelephonyFrameworkInitializer
                    .getTelephonyServiceManager()
                    .getTelephonyServiceRegisterer()
                    .get());
    }
}
