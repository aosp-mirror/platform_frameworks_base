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

import android.service.euicc.IDownloadSubscriptionCallback;
import android.service.euicc.IGetDownloadableSubscriptionMetadataCallback;
import android.service.euicc.IGetEidCallback;
import android.telephony.euicc.DownloadableSubscription;

/** @hide */
oneway interface IEuiccService {
    void downloadSubscription(int slotId, in DownloadableSubscription subscription,
            boolean switchAfterDownload, in IDownloadSubscriptionCallback callback);
    void getDownloadableSubscriptionMetadata(int slotId, in DownloadableSubscription subscription,
            in IGetDownloadableSubscriptionMetadataCallback callback);
    void getEid(int slotId, in IGetEidCallback callback);
}