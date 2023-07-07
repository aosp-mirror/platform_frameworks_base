/**
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.soundtrigger;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.hardware.soundtrigger.ConversionUtil;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.soundtrigger_middleware.IAcknowledgeEvent;
import android.media.soundtrigger_middleware.IInjectGlobalEvent;
import android.media.soundtrigger_middleware.IInjectModelEvent;
import android.media.soundtrigger_middleware.IInjectRecognitionEvent;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ISoundTriggerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Used to inject/observe events when using a fake SoundTrigger HAL for test purposes.
 * Created by {@link SoundTriggerManager#getInjection(Executor, GlobalCallback)}.
 * Only one instance of this class is valid at any given time, old instances will be delivered
 * {@link GlobalCallback#onPreempted()}.
 * @hide
 */
@TestApi
public final class SoundTriggerInstrumentation {

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IInjectGlobalEvent mInjectGlobalEvent = null;

    @GuardedBy("mLock")
    private Map<IBinder, ModelSession> mModelSessionMap = new HashMap<>();
    @GuardedBy("mLock")
    private Map<IBinder, RecognitionSession> mRecognitionSessionMap = new HashMap<>();
    @GuardedBy("mLock")
    private IBinder mClientToken = null;

    private final ISoundTriggerService mService;

    private final GlobalCallback mClientCallback;
    private final Executor mGlobalCallbackExecutor;

    /**
     * Callback interface for un-sessioned events observed from the fake STHAL.
     * Registered upon construction of {@link SoundTriggerInstrumentation}
     * @hide
     */
    @TestApi
    public interface GlobalCallback {
        /**
         * Called when the created {@link SoundTriggerInstrumentation} object is invalidated
         * by another client creating an {@link SoundTriggerInstrumentation} to instrument the
         * fake STHAL. Only one client may inject at a time.
         * All sessions are invalidated, no further events will be received, and no
         * injected events will be delivered.
         */
        default void onPreempted() {}
        /**
         * Called when the STHAL has been restarted by the framework, due to unexpected
         * error conditions.
         * Not called when {@link SoundTriggerInstrumentation#triggerRestart()} is injected.
         */
        default void onRestarted() {}
        /**
         * Called when the framework detaches from the fake HAL.
         * This is not transmitted to real HALs, but it indicates that the
         * framework has flushed its global state.
         */
        default void onFrameworkDetached() {}
        /**
         * Called when a client application attaches to the framework.
         * This is not transmitted to real HALs, but it represents the state of
         * the framework.
         */
        default void onClientAttached() {}
        /**
         * Called when a client application detaches from the framework.
         * This is not transmitted to real HALs, but it represents the state of
         * the framework.
         */
        default void onClientDetached() {}
        /**
         * Called when the fake HAL receives a model load from the framework.
         * @param modelSession - A session which exposes additional injection
         *                       functionality associated with the newly loaded
         *                       model. See {@link ModelSession}.
         */
        void onModelLoaded(@NonNull ModelSession modelSession);
    }

    /**
     * Callback for HAL events related to a loaded model. Register with
     * {@link ModelSession#setModelCallback(Executor, ModelCallback)}
     * Note, callbacks will not be delivered for events triggered by the injection.
     * @hide
     */
    @TestApi
    public interface ModelCallback {
        /**
         * Called when the model associated with the {@link ModelSession} this callback
         * was registered for was unloaded by the framework.
         */
        default void onModelUnloaded() {}
        /**
         * Called when the model associated with the {@link ModelSession} this callback
         * was registered for receives a set parameter call from the framework.
         * @param param - Parameter being set.
         *                 See {@link SoundTrigger.ModelParamTypes}
         * @param value - Value the model parameter was set to.
         */
        default void onParamSet(@SoundTrigger.ModelParamTypes int param, int value) {}
        /**
         * Called when the model associated with the {@link ModelSession} this callback
         * was registered for receives a recognition start request.
         * @param recognitionSession - A session which exposes additional injection
         *                             functionality associated with the newly started
         *                             recognition. See {@link RecognitionSession}
         */
        void onRecognitionStarted(@NonNull RecognitionSession recognitionSession);
    }

