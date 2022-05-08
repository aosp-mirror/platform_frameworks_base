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

import java.util.List;

/** @hide */
interface IEuiccController {
    oneway void continueOperation(int cardId, in Intent resolutionIntent,
            in Bundle resolutionExtras);
    oneway void getDownloadableSubscriptionMetadata(int cardId,
            in DownloadableSubscription subscription,
        String callingPackage, in PendingIntent callbackIntent);
    oneway void getDefaultDownloadableSubscriptionList(int cardId,
        String callingPackage, in PendingIntent callbackIntent);
    String getEid(int cardId, String callingPackage);
    int getOtaStatus(int cardId);
    oneway void downloadSubscription(int cardId, in DownloadableSubscription subscription,
        boolean switchAfterDownload, String callingPackage, in Bundle resolvedBundle,
        in PendingIntent callbackIntent);
    EuiccInfo getEuiccInfo(int cardId);
    oneway void deleteSubscription(int cardId, int subscriptionId, String callingPackage,
        in PendingIntent callbackIntent);
    oneway void switchToSubscription(int cardId, int subscriptionId, String callingPackage,
        in PendingIntent callbackIntent);
    oneway void switchToSubscriptionWithPort(int cardId, int subscriptionId, int portIndex,
        String callingPackage, in PendingIntent callbackIntent);
    oneway void updateSubscriptionNickname(int cardId, int subscriptionId, String nickname,
        String callingPackage, in PendingIntent callbackIntent);
    oneway void eraseSubscriptions(int cardId, in PendingIntent callbackIntent);
    oneway void eraseSubscriptionsWithOptions(
        int cardId, int options, in PendingIntent callbackIntent);
    oneway void retainSubscriptionsForFactoryReset(int cardId, in PendingIntent callbackIntent);
    void setSupportedCountries(boolean isSupported, in List<String> countriesList);
    List<String> getSupportedCountries(boolean isSupported);
    boolean isSupportedCountry(String countryIso);
    boolean isSimPortAvailable(int cardId, int portIndex, String callingPackage);
}
