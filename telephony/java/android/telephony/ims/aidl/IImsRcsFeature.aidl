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

import android.net.Uri;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IRcsFeatureListener;
import android.telephony.ims.feature.CapabilityChangeRequest;

import java.util.List;

/**
 * See RcsFeature for more information.
 * {@hide}
 */
interface IImsRcsFeature {
    // Not oneway because we need to verify this completes before doing anything else.
    void setListener(IRcsFeatureListener listener);
    int queryCapabilityStatus();
    // Inherited from ImsFeature
    int getFeatureState();
    oneway void addCapabilityCallback(IImsCapabilityCallback c);
    oneway void removeCapabilityCallback(IImsCapabilityCallback c);
    oneway void changeCapabilitiesConfiguration(in CapabilityChangeRequest r,
            IImsCapabilityCallback c);
    oneway void queryCapabilityConfiguration(int capability, int radioTech,
            IImsCapabilityCallback c);
    // RcsPresenceExchangeImplBase specific api
    oneway void requestCapabilities(in List<Uri> uris, int operationToken);
    oneway void updateCapabilities(in RcsContactUceCapability capabilities, int operationToken);
    // RcsSipOptionsImplBase specific api
    oneway void sendCapabilityRequest(in Uri contactUri,
            in RcsContactUceCapability capabilities, int operationToken);
    oneway void respondToCapabilityRequest(in String contactUri,
            in RcsContactUceCapability ownCapabilities, int operationToken);
    oneway void respondToCapabilityRequestWithError(in Uri contactUri, int code, in String reason,
            int operationToken);
}