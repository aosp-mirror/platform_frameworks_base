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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.soundtrigger.V2_0.ISoundTriggerHw;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This is a wrapper around an {@link ISoundTriggerMiddlewareService} implementation, which exposes
 * it as a Binder service and enforces permissions and correct usage by the client, as well as makes
 * sure that exceptions representing a server malfunction do not get sent to the client.
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
 *     // Permission check.
 *     checkPermissions();
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
 * with client-server separation.
 * <p>
 * <b>Exception handling approach:</b><br>
 * We make sure all client faults (permissions, argument and state validation) happen first, and
 * would throw {@link SecurityException}, {@link IllegalArgumentException}/
 * {@link NullPointerException} or {@link
 * IllegalStateException}, respectively. All those exceptions are treated specially by Binder and
 * will get sent back to the client.<br>
 * Once this is done, any subsequent fault is considered a server fault. Only {@link
 * RecoverableException}s thrown by the implementation are special-cased: they would get sent back
 * to the caller as a {@link ServiceSpecificException}, which is the behavior of Binder. Any other
 * exception gets wrapped with a {@link InternalServerError}, which is specifically chosen as a type
 * that <b>does NOT</b> get forwarded by binder. Those exceptions would be handled by a high-level
 * exception handler on the server side, typically resulting in rebooting the server.
 * <p>
 * <b>Exposing this service as a System Service:</b><br>
 * Insert this line into {@link com.android.server.SystemServer}:
 * <code><pre>
 * mSystemServiceManager.startService(SoundTriggerMiddlewareService.Lifecycle.class);
 * </pre></code>
 *
 * {@hide}
 */
public class SoundTriggerMiddlewareService extends ISoundTriggerMiddlewareService.Stub {
    static private final String TAG = "SoundTriggerMiddlewareService";

    final ISoundTriggerMiddlewareService mDelegate;
    final Context mContext;
    Set<Integer> mModuleHandles;

    /**
     * Constructor for internal use only. Could be exposed for testing purposes in the future.
     * Users should access this class via {@link Lifecycle}.
     */
    private SoundTriggerMiddlewareService(
            @NonNull ISoundTriggerMiddlewareService delegate, @NonNull Context context) {
        mDelegate = delegate;
        mContext = context;
    }

