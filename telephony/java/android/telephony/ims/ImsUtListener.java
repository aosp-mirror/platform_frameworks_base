/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims;

import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.ims.internal.IImsUtListener;

/**
 * Base implementation of the IMS UT listener interface, which implements stubs.
 * Override these methods to implement functionality.
 * @hide
 */
// DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
// will break other implementations of ImsUt maintained by other ImsServices.
@SystemApi
public class ImsUtListener {
    private IImsUtListener mServiceInterface;
    private static final String LOG_TAG = "ImsUtListener";

    public void onUtConfigurationUpdated(int id) {
        try {
            mServiceInterface.utConfigurationUpdated(null, id);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationUpdated: remote exception");
        }
    }

    public void onUtConfigurationUpdateFailed(int id, ImsReasonInfo error) {
        try {
            mServiceInterface.utConfigurationUpdateFailed(null, id, error);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationUpdateFailed: remote exception");
        }
    }

    public void onUtConfigurationQueried(int id, Bundle ssInfo) {
        try {
            mServiceInterface.utConfigurationQueried(null, id, ssInfo);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationQueried: remote exception");
        }
    }

    public void onUtConfigurationQueryFailed(int id, ImsReasonInfo error) {
        try {
            mServiceInterface.utConfigurationQueryFailed(null, id, error);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationQueryFailed: remote exception");
        }
    }

    public void onUtConfigurationCallBarringQueried(int id, ImsSsInfo[] cbInfo) {
        try {
            mServiceInterface.utConfigurationCallBarringQueried(null, id, cbInfo);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationCallBarringQueried: remote exception");
        }
    }

    public void onUtConfigurationCallForwardQueried(int id, ImsCallForwardInfo[] cfInfo) {
        try {
            mServiceInterface.utConfigurationCallForwardQueried(null, id, cfInfo);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationCallForwardQueried: remote exception");
        }
    }

    public void onUtConfigurationCallWaitingQueried(int id, ImsSsInfo[] cwInfo) {
        try {
            mServiceInterface.utConfigurationCallWaitingQueried(null, id, cwInfo);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationCallWaitingQueried: remote exception");
        }
    }

    public void onSupplementaryServiceIndication(ImsSsData ssData) {
        try {
            mServiceInterface.onSupplementaryServiceIndication(ssData);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "onSupplementaryServiceIndication: remote exception");
        }
    }

    /**
     * @hide
     */
    public ImsUtListener(IImsUtListener serviceInterface) {
        mServiceInterface = serviceInterface;
    }
}
