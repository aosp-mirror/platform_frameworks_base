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
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN;
import static android.hardware.devicestate.feature.flags.Flags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Test class for {@link DeviceStateController}.
 *
 * Build/Install/Run:
 *  atest WmTests:DeviceStateControllerTests
 */
@SmallTest
@Presubmit
public class DeviceStateControllerTests {

    private DeviceStateController mTarget;
    private DeviceStateControllerBuilder mBuilder;

    private Context mMockContext;
    private DeviceStateManager mMockDeviceStateManager;
    private DeviceStateController.DeviceState mCurrentState =
            DeviceStateController.DeviceState.UNKNOWN;
    private Consumer<DeviceStateController.DeviceState> mDelegate;
    private Executor mExecutor = MoreExecutors.directExecutor();

    @Before
    public void setUp() {
        mBuilder = new DeviceStateControllerBuilder();
        mCurrentState = DeviceStateController.DeviceState.UNKNOWN;
    }

    private void initialize(boolean supportFold, boolean supportHalfFold) {
        mBuilder.setSupportFold(supportFold, supportHalfFold);
        mDelegate = (newFoldState) -> {
            mCurrentState = newFoldState;
        };
        mBuilder.setDelegate(mDelegate);
        mBuilder.build();
    }

    @Test
    public void testInitialization() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
    }

    @Test
    public void testInitializationWithNoFoldSupport() {
        initialize(false /* supportFold */, false /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        // Note that the folded state is ignored.
        assertEquals(DeviceStateController.DeviceState.UNKNOWN, mCurrentState);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    public void testWithFoldSupported_withOverlayConfigValues() {
        initialize(true /* supportFold */, false /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mHalfFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.UNKNOWN, mCurrentState); // Ignored
    }

    @Test
    @RequiresFlagsEnabled(FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    public void testWithFoldSupported_withDeviceStateManagerPropertyAPI() {
        initialize(true /* supportFold */, false /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mHalfFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.UNKNOWN, mCurrentState); // Ignored
    }

    @Test
    @RequiresFlagsDisabled(FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    public void testWithHalfFoldSupported_withOverlayConfigValue() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mHalfFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.HALF_FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mConcurrentDisplayState.getIdentifier());
        assertEquals(DeviceStateController.DeviceState.CONCURRENT, mCurrentState);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    public void testWithHalfFoldSupported_withDeviceStateManagerPropertyApi() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mHalfFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.HALF_FOLDED, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mConcurrentDisplayState.getIdentifier());
        assertEquals(DeviceStateController.DeviceState.CONCURRENT, mCurrentState);
    }

    @Test
    public void testUnregisterDeviceStateCallback() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        assertEquals(1, mTarget.mDeviceStateCallbacks.size());
        assertTrue(mTarget.mDeviceStateCallbacks.containsKey(mDelegate));

        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onDeviceStateReceivedByDisplayManager(mFoldedStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);

        // The callback should not receive state change when it is unregistered.
        mTarget.unregisterDeviceStateCallback(mDelegate);
        assertTrue(mTarget.mDeviceStateCallbacks.isEmpty());

        mTarget.onDeviceStateReceivedByDisplayManager(mOpenDeviceStates.get(0).getIdentifier());
        assertEquals(DeviceStateController.DeviceState.FOLDED /* unchanged */,
                mCurrentState);
    }

    @Test
    public void testCopyDeviceStateCallbacks() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        assertEquals(1, mTarget.mDeviceStateCallbacks.size());
        assertTrue(mTarget.mDeviceStateCallbacks.containsKey(mDelegate));

        List<Pair<Consumer<DeviceStateController.DeviceState>, Executor>> entries =
                mTarget.copyDeviceStateCallbacks();
        mTarget.unregisterDeviceStateCallback(mDelegate);

        // In contrast to List<Map.Entry> where the entries are tied to changes in the backing map,
        // List<Pair> should still contain non-null callbacks and executors even though they were
        // removed from the backing map via the unregister method above.
        assertEquals(1, entries.size());
        assertEquals(mDelegate, entries.get(0).first);
        assertEquals(mExecutor, entries.get(0).second);
    }

    private final List<DeviceState> mFoldedStates = new ArrayList<>(
            List.of(new DeviceState(new DeviceState.Configuration.Builder(0,
                    "folded").setSystemProperties(new HashSet<>(
                    List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY)))
                    .setPhysicalProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)))
                    .build())));
    private final List<DeviceState> mOpenDeviceStates = new ArrayList<>(
            List.of(new DeviceState(new DeviceState.Configuration.Builder(1,
                    "open").setSystemProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)))
                    .setPhysicalProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)))
                    .build())));
    private final List<DeviceState> mHalfFoldedStates = new ArrayList<>(
            List.of(new DeviceState(new DeviceState.Configuration.Builder(2,
                    "half_folded").setSystemProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)))
                    .setPhysicalProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN)))
                    .build())));
    private final List<DeviceState> mRearDisplayStates = new ArrayList<>(
            List.of(new DeviceState(new DeviceState.Configuration.Builder(3,
                    "rear_display").setSystemProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                                    PROPERTY_FEATURE_REAR_DISPLAY)))
                    .setPhysicalProperties(new HashSet<>(
                            List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)))
                    .build())));
    private final DeviceState mConcurrentDisplayState = new DeviceState(
            new DeviceState.Configuration.Builder(4, "concurrent_display")
                    .setSystemProperties(new HashSet<>(List.of(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                            PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT)))
                    .setPhysicalProperties(new HashSet<>(List.of(
                            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)))
                    .build());

    private class DeviceStateControllerBuilder {
        private boolean mSupportFold = false;
        private boolean mSupportHalfFold = false;

        private Consumer<DeviceStateController.DeviceState> mDelegate;
        private final List<DeviceState> mDeviceStateList = new ArrayList<>();

        DeviceStateControllerBuilder setSupportFold(
                boolean supportFold, boolean supportHalfFold) {
            mSupportFold = supportFold;
            mSupportHalfFold = supportHalfFold;
            return this;
        }

        DeviceStateControllerBuilder setDelegate(
                Consumer<DeviceStateController.DeviceState> delegate) {
            mDelegate = delegate;
            return this;
        }

        private void mockFold(boolean enableFold, boolean enableHalfFold) {
            if (enableFold || enableHalfFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_openDeviceStates))
                        .thenReturn(mapDeviceStateListToIdentifierArray(mOpenDeviceStates));
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_rearDisplayDeviceStates))
                        .thenReturn(mapDeviceStateListToIdentifierArray(mRearDisplayStates));
                when(mMockContext.getResources()
                        .getInteger(R.integer.config_deviceStateConcurrentRearDisplay))
                        .thenReturn(mConcurrentDisplayState.getIdentifier());

                mDeviceStateList.addAll(mOpenDeviceStates);
                mDeviceStateList.addAll(mRearDisplayStates);
                mDeviceStateList.add(mConcurrentDisplayState);
            } else {
                // Match the default value in framework resources
                when(mMockContext.getResources()
                        .getInteger(R.integer.config_deviceStateConcurrentRearDisplay))
                        .thenReturn(-1);
            }

            if (enableFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_foldedDeviceStates))
                        .thenReturn(mapDeviceStateListToIdentifierArray(mFoldedStates));
                mDeviceStateList.addAll(mFoldedStates);
            }
            if (enableHalfFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_halfFoldedDeviceStates))
                        .thenReturn(mapDeviceStateListToIdentifierArray(mHalfFoldedStates));
                mDeviceStateList.addAll(mHalfFoldedStates);
            }
        }

        private void build() {
            mMockContext = mock(Context.class);
            mMockDeviceStateManager = mock(DeviceStateManager.class);
            when(mMockContext.getSystemService(DeviceStateManager.class))
                    .thenReturn(mMockDeviceStateManager);
            when(mMockDeviceStateManager.getSupportedDeviceStates()).thenReturn(mDeviceStateList);
            Resources mockRes = mock(Resources.class);
            when(mMockContext.getResources()).thenReturn((mockRes));
            mockFold(mSupportFold, mSupportHalfFold);
            mTarget = new DeviceStateController(mMockContext, new WindowManagerGlobalLock());
            mTarget.registerDeviceStateCallback(mDelegate, mExecutor);
        }

        private int[] mapDeviceStateListToIdentifierArray(List<DeviceState> deviceStates) {
            int[] identifiers = new int[deviceStates.size()];
            for (int i = 0; i < deviceStates.size(); i++) {
                identifiers[i] = deviceStates.get(i).getIdentifier();
            }
            return identifiers;
        }
    }
}
