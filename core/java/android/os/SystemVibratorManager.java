/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.annotation.Nullable;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * VibratorManager implementation that controls the system vibrators.
 *
 * @hide
 */
public class SystemVibratorManager extends VibratorManager {
    private static final String TAG = "VibratorManager";

    private final IVibratorManagerService mService;
    private final Context mContext;
    private final Binder mToken = new Binder();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int[] mVibratorIds;
    @GuardedBy("mLock")
    private final SparseArray<Vibrator> mVibrators = new SparseArray<>();

    @GuardedBy("mLock")
    private final ArrayMap<Vibrator.OnVibratorStateChangedListener,
            OnVibratorStateChangedListenerDelegate> mListeners = new ArrayMap<>();

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    public SystemVibratorManager(Context context) {
        super(context);
        mContext = context;
        mService = IVibratorManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VIBRATOR_MANAGER_SERVICE));
    }

    @NonNull
    @Override
    public int[] getVibratorIds() {
        synchronized (mLock) {
            if (mVibratorIds != null) {
                return mVibratorIds;
            }
            try {
                if (mService == null) {
                    Log.w(TAG, "Failed to retrieve vibrator ids; no vibrator manager service.");
                } else {
                    return mVibratorIds = mService.getVibratorIds();
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            return new int[0];
        }
    }

    @NonNull
    @Override
    public Vibrator getVibrator(int vibratorId) {
        synchronized (mLock) {
            Vibrator vibrator = mVibrators.get(vibratorId);
            if (vibrator != null) {
                return vibrator;
            }
            VibratorInfo info = null;
            try {
                if (mService == null) {
                    Log.w(TAG, "Failed to retrieve vibrator; no vibrator manager service.");
                } else {
                    info = mService.getVibratorInfo(vibratorId);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            if (info != null) {
                vibrator = new SingleVibrator(info);
                mVibrators.put(vibratorId, vibrator);
            } else {
                vibrator = NullVibrator.getInstance();
            }
            return vibrator;
        }
    }

    @NonNull
    @Override
    public Vibrator getDefaultVibrator() {
        return mContext.getSystemService(Vibrator.class);
    }

    @Override
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibration effect, @Nullable VibrationAttributes attributes) {
        if (mService == null) {
            Log.w(TAG, "Failed to set always-on effect; no vibrator manager service.");
            return false;
        }
        try {
            return mService.setAlwaysOnEffect(uid, opPkg, alwaysOnId, effect, attributes);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to set always-on effect.", e);
        }
        return false;
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull CombinedVibration effect,
            String reason, @Nullable VibrationAttributes attributes) {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator manager service.");
            return;
        }
        try {
            mService.vibrate(uid, mContext.getDeviceId(), opPkg, effect, attributes, reason,
                    mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to vibrate.", e);
        }
    }

    @Override
    public void performHapticFeedback(int constant, boolean always, String reason) {
        if (mService == null) {
            Log.w(TAG, "Failed to perform haptic feedback; no vibrator manager service.");
            return;
        }
        try {
            mService.performHapticFeedback(
                    Process.myUid(), mContext.getDeviceId(), mPackageName, constant, always, reason,
                    mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to perform haptic feedback.", e);
        }
    }

    @Override
    public void cancel() {
        cancelVibration(VibrationAttributes.USAGE_FILTER_MATCH_ALL);
    }

    @Override
    public void cancel(int usageFilter) {
        cancelVibration(usageFilter);
    }

    private void cancelVibration(int usageFilter) {
        if (mService == null) {
            Log.w(TAG, "Failed to cancel vibration; no vibrator manager service.");
            return;
        }
        try {
            mService.cancelVibrate(usageFilter, mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel vibration.", e);
        }
    }

    /** Listener for vibrations on a single vibrator. */
    private static class OnVibratorStateChangedListenerDelegate extends
            IVibratorStateListener.Stub {
        private final Executor mExecutor;
        private final Vibrator.OnVibratorStateChangedListener mListener;

        OnVibratorStateChangedListenerDelegate(
                @NonNull Vibrator.OnVibratorStateChangedListener listener,
                @NonNull Executor executor) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onVibrating(boolean isVibrating) {
            mExecutor.execute(() -> mListener.onVibratorStateChanged(isVibrating));
        }
    }

    /** Controls vibrations on a single vibrator. */
    private final class SingleVibrator extends Vibrator {
        private final VibratorInfo mVibratorInfo;

        SingleVibrator(@NonNull VibratorInfo vibratorInfo) {
            mVibratorInfo = vibratorInfo;
        }

        @Override
        public VibratorInfo getInfo() {
            return mVibratorInfo;
        }

        @Override
        public boolean hasVibrator() {
            return true;
        }

        @Override
        public boolean hasAmplitudeControl() {
            return mVibratorInfo.hasAmplitudeControl();
        }

        @Override
        public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
                @Nullable VibrationEffect effect, @Nullable VibrationAttributes attrs) {
            CombinedVibration combined = CombinedVibration.startParallel()
                    .addVibrator(mVibratorInfo.getId(), effect)
                    .combine();
            return SystemVibratorManager.this.setAlwaysOnEffect(uid, opPkg, alwaysOnId, combined,
                    attrs);
        }

        @Override
        public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe, String reason,
                @NonNull VibrationAttributes attributes) {
            CombinedVibration combined = CombinedVibration.startParallel()
                    .addVibrator(mVibratorInfo.getId(), vibe)
                    .combine();
            SystemVibratorManager.this.vibrate(uid, opPkg, combined, reason, attributes);
        }

        @Override
        public void performHapticFeedback(int effectId, boolean always, String reason) {
            SystemVibratorManager.this.performHapticFeedback(effectId, always, reason);
        }

        @Override
        public void cancel() {
            SystemVibratorManager.this.cancel();
        }

        @Override
        public void cancel(int usageFilter) {
            SystemVibratorManager.this.cancel(usageFilter);
        }

        @Override
        public boolean isVibrating() {
            if (mService == null) {
                Log.w(TAG, "Failed to check status of vibrator " + mVibratorInfo.getId()
                        + "; no vibrator service.");
                return false;
            }
            try {
                return mService.isVibrating(mVibratorInfo.getId());
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
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
            if (mService == null) {
                Log.w(TAG,
                        "Failed to add vibrate state listener to vibrator " + mVibratorInfo.getId()
                                + "; no vibrator service.");
                return;
            }
            synchronized (mLock) {
                // If listener is already registered, reject and return.
                if (mListeners.containsKey(listener)) {
                    Log.w(TAG, "Listener already registered.");
                    return;
                }
                try {
                    OnVibratorStateChangedListenerDelegate delegate =
                            new OnVibratorStateChangedListenerDelegate(listener, executor);
                    if (!mService.registerVibratorStateListener(mVibratorInfo.getId(), delegate)) {
                        Log.w(TAG, "Failed to add vibrate state listener to vibrator "
                                + mVibratorInfo.getId());
                        return;
                    }
                    mListeners.put(listener, delegate);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
            Objects.requireNonNull(listener);
            if (mService == null) {
                Log.w(TAG, "Failed to remove vibrate state listener from vibrator "
                        + mVibratorInfo.getId() + "; no vibrator service.");
                return;
            }
            synchronized (mLock) {
                // Check if the listener is registered, otherwise will return.
                if (mListeners.containsKey(listener)) {
                    OnVibratorStateChangedListenerDelegate delegate = mListeners.get(listener);
                    try {
                        if (!mService.unregisterVibratorStateListener(mVibratorInfo.getId(),
                                delegate)) {
                            Log.w(TAG, "Failed to remove vibrate state listener from vibrator "
                                    + mVibratorInfo.getId());
                            return;
                        }
                        mListeners.remove(listener);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            }
        }
    }
}
