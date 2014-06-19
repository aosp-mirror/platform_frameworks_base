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
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.BinderHolder;
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

    int connect(ICameraClient client, int cameraId,
                    String clientPackageName,
                    int clientUid,
                    // Container for an ICamera object
                    out BinderHolder device);

    int connectPro(IProCameraCallbacks callbacks, int cameraId,
                              String clientPackageName,
                              int clientUid,
                              // Container for an IProCameraUser object
                              out BinderHolder device);

    int connectDevice(ICameraDeviceCallbacks callbacks, int cameraId,
                              String clientPackageName,
                              int clientUid,
                              // Container for an ICameraDeviceUser object
                              out BinderHolder device);

    int addListener(ICameraServiceListener listener);
    int removeListener(ICameraServiceListener listener);

    int getCameraCharacteristics(int cameraId, out CameraMetadataNative info);

    /**
     * The java stubs for this method are not intended to be used.  Please use
     * the native stub in frameworks/av/include/camera/ICameraService.h instead.
     * The BinderHolder output is being used as a placeholder, and will not be
     * well-formatted in the generated java method.
     */
    int getCameraVendorTagDescriptor(out BinderHolder desc);

    // Writes the camera1 parameters into a single-element array.
    int getLegacyParameters(int cameraId, out String[] parameters);
    // Determines if a particular API version is supported; see ICameraService.h for version defines
    int supportsCameraApi(int cameraId, int apiVersion);

    int connectLegacy(ICameraClient client, int cameraId,
                    int halVersion,
                    String clientPackageName,
                    int clientUid,
                    // Container for an ICamera object
                    out BinderHolder device);
}
