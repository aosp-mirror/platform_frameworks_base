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

package android.hardware.devicestate;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ConcurrentUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link DeviceStateManagerGlobal}.
 * <p/>
 * Run with <code>atest DeviceStateManagerGlobalTest</code>.
 */
@RunWith(JUnit4.class)
@SmallTest
public final class DeviceStateManagerGlobalTest {
    private static final int DEFAULT_DEVICE_STATE = 0;
    private static final int OTHER_DEVICE_STATE = 1;

    private TestDeviceStateManagerService mService;
    private DeviceStateManagerGlobal mDeviceStateManagerGlobal;

    @Before
    public void setUp() {
        mService = new TestDeviceStateManagerService();
        mDeviceStateManagerGlobal = new DeviceStateManagerGlobal(mService);
    }

    @Test
    public void registerListener() {
        mService.setBaseState(DEFAULT_DEVICE_STATE);

        TestDeviceStateListener listener1 = new TestDeviceStateListener();
        TestDeviceStateListener listener2 = new TestDeviceStateListener();

        mDeviceStateManagerGlobal.registerDeviceStateListener(listener1,
                ConcurrentUtils.DIRECT_EXECUTOR);
        mDeviceStateManagerGlobal.registerDeviceStateListener(listener2,
                ConcurrentUtils.DIRECT_EXECUTOR);
        assertEquals(DEFAULT_DEVICE_STATE, listener1.getLastReportedState().intValue());
        assertEquals(DEFAULT_DEVICE_STATE, listener2.getLastReportedState().intValue());

        mService.setBaseState(OTHER_DEVICE_STATE);
        assertEquals(OTHER_DEVICE_STATE, listener1.getLastReportedState().intValue());
        assertEquals(OTHER_DEVICE_STATE, listener2.getLastReportedState().intValue());
    }

    @Test
    public void unregisterListener() {
        mService.setBaseState(DEFAULT_DEVICE_STATE);

        TestDeviceStateListener listener = new TestDeviceStateListener();

        mDeviceStateManagerGlobal.registerDeviceStateListener(listener,
                ConcurrentUtils.DIRECT_EXECUTOR);
        assertEquals(DEFAULT_DEVICE_STATE, listener.getLastReportedState().intValue());

        mDeviceStateManagerGlobal.unregisterDeviceStateListener(listener);

        mService.setBaseState(OTHER_DEVICE_STATE);
        assertEquals(DEFAULT_DEVICE_STATE, listener.getLastReportedState().intValue());
    }

    @Test
    public void submittingRequestRegisteredCallback() {
        assertTrue(mService.mCallbacks.isEmpty());

        DeviceStateRequest request = DeviceStateRequest.newBuilder(DEFAULT_DEVICE_STATE).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        assertFalse(mService.mCallbacks.isEmpty());
    }

    @Test
    public void submitRequest() {
        mService.setBaseState(DEFAULT_DEVICE_STATE);

        TestDeviceStateListener listener = new TestDeviceStateListener();
        mDeviceStateManagerGlobal.registerDeviceStateListener(listener,
                ConcurrentUtils.DIRECT_EXECUTOR);

        assertEquals(DEFAULT_DEVICE_STATE, listener.getLastReportedState().intValue());

        DeviceStateRequest request = DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        assertEquals(OTHER_DEVICE_STATE, listener.getLastReportedState().intValue());

        mDeviceStateManagerGlobal.cancelRequest(request);

        assertEquals(DEFAULT_DEVICE_STATE, listener.getLastReportedState().intValue());
    }

    private final class TestDeviceStateListener implements DeviceStateManager.DeviceStateListener {
        @Nullable
        private Integer mLastReportedDeviceState;

        @Override
        public void onDeviceStateChanged(int deviceState) {
            mLastReportedDeviceState = deviceState;
        }

        @Nullable
        public Integer getLastReportedState() {
            return mLastReportedDeviceState;
        }
    }

    private static final class TestDeviceStateManagerService extends IDeviceStateManager.Stub {
        public static final class Request {
            public final IBinder token;
            public final int state;
            public final int flags;

            private Request(IBinder token, int state, int flags) {
                this.token = token;
                this.state = state;
                this.flags = flags;
            }
        }

        private int mBaseState = DEFAULT_DEVICE_STATE;
        private int mMergedState = DEFAULT_DEVICE_STATE;
        private ArrayList<Request> mRequests = new ArrayList<>();

        private Set<IDeviceStateManagerCallback> mCallbacks = new HashSet<>();

        @Override
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (mCallbacks.contains(callback)) {
                throw new SecurityException("Callback is already registered.");
            }

            mCallbacks.add(callback);
            try {
                callback.onDeviceStateChanged(mMergedState);
            } catch (RemoteException e) {
                // Do nothing. Should never happen.
            }
        }

        @Override
        public int[] getSupportedDeviceStates() {
            return new int[] { DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE };
        }

        @Override
        public void requestState(IBinder token, int state, int flags) {
            if (!mRequests.isEmpty()) {
                final Request topRequest = mRequests.get(mRequests.size() - 1);
                for (IDeviceStateManagerCallback callback : mCallbacks) {
                    try {
                        callback.onRequestSuspended(topRequest.token);
                    } catch (RemoteException e) {
                        // Do nothing. Should never happen.
                    }
                }
            }

            final Request request = new Request(token, state, flags);
            mRequests.add(request);
            notifyStateChangedIfNeeded();

            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestActive(token);
                } catch (RemoteException e) {
                    // Do nothing. Should never happen.
                }
            }
        }

        @Override
        public void cancelRequest(IBinder token) {
            int index = -1;
            for (int i = 0; i < mRequests.size(); i++) {
                if (mRequests.get(i).token.equals(token)) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                throw new IllegalArgumentException("Unknown request: " + token);
            }

            mRequests.remove(index);
            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestCanceled(token);
                } catch (RemoteException e) {
                    // Do nothing. Should never happen.
                }
            }
            notifyStateChangedIfNeeded();
        }

        public void setBaseState(int state) {
            mBaseState = state;
            notifyStateChangedIfNeeded();
        }

        private void notifyStateChangedIfNeeded() {
            final int originalMergedState = mMergedState;

            if (!mRequests.isEmpty()) {
                mMergedState = mRequests.get(mRequests.size() - 1).state;
            } else {
                mMergedState = mBaseState;
            }

            if (mMergedState != originalMergedState) {
                for (IDeviceStateManagerCallback callback : mCallbacks) {
                    try {
                        callback.onDeviceStateChanged(mMergedState);
                    } catch (RemoteException e) {
                        // Do nothing. Should never happen.
                    }
                }
            }
        }
    }
}
