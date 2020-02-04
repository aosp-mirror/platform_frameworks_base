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

package android.service.euicc;

import android.service.euicc.IDeleteSubscriptionCallback;
import android.service.euicc.IDownloadSubscriptionCallback;
import android.service.euicc.IEraseSubscriptionsCallback;
import android.service.euicc.IGetDefaultDownloadableSubscriptionListCallback;
import android.service.euicc.IGetDownloadableSubscriptionMetadataCallback;
import android.service.euicc.IGetEidCallback;
import android.service.euicc.IGetEuiccInfoCallback;
import android.service.euicc.IGetEuiccProfileInfoListCallback;
import android.service.euicc.IGetOtaStatusCallback;
import android.service.euicc.IOtaStatusChangedCallback;
import android.service.euicc.IRetainSubscriptionsForFactoryResetCallback;
import android.service.euicc.ISwitchToSubscriptionCallback;
import android.service.euicc.IUpdateSubscriptionNicknameCallback;
import android.service.euicc.IEuiccServiceDumpResultCallback;
import android.telephony.euicc.DownloadableSubscription;
import android.os.Bundle;

/** @hide */
oneway interface IEuiccService {
    void downloadSubscription(int slotId, in DownloadableSubscription subscription,
            boolean switchAfterDownload, boolean forceDeactivateSim, in Bundle resolvedBundle,
            in IDownloadSubscriptionCallback callback);
    void getDownloadableSubscriptionMetadata(int slotId, in DownloadableSubscription subscription,
            boolean forceDeactivateSim, in IGetDownloadableSubscriptionMetadataCallback callback);
    void getEid(int slotId, in IGetEidCallback callback);
    void getOtaStatus(int slotId, in IGetOtaStatusCallback callback);
    void startOtaIfNecessary(int slotId, in IOtaStatusChangedCallback statusChangedCallback);
    void getEuiccProfileInfoList(int slotId, in IGetEuiccProfileInfoListCallback callback);
    void getDefaultDownloadableSubscriptionList(int slotId, boolean forceDeactivateSim,
            in IGetDefaultDownloadableSubscriptionListCallback callback);
    void getEuiccInfo(int slotId, in IGetEuiccInfoCallback callback);
    void deleteSubscription(int slotId, String iccid, in IDeleteSubscriptionCallback callback);
    void switchToSubscription(int slotId, String iccid, boolean forceDeactivateSim,
            in ISwitchToSubscriptionCallback callback);
    void updateSubscriptionNickname(int slotId, String iccid, String nickname,
            in IUpdateSubscriptionNicknameCallback callback);
    void eraseSubscriptions(int slotId, in IEraseSubscriptionsCallback callback);
    void eraseSubscriptionsWithOptions(
            int slotIndex, int options, in IEraseSubscriptionsCallback callback);
    void retainSubscriptionsForFactoryReset(
            int slotId, in IRetainSubscriptionsForFactoryResetCallback callback);
    void dump(in IEuiccServiceDumpResultCallback callback);
}