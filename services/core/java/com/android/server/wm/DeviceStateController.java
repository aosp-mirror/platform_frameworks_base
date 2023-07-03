/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Class that listens for a callback from display manager and responds to device state
 * changes.
 */
final class DeviceStateController {

    // Used to synchronize WindowManager services call paths with DeviceStateManager's callbacks.
    @NonNull
    private final WindowManagerGlobalLock mWmLock;
    @NonNull
    private final int[] mOpenDeviceStates;
    @NonNull
    private final int[] mHalfFoldedDeviceStates;
    @NonNull
    private final int[] mFoldedDeviceStates;
    @NonNull
    private final int[] mRearDisplayDeviceStates;
    private final int mConcurrentDisplayDeviceState;
    @NonNull
    private final int[] mReverseRotationAroundZAxisStates;
    @GuardedBy("mWmLock")
    @NonNull
    @VisibleForTesting
    final Map<Consumer<DeviceState>, Executor> mDeviceStateCallbacks = new ArrayMap<>();

    private final boolean mMatchBuiltInDisplayOrientationToDefaultDisplay;

    @NonNull
    private DeviceState mCurrentDeviceState = DeviceState.UNKNOWN;
    private int mCurrentState;

    public enum DeviceState {
        UNKNOWN,
        OPEN,
        FOLDED,
        HALF_FOLDED,
        REAR,
        CONCURRENT,
    }

    DeviceStateController(@NonNull Context context, @NonNull WindowManagerGlobalLock wmLock) {
        mWmLock = wmLock;

        mOpenDeviceStates = context.getResources()
                .getIntArray(R.array.config_openDeviceStates);
        mHalfFoldedDeviceStates = context.getResources()
                .getIntArray(R.array.config_halfFoldedDeviceStates);
        mFoldedDeviceStates = context.getResources()
                .getIntArray(R.array.config_foldedDeviceStates);
        mRearDisplayDeviceStates = context.getResources()
                .getIntArray(R.array.config_rearDisplayDeviceStates);
        mConcurrentDisplayDeviceState = context.getResources()
                .getInteger(R.integer.config_deviceStateConcurrentRearDisplay);
        mReverseRotationAroundZAxisStates = context.getResources()
                .getIntArray(R.array.config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis);
        mMatchBuiltInDisplayOrientationToDefaultDisplay = context.getResources()
                .getBoolean(R.bool
                        .config_matchSecondaryInternalDisplaysOrientationToReverseDefaultDisplay);
    }

    /**
     * Registers a callback to be notified when the device state changes. Callers should always
     * post the work onto their own worker thread to avoid holding the WindowManagerGlobalLock for
     * an extended period of time.
     */
    void registerDeviceStateCallback(@NonNull Consumer<DeviceState> callback,
            @NonNull @CallbackExecutor Executor executor) {
        synchronized (mWmLock) {
            mDeviceStateCallbacks.put(callback, executor);
        }
    }

    void unregisterDeviceStateCallback(@NonNull Consumer<DeviceState> callback) {
        synchronized (mWmLock) {
            mDeviceStateCallbacks.remove(callback);
        }
    }

    /**
     * @return true if the rotation direction on the Z axis should be reversed for the default
     * display.
     */
    boolean shouldReverseRotationDirectionAroundZAxis(@NonNull DisplayContent displayContent) {
        if (!displayContent.isDefaultDisplay) {
            return false;
        }
        return ArrayUtils.contains(mReverseRotationAroundZAxisStates, mCurrentState);
    }

    /**
     * @return true if non-default built-in displays should match the default display's rotation.
     */
    boolean shouldMatchBuiltInDisplayOrientationToReverseDefaultDisplay() {
        // TODO(b/265991392): This should come from display_settings.xml once it's easier to
        //  extend with complex configurations.
        return mMatchBuiltInDisplayOrientationToDefaultDisplay;
    }

    /**
     * This event is sent from DisplayManager just before the device state is applied to
     * the displays. This is needed to make sure that we first receive this callback before
     * any device state related display updates from the DisplayManager.
     *
     * The flow for this event is the following:
     *  - {@link DeviceStateManager} sends event to {@link android.hardware.display.DisplayManager}
     *  - {@link android.hardware.display.DisplayManager} sends it to {@link WindowManagerInternal}
     *  - {@link WindowManagerInternal} eventually calls this method
     *
     * @param state device state as defined by {@link DeviceStateManager}
     */
    public void onDeviceStateReceivedByDisplayManager(int state) {
        mCurrentState = state;

        final DeviceState deviceState;
        if (ArrayUtils.contains(mHalfFoldedDeviceStates, state)) {
            deviceState = DeviceState.HALF_FOLDED;
        } else if (ArrayUtils.contains(mFoldedDeviceStates, state)) {
            deviceState = DeviceState.FOLDED;
        } else if (ArrayUtils.contains(mRearDisplayDeviceStates, state)) {
            deviceState = DeviceState.REAR;
        } else if (ArrayUtils.contains(mOpenDeviceStates, state)) {
            deviceState = DeviceState.OPEN;
        } else if (state == mConcurrentDisplayDeviceState) {
            deviceState = DeviceState.CONCURRENT;
        } else {
            deviceState = DeviceState.UNKNOWN;
        }

        if (mCurrentDeviceState == null || !mCurrentDeviceState.equals(deviceState)) {
            mCurrentDeviceState = deviceState;

            // Make a copy here because it's possible that the consumer tries to remove a callback
            // while we're still iterating through the list, which would end up in a
            // ConcurrentModificationException. Note that cannot use a List<Map.Entry> because the
            // entries are tied to the backing map. So, if a client removes a callback while
            // we are notifying clients, we will get a NPE.
            final List<Pair<Consumer<DeviceState>, Executor>> entries = copyDeviceStateCallbacks();

            for (int i = 0; i < entries.size(); i++) {
                final Pair<Consumer<DeviceState>, Executor> entry = entries.get(i);
                entry.second.execute(() -> entry.first.accept(deviceState));
            }
        }
    }

    @VisibleForTesting
    @NonNull
    List<Pair<Consumer<DeviceState>, Executor>> copyDeviceStateCallbacks() {
        final List<Pair<Consumer<DeviceState>, Executor>> entries = new ArrayList<>();

        synchronized (mWmLock) {
            mDeviceStateCallbacks.forEach((deviceStateConsumer, executor) -> {
                entries.add(new Pair<>(deviceStateConsumer, executor));
            });
        }
        return entries;
    }
}
