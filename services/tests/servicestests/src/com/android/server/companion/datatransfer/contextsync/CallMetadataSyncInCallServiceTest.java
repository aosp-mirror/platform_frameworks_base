/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class CallMetadataSyncInCallServiceTest {

    private CallMetadataSyncInCallService mSyncInCallService;
    @Mock
    private CrossDeviceCall mMockCrossDeviceCall;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSyncInCallService = new CallMetadataSyncInCallService();
    }

    @Test
    public void getCallForId_invalid() {
        when(mMockCrossDeviceCall.getId()).thenReturn(-1L);
        final CrossDeviceCall call = mSyncInCallService.getCallForId(-1L,
                List.of(mMockCrossDeviceCall));
        assertWithMessage("Unexpectedly found a match for call id").that(call).isNull();
    }

    @Test
    public void getCallForId_noMatch() {
        when(mMockCrossDeviceCall.getId()).thenReturn(5L);
        final CrossDeviceCall call = mSyncInCallService.getCallForId(1L,
                List.of(mMockCrossDeviceCall));
        assertWithMessage("Unexpectedly found a match for call id").that(call).isNull();
    }

    @Test
    public void getCallForId_hasMatch() {
        when(mMockCrossDeviceCall.getId()).thenReturn(5L);
        final CrossDeviceCall call = mSyncInCallService.getCallForId(5L,
                List.of(mMockCrossDeviceCall));
        assertWithMessage("Unexpectedly did not find a match for call id").that(call).isNotNull();
    }
}