    /**
     * Callback for HAL events related to a started recognition. Register with
     * {@link RecognitionSession#setRecognitionCallback(Executor, RecognitionCallback)}
     * Note, callbacks will not be delivered for events triggered by the injection.
     * @hide
     */
    @TestApi
    public interface RecognitionCallback {
        /**
         * Called when the recognition associated with the {@link RecognitionSession} this
         * callback was registered for was stopped by the framework.
         */
        void onRecognitionStopped();
    }

    /**
     * Session associated with a loaded model in the fake STHAL.
     * Can be used to query details about the loaded model, register a callback for future
     * model events, or trigger HAL events associated with a loaded model.
     * This session is invalid once the model is unloaded, caused by a
     * {@link ModelSession#triggerUnloadModel()},
     * the client unloading recognition, or if a {@link GlobalCallback#onRestarted()} is
     * received.
     * Further injections on an invalidated session will not be respected, and no future
     * callbacks will be delivered.
     * @hide
     */
    @TestApi
    public class ModelSession {

        /**
         * Trigger the HAL to preemptively unload the model associated with this session.
         * Typically occurs when a higher priority model is loaded which utilizes the same
         * resources.
         */
        public void triggerUnloadModel() {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                try {
                    mInjectModelEvent.triggerUnloadModel();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mModelSessionMap.remove(mInjectModelEvent.asBinder());
            }
        }

        /**
         * Get the {@link SoundTriggerManager.Model} associated with this session.
         * @return - The model associated with this session.
         */
        public @NonNull SoundTriggerManager.Model getSoundModel() {
            return mModel;
        }

        /**
         * Get the list of {@link SoundTrigger.Keyphrase} associated with this session.
         * @return - The keyphrases associated with this session.
         */
        public @NonNull List<SoundTrigger.Keyphrase> getPhrases() {
            if (mPhrases == null) {
                return new ArrayList<>();
            } else {
                return new ArrayList<>(Arrays.asList(mPhrases));
            }
        }

        /**
         * Get whether this model is of keyphrase type.
         * @return - true if the model is a keyphrase model, false otherwise
         */
        public boolean isKeyphrase() {
            return (mPhrases != null);
        }

        /**
         * Registers the model callback associated with this session. Events associated
         * with this model session will be reported via this callback.
         * See {@link ModelCallback}
         * @param executor - Executor which the callback is dispatched on
         * @param callback - Model callback for reporting model session events.
         */
        public void setModelCallback(@NonNull @CallbackExecutor Executor executor, @NonNull
                ModelCallback callback) {
            Objects.requireNonNull(callback);
            Objects.requireNonNull(executor);
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (mModelCallback == null) {
                    for (var droppedConsumer : mDroppedConsumerList) {
                        executor.execute(() -> droppedConsumer.accept(callback));
                    }
                    mDroppedConsumerList.clear();
                }
                mModelCallback = callback;
                mModelExecutor = executor;
            }
        }

