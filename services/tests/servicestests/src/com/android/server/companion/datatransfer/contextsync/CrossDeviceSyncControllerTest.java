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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
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
    @Mock
    private TelecomManager mMockTelecomManager;
    @Mock
    private Context mMockContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCrossDeviceSyncController = new CrossDeviceSyncController(
                InstrumentationRegistry.getInstrumentation().getContext(),
                mMockCompanionTransportManager);
        when(mMockContext.getSystemServiceName(TelecomManager.class))
                .thenReturn(Context.TELECOM_SERVICE);
        when(mMockContext.getSystemService(Context.TELECOM_SERVICE))
                .thenReturn(mMockTelecomManager);
        doNothing().when(mMockTelecomManager).registerPhoneAccount(any());
        doNothing().when(mMockTelecomManager).unregisterPhoneAccount(any());
    }

    @Test
    public void processTelecomDataFromSync_createCallUpdateMessage_emptyCallsAndRequests() {
        final byte[] data = mCrossDeviceSyncController.createCallUpdateMessage(new HashSet<>(),
                InstrumentationRegistry.getInstrumentation().getContext().getUserId());
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Unexpectedly found a call").that(
                callMetadataSyncData.getCalls()).isEmpty();
        assertWithMessage("Unexpectedly has control requests").that(
                callMetadataSyncData.getCallControlRequests()).isEmpty();
        assertWithMessage("Unexpectedly has create requests").that(
                callMetadataSyncData.getCallCreateRequests()).isEmpty();
    }

    @Test
    public void processTelecomDataFromSync_createEmptyMessage_emptyCallsAndRequests() {
        final byte[] data = CrossDeviceSyncController.createEmptyMessage();
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Unexpectedly found a call").that(
                callMetadataSyncData.getCalls()).isEmpty();
        assertWithMessage("Unexpectedly has control requests").that(
                callMetadataSyncData.getCallControlRequests()).isEmpty();
        assertWithMessage("Unexpectedly has create requests").that(
                callMetadataSyncData.getCallCreateRequests()).isEmpty();
    }

    @Test
    public void processTelecomDataFromSync_createCallUpdateMessage_hasCalls() {
        when(mMockCrossDeviceCall.getId()).thenReturn("123abc");
        final String callerId = "Firstname Lastname";
        when(mMockCrossDeviceCall.getReadableCallerId(anyBoolean())).thenReturn(callerId);
        final String appName = "AppName";
        when(mMockCrossDeviceCall.getCallingAppName()).thenReturn(appName);
        final String pkgName = "com.google.test";
        when(mMockCrossDeviceCall.getCallingAppPackageName()).thenReturn(pkgName);
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
        final CallMetadataSyncData.CallFacilitator facilitator = call.getFacilitator();
        assertWithMessage("Wrong app name").that(facilitator.getName()).isEqualTo(appName);
        assertWithMessage("Wrong pkg name").that(facilitator.getIdentifier()).isEqualTo(pkgName);
        assertWithMessage("Wrong caller id").that(call.getCallerId()).isEqualTo(callerId);
        assertWithMessage("Wrong status").that(call.getStatus())
                .isEqualTo(android.companion.Telecom.Call.RINGING);
        assertWithMessage("Wrong controls").that(call.getControls()).isEqualTo(controls);
        assertWithMessage("Unexpectedly has control requests").that(
                callMetadataSyncData.getCallControlRequests()).isEmpty();
        assertWithMessage("Unexpectedly has create requests").that(
                callMetadataSyncData.getCallCreateRequests()).isEmpty();
    }

    @Test
    public void processTelecomDataFromMessage_createCallControlMessage_hasCallControlRequest() {
        final byte[] data = CrossDeviceSyncController.createCallControlMessage(
                /* callId= */ "5678abc", /* status= */ android.companion.Telecom.ACCEPT);
        final CallMetadataSyncData callMetadataSyncData =
                mCrossDeviceSyncController.processTelecomDataFromSync(data);
        assertWithMessage("Wrong number of requests").that(
                callMetadataSyncData.getCallControlRequests()).hasSize(1);
        final CallMetadataSyncData.CallControlRequest request =
                callMetadataSyncData.getCallControlRequests().stream().findAny().orElseThrow();
        assertWithMessage("Wrong id").that(request.getId()).isEqualTo("5678abc");
        assertWithMessage("Wrong control").that(request.getControl())
                .isEqualTo(android.companion.Telecom.ACCEPT);
        assertWithMessage("Unexpectedly has active calls").that(
                callMetadataSyncData.getCalls()).isEmpty();
    }

    @Test
    public void createPhoneAccount_success() {
        final PhoneAccount phoneAccount =
                CrossDeviceSyncController.PhoneAccountManager.createPhoneAccount(
                        new PhoneAccountHandle(
                                new ComponentName("com.google.test", "com.google.test.Activity"),
                                "id"), "Test App", "com.google.test");
        assertWithMessage("Could not create phone account").that(phoneAccount).isNotNull();
    }

    @Test
    public void updateFacilitators_alreadyExists_doesNotCreateAnother() {
        final CrossDeviceSyncController.PhoneAccountManager phoneAccountManager =
                new CrossDeviceSyncController.PhoneAccountManager(mMockContext);
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        callMetadataSyncData.addFacilitator(
                new CallMetadataSyncData.CallFacilitator("name", "com.google.test"));
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData);
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData);
        verify(mMockTelecomManager, times(1)).registerPhoneAccount(any());
        verify(mMockTelecomManager, times(0)).unregisterPhoneAccount(any());
    }

    @Test
    public void updateFacilitators_new_addsIt() {
        final CrossDeviceSyncController.PhoneAccountManager phoneAccountManager =
                new CrossDeviceSyncController.PhoneAccountManager(mMockContext);
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        callMetadataSyncData.addFacilitator(
                new CallMetadataSyncData.CallFacilitator("name", "com.google.test"));
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData);
        callMetadataSyncData.addFacilitator(
                new CallMetadataSyncData.CallFacilitator("name", "com.google.test2"));
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData);
        verify(mMockTelecomManager, times(2)).registerPhoneAccount(any());
        verify(mMockTelecomManager, times(0)).unregisterPhoneAccount(any());
    }

    @Test
    public void updateFacilitators_old_removesIt() {
        final CrossDeviceSyncController.PhoneAccountManager phoneAccountManager =
                new CrossDeviceSyncController.PhoneAccountManager(mMockContext);
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        callMetadataSyncData.addFacilitator(
                new CallMetadataSyncData.CallFacilitator("name", "com.google.test"));
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData);
        final CallMetadataSyncData callMetadataSyncData2 = new CallMetadataSyncData();
        phoneAccountManager.updateFacilitators(0, callMetadataSyncData2);
        verify(mMockTelecomManager, times(1)).registerPhoneAccount(any());
        verify(mMockTelecomManager, times(1)).unregisterPhoneAccount(any());
    }
}
