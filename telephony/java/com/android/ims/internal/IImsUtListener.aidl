/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims.internal;

import android.os.Bundle;

import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.ImsSsInfo;
import com.android.ims.internal.IImsUt;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsData;

/**
 * {@hide}
 */
oneway interface IImsUtListener {
    /**
     * Notifies the result of the supplementary service configuration udpate.
     */
    @UnsupportedAppUsage
    void utConfigurationUpdated(in IImsUt ut, int id);
    @UnsupportedAppUsage
    void utConfigurationUpdateFailed(in IImsUt ut, int id, in ImsReasonInfo error);

    /**
     * Notifies the result of the supplementary service configuration query.
     */
    @UnsupportedAppUsage
    void utConfigurationQueried(in IImsUt ut, int id, in Bundle ssInfo);
    @UnsupportedAppUsage
    void utConfigurationQueryFailed(in IImsUt ut, int id, in ImsReasonInfo error);

    /**
     * Notifies the status of the call barring supplementary service.
     */
    @UnsupportedAppUsage
    void utConfigurationCallBarringQueried(in IImsUt ut,
            int id, in ImsSsInfo[] cbInfo);

    /**
     * Notifies the status of the call forwarding supplementary service.
     */
    @UnsupportedAppUsage
    void utConfigurationCallForwardQueried(in IImsUt ut,
            int id, in ImsCallForwardInfo[] cfInfo);

    /**
     * Notifies the status of the call waiting supplementary service.
     */
    @UnsupportedAppUsage
    void utConfigurationCallWaitingQueried(in IImsUt ut,
            int id, in ImsSsInfo[] cwInfo);

    /**
     * Notifies client when Supplementary Service indication is received
     *
     * @param ssData Details of SS request and response information
     */
    void onSupplementaryServiceIndication(in ImsSsData ssData);
}
