/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.hardware.soundtrigger.V2_1.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.media.soundtrigger_middleware.Status;
import android.os.DeadObjectException;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * A decorator around a HAL, which adds some checks that the HAL is behaving as expected.
 * This is not necessarily a strict enforcement for the HAL contract, but a place to add checks for
 * common HAL malfunctions, to help track them and assist in debugging.
 *
 * The class is thread-safe.
 */
public class SoundTriggerHw2Enforcer implements ISoundTriggerHw2 {
    static final String TAG = "SoundTriggerHw2Enforcer";

    final ISoundTriggerHw2 mUnderlying;
    Map<Integer, Boolean> mModelStates = new HashMap<>();

    public SoundTriggerHw2Enforcer(
            ISoundTriggerHw2 underlying) {
        mUnderlying = underlying;
    }

    @Override
    public Properties getProperties() {
        try {
            return mUnderlying.getProperties();
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, Callback callback,
            int cookie) {
        try {
            int handle = mUnderlying.loadSoundModel(soundModel, new CallbackEnforcer(callback),
                    cookie);
            synchronized (mModelStates) {
                mModelStates.put(handle, false);
            }
            return handle;
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel, Callback callback,
            int cookie) {
        try {
            int handle = mUnderlying.loadPhraseSoundModel(soundModel,
                    new CallbackEnforcer(callback),
                    cookie);
            synchronized (mModelStates) {
                mModelStates.put(handle, false);
            }
            return handle;
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        try {
            mUnderlying.unloadSoundModel(modelHandle);
            synchronized (mModelStates) {
                mModelStates.remove(modelHandle);
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        try {
            mUnderlying.stopRecognition(modelHandle);
            synchronized (mModelStates) {
                mModelStates.replace(modelHandle, false);
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void stopAllRecognitions() {
        try {
            mUnderlying.stopAllRecognitions();
            synchronized (mModelStates) {
                for (Map.Entry<Integer, Boolean> entry : mModelStates.entrySet()) {
                    entry.setValue(false);
                }
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void startRecognition(int modelHandle, RecognitionConfig config, Callback callback,
            int cookie) {
        // It is possible that an event will be sent before the HAL returns from the
        // startRecognition call, thus it is important to set the state to active before the call.
        synchronized (mModelStates) {
            mModelStates.replace(modelHandle, true);
        }
        try {
            mUnderlying.startRecognition(modelHandle, config, new CallbackEnforcer(callback),
                    cookie);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void getModelState(int modelHandle) {
        try {
            mUnderlying.getModelState(modelHandle);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        try {
            return mUnderlying.getModelParameter(modelHandle, param);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        try {
            mUnderlying.setModelParameter(modelHandle, param, value);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        try {
            return mUnderlying.queryParameter(modelHandle, param);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        return mUnderlying.linkToDeath(recipient, cookie);
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mUnderlying.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() throws RemoteException {
        return mUnderlying.interfaceDescriptor();
    }

    private static RuntimeException handleException(RuntimeException e) {
        if (e.getCause() instanceof DeadObjectException) {
            // Server is dead, no need to reboot.
            Log.e(TAG, "HAL died");
            throw new RecoverableException(Status.DEAD_OBJECT);
        }
        Log.e(TAG, "Exception caught from HAL, rebooting HAL");
        rebootHal();
        throw e;
    }

    private static void rebootHal() {
        // This property needs to be defined in an init.rc script and trigger a HAL reboot.
        SystemProperties.set("sys.audio.restart.hal", "1");
    }

    private class CallbackEnforcer implements Callback {
        private final Callback mUnderlying;

        private CallbackEnforcer(
                Callback underlying) {
            mUnderlying = underlying;
        }

        @Override
        public void recognitionCallback(ISoundTriggerHwCallback.RecognitionEvent event,
                int cookie) {
            int model = event.header.model;
            synchronized (mModelStates) {
                if (!mModelStates.getOrDefault(model, false)) {
                    Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
                    rebootHal();
                    return;
                }
                if (event.header.status
                        != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                    mModelStates.replace(model, false);
                }
            }
            mUnderlying.recognitionCallback(event, cookie);
        }

        @Override
        public void phraseRecognitionCallback(ISoundTriggerHwCallback.PhraseRecognitionEvent event,
                int cookie) {
            int model = event.common.header.model;
            synchronized (mModelStates) {
                if (!mModelStates.getOrDefault(model, false)) {
                    Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
                    rebootHal();
                    return;
                }
                if (event.common.header.status
                        != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                    mModelStates.replace(model, false);
                }
            }
            mUnderlying.phraseRecognitionCallback(event, cookie);
        }
    }
}
