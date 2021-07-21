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

import android.hardware.camera2.extension.IImageProcessorImpl;
import android.hardware.camera2.extension.IRequestCallback;
import android.hardware.camera2.extension.OutputConfigId;
import android.hardware.camera2.extension.Request;

/** @hide */
interface IRequestProcessorImpl
{
    void setImageProcessor(in OutputConfigId outputConfigId, in IImageProcessorImpl imageProcessor);
    boolean submit(in Request request, in IRequestCallback callback);
    boolean submitBurst(in List<Request> requests, in IRequestCallback callback);
    boolean setRepeating(in Request request, in IRequestCallback callback);
    void abortCaptures();
    void stopRepeating();
}
