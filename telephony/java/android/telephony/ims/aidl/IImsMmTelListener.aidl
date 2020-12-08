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

import com.android.ims.internal.IImsCallSession;

/**
 * See MmTelFeature#Listener for more information.
 * {@hide}
 */
 // This interface is not considered oneway because we need to ensure that these operations are
 // processed by telephony before the control flow returns to the ImsService to perform
 // operations on the IImsCallSession.
interface IImsMmTelListener {
    void onIncomingCall(IImsCallSession c, in Bundle extras);
    void onRejectedCall(in ImsCallProfile callProfile, in ImsReasonInfo reason);
    oneway void onVoiceMessageCountUpdate(int count);
}
