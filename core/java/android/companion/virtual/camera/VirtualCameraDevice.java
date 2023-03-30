/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.hardware.camera2.CameraCharacteristics;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Virtual camera that is used to send image data into system.
 *
 * @hide
 */
public final class VirtualCameraDevice implements AutoCloseable {

    @NonNull
    private final String mCameraDeviceName;
    @NonNull
    private final CameraCharacteristics mCameraCharacteristics;
    @NonNull
    private final VirtualCameraOutput mCameraOutput;
    private boolean mCameraRegistered = false;

    /**
     * VirtualCamera device constructor.
     *
     * @param virtualDeviceId ID of virtual device to which camera will be added.
     * @param cameraName must be unique for each camera per virtual device.
     * @param characteristics of camera that will be passed into system in order to describe
     *         camera.
     * @param virtualCameraInput component that provides image data.
     * @param executor on which to collect image data and pass it into system.
     */
    public VirtualCameraDevice(int virtualDeviceId, @NonNull String cameraName,
            @NonNull CameraCharacteristics characteristics,
            @NonNull VirtualCameraInput virtualCameraInput, @NonNull Executor executor) {
        Objects.requireNonNull(cameraName);
        mCameraCharacteristics = Objects.requireNonNull(characteristics);
        mCameraDeviceName = generateCameraDeviceName(virtualDeviceId, cameraName);
        mCameraOutput = new VirtualCameraOutput(virtualCameraInput, executor);
        registerCamera();
    }

    private static String generateCameraDeviceName(int deviceId, @NonNull String cameraName) {
        return String.format(Locale.ENGLISH, "%d_%s", deviceId, Objects.requireNonNull(cameraName));
    }

    @Override
    public void close() {
        if (!mCameraRegistered) {
            return;
        }

        mCameraOutput.closeStream();
    }

    private void registerCamera() {
        if (mCameraRegistered) {
            return;
        }

        mCameraRegistered = true;
    }
}
