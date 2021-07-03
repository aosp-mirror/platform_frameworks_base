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

package android.hardware.input;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.Context;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.IVibratorStateListener;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;

/**
 * Vibrator implementation that communicates with the input device vibrators.
 */
final class InputDeviceVibrator extends Vibrator {
    private static final String TAG = "InputDeviceVibrator";

    // mDeviceId represents InputDevice ID the vibrator belongs to
    private final int mDeviceId;
    private final VibratorInfo mVibratorInfo;
    private final Binder mToken;
    private final InputManager mInputManager;

    @GuardedBy("mDelegates")
    private final ArrayMap<OnVibratorStateChangedListener,
            OnVibratorStateChangedListenerDelegate> mDelegates = new ArrayMap<>();

    InputDeviceVibrator(InputManager inputManager, int deviceId, int vibratorId) {
        mInputManager = inputManager;
        mDeviceId = deviceId;
        mVibratorInfo = new VibratorInfo.Builder(vibratorId)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                // The supported effect and braking lists are known to be empty for input devices,
                // which is different from not being set (that means the device support is unknown).
                .setSupportedEffects(new int[0])
                .setSupportedBraking(new int[0])
                .build();
        mToken = new Binder();
    }

    private class OnVibratorStateChangedListenerDelegate extends
            IVibratorStateListener.Stub {
        private final Executor mExecutor;
        private final OnVibratorStateChangedListener mListener;

        OnVibratorStateChangedListenerDelegate(@NonNull OnVibratorStateChangedListener listener,
                @NonNull Executor executor) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onVibrating(boolean isVibrating) {
            mExecutor.execute(() -> mListener.onVibratorStateChanged(isVibrating));
        }
    }

    @Override
    protected VibratorInfo getInfo() {
        return mVibratorInfo;
    }

    @Override
    public boolean hasVibrator() {
        return true;
    }

    @Override
    public boolean isVibrating() {
        return mInputManager.isVibrating(mDeviceId);
    }

    /**
     * Adds a listener for vibrator state changes. Callbacks will be executed on the main thread.
     * If the listener was previously added and not removed, this call will be ignored.
     *
     * @param listener listener to be added
     */
    @Override
    public void addVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Preconditions.checkNotNull(listener);
        Context context = ActivityThread.currentApplication();
        addVibratorStateListener(context.getMainExecutor(), listener);
    }

    /**
     * Adds a listener for vibrator state change. If the listener was previously added and not
     * removed, this call will be ignored.
     *
     * @param listener Listener to be added.
     * @param executor The {@link Executor} on which the listener's callbacks will be executed on.
     */
    @Override
    public void addVibratorStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnVibratorStateChangedListener listener) {
        Preconditions.checkNotNull(listener);
        Preconditions.checkNotNull(executor);

        synchronized (mDelegates) {
            // If listener is already registered, reject and return.
            if (mDelegates.containsKey(listener)) {
                Log.w(TAG, "Listener already registered.");
                return;
            }

            final OnVibratorStateChangedListenerDelegate delegate =
                    new OnVibratorStateChangedListenerDelegate(listener, executor);
            if (!mInputManager.registerVibratorStateListener(mDeviceId, delegate)) {
                Log.w(TAG, "Failed to register vibrate state listener");
                return;
            }
            mDelegates.put(listener, delegate);

        }
    }

    /**
     * Removes the listener for vibrator state changes. If the listener was not previously
     * registered, this call will do nothing.
     *
     * @param listener Listener to be removed.
     */
    @Override
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        Preconditions.checkNotNull(listener);

        synchronized (mDelegates) {
            // Check if the listener is registered, otherwise will return.
            if (mDelegates.containsKey(listener)) {
                final OnVibratorStateChangedListenerDelegate delegate = mDelegates.get(listener);

                if (!mInputManager.unregisterVibratorStateListener(mDeviceId, delegate)) {
                    Log.w(TAG, "Failed to unregister vibrate state listener");
                    return;
                }
                mDelegates.remove(listener);
            }
        }
    }

    @Override
    public boolean hasAmplitudeControl() {
        return mVibratorInfo.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL);
    }

    /**
     * @hide
     */
    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect effect,
            String reason, @NonNull VibrationAttributes attributes) {
        mInputManager.vibrate(mDeviceId, effect, mToken);
    }

    @Override
    public void cancel() {
        mInputManager.cancelVibrate(mDeviceId, mToken);
    }

    @Override
    public void cancel(int usageFilter) {
        cancel();
    }
}
