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
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * {@link android.media.soundtrigger.Status} constants. Any other exception thrown should be
 * regarded as a bug in the implementation or one of its dependencies (assuming correct usage).
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
class SoundTriggerModule implements IBinder.DeathRecipient, ISoundTriggerHal.GlobalCallback {
    static private final String TAG = "SoundTriggerModule";
    @NonNull private final HalFactory mHalFactory;
    @NonNull private ISoundTriggerHal mHalService;
    @NonNull private final SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider;
    private final Set<Session> mActiveSessions = new HashSet<>();
    private Properties mProperties;

    /**
     * Ctor.
     *
     * @param halFactory - A factory for the underlying HAL driver.
     * @param audioSessionProvider - Creates a session token + device id/port pair used to
     * associate recognition events with the audio stream used to access data.
     */
    SoundTriggerModule(@NonNull HalFactory halFactory,
            @NonNull SoundTriggerMiddlewareImpl.AudioSessionProvider audioSessionProvider) {
        mHalFactory = Objects.requireNonNull(halFactory);
        mAudioSessionProvider = Objects.requireNonNull(audioSessionProvider);

        attachToHal();
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
    Properties getProperties() {
        return mProperties;
    }

    @Override
    public void binderDied() {
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
        mHalService.detach();
        attachToHal();
    }

    /**
     * Attached to the HAL service via factory.
     */
    private void attachToHal() {
        mHalService = new SoundTriggerHalEnforcer(
                new SoundTriggerHalWatchdog(
                    new SoundTriggerDuplicateModelHandler(mHalFactory.create())));
        mHalService.linkToDeath(this);
        mHalService.registerCallback(this);
        mProperties = mHalService.getProperties();
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
    public void onResourcesAvailable() {
        List<ISoundTriggerCallback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(mActiveSessions.size());
            for (Session session : mActiveSessions) {
                callbacks.add(session.mCallback);
            }
        }
        // Trigger the callbacks outside of the lock to avoid deadlocks.
        for (ISoundTriggerCallback callback : callbacks) {
            try {
                callback.onResourcesAvailable();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
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
        private final IBinder mToken = new Binder();
        private final Map<Integer, Model> mLoadedModels = new HashMap<>();

        /**
         * Ctor.
         *
         * @param callback The client callback interface.
         */
        private Session(@NonNull ISoundTriggerCallback callback) {
            mCallback = callback;
            mHalService.clientAttached(mToken);
        }

        @Override
        public void detach() {
            synchronized (SoundTriggerModule.this) {
                if (mCallback == null) {
                    return;
                }
                removeSession(this);
                mCallback = null;
                mHalService.clientDetached(mToken);
            }
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            synchronized (SoundTriggerModule.this) {
                SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession =
                        mAudioSessionProvider.acquireSession();
                try {
                    checkValid();
                    Model loadedModel = new Model();
                    return loadedModel.load(model, audioSession);
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
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            synchronized (SoundTriggerModule.this) {
                SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession =
                        mAudioSessionProvider.acquireSession();
                try {
                    checkValid();
                    Model loadedModel = new Model();
                    int result = loadedModel.load(model, audioSession);
                    Log.d(TAG, String.format("loadPhraseModel()->%d", result));
                    return result;
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
        }

        @Override
        public void unloadModel(int modelHandle) {
            synchronized (SoundTriggerModule.this) {
                int sessionId;
                checkValid();
                sessionId = mLoadedModels.get(modelHandle).unload();
                mAudioSessionProvider.releaseSession(sessionId);
            }
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            synchronized (SoundTriggerModule.this) {
                checkValid();
                mLoadedModels.get(modelHandle).startRecognition(config);
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            Model model;
            synchronized (SoundTriggerModule.this) {
                checkValid();
                model = mLoadedModels.get(modelHandle);
            }
            model.stopRecognition();
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
        private class Model implements ISoundTriggerHal.ModelCallback {
            public int mHandle;
            private ModelState mState = ModelState.INIT;
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
                mSession = audioSession;
                mHandle = mHalService.loadSoundModel(model, this);
                setState(ModelState.LOADED);
                mLoadedModels.put(mHandle, this);
                return mHandle;
            }

            private int load(@NonNull PhraseSoundModel model,
                    SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession audioSession) {
                mSession = audioSession;
                mHandle = mHalService.loadPhraseSoundModel(model, this);

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

            private void startRecognition(@NonNull RecognitionConfig config) {
                mHalService.startRecognition(mHandle, mSession.mDeviceHandle,
                        mSession.mIoHandle, config);
                setState(ModelState.ACTIVE);
            }

            private void stopRecognition() {
                synchronized (SoundTriggerModule.this) {
                    if (getState() == ModelState.LOADED) {
                        // This call is idempotent in order to avoid races.
                        return;
                    }
                }
                mHalService.stopRecognition(mHandle);
                synchronized (SoundTriggerModule.this) {
                    setState(ModelState.LOADED);
                }
            }

            /** Request a forced recognition event. Will do nothing if recognition is inactive. */
            private void forceRecognitionEvent() {
                if (getState() != ModelState.ACTIVE) {
                    // This call is idempotent in order to avoid races.
                    return;
                }
                mHalService.forceRecognitionEvent(mHandle);
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
                return mHalService.queryParameter(mHandle, modelParam);
            }

            @Override
            public void recognitionCallback(int modelHandle,
                    @NonNull RecognitionEventSys event) {
                ISoundTriggerCallback callback;
                synchronized (SoundTriggerModule.this) {
                    if (!event.recognitionEvent.recognitionStillActive) {
                        setState(ModelState.LOADED);
                    }
                    callback = mCallback;
                }
                // The callback must be invoked outside of the lock.
                try {
                    if (callback != null) {
                        callback.onRecognition(mHandle, event, mSession.mSessionHandle);
                    }
                } catch (RemoteException e) {
                    // We're not expecting any exceptions here.
                    throw e.rethrowAsRuntimeException();
                }
            }

            @Override
            public void phraseRecognitionCallback(int modelHandle,
                    @NonNull PhraseRecognitionEventSys event) {
                ISoundTriggerCallback callback;
                synchronized (SoundTriggerModule.this) {
                    if (!event.phraseRecognitionEvent.common.recognitionStillActive) {
                        setState(ModelState.LOADED);
                    }
                    callback = mCallback;
                }

                // The callback must be invoked outside of the lock.
                try {
                    if (callback != null) {
                        mCallback.onPhraseRecognition(mHandle, event, mSession.mSessionHandle);
                    }
                } catch (RemoteException e) {
                    // We're not expecting any exceptions here.
                    throw e.rethrowAsRuntimeException();
                }
            }

            @Override
            public void modelUnloaded(int modelHandle) {
                ISoundTriggerCallback callback;
                synchronized (SoundTriggerModule.this) {
                    callback = mCallback;
                }

                // The callback must be invoked outside of the lock.
                try {
                    if (callback != null) {
                        callback.onModelUnloaded(modelHandle);
                    }
                } catch (RemoteException e) {
                    // We're not expecting any exceptions here.
                    throw e.rethrowAsRuntimeException();
                }
            }
        }
    }
}
