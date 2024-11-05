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

package com.android.server.pm.verify.pkg;

import static android.content.pm.PackageInstaller.VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;
import static android.content.pm.PackageInstaller.VERIFICATION_POLICY_BLOCK_FAIL_OPEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.verify.pkg.IVerifierService;
import android.content.pm.verify.pkg.VerificationSession;
import android.content.pm.verify.pkg.VerificationStatus;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.server.pm.Computer;
import com.android.server.pm.PackageInstallerSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VerifierControllerTest {
    private static final int TEST_ID = 100;
    private static final String TEST_PACKAGE_NAME = "com.foo";
    private static final ComponentName TEST_VERIFIER_COMPONENT_NAME =
            new ComponentName("com.verifier", "com.verifier.Service");
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
    private static final String TEST_KEY = "test key";
    private static final String TEST_VALUE = "test value";
    private static final String TEST_FAILURE_MESSAGE = "verification failed!";
    private static final long TEST_REQUEST_START_TIME = 0L;
    private static final long TEST_TIMEOUT_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long TEST_MAX_TIMEOUT_DURATION_MILLIS =
            TimeUnit.MINUTES.toMillis(10);
    private static final long TEST_VERIFIER_CONNECTION_TIMEOUT_DURATION_MILLIS =
            TimeUnit.SECONDS.toMillis(10);
    private static final int TEST_POLICY = VERIFICATION_POLICY_BLOCK_FAIL_CLOSED;

    private final ArrayList<SharedLibraryInfo> mTestDeclaredLibraries = new ArrayList<>();
    private final PersistableBundle mTestExtensionParams = new PersistableBundle();
    @Mock
    Context mContext;
    @Mock
    Handler mHandler;
    @Mock
    VerifierController.Injector mInjector;
    @Mock
    ServiceConnector<IVerifierService> mMockServiceConnector;
    @Mock
    IVerifierService mMockService;
    @Mock
    Computer mSnapshot;
    Supplier<Computer> mSnapshotSupplier = () -> mSnapshot;
    @Mock
    PackageInstallerSession.VerifierCallback mSessionCallback;

    private VerifierController mVerifierController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // Mock that the UID of this test becomes the UID of the verifier
        when(mSnapshot.getPackageUidInternal(anyString(), anyLong(), anyInt(), anyInt()))
                .thenReturn(InstrumentationRegistry.getInstrumentation().getContext()
                        .getApplicationInfo().uid);
        when(mInjector.getVerifierPackageName(any(Computer.class), anyInt())).thenReturn(
                TEST_VERIFIER_COMPONENT_NAME.getPackageName());
        when(mInjector.getRemoteService(
                any(Computer.class), any(Context.class), anyInt(), any(Handler.class)
        )).thenReturn(new Pair<>(mMockServiceConnector, TEST_VERIFIER_COMPONENT_NAME));
        when(mInjector.getVerificationRequestTimeoutMillis()).thenReturn(
                TEST_TIMEOUT_DURATION_MILLIS);
        when(mInjector.getMaxVerificationExtendedTimeoutMillis()).thenReturn(
                TEST_MAX_TIMEOUT_DURATION_MILLIS);
        when(mInjector.getVerifierConnectionTimeoutMillis()).thenReturn(
                TEST_VERIFIER_CONNECTION_TIMEOUT_DURATION_MILLIS
        );
        // Mock time forward as the code continues to check for the current time
        when(mInjector.getCurrentTimeMillis())
                .thenReturn(TEST_REQUEST_START_TIME)
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS + 1);
        when(mMockServiceConnector.post(any(ServiceConnector.VoidJob.class)))
                .thenAnswer(
                        i -> {
                            ((ServiceConnector.VoidJob) i.getArguments()[0]).run(mMockService);
                            return new AndroidFuture<>();
                        });
        when(mMockServiceConnector.run(any(ServiceConnector.VoidJob.class)))
                .thenAnswer(
                        i -> {
                            ((ServiceConnector.VoidJob) i.getArguments()[0]).run(mMockService);
                            return true;
                        });

        mTestDeclaredLibraries.add(TEST_SHARED_LIBRARY_INFO1);
        mTestDeclaredLibraries.add(TEST_SHARED_LIBRARY_INFO2);
        mTestExtensionParams.putString(TEST_KEY, TEST_VALUE);

        mVerifierController = new VerifierController(mContext, mHandler, mInjector);
    }

    @Test
    public void testVerifierNotInstalled() {
        when(mInjector.getVerifierPackageName(any(Computer.class), anyInt())).thenReturn(null);
        when(mInjector.getRemoteService(
                any(Computer.class), any(Context.class), anyInt(), any(Handler.class)
        )).thenReturn(null);
        assertThat(mVerifierController.getVerifierPackageName(mSnapshotSupplier, 0)).isNull();
        assertThat(mVerifierController.bindToVerifierServiceIfNeeded(mSnapshotSupplier, 0))
                .isFalse();
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isFalse();
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ true)).isFalse();
        verifyZeroInteractions(mSessionCallback);
    }

    @Test
    public void testRebindService() {
        assertThat(mVerifierController.bindToVerifierServiceIfNeeded(mSnapshotSupplier, 0))
                .isTrue();
    }

    @Test
    public void testVerifierAvailableButNotConnected() {
        assertThat(mVerifierController.getVerifierPackageName(mSnapshotSupplier, 0)).isNotNull();
        when(mInjector.getRemoteService(
                any(Computer.class), any(Context.class), anyInt(), any(Handler.class)
        )).thenReturn(null);
        assertThat(mVerifierController.bindToVerifierServiceIfNeeded(mSnapshotSupplier, 0))
                .isFalse();
        // Test that nothing crashes if the verifier is available even though there's no bound
        mVerifierController.notifyPackageNameAvailable(TEST_PACKAGE_NAME);
        mVerifierController.notifyVerificationCancelled(TEST_PACKAGE_NAME);
        mVerifierController.notifyVerificationTimeout(-1);
        // Since there was no bound, no call is made to the verifier
        verifyZeroInteractions(mMockService);
    }

    @Test
    public void testUnbindService() throws Exception {
        ArgumentCaptor<ServiceConnector.ServiceLifecycleCallbacks> captor = ArgumentCaptor.forClass(
                ServiceConnector.ServiceLifecycleCallbacks.class);
        assertThat(mVerifierController.bindToVerifierServiceIfNeeded(mSnapshotSupplier, 0))
                .isTrue();
        verify(mMockServiceConnector).setServiceLifecycleCallbacks(captor.capture());
        ServiceConnector.ServiceLifecycleCallbacks<IVerifierService> callbacks = captor.getValue();
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService, times(1)).onVerificationRequired(any(VerificationSession.class));
        callbacks.onBinderDied();
        // Test that nothing crashes if the service connection is lost
        assertThat(mVerifierController.getVerifierPackageName(mSnapshotSupplier, 0)).isNotNull();
        mVerifierController.notifyPackageNameAvailable(TEST_PACKAGE_NAME);
        mVerifierController.notifyVerificationCancelled(TEST_PACKAGE_NAME);
        mVerifierController.notifyVerificationTimeout(TEST_ID);
        verifyNoMoreInteractions(mMockService);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ true)).isTrue();
        mVerifierController.notifyVerificationTimeout(TEST_ID);
        verify(mMockService, times(1)).onVerificationTimeout(eq(TEST_ID));
    }

    @Test
    public void testNotifyPackageNameAvailable() throws Exception {
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        mVerifierController.notifyPackageNameAvailable(TEST_PACKAGE_NAME);
        verify(mMockService).onPackageNameAvailable(eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testNotifyVerificationCancelled() throws Exception {
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        mVerifierController.notifyVerificationCancelled(TEST_PACKAGE_NAME);
        verify(mMockService).onVerificationCancelled(eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testStartVerificationSession() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        assertThat(session.getId()).isEqualTo(TEST_ID);
        assertThat(session.getInstallSessionId()).isEqualTo(TEST_ID);
        assertThat(session.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(session.getStagedPackageUri()).isEqualTo(TEST_PACKAGE_URI);
        assertThat(session.getSigningInfo().getSigningDetails())
                .isEqualTo(TEST_SIGNING_INFO.getSigningDetails());
        List<SharedLibraryInfo> declaredLibraries = session.getDeclaredLibraries();
        // SharedLibraryInfo doesn't have a "equals" method, so we have to check it indirectly
        assertThat(declaredLibraries.getFirst().toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO1.toString());
        assertThat(declaredLibraries.get(1).toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO2.toString());
        // We can't directly test with PersistableBundle.equals() because the parceled bundle's
        // structure is different, but all the key/value pairs should be preserved as before.
        assertThat(session.getExtensionParams().getString(TEST_KEY))
                .isEqualTo(mTestExtensionParams.getString(TEST_KEY));
    }

    @Test
    public void testNotifyVerificationRetry() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ true)).isTrue();
        verify(mMockService).onVerificationRetry(captor.capture());
        VerificationSession session = captor.getValue();
        assertThat(session.getId()).isEqualTo(TEST_ID);
        assertThat(session.getInstallSessionId()).isEqualTo(TEST_ID);
        assertThat(session.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(session.getStagedPackageUri()).isEqualTo(TEST_PACKAGE_URI);
        assertThat(session.getSigningInfo().getSigningDetails())
                .isEqualTo(TEST_SIGNING_INFO.getSigningDetails());
        List<SharedLibraryInfo> declaredLibraries = session.getDeclaredLibraries();
        // SharedLibraryInfo doesn't have a "equals" method, so we have to check it indirectly
        assertThat(declaredLibraries.getFirst().toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO1.toString());
        assertThat(declaredLibraries.get(1).toString())
                .isEqualTo(TEST_SHARED_LIBRARY_INFO2.toString());
        // We can't directly test with PersistableBundle.equals() because the parceled bundle's
        // structure is different, but all the key/value pairs should be preserved as before.
        assertThat(session.getExtensionParams().getString(TEST_KEY))
                .isEqualTo(mTestExtensionParams.getString(TEST_KEY));
    }

    @Test
    public void testNotifyVerificationTimeout() throws Exception {
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ true)).isTrue();
        mVerifierController.notifyVerificationTimeout(TEST_ID);
        verify(mMockService).onVerificationTimeout(eq(TEST_ID));
    }

    @Test
    public void testRequestTimeout() {
        // Let the mock handler set request to TIMEOUT, immediately after the request is sent.
        // We can't mock postDelayed because it's final, but we can mock the method it calls.
        when(mHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                i -> {
                    ((Message) i.getArguments()[0]).getCallback().run();
                    return true;
                });
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mHandler, times(1)).sendMessageAtTime(any(Message.class), anyLong());
        verify(mSessionCallback, times(1)).onTimeout();
        verify(mInjector, times(2)).getCurrentTimeMillis();
        verify(mInjector, times(1)).stopTimeoutCountdown(eq(mHandler), any());
    }

    @Test
    public void testRequestTimeoutWithRetryPass() throws Exception {
        // Only let the first request timeout and let the second one pass
        when(mHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                        i -> {
                            ((Message) i.getArguments()[0]).getCallback().run();
                            return true;
                        })
                .thenAnswer(i -> true);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mHandler, times(1)).sendMessageAtTime(any(Message.class), anyLong());
        verify(mSessionCallback, times(1)).onTimeout();
        verify(mInjector, times(2)).getCurrentTimeMillis();
        verify(mInjector, times(1)).stopTimeoutCountdown(eq(mHandler), any());
        // Then retry
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ true)).isTrue();
        verify(mMockService).onVerificationRetry(captor.capture());
        VerificationSession session = captor.getValue();
        VerificationStatus status = new VerificationStatus.Builder().setVerified(true).build();
        session.reportVerificationComplete(status);
        verify(mSessionCallback, times(1)).onVerificationCompleteReceived(
                eq(status), eq(null));
        verify(mInjector, times(2)).stopTimeoutCountdown(eq(mHandler), any());
    }

    @Test
    public void testRequestIncomplete() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        session.reportVerificationIncomplete(VerificationSession.VERIFICATION_INCOMPLETE_UNKNOWN);
        verify(mSessionCallback, times(1)).onVerificationIncompleteReceived(
                eq(VerificationSession.VERIFICATION_INCOMPLETE_UNKNOWN));
        verify(mInjector, times(1)).stopTimeoutCountdown(eq(mHandler), any());
    }

    @Test
    public void testRequestCompleteWithSuccessWithExtensionResponse() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        VerificationStatus status = new VerificationStatus.Builder().setVerified(true).build();
        PersistableBundle bundle = new PersistableBundle();
        session.reportVerificationComplete(status, bundle);
        verify(mSessionCallback, times(1)).onVerificationCompleteReceived(
                eq(status), eq(bundle));
        verify(mInjector, times(1)).stopTimeoutCountdown(eq(mHandler), any());
    }

    @Test
    public void testRequestCompleteWithFailure() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        VerificationStatus status = new VerificationStatus.Builder()
                .setVerified(false)
                .setFailureMessage(TEST_FAILURE_MESSAGE)
                .build();
        session.reportVerificationComplete(status);
        verify(mSessionCallback, times(1)).onVerificationCompleteReceived(
                eq(status), eq(null));
        verify(mInjector, times(1)).stopTimeoutCountdown(eq(mHandler), any());
    }

    @Test
    public void testRepeatedRequestCompleteShouldThrow() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        assertThat(mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false)).isTrue();
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        VerificationStatus status = new VerificationStatus.Builder().setVerified(true).build();
        session.reportVerificationComplete(status);
        // getters should throw after the report
        expectThrows(IllegalStateException.class, () -> session.getTimeoutTime());
        // Report again should fail with exception
        expectThrows(IllegalStateException.class, () -> session.reportVerificationComplete(status));
    }

    @Test
    public void testExtendTimeRemaining() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false);
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        final long initialTimeoutTime = TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS;
        assertThat(session.getTimeoutTime()).isEqualTo(initialTimeoutTime);
        final long extendTimeMillis = TEST_TIMEOUT_DURATION_MILLIS;
        assertThat(session.extendTimeRemaining(extendTimeMillis)).isEqualTo(extendTimeMillis);
        assertThat(session.getTimeoutTime()).isEqualTo(initialTimeoutTime + extendTimeMillis);
    }

    @Test
    public void testExtendTimeExceedsMax() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false);
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        final long initialTimeoutTime = TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS;
        final long maxTimeoutTime = TEST_REQUEST_START_TIME + TEST_MAX_TIMEOUT_DURATION_MILLIS;
        assertThat(session.getTimeoutTime()).isEqualTo(initialTimeoutTime);
        final long extendTimeMillis = TEST_MAX_TIMEOUT_DURATION_MILLIS;
        assertThat(session.extendTimeRemaining(extendTimeMillis)).isEqualTo(
                TEST_MAX_TIMEOUT_DURATION_MILLIS - TEST_TIMEOUT_DURATION_MILLIS);
        assertThat(session.getTimeoutTime()).isEqualTo(maxTimeoutTime);
    }

    @Test
    public void testTimeoutChecksMultipleTimes() {
        // Mock message handling
        when(mHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                        i -> {
                            ((Message) i.getArguments()[0]).getCallback().run();
                            return true;
                        });
        // Mock time forward as the code continues to check for the current time
        when(mInjector.getCurrentTimeMillis())
                // First called when the tracker is created
                .thenReturn(TEST_REQUEST_START_TIME)
                // Then mock the first timeout check when the timeout time isn't reached yet
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS - 1000)
                // Then mock the same time used to check the remaining time
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS - 1000)
                // Then mock the second timeout check when the timeout time isn't reached yet
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS - 100)
                // Then mock the same time used to check the remaining time
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS - 100)
                // Then mock the third timeout check when the timeout time has been reached
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS + 1);
        mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false);
        verify(mHandler, times(3)).sendMessageAtTime(any(Message.class), anyLong());
        verify(mInjector, times(6)).getCurrentTimeMillis();
        verify(mSessionCallback, times(1)).onTimeout();
    }

    @Test
    public void testPolicyOverride() throws Exception {
        ArgumentCaptor<VerificationSession> captor =
                ArgumentCaptor.forClass(VerificationSession.class);
        mVerifierController.startVerificationSession(
                mSnapshotSupplier, 0, TEST_ID, TEST_PACKAGE_NAME, TEST_PACKAGE_URI,
                TEST_SIGNING_INFO, mTestDeclaredLibraries, TEST_POLICY, mTestExtensionParams,
                mSessionCallback, /* retry= */ false);
        verify(mMockService).onVerificationRequired(captor.capture());
        VerificationSession session = captor.getValue();
        final int policy = VERIFICATION_POLICY_BLOCK_FAIL_OPEN;
        when(mSessionCallback.setVerificationPolicy(eq(policy))).thenReturn(true);
        assertThat(session.setVerificationPolicy(policy)).isTrue();
        assertThat(session.getVerificationPolicy()).isEqualTo(policy);
        verify(mSessionCallback, times(1)).setVerificationPolicy(eq(policy));
    }
}
