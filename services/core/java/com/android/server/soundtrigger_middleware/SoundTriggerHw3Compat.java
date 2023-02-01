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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.hardware.soundtrigger3.ISoundTriggerHw;
import android.hardware.soundtrigger3.ISoundTriggerHwCallback;
import android.hardware.soundtrigger3.ISoundTriggerHwGlobalCallback;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

public class SoundTriggerHw3Compat implements ISoundTriggerHal {
    private final @NonNull ISoundTriggerHw mDriver;
    private final @NonNull Runnable mRebootRunnable;

    public SoundTriggerHw3Compat(@NonNull IBinder binder, @NonNull Runnable rebootRunnable) {
        mDriver = android.hardware.soundtrigger3.ISoundTriggerHw.Stub.asInterface(binder);
        mRebootRunnable = rebootRunnable;
    }

    @Override
    public Properties getProperties() {
        try {
            return mDriver.getProperties();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        try {
            mDriver.registerGlobalCallback(new GlobalCallbackAdaper(callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        try {
            return mDriver.loadSoundModel(soundModel, new ModelCallbackAdaper(callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == Status.RESOURCE_CONTENTION) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            throw e;
        }
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel, ModelCallback callback) {
        try {
            return mDriver.loadPhraseSoundModel(soundModel, new ModelCallbackAdaper(callback));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == Status.RESOURCE_CONTENTION) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            throw e;
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        try {
            mDriver.unloadSoundModel(modelHandle);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) {
        try {
            mDriver.startRecognition(modelHandle, deviceHandle, ioHandle, config);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == Status.RESOURCE_CONTENTION) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            throw e;
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        try {
            mDriver.stopRecognition(modelHandle);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        try {
            mDriver.forceRecognitionEvent(modelHandle);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        try {
            return mDriver.queryParameter(modelHandle, param);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        try {
            return mDriver.getParameter(modelHandle, param);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        try {
            mDriver.setParameter(modelHandle, param, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public String interfaceDescriptor() {
        try {
            return mDriver.asBinder().getInterfaceDescriptor();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        try {
            mDriver.asBinder().linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mDriver.asBinder().unlinkToDeath(recipient, 0);
    }

    @Override
    public void flushCallbacks() {
        // No-op.
    }

    @Override
    public void reboot() {
        mRebootRunnable.run();
    }

    @Override
    public void detach() {
        // No-op.
    }

    private static class GlobalCallbackAdaper extends ISoundTriggerHwGlobalCallback.Stub {
        private final @NonNull GlobalCallback mDelegate;

        public GlobalCallbackAdaper(@NonNull GlobalCallback callback) {
            mDelegate = callback;
        }

        @Override
        public void onResourcesAvailable() {
            mDelegate.onResourcesAvailable();
        }

        @Override
        public int getInterfaceVersion() {
            return ISoundTriggerHwGlobalCallback.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return ISoundTriggerHwGlobalCallback.HASH;
        }
    }

    private static class ModelCallbackAdaper extends ISoundTriggerHwCallback.Stub {
        private final @NonNull ModelCallback mDelegate;

        public ModelCallbackAdaper(ModelCallback callback) {
            mDelegate = callback;
        }

        @Override
        public void modelUnloaded(int model) {
            mDelegate.modelUnloaded(model);
        }

        @Override
        public void phraseRecognitionCallback(int model, PhraseRecognitionEvent event) {
            // A FORCED status implies that recognition is still active after the event.
            event.common.recognitionStillActive |= event.common.status == RecognitionStatus.FORCED;
            mDelegate.phraseRecognitionCallback(model, event);
        }

        @Override
        public void recognitionCallback(int model, RecognitionEvent event) {
            // A FORCED status implies that recognition is still active after the event.
            event.recognitionStillActive |= event.status == RecognitionStatus.FORCED;
            mDelegate.recognitionCallback(model, event);
        }

        @Override
        public int getInterfaceVersion() {
            return ISoundTriggerHwCallback.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return ISoundTriggerHwCallback.HASH;
        }
    }
}
