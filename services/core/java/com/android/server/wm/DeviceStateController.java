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

import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT;
import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.feature.flags.FeatureFlags;
import android.hardware.devicestate.feature.flags.FeatureFlagsImpl;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Collections;
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
    private final List<Integer> mOpenDeviceStates;
    @NonNull
    private final List<Integer> mHalfFoldedDeviceStates;
    @NonNull
    private final List<Integer> mFoldedDeviceStates;
    @NonNull
    private final List<Integer> mRearDisplayDeviceStates;
    private final List<Integer> mConcurrentDisplayDeviceStates;
    @NonNull
    private final List<Integer> mReverseRotationAroundZAxisStates;
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

        final FeatureFlags deviceStateManagerFlags = new FeatureFlagsImpl();
        if (deviceStateManagerFlags.deviceStatePropertyMigration()) {
            mOpenDeviceStates = new ArrayList<>();
            mHalfFoldedDeviceStates = new ArrayList<>();
            mFoldedDeviceStates = new ArrayList<>();
            mRearDisplayDeviceStates = new ArrayList<>();
            mConcurrentDisplayDeviceStates = new ArrayList<>();

            final DeviceStateManager deviceStateManager =
                    context.getSystemService(DeviceStateManager.class);
            final List<android.hardware.devicestate.DeviceState> deviceStates =
                    deviceStateManager.getSupportedDeviceStates();

            for (int i = 0; i < deviceStates.size(); i++) {
                final android.hardware.devicestate.DeviceState state = deviceStates.get(i);
                if (state.hasProperty(
                        PROPERTY_FEATURE_REAR_DISPLAY)) {
                    mRearDisplayDeviceStates.add(state.getIdentifier());
                } else if (state.hasProperty(
                        PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT)) {
                    mConcurrentDisplayDeviceStates.add(state.getIdentifier());
                } else if (state.hasProperty(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY)) {
                    mFoldedDeviceStates.add(state.getIdentifier());
                } else if (state.hasProperty(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)) {
                    if (state.hasProperty(
                            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN)) {
                        mHalfFoldedDeviceStates.add(state.getIdentifier());
                    } else {
                        mOpenDeviceStates.add(state.getIdentifier());
                    }
                }
            }
        } else {
            mOpenDeviceStates = copyIntArrayToList(context.getResources()
                    .getIntArray(R.array.config_openDeviceStates));
            mHalfFoldedDeviceStates = copyIntArrayToList(context.getResources()
                    .getIntArray(R.array.config_halfFoldedDeviceStates));
            mFoldedDeviceStates = copyIntArrayToList(context.getResources()
                    .getIntArray(R.array.config_foldedDeviceStates));
            mRearDisplayDeviceStates = copyIntArrayToList(context.getResources()
                    .getIntArray(R.array.config_rearDisplayDeviceStates));
            mConcurrentDisplayDeviceStates = new ArrayList<>(List.of(context.getResources()
                    .getInteger(R.integer.config_deviceStateConcurrentRearDisplay)));
        }

        mReverseRotationAroundZAxisStates = copyIntArrayToList(context.getResources().getIntArray(
                R.array.config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis));
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
        } else if (ArrayUtils.contains(mConcurrentDisplayDeviceStates, state)) {
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

    @NonNull
    private List<Integer> copyIntArrayToList(@Nullable int[] values) {
        if (values == null) {
            return Collections.emptyList();
        }
        final List<Integer> valueList = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            valueList.add(values[i]);
        }
        return valueList;
    }
}
