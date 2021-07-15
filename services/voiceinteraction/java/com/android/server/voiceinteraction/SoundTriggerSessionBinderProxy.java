/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.hardware.soundtrigger.SoundTrigger;
import android.os.RemoteException;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;

/**
 * A remote object that simply proxies calls to a real {@link IVoiceInteractionSoundTriggerSession}
 * implementation. This design pattern allows us to add decorators to the core implementation
 * (simply wrapping a binder object does not work).
 */
final class SoundTriggerSessionBinderProxy extends IVoiceInteractionSoundTriggerSession.Stub {

    private final IVoiceInteractionSoundTriggerSession mDelegate;

    SoundTriggerSessionBinderProxy(IVoiceInteractionSoundTriggerSession delegate) {
        mDelegate = delegate;
    }

    @Override
    public SoundTrigger.ModuleProperties getDspModuleProperties() throws RemoteException {
        return mDelegate.getDspModuleProperties();
    }

    @Override
    public int startRecognition(int i, String s,
            IHotwordRecognitionStatusCallback iHotwordRecognitionStatusCallback,
            SoundTrigger.RecognitionConfig recognitionConfig, boolean b) throws RemoteException {
        return mDelegate.startRecognition(i, s, iHotwordRecognitionStatusCallback,
                recognitionConfig, b);
    }

    @Override
    public int stopRecognition(int i,
            IHotwordRecognitionStatusCallback iHotwordRecognitionStatusCallback)
            throws RemoteException {
        return mDelegate.stopRecognition(i, iHotwordRecognitionStatusCallback);
    }

    @Override
    public int setParameter(int i, int i1, int i2) throws RemoteException {
        return mDelegate.setParameter(i, i1, i2);
    }

    @Override
    public int getParameter(int i, int i1) throws RemoteException {
        return mDelegate.getParameter(i, i1);
    }

    @Override
    public SoundTrigger.ModelParamRange queryParameter(int i, int i1) throws RemoteException {
        return mDelegate.queryParameter(i, i1);
    }
}
