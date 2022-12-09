/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.devicestate.OverrideRequest.OVERRIDE_REQUEST_TYPE_BASE_STATE;
import static com.android.server.devicestate.OverrideRequest.OVERRIDE_REQUEST_TYPE_EMULATED_STATE;
import static com.android.server.devicestate.OverrideRequestController.STATUS_ACTIVE;
import static com.android.server.devicestate.OverrideRequestController.STATUS_CANCELED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import android.annotation.Nullable;
import android.hardware.devicestate.DeviceStateRequest;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link OverrideRequestController}.
 * <p/>
 * Run with <code>atest OverrideRequestControllerTest</code>.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class OverrideRequestControllerTest {
    private TestStatusChangeListener mStatusListener;
    private OverrideRequestController mController;

    @Before
    public void setup() {
        mStatusListener = new TestStatusChangeListener();
        mController = new OverrideRequestController(mStatusListener);
    }

    @Test
    public void addRequest() {
        OverrideRequest request = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);
        assertNull(mStatusListener.getLastStatus(request));

        mController.addRequest(request);
        assertEquals(mStatusListener.getLastStatus(request).intValue(), STATUS_ACTIVE);
    }

    @Test
    public void addRequest_cancelExistingRequestThroughNewRequest() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);
        assertNull(mStatusListener.getLastStatus(firstRequest));

        mController.addRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        OverrideRequest secondRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);
        assertNull(mStatusListener.getLastStatus(secondRequest));

        mController.addRequest(secondRequest);
        assertEquals(mStatusListener.getLastStatus(secondRequest).intValue(), STATUS_ACTIVE);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void addRequest_cancelActiveRequest() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        mController.addRequest(firstRequest);

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.cancelOverrideRequest();

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void addBaseStateRequest() {
        OverrideRequest request = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);
        assertNull(mStatusListener.getLastStatus(request));

        mController.addBaseStateRequest(request);
        assertEquals(mStatusListener.getLastStatus(request).intValue(), STATUS_ACTIVE);
    }

    @Test
    public void addBaseStateRequest_cancelExistingBaseStateRequestThroughNewRequest() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);
        assertNull(mStatusListener.getLastStatus(firstRequest));

        mController.addBaseStateRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        OverrideRequest secondRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);
        assertNull(mStatusListener.getLastStatus(secondRequest));

        mController.addBaseStateRequest(secondRequest);
        assertEquals(mStatusListener.getLastStatus(secondRequest).intValue(), STATUS_ACTIVE);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void addBaseStateRequest_cancelActiveBaseStateRequest() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);

        mController.addBaseStateRequest(firstRequest);

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.cancelBaseStateOverrideRequest();

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void handleBaseStateChanged() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */,
                DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES /* flags */,
                OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        OverrideRequest baseStateRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */,
                0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);

        mController.addRequest(firstRequest);

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.addBaseStateRequest(baseStateRequest);

        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_ACTIVE);

        mController.handleBaseStateChanged(1);

        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void handleProcessDied() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        OverrideRequest baseStateRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */,
                0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);

        mController.addRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.addBaseStateRequest(baseStateRequest);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_ACTIVE);

        mController.handleProcessDied(0);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void handleProcessDied_stickyRequests() {
        mController.setStickyRequestsAllowed(true);

        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                0 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        OverrideRequest baseStateRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);

        mController.addRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.addBaseStateRequest(baseStateRequest);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_ACTIVE);

        mController.handleProcessDied(0);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_CANCELED);

        mController.cancelStickyRequest();
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void handleNewSupportedStates() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        OverrideRequest baseStateRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */,
                0 /* flags */, OVERRIDE_REQUEST_TYPE_BASE_STATE);

        mController.addRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.addBaseStateRequest(baseStateRequest);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_ACTIVE);

        mController.handleNewSupportedStates(new int[]{0, 1});
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_ACTIVE);

        mController.handleNewSupportedStates(new int[]{0});
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
        assertEquals(mStatusListener.getLastStatus(baseStateRequest).intValue(), STATUS_CANCELED);
    }

    @Test
    public void cancelOverrideRequestsTest() {
        OverrideRequest firstRequest = new OverrideRequest(new Binder(), 0 /* pid */,
                1 /* requestedState */, 0 /* flags */, OVERRIDE_REQUEST_TYPE_EMULATED_STATE);

        mController.addRequest(firstRequest);
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_ACTIVE);

        mController.cancelOverrideRequest();
        assertEquals(mStatusListener.getLastStatus(firstRequest).intValue(), STATUS_CANCELED);
    }

    private static final class TestStatusChangeListener implements
            OverrideRequestController.StatusChangeListener {
        private Map<OverrideRequest, Integer> mLastStatusMap = new HashMap<>();

        @Override
        public void onStatusChanged(@NonNull OverrideRequest request, int newStatus) {
            mLastStatusMap.put(request, newStatus);
        }

        @Nullable
        public Integer getLastStatus(OverrideRequest request) {
            return mLastStatusMap.get(request);
        }
    }
}
