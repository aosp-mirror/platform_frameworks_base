/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import com.android.timezone.distro.DistroVersion;
import com.android.timezone.distro.StagedDistroOperation;
import com.android.timezone.distro.TimeZoneDistro;
import com.android.timezone.distro.installer.TimeZoneDistroInstaller;

import org.junit.Before;
import org.junit.Test;

import android.app.timezone.Callback;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.ICallback;
import android.app.timezone.RulesManager;
import android.app.timezone.RulesState;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

import libcore.io.IoUtils;
import libcore.timezone.TzDataSetVersion;

import static com.android.server.timezone.RulesManagerService.REQUIRED_QUERY_PERMISSION;
import static com.android.server.timezone.RulesManagerService.REQUIRED_UPDATER_PERMISSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * White box interaction / unit testing of the {@link RulesManagerService}.
 */
public class RulesManagerServiceTest {

    private RulesManagerService mRulesManagerService;

    private FakeExecutor mFakeExecutor;
    private PermissionHelper mMockPermissionHelper;
    private RulesManagerIntentHelper mMockIntentHelper;
    private PackageTracker mMockPackageTracker;
    private TimeZoneDistroInstaller mMockTimeZoneDistroInstaller;

    @Before
    public void setUp() {
        mFakeExecutor = new FakeExecutor();

        mMockPackageTracker = mock(PackageTracker.class);
        mMockPermissionHelper = mock(PermissionHelper.class);
        mMockIntentHelper = mock(RulesManagerIntentHelper.class);
        mMockTimeZoneDistroInstaller = mock(TimeZoneDistroInstaller.class);

        mRulesManagerService = new RulesManagerService(
                mMockPermissionHelper,
                mFakeExecutor,
                mMockIntentHelper,
                mMockPackageTracker,
                mMockTimeZoneDistroInstaller);
    }

    @Test(expected = SecurityException.class)
    public void getRulesState_noCallerPermission() throws Exception {
        configureCallerDoesNotHaveQueryPermission();
        mRulesManagerService.getRulesState();
    }

    @Test(expected = SecurityException.class)
    public void requestInstall_noCallerPermission() throws Exception {
        configureCallerDoesNotHaveUpdatePermission();
        mRulesManagerService.requestInstall(null, null, null);
    }

    @Test(expected = SecurityException.class)
    public void requestUninstall_noCallerPermission() throws Exception {
        configureCallerDoesNotHaveUpdatePermission();
        mRulesManagerService.requestUninstall(null, null);
    }

    @Test(expected = SecurityException.class)
    public void requestNothing_noCallerPermission() throws Exception {
        configureCallerDoesNotHaveUpdatePermission();
        mRulesManagerService.requestNothing(null, true);
    }

