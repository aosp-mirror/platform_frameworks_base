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
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseRecognitionExtra;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundModelType;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.media.soundtrigger_middleware.Status;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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
 * <li>Recovery from driver death is supported.</li>
 * <li>RemoteExceptions thrown by the driver are treated as RuntimeExceptions - they are not
 * considered recoverable faults and should not occur in a properly functioning system.
 * <li>There is no binder instance associated with this implementation. Do not call asBinder().
 * <li>The implementation may throw a {@link RecoverableException} to indicate non-fatal,
 * recoverable faults. The error code would one of the
 * {@link android.media.soundtrigger_middleware.Status} constants. Any other exception
 * thrown should be regarded as a bug in the implementation or one of its dependencies
 * (assuming correct usage).
 * <li>The implementation is designed for testability by featuring dependency injection (the
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
class SoundTriggerModule implements IHwBinder.DeathRecipient, ISoundTriggerHw2.GlobalCallback {
    static private final String TAG = "SoundTriggerModule";
    @NonNull private final HalFactory mHalFactory;
    @NonNull private ISoundTriggerHw2 mHalService;
    @NonNull private final SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider;
    private final Set<Session> mActiveSessions = new HashSet<>();
    private int mNumLoadedModels = 0;
    private final SoundTriggerModuleProperties mProperties;
    private boolean mRecognitionAvailable;

    /**
     * Ctor.
     *
     * @param halFactory A factory for the underlying HAL driver.
     */
    SoundTriggerModule(@NonNull HalFactory halFactory,
            @NonNull SoundTriggerMiddlewareImpl.AudioSessionProvider audioSessionProvider) {
        assert halFactory != null;
        mHalFactory = halFactory;
        mAudioSessionProvider = audioSessionProvider;

        attachToHal();
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
    ISoundTriggerModule attach(@NonNull ISoundTriggerCallback callback) {
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
    void setExternalCaptureState(boolean active) {
        // We should never invoke callbacks while holding the lock, since this may deadlock with
        // forward calls. Thus, we first gather all the callbacks we need to invoke while holding
        // the lock, but invoke them after releasing it.
        List<Runnable> callbacks = new LinkedList<>();

        synchronized (this) {
            if (mProperties.concurrentCapture) {
                // If we support concurrent capture, we don't care about any of this.
                return;
            }
            mRecognitionAvailable = !active;
            if (!mRecognitionAvailable) {
                // Our module does not support recognition while a capture is active -
                // need to abort all active recognitions.
                for (Session session : mActiveSessions) {
                    session.abortActiveRecognitions(callbacks);
                }
            }
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
        for (Session session : mActiveSessions) {
            session.notifyRecognitionAvailability();
        }
    }

    @Override
    public void serviceDied(long cookie) {
        Log.w(TAG, "Underlying HAL driver died.");
        List<ISoundTriggerCallback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(mActiveSessions.size());
            for (Session session : mActiveSessions) {
                callbacks.add(session.moduleDied());
            }
            mActiveSessions.clear();
            reset();
        }
        // Trigger the callbacks outside of the lock to avoid deadlocks.
        for (ISoundTriggerCallback callback : callbacks) {
            try {
                callback.onModuleDied();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Resets the transient state of this object.
     */
    private void reset() {
        attachToHal();
        // We conservatively assume that external capture is active until explicitly told otherwise.
        mRecognitionAvailable = mProperties.concurrentCapture;
        mNumLoadedModels = 0;
    }

    /**
     * Attached to the HAL service via factory.
     */
    private void attachToHal() {
        mHalService = new SoundTriggerHw2Enforcer(
                new SoundTriggerHw2Watchdog(
                        new SoundTriggerHw2Compat(mHalFactory.create())));
        mHalService.linkToDeath(this, 0);
        mHalService.registerCallback(this);
    }

    /**
     * Remove session from the list of active sessions.
     *
     * @param session The session to remove.
     */
    private void removeSession(@NonNull Session session) {
        mActiveSessions.remove(session);
    }

    @Override
    public void tryAgain() {
        // TODO: Implement
        throw new RuntimeException("Implement me");
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
        private final Map<Integer, Model> mLoadedModels = new HashMap<>();

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
            synchronized (SoundTriggerModule.this) {
                if (mCallback == null) {
                    return;
                }
                removeSession(this);
                mCallback = null;
            }
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            // We must do this outside the lock, to avoid possible deadlocks with the remote process
            // that provides the audio sessions, which may also be calling into us.
            SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession =
                    mAudioSessionProvider.acquireSession();

            try {
                synchronized (SoundTriggerModule.this) {
                    checkValid();
                    if (mNumLoadedModels == mProperties.maxSoundModels) {
                        throw new RecoverableException(Status.RESOURCE_CONTENTION,
                                "Maximum number of models loaded.");
                    }
                    Model loadedModel = new Model();
                    int result = loadedModel.load(model, audioSession);
                    ++mNumLoadedModels;
                    return result;
                }
            } catch (Exception e) {
                // We must do this outside the lock, to avoid possible deadlocks with the remote
                // process that provides the audio sessions, which may also be calling into us.
                try {
                    mAudioSessionProvider.releaseSession(audioSession.mSessionHandle);
                } catch (Exception ee) {
                    Log.e(TAG, "Failed to release session.", ee);
                }
                throw e;
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            // We must do this outside the lock, to avoid possible deadlocks with the remote process
            // that provides the audio sessions, which may also be calling into us.
            SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession =
                    mAudioSessionProvider.acquireSession();

            try {
                synchronized (SoundTriggerModule.this) {
                    checkValid();
                    if (mNumLoadedModels == mProperties.maxSoundModels) {
                        throw new RecoverableException(Status.RESOURCE_CONTENTION,
                                "Maximum number of models loaded.");
                    }
                    Model loadedModel = new Model();
                    int result = loadedModel.load(model, audioSession);
                    ++mNumLoadedModels;
                    Log.d(TAG, String.format("loadPhraseModel()->%d", result));
                    return result;
                }
            } catch (Exception e) {
                // We must do this outside the lock, to avoid possible deadlocks with the remote
                // process that provides the audio sessions, which may also be calling into us.
                try {
                    mAudioSessionProvider.releaseSession(audioSession.mSessionHandle);
                } catch (Exception ee) {
                    Log.e(TAG, "Failed to release session.", ee);
                }
                throw e;
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            int sessionId;
            synchronized (SoundTriggerModule.this) {
                checkValid();
                sessionId = mLoadedModels.get(modelHandle).unload();
                --mNumLoadedModels;
            }

            // We must do this outside the lock, to avoid possible deadlocks with the remote process
            // that provides the audio sessions, which may also be calling into us.
            mAudioSessionProvider.releaseSession(sessionId);
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            // We should never invoke callbacks while holding the lock, since this may deadlock with
            // forward calls. Thus, we first gather all the callbacks we need to invoke while holding
            // the lock, but invoke them after releasing it.
            List<Runnable> callbacks = new LinkedList<>();

            synchronized (SoundTriggerModule.this) {
                checkValid();
                mLoadedModels.get(modelHandle).startRecognition(config, callbacks);
            }

            for (Runnable callback : callbacks) {
                callback.run();
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            synchronized (SoundTriggerModule.this) {
                mLoadedModels.get(modelHandle).stopRecognition();
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            synchronized (SoundTriggerModule.this) {
                checkValid();
                mLoadedModels.get(modelHandle).forceRecognitionEvent();
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value) {
            synchronized (SoundTriggerModule.this) {
                checkValid();
                mLoadedModels.get(modelHandle).setParameter(modelParam, value);
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) {
            synchronized (SoundTriggerModule.this) {
                checkValid();
                return mLoadedModels.get(modelHandle).getParameter(modelParam);
            }
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam) {
            synchronized (SoundTriggerModule.this) {
                checkValid();
                return mLoadedModels.get(modelHandle).queryModelParameterSupport(modelParam);
            }
        }

        /**
         * Abort all currently active recognitions.
         * @param callbacks Will be appended with a list of callbacks that need to be invoked
         *                  after this method returns, without holding the module lock.
         */
        private void abortActiveRecognitions(@NonNull List<Runnable> callbacks) {
            for (Model model : mLoadedModels.values()) {
                model.abortActiveRecognition(callbacks);
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

        /**
         * The underlying module HAL is dead.
         * @return The client callback that needs to be invoked to notify the client.
         */
        private ISoundTriggerCallback moduleDied() {
            ISoundTriggerCallback callback = mCallback;
            mCallback = null;
            return callback;
        }

        private void checkValid() {
            if (mCallback == null) {
                throw new RecoverableException(Status.DEAD_OBJECT);
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
        private class Model implements ISoundTriggerHw2.ModelCallback {
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

            private int load(@NonNull SoundModel model,
                    SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession) {
                mModelType = model.type;
                mSession = audioSession;
                ISoundTriggerHw.SoundModel hidlModel = ConversionUtil.aidl2hidlSoundModel(model);

                mHandle = mHalService.loadSoundModel(hidlModel, this);
                setState(ModelState.LOADED);
                mLoadedModels.put(mHandle, this);
                return mHandle;
            }

            private int load(@NonNull PhraseSoundModel model,
                    SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession) {
                mModelType = model.common.type;
                mSession = audioSession;
                ISoundTriggerHw.PhraseSoundModel hidlModel =
                        ConversionUtil.aidl2hidlPhraseSoundModel(model);

                mHandle = mHalService.loadPhraseSoundModel(hidlModel, this);

                setState(ModelState.LOADED);
                mLoadedModels.put(mHandle, this);
                return mHandle;
            }

            /**
             * Unloads the model.
             * @return The audio session handle.
             */
            private int unload() {
                mHalService.unloadSoundModel(mHandle);
                mLoadedModels.remove(mHandle);
                return mSession.mSessionHandle;
            }

            private void startRecognition(@NonNull RecognitionConfig config,
                    @NonNull List<Runnable> callbacks) {
                if (!mRecognitionAvailable) {
                    // Recognition is unavailable - send an abort event immediately.
                    callbacks.add(this::notifyAbort);
                    return;
                }
                android.hardware.soundtrigger.V2_3.RecognitionConfig hidlConfig =
                        ConversionUtil.aidl2hidlRecognitionConfig(config);
                hidlConfig.base.header.captureDevice = mSession.mDeviceHandle;
                hidlConfig.base.header.captureHandle = mSession.mIoHandle;
                mHalService.startRecognition(mHandle, hidlConfig);
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

            /**
             * Abort the recognition, if active.
             * @param callbacks Will be appended with a list of callbacks that need to be invoked
             *                  after this method returns, without holding the module lock.
             */
            private void abortActiveRecognition(List<Runnable> callbacks) {
                // If we're inactive, do nothing.
                if (getState() != ModelState.ACTIVE) {
                    return;
                }
                // Stop recognition.
                stopRecognition();

                // Notify the client that recognition has been aborted.
                callbacks.add(this::notifyAbort);
            }

            /** Notify the client that recognition has been aborted. */
            private void notifyAbort() {
                try {
                    switch (mModelType) {
                        case SoundModelType.GENERIC: {
                            android.media.soundtrigger_middleware.RecognitionEvent event =
                                    newEmptyRecognitionEvent();
                            event.status =
                                    android.media.soundtrigger_middleware.RecognitionStatus.ABORTED;
                            event.type = SoundModelType.GENERIC;
                            mCallback.onRecognition(mHandle, event);
                        }
                        break;

                        case SoundModelType.KEYPHRASE: {
                            android.media.soundtrigger_middleware.PhraseRecognitionEvent event =
                                    newEmptyPhraseRecognitionEvent();
                            event.common.status =
                                    android.media.soundtrigger_middleware.RecognitionStatus.ABORTED;
                            event.common.type = SoundModelType.KEYPHRASE;
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
                    @NonNull ISoundTriggerHwCallback.RecognitionEvent recognitionEvent) {
                RecognitionEvent aidlEvent =
                        ConversionUtil.hidl2aidlRecognitionEvent(recognitionEvent);
                aidlEvent.captureSession = mSession.mSessionHandle;
                synchronized (SoundTriggerModule.this) {
                    if (aidlEvent.status != RecognitionStatus.FORCED) {
                        setState(ModelState.LOADED);
                    }
                }
                // The callback must be invoked outside of the lock.
                try {
                    mCallback.onRecognition(mHandle, aidlEvent);
                } catch (RemoteException e) {
                    // We're not expecting any exceptions here.
                    throw e.rethrowAsRuntimeException();
                }
            }

            @Override
            public void phraseRecognitionCallback(@NonNull
                            ISoundTriggerHwCallback.PhraseRecognitionEvent phraseRecognitionEvent) {
                PhraseRecognitionEvent aidlEvent =
                        ConversionUtil.hidl2aidlPhraseRecognitionEvent(phraseRecognitionEvent);
                aidlEvent.common.captureSession = mSession.mSessionHandle;

                synchronized (SoundTriggerModule.this) {
                    if (aidlEvent.common.status != RecognitionStatus.FORCED) {
                        setState(ModelState.LOADED);
                    }
                }

                // The callback must be invoked outside of the lock.
                try {
                    mCallback.onPhraseRecognition(mHandle, aidlEvent);
                } catch (RemoteException e) {
                    // We're not expecting any exceptions here.
                    throw e.rethrowAsRuntimeException();
                }
            }

            @Override
            public void modelUnloaded(int modelHandle) {
                // TODO: Implement
                throw new RuntimeException("Implement me");
            }
        }
    }

    /**
     * Creates a default-initialized recognition event.
     *
     * Non-nullable object fields are default constructed.
     * Non-nullable array fields are initialized to 0 length.
     *
     * @return The event.
     */
    private static RecognitionEvent newEmptyRecognitionEvent() {
        RecognitionEvent result = new RecognitionEvent();
        result.data = new byte[0];
        return result;
    }

    /**
     * Creates a default-initialized phrase recognition event.
     *
     * Non-nullable object fields are default constructed.
     * Non-nullable array fields are initialized to 0 length.
     *
     * @return The event.
     */
    private static PhraseRecognitionEvent newEmptyPhraseRecognitionEvent() {
        PhraseRecognitionEvent result = new PhraseRecognitionEvent();
        result.common = newEmptyRecognitionEvent();
        result.phraseExtras = new PhraseRecognitionExtra[0];
        return result;
    }
}
