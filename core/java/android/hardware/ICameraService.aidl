/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware;

import android.hardware.ICamera;
import android.hardware.ICameraClient;
import android.hardware.IProCameraUser;
import android.hardware.IProCameraCallbacks;
import android.hardware.photography.ICameraDeviceUser;
import android.hardware.photography.ICameraDeviceCallbacks;
import android.hardware.ICameraServiceListener;
import android.hardware.CameraInfo;

/** @hide */
interface ICameraService
{
    /**
     * Keep up-to-date with frameworks/av/include/camera/ICameraService.h
     */
    int getNumberOfCameras();

    // rest of 'int' return values in this file are actually status_t

    int getCameraInfo(int cameraId, out CameraInfo info);

    ICamera connect(ICameraClient client, int cameraId,
                    String clientPackageName,
                    int clientUid);

    IProCameraUser connectPro(IProCameraCallbacks callbacks, int cameraId,
                              String clientPackageName,
                              int clientUid);

    ICameraDeviceUser connectDevice(ICameraDeviceCallbacks callbacks, int cameraId,
                              String clientPackageName,
                              int clientUid);

    int addListener(ICameraServiceListener listener);
    int removeListener(ICameraServiceListener listener);
}
