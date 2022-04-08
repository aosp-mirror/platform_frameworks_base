/*
 * Copyright (c) 2018 The Android Open Source Project
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

import java.util.List;

/**
 * Listener interface for updates from the RcsFeature back to the framework.
 * {@hide}
 */
interface IRcsFeatureListener {
    //RcsCapabilityExchange specific
    oneway void onCommandUpdate(int commandCode, int operationToken);
    // RcsPresenceExchangeImplBase Specific
    oneway void onNetworkResponse(int code, in String reason, int operationToken);
    oneway void onCapabilityRequestResponsePresence(in List<RcsContactUceCapability> infos,
    int operationToken);
    oneway void onNotifyUpdateCapabilities(int publishTriggerType);
    oneway void onUnpublish();
    // RcsSipOptionsImplBase specific
    oneway void onCapabilityRequestResponseOptions(int code, in String reason,
            in RcsContactUceCapability info, int operationToken);
    oneway void onRemoteCapabilityRequest(in Uri contactUri, in RcsContactUceCapability remoteInfo,
            int operationToken);
}
