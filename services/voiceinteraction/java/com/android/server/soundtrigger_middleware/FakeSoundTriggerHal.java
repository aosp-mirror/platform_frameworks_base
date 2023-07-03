/*
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

package com.android.server.soundtrigger_middleware;

import android.annotation.Nullable;
import android.hardware.soundtrigger3.ISoundTriggerHw;
import android.hardware.soundtrigger3.ISoundTriggerHwCallback;
import android.hardware.soundtrigger3.ISoundTriggerHwGlobalCallback;
import android.media.soundtrigger.ModelParameter;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseRecognitionExtra;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionMode;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.IAcknowledgeEvent;
import android.media.soundtrigger_middleware.IInjectGlobalEvent;
import android.media.soundtrigger_middleware.IInjectModelEvent;
import android.media.soundtrigger_middleware.IInjectRecognitionEvent;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FunctionalUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


/**
 * Fake HAL implementation, which offers injection via
 * {@link ISoundTriggerInjection}.
 * Since this is a test interface, upon unexpected operations from the framework,
 * we will abort.
 */
public class FakeSoundTriggerHal extends ISoundTriggerHw.Stub {
    private static final String TAG = "FakeSoundTriggerHal";

    // Fake values for valid model param range
    private static final int THRESHOLD_MIN = -10;
    private static final int THRESHOLD_MAX = 10;

    // Logically const
    private final Object mLock = new Object();
    private final Properties mProperties;

    // These cannot be injected, since we rely on:
    // 1) Serialization
    // 2) Running in a different thread
    // And there is no Executor interface with these requirements
    // These factories clean up the pools on finalizer.
    // Package private so the FakeHalFactory can dispatch
    static class ExecutorHolder {
        static final Executor CALLBACK_EXECUTOR =
                Executors.newSingleThreadExecutor();
        static final Executor INJECTION_EXECUTOR =
                Executors.newSingleThreadExecutor();
    }

    // Dispatcher interface for callbacks, using the executors above
    private final InjectionDispatcher mInjectionDispatcher;

    // Created on construction, passed back to clients.
    private final IInjectGlobalEvent.Stub mGlobalEventSession;

    @GuardedBy("mLock")
    private IBinder.DeathRecipient mDeathRecipient;

    @GuardedBy("mLock")
    private GlobalCallbackDispatcher mGlobalCallbackDispatcher = null;

    @GuardedBy("mLock")
    private boolean mIsResourceContended = false;
    @GuardedBy("mLock")
    private final Map<Integer, ModelSession> mModelSessionMap = new HashMap<>();

    // Current version of the STHAL relies on integer model session ids.
    // Generate them monotonically starting at 101
    @GuardedBy("mLock")
    private int mModelKeyCounter = 101;

    @GuardedBy("mLock")
    private boolean mIsDead = false;

    private class ModelSession extends IInjectModelEvent.Stub {
        // Logically const
        private final boolean mIsKeyphrase;
        private final CallbackDispatcher mCallbackDispatcher;
        private final int mModelHandle;

        // Model parameter
        @GuardedBy("FakeSoundTriggerHal.this.mLock")
        private int mThreshold = 0;

        // Mutable
        @GuardedBy("FakeSoundTriggerHal.this.mLock")
        private boolean mIsUnloaded = false; // Latch

        // Only a single recognition session is able to be active for a model
        // session at any given time. Null if no recognition is active.
        @GuardedBy("FakeSoundTriggerHal.this.mLock")
        @Nullable private RecognitionSession mRecognitionSession;

        private ModelSession(int modelHandle, CallbackDispatcher callbackDispatcher,
                boolean isKeyphrase) {
            mModelHandle = modelHandle;
            mCallbackDispatcher = callbackDispatcher;
            mIsKeyphrase = isKeyphrase;
        }

