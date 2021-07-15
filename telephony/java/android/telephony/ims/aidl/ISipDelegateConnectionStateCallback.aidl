/*
 * Copyright (c) 2020 The Android Open Source Project
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

package android.telephony.ims.aidl;

import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.aidl.ISipDelegate;

/**
 * See {@link SipDelegateConnectionStateCallback} for docs regarding this callback.
 * {@hide}
 */
oneway interface ISipDelegateConnectionStateCallback {
    void onCreated(ISipDelegate c);
    void onFeatureTagStatusChanged(in DelegateRegistrationState registrationState,
                in List<FeatureTagState> deniedFeatureTags);
    void onImsConfigurationChanged(in SipDelegateImsConfiguration registeredSipConfig);
    void onConfigurationChanged(in SipDelegateConfiguration registeredSipConfig);
    void onDestroyed(int reason);
}
