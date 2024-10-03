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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.verify.pkg.IVerificationSessionCallback;
import android.content.pm.verify.pkg.IVerificationSessionInterface;
import android.content.pm.verify.pkg.VerificationSession;
import android.content.pm.verify.pkg.VerificationStatus;
import android.net.Uri;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VerificationSessionTest {
    private static final int TEST_ID = 100;
    private static final int TEST_INSTALL_SESSION_ID = 33;
    private static final String TEST_PACKAGE_NAME = "com.foo";
    private static final Uri TEST_PACKAGE_URI = Uri.parse("test://test");
    private static final SigningInfo TEST_SIGNING_INFO = new SigningInfo();
    private static final SharedLibraryInfo TEST_SHARED_LIBRARY_INFO1 =
            new SharedLibraryInfo("sharedLibPath1", TEST_PACKAGE_NAME,
                    Collections.singletonList("path1"), "sharedLib1", 101,
                    SharedLibraryInfo.TYPE_DYNAMIC, new VersionedPackage(TEST_PACKAGE_NAME, 1),
                    null, null, false);
    private static final SharedLibraryInfo TEST_SHARED_LIBRARY_INFO2 =
            new SharedLibraryInfo("sharedLibPath2", TEST_PACKAGE_NAME,
                    Collections.singletonList("path2"), "sharedLib2", 102,
                    SharedLibraryInfo.TYPE_DYNAMIC,
                    new VersionedPackage(TEST_PACKAGE_NAME, 2), null, null, false);
    private static final long TEST_TIMEOUT_TIME = System.currentTimeMillis();
    private static final long TEST_EXTEND_TIME = 2000L;
    private static final String TEST_KEY = "test key";
    private static final String TEST_VALUE = "test value";

    private final ArrayList<SharedLibraryInfo> mTestDeclaredLibraries = new ArrayList<>();
    private final PersistableBundle mTestExtensionParams = new PersistableBundle();
    @Mock
    private IVerificationSessionInterface mTestSessionInterface;
    @Mock
    private IVerificationSessionCallback mTestCallback;
    private VerificationSession mTestSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestDeclaredLibraries.add(TEST_SHARED_LIBRARY_INFO1);
        mTestDeclaredLibraries.add(TEST_SHARED_LIBRARY_INFO2);
        mTestExtensionParams.putString(TEST_KEY, TEST_VALUE);
        mTestSession = new VerificationSession(TEST_ID, TEST_INSTALL_SESSION_ID,
                TEST_PACKAGE_NAME, TEST_PACKAGE_URI, TEST_SIGNING_INFO, mTestDeclaredLibraries,
                mTestExtensionParams, mTestSessionInterface, mTestCallback);
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        mTestSession.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VerificationSession sessionFromParcel =
                VerificationSession.CREATOR.createFromParcel(parcel);
        assertThat(sessionFromParcel.getId()).isEqualTo(TEST_ID);
        assertThat(sessionFromParcel.getInstallSessionId()).isEqualTo(TEST_INSTALL_SESSION_ID);
        assertThat(sessionFromParcel.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(sessionFromParcel.getStagedPackageUri()).isEqualTo(TEST_PACKAGE_URI);
        assertThat(sessionFromParcel.getSigningInfo().getSigningDetails())
                .isEqualTo(TEST_SIGNING_INFO.getSigningDetails());
        List<SharedLibraryInfo> declaredLibrariesFromParcel =
                sessionFromParcel.getDeclaredLibraries();
        assertThat(declaredLibrariesFromParcel).hasSize(2);
        // SharedLibraryInfo doesn't have a "equals" method, so we have to check it indirectly
        assertThat(declaredLibrariesFromParcel.getFirst().toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO1.toString());
        assertThat(declaredLibrariesFromParcel.get(1).toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO2.toString());
        // We can't directly test with PersistableBundle.equals() because the parceled bundle's
        // structure is different, but all the key/value pairs should be preserved as before.
        assertThat(sessionFromParcel.getExtensionParams().getString(TEST_KEY))
                .isEqualTo(mTestExtensionParams.getString(TEST_KEY));
    }

    @Test
    public void testInterface() throws Exception {
        when(mTestSessionInterface.getTimeoutTime(anyInt())).thenAnswer(i -> TEST_TIMEOUT_TIME);
        when(mTestSessionInterface.extendTimeRemaining(anyInt(), anyLong())).thenAnswer(
                i -> i.getArguments()[1]);

        assertThat(mTestSession.getTimeoutTime()).isEqualTo(TEST_TIMEOUT_TIME);
        verify(mTestSessionInterface, times(1)).getTimeoutTime(eq(TEST_ID));
        assertThat(mTestSession.extendTimeRemaining(TEST_EXTEND_TIME)).isEqualTo(TEST_EXTEND_TIME);
        verify(mTestSessionInterface, times(1)).extendTimeRemaining(
                eq(TEST_ID), eq(TEST_EXTEND_TIME));
    }

    @Test
    public void testCallback() throws Exception {
        PersistableBundle response = new PersistableBundle();
        response.putString("test key", "test value");
        final VerificationStatus status =
                new VerificationStatus.Builder().setVerified(true).build();
        mTestSession.reportVerificationComplete(status);
        verify(mTestCallback, times(1)).reportVerificationComplete(
                eq(TEST_ID), eq(status));
        mTestSession.reportVerificationComplete(status, response);
        verify(mTestCallback, times(1))
                .reportVerificationCompleteWithExtensionResponse(
                        eq(TEST_ID), eq(status), eq(response));

        final int reason = VerificationSession.VERIFICATION_INCOMPLETE_UNKNOWN;
        mTestSession.reportVerificationIncomplete(reason);
        verify(mTestCallback, times(1)).reportVerificationIncomplete(
                eq(TEST_ID), eq(reason));
    }
}
