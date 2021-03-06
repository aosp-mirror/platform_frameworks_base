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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.IVibratorStateListener;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.NativeAllocationRegistry;

/** Controls a single vibrator. */
final class VibratorController {
    private static final String TAG = "VibratorController";

    private final Object mLock = new Object();
    private final NativeWrapper mNativeWrapper;
    private final VibratorInfo mVibratorInfo;

    @GuardedBy("mLock")
    private final RemoteCallbackList<IVibratorStateListener> mVibratorStateListeners =
            new RemoteCallbackList<>();
    @GuardedBy("mLock")
    private boolean mIsVibrating;
    @GuardedBy("mLock")
    private boolean mIsUnderExternalControl;

    /** Listener for vibration completion callbacks from native. */
    public interface OnVibrationCompleteListener {

        /** Callback triggered when vibration is complete. */
        void onComplete(int vibratorId, long vibrationId);
    }

    /**
     * Initializes the native part of this controller, creating a global reference to given
     * {@link OnVibrationCompleteListener} and returns a newly allocated native pointer. This
     * wrapper is responsible for deleting this pointer by calling the method pointed
     * by {@link #vibratorGetFinalizer()}.
     *
     * <p><b>Note:</b> Make sure the given implementation of {@link OnVibrationCompleteListener}
     * do not hold any strong reference to the instance responsible for deleting the returned
     * pointer, to avoid creating a cyclic GC root reference.
     */
    static native long vibratorInit(int vibratorId, OnVibrationCompleteListener listener);

    /**
     * Returns pointer to native function responsible for cleaning up the native pointer allocated
     * and returned by {@link #vibratorInit(int, OnVibrationCompleteListener)}.
     */
    static native long vibratorGetFinalizer();

    static native boolean vibratorIsAvailable(long nativePtr);

    static native void vibratorOn(long nativePtr, long milliseconds, long vibrationId);

    static native void vibratorOff(long nativePtr);

    static native void vibratorSetAmplitude(long nativePtr, int amplitude);

    static native int[] vibratorGetSupportedEffects(long nativePtr);

    static native int[] vibratorGetSupportedPrimitives(long nativePtr);

    static native long vibratorPerformEffect(
            long nativePtr, long effect, long strength, long vibrationId);

    static native long vibratorPerformComposedEffect(
            long nativePtr, VibrationEffect.Composition.PrimitiveEffect[] effect, long vibrationId);

    static native void vibratorSetExternalControl(long nativePtr, boolean enabled);

    static native long vibratorGetCapabilities(long nativePtr);

    static native void vibratorAlwaysOnEnable(long nativePtr, long id, long effect, long strength);

    static native void vibratorAlwaysOnDisable(long nativePtr, long id);

    VibratorController(int vibratorId, OnVibrationCompleteListener listener) {
        this(vibratorId, listener, new NativeWrapper());
    }

    @VisibleForTesting
    VibratorController(int vibratorId, OnVibrationCompleteListener listener,
            NativeWrapper nativeWrapper) {
        mNativeWrapper = nativeWrapper;
        mNativeWrapper.init(vibratorId, listener);

        mVibratorInfo = new VibratorInfo(vibratorId, nativeWrapper.getCapabilities(),
                nativeWrapper.getSupportedEffects(), nativeWrapper.getSupportedPrimitives());
    }

