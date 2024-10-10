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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.annotation.EnforcePermission;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.ConcurrentUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link DeviceStateManagerGlobal}.
 *
 * <p> Build/Install/Run:
 * atest FrameworksCoreDeviceStateManagerTests:DeviceStateManagerGlobalTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DeviceStateManagerGlobalTest {
    private static final DeviceState DEFAULT_DEVICE_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(0 /* identifier */, "" /* name */).build());
    private static final DeviceState OTHER_DEVICE_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(1 /* identifier */, "" /* name */).build());

    @NonNull
    private TestDeviceStateManagerService mService;
    @NonNull
    private DeviceStateManagerGlobal mDeviceStateManagerGlobal;

    @Before
    public void setUp() {
        final FakePermissionEnforcer permissionEnforcer = new FakePermissionEnforcer();
        mService = new TestDeviceStateManagerService(permissionEnforcer);
        mDeviceStateManagerGlobal = new DeviceStateManagerGlobal(mService);
        assertThat(mService.mCallbacks).isNotEmpty();
    }

    @Test
    public void registerCallback() {
        final DeviceStateCallback callback1 = mock(DeviceStateCallback.class);
        final DeviceStateCallback callback2 = mock(DeviceStateCallback.class);

        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback1,
                ConcurrentUtils.DIRECT_EXECUTOR);
        mDeviceStateManagerGlobal.registerDeviceStateCallback(callback2,
                ConcurrentUtils.DIRECT_EXECUTOR);

        // Verify initial callbacks
        verify(callback1).onSupportedStatesChanged(eq(mService.getSupportedDeviceStates()));
        verify(callback1).onDeviceStateChanged(eq(mService.getMergedState()));
        verify(callback2).onDeviceStateChanged(eq(mService.getMergedState()));

        reset(callback1);
        reset(callback2);

        // Change the supported states and verify callback
        mService.setSupportedStates(List.of(DEFAULT_DEVICE_STATE));
        verify(callback1).onSupportedStatesChanged(eq(mService.getSupportedDeviceStates()));
        verify(callback2).onSupportedStatesChanged(eq(mService.getSupportedDeviceStates()));
        mService.setSupportedStates(List.of(DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE));

        reset(callback1);
        reset(callback2);

        // Change the base state and verify callback
        mService.setBaseState(OTHER_DEVICE_STATE);
        verify(callback1).onDeviceStateChanged(eq(mService.getMergedState()));
        verify(callback2).onDeviceStateChanged(eq(mService.getMergedState()));

        reset(callback1);
        reset(callback2);

        // Change the requested state and verify callback
        final DeviceStateRequest request =
                DeviceStateRequest.newBuilder(DEFAULT_DEVICE_STATE.getIdentifier()).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        verify(callback1).onDeviceStateChanged(eq(mService.getMergedState()));
        verify(callback2).onDeviceStateChanged(eq(mService.getMergedState()));
    }

    @Test
    public void unregisterCallback() {
        final DeviceStateCallback callback = mock(DeviceStateCallback.class);

        mDeviceStateManagerGlobal
                .registerDeviceStateCallback(callback, ConcurrentUtils.DIRECT_EXECUTOR);

        // Verify initial callbacks
        verify(callback).onSupportedStatesChanged(eq(mService.getSupportedDeviceStates()));
        verify(callback).onDeviceStateChanged(eq(mService.getMergedState()));
        reset(callback);

        mDeviceStateManagerGlobal.unregisterDeviceStateCallback(callback);

        mService.setSupportedStates(List.of(OTHER_DEVICE_STATE));
        mService.setBaseState(OTHER_DEVICE_STATE);
        verifyZeroInteractions(callback);
    }

    @Test
    public void submitRequest() {
        final DeviceStateCallback callback = mock(DeviceStateCallback.class);
        mDeviceStateManagerGlobal
                .registerDeviceStateCallback(callback, ConcurrentUtils.DIRECT_EXECUTOR);

        verify(callback).onDeviceStateChanged(eq(mService.getBaseState()));
        reset(callback);

        final DeviceStateRequest request =
                DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE.getIdentifier()).build();
        mDeviceStateManagerGlobal.requestState(request, null /* executor */, null /* callback */);

        verify(callback).onDeviceStateChanged(eq(OTHER_DEVICE_STATE));
        reset(callback);

        mDeviceStateManagerGlobal.cancelStateRequest();

        verify(callback).onDeviceStateChanged(eq(mService.getBaseState()));
    }

    @Test
    public void submitBaseStateOverrideRequest() {
        final DeviceStateCallback callback = mock(DeviceStateCallback.class);
        mDeviceStateManagerGlobal
                .registerDeviceStateCallback(callback, ConcurrentUtils.DIRECT_EXECUTOR);

        verify(callback).onDeviceStateChanged(eq(mService.getBaseState()));
        reset(callback);

        final DeviceStateRequest request =
                DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE.getIdentifier()).build();
        mDeviceStateManagerGlobal.requestBaseStateOverride(request, null /* executor */,
                null /* callback */);

        verify(callback).onDeviceStateChanged(eq(OTHER_DEVICE_STATE));
        reset(callback);

        mDeviceStateManagerGlobal.cancelBaseStateOverride();

        verify(callback).onDeviceStateChanged(eq(mService.getBaseState()));
    }

    @Test
    public void submitBaseAndEmulatedStateOverride() {
        final DeviceStateCallback callback = mock(DeviceStateCallback.class);
        mDeviceStateManagerGlobal
                .registerDeviceStateCallback(callback, ConcurrentUtils.DIRECT_EXECUTOR);

        verify(callback).onDeviceStateChanged(eq(mService.getBaseState()));
        reset(callback);

        final DeviceStateRequest request =
                DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE.getIdentifier()).build();
        mDeviceStateManagerGlobal.requestBaseStateOverride(request, null /* executor */,
                null /* callback */);

        verify(callback).onDeviceStateChanged(eq(OTHER_DEVICE_STATE));
        assertThat(mService.getBaseState()).isEqualTo(OTHER_DEVICE_STATE);
        reset(callback);

        final DeviceStateRequest secondRequest =
                DeviceStateRequest.newBuilder(DEFAULT_DEVICE_STATE.getIdentifier()).build();

        mDeviceStateManagerGlobal.requestState(secondRequest, null, null);

        assertThat(mService.getBaseState()).isEqualTo(OTHER_DEVICE_STATE);
        verify(callback).onDeviceStateChanged(eq(DEFAULT_DEVICE_STATE));
        reset(callback);

        mDeviceStateManagerGlobal.cancelStateRequest();

        verify(callback).onDeviceStateChanged(OTHER_DEVICE_STATE);
        reset(callback);

        mDeviceStateManagerGlobal.cancelBaseStateOverride();

        verify(callback).onDeviceStateChanged(DEFAULT_DEVICE_STATE);
    }

    @Test
    public void verifyDeviceStateRequestCallbacksCalled() {
        final DeviceStateRequest.Callback callback = mock(TestDeviceStateRequestCallback.class);

        final DeviceStateRequest request =
                DeviceStateRequest.newBuilder(OTHER_DEVICE_STATE.getIdentifier()).build();
        mDeviceStateManagerGlobal.requestState(request,
                ConcurrentUtils.DIRECT_EXECUTOR /* executor */,
                callback /* callback */);

        verify(callback).onRequestActivated(eq(request));
        reset(callback);

        mDeviceStateManagerGlobal.cancelStateRequest();

        verify(callback).onRequestCanceled(eq(request));
    }

    public static class TestDeviceStateRequestCallback implements DeviceStateRequest.Callback {
        @Override
        public void onRequestActivated(@NonNull DeviceStateRequest request) { }

        @Override
        public void onRequestCanceled(@NonNull DeviceStateRequest request) { }

        @Override
        public void onRequestSuspended(@NonNull DeviceStateRequest request) { }
    }

    private static final class TestDeviceStateManagerService extends IDeviceStateManager.Stub {
        static final class Request {
            @NonNull
            final IBinder mToken;
            final int mState;

            private Request(@NonNull IBinder token, int state) {
                this.mToken = token;
                this.mState = state;
            }
        }

        @NonNull
        private List<DeviceState> mSupportedDeviceStates =
                List.of(DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE);
        @NonNull
        private DeviceState mBaseState = DEFAULT_DEVICE_STATE;
        @Nullable
        private Request mRequest;
        @Nullable
        private Request mBaseStateRequest;

        private final Set<IDeviceStateManagerCallback> mCallbacks = new HashSet<>();

        TestDeviceStateManagerService(@NonNull FakePermissionEnforcer enforcer) {
            super(enforcer);
        }

        @NonNull
        private DeviceStateInfo getInfo() {
            final int mergedBaseState = mBaseStateRequest == null
                    ? mBaseState.getIdentifier() : mBaseStateRequest.mState;
            final int mergedState = mRequest == null ? mergedBaseState : mRequest.mState;

            final ArrayList<DeviceState> supportedStates = new ArrayList<>(mSupportedDeviceStates);
            final DeviceState baseState = new DeviceState(
                    new DeviceState.Configuration.Builder(mergedBaseState, "" /* name */).build());
            final DeviceState state = new DeviceState(
                    new DeviceState.Configuration.Builder(mergedState, "" /* name */).build());
            return new DeviceStateInfo(supportedStates, baseState, state);
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

        @NonNull
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
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void requestState(@NonNull IBinder token, int state, int unusedFlags) {
            if (mRequest != null) {
                for (IDeviceStateManagerCallback callback : mCallbacks) {
                    try {
                        callback.onRequestCanceled(mRequest.mToken);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            }

            final Request request = new Request(token, state);
            mRequest = request;
            notifyDeviceStateInfoChanged();

            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestActive(token);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void cancelStateRequest() {
            final IBinder token = mRequest.mToken;
            mRequest = null;
            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestCanceled(token);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
            notifyDeviceStateInfoChanged();
        }

        @Override
        public void requestBaseStateOverride(@NonNull IBinder token, int state, int unusedFlags) {
            if (mBaseStateRequest != null) {
                for (IDeviceStateManagerCallback callback : mCallbacks) {
                    try {
                        callback.onRequestCanceled(mBaseStateRequest.mToken);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            }

            mBaseStateRequest = new Request(token, state);
            notifyDeviceStateInfoChanged();

            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestActive(token);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void cancelBaseStateOverride() throws RemoteException {
            final IBinder token = mBaseStateRequest.mToken;
            mBaseStateRequest = null;
            for (IDeviceStateManagerCallback callback : mCallbacks) {
                try {
                    callback.onRequestCanceled(token);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
            notifyDeviceStateInfoChanged();
        }

        // No-op in the test since DeviceStateManagerGlobal just calls into the system server with
        // no business logic around it.
        @Override
        @EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
        public void onStateRequestOverlayDismissed(boolean shouldCancelMode) {
            onStateRequestOverlayDismissed_enforcePermission();
        }

        public void setSupportedStates(@NonNull List<DeviceState> states) {
            mSupportedDeviceStates = states;
            notifyDeviceStateInfoChanged();
        }

        @NonNull
        public List<DeviceState> getSupportedDeviceStates() {
            return mSupportedDeviceStates;
        }

        public void setBaseState(@NonNull DeviceState state) {
            mBaseState = state;
            notifyDeviceStateInfoChanged();
        }

        @NonNull
        public DeviceState getBaseState() {
            return getInfo().baseState;
        }

        @NonNull
        public DeviceState getMergedState() {
            return getInfo().currentState;
        }
    }
}
