/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity;

import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;

import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test for {@link com.android.server.integrity.AppIntegrityManagerServiceImpl} */
@RunWith(AndroidJUnit4.class)
public class AppIntegrityManagerServiceImplTest {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock PackageManagerInternal mPackageManagerInternal;

    // under test
    private AppIntegrityManagerServiceImpl mService;

    @Before
    public void setup() {
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);

        mService = new AppIntegrityManagerServiceImpl(InstrumentationRegistry.getContext());
    }

    @Test
    public void integrityVerification_allow() {
        int verificationId = 2;
        Intent integrityVerificationIntent = new Intent();
        integrityVerificationIntent.setAction(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        integrityVerificationIntent.putExtra(EXTRA_VERIFICATION_ID, verificationId);

        // We cannot send the broadcast using the context since it is a protected broadcast and
        // we will get a security exception.
        mService.handleIntegrityVerification(integrityVerificationIntent);

        verify(mPackageManagerInternal)
                .setIntegrityVerificationResult(verificationId, PackageManager.VERIFICATION_ALLOW);
    }
}
