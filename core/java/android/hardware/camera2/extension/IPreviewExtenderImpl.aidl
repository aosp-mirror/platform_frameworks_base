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
import android.hardware.camera2.extension.IPreviewImageProcessorImpl;
import android.hardware.camera2.extension.IRequestUpdateProcessorImpl;
import android.hardware.camera2.extension.SizeList;

import android.os.IBinder;

/** @hide */
interface IPreviewExtenderImpl
{
    void onInit(in IBinder token, in String cameraId, in CameraMetadataNative cameraCharacteristics);
    void onDeInit(in IBinder token);
    @nullable CaptureStageImpl onPresetSession();
    @nullable CaptureStageImpl onEnableSession();
    @nullable CaptureStageImpl onDisableSession();

    void init(in String cameraId, in CameraMetadataNative chars);
    boolean isExtensionAvailable(in String cameraId, in CameraMetadataNative chars);
    @nullable CaptureStageImpl getCaptureStage();
    int getSessionType();

    const int PROCESSOR_TYPE_REQUEST_UPDATE_ONLY = 0;
    const int PROCESSOR_TYPE_IMAGE_PROCESSOR = 1;
    const int PROCESSOR_TYPE_NONE = 2;
    int getProcessorType();
    @nullable IPreviewImageProcessorImpl getPreviewImageProcessor();
    @nullable IRequestUpdateProcessorImpl getRequestUpdateProcessor();

    @nullable List<SizeList> getSupportedResolutions();
}
