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

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.annotation.IntDef;
import android.util.Log;

import com.android.internal.telephony.ITelephony;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The caller of
 * {@link TelephonyManager#requestNetworkScan(NetworkScanRequest, Executor, NetworkScanCallback)}
 * will receive an instance of {@link NetworkScan}, which contains a callback method
 * {@link #stopScan()} for stopping the in-progress scan.
 */
public class NetworkScan {

    private static final String TAG = "NetworkScan";

    // Below errors are mapped from RadioError which is returned from RIL. We will consolidate
    // RadioErrors during the mapping if those RadioErrors mean no difference to the users.

    /**
     * Defines acceptable values of scan error code.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR_MODEM_ERROR, ERROR_INVALID_SCAN, ERROR_MODEM_UNAVAILABLE, ERROR_UNSUPPORTED,
            ERROR_RADIO_INTERFACE_ERROR, ERROR_INVALID_SCANID, ERROR_INTERRUPTED})
    public @interface ScanErrorCode {}

    /**
     * The RIL has successfully performed the network scan.
     */
    public static final int SUCCESS = 0;                    // RadioError:NONE

    /**
     * The scan has failed due to some modem errors.
     */
    public static final int ERROR_MODEM_ERROR = 1;          // RadioError:RADIO_NOT_AVAILABLE
                                                            // RadioError:NO_MEMORY
                                                            // RadioError:INTERNAL_ERR
                                                            // RadioError:MODEM_ERR
                                                            // RadioError:OPERATION_NOT_ALLOWED

    /**
     * The parameters of the scan is invalid.
     */
    public static final int ERROR_INVALID_SCAN = 2;         // RadioError:INVALID_ARGUMENTS

    /**
     * The modem can not perform the scan because it is doing something else.
     */
    public static final int ERROR_MODEM_UNAVAILABLE = 3;    // RadioError:DEVICE_IN_USE

    /**
     * The modem does not support the request scan.
     */
    public static final int ERROR_UNSUPPORTED = 4;          // RadioError:REQUEST_NOT_SUPPORTED


    // Below errors are generated at the Telephony.

    /**
     * The RIL returns nothing or exceptions.
     */
    public static final int ERROR_RADIO_INTERFACE_ERROR = 10000;

    /**
     * The scan ID is invalid. The user is either trying to stop a scan which does not exist
     * or started by others.
     */
    public static final int ERROR_INVALID_SCANID = 10001;

    /**
     * The scan has been interrupted by another scan with higher priority.
     */
    public static final int ERROR_INTERRUPTED = 10002;

    private final int mScanId;
    private final int mSubId;

    /**
     * Stops the network scan
     *
     * Use this method to stop an ongoing scan. When user requests a new scan, a {@link NetworkScan}
     * object will be returned, and the user can stop the scan by calling this method.
     */
    public void stopScan() {
        ITelephony telephony = getITelephony();
        if (telephony == null) {
            Rlog.e(TAG, "Failed to get the ITelephony instance.");
        }
        try {
            telephony.stopNetworkScan(mSubId, mScanId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "stopNetworkScan  RemoteException", ex);
        } catch (RuntimeException ex) {
            Rlog.e(TAG, "stopNetworkScan  RuntimeException", ex);
        }
    }

    /**
     * @deprecated Use {@link #stopScan()}
     * @removed
     */
    @Deprecated
    public void stop() throws RemoteException {
        try {
            stopScan();
        } catch (RuntimeException ex) {
            throw new RemoteException("Failed to stop the network scan with id " + mScanId);
        }
    }

    /**
     * Creates a new NetworkScan with scanId
     *
     * @param scanId The id of the scan
     * @param subId the id of the subscription
     * @hide
     */
    public NetworkScan(int scanId, int subId) {
        mScanId = scanId;
        mSubId = subId;
    }

    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(
            ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }
}
