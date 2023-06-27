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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This is a decorator of an {@link ISoundTriggerMiddlewareService}, which enforces correct usage by
 * the client, as well as makes sure that exceptions representing a server malfunction get sent to
 * the client in a consistent manner, which cannot be confused with a client fault.
 * <p>
 * This is intended to extract the non-business logic out of the underlying implementation and thus
 * make it easier to maintain each one of those separate aspects. A design trade-off is being made
 * here, in that this class would need to essentially eavesdrop on all the client-server
 * communication and retain all state known to the client, while the client doesn't necessarily care
 * about all of it, and while the server has its own representation of this information. However,
 * in this case, this is a small amount of data, and the benefits in code elegance seem worth it.
 * There is also some additional cost in employing a simplistic locking mechanism here, but
 * following the same line of reasoning, the benefits in code simplicity outweigh it.
 * <p>
 * Every public method in this class, overriding an interface method, must follow the following
 * pattern:
 * <code><pre>
 * @Override public T method(S arg) {
 *     // Input validation.
 *     ValidationUtil.validateS(arg);
 *     synchronized (this) {
 *         // State validation.
 *         if (...state is not valid for this call...) {
 *             throw new IllegalStateException("State is invalid because...");
 *         }
 *         // From here on, every exception isn't client's fault.
 *         try {
 *             T result = mDelegate.method(arg);
 *             // Update state.;
 *             ...
 *             return result;
 *         } catch (Exception e) {
 *             throw handleException(e);
 *         }
 *     }
 * }
 * </pre></code>
 * Following this patterns ensures a consistent and rigorous handling of all aspects associated
 * with client-server separation. Notable exceptions are stopRecognition() and unloadModel(), which
 * follow slightly more complicated rules for synchronization (see README.md for details).
 * <p>
 * <b>Exception handling approach:</b><br>
 * We make sure all client faults (argument and state validation) happen first, and
 * would throw {@link IllegalArgumentException}/{@link NullPointerException} or {@link
 * IllegalStateException}, respectively. All those exceptions are treated specially by Binder and
 * will get sent back to the client.<br>
 * Once this is done, any subsequent fault is considered either a recoverable (expected) or
 * unexpected server fault. Those will be delivered to the client as a
 * {@link ServiceSpecificException}. {@link RecoverableException}s thrown by the implementation are
 * considered recoverable and will include a specific error code to indicate the problem. Any other
 * exceptions will use the INTERNAL_ERROR code. They may also cause the module to become invalid
 * asynchronously, and the client would be notified via the moduleDied() callback.
 *
 * {@hide}
 */
