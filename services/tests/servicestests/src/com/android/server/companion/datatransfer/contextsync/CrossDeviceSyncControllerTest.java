/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.companion.transport.CompanionTransportManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class CrossDeviceSyncControllerTest {

    private CrossDeviceSyncController mCrossDeviceSyncController;
    @Mock
    private CompanionTransportManager mMockCompanionTransportManager;
    @Mock
    private CrossDeviceCall mMockCrossDeviceCall;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCrossDeviceSyncController = new CrossDeviceSyncController(
                InstrumentationRegistry.getInstrumentation().getContext(),
                mMockCompanionTransportManager);
    }

    @Test
    public void processTelecomDataFromSync_createCallUpdateMessage_emptyCallsAndRequests() {
        final byte[] data = mCrossDeviceSyncController.createCallUpdateMessage(new HashSet<>(),
                InstrumentationRegistry.getInstrumentation().getContext().getUserId());
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Unexpectedly found a call").that(
                callMetadataSyncData.getCalls()).isEmpty();
        assertWithMessage("Unexpectedly found a request").that(
                callMetadataSyncData.getRequests()).isEmpty();
    }

    @Test
    public void processTelecomDataFromSync_createEmptyMessage_emptyCallsAndRequests() {
        final byte[] data = CrossDeviceSyncController.createEmptyMessage();
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Unexpectedly found a call").that(
                callMetadataSyncData.getCalls()).isEmpty();
        assertWithMessage("Unexpectedly found a request").that(
                callMetadataSyncData.getRequests()).isEmpty();
    }

    @Test
    public void processTelecomDataFromSync_createCallUpdateMessage_hasCalls() {
        when(mMockCrossDeviceCall.getId()).thenReturn("123abc");
        final String callerId = "Firstname Lastname";
        when(mMockCrossDeviceCall.getReadableCallerId(anyBoolean())).thenReturn(callerId);
        final String appName = "AppName";
        when(mMockCrossDeviceCall.getCallingAppName()).thenReturn(appName);
        final String appIcon = "ABCD";
        when(mMockCrossDeviceCall.getCallingAppIcon()).thenReturn(appIcon.getBytes());
        when(mMockCrossDeviceCall.getStatus()).thenReturn(android.companion.Telecom.Call.RINGING);
        final Set<Integer> controls = Set.of(
                android.companion.Telecom.ACCEPT,
                android.companion.Telecom.REJECT,
                android.companion.Telecom.SILENCE);
        when(mMockCrossDeviceCall.getControls()).thenReturn(controls);
        final byte[] data = mCrossDeviceSyncController.createCallUpdateMessage(
                new HashSet<>(List.of(mMockCrossDeviceCall)),
                InstrumentationRegistry.getInstrumentation().getContext().getUserId());
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Wrong number of active calls").that(
                callMetadataSyncData.getCalls()).hasSize(1);
        final CallMetadataSyncData.Call call =
                callMetadataSyncData.getCalls().stream().findAny().orElseThrow();
        assertWithMessage("Wrong id").that(call.getId()).isEqualTo("123abc");
        assertWithMessage("Wrong app icon").that(new String(call.getAppIcon())).isEqualTo(appIcon);
        assertWithMessage("Wrong app name").that(call.getAppName()).isEqualTo(appName);
        assertWithMessage("Wrong caller id").that(call.getCallerId()).isEqualTo(callerId);
        assertWithMessage("Wrong status").that(call.getStatus())
                .isEqualTo(android.companion.Telecom.Call.RINGING);
        assertWithMessage("Wrong controls").that(call.getControls()).isEqualTo(controls);
    }

    @Test
    public void processTelecomDataFromMessage_createCallControlMessage_hasCallControlRequest() {
        final byte[] data = CrossDeviceSyncController.createCallControlMessage(
                /* callId= */ "123abc", /* status= */ android.companion.Telecom.ACCEPT);
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Wrong number of requests").that(
                callMetadataSyncData.getRequests()).hasSize(1);
        final CallMetadataSyncData.Call call =
                callMetadataSyncData.getRequests().stream().findAny().orElseThrow();
        assertWithMessage("Wrong id").that(call.getId()).isEqualTo("123abc");
        assertWithMessage("Wrong app icon").that(call.getAppIcon()).isNull();
        assertWithMessage("Wrong app name").that(call.getAppName()).isNull();
        assertWithMessage("Wrong caller id").that(call.getCallerId()).isNull();
        assertWithMessage("Wrong status").that(call.getStatus())
                .isEqualTo(android.companion.Telecom.Call.UNKNOWN_STATUS);
        assertWithMessage("Wrong controls").that(call.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.ACCEPT));
        assertWithMessage("Unexpectedly has active calls").that(
                callMetadataSyncData.getCalls()).isEmpty();
    }
}
