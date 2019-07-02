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
import android.os.ICancellationSignal;

import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;

/**
 * IPC interface for an application to perform calls through a VoiceInteractor.
 */
interface IVoiceInteractor {
    IVoiceInteractorRequest startConfirmation(String callingPackage,
            IVoiceInteractorCallback callback, in VoiceInteractor.Prompt prompt, in Bundle extras);
    IVoiceInteractorRequest startPickOption(String callingPackage,
            IVoiceInteractorCallback callback, in VoiceInteractor.Prompt prompt,
            in VoiceInteractor.PickOptionRequest.Option[] options, in Bundle extras);
    IVoiceInteractorRequest startCompleteVoice(String callingPackage,
            IVoiceInteractorCallback callback, in VoiceInteractor.Prompt prompt, in Bundle extras);
    IVoiceInteractorRequest startAbortVoice(String callingPackage,
            IVoiceInteractorCallback callback, in VoiceInteractor.Prompt prompt, in Bundle extras);
    IVoiceInteractorRequest startCommand(String callingPackage,
            IVoiceInteractorCallback callback, String command, in Bundle extras);
    boolean[] supportsCommands(String callingPackage, in String[] commands);
    void notifyDirectActionsChanged(int taskId, IBinder assistToken);
    void setKillCallback(in ICancellationSignal callback);
}
