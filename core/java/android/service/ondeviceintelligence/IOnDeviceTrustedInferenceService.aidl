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

package android.service.ondeviceintelligence;

import android.app.ondeviceintelligence.IStreamingResponseCallback;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.ITokenCountCallback;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.Feature;
import android.os.ICancellationSignal;
import android.os.PersistableBundle;
import android.os.Bundle;
import android.service.ondeviceintelligence.IRemoteStorageService;
import android.service.ondeviceintelligence.IProcessingUpdateStatusCallback;

/**
 * Interface for a concrete implementation to provide on device trusted inference.
 *
 * @hide
 */
oneway interface IOnDeviceTrustedInferenceService {
    void registerRemoteStorageService(in IRemoteStorageService storageService);
    void requestTokenCount(in Feature feature, in Content request, in ICancellationSignal cancellationSignal,
                            in ITokenCountCallback tokenCountCallback);
    void processRequest(in Feature feature, in Content request, in int requestType,
                        in ICancellationSignal cancellationSignal, in IProcessingSignal processingSignal,
                        in IResponseCallback callback);
    void processRequestStreaming(in Feature feature, in Content request, in int requestType,
                                in ICancellationSignal cancellationSignal, in IProcessingSignal processingSignal,
                                in IStreamingResponseCallback callback);
    void updateProcessingState(in Bundle processingState,
                                     in IProcessingUpdateStatusCallback callback);
}