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

package com.android.internal.telephony.euicc;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;

/** @hide */
interface IEuiccController {
    oneway void continueOperation(in Intent resolutionIntent, in Bundle resolutionExtras);
    oneway void getDownloadableSubscriptionMetadata(in DownloadableSubscription subscription,
        String callingPackage, in PendingIntent callbackIntent);
    oneway void getDefaultDownloadableSubscriptionList(
        String callingPackage, in PendingIntent callbackIntent);
    String getEid();
    int getOtaStatus();
    oneway void downloadSubscription(in DownloadableSubscription subscription,
        boolean switchAfterDownload, String callingPackage, in PendingIntent callbackIntent);
    EuiccInfo getEuiccInfo();
    oneway void deleteSubscription(int subscriptionId, String callingPackage,
        in PendingIntent callbackIntent);
    oneway void switchToSubscription(int subscriptionId, String callingPackage,
        in PendingIntent callbackIntent);
    oneway void updateSubscriptionNickname(int subscriptionId, String nickname,
        in PendingIntent callbackIntent);
    oneway void eraseSubscriptions(in PendingIntent callbackIntent);
    oneway void retainSubscriptionsForFactoryReset(in PendingIntent callbackIntent);
}