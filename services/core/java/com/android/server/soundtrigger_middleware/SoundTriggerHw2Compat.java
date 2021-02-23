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
import android.hardware.soundtrigger.V2_0.ISoundTriggerHw;
import android.media.soundtrigger_middleware.Status;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.system.OsConstants;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An implementation of {@link ISoundTriggerHw2}, on top of any
 * android.hardware.soundtrigger.V2_x.ISoundTriggerHw implementation. This class hides away some of
 * the details involved with retaining backward compatibility and adapts to the more pleasant syntax
 * exposed by {@link ISoundTriggerHw2}, compared to the bare driver interface.
 * <p>
 * Exception handling:
 * <ul>
 * <li>All {@link RemoteException}s get rethrown as {@link RuntimeException}.
 * <li>All HAL malfunctions get thrown as {@link HalException}.
 * <li>All unsupported operations get thrown as {@link RecoverableException} with a
 * {@link android.media.soundtrigger_middleware.Status#OPERATION_NOT_SUPPORTED}
 * code.
 * </ul>
 */
final class SoundTriggerHw2Compat implements ISoundTriggerHw2 {
    private final @NonNull Runnable mRebootRunnable;
    private final @NonNull IHwBinder mBinder;
    private @NonNull android.hardware.soundtrigger.V2_0.ISoundTriggerHw mUnderlying_2_0;
    private @Nullable android.hardware.soundtrigger.V2_1.ISoundTriggerHw mUnderlying_2_1;
    private @Nullable android.hardware.soundtrigger.V2_2.ISoundTriggerHw mUnderlying_2_2;
    private @Nullable android.hardware.soundtrigger.V2_3.ISoundTriggerHw mUnderlying_2_3;
    private @Nullable android.hardware.soundtrigger.V2_4.ISoundTriggerHw mUnderlying_2_4;

    // HAL <=2.1 requires us to pass a callback argument to startRecognition. We will store the one
    // passed on load and then pass it on start. We don't bother storing the callback on newer
    // versions.
    private final @NonNull ConcurrentMap<Integer, ModelCallback> mModelCallbacks =
            new ConcurrentHashMap<>();

    // The properties are read at construction time and cached, since we need to use some of them
    // to enforce constraints.
    private final @NonNull android.hardware.soundtrigger.V2_3.Properties mProperties;

    static ISoundTriggerHw2 create(
            @NonNull ISoundTriggerHw underlying,
            @NonNull Runnable rebootRunnable,
            ICaptureStateNotifier notifier) {
        return create(underlying.asBinder(), rebootRunnable, notifier);
    }

    static ISoundTriggerHw2 create(@NonNull IHwBinder binder,
            @NonNull Runnable rebootRunnable,
            ICaptureStateNotifier notifier) {
        SoundTriggerHw2Compat compat = new SoundTriggerHw2Compat(binder, rebootRunnable);
        ISoundTriggerHw2 result = compat;
        // Add max model limiter for versions <2.4.
        if (compat.mUnderlying_2_4 == null) {
            result = new SoundTriggerHw2MaxModelLimiter(result,
                    compat.mProperties.base.maxSoundModels);
        }
        // Add concurrent capture handler for versions <2.4 which do not support concurrent capture.
        if (compat.mUnderlying_2_4 == null && !compat.mProperties.base.concurrentCapture) {
            result = new SoundTriggerHw2ConcurrentCaptureHandler(result, notifier);
        }
        return result;
    }

    private SoundTriggerHw2Compat(@NonNull IHwBinder binder, @NonNull Runnable rebootRunnable) {
        mRebootRunnable = Objects.requireNonNull(rebootRunnable);
        mBinder = Objects.requireNonNull(binder);
        initUnderlying(binder);
        mProperties = Objects.requireNonNull(getPropertiesInternal());
    }

    private void initUnderlying(IHwBinder binder) {
        // We want to share the proxy instances rather than create a separate proxy for every
        // version, so we go down the versions in descending order to find the latest one supported,
        // and then simply up-cast it to obtain all the versions that are earlier.

        // Attempt 2.4
        android.hardware.soundtrigger.V2_4.ISoundTriggerHw as2_4 =
                android.hardware.soundtrigger.V2_4.ISoundTriggerHw.asInterface(binder);
        if (as2_4 != null) {
            mUnderlying_2_0 =
                    mUnderlying_2_1 = mUnderlying_2_2 = mUnderlying_2_3 = mUnderlying_2_4 = as2_4;
            return;
        }

        // Attempt 2.3
        android.hardware.soundtrigger.V2_3.ISoundTriggerHw as2_3 =
                android.hardware.soundtrigger.V2_3.ISoundTriggerHw.asInterface(binder);
        if (as2_3 != null) {
            mUnderlying_2_0 = mUnderlying_2_1 = mUnderlying_2_2 = mUnderlying_2_3 = as2_3;
            mUnderlying_2_4 = null;
            return;
        }

        // Attempt 2.2
        android.hardware.soundtrigger.V2_2.ISoundTriggerHw as2_2 =
                android.hardware.soundtrigger.V2_2.ISoundTriggerHw.asInterface(binder);
        if (as2_2 != null) {
            mUnderlying_2_0 = mUnderlying_2_1 = mUnderlying_2_2 = as2_2;
            mUnderlying_2_3 = mUnderlying_2_4 = null;
            return;
        }

        // Attempt 2.1
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw as2_1 =
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.asInterface(binder);
        if (as2_1 != null) {
            mUnderlying_2_0 = mUnderlying_2_1 = as2_1;
            mUnderlying_2_2 = mUnderlying_2_3 = mUnderlying_2_4 = null;
            return;
        }

        // Attempt 2.0
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw as2_0 =
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.asInterface(binder);
        if (as2_0 != null) {
            mUnderlying_2_0 = as2_0;
            mUnderlying_2_1 = mUnderlying_2_2 = mUnderlying_2_3 = mUnderlying_2_4 = null;
            return;
        }

        throw new RuntimeException("Binder doesn't support ISoundTriggerHw@2.0");
    }

    private static void handleHalStatus(int status, String methodName) {
        if (status != 0) {
            throw new HalException(status, methodName);
        }
    }

    private static void handleHalStatusAllowBusy(int status, String methodName) {
        if (status == -OsConstants.EBUSY) {
            throw new RecoverableException(Status.RESOURCE_CONTENTION);
        }
        handleHalStatus(status, methodName);
    }

    @Override
    public void reboot() {
        mRebootRunnable.run();
    }

    @Override
    public void detach() {
        // No-op.
    }

    @Override
    public android.hardware.soundtrigger.V2_3.Properties getProperties() {
        return mProperties;
    }

    private android.hardware.soundtrigger.V2_3.Properties getPropertiesInternal() {
        try {
            AtomicInteger retval = new AtomicInteger(-1);
            AtomicReference<android.hardware.soundtrigger.V2_3.Properties>
                    properties =
                    new AtomicReference<>();
            try {
                as2_3().getProperties_2_3(
                        (r, p) -> {
                            retval.set(r);
                            properties.set(p);
                        });
            } catch (NotSupported e) {
                // Fall-back to the 2.0 version:
                return getProperties_2_0();
            }
            handleHalStatus(retval.get(), "getProperties_2_3");
            return properties.get();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        try {
            try {
                as2_4().registerGlobalCallback(new GlobalCallbackWrapper(callback));
            } catch (NotSupported e) {
                // In versions < 2.4 the events represented by this callback don't exist, we can
                // safely ignore this.
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public int loadSoundModel(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel soundModel,
            ModelCallback callback) {
        try {
            AtomicInteger retval = new AtomicInteger(-1);
            AtomicInteger handle = new AtomicInteger(0);

            try {
                as2_4().loadSoundModel_2_4(soundModel, new ModelCallbackWrapper(callback),
                        (r, h) -> {
                            retval.set(r);
                            handle.set(h);
                        });
                handleHalStatusAllowBusy(retval.get(), "loadSoundModel_2_4");
            } catch (NotSupported e) {
                // Fall-back to the 2.1 version:
                try {
                    as2_1().loadSoundModel_2_1(soundModel, new ModelCallbackWrapper(callback),
                            0,
                            (r, h) -> {
                                retval.set(r);
                                handle.set(h);
                            });
                    handleHalStatus(retval.get(), "loadSoundModel_2_1");
                    mModelCallbacks.put(handle.get(), callback);
                } catch (NotSupported ee) {
                    // Fall-back to the 2.0 version:
                    return loadSoundModel_2_0(soundModel, callback);
                }
            }
            return handle.get();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public int loadPhraseSoundModel(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel soundModel,
            ModelCallback callback) {
        try {
            AtomicInteger retval = new AtomicInteger(-1);
            AtomicInteger handle = new AtomicInteger(0);
            try {
                as2_4().loadPhraseSoundModel_2_4(soundModel, new ModelCallbackWrapper(callback),
                        (r, h) -> {
                            retval.set(r);
                            handle.set(h);
                        });
                handleHalStatusAllowBusy(retval.get(), "loadPhraseSoundModel_2_4");
            } catch (NotSupported e) {
                // Fall-back to the 2.1 version:
                try {
                    as2_1().loadPhraseSoundModel_2_1(soundModel, new ModelCallbackWrapper(callback),
                            0,
                            (r, h) -> {
                                retval.set(r);
                                handle.set(h);
                            });
                    handleHalStatus(retval.get(), "loadPhraseSoundModel_2_1");
                    mModelCallbacks.put(handle.get(), callback);
                } catch (NotSupported ee) {
                    // Fall-back to the 2.0 version:
                    return loadPhraseSoundModel_2_0(soundModel, callback);
                }
            }
            return handle.get();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        try {
            // Safe if key doesn't exist.
            mModelCallbacks.remove(modelHandle);
            int retval = as2_0().unloadSoundModel(modelHandle);
            handleHalStatus(retval, "unloadSoundModel");
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        try {
            int retval = as2_0().stopRecognition(modelHandle);
            handleHalStatus(retval, "stopRecognition");
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

    }

    @Override
    public void startRecognition(int modelHandle,
            android.hardware.soundtrigger.V2_3.RecognitionConfig config) {
        try {
            try {
                int retval = as2_4().startRecognition_2_4(modelHandle, config);
                handleHalStatusAllowBusy(retval, "startRecognition_2_4");
            } catch (NotSupported e) {
                // Fall-back to the 2.3 version:
                try {
                    int retval = as2_3().startRecognition_2_3(modelHandle, config);
                    handleHalStatus(retval, "startRecognition_2_3");
                } catch (NotSupported ee) {
                    // Fall-back to the 2.0 version:
                    startRecognition_2_1(modelHandle, config);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void getModelState(int modelHandle) {
        try {
            int retval = as2_2().getModelState(modelHandle);
            handleHalStatus(retval, "getModelState");
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (NotSupported e) {
            throw e.throwAsRecoverableException();
        }
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        AtomicInteger status = new AtomicInteger(-1);
        AtomicInteger value = new AtomicInteger(0);
        try {
            as2_3().getParameter(modelHandle, param,
                    (s, v) -> {
                        status.set(s);
                        value.set(v);
                    });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (NotSupported e) {
            throw e.throwAsRecoverableException();
        }
        handleHalStatus(status.get(), "getParameter");
        return value.get();
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        try {
            int retval = as2_3().setParameter(modelHandle, param, value);
            handleHalStatus(retval, "setParameter");
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (NotSupported e) {
            throw e.throwAsRecoverableException();
        }
    }

    @Override
    public android.hardware.soundtrigger.V2_3.ModelParameterRange queryParameter(int modelHandle,
            int param) {
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<android.hardware.soundtrigger.V2_3.OptionalModelParameterRange>
                optionalRange =
                new AtomicReference<>();
        try {
            as2_3().queryParameter(modelHandle, param,
                    (s, r) -> {
                        status.set(s);
                        optionalRange.set(r);
                    });
        } catch (NotSupported e) {
            // For older drivers, we consider no model parameter to be supported.
            return null;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
        handleHalStatus(status.get(), "queryParameter");
        return (optionalRange.get().getDiscriminator()
                == android.hardware.soundtrigger.V2_3.OptionalModelParameterRange.hidl_discriminator.range)
                ?
                optionalRange.get().range() : null;
    }

    @Override
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        return mBinder.linkToDeath(recipient, cookie);
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mBinder.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() throws RemoteException {
        return as2_0().interfaceDescriptor();
    }

    @Override
    public void flushCallbacks() {
        // This is a no-op. Only implemented for decorators.
    }

    private android.hardware.soundtrigger.V2_3.Properties getProperties_2_0()
            throws RemoteException {
        AtomicInteger retval = new AtomicInteger(-1);
        AtomicReference<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties>
                properties =
                new AtomicReference<>();
        as2_0().getProperties(
                (r, p) -> {
                    retval.set(r);
                    properties.set(p);
                });
        handleHalStatus(retval.get(), "getProperties");
        return Hw2CompatUtil.convertProperties_2_0_to_2_3(properties.get());
    }

    private int loadSoundModel_2_0(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel soundModel,
            ModelCallback callback)
            throws RemoteException {
        // Convert the soundModel to V2.0.
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel model_2_0 =
                Hw2CompatUtil.convertSoundModel_2_1_to_2_0(soundModel);

        AtomicInteger retval = new AtomicInteger(-1);
        AtomicInteger handle = new AtomicInteger(0);
        as2_0().loadSoundModel(model_2_0, new ModelCallbackWrapper(callback), 0, (r, h) -> {
            retval.set(r);
            handle.set(h);
        });
        handleHalStatus(retval.get(), "loadSoundModel");
        mModelCallbacks.put(handle.get(), callback);
        return handle.get();
    }

    private int loadPhraseSoundModel_2_0(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel soundModel,
            ModelCallback callback)
            throws RemoteException {
        // Convert the soundModel to V2.0.
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel model_2_0 =
                Hw2CompatUtil.convertPhraseSoundModel_2_1_to_2_0(soundModel);

        AtomicInteger retval = new AtomicInteger(-1);
        AtomicInteger handle = new AtomicInteger(0);
        as2_0().loadPhraseSoundModel(model_2_0, new ModelCallbackWrapper(callback), 0,
                (r, h) -> {
                    retval.set(r);
                    handle.set(h);
                });
        handleHalStatus(retval.get(), "loadSoundModel");
        mModelCallbacks.put(handle.get(), callback);
        return handle.get();
    }

    private void startRecognition_2_1(int modelHandle,
            android.hardware.soundtrigger.V2_3.RecognitionConfig config) {
        try {
            try {
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig config_2_1 =
                        Hw2CompatUtil.convertRecognitionConfig_2_3_to_2_1(config);
                int retval = as2_1().startRecognition_2_1(modelHandle, config_2_1,
                        new ModelCallbackWrapper(mModelCallbacks.get(modelHandle)), 0);
                handleHalStatus(retval, "startRecognition_2_1");
            } catch (NotSupported e) {
                // Fall-back to the 2.0 version:
                startRecognition_2_0(modelHandle, config);
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private void startRecognition_2_0(int modelHandle,
            android.hardware.soundtrigger.V2_3.RecognitionConfig config)
            throws RemoteException {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig config_2_0 =
                Hw2CompatUtil.convertRecognitionConfig_2_3_to_2_0(config);
        int retval = as2_0().startRecognition(modelHandle, config_2_0,
                new ModelCallbackWrapper(mModelCallbacks.get(modelHandle)), 0);
        handleHalStatus(retval, "startRecognition");
    }

    private @NonNull
    android.hardware.soundtrigger.V2_0.ISoundTriggerHw as2_0() {
        return mUnderlying_2_0;
    }

    private @NonNull
    android.hardware.soundtrigger.V2_1.ISoundTriggerHw as2_1() throws NotSupported {
        if (mUnderlying_2_1 == null) {
            throw new NotSupported("Underlying driver version < 2.1");
        }
        return mUnderlying_2_1;
    }

    private @NonNull
    android.hardware.soundtrigger.V2_2.ISoundTriggerHw as2_2() throws NotSupported {
        if (mUnderlying_2_2 == null) {
            throw new NotSupported("Underlying driver version < 2.2");
        }
        return mUnderlying_2_2;
    }

    private @NonNull
    android.hardware.soundtrigger.V2_3.ISoundTriggerHw as2_3() throws NotSupported {
        if (mUnderlying_2_3 == null) {
            throw new NotSupported("Underlying driver version < 2.3");
        }
        return mUnderlying_2_3;
    }

    private @NonNull
    android.hardware.soundtrigger.V2_4.ISoundTriggerHw as2_4() throws NotSupported {
        if (mUnderlying_2_4 == null) {
            throw new NotSupported("Underlying driver version < 2.4");
        }
        return mUnderlying_2_4;
    }

    /**
     * A checked exception representing the requested interface version not being supported.
     * At the public interface layer, use {@link #throwAsRecoverableException()} to propagate it to
     * the caller if the request cannot be fulfilled.
     */
    private static class NotSupported extends Exception {
        NotSupported(String message) {
            super(message);
        }

        /**
         * Throw this as a recoverable exception.
         *
         * @return Never actually returns anything. Always throws. Used so that caller can write
         * throw e.throwAsRecoverableException().
         */
        RecoverableException throwAsRecoverableException() {
            throw new RecoverableException(Status.OPERATION_NOT_SUPPORTED, getMessage());
        }
    }

    private static class GlobalCallbackWrapper extends
            android.hardware.soundtrigger.V2_4.ISoundTriggerHwGlobalCallback.Stub {
        private final @NonNull GlobalCallback mDelegate;

        private GlobalCallbackWrapper(@NonNull GlobalCallback delegate) {
            mDelegate = delegate;
        }

        @Override
        public void tryAgain() {
            mDelegate.tryAgain();
        }
    }

    private static class ModelCallbackWrapper extends
            android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback.Stub {
        private final @NonNull ModelCallback mDelegate;

        private ModelCallbackWrapper(
                @NonNull ModelCallback delegate) {
            mDelegate = Objects.requireNonNull(delegate);
        }

        @Override
        public void modelUnloaded(int modelHandle) {
            mDelegate.modelUnloaded(modelHandle);
        }

        @Override
        public void recognitionCallback_2_1(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent event,
                int cookie) {
            mDelegate.recognitionCallback(event);
        }

        @Override
        public void phraseRecognitionCallback_2_1(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent event,
                int cookie) {
            mDelegate.phraseRecognitionCallback(event);
        }

        @Override
        public void soundModelCallback_2_1(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.ModelEvent event,
                int cookie) {
            // Nobody cares.
        }

        @Override
        public void recognitionCallback(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent event,
                int cookie) {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent event_2_1 =
                    Hw2CompatUtil.convertRecognitionEvent_2_0_to_2_1(event);
            mDelegate.recognitionCallback(event_2_1);
        }

        @Override
        public void phraseRecognitionCallback(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent event,
                int cookie) {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent
                    event_2_1 = Hw2CompatUtil.convertPhraseRecognitionEvent_2_0_to_2_1(event);
            mDelegate.phraseRecognitionCallback(event_2_1);
        }

        @Override
        public void soundModelCallback(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.ModelEvent event,
                int cookie) {
            // Nobody cares.
        }
    }
}