        /**
         * Clear the model callback associated with this session, if any has been
         * set by {@link #setModelCallback(Executor, ModelCallback)}.
         */
        public void clearModelCallback() {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                mModelCallback = null;
                mModelExecutor = null;
            }
        }

        private ModelSession(SoundModel model, Phrase[] phrases,
                IInjectModelEvent injection) {
            mModel = SoundTriggerManager.Model.create(UUID.fromString(model.uuid),
                    UUID.fromString(model.vendorUuid),
                    ConversionUtil.sharedMemoryToByteArray(model.data, model.dataSize));
            if (phrases != null) {
                mPhrases = new SoundTrigger.Keyphrase[phrases.length];
                int i = 0;
                for (var phrase : phrases) {
                    mPhrases[i++] = ConversionUtil.aidl2apiPhrase(phrase);
                }
            } else {
                mPhrases = null;
            }
            mInjectModelEvent = injection;
        }

        private void wrap(Consumer<ModelCallback> consumer) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (mModelCallback != null) {
                    final ModelCallback callback = mModelCallback;
                    mModelExecutor.execute(() -> consumer.accept(callback));
                } else {
                    mDroppedConsumerList.add(consumer);
                }
            }
        }

        private final SoundTriggerManager.Model mModel;
        private final SoundTrigger.Keyphrase[] mPhrases;
        private final IInjectModelEvent mInjectModelEvent;

        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private ModelCallback mModelCallback = null;
        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private Executor mModelExecutor = null;
        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private final List<Consumer<ModelCallback>> mDroppedConsumerList = new ArrayList<>();
    }

    /**
     * Session associated with a recognition start in the fake STHAL.
     * Can be used to get information about the started recognition, register a callback
     * for future events associated with this recognition, and triggering
     * recognition events or aborts.
     * This session is invalid once the recognition is stopped, caused by a
     * {@link RecognitionSession#triggerAbortRecognition()},
     * {@link RecognitionSession#triggerRecognitionEvent(byte[], List)},
     * the client stopping recognition, or any operation which invalidates the
     * {@link ModelSession} which the session was created from.
     * Further injections on an invalidated session will not be respected, and no future
     * callbacks will be delivered.
     * @hide
     */
    @TestApi
    public class RecognitionSession {

        /**
         * Get an integer token representing the audio session associated with this
         * recognition in the STHAL.
         * @return - The session token.
         */
        public int getAudioSession() {
            return mAudioSession;
        }

        /**
         * Get the recognition config used to start this recognition.
         * @return - The config passed to the HAL for startRecognition.
         */
        public @NonNull SoundTrigger.RecognitionConfig getRecognitionConfig() {
            return mRecognitionConfig;
        }

        /**
         * Trigger a recognition in the fake STHAL.
         * @param data - The opaque data buffer included in the recognition event.
         * @param phraseExtras - Keyphrase metadata included in the event. The
         *                       event must include metadata for the keyphrase id
         *                       associated with this model to be received by the
         *                       client application.
         */
        public void triggerRecognitionEvent(@NonNull byte[] data, @Nullable
                List<SoundTrigger.KeyphraseRecognitionExtra> phraseExtras) {
            PhraseRecognitionExtra[] converted = null;
            if (phraseExtras != null) {
                converted = new PhraseRecognitionExtra[phraseExtras.size()];
                int i = 0;
                for (var phraseExtra : phraseExtras) {
                    converted[i++] = ConversionUtil.api2aidlPhraseRecognitionExtra(phraseExtra);
                }
            }
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                mRecognitionSessionMap.remove(mInjectRecognitionEvent.asBinder());
                try {
                    mInjectRecognitionEvent.triggerRecognitionEvent(data, converted);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

        /**
         * Trigger an abort recognition event in the fake HAL. This represents a
         * preemptive ending of the recognition session by the HAL, despite no
         * recognition detection. Typically occurs during contention for microphone
         * usage, or if model limits are hit.
         * See {@link SoundTriggerInstrumentation#setResourceContention(boolean)} to block
         * subsequent downward calls for contention reasons.
         */
        public void triggerAbortRecognition() {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                mRecognitionSessionMap.remove(mInjectRecognitionEvent.asBinder());
                try {
                    mInjectRecognitionEvent.triggerAbortRecognition();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

         /**
         * Registers the recognition callback associated with this session. Events associated
         * with this recognition session will be reported via this callback.
         * See {@link RecognitionCallback}
         * @param executor - Executor which the callback is dispatched on
         * @param callback - Recognition callback for reporting recognition session events.
         */
        public void setRecognitionCallback(@NonNull @CallbackExecutor Executor executor,
                @NonNull RecognitionCallback callback) {
            Objects.requireNonNull(callback);
            Objects.requireNonNull(executor);
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (mRecognitionCallback == null) {
                    for (var droppedConsumer : mDroppedConsumerList) {
                        executor.execute(() -> droppedConsumer.accept(callback));
                    }
                    mDroppedConsumerList.clear();
                }
                mRecognitionCallback = callback;
                mRecognitionExecutor = executor;

            }
        }

        /**
         * Clear the recognition callback associated with this session, if any has been
         * set by {@link #setRecognitionCallback(Executor, RecognitionCallback)}.
         */
        public void clearRecognitionCallback() {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                mRecognitionCallback = null;
                mRecognitionExecutor = null;
            }
        }

        private RecognitionSession(int audioSession,
                RecognitionConfig recognitionConfig,
                IInjectRecognitionEvent injectRecognitionEvent) {
            mAudioSession = audioSession;
            mRecognitionConfig = ConversionUtil.aidl2apiRecognitionConfig(recognitionConfig);
            mInjectRecognitionEvent = injectRecognitionEvent;
        }

        private void wrap(Consumer<RecognitionCallback> consumer) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (mRecognitionCallback != null) {
                    final RecognitionCallback callback = mRecognitionCallback;
                    mRecognitionExecutor.execute(() -> consumer.accept(callback));
                } else {
                    mDroppedConsumerList.add(consumer);
                }
            }
        }

        private final int mAudioSession;
        private final SoundTrigger.RecognitionConfig mRecognitionConfig;
        private final IInjectRecognitionEvent mInjectRecognitionEvent;

        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private Executor mRecognitionExecutor = null;
        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private RecognitionCallback mRecognitionCallback = null;
        @GuardedBy("SoundTriggerInstrumentation.this.mLock")
        private final List<Consumer<RecognitionCallback>> mDroppedConsumerList = new ArrayList<>();
    }

    // Implementation of injection interface passed to the HAL.
    // This class will re-associate events received on this callback interface
    // with sessions, to avoid staleness issues.
    private class Injection extends ISoundTriggerInjection.Stub {
        @Override
        public void registerGlobalEventInjection(IInjectGlobalEvent globalInjection) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                mInjectGlobalEvent = globalInjection;
            }
        }

        @Override
        public void onSoundModelLoaded(SoundModel model, @Nullable Phrase[] phrases,
                            IInjectModelEvent modelInjection, IInjectGlobalEvent globalSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (globalSession.asBinder() != mInjectGlobalEvent.asBinder()) return;
                ModelSession modelSession = new ModelSession(model, phrases, modelInjection);
                mModelSessionMap.put(modelInjection.asBinder(), modelSession);
                mGlobalCallbackExecutor.execute(() -> mClientCallback.onModelLoaded(modelSession));
            }
        }

        @Override
        public void onSoundModelUnloaded(IInjectModelEvent modelSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                ModelSession clientModelSession = mModelSessionMap.remove(modelSession.asBinder());
                if (clientModelSession == null) return;
                clientModelSession.wrap((ModelCallback cb) -> cb.onModelUnloaded());
            }
        }

        @Override
        public void onRecognitionStarted(int audioSessionHandle, RecognitionConfig config,
                IInjectRecognitionEvent recognitionInjection, IInjectModelEvent modelSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                ModelSession clientModelSession = mModelSessionMap.get(modelSession.asBinder());
                if (clientModelSession == null) return;
                RecognitionSession recogSession = new RecognitionSession(
                        audioSessionHandle, config, recognitionInjection);
                mRecognitionSessionMap.put(recognitionInjection.asBinder(), recogSession);
                clientModelSession.wrap((ModelCallback cb) ->
                        cb.onRecognitionStarted(recogSession));
            }
        }

        @Override
        public void onRecognitionStopped(IInjectRecognitionEvent recognitionSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                RecognitionSession clientRecognitionSession =
                        mRecognitionSessionMap.remove(recognitionSession.asBinder());
                if (clientRecognitionSession == null) return;
                clientRecognitionSession.wrap((RecognitionCallback cb)
                        -> cb.onRecognitionStopped());
            }
        }

        @Override
        public void onParamSet(int modelParam, int value, IInjectModelEvent modelSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                ModelSession clientModelSession = mModelSessionMap.get(modelSession.asBinder());
                if (clientModelSession == null) return;
                clientModelSession.wrap((ModelCallback cb) -> cb.onParamSet(modelParam, value));
            }
        }


        @Override
        public void onRestarted(IInjectGlobalEvent globalSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (globalSession.asBinder() != mInjectGlobalEvent.asBinder()) return;
                mRecognitionSessionMap.clear();
                mModelSessionMap.clear();
                mGlobalCallbackExecutor.execute(() -> mClientCallback.onRestarted());
            }
        }

        @Override
        public void onFrameworkDetached(IInjectGlobalEvent globalSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (globalSession.asBinder() != mInjectGlobalEvent.asBinder()) return;
                mGlobalCallbackExecutor.execute(() -> mClientCallback.onFrameworkDetached());
            }
        }

        @Override
        public void onClientAttached(IBinder token, IInjectGlobalEvent globalSession) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (globalSession.asBinder() != mInjectGlobalEvent.asBinder()) return;
                mClientToken = token;
                mGlobalCallbackExecutor.execute(() -> mClientCallback.onClientAttached());
            }
        }

        @Override
        public void onClientDetached(IBinder token) {
            synchronized (SoundTriggerInstrumentation.this.mLock) {
                if (token != mClientToken) return;
                mClientToken = null;
                mGlobalCallbackExecutor.execute(() -> mClientCallback.onClientDetached());
            }
        }

        @Override
        public void onPreempted() {
            // This is always valid, independent of session
            mGlobalCallbackExecutor.execute(() -> mClientCallback.onPreempted());
            // Callbacks will no longer be delivered, and injection will be silently dropped.
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_SOUND_TRIGGER)
    public SoundTriggerInstrumentation(ISoundTriggerService service,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull GlobalCallback callback) {
        mClientCallback = Objects.requireNonNull(callback);
        mGlobalCallbackExecutor = Objects.requireNonNull(executor);
        mService = service;
        try {
            service.attachInjection(new Injection());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulate a HAL restart, typically caused by the framework on an unexpected error,
     * or a restart of the core audio HAL.
     * Application sessions will be detached, and all state will be cleared. The framework
     * will re-attach to the HAL following restart.
     * @hide
     */
    @TestApi
    public void triggerRestart() {
        synchronized (mLock) {
            if (mInjectGlobalEvent == null) {
                throw new IllegalStateException(
                        "Attempted to trigger HAL restart before registration");
            }
            try {
                mInjectGlobalEvent.triggerRestart();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Trigger a resource available callback from the fake SoundTrigger HAL to the framework.
     * This callback notifies the framework that methods which previously failed due to
     * resource contention may now succeed.
     * @hide
     */
    @TestApi
    public void triggerOnResourcesAvailable() {
        synchronized (mLock) {
            if (mInjectGlobalEvent == null) {
                throw new IllegalStateException(
                        "Attempted to trigger HAL resources available before registration");
            }
            try {
                mInjectGlobalEvent.triggerOnResourcesAvailable();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Simulate resource contention, similar to when HAL which does not
     * support concurrent capture opens a capture stream, or when a HAL
     * has reached its maximum number of models.
     * Subsequent model loads and recognition starts will gracefully error.
     * Since this call does not trigger a callback through the framework, the
     * call will block until the fake HAL has acknowledged the state change.
     * @param isResourceContended - true to enable contention, false to return
     *                              to normal functioning.
     * @hide
     */
    @TestApi
    public void setResourceContention(boolean isResourceContended) {
        synchronized (mLock) {
            if (mInjectGlobalEvent == null) {
                throw new IllegalStateException("Injection interface not set up");
            }
            IInjectGlobalEvent current = mInjectGlobalEvent;
            final CountDownLatch signal = new CountDownLatch(1);
            try {
                current.setResourceContention(isResourceContended, new IAcknowledgeEvent.Stub() {
                    @Override
                    public void eventReceived() {
                        signal.countDown();
                    }
                });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            // Block until we get a callback from the service that our request was serviced.
            try {
                // Rely on test timeout if we don't get a response.
                signal.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Simulate a phone call for {@link com.android.server.soundtrigger.SoundTriggerService}.
     * If the phone call state changes, the service will be notified to respond.
     * The service should pause recognition for the duration of the call.
     *
     * @param isInPhoneCall - {@code true} to cause the SoundTriggerService to
     * see the phone call state as off-hook. {@code false} to cause the service to
     * see the state as normal.
     * @hide
     */
    @TestApi
    public void setInPhoneCallState(boolean isInPhoneCall) {
        try {
            mService.setInPhoneCallState(isInPhoneCall);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

