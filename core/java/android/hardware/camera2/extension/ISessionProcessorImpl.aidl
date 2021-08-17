/**
 * Copyright (c) 2021, The Android Open Source Project
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

import android.hardware.camera2.extension.CameraSessionConfig;
import android.hardware.camera2.extension.ICaptureCallback;
import android.hardware.camera2.extension.IRequestProcessorImpl;
import android.hardware.camera2.extension.OutputSurface;

/** @hide */
interface ISessionProcessorImpl
{
    CameraSessionConfig initSession(in String cameraId, in OutputSurface previewSurface,
            in OutputSurface imageCaptureSurface);
    void deInitSession();
    void onCaptureSessionStart(IRequestProcessorImpl requestProcessor);
    void onCaptureSessionEnd();
    int startRepeating(in ICaptureCallback callback);
    void stopRepeating();
    int startCapture(in ICaptureCallback callback, int jpegRotation, int jpegQuality);
}
