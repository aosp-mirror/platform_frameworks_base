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

package com.android.internal.app;

import android.app.VoiceInteractor;
import android.os.Bundle;

import com.android.internal.app.IVoiceInteractorRequest;

/**
 * IPC interface for an application to receive callbacks from the voice system.
 */
oneway interface IVoiceInteractorCallback {
    void deliverConfirmationResult(IVoiceInteractorRequest request, boolean confirmed,
            in Bundle result);
    void deliverPickOptionResult(IVoiceInteractorRequest request, boolean finished,
            in VoiceInteractor.PickOptionRequest.Option[] selections, in Bundle result);
    void deliverCompleteVoiceResult(IVoiceInteractorRequest request, in Bundle result);
    void deliverAbortVoiceResult(IVoiceInteractorRequest request, in Bundle result);
    void deliverCommandResult(IVoiceInteractorRequest request, boolean finished, in Bundle result);
    void deliverCancel(IVoiceInteractorRequest request);
    void destroy();
}