    /**
     * Generic exception handling for exceptions thrown by the underlying implementation.
     *
     * Would throw any {@link RecoverableException} as a {@link ServiceSpecificException} (passed
     * by Binder to the caller) and <i>any other</i> exception as {@link InternalServerError}
     * (<b>not</b> passed by Binder to the caller).
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
    private static @NonNull
    RuntimeException handleException(@NonNull Exception e) {
        if (e instanceof RecoverableException) {
            throw new ServiceSpecificException(((RecoverableException) e).errorCode,
                    e.getMessage());
        }
        throw new InternalServerError(e);
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        // Permission check.
        checkPermissions();
        // Input validation (always valid).

        synchronized (this) {
            // State validation (always valid).

            // From here on, every exception isn't client's fault.
            try {
                SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
                mModuleHandles = new HashSet<>(result.length);
                for (SoundTriggerModuleDescriptor desc : result) {
                    mModuleHandles.add(desc.handle);
                }
                return result;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle, @NonNull ISoundTriggerCallback callback) {
        // Permission check.
        checkPermissions();
        // Input validation.
        Objects.requireNonNull(callback);
        Objects.requireNonNull(callback.asBinder());

        synchronized (this) {
            // State validation.
            if (mModuleHandles == null) {
                throw new IllegalStateException(
                        "Client must call listModules() prior to attaching.");
            }
            if (!mModuleHandles.contains(handle)) {
                throw new IllegalArgumentException("Invalid handle: " + handle);
            }

            // From here on, every exception isn't client's fault.
            try {
                ModuleService moduleService = new ModuleService(callback);
                moduleService.attach(mDelegate.attach(handle, moduleService));
                return moduleService;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public void setExternalCaptureState(boolean active) {
        // Permission check.
        checkPreemptPermissions();
        // Input validation (always valid).

        synchronized (this) {
            // State validation (always valid).

            // From here on, every exception isn't client's fault.
            try {
                mDelegate.setExternalCaptureState(active);
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    /**
     * Throws a {@link SecurityException} if caller doesn't have the right permissions to use this
     * service.
     */
    private void checkPermissions() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO,
                "Caller must have the android.permission.RECORD_AUDIO permission.");
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD,
                "Caller must have the android.permission.CAPTURE_AUDIO_HOTWORD permission.");
    }

    /**
     * Throws a {@link SecurityException} if caller doesn't have the right permissions to preempt
     * active sound trigger sessions.
     */
    private void checkPreemptPermissions() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.PREEMPT_SOUND_TRIGGER,
                "Caller must have the android.permission.PREEMPT_SOUND_TRIGGER permission.");
    }

    /** State of a sound model. */
    static class ModelState {
        /** Activity state of a sound model. */
        enum Activity {
            /** Model is loaded, recognition is inactive. */
            LOADED,
            /** Model is loaded, recognition is active. */
            ACTIVE
        }

        /** Activity state. */
        public Activity activityState = Activity.LOADED;

        /**
         * A map of known parameter support. A missing key means we don't know yet whether the
         * parameter is supported. A null value means it is known to not be supported. A non-null
         * value indicates the valid value range.
         */
        private Map<Integer, ModelParameterRange> parameterSupport = new HashMap<>();

        /**
         * Check that the given parameter is known to be supported for this model.
         *
         * @param modelParam The parameter key.
         */
        public void checkSupported(int modelParam) {
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
        public void checkSupported(int modelParam, int value) {
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

        /**
         * Update support state for the given parameter for this model.
         *
         * @param modelParam The parameter key.
         * @param range      The parameter value range, or null if not supported.
         */
        public void updateParameterSupport(int modelParam, @Nullable ModelParameterRange range) {
            parameterSupport.put(modelParam, range);
        }
    }

    /**
     * Entry-point to this module: exposes the module as a {@link SystemService}.
     */
    public static final class Lifecycle extends SystemService {
        private SoundTriggerMiddlewareService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            ISoundTriggerHw[] services;
            try {
                services = new ISoundTriggerHw[]{ISoundTriggerHw.getService(true)};
                Log.d(TAG, "Connected to default ISoundTriggerHw");
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to default ISoundTriggerHw", e);
                services = new ISoundTriggerHw[0];
            }

            mService = new SoundTriggerMiddlewareService(
                    new SoundTriggerMiddlewareImpl(services, new AudioSessionProviderImpl()),
                    getContext());
            publishBinderService(Context.SOUND_TRIGGER_MIDDLEWARE_SERVICE, mService);
        }
    }

    /**
     * A wrapper around an {@link ISoundTriggerModule} implementation, to address the same aspects
     * mentioned in {@link SoundTriggerModule} above. This class follows the same conventions.
     */
    private class ModuleService extends ISoundTriggerModule.Stub implements ISoundTriggerCallback,
            DeathRecipient {
        private final ISoundTriggerCallback mCallback;
        private ISoundTriggerModule mDelegate;
        private Map<Integer, ModelState> mLoadedModels = new HashMap<>();

        ModuleService(@NonNull ISoundTriggerCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        void attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateGenericModel(model);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadModel(model);
                    mLoadedModels.put(handle, new ModelState());
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validatePhraseModel(model);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadPhraseModel(model);
                    mLoadedModels.put(handle, new ModelState());
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.activityState != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for unloading: " + modelState.activityState);
                }

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.unloadModel(modelHandle);
                    mLoadedModels.remove(modelHandle);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateRecognitionConfig(config);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.activityState != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for starting recognition: "
                            + modelState.activityState);
                }

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.startRecognition(modelHandle, config);
                    modelState.activityState = ModelState.Activity.ACTIVE;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // stopRecognition is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.stopRecognition(modelHandle);
                    modelState.activityState = ModelState.Activity.LOADED;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // forceRecognitionEvent is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.forceRecognitionEvent(modelHandle);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
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
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
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
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }

                // From here on, every exception isn't client's fault.
                try {
                    ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                            modelParam);
                    modelState.updateParameterSupport(modelParam, result);
                    return result;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void detach() {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (this) {
                // State validation.
                if (mDelegate == null) {
                    throw new IllegalStateException("Module has already been detached.");
                }
                if (!mLoadedModels.isEmpty()) {
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

        private void detachInternal() {
            try {
                mDelegate.detach();
                mDelegate = null;
                mCallback.asBinder().unlinkToDeath(this, 0);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////////
        // Callbacks

        @Override
        public void onRecognition(int modelHandle, @NonNull RecognitionEvent event) {
            synchronized (this) {
                if (event.status != RecognitionStatus.FORCED) {
                    mLoadedModels.get(modelHandle).activityState = ModelState.Activity.LOADED;
                }
                try {
                    mCallback.onRecognition(modelHandle, event);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback execption.", e);
                }
            }
        }

        @Override
        public void onPhraseRecognition(int modelHandle, @NonNull PhraseRecognitionEvent event) {
            synchronized (this) {
                if (event.common.status != RecognitionStatus.FORCED) {
                    mLoadedModels.get(modelHandle).activityState = ModelState.Activity.LOADED;
                }
                try {
                    mCallback.onPhraseRecognition(modelHandle, event);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback execption.", e);
                }
            }
        }

        @Override
        public void onRecognitionAvailabilityChange(boolean available) throws RemoteException {
            synchronized (this) {
                try {
                    mCallback.onRecognitionAvailabilityChange(available);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback execption.", e);
                }
            }
        }

        @Override
        public void binderDied() {
            // This is called whenever our client process dies.
            synchronized (this) {
                try {
                    // Gracefully stop all active recognitions and unload the models.
                    for (Map.Entry<Integer, ModelState> entry : mLoadedModels.entrySet()) {
                        if (entry.getValue().activityState == ModelState.Activity.ACTIVE) {
                            mDelegate.stopRecognition(entry.getKey());
                        }
                        mDelegate.unloadModel(entry.getKey());
                    }
                    // Detach.
                    detachInternal();
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }
    }
}
