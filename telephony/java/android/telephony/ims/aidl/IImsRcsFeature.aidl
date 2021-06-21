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
import android.telephony.ims.aidl.ICapabilityExchangeEventListener;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IOptionsResponseCallback;
import android.telephony.ims.aidl.IPublishResponseCallback;
import android.telephony.ims.aidl.ISubscribeResponseCallback;
import android.telephony.ims.feature.CapabilityChangeRequest;

import java.util.List;

/**
 * See RcsFeature for more information.
 * {@hide}
 */
interface IImsRcsFeature {
    // Not oneway because we need to verify this completes before doing anything else.
    int queryCapabilityStatus();
    // Inherited from ImsFeature
    int getFeatureState();
    oneway void addCapabilityCallback(IImsCapabilityCallback c);
    oneway void removeCapabilityCallback(IImsCapabilityCallback c);
    oneway void changeCapabilitiesConfiguration(in CapabilityChangeRequest r,
            IImsCapabilityCallback c);
    oneway void queryCapabilityConfiguration(int capability, int radioTech,
            IImsCapabilityCallback c);
    // RcsCapabilityExchangeImplBase specific api
    oneway void setCapabilityExchangeEventListener(ICapabilityExchangeEventListener listener);
    oneway void publishCapabilities(in String pidfXml, IPublishResponseCallback cb);
    oneway void subscribeForCapabilities(in List<Uri> uris, ISubscribeResponseCallback cb);
    oneway void sendOptionsCapabilityRequest(in Uri contactUri,
            in List<String> myCapabilities, IOptionsResponseCallback cb);
}
