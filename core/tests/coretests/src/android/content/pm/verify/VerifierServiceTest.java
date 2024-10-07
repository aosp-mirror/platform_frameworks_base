/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.content.pm.verify.pkg.IVerifierService;
import android.content.pm.verify.pkg.VerificationSession;
import android.content.pm.verify.pkg.VerifierService;
import android.net.Uri;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VerifierServiceTest {
    private static final int TEST_ID = 100;
    private static final int TEST_INSTALL_SESSION_ID = 33;
    private static final String TEST_PACKAGE_NAME = "com.foo";
    private static final Uri TEST_PACKAGE_URI = Uri.parse("test://test");
    private static final SigningInfo TEST_SIGNING_INFO = new SigningInfo();
    private VerifierService mService;
    private VerificationSession mSession;

    @Before
    public void setUp() {
        mService = Mockito.mock(VerifierService.class, Answers.CALLS_REAL_METHODS);
        mSession = new VerificationSession(TEST_ID, TEST_INSTALL_SESSION_ID,
                TEST_PACKAGE_NAME, TEST_PACKAGE_URI, TEST_SIGNING_INFO,
                new ArrayList<>(),
                new PersistableBundle(), null, null);
    }

    @Test
    public void testBind() throws Exception {
        Intent intent = Mockito.mock(Intent.class);
        when(intent.getAction()).thenReturn(PackageManager.ACTION_VERIFY_PACKAGE);
        IVerifierService binder =
                (IVerifierService) mService.onBind(intent);
        assertThat(binder).isNotNull();
        binder.onPackageNameAvailable(TEST_PACKAGE_NAME);
        verify(mService).onPackageNameAvailable(eq(TEST_PACKAGE_NAME));
        binder.onVerificationCancelled(TEST_PACKAGE_NAME);
        verify(mService).onVerificationCancelled(eq(TEST_PACKAGE_NAME));
        binder.onVerificationRequired(mSession);
        verify(mService).onVerificationRequired(eq(mSession));
        binder.onVerificationRetry(mSession);
        verify(mService).onVerificationRetry(eq(mSession));
        binder.onVerificationTimeout(TEST_ID);
        verify(mService).onVerificationTimeout(eq(TEST_ID));
    }

    @Test
    public void testBindFailsWithWrongIntent() {
        Intent intent = Mockito.mock(Intent.class);
        when(intent.getAction()).thenReturn(Intent.ACTION_SEND);
        assertThat(mService.onBind(intent)).isNull();
    }
}
