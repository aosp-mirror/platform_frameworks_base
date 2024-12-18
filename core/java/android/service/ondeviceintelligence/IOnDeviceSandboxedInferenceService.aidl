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
import android.app.ondeviceintelligence.ITokenInfoCallback;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.Feature;
import android.os.IRemoteCallback;
import android.os.ICancellationSignal;
import android.os.PersistableBundle;
import android.os.Bundle;
import com.android.internal.infra.AndroidFuture;
import android.service.ondeviceintelligence.IRemoteStorageService;
import android.service.ondeviceintelligence.IProcessingUpdateStatusCallback;

/**
 * Interface for a concrete implementation to provide on-device sandboxed inference.
 *
 * @hide
 */
oneway interface IOnDeviceSandboxedInferenceService {
    void registerRemoteStorageService(in IRemoteStorageService storageService,
                                        in IRemoteCallback remoteCallback) = 0;
    void requestTokenInfo(int callerUid, in Feature feature, in Bundle request,
                            in AndroidFuture cancellationSignal,
                            in ITokenInfoCallback tokenInfoCallback) = 1;
    void processRequest(int callerUid, in Feature feature, in Bundle request, in int requestType,
                        in AndroidFuture cancellationSignal,
                        in AndroidFuture processingSignal,
                        in IResponseCallback callback) = 2;
    void processRequestStreaming(int callerUid, in Feature feature, in Bundle request, in int requestType,
                                in AndroidFuture cancellationSignal,
                                in AndroidFuture processingSignal,
                                in IStreamingResponseCallback callback) = 3;
    void updateProcessingState(in Bundle processingState,
                                     in IProcessingUpdateStatusCallback callback) = 4;
}