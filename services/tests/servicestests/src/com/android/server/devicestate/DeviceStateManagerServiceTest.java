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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link DeviceStateManagerService}.
 * <p/>
 * Run with <code>atest DeviceStateManagerServiceTest</code>.
 */
@RunWith(AndroidJUnit4.class)
public final class DeviceStateManagerServiceTest {
    private static final int DEFAULT_DEVICE_STATE = 0;
    private static final int OTHER_DEVICE_STATE = 1;
    private static final int UNSUPPORTED_DEVICE_STATE = 999;

    private TestDeviceStatePolicy mPolicy;
    private TestDeviceStateProvider mProvider;
    private DeviceStateManagerService mService;

    @Before
    public void setup() {
        mProvider = new TestDeviceStateProvider();
        mPolicy = new TestDeviceStatePolicy(mProvider);
        mService = new DeviceStateManagerService(InstrumentationRegistry.getContext(), mPolicy);
        mService.onStart();
    }

    @Test
    public void requestStateChange() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);

        mProvider.notifyRequestState(OTHER_DEVICE_STATE);
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), OTHER_DEVICE_STATE);
    }

    @Test
    public void requestStateChange_pendingState() {
        mPolicy.blockConfigure();

        mProvider.notifyRequestState(OTHER_DEVICE_STATE);
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), OTHER_DEVICE_STATE);

        mProvider.notifyRequestState(DEFAULT_DEVICE_STATE);
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), OTHER_DEVICE_STATE);

        mPolicy.resumeConfigure();
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void requestStateChange_unsupportedState() {
        mProvider.notifyRequestState(UNSUPPORTED_DEVICE_STATE);
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void requestStateChange_invalidState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.notifyRequestState(INVALID_DEVICE_STATE);
        });
    }

    @Test
    public void requestOverrideState() {
        mService.setOverrideState(OTHER_DEVICE_STATE);
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), OTHER_DEVICE_STATE);

        // Committed state is set back to the requested state once the override is cleared.
        mService.clearOverrideState();
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void requestOverrideState_unsupportedState() {
        mService.setOverrideState(UNSUPPORTED_DEVICE_STATE);
        // Committed state remains the same as the override state is unsupported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new int []{ DEFAULT_DEVICE_STATE });

        // The current committed and requests states do not change because the current state remains
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_invalidState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.notifySupportedDeviceStates(new int []{ INVALID_DEVICE_STATE });
        });
    }

    @Test
    public void supportedStatesChanged_unsupportedRequestedState() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new int []{ OTHER_DEVICE_STATE });

        // The current requested state is cleared because it is no longer supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), INVALID_DEVICE_STATE);

        mProvider.notifyRequestState(OTHER_DEVICE_STATE);

        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), INVALID_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), OTHER_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_unsupportedOverrideState() {
        mService.setOverrideState(OTHER_DEVICE_STATE);
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), OTHER_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new int []{ DEFAULT_DEVICE_STATE });

        // Committed state is set back to the requested state as the override state is no longer
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(), DEFAULT_DEVICE_STATE);
    }

    private static final class TestDeviceStatePolicy implements DeviceStatePolicy {
        private final DeviceStateProvider mProvider;
        private int mLastDeviceStateRequestedToConfigure = INVALID_DEVICE_STATE;
        private boolean mConfigureBlocked = false;
        private Runnable mPendingConfigureCompleteRunnable;

        TestDeviceStatePolicy(DeviceStateProvider provider) {
            mProvider = provider;
        }

        @Override
        public DeviceStateProvider getDeviceStateProvider() {
            return mProvider;
        }

        public void blockConfigure() {
            mConfigureBlocked = true;
        }

        public void resumeConfigure() {
            mConfigureBlocked = false;
            if (mPendingConfigureCompleteRunnable != null) {
                Runnable onComplete = mPendingConfigureCompleteRunnable;
                mPendingConfigureCompleteRunnable = null;
                onComplete.run();
            }
        }

        public int getMostRecentRequestedStateToConfigure() {
            return mLastDeviceStateRequestedToConfigure;
        }

        @Override
        public void configureDeviceForState(int state, Runnable onComplete) {
            if (mPendingConfigureCompleteRunnable != null) {
                throw new IllegalStateException("configureDeviceForState() called while configure"
                        + " is pending");
            }

            mLastDeviceStateRequestedToConfigure = state;
            if (mConfigureBlocked) {
                mPendingConfigureCompleteRunnable = onComplete;
                return;
            }
            onComplete.run();
        }
    }

    private static final class TestDeviceStateProvider implements DeviceStateProvider {
        private int[] mSupportedDeviceStates = new int[]{ DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE };
        private int mCurrentDeviceState = DEFAULT_DEVICE_STATE;
        private Listener mListener;

        @Override
        public void setListener(Listener listener) {
            if (mListener != null) {
                throw new IllegalArgumentException("Provider already has listener set.");
            }

            mListener = listener;
            mListener.onSupportedDeviceStatesChanged(mSupportedDeviceStates);
            mListener.onStateChanged(mCurrentDeviceState);
        }

        public void notifySupportedDeviceStates(int[] supportedDeviceStates) {
            mSupportedDeviceStates = supportedDeviceStates;
            mListener.onSupportedDeviceStatesChanged(supportedDeviceStates);
        }

        public void notifyRequestState(int state) {
            mCurrentDeviceState = state;
            mListener.onStateChanged(state);
        }
    }
}
