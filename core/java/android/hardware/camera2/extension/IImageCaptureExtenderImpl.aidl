/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.hardware.camera2.extension;

import android.hardware.camera2.impl.CameraMetadataNative;

import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.LatencyPair;
import android.hardware.camera2.extension.LatencyRange;
import android.hardware.camera2.extension.Size;
import android.hardware.camera2.extension.SizeList;

import android.os.IBinder;

/** @hide */
interface IImageCaptureExtenderImpl
{
    void onInit(in IBinder token, in String cameraId, in CameraMetadataNative cameraCharacteristics);
    void onDeInit(in IBinder token);
    @nullable CaptureStageImpl onPresetSession();
    @nullable CaptureStageImpl onEnableSession();
    @nullable CaptureStageImpl onDisableSession();
    int getSessionType();

    boolean isExtensionAvailable(in String cameraId, in CameraMetadataNative chars);
    void init(in String cameraId, in CameraMetadataNative chars);
    @nullable ICaptureProcessorImpl getCaptureProcessor();
    @nullable List<CaptureStageImpl> getCaptureStages();
    int getMaxCaptureStage();
    @nullable List<SizeList> getSupportedResolutions();
    @nullable List<SizeList> getSupportedPostviewResolutions(in Size captureSize);
    LatencyRange getEstimatedCaptureLatencyRange(in Size outputSize);
    CameraMetadataNative getAvailableCaptureRequestKeys();
    CameraMetadataNative getAvailableCaptureResultKeys();
    boolean isCaptureProcessProgressAvailable();
    @nullable LatencyPair getRealtimeCaptureLatency();
    boolean isPostviewAvailable();
}
