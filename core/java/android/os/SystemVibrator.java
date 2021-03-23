/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.media.AudioAttributes;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Vibrator implementation that controls the main system vibrator.
 *
 * @hide
 */
public class SystemVibrator extends Vibrator {
    private static final String TAG = "Vibrator";

    private final VibratorManager mVibratorManager;
    private final Context mContext;

    @GuardedBy("mBrokenListeners")
    private final ArrayList<AllVibratorsStateListener> mBrokenListeners = new ArrayList<>();

    @GuardedBy("mRegisteredListeners")
    private final ArrayMap<OnVibratorStateChangedListener, AllVibratorsStateListener>
            mRegisteredListeners = new ArrayMap<>();

    @UnsupportedAppUsage
    public SystemVibrator(Context context) {
        super(context);
        mContext = context;
        mVibratorManager = mContext.getSystemService(VibratorManager.class);
    }

    @Override
    public boolean hasVibrator() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to check if vibrator exists; no vibrator manager.");
            return false;
        }
        return mVibratorManager.getVibratorIds().length > 0;
    }

    @Override
    public boolean isVibrating() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager.");
            return false;
        }
        for (int vibratorId : mVibratorManager.getVibratorIds()) {
            if (mVibratorManager.getVibrator(vibratorId).isVibrating()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        if (mContext == null) {
            Log.w(TAG, "Failed to add vibrate state listener; no vibrator context.");
            return;
        }
        addVibratorStateListener(mContext.getMainExecutor(), listener);
    }

    @Override
    public void addVibratorStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        Objects.requireNonNull(executor);
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to add vibrate state listener; no vibrator manager.");
            return;
        }
        AllVibratorsStateListener delegate = null;
        try {
            synchronized (mRegisteredListeners) {
                // If listener is already registered, reject and return.
                if (mRegisteredListeners.containsKey(listener)) {
                    Log.w(TAG, "Listener already registered.");
                    return;
                }
                delegate = new AllVibratorsStateListener(executor, listener);
                delegate.register(mVibratorManager);
                mRegisteredListeners.put(listener, delegate);
                delegate = null;
            }
        } finally {
            if (delegate != null && delegate.hasRegisteredListeners()) {
                // The delegate listener was left in a partial state with listeners registered to
                // some but not all vibrators. Keep track of this to try to unregister them later.
                synchronized (mBrokenListeners) {
                    mBrokenListeners.add(delegate);
                }
            }
            tryUnregisterBrokenListeners();
        }
    }

    @Override
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Objects.requireNonNull(listener);
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to remove vibrate state listener; no vibrator manager.");
            return;
        }
        synchronized (mRegisteredListeners) {
            if (mRegisteredListeners.containsKey(listener)) {
                AllVibratorsStateListener delegate = mRegisteredListeners.get(listener);
                delegate.unregister(mVibratorManager);
                mRegisteredListeners.remove(listener);
            }
        }
        tryUnregisterBrokenListeners();
    }

    @Override
    public boolean hasAmplitudeControl() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to check vibrator has amplitude control; no vibrator manager.");
            return false;
        }
        int[] vibratorIds = mVibratorManager.getVibratorIds();
        if (vibratorIds.length == 0) {
            return false;
        }
        for (int vibratorId : vibratorIds) {
            if (!mVibratorManager.getVibrator(vibratorId).hasAmplitudeControl()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId, VibrationEffect effect,
            AudioAttributes attributes) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to set always-on effect; no vibrator manager.");
            return false;
        }
        VibrationAttributes attr = new VibrationAttributes.Builder(attributes, effect).build();
        CombinedVibrationEffect combinedEffect = CombinedVibrationEffect.createSynced(effect);
        return mVibratorManager.setAlwaysOnEffect(uid, opPkg, alwaysOnId, combinedEffect, attr);
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect effect,
            String reason, @NonNull VibrationAttributes attributes) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager.");
            return;
        }
        CombinedVibrationEffect combinedEffect = CombinedVibrationEffect.createSynced(effect);
        mVibratorManager.vibrate(uid, opPkg, combinedEffect, reason, attributes);
    }

    @Override
    public int[] areEffectsSupported(@VibrationEffect.EffectType int... effectIds) {
        int[] supported = new int[effectIds.length];
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to check supported effects; no vibrator manager.");
            Arrays.fill(supported, Vibrator.VIBRATION_EFFECT_SUPPORT_NO);
            return supported;
        }
        int[] vibratorIds = mVibratorManager.getVibratorIds();
        if (vibratorIds.length == 0) {
            Arrays.fill(supported, Vibrator.VIBRATION_EFFECT_SUPPORT_NO);
            return supported;
        }
        int[][] vibratorSupportMap = new int[vibratorIds.length][effectIds.length];
        for (int i = 0; i < vibratorIds.length; i++) {
            vibratorSupportMap[i] = mVibratorManager.getVibrator(
                    vibratorIds[i]).areEffectsSupported(effectIds);
        }
        Arrays.fill(supported, Vibrator.VIBRATION_EFFECT_SUPPORT_YES);
        for (int effectIdx = 0; effectIdx < effectIds.length; effectIdx++) {
            for (int vibratorIdx = 0; vibratorIdx < vibratorIds.length; vibratorIdx++) {
                int effectSupported = vibratorSupportMap[vibratorIdx][effectIdx];
                if (effectSupported == Vibrator.VIBRATION_EFFECT_SUPPORT_NO) {
                    supported[effectIdx] = Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
                    break;
                } else if (effectSupported == Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN) {
                    supported[effectIdx] = Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN;
                }
            }
        }
        return supported;
    }

    @Override
    public boolean[] arePrimitivesSupported(
            @NonNull @VibrationEffect.Composition.PrimitiveType int... primitiveIds) {
        boolean[] supported = new boolean[primitiveIds.length];
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to check supported primitives; no vibrator manager.");
            Arrays.fill(supported, false);
            return supported;
        }
        int[] vibratorIds = mVibratorManager.getVibratorIds();
        if (vibratorIds.length == 0) {
            Arrays.fill(supported, false);
            return supported;
        }
        boolean[][] vibratorSupportMap = new boolean[vibratorIds.length][primitiveIds.length];
        for (int i = 0; i < vibratorIds.length; i++) {
            vibratorSupportMap[i] = mVibratorManager.getVibrator(
                    vibratorIds[i]).arePrimitivesSupported(primitiveIds);
        }
        Arrays.fill(supported, true);
        for (int primitiveIdx = 0; primitiveIdx < primitiveIds.length; primitiveIdx++) {
            for (int vibratorIdx = 0; vibratorIdx < vibratorIds.length; vibratorIdx++) {
                if (!vibratorSupportMap[vibratorIdx][primitiveIdx]) {
                    supported[primitiveIdx] = false;
                    break;
                }
            }
        }
        return supported;
    }

    @Override
    public void cancel() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to cancel vibrate; no vibrator manager.");
            return;
        }
        mVibratorManager.cancel();
    }

    /**
     * Tries to unregister individual {@link android.os.Vibrator.OnVibratorStateChangedListener}
     * that were left registered to vibrators after failures to register them to all vibrators.
     *
     * <p>This might happen if {@link AllVibratorsStateListener} fails to register to any vibrator
     * and also fails to unregister any previously registered single listeners to other vibrators.
     *
     * <p>This method never throws {@link RuntimeException} if it fails to unregister again, it will
     * fail silently and attempt to unregister the same broken listener later.
     */
    private void tryUnregisterBrokenListeners() {
        synchronized (mBrokenListeners) {
            try {
                for (int i = mBrokenListeners.size(); --i >= 0; ) {
                    mBrokenListeners.get(i).unregister(mVibratorManager);
                    mBrokenListeners.remove(i);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister broken listener", e);
            }
        }
    }

    /** Listener for a single vibrator state change. */
    private static class SingleVibratorStateListener implements OnVibratorStateChangedListener {
        private final AllVibratorsStateListener mAllVibratorsListener;
        private final int mVibratorIdx;

        SingleVibratorStateListener(AllVibratorsStateListener listener, int vibratorIdx) {
            mAllVibratorsListener = listener;
            mVibratorIdx = vibratorIdx;
        }

        @Override
        public void onVibratorStateChanged(boolean isVibrating) {
            mAllVibratorsListener.onVibrating(mVibratorIdx, isVibrating);
        }
    }

    /** Listener for all vibrators state change. */
    private static class AllVibratorsStateListener {
        private final Object mLock = new Object();
        private final Executor mExecutor;
        private final OnVibratorStateChangedListener mDelegate;

        @GuardedBy("mLock")
        private final SparseArray<SingleVibratorStateListener> mVibratorListeners =
                new SparseArray<>();

        @GuardedBy("mLock")
        private int mInitializedMask;
        @GuardedBy("mLock")
        private int mVibratingMask;

        AllVibratorsStateListener(@NonNull Executor executor,
                @NonNull OnVibratorStateChangedListener listener) {
            mExecutor = executor;
            mDelegate = listener;
        }

        boolean hasRegisteredListeners() {
            synchronized (mLock) {
                return mVibratorListeners.size() > 0;
            }
        }

        void register(VibratorManager vibratorManager) {
            int[] vibratorIds = vibratorManager.getVibratorIds();
            synchronized (mLock) {
                for (int i = 0; i < vibratorIds.length; i++) {
                    int vibratorId = vibratorIds[i];
                    SingleVibratorStateListener listener = new SingleVibratorStateListener(this, i);
                    try {
                        vibratorManager.getVibrator(vibratorId).addVibratorStateListener(mExecutor,
                                listener);
                        mVibratorListeners.put(vibratorId, listener);
                    } catch (RuntimeException e) {
                        try {
                            unregister(vibratorManager);
                        } catch (RuntimeException e1) {
                            Log.w(TAG,
                                    "Failed to unregister listener while recovering from a failed "
                                            + "register call", e1);
                        }
                        throw e;
                    }
                }
            }
        }

        void unregister(VibratorManager vibratorManager) {
            synchronized (mLock) {
                for (int i = mVibratorListeners.size(); --i >= 0; ) {
                    int vibratorId = mVibratorListeners.keyAt(i);
                    SingleVibratorStateListener listener = mVibratorListeners.valueAt(i);
                    vibratorManager.getVibrator(vibratorId).removeVibratorStateListener(listener);
                    mVibratorListeners.removeAt(i);
                }
            }
        }

        void onVibrating(int vibratorIdx, boolean vibrating) {
            mExecutor.execute(() -> {
                boolean anyVibrating;
                synchronized (mLock) {
                    int allInitializedMask = 1 << mVibratorListeners.size() - 1;
                    int vibratorMask = 1 << vibratorIdx;
                    if ((mInitializedMask & vibratorMask) == 0) {
                        // First state report for this vibrator, set vibrating initial value.
                        mInitializedMask |= vibratorMask;
                        mVibratingMask |= vibrating ? vibratorMask : 0;
                    } else {
                        // Flip vibrating value, if changed.
                        boolean prevVibrating = (mVibratingMask & vibratorMask) != 0;
                        if (prevVibrating != vibrating) {
                            mVibratingMask ^= vibratorMask;
                        }
                    }
                    if (mInitializedMask != allInitializedMask) {
                        // Wait for all vibrators initial state to be reported before delegating.
                        return;
                    }
                    anyVibrating = mVibratingMask != 0;
                }
                mDelegate.onVibratorStateChanged(anyVibrating);
            });
        }
    }
}
