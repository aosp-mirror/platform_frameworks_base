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

package com.android.ims.internal;

import android.app.PendingIntent;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;

import android.os.Message;

/**
 * See ImsService and IMMTelFeature for more information.
 * {@hide}
 */
interface IImsServiceController {
    // ImsService Control
    void createImsFeature(int slotId, int feature, IImsFeatureStatusCallback c);
    void removeImsFeature(int slotId, int feature, IImsFeatureStatusCallback c);
    // MMTel Feature
    int startSession(int slotId, int featureType, in PendingIntent incomingCallIntent,
            in IImsRegistrationListener listener);
    void endSession(int slotId, int featureType, int sessionId);
    boolean isConnected(int slotId, int featureType, int callSessionType, int callType);
    boolean isOpened(int slotId, int featureType);
    int getFeatureStatus(int slotId, int featureType);
    void addRegistrationListener(int slotId, int featureType, in IImsRegistrationListener listener);
    void removeRegistrationListener(int slotId, int featureType,
            in IImsRegistrationListener listener);
    ImsCallProfile createCallProfile(int slotId, int featureType, int sessionId,
            int callSessionType, int callType);
    IImsCallSession createCallSession(int slotId, int featureType, int sessionId,
            in ImsCallProfile profile, IImsCallSessionListener listener);
    IImsCallSession getPendingCallSession(int slotId, int featureType, int sessionId,
            String callId);
    IImsUt getUtInterface(int slotId, int featureType);
    IImsConfig getConfigInterface(int slotId, int featureType);
    void turnOnIms(int slotId, int featureType);
    void turnOffIms(int slotId, int featureType);
    IImsEcbm getEcbmInterface(int slotId, int featureType);
    void setUiTTYMode(int slotId, int featureType, int uiTtyMode, in Message onComplete);
    IImsMultiEndpoint getMultiEndpointInterface(int slotId, int featureType);
}
