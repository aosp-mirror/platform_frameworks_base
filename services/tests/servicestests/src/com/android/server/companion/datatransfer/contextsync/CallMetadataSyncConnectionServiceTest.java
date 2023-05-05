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


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.media.AudioManager;
import android.platform.test.annotations.Presubmit;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class CallMetadataSyncConnectionServiceTest {

    private CallMetadataSyncConnectionService mSyncConnectionService;
    @Mock
    private TelecomManager mMockTelecomManager;
    @Mock
    private AudioManager mMockAudioManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doNothing().when(mMockTelecomManager).registerPhoneAccount(any());
        doNothing().when(mMockTelecomManager).unregisterPhoneAccount(any());
        mSyncConnectionService = new CallMetadataSyncConnectionService() {
            @Override
            public String getPackageName() {
                return "android";
            }
        };
        mSyncConnectionService.mTelecomManager = mMockTelecomManager;
        mSyncConnectionService.mAudioManager = mMockAudioManager;
    }

    @Test
    public void processContextSyncMessage_empty() {
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        mSyncConnectionService.mCrossDeviceSyncControllerCallback.processContextSyncMessage(
                /* associationId= */ 0, callMetadataSyncData);
        verify(mMockTelecomManager, never()).addNewIncomingCall(any(), any());
    }

    @Test
    public void processContextSyncMessage_newCall() {
        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        call.setId("123abc");
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        callMetadataSyncData.addCall(call);
        mSyncConnectionService.mCrossDeviceSyncControllerCallback.processContextSyncMessage(
                /* associationId= */ 0, callMetadataSyncData);
        verify(mMockTelecomManager, times(1)).addNewIncomingCall(any(), any());
    }

    @Test
    public void processContextSyncMessage_existingCall() {
        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        call.setId("123abc");
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        callMetadataSyncData.addCall(call);
        mSyncConnectionService.mActiveConnections.put(
                new CallMetadataSyncConnectionService.CallMetadataSyncConnectionIdentifier(
                        /* asscociationId= */ 0, "123abc"),
                new CallMetadataSyncConnectionService.CallMetadataSyncConnection(
                        mMockTelecomManager, mMockAudioManager, 0, call,
                        new CallMetadataSyncConnectionService.CallMetadataSyncConnectionCallback() {
                            @Override
                            void sendCallAction(int associationId, String callId, int action) {}
                        }));
        mSyncConnectionService.mCrossDeviceSyncControllerCallback.processContextSyncMessage(
                /* associationId= */ 0, callMetadataSyncData);
        verify(mMockTelecomManager, never()).addNewIncomingCall(any(), any());
    }
}
