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
import android.util.Log;

import com.android.internal.telephony.ITelephony;

/**
 * Allows applications to request the system to perform a network scan.
 *
 * The caller of {@link #requestNetworkScan(NetworkScanRequest, NetworkScanCallback)} will
 * receive a NetworkScan which contains the callback method to stop the scan requested.
 * @hide
 */
public class NetworkScan {

    public static final String TAG = "NetworkScan";

    // Below errors are mapped from RadioError which is returned from RIL. We will consolidate
    // RadioErrors during the mapping if those RadioErrors mean no difference to the users.
    public static final int SUCCESS = 0;                    // RadioError:NONE
    public static final int ERROR_MODEM_ERROR = 1;          // RadioError:RADIO_NOT_AVAILABLE
                                                            // RadioError:NO_MEMORY
                                                            // RadioError:INTERNAL_ERR
                                                            // RadioError:MODEM_ERR
                                                            // RadioError:OPERATION_NOT_ALLOWED
    public static final int ERROR_INVALID_SCAN = 2;         // RadioError:INVALID_ARGUMENTS
    public static final int ERROR_MODEM_BUSY = 3;           // RadioError:DEVICE_IN_USE
    public static final int ERROR_UNSUPPORTED = 4;          // RadioError:REQUEST_NOT_SUPPORTED

    // Below errors are generated at the Telephony.
    public static final int ERROR_RIL_ERROR = 10000;        // Nothing or only exception is
                                                            // returned from RIL.
    public static final int ERROR_INVALID_SCANID = 10001;   // The scanId is invalid. The user is
                                                            // either trying to stop a scan which
                                                            // does not exist or started by others.
    public static final int ERROR_INTERRUPTED = 10002;      // Scan was interrupted by another scan
                                                            // with higher priority.
    private final int mScanId;
    private final int mSubId;

    /**
     * Stops the network scan
     *
     * This is the callback method to stop an ongoing scan. When user requests a new scan,
     * a NetworkScan object will be returned, and the user can stop the scan by calling this
     * method.
     */
    public void stop() throws RemoteException {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.stopNetworkScan(mSubId, mScanId);
            } else {
                throw new RemoteException("Failed to get the ITelephony instance.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "stopNetworkScan  RemoteException", ex);
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
