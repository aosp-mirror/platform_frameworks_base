/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.telecomm.CameraCapabilities;
import android.telecomm.VideoProfile;

/**
 * Internal remote interface for IMS's video call provider.
 *
 * At least initially, this aidl mirrors telecomm's {@link VideoCallCallback}. We created a
 * separate aidl interface for invoking callbacks in Telephony from the IMS Service to without
 * accessing internal interfaces. See {@link IImsVideoCallProvider} for additional detail.
 *
 * @see android.telecomm.internal.IVideoCallCallback
 * @see android.telecomm.VideoCallImpl
 *
 * {@hide}
 */
oneway interface IImsVideoCallCallback {
    void receiveSessionModifyRequest(in VideoProfile videoProfile);

    void receiveSessionModifyResponse(int status, in VideoProfile requestedProfile,
        in VideoProfile responseProfile);

    void handleCallSessionEvent(int event);

    void changePeerDimensions(int width, int height);

    void changeCallDataUsage(int dataUsage);

    void changeCameraCapabilities(in CameraCapabilities cameraCapabilities);
}
