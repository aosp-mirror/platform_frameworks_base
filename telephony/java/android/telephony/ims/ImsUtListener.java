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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.Log;

import com.android.ims.internal.IImsUtListener;

/**
 * Listener interface used to receive network responses back from UT supplementary service queries
 * made by the framework.
 * @hide
 */
// DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
// will break other implementations of ImsUt maintained by other ImsServices.
@SystemApi
public class ImsUtListener {

    /**
     * The {@link Bundle} key for a Calling Line Identification Restriction (CLIR) response. The
     * value will be an int[] with two values:
     * int[0] contains the 'n' parameter from TS 27.007 7.7, which is the
     * outgoing CLIR state. See {@link ImsSsInfo#CLIR_OUTGOING_DEFAULT},
     * {@link ImsSsInfo#CLIR_OUTGOING_INVOCATION}, and {@link ImsSsInfo#CLIR_OUTGOING_SUPPRESSION};
     * int[1] contains the 'm' parameter from TS 27.007 7.7, which is the CLIR interrogation status.
     * See {@link ImsSsInfo#CLIR_STATUS_NOT_PROVISIONED},
     * {@link ImsSsInfo#CLIR_STATUS_PROVISIONED_PERMANENT}, {@link ImsSsInfo#CLIR_STATUS_UNKNOWN},
     * {@link ImsSsInfo#CLIR_STATUS_TEMPORARILY_RESTRICTED}, and
     * {@link ImsSsInfo#CLIR_STATUS_TEMPORARILY_ALLOWED}.
     * @deprecated Use {@link #onLineIdentificationSupplementaryServiceResponse(int, ImsSsInfo)}
     * instead, this key has been added for backwards compatibility with older proprietary
     * implementations only and is being phased out.
     */
    @Deprecated
    public static final String BUNDLE_KEY_CLIR = "queryClir";

    /**
     * The {@link Bundle} key for a Calling Line Identification Presentation (CLIP), Connected Line
     * Identification Presentation (COLP), or Connected Line Identification Restriction (COLR)
     * response. The value will be an instance of {@link ImsSsInfo}, which contains the response to
     * the query.
     * @deprecated Use {@link #onLineIdentificationSupplementaryServiceResponse(int, ImsSsInfo)}
     * instead, this key has been added for backwards compatibility with older proprietary
     * implementations only and is being phased out.
     */
    @Deprecated
    public static final String BUNDLE_KEY_SSINFO = "imsSsInfo";

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

    /**
     * Notify the framework of a UT configuration response to a {@link ImsUtImplBase#queryClir()},
     * {@link ImsUtImplBase#queryClip()}, {@link ImsUtImplBase#queryColp()}, or
     * {@link ImsUtImplBase#queryColr()} query for the transaction ID specified. If the query fails,
     * {@link #onUtConfigurationQueryFailed(int, ImsReasonInfo)} should be called.
     * @param id The ID associated with this UT configuration transaction from the framework.
     * @param configuration A {@link Bundle} containing the result of querying the UT configuration.
     *                      Must contain {@link #BUNDLE_KEY_CLIR} if it is a response to
     *                      {@link ImsUtImplBase#queryClir()} or
     *                      {@link #BUNDLE_KEY_SSINFO} if it is a response to
     *                      {@link ImsUtImplBase#queryClip()}, {@link ImsUtImplBase#queryColp()}, or
     *                      {@link ImsUtImplBase#queryColr()}.
     * @deprecated Use {@link #onLineIdentificationSupplementaryServiceResponse(int, ImsSsInfo)}
     * instead.
     */
    @Deprecated
    public void onUtConfigurationQueried(int id, Bundle configuration) {
        try {
            mServiceInterface.utConfigurationQueried(null, id, configuration);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "utConfigurationQueried: remote exception");
        }
    }

    /**
     * Notify the framework of a UT configuration response to a {@link ImsUtImplBase#queryClir()},
     * {@link ImsUtImplBase#queryClip()}, {@link ImsUtImplBase#queryColp()}, or
     * {@link ImsUtImplBase#queryColr()} query for the transaction ID specified. If the query fails,
     * the framework should be notified via
     * {@link #onUtConfigurationQueryFailed(int, ImsReasonInfo)}.
     * @param id The ID associated with this UT configuration transaction from the framework.
     * @param configuration An {@link ImsSsInfo} instance containing the configuration for the
     *                      line identification supplementary service queried.
     */
    public void onLineIdentificationSupplementaryServiceResponse(int id,
            @NonNull ImsSsInfo configuration) {
        try {
            mServiceInterface.lineIdentificationSupplementaryServiceResponse(id, configuration);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the Framework of the line identification query failure.
     * @param id The ID associated with the UT query transaction.
     * @param error The query failure reason.
     */
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

    /**
     * @hide
     */
    public IImsUtListener getListenerInterface() {
        return mServiceInterface;
    }
}
