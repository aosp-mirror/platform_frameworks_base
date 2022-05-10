/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.telephony.ims.aidl.IOptionsRequestCallback;

import java.util.List;

/**
 * Listener interface for the ImsService to use to notify the framework of UCE
 * events.
 *
 * See CapabilityExchangeEventListener for more information.
 * {@hide}
 */
oneway interface ICapabilityExchangeEventListener {
    void onRequestPublishCapabilities(int publishTriggerType);
    void onUnpublish();
    void onPublishUpdated(int reasonCode, String reasonPhrase, int reasonHeaderCause,
            String reasonHeaderText);
    void onRemoteCapabilityRequest(in Uri contactUri,
            in List<String> remoteCapabilities, IOptionsRequestCallback cb);
}
