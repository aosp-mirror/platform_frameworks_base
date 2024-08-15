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
import android.os.vibrator.VibratorInfoFactory;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
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
    private final ArrayList<MultiVibratorStateListener> mBrokenListeners = new ArrayList<>();

    @GuardedBy("mRegisteredListeners")
    private final ArrayMap<OnVibratorStateChangedListener, MultiVibratorStateListener>
            mRegisteredListeners = new ArrayMap<>();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private VibratorInfo mVibratorInfo;

    @UnsupportedAppUsage
    public SystemVibrator(Context context) {
        super(context);
        mContext = context;
        mVibratorManager = mContext.getSystemService(VibratorManager.class);
    }

    @Override
    public VibratorInfo getInfo() {
        synchronized (mLock) {
            if (mVibratorInfo != null) {
                return mVibratorInfo;
            }
            if (mVibratorManager == null) {
                Log.w(TAG, "Failed to retrieve vibrator info; no vibrator manager.");
                return VibratorInfo.EMPTY_VIBRATOR_INFO;
            }
            int[] vibratorIds = mVibratorManager.getVibratorIds();
            if (vibratorIds.length == 0) {
                // It is known that the device has no vibrator, so cache and return info that
                // reflects the lack of support for effects/primitives.
                return mVibratorInfo = VibratorInfo.EMPTY_VIBRATOR_INFO;
            }
            VibratorInfo[] vibratorInfos = new VibratorInfo[vibratorIds.length];
            for (int i = 0; i < vibratorIds.length; i++) {
                Vibrator vibrator = mVibratorManager.getVibrator(vibratorIds[i]);
                if (vibrator instanceof NullVibrator) {
                    Log.w(TAG, "Vibrator manager service not ready; "
                            + "Info not yet available for vibrator: " + vibratorIds[i]);
                    // This should never happen after the vibrator manager service is ready.
                    // Skip caching this vibrator until then.
                    return VibratorInfo.EMPTY_VIBRATOR_INFO;
                }
                vibratorInfos[i] = vibrator.getInfo();
            }
            return mVibratorInfo = VibratorInfoFactory.create(/* id= */ -1, vibratorInfos);
        }
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
        MultiVibratorStateListener delegate = null;
        try {
            synchronized (mRegisteredListeners) {
                // If listener is already registered, reject and return.
                if (mRegisteredListeners.containsKey(listener)) {
                    Log.w(TAG, "Listener already registered.");
                    return;
                }
                delegate = new MultiVibratorStateListener(executor, listener);
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
                MultiVibratorStateListener delegate = mRegisteredListeners.get(listener);
                delegate.unregister(mVibratorManager);
                mRegisteredListeners.remove(listener);
            }
        }
        tryUnregisterBrokenListeners();
    }

    @Override
    public boolean hasAmplitudeControl() {
        return getInfo().hasAmplitudeControl();
    }

    @Override
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId, VibrationEffect effect,
            VibrationAttributes attrs) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to set always-on effect; no vibrator manager.");
            return false;
        }
        CombinedVibration combinedEffect = CombinedVibration.createParallel(effect);
        return mVibratorManager.setAlwaysOnEffect(uid, opPkg, alwaysOnId, combinedEffect, attrs);
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect effect,
            String reason, @NonNull VibrationAttributes attributes) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager.");
            return;
        }
        CombinedVibration combinedEffect = CombinedVibration.createParallel(effect);
        mVibratorManager.vibrate(uid, opPkg, combinedEffect, reason, attributes);
    }

    @Override
    public void performHapticFeedback(int constant, String reason, int flags, int privFlags) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to perform haptic feedback; no vibrator manager.");
            return;
        }
        mVibratorManager.performHapticFeedback(constant, reason, flags, privFlags);
    }

    @Override
    public void performHapticFeedbackForInputDevice(int constant, int inputDeviceId,
            int inputSource, String reason, int flags, int privFlags) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to perform haptic feedback for input device; no vibrator manager.");
            return;
        }
        mVibratorManager.performHapticFeedbackForInputDevice(constant, inputDeviceId, inputSource,
                reason, flags, privFlags);
    }

    @Override
    public void cancel() {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to cancel vibrate; no vibrator manager.");
            return;
        }
        mVibratorManager.cancel();
    }

    @Override
    public void cancel(int usageFilter) {
        if (mVibratorManager == null) {
            Log.w(TAG, "Failed to cancel vibrate; no vibrator manager.");
            return;
        }
        mVibratorManager.cancel(usageFilter);
    }

    /**
     * Tries to unregister individual {@link android.os.Vibrator.OnVibratorStateChangedListener}
     * that were left registered to vibrators after failures to register them to all vibrators.
     *
     * <p>This might happen if {@link MultiVibratorStateListener} fails to register to any vibrator
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
        private final MultiVibratorStateListener mAllVibratorsListener;
        private final int mVibratorIdx;

        SingleVibratorStateListener(MultiVibratorStateListener listener, int vibratorIdx) {
            mAllVibratorsListener = listener;
            mVibratorIdx = vibratorIdx;
        }

        @Override
        public void onVibratorStateChanged(boolean isVibrating) {
            mAllVibratorsListener.onVibrating(mVibratorIdx, isVibrating);
        }
    }

    /**
     * Listener for all vibrators state change.
     *
     * <p>This registers a listener to all vibrators to merge the callbacks into a single state
     * that is set to true if any individual vibrator is also true, and false otherwise.
     *
     * @hide
     */
    @VisibleForTesting
    public static class MultiVibratorStateListener {
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

        public MultiVibratorStateListener(@NonNull Executor executor,
                @NonNull OnVibratorStateChangedListener listener) {
            mExecutor = executor;
            mDelegate = listener;
        }

        /** Returns true if at least one listener was registered to an individual vibrator. */
        public boolean hasRegisteredListeners() {
            synchronized (mLock) {
                return mVibratorListeners.size() > 0;
            }
        }

        /** Registers a listener to all individual vibrators in {@link VibratorManager}. */
        public void register(VibratorManager vibratorManager) {
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

        /** Unregisters the listeners from all individual vibrators in {@link VibratorManager}. */
        public void unregister(VibratorManager vibratorManager) {
            synchronized (mLock) {
                for (int i = mVibratorListeners.size(); --i >= 0; ) {
                    int vibratorId = mVibratorListeners.keyAt(i);
                    SingleVibratorStateListener listener = mVibratorListeners.valueAt(i);
                    vibratorManager.getVibrator(vibratorId).removeVibratorStateListener(listener);
                    mVibratorListeners.removeAt(i);
                }
            }
        }

        /** Callback triggered by {@link SingleVibratorStateListener} for each vibrator. */
        public void onVibrating(int vibratorIdx, boolean vibrating) {
            mExecutor.execute(() -> {
                boolean shouldNotifyStateChange;
                boolean isAnyVibrating;
                synchronized (mLock) {
                    // Bitmask indicating that all vibrators have been initialized.
                    int allInitializedMask = (1 << mVibratorListeners.size()) - 1;

                    // Save current global state before processing this vibrator state change.
                    boolean previousIsAnyVibrating = (mVibratingMask != 0);
                    boolean previousAreAllInitialized = (mInitializedMask == allInitializedMask);

                    // Mark this vibrator as initialized.
                    int vibratorMask = (1 << vibratorIdx);
                    mInitializedMask |= vibratorMask;

                    // Flip the vibrating bit flag for this vibrator, only if the state is changing.
                    boolean previousVibrating = (mVibratingMask & vibratorMask) != 0;
                    if (previousVibrating != vibrating) {
                        mVibratingMask ^= vibratorMask;
                    }

                    // Check new global state after processing this vibrator state change.
                    isAnyVibrating = (mVibratingMask != 0);
                    boolean areAllInitialized = (mInitializedMask == allInitializedMask);

                    // Prevent multiple triggers with the same state.
                    // Trigger once when all vibrators have reported their state, and then only when
                    // the merged vibrating state changes.
                    boolean isStateChanging = (previousIsAnyVibrating != isAnyVibrating);
                    shouldNotifyStateChange =
                            areAllInitialized && (!previousAreAllInitialized || isStateChanging);
                }
                // Notify delegate listener outside the lock, only if merged state is changing.
                if (shouldNotifyStateChange) {
                    mDelegate.onVibratorStateChanged(isAnyVibrating);
                }
            });
        }
    }
}
