/*
 * Copyright (c) 2017 The Android Open Source Project
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

import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsServiceControllerListener;
import android.telephony.ims.stub.ImsFeatureConfiguration;

import com.android.ims.internal.IImsFeatureStatusCallback;

/**
 * See ImsService and MmTelFeature for more information.
 * {@hide}
 */
interface IImsServiceController {
    void setListener(IImsServiceControllerListener l);
    IImsMmTelFeature createMmTelFeature(int slotId, in IImsFeatureStatusCallback c);
    IImsRcsFeature createRcsFeature(int slotId, in IImsFeatureStatusCallback c);
    ImsFeatureConfiguration querySupportedImsFeatures();
    // Synchronous call to ensure the ImsService is ready before continuing with feature creation.
    void notifyImsServiceReadyForFeatureCreation();
    void removeImsFeature(int slotId, int featureType, in IImsFeatureStatusCallback c);
    IImsConfig getConfig(int slotId);
    IImsRegistration getRegistration(int slotId);
    oneway void enableIms(int slotId);
    oneway void disableIms(int slotId);
}
