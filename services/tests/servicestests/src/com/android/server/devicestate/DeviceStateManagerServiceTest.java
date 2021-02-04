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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;

import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Unit tests for {@link DeviceStateManagerService}.
 * <p/>
 * Run with <code>atest DeviceStateManagerServiceTest</code>.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DeviceStateManagerServiceTest {
    private static final DeviceState DEFAULT_DEVICE_STATE = new DeviceState(0, "DEFAULT");
    private static final DeviceState OTHER_DEVICE_STATE = new DeviceState(1, "OTHER");
    private static final DeviceState UNSUPPORTED_DEVICE_STATE = new DeviceState(999, "UNSUPPORTED");

    private TestDeviceStatePolicy mPolicy;
    private TestDeviceStateProvider mProvider;
    private DeviceStateManagerService mService;

    @Before
    public void setup() {
        mProvider = new TestDeviceStateProvider();
        mPolicy = new TestDeviceStatePolicy(mProvider);
        mService = new DeviceStateManagerService(InstrumentationRegistry.getContext(), mPolicy);
    }

    @Test
    public void requestStateChange() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mProvider.notifyRequestState(OTHER_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestStateChange_pendingState() {
        mPolicy.blockConfigure();

        mProvider.notifyRequestState(OTHER_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState().get(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.notifyRequestState(DEFAULT_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState().get(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mPolicy.resumeConfigure();
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestStateChange_unsupportedState() {
        mProvider.notifyRequestState(UNSUPPORTED_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestStateChange_invalidState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.notifyRequestState(INVALID_DEVICE_STATE);
        });
    }

    @Test
    public void requestOverrideState() {
        mService.setOverrideState(OTHER_DEVICE_STATE.getIdentifier());
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        // Committed state is set back to the requested state once the override is cleared.
        mService.clearOverrideState();
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestOverrideState_unsupportedState() {
        mService.setOverrideState(UNSUPPORTED_DEVICE_STATE.getIdentifier());
        // Committed state remains the same as the override state is unsupported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void supportedStatesChanged() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ DEFAULT_DEVICE_STATE });

        // The current committed and requests states do not change because the current state remains
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_unsupportedRequestedState() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ OTHER_DEVICE_STATE });

        // The current requested state is cleared because it is no longer supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState(), Optional.empty());

        mProvider.notifyRequestState(OTHER_DEVICE_STATE.getIdentifier());

        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getRequestedState().get(), OTHER_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_unsupportedOverrideState() {
        mService.setOverrideState(OTHER_DEVICE_STATE.getIdentifier());
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ DEFAULT_DEVICE_STATE });

        // Committed state is set back to the requested state as the override state is no longer
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getRequestedState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void registerCallback() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        mProvider.notifyRequestState(OTHER_DEVICE_STATE.getIdentifier());
        assertNotNull(callback.getLastNotifiedValue());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.notifyRequestState(DEFAULT_DEVICE_STATE.getIdentifier());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mPolicy.blockConfigure();
        mProvider.notifyRequestState(OTHER_DEVICE_STATE.getIdentifier());
        // The callback should not have been notified of the state change as the policy is still
        // pending callback.
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mPolicy.resumeConfigure();
        // Now that the policy is finished processing the callback should be notified of the state
        // change.
        assertEquals(callback.getLastNotifiedValue().intValue(),
                OTHER_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void registerCallback_emitsInitialValue() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        assertNotNull(callback.getLastNotifiedValue());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());
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
        private DeviceState[] mSupportedDeviceStates = new DeviceState[]{ DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE };
        private Listener mListener;

        @Override
        public void setListener(Listener listener) {
            if (mListener != null) {
                throw new IllegalArgumentException("Provider already has listener set.");
            }

            mListener = listener;
            mListener.onSupportedDeviceStatesChanged(mSupportedDeviceStates);
            mListener.onStateChanged(mSupportedDeviceStates[0].getIdentifier());
        }

        public void notifySupportedDeviceStates(DeviceState[] supportedDeviceStates) {
            mSupportedDeviceStates = supportedDeviceStates;
            mListener.onSupportedDeviceStatesChanged(supportedDeviceStates);
        }

        public void notifyRequestState(int identifier) {
            mListener.onStateChanged(identifier);
        }
    }

    private static final class TestDeviceStateManagerCallback extends
            IDeviceStateManagerCallback.Stub {
        Integer mLastNotifiedValue;

        @Override
        public void onDeviceStateChanged(int deviceState) {
            mLastNotifiedValue = deviceState;
        }

        @Nullable
        Integer getLastNotifiedValue() {
            return mLastNotifiedValue;
        }
    }
}
