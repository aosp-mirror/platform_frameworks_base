/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.Nullable;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_2.ISoundTriggerHw;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundModelType;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.media.soundtrigger_middleware.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This is an implementation of a single module of the ISoundTriggerMiddlewareService interface,
 * exposing itself through the {@link ISoundTriggerModule} interface, possibly to multiple separate
 * clients.
 * <p>
 * Typical usage is to query the module capabilities using {@link #getProperties()} and then to use
 * the module through an {@link ISoundTriggerModule} instance, obtained via {@link
 * #attach(ISoundTriggerCallback)}. Every such interface is its own session and state is not shared
 * between sessions (i.e. cannot use a handle obtained from one session through another).
 * <p>
 * <b>Important conventions:</b>
 * <ul>
 * <li>Correct usage is assumed. This implementation does not attempt to gracefully handle
 * invalid usage, and such usage will result in undefined behavior. If this service is to be
 * offered to an untrusted client, it must be wrapped with input and state validation.
 * <li>The underlying driver is assumed to be correct. This implementation does not attempt to
 * gracefully handle driver malfunction and such behavior will result in undefined behavior. If this
 * service is to used with an untrusted driver, the driver must be wrapped with validation / error
 * recovery code.
 * <li>RemoteExceptions thrown by the driver are treated as RuntimeExceptions - they are not
 * considered recoverable faults and should not occur in a properly functioning system.
 * <li>There is no binder instance associated with this implementation. Do not call asBinder().
 * <li>The implementation may throw a {@link RecoverableException} to indicate non-fatal,
 * recoverable faults. The error code would one of the
 * {@link android.media.soundtrigger_middleware.Status} constants. Any other exception
 * thrown should be regarded as a bug in the implementation or one of its dependencies
 * (assuming correct usage).
 * <li>The implementation is designed for testibility by featuring dependency injection (the
 * underlying HAL driver instances are passed to the ctor) and by minimizing dependencies
 * on Android runtime.
 * <li>The implementation is thread-safe. This is achieved by a simplistic model, where all entry-
 * points (both client API and driver callbacks) obtain a lock on the SoundTriggerModule instance
 * for their entire scope. Any other method can be assumed to be running with the lock already
 * obtained, so no further locking should be done. While this is not necessarily the most efficient
 * synchronization strategy, it is very easy to reason about and this code is likely not on any
 * performance-critical
 * path.
 * </ul>
 *
 * @hide
 */
class SoundTriggerModule {
    static private final String TAG = "SoundTriggerModule";
    @NonNull private final ISoundTriggerHw2 mHalService;
    @NonNull private final SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider;
    private final Set<Session> mActiveSessions = new HashSet<>();
    private int mNumLoadedModels = 0;
    private SoundTriggerModuleProperties mProperties = null;
    private boolean mRecognitionAvailable;

    /**
     * Ctor.
     *
     * @param halService The underlying HAL driver.
     */
    SoundTriggerModule(@NonNull android.hardware.soundtrigger.V2_0.ISoundTriggerHw halService,
            @NonNull SoundTriggerMiddlewareImpl.AudioSessionProvider audioSessionProvider) {
        assert halService != null;
        mHalService = new SoundTriggerHw2Compat(halService);
        mAudioSessionProvider = audioSessionProvider;
        mProperties = ConversionUtil.hidl2aidlProperties(mHalService.getProperties());

        // We conservatively assume that external capture is active until explicitly told otherwise.
        mRecognitionAvailable = mProperties.concurrentCapture;
    }

    /**
     * Establish a client session with this module.
     *
     * This module may be shared by multiple clients, each will get its own session. While resources
     * are shared between the clients, each session has its own state and data should not be shared
     * across sessions.
     *
     * @param callback The client callback, which will be used for all messages. This is a oneway
     *                 callback, so will never block, throw an unchecked exception or return a
     *                 value.
     * @return The interface through which this module can be controlled.
     */
    synchronized @NonNull
    Session attach(@NonNull ISoundTriggerCallback callback) {
        Log.d(TAG, "attach()");
        Session session = new Session(callback);
        mActiveSessions.add(session);
        return session;
    }

    /**
     * Query the module's properties.
     *
     * @return The properties structure.
     */
    synchronized @NonNull
    SoundTriggerModuleProperties getProperties() {
        return mProperties;
    }

    /**
     * Notify the module that external capture has started / finished, using the same input device
     * used for recognition.
     * If the underlying driver does not support recognition while capturing, capture will be
     * aborted, and the recognition callback will receive and abort event. In addition, all active
     * clients will be notified of the change in state.
     *
     * @param active true iff external capture is active.
     */
    synchronized void setExternalCaptureState(boolean active) {
        Log.d(TAG, String.format("setExternalCaptureState(active=%b)", active));
        if (mProperties.concurrentCapture) {
            // If we support concurrent capture, we don't care about any of this.
            return;
        }
        mRecognitionAvailable = !active;
        if (!mRecognitionAvailable) {
            // Our module does not support recognition while a capture is active -
            // need to abort all active recognitions.
            for (Session session : mActiveSessions) {
                session.abortActiveRecognitions();
            }
        }
        for (Session session : mActiveSessions) {
            session.notifyRecognitionAvailability();
        }
    }

    /**
     * Remove session from the list of active sessions.
     *
     * @param session The session to remove.
     */
    private void removeSession(@NonNull Session session) {
        mActiveSessions.remove(session);
    }

    /** State of a single sound model. */
    private enum ModelState {
        /** Initial state, until load() is called. */
        INIT,
        /** Model is loaded, but recognition is not active. */
        LOADED,
        /** Model is loaded and recognition is active. */
        ACTIVE
    }

    /**
     * A single client session with this module.
     *
     * This is the main interface used to interact with this module.
     */
    private class Session implements ISoundTriggerModule {
        private ISoundTriggerCallback mCallback;
        private Map<Integer, Model> mLoadedModels = new HashMap<>();

        /**
         * Ctor.
         *
         * @param callback The client callback interface.
         */
        private Session(@NonNull ISoundTriggerCallback callback) {
            mCallback = callback;
            notifyRecognitionAvailability();
        }

        @Override
        public void detach() {
            Log.d(TAG, "detach()");
            synchronized (SoundTriggerModule.this) {
                removeSession(this);
            }
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            Log.d(TAG, String.format("loadModel(model=%s)", model));
            synchronized (SoundTriggerModule.this) {
                if (mNumLoadedModels == mProperties.maxSoundModels) {
                    throw new RecoverableException(Status.RESOURCE_CONTENTION,
                            "Maximum number of models loaded.");
                }
                Model loadedModel = new Model();
                int result = loadedModel.load(model);
                ++mNumLoadedModels;
                return result;
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            Log.d(TAG, String.format("loadPhraseModel(model=%s)", model));
            synchronized (SoundTriggerModule.this) {
                if (mNumLoadedModels == mProperties.maxSoundModels) {
                    throw new RecoverableException(Status.RESOURCE_CONTENTION,
                            "Maximum number of models loaded.");
                }
                Model loadedModel = new Model();
                int result = loadedModel.load(model);
                ++mNumLoadedModels;
                Log.d(TAG, String.format("loadPhraseModel()->%d", result));
                return result;
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            Log.d(TAG, String.format("unloadModel(handle=%d)", modelHandle));
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).unload();
                --mNumLoadedModels;
            }
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            Log.d(TAG,
                    String.format("startRecognition(handle=%d, config=%s)", modelHandle, config));
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).startRecognition(config);
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            Log.d(TAG, String.format("stopRecognition(handle=%d)", modelHandle));
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).stopRecognition();
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            Log.d(TAG, String.format("forceRecognitionEvent(handle=%d)", modelHandle));
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).forceRecognitionEvent();
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            Log.d(TAG,
                    String.format("setModelParameter(handle=%d, param=%d, value=%d)", modelHandle,
                            modelParam, value));
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).setParameter(modelParam, value);
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            Log.d(TAG, String.format("getModelParameter(handle=%d, param=%d)", modelHandle,
                    modelParam));
            synchronized (SoundTriggerModule.this) {
                return mLoadedModels.get(modelHandle).getParameter(modelParam);
            }
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam) {
            Log.d(TAG, String.format("queryModelParameterSupport(handle=%d, param=%d)", modelHandle,
                    modelParam));
            synchronized (SoundTriggerModule.this) {
                return mLoadedModels.get(modelHandle).queryModelParameterSupport(modelParam);
            }
        }

        /**
         * Abort all currently active recognitions.
         */
        private void abortActiveRecognitions() {
            for (Model model : mLoadedModels.values()) {
                model.abortActiveRecognition();
            }
        }

        private void notifyRecognitionAvailability() {
            try {
                mCallback.onRecognitionAvailabilityChange(mRecognitionAvailable);
            } catch (RemoteException e) {
                // Dead client will be handled by binderDied() - no need to handle here.
                // In any case, client callbacks are considered best effort.
                Log.e(TAG, "Client callback execption.", e);
            }
        }

        @Override
        public @NonNull
        IBinder asBinder() {
            throw new UnsupportedOperationException(
                    "This implementation is not intended to be used directly with Binder.");
        }

        /**
         * A single sound model in the system.
         *
         * All model-based operations are delegated to this class and implemented here.
         */
        private class Model implements ISoundTriggerHw2.Callback {
            public int mHandle;
            private ModelState mState = ModelState.INIT;
            private int mModelType = SoundModelType.UNKNOWN;
            private SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession mSession;

            private @NonNull
            ModelState getState() {
                return mState;
            }

            private void setState(@NonNull ModelState state) {
                mState = state;
                SoundTriggerModule.this.notifyAll();
            }

            private void waitStateChange() throws InterruptedException {
                SoundTriggerModule.this.wait();
            }

            private int load(@NonNull SoundModel model) {
                mModelType = model.type;
                ISoundTriggerHw.SoundModel hidlModel = ConversionUtil.aidl2hidlSoundModel(model);

                mSession = mAudioSessionProvider.acquireSession();
                try {
                    mHandle = mHalService.loadSoundModel(hidlModel, this, 0);
                } catch (Exception e) {
                    mAudioSessionProvider.releaseSession(mSession.mSessionHandle);
                    throw e;
                }

                setState(ModelState.LOADED);
                mLoadedModels.put(mHandle, this);
                return mHandle;
            }

            private int load(@NonNull PhraseSoundModel model) {
                mModelType = model.common.type;
                ISoundTriggerHw.PhraseSoundModel hidlModel =
                        ConversionUtil.aidl2hidlPhraseSoundModel(model);

                mSession = mAudioSessionProvider.acquireSession();
                try {
                    mHandle = mHalService.loadPhraseSoundModel(hidlModel, this, 0);
                } catch (Exception e) {
                    mAudioSessionProvider.releaseSession(mSession.mSessionHandle);
                    throw e;
                }

                setState(ModelState.LOADED);
                mLoadedModels.put(mHandle, this);
                return mHandle;
            }

            private void unload() {
                mAudioSessionProvider.releaseSession(mSession.mSessionHandle);
                mHalService.unloadSoundModel(mHandle);
                mLoadedModels.remove(mHandle);
            }

            private void startRecognition(@NonNull RecognitionConfig config) {
                if (!mRecognitionAvailable) {
                    // Recognition is unavailable - send an abort event immediately.
                    notifyAbort();
                    return;
                }
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig hidlConfig =
                        ConversionUtil.aidl2hidlRecognitionConfig(config);
                hidlConfig.header.captureDevice = mSession.mDeviceHandle;
                hidlConfig.header.captureHandle = mSession.mIoHandle;
                mHalService.startRecognition(mHandle, hidlConfig, this, 0);
                setState(ModelState.ACTIVE);
            }

            private void stopRecognition() {
                if (getState() == ModelState.LOADED) {
                    // This call is idempotent in order to avoid races.
                    return;
                }
                mHalService.stopRecognition(mHandle);
                setState(ModelState.LOADED);
            }

            /** Request a forced recognition event. Will do nothing if recognition is inactive. */
            private void forceRecognitionEvent() {
                if (getState() != ModelState.ACTIVE) {
                    // This call is idempotent in order to avoid races.
                    return;
                }
                mHalService.getModelState(mHandle);
            }


            private void setParameter(int modelParam, int value) {
                mHalService.setModelParameter(mHandle,
                        ConversionUtil.aidl2hidlModelParameter(modelParam), value);
            }

            private int getParameter(int modelParam) {
                return mHalService.getModelParameter(mHandle,
                        ConversionUtil.aidl2hidlModelParameter(modelParam));
            }

            @Nullable
            private ModelParameterRange queryModelParameterSupport(int modelParam) {
                return ConversionUtil.hidl2aidlModelParameterRange(
                        mHalService.queryParameter(mHandle,
                                ConversionUtil.aidl2hidlModelParameter(modelParam)));
            }

            /** Abort the recognition, if active. */
            private void abortActiveRecognition() {
                // If we're inactive, do nothing.
                if (getState() != ModelState.ACTIVE) {
                    return;
                }
                // Stop recognition.
                stopRecognition();

                // Notify the client that recognition has been aborted.
                notifyAbort();
            }

            /** Notify the client that recognition has been aborted. */
            private void notifyAbort() {
                try {
                    switch (mModelType) {
                        case SoundModelType.GENERIC: {
                            android.media.soundtrigger_middleware.RecognitionEvent event =
                                    new android.media.soundtrigger_middleware.RecognitionEvent();
                            event.status =
                                    android.media.soundtrigger_middleware.RecognitionStatus.ABORTED;
                            mCallback.onRecognition(mHandle, event);
                        }
                        break;

                        case SoundModelType.KEYPHRASE: {
                            android.media.soundtrigger_middleware.PhraseRecognitionEvent event =
                                    new android.media.soundtrigger_middleware.PhraseRecognitionEvent();
                            event.common =
                                    new android.media.soundtrigger_middleware.RecognitionEvent();
                            event.common.status =
                                    android.media.soundtrigger_middleware.RecognitionStatus.ABORTED;
                            mCallback.onPhraseRecognition(mHandle, event);
                        }
                        break;

                        default:
                            Log.e(TAG, "Unknown model type: " + mModelType);

                    }
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback execption.", e);
                }
            }

            @Override
            public void recognitionCallback(
                    @NonNull ISoundTriggerHwCallback.RecognitionEvent recognitionEvent,
                    int cookie) {
                Log.d(TAG, String.format("recognitionCallback_2_1(event=%s, cookie=%d)",
                        recognitionEvent, cookie));
                synchronized (SoundTriggerModule.this) {
                    android.media.soundtrigger_middleware.RecognitionEvent aidlEvent =
                            ConversionUtil.hidl2aidlRecognitionEvent(recognitionEvent);
                    aidlEvent.captureSession = mSession.mSessionHandle;
                    try {
                        mCallback.onRecognition(mHandle, aidlEvent);
                    } catch (RemoteException e) {
                        // Dead client will be handled by binderDied() - no need to handle here.
                        // In any case, client callbacks are considered best effort.
                        Log.e(TAG, "Client callback execption.", e);
                    }
                    if (aidlEvent.status
                            != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                        setState(ModelState.LOADED);
                    }
                }
            }

            @Override
            public void phraseRecognitionCallback(
                    @NonNull ISoundTriggerHwCallback.PhraseRecognitionEvent phraseRecognitionEvent,
                    int cookie) {
                Log.d(TAG, String.format("phraseRecognitionCallback_2_1(event=%s, cookie=%d)",
                        phraseRecognitionEvent, cookie));
                synchronized (SoundTriggerModule.this) {
                    android.media.soundtrigger_middleware.PhraseRecognitionEvent aidlEvent =
                            ConversionUtil.hidl2aidlPhraseRecognitionEvent(phraseRecognitionEvent);
                    aidlEvent.common.captureSession = mSession.mSessionHandle;
                    try {
                        mCallback.onPhraseRecognition(mHandle, aidlEvent);
                    } catch (RemoteException e) {
                        // Dead client will be handled by binderDied() - no need to handle here.
                        // In any case, client callbacks are considered best effort.
                        Log.e(TAG, "Client callback execption.", e);
                    }
                    if (aidlEvent.common.status
                            != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                        setState(ModelState.LOADED);
                    }
                }
            }
        }
    }
}
