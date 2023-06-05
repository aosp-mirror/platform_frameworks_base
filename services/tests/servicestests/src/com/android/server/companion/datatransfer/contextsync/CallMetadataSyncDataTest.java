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

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.testing.AndroidTestingRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
public class CallMetadataSyncDataTest {

    @Test
    public void call_writeToBundle_fromBundle_reconstructsSuccessfully() {
        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        final String id = "5";
        final String callerId = "callerId";
        final byte[] appIcon = "appIcon".getBytes();
        final String appName = "appName";
        final String appIdentifier = "com.google.test";
        final int status = 1;
        final int direction = android.companion.Telecom.Call.OUTGOING;
        final int control1 = 2;
        final int control2 = 3;
        call.setId(id);
        call.setCallerId(callerId);
        call.setAppIcon(appIcon);
        final CallMetadataSyncData.CallFacilitator callFacilitator =
                new CallMetadataSyncData.CallFacilitator(appName, appIdentifier);
        call.setFacilitator(callFacilitator);
        call.setStatus(status);
        call.setDirection(direction);
        call.addControl(control1);
        call.addControl(control2);

        final Bundle bundle = call.writeToBundle();
        final CallMetadataSyncData.Call reconstructedCall = CallMetadataSyncData.Call.fromBundle(
                bundle);

        assertThat(reconstructedCall.getId()).isEqualTo(id);
        assertThat(reconstructedCall.getCallerId()).isEqualTo(callerId);
        assertThat(reconstructedCall.getAppIcon()).isEqualTo(appIcon);
        assertThat(reconstructedCall.getFacilitator().getName()).isEqualTo(appName);
        assertThat(reconstructedCall.getFacilitator().getIdentifier()).isEqualTo(appIdentifier);
        assertThat(reconstructedCall.getStatus()).isEqualTo(status);
        assertThat(reconstructedCall.getDirection()).isEqualTo(direction);
        assertThat(reconstructedCall.getControls()).containsExactly(control1, control2);
    }
}
