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

import android.telecom.VideoProfile;

/**
 * Internal remote interface for IMS's video call provider.
 *
 * At least initially, this aidl mirrors telecomm's {@link VideoCallCallback}. We created a
 * separate aidl interface for invoking callbacks in Telephony from the IMS Service to without
 * accessing internal interfaces. See {@link IImsVideoCallProvider} for additional detail.
 *
 * @see android.telecom.internal.IVideoCallCallback
 * @see android.telecom.VideoCallImpl
 *
 * {@hide}
 */
oneway interface IImsVideoCallCallback {
    @UnsupportedAppUsage
    void receiveSessionModifyRequest(in VideoProfile videoProfile);

    @UnsupportedAppUsage
    void receiveSessionModifyResponse(int status, in VideoProfile requestedProfile,
        in VideoProfile responseProfile);

    @UnsupportedAppUsage
    void handleCallSessionEvent(int event);

    @UnsupportedAppUsage
    void changePeerDimensions(int width, int height);

    @UnsupportedAppUsage
    void changeCallDataUsage(long dataUsage);

    @UnsupportedAppUsage
    void changeCameraCapabilities(in VideoProfile.CameraCapabilities cameraCapabilities);

    @UnsupportedAppUsage
    void changeVideoQuality(int videoQuality);
}
