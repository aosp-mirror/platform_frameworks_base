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

import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.os.DeadObjectException;
import android.os.IBinder;
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
public class SoundTriggerHalEnforcer implements ISoundTriggerHal {
    private static final String TAG = "SoundTriggerHalEnforcer";

    /** The state of a model. */
    private enum ModelState {
        /** Model is loaded, but inactive. */
        INACTIVE,
        /** Model is active. */
        ACTIVE,
        /** A request to stop is being made, which may or may not have been processed yet. */
        PENDING_STOP,
    }

    private final ISoundTriggerHal mUnderlying;
    private final Map<Integer, ModelState> mModelStates = new HashMap<>();

    public SoundTriggerHalEnforcer(ISoundTriggerHal underlying) {
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
    public void registerCallback(GlobalCallback callback) {
        try {
            mUnderlying.registerCallback(callback);
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        try {
            synchronized (mModelStates) {
                int handle = mUnderlying.loadSoundModel(soundModel,
                        new ModelCallbackEnforcer(callback));
                mModelStates.put(handle, ModelState.INACTIVE);
                return handle;
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel, ModelCallback callback) {
        try {
            synchronized (mModelStates) {
                int handle = mUnderlying.loadPhraseSoundModel(soundModel,
                        new ModelCallbackEnforcer(callback));
                mModelStates.put(handle, ModelState.INACTIVE);
                return handle;
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        try {
            // This call into the HAL may block on callback processing, thus must be done outside
            // of the critical section. After this call returns we are guaranteed to no longer be
            // getting unload events for that model.
            mUnderlying.unloadSoundModel(modelHandle);
            synchronized (mModelStates) {
                // At this point, the model may have already been removed by a HAL callback, but the
                // remove() method is a no-op in this case, so thus safe.
                mModelStates.remove(modelHandle);
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        try {
            // This call into the HAL may block on callback processing, thus must be done outside
            // of the critical section. After this call returns we are guaranteed to no longer be
            // getting stop events for that model.
            synchronized (mModelStates) {
                mModelStates.replace(modelHandle, ModelState.PENDING_STOP);
            }
            mUnderlying.stopRecognition(modelHandle);
            synchronized (mModelStates) {
                // At this point, the model might have been preemptively unloaded, but replace()
                // do nothing when the entry does not exist, so all good.
                mModelStates.replace(modelHandle, ModelState.INACTIVE);
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) {
        try {
            synchronized (mModelStates) {
                mUnderlying.startRecognition(modelHandle, deviceHandle, ioHandle, config);
                mModelStates.replace(modelHandle, ModelState.ACTIVE);
            }
        } catch (RuntimeException e) {
            throw handleException(e);
        }
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        try {
            mUnderlying.forceRecognitionEvent(modelHandle);
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
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        mUnderlying.linkToDeath(recipient);
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mUnderlying.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() {
        return mUnderlying.interfaceDescriptor();
    }

    @Override
    public void flushCallbacks() {
        mUnderlying.flushCallbacks();
    }

    @Override
    public void clientAttached(IBinder binder) {
        mUnderlying.clientAttached(binder);
    }

    @Override
    public void clientDetached(IBinder binder) {
        mUnderlying.clientDetached(binder);
    }

    private RuntimeException handleException(RuntimeException e) {
        if (e instanceof RecoverableException) {
            throw e;
        }
        if (e.getCause() instanceof DeadObjectException) {
            // Server is dead, no need to reboot.
            Log.e(TAG, "HAL died");
            throw new RecoverableException(Status.DEAD_OBJECT);
        }
        Log.e(TAG, "Exception caught from HAL, rebooting HAL");
        reboot();
        throw e;
    }

    @Override
    public void reboot() {
        mUnderlying.reboot();
    }

    @Override
    public void detach() {
        mUnderlying.detach();
    }

    private class ModelCallbackEnforcer implements ModelCallback {
        private final ModelCallback mUnderlying;

        private ModelCallbackEnforcer(ModelCallback underlying) {
            mUnderlying = underlying;
        }

        @Override
        public void recognitionCallback(int model, RecognitionEventSys event) {
            synchronized (mModelStates) {
                ModelState state = mModelStates.get(model);
                if (state == null) {
                    Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
                    reboot();
                    return;
                }
                if (event.recognitionEvent.recognitionStillActive
                        && event.recognitionEvent.status != RecognitionStatus.SUCCESS
                        && event.recognitionEvent.status != RecognitionStatus.FORCED) {
                    Log.wtfStack(TAG,
                            "recognitionStillActive is only allowed when the recognition status "
                                    + "is SUCCESS");
                    reboot();
                    return;
                }
                if (!event.recognitionEvent.recognitionStillActive) {
                    mModelStates.replace(model, ModelState.INACTIVE);
                }
            }
            // Always invoke the delegate from outside the critical section.
            mUnderlying.recognitionCallback(model, event);
        }

        @Override
        public void phraseRecognitionCallback(int model, PhraseRecognitionEventSys event) {
            synchronized (mModelStates) {
                ModelState state = mModelStates.get(model);
                if (state == null) {
                    Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
                    reboot();
                    return;
                }
                if (event.phraseRecognitionEvent.common.recognitionStillActive
                        && event.phraseRecognitionEvent.common.status != RecognitionStatus.SUCCESS
                        && event.phraseRecognitionEvent.common.status != RecognitionStatus.FORCED) {
                    Log.wtfStack(TAG,
                            "recognitionStillActive is only allowed when the recognition status "
                                    + "is SUCCESS");
                    reboot();
                    return;
                }
                if (!event.phraseRecognitionEvent.common.recognitionStillActive) {
                    mModelStates.replace(model, ModelState.INACTIVE);
                }
            }
            // Always invoke the delegate from outside the critical section.
            mUnderlying.phraseRecognitionCallback(model, event);
        }

        @Override
        public void modelUnloaded(int modelHandle) {
            synchronized (mModelStates) {
                ModelState state = mModelStates.get(modelHandle);
                if (state == null) {
                    Log.wtfStack(TAG, "Unexpected unload event for model: " + modelHandle);
                    reboot();
                    return;
                }

                if (state == ModelState.ACTIVE) {
                    Log.wtfStack(TAG, "Trying to unload an active model: " + modelHandle);
                    reboot();
                    return;
                }
                mModelStates.remove(modelHandle);
            }
            // Always invoke the delegate from outside the critical section.
            mUnderlying.modelUnloaded(modelHandle);
        }
    }
}