    @Test
    public void getRulesState_systemRulesError() throws Exception {
        configureDeviceCannotReadSystemRulesVersion();

        assertNull(mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_stagedInstall() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        DistroVersion stagedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                "2016c",
                3);
        configureStagedInstall(stagedDistroVersion);

        DistroVersion installedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion stagedDistroRulesVersion = new DistroRulesVersion(
                stagedDistroVersion.rulesVersion, stagedDistroVersion.revision);
        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, stagedDistroRulesVersion,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_nothingStaged() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        configureNoStagedOperation();

        DistroVersion installedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_uninstallStaged() throws Exception {
        configureCallerHasPermission();

        configureDeviceSystemRulesVersion("2016a");

        configureStagedUninstall();

        DistroVersion installedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNINSTALL, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_installedRulesError() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);

        configureStagedUninstall();
        configureDeviceCannotReadInstalledDistroVersion();

        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNINSTALL, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_UNKNOWN, null /* installedDistroRulesVersion */);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_stagedRulesError() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);

        configureDeviceCannotReadStagedDistroOperation();

        DistroVersion installedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                "2016b",
                4);
        configureInstalledDistroVersion(installedDistroVersion);

        DistroRulesVersion installedDistroRulesVersion = new DistroRulesVersion(
                installedDistroVersion.rulesVersion, installedDistroVersion.revision);
        RulesState expectedRuleState = new RulesState(
                "2016a", RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, installedDistroRulesVersion);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_noInstalledRules() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        configureDeviceSystemRulesVersion(systemRulesVersion);
        configureNoStagedOperation();
        configureInstalledDistroVersion(null);

        RulesState expectedRuleState = new RulesState(
                systemRulesVersion, RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_NONE, null /* installedDistroRulesVersion */);
        assertEquals(expectedRuleState, mRulesManagerService.getRulesState());
    }

    @Test
    public void getRulesState_operationInProgress() throws Exception {
        configureCallerHasPermission();

        String systemRulesVersion = "2016a";
        String installedRulesVersion = "2016b";
        int revision = 3;

        configureDeviceSystemRulesVersion(systemRulesVersion);

        DistroVersion installedDistroVersion = new DistroVersion(
                TzDataSetVersion.currentFormatMajorVersion(),
                TzDataSetVersion.currentFormatMinorVersion() - 1,
                installedRulesVersion,
                revision);
        configureInstalledDistroVersion(installedDistroVersion);

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        // Start an async operation so there is one in progress. The mFakeExecutor won't actually
        // execute it.
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);

        // Request the rules state while the async operation is "happening".
        RulesState actualRulesState = mRulesManagerService.getRulesState();
        DistroRulesVersion expectedInstalledDistroRulesVersion =
                new DistroRulesVersion(installedRulesVersion, revision);
        RulesState expectedRuleState = new RulesState(
                systemRulesVersion, RulesManagerService.DISTRO_FORMAT_VERSION_SUPPORTED,
                true /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, expectedInstalledDistroRulesVersion);
        assertEquals(expectedRuleState, actualRulesState);
    }

    @Test
    public void requestInstall_operationInProgress() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor1 =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        // First request should succeed.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor1, tokenBytes, callback));

        // Something async should be enqueued. Clear it but do not execute it so we can detect the
        // second request does nothing.
        mFakeExecutor.getAndResetLastCommand();

        // Second request should fail.
        ParcelFileDescriptor parcelFileDescriptor2 =
                createParcelFileDescriptor(createArbitraryBytes(1000));
        assertEquals(RulesManager.ERROR_OPERATION_IN_PROGRESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor2, tokenBytes, callback));

        assertClosed(parcelFileDescriptor2);

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestInstall_badToken() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        byte[] badTokenBytes = new byte[2];
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, badTokenBytes, callback);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        assertClosed(parcelFileDescriptor);

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestInstall_nullParcelFileDescriptor() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor = null;
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestInstall_nullCallback() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));
        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = null;

        try {
            mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        assertClosed(parcelFileDescriptor);

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestInstall_asyncSuccess() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Assert nothing has happened yet.
        callback.assertNoResultReceived();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageInstallExpectation(TimeZoneDistroInstaller.INSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        assertClosed(parcelFileDescriptor);

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled();
        verifyPackageTrackerCalled(token, true /* success */);
        verifyStagedOperationIntentSent();

        // Check the callback was called.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestInstall_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(
                        parcelFileDescriptor, null /* tokenBytes */, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageInstallExpectation(TimeZoneDistroInstaller.INSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        assertClosed(parcelFileDescriptor);

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled();
        verifyPackageTrackerCalled(null /* expectedToken */, true /* success */);
        verifyStagedOperationIntentSent();

        // Check the callback was received.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestInstall_asyncInstallFail() throws Exception {
        configureCallerHasPermission();

        ParcelFileDescriptor parcelFileDescriptor =
                createParcelFileDescriptor(createArbitraryBytes(1000));

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the install.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestInstall(parcelFileDescriptor, tokenBytes, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageInstallExpectation(TimeZoneDistroInstaller.INSTALL_FAIL_VALIDATION_ERROR);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        assertClosed(parcelFileDescriptor);

        // Verify the expected calls were made to other components.
        verifyStageInstallCalled();

        // Validation failure is treated like a successful check: repeating it won't improve things.
        boolean expectedSuccess = true;
        verifyPackageTrackerCalled(token, expectedSuccess);

        // Nothing should be staged, so no intents sent.
        verifyNoIntentsSent();

        // Check the callback was received.
        callback.assertResultReceived(Callback.ERROR_INSTALL_VALIDATION_ERROR);
    }

    @Test
    public void requestUninstall_operationInProgress() throws Exception {
        configureCallerHasPermission();

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = new StubbedCallback();

        // First request should succeed.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Something async should be enqueued. Clear it but do not execute it so we can detect the
        // second request does nothing.
        mFakeExecutor.getAndResetLastCommand();

        // Second request should fail.
        assertEquals(RulesManager.ERROR_OPERATION_IN_PROGRESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestUninstall_badToken() throws Exception {
        configureCallerHasPermission();

        byte[] badTokenBytes = new byte[2];
        ICallback callback = new StubbedCallback();

        try {
            mRulesManagerService.requestUninstall(badTokenBytes, callback);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestUninstall_nullCallback() throws Exception {
        configureCallerHasPermission();

        byte[] tokenBytes = createArbitraryTokenBytes();
        ICallback callback = null;

        try {
            mRulesManagerService.requestUninstall(tokenBytes, callback);
            fail();
        } catch (NullPointerException expected) {}

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestUninstall_asyncSuccess() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing has happened yet.
        callback.assertNoResultReceived();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageUninstallExpectation(TimeZoneDistroInstaller.UNINSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(token, true /* success */);
        verifyStagedOperationIntentSent();

        // Check the callback was called.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestUninstall_asyncNothingInstalled() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing has happened yet.
        callback.assertNoResultReceived();
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageUninstallExpectation(TimeZoneDistroInstaller.UNINSTALL_NOTHING_INSTALLED);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(token, true /* success */);
        verifyUnstagedOperationIntentSent();

        // Check the callback was called.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestUninstall_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(null /* tokenBytes */, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageUninstallExpectation(TimeZoneDistroInstaller.UNINSTALL_SUCCESS);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(null /* expectedToken */, true /* success */);
        verifyStagedOperationIntentSent();

        // Check the callback was received.
        callback.assertResultReceived(Callback.SUCCESS);
    }

    @Test
    public void requestUninstall_asyncUninstallFail() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        TestCallback callback = new TestCallback();

        // Request the uninstall.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(tokenBytes, callback));

        // Assert nothing has happened yet.
        verifyNoInstallerCallsMade();
        callback.assertNoResultReceived();
        verifyNoIntentsSent();

        // Set up the installer.
        configureStageUninstallExpectation(TimeZoneDistroInstaller.UNINSTALL_FAIL);

        // Simulate the async execution.
        mFakeExecutor.simulateAsyncExecutionOfLastCommand();

        // Verify the expected calls were made to other components.
        verifyStageUninstallCalled();
        verifyPackageTrackerCalled(token, false /* success */);
        verifyNoIntentsSent();

        // Check the callback was received.
        callback.assertResultReceived(Callback.ERROR_UNKNOWN_FAILURE);
    }

    @Test
    public void requestNothing_operationInProgressOk() throws Exception {
        configureCallerHasPermission();

        // Set up a parallel operation.
        assertEquals(RulesManager.SUCCESS,
                mRulesManagerService.requestUninstall(null, new StubbedCallback()));
        // Something async should be enqueued. Clear it but do not execute it to simulate it still
        // being in progress.
        mFakeExecutor.getAndResetLastCommand();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        // Make the call.
        mRulesManagerService.requestNothing(tokenBytes, true /* success */);

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();

        // Verify the expected calls were made to other components.
        verifyPackageTrackerCalled(token, true /* success */);
        verifyNoInstallerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestNothing_badToken() throws Exception {
        configureCallerHasPermission();

        byte[] badTokenBytes = new byte[2];

        try {
            mRulesManagerService.requestNothing(badTokenBytes, true /* success */);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Assert nothing async was enqueued.
        mFakeExecutor.assertNothingQueued();

        // Assert no other calls were made.
        verifyNoInstallerCallsMade();
        verifyNoPackageTrackerCallsMade();
        verifyNoIntentsSent();
    }

    @Test
    public void requestNothing() throws Exception {
        configureCallerHasPermission();

        CheckToken token = createArbitraryToken();
        byte[] tokenBytes = token.toByteArray();

        // Make the call.
        mRulesManagerService.requestNothing(tokenBytes, false /* success */);

        // Assert everything required was done.
        verifyNoInstallerCallsMade();
        verifyPackageTrackerCalled(token, false /* success */);
        verifyNoIntentsSent();
    }

    @Test
    public void requestNothing_nullTokenBytes() throws Exception {
        configureCallerHasPermission();

        // Make the call.
        mRulesManagerService.requestNothing(null /* tokenBytes */, true /* success */);

        // Assert everything required was done.
        verifyNoInstallerCallsMade();
        verifyPackageTrackerCalled(null /* token */, true /* success */);
        verifyNoIntentsSent();
    }

    @Test
    public void dump_noPermission() throws Exception {
        when(mMockPermissionHelper.checkDumpPermission(any(String.class), any(PrintWriter.class)))
                .thenReturn(false);

        doDumpCallAndCapture(mRulesManagerService, null);
        verifyZeroInteractions(mMockPackageTracker, mMockTimeZoneDistroInstaller);
    }

    @Test
    public void dump_emptyArgs() throws Exception {
        doSuccessfulDumpCall(mRulesManagerService, new String[0]);

        // Verify the package tracker was consulted.
        verify(mMockPackageTracker).dump(any(PrintWriter.class));
    }

    @Test
    public void dump_nullArgs() throws Exception {
        doSuccessfulDumpCall(mRulesManagerService, null);
        // Verify the package tracker was consulted.
        verify(mMockPackageTracker).dump(any(PrintWriter.class));
    }

    @Test
    public void dump_unknownArgs() throws Exception {
        String dumpedTextUnknownArgs = doSuccessfulDumpCall(
                mRulesManagerService, new String[] { "foo", "bar"});

        // Verify the package tracker was consulted.
        verify(mMockPackageTracker).dump(any(PrintWriter.class));

        String dumpedTextZeroArgs = doSuccessfulDumpCall(mRulesManagerService, null);
        assertEquals(dumpedTextZeroArgs, dumpedTextUnknownArgs);
    }

    @Test
    public void dump_formatState() throws Exception {
        // Just expect these to not throw exceptions, not return nothing, and not interact with the
        // package tracker.
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("p"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("s"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("c"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("i"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("o"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("t"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("a"));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("z" /* Unknown */));
        doSuccessfulDumpCall(mRulesManagerService, dumpFormatArgs("piscotz"));

        verifyZeroInteractions(mMockPackageTracker);
    }

    private static String[] dumpFormatArgs(String argsString) {
        return new String[] { "-format_state", argsString};
    }

    private String doSuccessfulDumpCall(RulesManagerService rulesManagerService, String[] args)
            throws Exception {
        when(mMockPermissionHelper.checkDumpPermission(any(String.class), any(PrintWriter.class)))
                .thenReturn(true);

        // Set up the mocks to return (arbitrary) information about the current device state.
        when(mMockTimeZoneDistroInstaller.getSystemRulesVersion()).thenReturn("2017a");
        when(mMockTimeZoneDistroInstaller.getInstalledDistroVersion()).thenReturn(
                new DistroVersion(2, 3, "2017b", 4));
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation()).thenReturn(
                StagedDistroOperation.install(new DistroVersion(5, 6, "2017c", 7)));

        // Do the dump call.
        String dumpedOutput = doDumpCallAndCapture(rulesManagerService, args);

        assertFalse(dumpedOutput.isEmpty());

        return dumpedOutput;
    }

    private static String doDumpCallAndCapture(
            RulesManagerService rulesManagerService, String[] args) throws IOException {
        File file = File.createTempFile("dump", null);
        try {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                FileDescriptor fd = fos.getFD();
                rulesManagerService.dump(fd, args);
            }
            return IoUtils.readFileAsString(file.getAbsolutePath());
        } finally {
            file.delete();
        }
    }

    private void verifyNoPackageTrackerCallsMade() {
        verifyNoMoreInteractions(mMockPackageTracker);
        reset(mMockPackageTracker);
    }

    private void verifyPackageTrackerCalled(
            CheckToken expectedCheckToken, boolean expectedSuccess) {
        verify(mMockPackageTracker).recordCheckResult(expectedCheckToken, expectedSuccess);
        reset(mMockPackageTracker);
    }

    private void verifyNoIntentsSent() {
        verifyNoMoreInteractions(mMockIntentHelper);
        reset(mMockIntentHelper);
    }

    private void verifyStagedOperationIntentSent() {
        verify(mMockIntentHelper).sendTimeZoneOperationStaged();
        reset(mMockIntentHelper);
    }

    private void verifyUnstagedOperationIntentSent() {
        verify(mMockIntentHelper).sendTimeZoneOperationUnstaged();
        reset(mMockIntentHelper);
    }

    private void configureCallerHasPermission() throws Exception {
        doNothing()
                .when(mMockPermissionHelper)
                .enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
    }

    private void configureCallerDoesNotHaveUpdatePermission() {
        doThrow(new SecurityException("Simulated permission failure"))
                .when(mMockPermissionHelper)
                .enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
    }

    private void configureCallerDoesNotHaveQueryPermission() {
        doThrow(new SecurityException("Simulated permission failure"))
                .when(mMockPermissionHelper)
                .enforceCallerHasPermission(REQUIRED_QUERY_PERMISSION);
    }

    private void configureStageInstallExpectation(int resultCode)
            throws Exception {
        when(mMockTimeZoneDistroInstaller.stageInstallWithErrorCode(any(TimeZoneDistro.class)))
                .thenReturn(resultCode);
    }

    private void configureStageUninstallExpectation(int resultCode) throws Exception {
        doReturn(resultCode).when(mMockTimeZoneDistroInstaller).stageUninstall();
    }

    private void verifyStageInstallCalled() throws Exception {
        verify(mMockTimeZoneDistroInstaller).stageInstallWithErrorCode(any(TimeZoneDistro.class));
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private void verifyStageUninstallCalled() throws Exception {
        verify(mMockTimeZoneDistroInstaller).stageUninstall();
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private void verifyNoInstallerCallsMade() {
        verifyNoMoreInteractions(mMockTimeZoneDistroInstaller);
        reset(mMockTimeZoneDistroInstaller);
    }

    private static byte[] createArbitraryBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private byte[] createArbitraryTokenBytes() {
        return createArbitraryToken().toByteArray();
    }

    private CheckToken createArbitraryToken() {
        return new CheckToken(1, new PackageVersions(1, 1));
    }

    private void configureDeviceSystemRulesVersion(String systemRulesVersion) throws Exception {
        when(mMockTimeZoneDistroInstaller.getSystemRulesVersion()).thenReturn(systemRulesVersion);
    }

    private void configureInstalledDistroVersion(@Nullable DistroVersion installedDistroVersion)
            throws Exception {
        when(mMockTimeZoneDistroInstaller.getInstalledDistroVersion())
                .thenReturn(installedDistroVersion);
    }

    private void configureStagedInstall(DistroVersion stagedDistroVersion) throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenReturn(StagedDistroOperation.install(stagedDistroVersion));
    }

    private void configureStagedUninstall() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenReturn(StagedDistroOperation.uninstall());
    }

    private void configureNoStagedOperation() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation()).thenReturn(null);
    }

    private void configureDeviceCannotReadStagedDistroOperation() throws Exception {
        when(mMockTimeZoneDistroInstaller.getStagedDistroOperation())
                .thenThrow(new IOException("Simulated failure"));
    }

    private void configureDeviceCannotReadSystemRulesVersion() throws Exception {
        when(mMockTimeZoneDistroInstaller.getSystemRulesVersion())
                .thenThrow(new IOException("Simulated failure"));
    }

    private void configureDeviceCannotReadInstalledDistroVersion() throws Exception {
        when(mMockTimeZoneDistroInstaller.getInstalledDistroVersion())
                .thenThrow(new IOException("Simulated failure"));
    }

    private static void assertClosed(ParcelFileDescriptor parcelFileDescriptor) {
        assertFalse(parcelFileDescriptor.getFileDescriptor().valid());
    }

    private static class FakeExecutor implements Executor {

        private Runnable mLastCommand;

        @Override
        public void execute(Runnable command) {
            assertNull(mLastCommand);
            assertNotNull(command);
            mLastCommand = command;
        }

        public Runnable getAndResetLastCommand() {
            assertNotNull(mLastCommand);
            Runnable toReturn = mLastCommand;
            mLastCommand = null;
            return toReturn;
        }

        public void simulateAsyncExecutionOfLastCommand() {
            Runnable toRun = getAndResetLastCommand();
            toRun.run();
        }

        public void assertNothingQueued() {
            assertNull(mLastCommand);
        }
    }

    private static class TestCallback extends ICallback.Stub {

        private boolean mOnFinishedCalled;
        private int mLastError;

        @Override
        public void onFinished(int error) {
            assertFalse(mOnFinishedCalled);
            mOnFinishedCalled = true;
            mLastError = error;
        }

        public void assertResultReceived(int expectedResult) {
            assertTrue(mOnFinishedCalled);
            assertEquals(expectedResult, mLastError);
        }

        public void assertNoResultReceived() {
            assertFalse(mOnFinishedCalled);
        }
    }

    private static class StubbedCallback extends ICallback.Stub {
        @Override
        public void onFinished(int error) {
            fail("Unexpected call");
        }
    }

    private static ParcelFileDescriptor createParcelFileDescriptor(byte[] bytes)
            throws IOException {
        File file = File.createTempFile("pfd", null);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }
        ParcelFileDescriptor pfd =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        // This should now be safe to delete. The ParcelFileDescriptor has an open fd.
        file.delete();
        return pfd;
    }
}