public class SoundTriggerMiddlewareValidation implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewareValidation";

    private enum ModuleStatus {
        ALIVE,
        DETACHED,
        DEAD
    }

    private class ModuleState {
        public @NonNull Properties properties;
        public Set<Session> sessions = new HashSet<>();

        private ModuleState(@NonNull Properties properties) {
            this.properties = properties;
        }
    }

    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;
    private Map<Integer, ModuleState> mModules;

    public SoundTriggerMiddlewareValidation(@NonNull ISoundTriggerMiddlewareInternal delegate) {
        mDelegate = delegate;
    }

    /**
     * Generic exception handling for exceptions thrown by the underlying implementation.
     *
     * Would throw any {@link RecoverableException} as a {@link ServiceSpecificException} (passed
     * by Binder to the caller) and <i>any other</i> exception as a {@link ServiceSpecificException}
     * with a {@link Status#INTERNAL_ERROR} code.
     * <p>
     * Typical usage:
     * <code><pre>
     * try {
     *     ... Do server operations ...
     * } catch (Exception e) {
     *     throw handleException(e);
     * }
     * </pre></code>
     */
    static @NonNull RuntimeException handleException(@NonNull Exception e) {
        if (e instanceof RecoverableException) {
            throw new ServiceSpecificException(((RecoverableException) e).errorCode,
                    e.getMessage());
        }

        Slog.wtf(TAG, "Unexpected exception", e);
        throw new ServiceSpecificException(Status.INTERNAL_ERROR, e.getMessage());
    }

    @Override
    public @NonNull SoundTriggerModuleDescriptor[] listModules() {
        // Input validation (always valid).

        synchronized (this) {
            // State validation (always valid).

            // From here on, every exception isn't client's fault.
            try {
                SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
                if (mModules == null) {
                    mModules = new HashMap<>(result.length);
                    for (SoundTriggerModuleDescriptor desc : result) {
                        mModules.put(desc.handle, new ModuleState(desc.properties));
                    }
                } else {
                    if (result.length != mModules.size()) {
                        throw new RuntimeException(
                                "listModules must always return the same result.");
                    }
                    for (SoundTriggerModuleDescriptor desc : result) {
                        if (!mModules.containsKey(desc.handle)) {
                            throw new RuntimeException(
                                    "listModules must always return the same result.");
                        }
                        mModules.get(desc.handle).properties = desc.properties;
                    }
                }
                return result;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public @NonNull ISoundTriggerModule attach(int handle,
            @NonNull ISoundTriggerCallback callback, boolean isTrusted) {
        // Input validation.
        Objects.requireNonNull(callback);
        Objects.requireNonNull(callback.asBinder());

        synchronized (this) {
            // State validation.
            if (mModules == null) {
                throw new IllegalStateException(
                        "Client must call listModules() prior to attaching.");
            }
            if (!mModules.containsKey(handle)) {
                throw new IllegalArgumentException("Invalid handle: " + handle);
            }

            // From here on, every exception isn't client's fault.
            try {
                Session session = new Session(handle, callback);
                session.attach(mDelegate.attach(handle, session.getCallbackWrapper(), isTrusted));
                return session;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    @Override
    public void dump(PrintWriter pw) {
        synchronized (this) {
            if (mModules != null) {
                for (int handle : mModules.keySet()) {
                    final ModuleState module = mModules.get(handle);
                    pw.println("=========================================");
                    pw.printf("Module %d\n%s\n", handle,
                            ObjectPrinter.print(module.properties, 16));
                    pw.println("=========================================");
                    for (Session session : module.sessions) {
                        session.dump(pw);
                    }
                }
            } else {
                pw.println("Modules have not yet been enumerated.");
            }
        }
        pw.println();

        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }

    /** State of a sound model. */
    static class ModelState {
        ModelState(SoundModel model) {
            this.description = ObjectPrinter.print(model, 16);
        }

        ModelState(PhraseSoundModel model) {
            this.description = ObjectPrinter.print(model, 16);
        }

        /** Activity state of a sound model. */
        enum Activity {
            /** Model is loaded, recognition is inactive. */
            LOADED,
            /** Model is loaded, recognition is active. */
            ACTIVE,
            /**
             * Model has been preemptively unloaded by the HAL.
             */
            PREEMPTED,
        }

        /** Activity state. */
        Activity activityState = Activity.LOADED;

        /** Recognition config, used to start the model. */
        RecognitionConfig config;

        /** Human-readable description of the model. */
        final String description;

        /**
         * A map of known parameter support. A missing key means we don't know yet whether the
         * parameter is supported. A null value means it is known to not be supported. A non-null
         * value indicates the valid value range.
         */
        private final Map<Integer, ModelParameterRange> parameterSupport = new HashMap<>();

        /**
         * Check that the given parameter is known to be supported for this model.
         *
         * @param modelParam The parameter key.
         */
        void checkSupported(int modelParam) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
        }

        /**
         * Check that the given parameter is known to be supported for this model and that the given
         * value is a valid value for it.
         *
         * @param modelParam The parameter key.
         * @param value      The value.
         */
        void checkSupported(int modelParam, int value) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
            Preconditions.checkArgumentInRange(value, range.minInclusive, range.maxInclusive,
                    "value");
        }
    }

    /**
     * A wrapper around an {@link ISoundTriggerModule} implementation, to address the same aspects
     * mentioned in {@link SoundTriggerModule} above. This class follows the same conventions.
     */
    private class Session extends ISoundTriggerModule.Stub {
        private ISoundTriggerModule mDelegate;
        private final @NonNull Map<Integer, ModelState> mLoadedModels = new HashMap<>();
        private final int mHandle;
        private ModuleStatus mState = ModuleStatus.ALIVE;
        private final CallbackWrapper mCallbackWrapper;
        private final Identity mOriginatorIdentity;

        Session(int handle, @NonNull ISoundTriggerCallback callback) {
            mCallbackWrapper = new CallbackWrapper(callback);
            mHandle = handle;
            mOriginatorIdentity = IdentityContext.get();
        }

        ISoundTriggerCallback getCallbackWrapper() {
            return mCallbackWrapper;
        }

        void attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
            mModules.get(mHandle).sessions.add(this);
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            // Input validation.
            ValidationUtil.validateGenericModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            // Input validation.
            ValidationUtil.validatePhraseModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadPhraseModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            // Input validation (always valid).
            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // To avoid race conditions, we treat LOADED and PREEMPTED exactly the same.
                if (modelState.activityState != ModelState.Activity.LOADED
                        && modelState.activityState != ModelState.Activity.PREEMPTED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for unloading");
                }
            }

            // From here on, every exception isn't client's fault.
            try {
                // Calling the delegate must be done outside the lock.
                mDelegate.unloadModel(modelHandle);
            } catch (Exception e) {
                throw handleException(e);
            }

            synchronized (SoundTriggerMiddlewareValidation.this) {
                mLoadedModels.remove(modelHandle);
            }
        }

        @Override
        public IBinder startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            // Input validation.
            ValidationUtil.validateRecognitionConfig(config);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                ModelState.Activity activityState = modelState.activityState;
                // To avoid race conditions, we treat LOADED and PREEMPTED exactly the same.
                if (activityState != ModelState.Activity.LOADED
                        && activityState != ModelState.Activity.PREEMPTED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for starting recognition");
                }

                // From here on, every exception isn't client's fault.
                try {
                    var result = mDelegate.startRecognition(modelHandle, config);
                    modelState.config = config;
                    modelState.activityState = ModelState.Activity.ACTIVE;
                    return result;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // stopRecognition is idempotent - no need to check model state.
            }

            // Calling the delegate's stop must be done without the lock.
            try {
                mDelegate.stopRecognition(modelHandle);
            } catch (Exception e) {
                throw handleException(e);
            }

            synchronized (SoundTriggerMiddlewareValidation.this) {
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    // The model was unloaded while we let go of the lock.
                    return;
                }

                // After the call, the state is LOADED, unless it has been first preempted.
                if (modelState.activityState != ModelState.Activity.PREEMPTED) {
                    modelState.activityState = ModelState.Activity.LOADED;
                }
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // forceRecognitionEvent is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    // If the activity state is LOADED or INTERCEPTED, we skip delegating the
                    // command, but still consider the call valid.
                    if (modelState.activityState == ModelState.Activity.ACTIVE) {
                        mDelegate.forceRecognitionEvent(modelHandle);
                    }
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam, value);

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.setModelParameter(modelHandle, modelParam, value);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam);

                // From here on, every exception isn't client's fault.
                try {
                    return mDelegate.getModelParameter(modelHandle, modelParam);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }

                // From here on, every exception isn't client's fault.
                try {
                    ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                            modelParam);
                    modelState.parameterSupport.put(modelParam, result);
                    return result;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void detach() {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has already been detached.");
                }
                if (mState == ModuleStatus.ALIVE && !mLoadedModels.isEmpty()) {
                    throw new IllegalStateException("Cannot detach while models are loaded.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    detachInternal();
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }

        private void detachInternal() {
            try {
                mDelegate.detach();
                mState = ModuleStatus.DETACHED;
                mCallbackWrapper.detached();
                mModules.get(mHandle).sessions.remove(this);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        void dump(PrintWriter pw) {
            if (mState == ModuleStatus.ALIVE) {
                pw.println("-------------------------------");
                pw.printf("Session %s, client: %s\n", toString(),
                        ObjectPrinter.print(mOriginatorIdentity, 16));
                pw.println("Loaded models (handle, active, description):");
                pw.println();
                pw.println("-------------------------------");
                for (Map.Entry<Integer, ModelState> entry : mLoadedModels.entrySet()) {
                    pw.print(entry.getKey());
                    pw.print('\t');
                    pw.print(entry.getValue().activityState.name());
                    pw.print('\t');
                    pw.print(entry.getValue().description);
                    pw.println();
                }
                pw.println();
            } else {
                pw.printf("Session %s is dead", toString());
                pw.println();
            }
        }

        class CallbackWrapper implements ISoundTriggerCallback, IBinder.DeathRecipient {
            private final ISoundTriggerCallback mCallback;

            CallbackWrapper(ISoundTriggerCallback callback) {
                mCallback = callback;
                try {
                    mCallback.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }

            void detached() {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }

            @Override
            public void onRecognition(int modelHandle, @NonNull RecognitionEventSys event,
                    int captureSession) {
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    ModelState modelState = mLoadedModels.get(modelHandle);
                    if (!event.recognitionEvent.recognitionStillActive) {
                        modelState.activityState = ModelState.Activity.LOADED;
                    }
                }

                // Calling the delegate callback must be done outside the lock.
                try {
                    mCallback.onRecognition(modelHandle, event, captureSession);
                } catch (Exception e) {
                    Slog.w(TAG, "Client callback exception.", e);
               }
            }

            @Override
            public void onPhraseRecognition(int modelHandle,
                    @NonNull PhraseRecognitionEventSys event, int captureSession) {
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    ModelState modelState = mLoadedModels.get(modelHandle);
                    if (!event.phraseRecognitionEvent.common.recognitionStillActive) {
                        modelState.activityState = ModelState.Activity.LOADED;
                    }
                }

                // Calling the delegate callback must be done outside the lock.
                try {
                    mCallback.onPhraseRecognition(modelHandle, event, captureSession);
                } catch (Exception e) {
                    Slog.w(TAG, "Client callback exception.", e);
               }
            }

            @Override
            public void onModelUnloaded(int modelHandle) {
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    ModelState modelState = mLoadedModels.get(modelHandle);
                    modelState.activityState = ModelState.Activity.PREEMPTED;
                }

                // Calling the delegate callback must be done outside the lock.
                try {
                    mCallback.onModelUnloaded(modelHandle);
                } catch (Exception e) {
                    Slog.w(TAG, "Client callback exception.", e);
                }
            }

            @Override
            public void onResourcesAvailable() {
                // Not locking to avoid deadlocks (not affecting any state).
                try {
                    mCallback.onResourcesAvailable();
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Slog.e(TAG, "Client callback exception.", e);
                }
            }

            @Override
            public void onModuleDied() {
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    mState = ModuleStatus.DEAD;
                }
                // Trigger the callback outside of the lock to avoid deadlocks.
                try {
                    mCallback.onModuleDied();
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Slog.e(TAG, "Client callback exception.", e);
                }
            }

            @Override
            public void binderDied() {
                // This is called whenever our client process dies.
                SparseArray<ModelState.Activity> cachedMap =
                        new SparseArray<ModelState.Activity>();
                synchronized (SoundTriggerMiddlewareValidation.this) {
                        // Copy the relevant state under the lock, so we can call back without
                        // holding a lock. This exposes us to a potential race, but the client is
                        // dead so we don't expect one.
                        // TODO(240613068) A more resilient fix for this.
                        for (Map.Entry<Integer, ModelState> entry :
                                mLoadedModels.entrySet()) {
                            cachedMap.put(entry.getKey(), entry.getValue().activityState);
                        }
                }
                try {
                    // Gracefully stop all active recognitions and unload the models.
                    for (int i = 0; i < cachedMap.size(); i++) {
                        if (cachedMap.valueAt(i) == ModelState.Activity.ACTIVE) {
                            mDelegate.stopRecognition(cachedMap.keyAt(i));
                        }
                        mDelegate.unloadModel(cachedMap.keyAt(i));
                    }
                } catch (Exception e) {
                    throw handleException(e);
                }
                synchronized (SoundTriggerMiddlewareValidation.this) {
                   // Check if state updated unexpectedly to log race conditions.
                    for (Map.Entry<Integer, ModelState> entry : mLoadedModels.entrySet()) {
                        if (cachedMap.get(entry.getKey()) != entry.getValue().activityState) {
                            Slog.e(TAG, "Unexpected state update in binderDied. Race occurred!");
                        }
                    }
                    if (mLoadedModels.size() != cachedMap.size()) {
                        Slog.e(TAG, "Unexpected state update in binderDied. Race occurred!");
                    }
                    try {
                        // Detach
                        detachInternal();
                    } catch (Exception e) {
                        throw handleException(e);
                    }
                }
            }

            @Override
            public IBinder asBinder() {
                return mCallback.asBinder();
            }

            // Override toString() in order to have the delegate's ID in it.
            @Override
            public String toString() {
                return Objects.toString(mDelegate);
            }
        }
    }
}
