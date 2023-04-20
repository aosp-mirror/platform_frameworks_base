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

import android.platform.test.annotations.Presubmit;
import android.telecom.PhoneAccount;
import android.testing.AndroidTestingRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class CallMetadataSyncConnectionServiceTest {

    private CallMetadataSyncConnectionService mSyncConnectionService;

    @Before
    public void setUp() throws Exception {
        mSyncConnectionService = new CallMetadataSyncConnectionService() {
            @Override
            public String getPackageName() {
                return "android";
            }
        };
    }

    @Test
    public void createPhoneAccount_success() {
        final PhoneAccount phoneAccount = mSyncConnectionService.createPhoneAccount(
                "com.google.test", "Test App");
        assertWithMessage("Could not create phone account").that(phoneAccount).isNotNull();
    }

    @Test
    public void createPhoneAccount_alreadyExists_doesNotCreateAnother() {
        final PhoneAccount phoneAccount = mSyncConnectionService.createPhoneAccount(
                "com.google.test", "Test App");
        final PhoneAccount phoneAccount2 = mSyncConnectionService.createPhoneAccount(
                "com.google.test", "Test App #2");
        assertWithMessage("Could not create phone account").that(phoneAccount).isNotNull();
        assertWithMessage("Unexpectedly created second phone account").that(phoneAccount2).isNull();
    }
}
