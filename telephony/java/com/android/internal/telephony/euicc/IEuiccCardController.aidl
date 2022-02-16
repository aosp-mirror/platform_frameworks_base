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
 * limitations under the License.
 */

package com.android.internal.telephony.euicc;

import com.android.internal.telephony.euicc.IGetAllProfilesCallback;
import com.android.internal.telephony.euicc.IGetProfileCallback;
import com.android.internal.telephony.euicc.IDisableProfileCallback;
import com.android.internal.telephony.euicc.ISwitchToProfileCallback;
import com.android.internal.telephony.euicc.ISetNicknameCallback;
import com.android.internal.telephony.euicc.IDeleteProfileCallback;
import com.android.internal.telephony.euicc.IResetMemoryCallback;
import com.android.internal.telephony.euicc.IGetDefaultSmdpAddressCallback;
import com.android.internal.telephony.euicc.IGetSmdsAddressCallback;
import com.android.internal.telephony.euicc.ISetDefaultSmdpAddressCallback;
import com.android.internal.telephony.euicc.IAuthenticateServerCallback;
import com.android.internal.telephony.euicc.ICancelSessionCallback;
import com.android.internal.telephony.euicc.IGetEuiccChallengeCallback;
import com.android.internal.telephony.euicc.IGetEuiccInfo1Callback;
import com.android.internal.telephony.euicc.IGetEuiccInfo2Callback;
import com.android.internal.telephony.euicc.IGetRulesAuthTableCallback;
import com.android.internal.telephony.euicc.IListNotificationsCallback;
import com.android.internal.telephony.euicc.ILoadBoundProfilePackageCallback;
import com.android.internal.telephony.euicc.IPrepareDownloadCallback;
import com.android.internal.telephony.euicc.IRemoveNotificationFromListCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationListCallback;

/** @hide */
interface IEuiccCardController {
    oneway void getAllProfiles(String callingPackage, String cardId,
        in IGetAllProfilesCallback callback);
    oneway void getProfile(String callingPackage, String cardId, String iccid,
        in IGetProfileCallback callback);
    oneway void disableProfile(String callingPackage, String cardId, String iccid, boolean refresh,
        in IDisableProfileCallback callback);
    oneway void switchToProfile(String callingPackage, String cardId, String iccid, boolean refresh,
        in ISwitchToProfileCallback callback);
    oneway void setNickname(String callingPackage, String cardId, String iccid, String nickname,
        in ISetNicknameCallback callback);
    oneway void deleteProfile(String callingPackage, String cardId, String iccid,
        in IDeleteProfileCallback callback);
    oneway void resetMemory(String callingPackage, String cardId, int options, in IResetMemoryCallback callback);
    oneway void getDefaultSmdpAddress(String callingPackage, String cardId,
        in IGetDefaultSmdpAddressCallback callback);
    oneway void getSmdsAddress(String callingPackage, String cardId,
        in IGetSmdsAddressCallback callback);
    oneway void setDefaultSmdpAddress(String callingPackage, String cardId, String address,
        in ISetDefaultSmdpAddressCallback callback);
    oneway void getRulesAuthTable(String callingPackage, String cardId,
        in IGetRulesAuthTableCallback callback);
    oneway void getEuiccChallenge(String callingPackage, String cardId,
        in IGetEuiccChallengeCallback callback);
    oneway void getEuiccInfo1(String callingPackage, String cardId,
        in IGetEuiccInfo1Callback callback);
    oneway void getEuiccInfo2(String callingPackage, String cardId,
        in IGetEuiccInfo2Callback callback);
    oneway void authenticateServer(String callingPackage, String cardId, String matchingId,
        in byte[] serverSigned1, in byte[] serverSignature1, in byte[] euiccCiPkIdToBeUsed,
        in byte[] serverCertificatein, in IAuthenticateServerCallback callback);
    oneway void prepareDownload(String callingPackage, String cardId, in byte[] hashCc,
        in byte[] smdpSigned2, in byte[] smdpSignature2, in byte[] smdpCertificate,
        in IPrepareDownloadCallback callback);
    oneway void loadBoundProfilePackage(String callingPackage, String cardId,
        in byte[] boundProfilePackage, in ILoadBoundProfilePackageCallback callback);
    oneway void cancelSession(String callingPackage, String cardId, in byte[] transactionId,
        int reason, in ICancelSessionCallback callback);
    oneway void listNotifications(String callingPackage, String cardId, int events,
        in IListNotificationsCallback callback);
    oneway void retrieveNotificationList(String callingPackage, String cardId, int events,
        in IRetrieveNotificationListCallback callback);
    oneway void retrieveNotification(String callingPackage, String cardId, int seqNumber,
        in IRetrieveNotificationCallback callback);
    oneway void removeNotificationFromList(String callingPackage, String cardId, int seqNumber,
            in IRemoveNotificationFromListCallback callback);
}
