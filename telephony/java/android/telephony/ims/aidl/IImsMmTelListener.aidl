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

import android.os.Bundle;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.MediaQualityStatus;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.telephony.ims.aidl.IImsTrafficSessionCallback;

import com.android.ims.internal.IImsCallSession;

/**
 * See MmTelFeature#Listener for more information.
 * {@hide}
 */
 // This interface is not considered oneway because we need to ensure that these operations are
 // processed by telephony before the control flow returns to the ImsService to perform
 // operations on the IImsCallSession.
interface IImsMmTelListener {
    IImsCallSessionListener onIncomingCall(in IImsCallSession c, in String callId, in Bundle extras);
    void onRejectedCall(in ImsCallProfile callProfile, in ImsReasonInfo reason);
    oneway void onVoiceMessageCountUpdate(int count);
    oneway void onAudioModeIsVoipChanged(int imsAudioHandler);
    oneway void onTriggerEpsFallback(int reason);
    oneway void onStartImsTrafficSession(int token, int trafficType, int accessNetworkType,
            int trafficDirection, in IImsTrafficSessionCallback callback);
    oneway void onModifyImsTrafficSession(int token, int accessNetworkType);
    oneway void onStopImsTrafficSession(int token);
    oneway void onMediaQualityStatusChanged(in MediaQualityStatus status);
}