        private RecognitionSession startRecognitionForModel() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                mRecognitionSession = new RecognitionSession();
                return mRecognitionSession;
            }
        }

        private RecognitionSession stopRecognitionForModel() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                RecognitionSession session = mRecognitionSession;
                mRecognitionSession = null;
                return session;
            }
        }

        private void forceRecognitionForModel() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                if (mIsKeyphrase) {
                    PhraseRecognitionEvent phraseEvent =
                            createDefaultKeyphraseEvent(RecognitionStatus.FORCED);
                    mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                            cb.phraseRecognitionCallback(mModelHandle, phraseEvent));
                } else {
                    RecognitionEvent event = createDefaultEvent(RecognitionStatus.FORCED);
                    mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                            cb.recognitionCallback(mModelHandle, event));
                }
            }
        }

        private void setThresholdFactor(int value) {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                mThreshold = value;
            }
        }

        private int getThresholdFactor() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                return mThreshold;
            }
        }

        private boolean getIsUnloaded() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                return mIsUnloaded;
            }
        }

        private RecognitionSession getRecogSession() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                return mRecognitionSession;
            }
        }


        /** oneway **/
        @Override
        public void triggerUnloadModel() {
            synchronized (FakeSoundTriggerHal.this.mLock) {
                if (mIsDead || mIsUnloaded) return;
                if (mRecognitionSession != null) {
                    // Must abort model before triggering unload
                    mRecognitionSession.triggerAbortRecognition();
                }
                // Invalidate the model session
                mIsUnloaded = true;
                mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                        cb.modelUnloaded(mModelHandle));
                // Don't notify the injection that an unload has occurred, since it is what
                // triggered the unload

                // Notify if we could have denied a previous model due to contention
                if (getNumLoadedModelsLocked() == (mProperties.maxSoundModels - 1)
                        && !mIsResourceContended) {
                    mGlobalCallbackDispatcher.wrap((ISoundTriggerHwGlobalCallback cb) ->
                            cb.onResourcesAvailable());
                }
            }
        }

        private class RecognitionSession extends IInjectRecognitionEvent.Stub {

            @Override
            /** oneway **/
            public void triggerRecognitionEvent(byte[] data,
                    @Nullable PhraseRecognitionExtra[] phraseExtras) {
                synchronized (FakeSoundTriggerHal.this.mLock) {
                    // Check if our session has already been invalidated
                    if (mIsDead || mRecognitionSession != this) return;
                    // Invalidate the recognition session
                    mRecognitionSession = null;
                    // Trigger the callback.
                    if (mIsKeyphrase) {
                        PhraseRecognitionEvent phraseEvent =
                                createDefaultKeyphraseEvent(RecognitionStatus.SUCCESS);
                        phraseEvent.common.data = data;
                        if (phraseExtras != null) phraseEvent.phraseExtras = phraseExtras;
                        mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                                cb.phraseRecognitionCallback(mModelHandle, phraseEvent));
                    } else {
                        RecognitionEvent event = createDefaultEvent(RecognitionStatus.SUCCESS);
                        event.data = data;
                        mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                                cb.recognitionCallback(mModelHandle, event));
                    }
                }
            }

            @Override
            /** oneway **/
            public void triggerAbortRecognition() {
                synchronized (FakeSoundTriggerHal.this.mLock) {
                    if (mIsDead || mRecognitionSession != this) return;
                    // Clear the session state
                    mRecognitionSession = null;
                    // Trigger the callback.
                    if (mIsKeyphrase) {
                        mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                                cb.phraseRecognitionCallback(mModelHandle,
                                    createDefaultKeyphraseEvent(RecognitionStatus.ABORTED)));
                    } else {
                        mCallbackDispatcher.wrap((ISoundTriggerHwCallback cb) ->
                                cb.recognitionCallback(mModelHandle,
                                    createDefaultEvent(RecognitionStatus.ABORTED)));
                    }
                }
            }
        }
    }

    // Since this is always constructed, it needs to be cheap to create.
    public FakeSoundTriggerHal(ISoundTriggerInjection injection) {
        mProperties = createDefaultProperties();
        mInjectionDispatcher = new InjectionDispatcher(injection);
        mGlobalCallbackDispatcher = null; // If this NPEs before registration, we want to abort.
        // Implement the IInjectGlobalEvent IInterface.
        // Since we can't extend multiple IInterface from the same object, instantiate an instance
        // for our clients.
        mGlobalEventSession = new IInjectGlobalEvent.Stub() {
            /**
             * Simulate a HAL process restart. This method is not included in regular HAL interface,
             * since the entire process is restarted by sending a signal.
             * Since we run in-proc, we must offer an explicit restart method.
             * oneway
             */
            @Override
            public void triggerRestart() {
                synchronized (FakeSoundTriggerHal.this.mLock) {
                    if (mIsDead) return;
                    mIsDead = true;
                    mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                            cb.onRestarted(this));
                    mModelSessionMap.clear();
                    if (mDeathRecipient != null) {
                        final DeathRecipient deathRecipient = mDeathRecipient;
                        ExecutorHolder.CALLBACK_EXECUTOR.execute(() -> {
                            try {
                                deathRecipient.binderDied(FakeSoundTriggerHal.this.asBinder());
                            } catch (Throwable e) {
                                // We don't expect RemoteException at the moment since we run
                                // in the same process
                                Slog.wtf(TAG, "Callback dispatch threw", e);
                            }
                        });
                    }
                }
            }

            // oneway
            @Override
            public void setResourceContention(boolean isResourcesContended,
                        IAcknowledgeEvent callback) {
                synchronized (FakeSoundTriggerHal.this.mLock) {
                    // oneway, so don't throw on death
                    if (mIsDead) {
                        return;
                    }
                    boolean oldIsResourcesContended = mIsResourceContended;
                    mIsResourceContended = isResourcesContended;
                    // Introducing contention is the only injection which can't be
                    // observed by the ST client.
                    mInjectionDispatcher.wrap((ISoundTriggerInjection unused) ->
                            callback.eventReceived());
                    if (!mIsResourceContended && oldIsResourcesContended) {
                        mGlobalCallbackDispatcher.wrap((ISoundTriggerHwGlobalCallback cb) ->
                                    cb.onResourcesAvailable());
                    }
                }
            }

            // oneway
            @Override
            public void triggerOnResourcesAvailable() {
                synchronized (FakeSoundTriggerHal.this.mLock) {
                    // oneway, so don't throw on death
                    if (mIsDead) return;
                    mGlobalCallbackDispatcher.wrap((ISoundTriggerHwGlobalCallback cb) ->
                            cb.onResourcesAvailable());
                }
            }
        };

        // Register the global event injection interface
        mInjectionDispatcher.wrap((ISoundTriggerInjection cb)
                -> cb.registerGlobalEventInjection(mGlobalEventSession));
    }

    /**
     * Get the {@link IInjectGlobalEvent} associated with this instance of the STHAL.
     * Used as a session token, valid until restarted.
     */
    public IInjectGlobalEvent getGlobalEventInjection() {
        return mGlobalEventSession;
    }

    // TODO(b/274467228) we can remove the next three methods when this HAL is moved out-of-proc,
    // so process restart at death notification is appropriately handled by the binder.
    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
        synchronized (mLock) {
            if (mDeathRecipient != null) {
                Slog.wtf(TAG, "Received two death recipients concurrently");
            }
            mDeathRecipient = recipient;
        }
    }

    @Override
    public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
        synchronized (mLock) {
            if (mIsDead) return false;
            if (mDeathRecipient != recipient) {
                throw new NoSuchElementException();
            }
            mDeathRecipient = null;
            return true;
        }
    }

    // STHAL method overrides to follow
    @Override
    public Properties getProperties() throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            Parcel parcel = Parcel.obtain();
            try {
                mProperties.writeToParcel(parcel, 0 /* flags */);
                parcel.setDataPosition(0);
                return Properties.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }

    @Override
    public void registerGlobalCallback(
            ISoundTriggerHwGlobalCallback callback) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            mGlobalCallbackDispatcher = new GlobalCallbackDispatcher(callback);
        }
    }

    @Override
    public int loadSoundModel(SoundModel soundModel,
            ISoundTriggerHwCallback callback) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            if (mIsResourceContended || getNumLoadedModelsLocked() == mProperties.maxSoundModels) {
                throw new ServiceSpecificException(Status.RESOURCE_CONTENTION);
            }
            int key = mModelKeyCounter++;
            ModelSession session = new ModelSession(key, new CallbackDispatcher(callback), false);

            mModelSessionMap.put(key, session);

            mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                    cb.onSoundModelLoaded(soundModel, null, session, mGlobalEventSession));
            return key;
        }
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel,
            ISoundTriggerHwCallback callback) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            if (mIsResourceContended || getNumLoadedModelsLocked() == mProperties.maxSoundModels) {
                throw new ServiceSpecificException(Status.RESOURCE_CONTENTION);
            }

            int key = mModelKeyCounter++;
            ModelSession session = new ModelSession(key, new CallbackDispatcher(callback), true);

            mModelSessionMap.put(key, session);

            mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                    cb.onSoundModelLoaded(soundModel.common, soundModel.phrases, session,
                        mGlobalEventSession));
            return key;
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to unload model which was never loaded");
            }

            if (session.getRecogSession() != null) {
                Slog.wtf(TAG, "Session unloaded before recog stopped!");
            }

            // Session is stale
            if (session.getIsUnloaded()) return;
            mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                    cb.onSoundModelUnloaded(session));

            // Notify if we could have denied a previous model due to contention
            if (getNumLoadedModelsLocked() == (mProperties.maxSoundModels - 1)
                    && !mIsResourceContended) {
                mGlobalCallbackDispatcher.wrap((ISoundTriggerHwGlobalCallback cb) ->
                        cb.onResourcesAvailable());
            }

        }
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to start recognition with invalid handle");
            }
            if (mIsResourceContended) {
                throw new ServiceSpecificException(Status.RESOURCE_CONTENTION);
            }
            if (session.getIsUnloaded()) {
                // TODO(b/274470274) this is a deficiency in the existing HAL API, there is no way
                // to handle this race gracefully
                throw new ServiceSpecificException(Status.RESOURCE_CONTENTION);
            }
            ModelSession.RecognitionSession recogSession = session.startRecognitionForModel();

            // TODO(b/274470571) appropriately translate ioHandle to session handle
            mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                    cb.onRecognitionStarted(-1, config, recogSession, session));
        }
    }

    @Override
    public void stopRecognition(int modelHandle) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to stop recognition with invalid handle");
            }
            ModelSession.RecognitionSession recogSession = session.stopRecognitionForModel();
            if (recogSession != null) {
                mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                        cb.onRecognitionStopped(recogSession));
            }
        }
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to force recognition with invalid handle");
            }

            // TODO(b/274470274) this is a deficiency in the existing HAL API, we could always
            // get a force request for an already stopped model. The only thing to do is
            // drop such a request.
            if (session.getRecogSession() == null) return;
            session.forceRecognitionForModel();
        }
    }

    // TODO(b/274470274) this is a deficiency in the existing HAL API, we could always
    // get model param API requests after model unload.
    // For now, succeed anyway to maintain fidelity to existing HALs.
    @Override
    public @Nullable ModelParameterRange queryParameter(int modelHandle,
            /** ModelParameter **/ int modelParam) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to get param with invalid handle");
            }
        }
        if (modelParam == ModelParameter.THRESHOLD_FACTOR) {
            ModelParameterRange range = new ModelParameterRange();
            range.minInclusive = THRESHOLD_MIN;
            range.maxInclusive = THRESHOLD_MAX;
            return range;
        } else {
            return null;
        }
    }

    @Override
    public int getParameter(int modelHandle,
            /** ModelParameter **/ int modelParam) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to get param with invalid handle");
            }
            if (modelParam != ModelParameter.THRESHOLD_FACTOR) {
                throw new IllegalArgumentException();
            }
            return session.getThresholdFactor();
        }
    }

    @Override
    public void setParameter(int modelHandle,
            /** ModelParameter **/ int modelParam, int value) throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
            ModelSession session = mModelSessionMap.get(modelHandle);
            if (session == null) {
                Slog.wtf(TAG, "Attempted to get param with invalid handle");
            }
            if ((modelParam == ModelParameter.THRESHOLD_FACTOR)
                    || (value >= THRESHOLD_MIN && value <= THRESHOLD_MAX)) {
                session.setThresholdFactor(value);
            } else {
                throw new IllegalArgumentException();
            }
            mInjectionDispatcher.wrap((ISoundTriggerInjection cb) ->
                    cb.onParamSet(modelParam, value, session));
        }
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
        }
        return super.VERSION;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        synchronized (mLock) {
            if (mIsDead) throw new DeadObjectException();
        }
        return super.HASH;
    }

    // Helpers to follow.
    @GuardedBy("mLock")
    private int getNumLoadedModelsLocked() {
        int numModels = 0;
        for (ModelSession session : mModelSessionMap.values()) {
            if (!session.getIsUnloaded()) {
                numModels++;
            }
        }
        return numModels;
    }

    private static Properties createDefaultProperties() {
        Properties properties = new Properties();
        properties.implementor = "android";
        properties.description = "AOSP fake STHAL";
        properties.version = 1;
        properties.uuid = "00000001-0002-0003-0004-deadbeefabcd";
        properties.supportedModelArch = ISoundTriggerInjection.FAKE_HAL_ARCH;
        properties.maxSoundModels = 8;
        properties.maxKeyPhrases = 2;
        properties.maxUsers = 2;
        properties.recognitionModes = RecognitionMode.VOICE_TRIGGER
                | RecognitionMode.GENERIC_TRIGGER;
        properties.captureTransition = true;
        // This is actually not respected, since there is no real AudioRecord
        properties.maxBufferMs = 5000;
        properties.concurrentCapture = true;
        properties.triggerInEvent = false;
        properties.powerConsumptionMw = 0;
        properties.audioCapabilities = 0;
        return properties;
    }

    private static RecognitionEvent createDefaultEvent(
            /** RecognitionStatus **/ int status) {
        RecognitionEvent event = new RecognitionEvent();
        // Overwrite the event appropriately.
        event.status = status;
        event.type = SoundModelType.GENERIC;
        // TODO(b/274466981) make this configurable.
        // For now, some plausible defaults
        event.captureAvailable = true;
        event.captureDelayMs = 50;
        event.capturePreambleMs = 200;
        event.triggerInData = false;
        event.audioConfig = null; // Nullable within AIDL
        event.data = new byte[0];
        // We don't support recognition restart for now
        event.recognitionStillActive = false;
        return event;
    }

    private static PhraseRecognitionEvent createDefaultKeyphraseEvent(
            /**RecognitionStatus **/ int status) {
        RecognitionEvent event = createDefaultEvent(status);
        event.type = SoundModelType.KEYPHRASE;
        PhraseRecognitionEvent phraseEvent = new PhraseRecognitionEvent();
        phraseEvent.common = event;
        phraseEvent.phraseExtras = new PhraseRecognitionExtra[0];
        return phraseEvent;
    }

    // Helper classes to dispatch oneway calls to the appropriate callback interfaces to follow.
    private static class CallbackDispatcher {

        private CallbackDispatcher(ISoundTriggerHwCallback callback) {
            mCallback = callback;
        }

        private void wrap(FunctionalUtils.ThrowingConsumer<ISoundTriggerHwCallback> command) {
            ExecutorHolder.CALLBACK_EXECUTOR.execute(() -> {
                try {
                    command.accept(mCallback);
                } catch (Throwable e) {
                    Slog.wtf(TAG, "Callback dispatch threw", e);
                }
            });
        }

        private final ISoundTriggerHwCallback mCallback;
    }

    private static class GlobalCallbackDispatcher {

        private GlobalCallbackDispatcher(ISoundTriggerHwGlobalCallback callback) {
            mCallback = callback;
        }

        private void wrap(FunctionalUtils.ThrowingConsumer<ISoundTriggerHwGlobalCallback> command) {
            ExecutorHolder.CALLBACK_EXECUTOR.execute(() -> {
                try {
                    command.accept(mCallback);
                } catch (Throwable e) {
                    // We don't expect RemoteException at the moment since we run
                    // in the same process
                    Slog.wtf(TAG, "Callback dispatch threw", e);
                }
            });
        }

        private final ISoundTriggerHwGlobalCallback mCallback;
    }

    private static class InjectionDispatcher {

        private InjectionDispatcher(ISoundTriggerInjection injection) {
            mInjection = injection;
        }

        private void wrap(FunctionalUtils.ThrowingConsumer<ISoundTriggerInjection> command) {
            ExecutorHolder.INJECTION_EXECUTOR.execute(() -> {
                try {
                    command.accept(mInjection);
                } catch (Throwable e) {
                    // We don't expect RemoteException at the moment since we run
                    // in the same process
                    Slog.wtf(TAG, "Callback dispatch threw", e);
                }
            });
        }

        private final ISoundTriggerInjection mInjection;
    }
}