    /** Register state listener for this vibrator. */
    public boolean registerVibratorStateListener(IVibratorStateListener listener) {
        synchronized (mLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mVibratorStateListeners.register(listener)) {
                    return false;
                }
                // Notify its callback after new client registered.
                notifyStateListenerLocked(listener);
                return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** Remove registered state listener for this vibrator. */
    public boolean unregisterVibratorStateListener(IVibratorStateListener listener) {
        synchronized (mLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                return mVibratorStateListeners.unregister(listener);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** Return the {@link VibratorInfo} representing the vibrator controlled by this instance. */
    public VibratorInfo getVibratorInfo() {
        return mVibratorInfo;
    }

    /**
     * Return {@code true} is this vibrator is currently vibrating, false otherwise.
     *
     * <p>This state is controlled by calls to {@link #on} and {@link #off} methods, and is
     * automatically notified to any registered {@link IVibratorStateListener} on change.
     */
    public boolean isVibrating() {
        synchronized (mLock) {
            return mIsVibrating;
        }
    }

    /** Return {@code true} if this vibrator is under external control, false otherwise. */
    public boolean isUnderExternalControl() {
        synchronized (mLock) {
            return mIsUnderExternalControl;
        }
    }

    /**
     * Check against this vibrator capabilities.
     *
     * @param capability one of IVibrator.CAP_*
     * @return true if this vibrator has this capability, false otherwise
     */
    public boolean hasCapability(long capability) {
        return mVibratorInfo.hasCapability(capability);
    }

    /** Return {@code true} if the underlying vibrator is currently available, false otherwise. */
    public boolean isAvailable() {
        return mNativeWrapper.isAvailable();
    }

    /**
     * Set the vibrator control to be external or not, based on given flag.
     *
     * <p>This will affect the state of {@link #isUnderExternalControl()}.
     */
    public void setExternalControl(boolean externalControl) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
            return;
        }
        synchronized (mLock) {
            mIsUnderExternalControl = externalControl;
            mNativeWrapper.setExternalControl(externalControl);
        }
    }

    /**
     * Update the predefined vibration effect saved with given id. This will remove the saved effect
     * if given {@code effect} is {@code null}.
     */
    public void updateAlwaysOn(int id, @Nullable VibrationEffect.Prebaked effect) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            return;
        }
        synchronized (mLock) {
            if (effect == null) {
                mNativeWrapper.alwaysOnDisable(id);
            } else {
                mNativeWrapper.alwaysOnEnable(id, effect.getId(), effect.getEffectStrength());
            }
        }
    }

    /** Set the vibration amplitude. This will NOT affect the state of {@link #isVibrating()}. */
    public void setAmplitude(int amplitude) {
        synchronized (mLock) {
            if (mVibratorInfo.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                mNativeWrapper.setAmplitude(amplitude);
            }
        }
    }

    /**
     * Turn on the vibrator for {@code milliseconds} time, using {@code vibrationId} or completion
     * callback to {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     */
    public void on(long milliseconds, long vibrationId) {
        synchronized (mLock) {
            mNativeWrapper.on(milliseconds, vibrationId);
            notifyVibratorOnLocked();
        }
    }

    /**
     * Plays predefined vibration effect, using {@code vibrationId} or completion callback to
     * {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The duration of the effect playing, or 0 if unsupported.
     */
    public long on(VibrationEffect.Prebaked effect, long vibrationId) {
        synchronized (mLock) {
            long duration = mNativeWrapper.perform(effect.getId(), effect.getEffectStrength(),
                    vibrationId);
            if (duration > 0) {
                notifyVibratorOnLocked();
            }
            return duration;
        }
    }

    /**
     * Plays composited vibration effect, using {@code vibrationId} or completion callback to
     * {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The duration of the effect playing, or 0 if unsupported.
     */
    public long on(VibrationEffect.Composed effect, long vibrationId) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
            return 0;
        }
        synchronized (mLock) {
            VibrationEffect.Composition.PrimitiveEffect[] primitives =
                    effect.getPrimitiveEffects().toArray(
                            new VibrationEffect.Composition.PrimitiveEffect[0]);
            long duration = mNativeWrapper.compose(primitives, vibrationId);
            if (duration > 0) {
                notifyVibratorOnLocked();
            }
            return duration;
        }
    }

    /** Turns off the vibrator.This will affect the state of {@link #isVibrating()}. */
    public void off() {
        synchronized (mLock) {
            mNativeWrapper.off();
            notifyVibratorOffLocked();
        }
    }

    @Override
    public String toString() {
        return "VibratorController{"
                + "mVibratorInfo=" + mVibratorInfo
                + ", mIsVibrating=" + mIsVibrating
                + ", mIsUnderExternalControl=" + mIsUnderExternalControl
                + ", mVibratorStateListeners count="
                + mVibratorStateListeners.getRegisteredCallbackCount()
                + '}';
    }

    @GuardedBy("mLock")
    private void notifyVibratorOnLocked() {
        if (!mIsVibrating) {
            mIsVibrating = true;
            notifyStateListenersLocked();
        }
    }

    @GuardedBy("mLock")
    private void notifyVibratorOffLocked() {
        if (mIsVibrating) {
            mIsVibrating = false;
            notifyStateListenersLocked();
        }
    }

    @GuardedBy("mLock")
    private void notifyStateListenersLocked() {
        final int length = mVibratorStateListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                notifyStateListenerLocked(mVibratorStateListeners.getBroadcastItem(i));
            }
        } finally {
            mVibratorStateListeners.finishBroadcast();
        }
    }

    @GuardedBy("mLock")
    private void notifyStateListenerLocked(IVibratorStateListener listener) {
        try {
            listener.onVibrating(mIsVibrating);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator state listener failed to call", e);
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorController} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativePtr = 0;

        /** Initializes native controller and allocation registry to destroy native instances. */
        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            mNativePtr = VibratorController.vibratorInit(vibratorId, listener);
            long finalizerPtr = VibratorController.vibratorGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorController.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativePtr);
            }
        }

        /** Check if the vibrator is currently available. */
        public boolean isAvailable() {
            return VibratorController.vibratorIsAvailable(mNativePtr);
        }

        /** Turns vibrator on for given time. */
        public void on(long milliseconds, long vibrationId) {
            VibratorController.vibratorOn(mNativePtr, milliseconds, vibrationId);
        }

        /** Turns vibrator off. */
        public void off() {
            VibratorController.vibratorOff(mNativePtr);
        }

        /** Sets the amplitude for the vibrator to run. */
        public void setAmplitude(int amplitude) {
            VibratorController.vibratorSetAmplitude(mNativePtr, amplitude);
        }

        /** Returns all predefined effects supported by the device vibrator. */
        public int[] getSupportedEffects() {
            return VibratorController.vibratorGetSupportedEffects(mNativePtr);
        }

        /** Returns all compose primitives supported by the device vibrator. */
        public int[] getSupportedPrimitives() {
            return VibratorController.vibratorGetSupportedPrimitives(mNativePtr);
        }

        /** Turns vibrator on to perform one of the supported effects. */
        public long perform(long effect, long strength, long vibrationId) {
            return VibratorController.vibratorPerformEffect(
                    mNativePtr, effect, strength, vibrationId);
        }

        /** Turns vibrator on to perform one of the supported composed effects. */
        public long compose(
                VibrationEffect.Composition.PrimitiveEffect[] effect, long vibrationId) {
            return VibratorController.vibratorPerformComposedEffect(mNativePtr, effect,
                    vibrationId);
        }

        /** Enabled the device vibrator to be controlled by another service. */
        public void setExternalControl(boolean enabled) {
            VibratorController.vibratorSetExternalControl(mNativePtr, enabled);
        }

        /** Returns all capabilities of the device vibrator. */
        public long getCapabilities() {
            return VibratorController.vibratorGetCapabilities(mNativePtr);
        }

        /** Enable always-on vibration with given id and effect. */
        public void alwaysOnEnable(long id, long effect, long strength) {
            VibratorController.vibratorAlwaysOnEnable(mNativePtr, id, effect, strength);
        }

        /** Disable always-on vibration for given id. */
        public void alwaysOnDisable(long id) {
            VibratorController.vibratorAlwaysOnDisable(mNativePtr, id);
        }
    }
}
