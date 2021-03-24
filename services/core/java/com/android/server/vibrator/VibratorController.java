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
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
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

    VibratorController(int vibratorId, OnVibrationCompleteListener listener) {
        this(vibratorId, listener, new NativeWrapper());
    }

    @VisibleForTesting
    VibratorController(int vibratorId, OnVibrationCompleteListener listener,
            NativeWrapper nativeWrapper) {
        mNativeWrapper = nativeWrapper;
        mNativeWrapper.init(vibratorId, listener);

        mVibratorInfo = new VibratorInfo(vibratorId, nativeWrapper.getCapabilities(),
                nativeWrapper.getSupportedEffects(), nativeWrapper.getSupportedPrimitives(),
                nativeWrapper.getResonantFrequency(), nativeWrapper.getQFactor());
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
    public void updateAlwaysOn(int id, @Nullable PrebakedSegment prebaked) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            return;
        }
        synchronized (mLock) {
            if (prebaked == null) {
                mNativeWrapper.alwaysOnDisable(id);
            } else {
                mNativeWrapper.alwaysOnEnable(id, prebaked.getEffectId(),
                        prebaked.getEffectStrength());
            }
        }
    }

    /** Set the vibration amplitude. This will NOT affect the state of {@link #isVibrating()}. */
    public void setAmplitude(float amplitude) {
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
    public long on(PrebakedSegment prebaked, long vibrationId) {
        synchronized (mLock) {
            long duration = mNativeWrapper.perform(prebaked.getEffectId(),
                    prebaked.getEffectStrength(), vibrationId);
            if (duration > 0) {
                notifyVibratorOnLocked();
            }
            return duration;
        }
    }

    /**
     * Plays a composition of vibration primitives, using {@code vibrationId} or completion callback
     * to {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The duration of the effect playing, or 0 if unsupported.
     */
    public long on(PrimitiveSegment[] primitives, long vibrationId) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
            return 0;
        }
        synchronized (mLock) {
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
        /**
         * Initializes the native part of this controller, creating a global reference to given
         * {@link OnVibrationCompleteListener} and returns a newly allocated native pointer. This
         * wrapper is responsible for deleting this pointer by calling the method pointed
         * by {@link #getNativeFinalizer()}.
         *
         * <p><b>Note:</b> Make sure the given implementation of {@link OnVibrationCompleteListener}
         * do not hold any strong reference to the instance responsible for deleting the returned
         * pointer, to avoid creating a cyclic GC root reference.
         */
        private static native long nativeInit(int vibratorId, OnVibrationCompleteListener listener);

        /**
         * Returns pointer to native function responsible for cleaning up the native pointer
         * allocated and returned by {@link #nativeInit(int, OnVibrationCompleteListener)}.
         */
        private static native long getNativeFinalizer();
        private static native boolean isAvailable(long nativePtr);
        private static native void on(long nativePtr, long milliseconds, long vibrationId);
        private static native void off(long nativePtr);
        private static native void setAmplitude(long nativePtr, float amplitude);
        private static native int[] getSupportedEffects(long nativePtr);
        private static native int[] getSupportedPrimitives(long nativePtr);
        private static native long performEffect(long nativePtr, long effect, long strength,
                long vibrationId);
        private static native long performComposedEffect(long nativePtr, PrimitiveSegment[] effect,
                long vibrationId);
        private static native void setExternalControl(long nativePtr, boolean enabled);
        private static native long getCapabilities(long nativePtr);
        private static native void alwaysOnEnable(long nativePtr, long id, long effect,
                long strength);
        private static native void alwaysOnDisable(long nativePtr, long id);
        private static native float getResonantFrequency(long nativePtr);
        private static native float getQFactor(long nativePtr);

        private long mNativePtr = 0;

        /** Initializes native controller and allocation registry to destroy native instances. */
        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            mNativePtr = nativeInit(vibratorId, listener);
            long finalizerPtr = getNativeFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorController.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativePtr);
            }
        }

        /** Check if the vibrator is currently available. */
        public boolean isAvailable() {
            return isAvailable(mNativePtr);
        }

        /** Turns vibrator on for given time. */
        public void on(long milliseconds, long vibrationId) {
            on(mNativePtr, milliseconds, vibrationId);
        }

        /** Turns vibrator off. */
        public void off() {
            off(mNativePtr);
        }

        /** Sets the amplitude for the vibrator to run. */
        public void setAmplitude(float amplitude) {
            setAmplitude(mNativePtr, amplitude);
        }

        /** Returns all predefined effects supported by the device vibrator. */
        public int[] getSupportedEffects() {
            return getSupportedEffects(mNativePtr);
        }

        /** Returns all compose primitives supported by the device vibrator. */
        public int[] getSupportedPrimitives() {
            return getSupportedPrimitives(mNativePtr);
        }

        /** Turns vibrator on to perform one of the supported effects. */
        public long perform(long effect, long strength, long vibrationId) {
            return performEffect(mNativePtr, effect, strength, vibrationId);
        }

        /** Turns vibrator on to perform one of the supported composed effects. */
        public long compose(PrimitiveSegment[] primitives, long vibrationId) {
            return performComposedEffect(mNativePtr, primitives, vibrationId);
        }

        /** Enabled the device vibrator to be controlled by another service. */
        public void setExternalControl(boolean enabled) {
            setExternalControl(mNativePtr, enabled);
        }

        /** Returns all capabilities of the device vibrator. */
        public long getCapabilities() {
            return getCapabilities(mNativePtr);
        }

        /** Enable always-on vibration with given id and effect. */
        public void alwaysOnEnable(long id, long effect, long strength) {
            alwaysOnEnable(mNativePtr, id, effect, strength);
        }

        /** Disable always-on vibration for given id. */
        public void alwaysOnDisable(long id) {
            alwaysOnDisable(mNativePtr, id);
        }

        /** Gets the vibrator's resonant frequency (F0) */
        public float getResonantFrequency() {
            return getResonantFrequency(mNativePtr);
        }

        /** Gets the vibrator's Q factor */
        public float getQFactor() {
            return getQFactor(mNativePtr);
        }
    }
}
