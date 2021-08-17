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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ConcurrentUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

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
    public void registerCallback() {
        DeviceStateCallback callback1 = mock(DeviceStateCallback.class);
        DeviceStateCallback callback2 = mock(DeviceStateCallback.class);

        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback1,
                ConcurrentUtils.DIRECT_EXECUTOR);
        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback2,
                ConcurrentUtils.DIRECT_EXECUTOR);

        // Verify initial callbacks
        verify(callback1).onSupportedStatesChanged(eq(mService.getSupportedStates()));
        verify(callback1).onBaseStateChanged(eq(mService.getBaseState()));
        verify(callback1).onStateChanged(eq(mService.getMergedState()));
        verify(callback2).onSupportedStatesChanged(eq(mService.getSupportedStates()));
        verify(callback2).onBaseStateChanged(eq(mService.getBaseState()));
        verify(callback2).onStateChanged(eq(mService.getMergedState()));

        Mockito.reset(callback1);
        Mockito.reset(callback2);

        // Change the supported states and verify callback
        mService.setSupportedStates(new int[]{ DEFAULT_DEVICE_STATE });
        verify(callback1).onSupportedStatesChanged(eq(mService.getSupportedStates()));
        verify(callback2).onSupportedStatesChanged(eq(mService.getSupportedStates()));
        mService.setSupportedStates(new int[]{ DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE });

        Mockito.reset(callback1);
        Mockito.reset(callback2);

        // Change the base state and verify callback
        mService.setBaseState(OTHER_DEVICE_STATE);
        verify(callback1).onBaseStateChanged(eq(mService.getBaseState()));
        verify(callback1).onStateChanged(eq(mService.getMergedState()));
        verify(callback2).onBaseStateChanged(eq(mService.getBaseState()));
        verify(callback2).onStateChanged(eq(mService.getMergedState()));

        Mockito.reset(callback1);
        Mockito.reset(callback2);

        // Change the requested state and verify callback
        DeviceStateRequest request = DeviceStateRequest.newBuilder(DEFAULT_DEVICE_STATE).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        verify(callback1).onStateChanged(eq(mService.getMergedState()));
        verify(callback2).onStateChanged(eq(mService.getMergedState()));
    }

    @Test
    public void unregisterCallback() {
        DeviceStateCallback callback = mock(DeviceStateCallback.class);

        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback,
                ConcurrentUtils.DIRECT_EXECUTOR);

        // Verify initial callbacks
        verify(callback).onSupportedStatesChanged(eq(mService.getSupportedStates()));
        verify(callback).onBaseStateChanged(eq(mService.getBaseState()));
        verify(callback).onStateChanged(eq(mService.getMergedState()));
        Mockito.reset(callback);

        mDeviceStateManagerGlobal.unregisterDeviceStateCallback(callback);

        mService.setSupportedStates(new int[]{OTHER_DEVICE_STATE});
        mService.setBaseState(OTHER_DEVICE_STATE);
        verifyZeroInteractions(callback);
    }

    @Test
    public void submittingRequestRegistersCallback() {
        assertTrue(mService.mCallbacks.isEmpty());

        DeviceStateRequest request = DeviceStateRequest.newBuilder(DEFAULT_DEVICE_STATE).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        assertFalse(mService.mCallbacks.isEmpty());
    }

    @Test
    public void submitRequest() {
        DeviceStateCallback callback = mock(DeviceStateCallback.class);
        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback,
                ConcurrentUtils.DIRECT_EXECUTOR);

        verify(callback).onStateChanged(eq(mService.getBaseState()));
        Mockito.reset(callback);

        DeviceStateRequest request = DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        verify(callback).onStateChanged(eq(OTHER_DEVICE_STATE));
        Mockito.reset(callback);

        mDeviceStateManagerGlobal.cancelRequest(request);

        verify(callback).onStateChanged(eq(mService.getBaseState()));
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

        private int[] mSupportedStates = new int[] { DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE };
        private int mBaseState = DEFAULT_DEVICE_STATE;
        private ArrayList<Request> mRequests = new ArrayList<>();

        private Set<IDeviceStateManagerCallback> mCallbacks = new HashSet<>();

        private DeviceStateInfo getInfo() {
            final int mergedState = mRequests.isEmpty()
                    ? mBaseState : mRequests.get(mRequests.size() - 1).state;
            return new DeviceStateInfo(mSupportedStates, mBaseState, mergedState);
        }

        private void notifyDeviceStateInfoChanged() {
            final DeviceStateInfo info = getInfo();
            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onDeviceStateInfoChanged(info);
                } catch (RemoteException e) {
                    // Do nothing. Should never happen.
                }
            }
        }

        @Override
        public DeviceStateInfo getDeviceStateInfo() {
            return getInfo();
        }

        @Override
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (mCallbacks.contains(callback)) {
                throw new SecurityException("Callback is already registered.");
            }

            mCallbacks.add(callback);
            try {
                callback.onDeviceStateInfoChanged(getInfo());
            } catch (RemoteException e) {
                // Do nothing. Should never happen.
            }
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
            notifyDeviceStateInfoChanged();

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
            notifyDeviceStateInfoChanged();
        }

        public void setSupportedStates(int[] states) {
            mSupportedStates = states;
            notifyDeviceStateInfoChanged();
        }

        public int[] getSupportedStates() {
            return mSupportedStates;
        }

        public void setBaseState(int state) {
            mBaseState = state;
            notifyDeviceStateInfoChanged();
        }

        public int getBaseState() {
            return mBaseState;
        }

        public int getMergedState() {
            return getInfo().currentState;
        }
    }
}
