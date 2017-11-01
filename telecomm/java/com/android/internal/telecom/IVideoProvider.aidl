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

package com.android.internal.telecom;

import android.net.Uri;
import android.view.Surface;
import android.telecom.VideoProfile;

/**
 * Internal remote interface for a video call provider.
 * @see android.telecom.VideoProvider
 * @hide
 */
oneway interface IVideoProvider {
    void addVideoCallback(IBinder videoCallbackBinder);

    void removeVideoCallback(IBinder videoCallbackBinder);

    void setCamera(String cameraId, in String mCallingPackageName, int targetSdkVersion);

    void setPreviewSurface(in Surface surface);

    void setDisplaySurface(in Surface surface);

    void setDeviceOrientation(int rotation);

    void setZoom(float value);

    void sendSessionModifyRequest(in VideoProfile fromProfile, in VideoProfile toProfile);

    void sendSessionModifyResponse(in VideoProfile responseProfile);

    void requestCameraCapabilities();

    void requestCallDataUsage();

    void setPauseImage(in Uri uri);
}
