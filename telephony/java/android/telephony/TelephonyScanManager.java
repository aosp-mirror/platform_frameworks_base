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

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import com.android.internal.telephony.ITelephony;

/**
 * Manages the radio access network scan requests and callbacks.
 */
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
    private final Messenger mMessenger;
    private SparseArray<NetworkScanInfo> mScanInfo = new SparseArray<NetworkScanInfo>();

    public TelephonyScanManager() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mLooper = thread.getLooper();
        mMessenger = new Messenger(new Handler(mLooper) {
            @Override
            public void handleMessage(Message message) {
                checkNotNull(message, "message cannot be null");
                NetworkScanInfo nsi;
                synchronized (mScanInfo) {
                    nsi = mScanInfo.get(message.arg2);
                }
                if (nsi == null) {
                    throw new RuntimeException(
                        "Failed to find NetworkScanInfo with id " + message.arg2);
                }
                NetworkScanCallback callback = nsi.mCallback;
                Executor executor = nsi.mExecutor;
                if (callback == null) {
                    throw new RuntimeException(
                        "Failed to find NetworkScanCallback with id " + message.arg2);
                }
                if (executor == null) {
                    throw new RuntimeException(
                        "Failed to find Executor with id " + message.arg2);
                }

                switch (message.what) {
                    case CALLBACK_SCAN_RESULTS:
                        try {
                            final Bundle b = message.getData();
                            final Parcelable[] parcelables = b.getParcelableArray(SCAN_RESULT_KEY);
                            CellInfo[] ci = new CellInfo[parcelables.length];
                            for (int i = 0; i < parcelables.length; i++) {
                                ci[i] = (CellInfo) parcelables[i];
                            }
                            executor.execute(() ->{
                                Rlog.d(TAG, "onResults: " + ci.toString());
                                callback.onResults((List<CellInfo>) Arrays.asList(ci));
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
                            mScanInfo.remove(message.arg2);
                        } catch (Exception e) {
                            Rlog.e(TAG, "Exception in networkscan callback onComplete", e);
                        }
                        break;
                    default:
                        Rlog.e(TAG, "Unhandled message " + Integer.toHexString(message.what));
                        break;
                }
            }
        });
    }

    /**
     * Request a network scan.
     *
     * This method is asynchronous, so the network scan results will be returned by callback.
     * The returned NetworkScan will contain a callback method which can be used to stop the scan.
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param request Contains all the RAT with bands/channels that need to be scanned.
     * @param callback Returns network scan results or errors.
     * @return A NetworkScan obj which contains a callback which can stop the scan.
     * @hide
     */
    public NetworkScan requestNetworkScan(int subId,
            NetworkScanRequest request, Executor executor, NetworkScanCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int scanId = telephony.requestNetworkScan(subId, request, mMessenger, new Binder());
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

    private void saveScanInfo(
            int id, NetworkScanRequest request, Executor executor, NetworkScanCallback callback) {
        synchronized (mScanInfo) {
            mScanInfo.put(id, new NetworkScanInfo(request, executor, callback));
        }
    }

    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(
            ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }
}
