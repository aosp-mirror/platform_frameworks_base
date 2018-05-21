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

import android.telephony.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;

import android.os.Message;

/**
 * See MMTelFeature for more information.
 * {@hide}
 */
interface IImsMMTelFeature {
    int startSession(in PendingIntent incomingCallIntent,
            in IImsRegistrationListener listener);
    void endSession(int sessionId);
    boolean isConnected(int callSessionType, int callType);
    boolean isOpened();
    int getFeatureStatus();
    void addRegistrationListener(in IImsRegistrationListener listener);
    void removeRegistrationListener(in IImsRegistrationListener listener);
    ImsCallProfile createCallProfile(int sessionId, int callSessionType, int callType);
    IImsCallSession createCallSession(int sessionId, in ImsCallProfile profile);
    IImsCallSession getPendingCallSession(int sessionId, String callId);
    IImsUt getUtInterface();
    IImsConfig getConfigInterface();
    void turnOnIms();
    void turnOffIms();
    IImsEcbm getEcbmInterface();
    void setUiTTYMode(int uiTtyMode, in Message onComplete);
    IImsMultiEndpoint getMultiEndpointInterface();
}
