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

import android.annotation.Nullable;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ConcurrentUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

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
    private TestDeviceStateManagerService mService;
    private DeviceStateManagerGlobal mDeviceStateManagerGlobal;

    @Before
    public void setUp() {
        mService = new TestDeviceStateManagerService();
        mDeviceStateManagerGlobal = new DeviceStateManagerGlobal(mService);
    }

    @Test
    public void registerListener() {
        mService.setDeviceState(0);

        TestDeviceStateListener listener1 = new TestDeviceStateListener();
        TestDeviceStateListener listener2 = new TestDeviceStateListener();

        mDeviceStateManagerGlobal.registerDeviceStateListener(listener1,
                ConcurrentUtils.DIRECT_EXECUTOR);
        mDeviceStateManagerGlobal.registerDeviceStateListener(listener2,
                ConcurrentUtils.DIRECT_EXECUTOR);
        assertEquals(0, listener1.getLastReportedState().intValue());
        assertEquals(0, listener2.getLastReportedState().intValue());

        mService.setDeviceState(1);
        assertEquals(1, listener1.getLastReportedState().intValue());
        assertEquals(1, listener2.getLastReportedState().intValue());
    }

    @Test
    public void unregisterListener() {
        mService.setDeviceState(0);

        TestDeviceStateListener listener = new TestDeviceStateListener();

        mDeviceStateManagerGlobal.registerDeviceStateListener(listener,
                ConcurrentUtils.DIRECT_EXECUTOR);
        assertEquals(0, listener.getLastReportedState().intValue());

        mDeviceStateManagerGlobal.unregisterDeviceStateListener(listener);

        mService.setDeviceState(1);
        assertEquals(0, listener.getLastReportedState().intValue());
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

    private final class TestDeviceStateManagerService extends IDeviceStateManager.Stub {
        private int mDeviceState = DeviceStateManager.INVALID_DEVICE_STATE;
        private Set<IDeviceStateManagerCallback> mCallbacks = new HashSet<>();

        @Override
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (mCallbacks.contains(callback)) {
                throw new SecurityException("Callback is already registered.");
            }

            mCallbacks.add(callback);
            try {
                callback.onDeviceStateChanged(mDeviceState);
            } catch (RemoteException e) {
                // Do nothing. Should never happen.
            }
        }

        public void setDeviceState(int deviceState) {
            boolean stateChanged = mDeviceState != deviceState;
            mDeviceState = deviceState;
            if (stateChanged) {
                for (IDeviceStateManagerCallback callback : mCallbacks) {
                    try {
                        callback.onDeviceStateChanged(mDeviceState);
                    } catch (RemoteException e) {
                        // Do nothing. Should never happen.
                    }
                }
            }
        }
    }
}
