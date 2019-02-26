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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.app.timezone.RulesUpdaterContract;
import android.content.Context;
import android.content.Intent;
import android.provider.TimeZoneRulesDataContract;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * White box interaction / unit testing of the {@link PackageTracker}.
 */
@SmallTest
public class PackageTrackerTest {
    private static final String UPDATE_APP_PACKAGE_NAME = "updateAppPackageName";
    private static final String DATA_APP_PACKAGE_NAME = "dataAppPackageName";
    private static final PackageVersions INITIAL_APP_PACKAGE_VERSIONS =
            new PackageVersions(2 /* updateAppVersion */, 2 /* dataAppVersion */);

    private ConfigHelper mMockConfigHelper;
    private PackageManagerHelper mMockPackageManagerHelper;

    private FakeClock mFakeClock;
    private FakeIntentHelper mFakeIntentHelper;
    private PackageStatusStorage mPackageStatusStorage;
    private PackageTracker mPackageTracker;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();

        mFakeClock = new FakeClock();

        // Read-only interfaces so are easy to mock.
        mMockConfigHelper = mock(ConfigHelper.class);
        mMockPackageManagerHelper = mock(PackageManagerHelper.class);

        // Using the instrumentation context means the database is created in a test app-specific
        // directory. We can use the real thing for this test.
        mPackageStatusStorage = new PackageStatusStorage(context.getFilesDir());

        // For other interactions with the Android framework we create a fake object.
        mFakeIntentHelper = new FakeIntentHelper();

        // Create the PackageTracker to use in tests.
        mPackageTracker = new PackageTracker(
                mFakeClock,
                mMockConfigHelper,
                mMockPackageManagerHelper,
                mPackageStatusStorage,
                mFakeIntentHelper);
    }

    @After
    public void tearDown() throws Exception {
        if (mPackageStatusStorage != null) {
            mPackageStatusStorage.deleteFileForTests();
        }
    }

    @Test
    public void trackingDisabled_intentHelperNotUsed() {
        // Set up device configuration.
        configureTrackingDisabled();

        // Initialize the tracker.
        assertFalse(mPackageTracker.start());

        // Check the IntentHelper was not initialized.
        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingDisabled_triggerUpdateIfNeededNotAllowed() {
        // Set up device configuration.
        configureTrackingDisabled();

        // Initialize the tracker.
        assertFalse(mPackageTracker.start());

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        try {
            // This call should also not be allowed and will throw an exception if tracking is
            // disabled.
            mPackageTracker.triggerUpdateIfNeeded(true);
            fail();
        } catch (IllegalStateException expected) {}

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingDisabled_unsolicitedResultsIgnored_withoutToken() {
        // Set up device configuration.
        configureTrackingDisabled();

        // Initialize the tracker.
        assertFalse(mPackageTracker.start());

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Receiving a check result when tracking is disabled should cause the storage to be
        // reset.
        mPackageTracker.recordCheckResult(null /* checkToken */, true /* success */);

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Assert the storage was reset.
        checkPackageStorageStatusIsInitialOrReset();
    }

    @Test
    public void trackingDisabled_unsolicitedResultsIgnored_withToken() {
        // Set up device configuration.
        configureTrackingDisabled();

        // Set the storage into an arbitrary state so we can detect a reset.
        mPackageStatusStorage.generateCheckToken(INITIAL_APP_PACKAGE_VERSIONS);

        // Initialize the tracker.
        assertFalse(mPackageTracker.start());

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Receiving a check result when tracking is disabled should cause the storage to be reset.
        mPackageTracker.recordCheckResult(createArbitraryCheckToken(), true /* success */);

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Assert the storage was reset.
        checkPackageStorageStatusIsInitialOrReset();
    }

    @Test
    public void trackingEnabled_updateAppConfigMissing() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureUpdateAppPackageNameMissing();
        configureDataAppPackageOk(DATA_APP_PACKAGE_NAME);

        try {
            // Initialize the tracker.
            mPackageTracker.start();
            fail();
        } catch (RuntimeException expected) {}

        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingEnabled_updateAppNotPrivileged() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureUpdateAppPackageNotPrivileged(UPDATE_APP_PACKAGE_NAME);
        configureDataAppPackageOk(DATA_APP_PACKAGE_NAME);

        try {
            // Initialize the tracker.
            mPackageTracker.start();
            fail();
        } catch (RuntimeException expected) {}

        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingEnabled_dataAppConfigMissing() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureUpdateAppPackageOk(UPDATE_APP_PACKAGE_NAME);
        configureDataAppPackageNameMissing();

        try {
            // Initialize the tracker.
            mPackageTracker.start();
            fail();
        } catch (RuntimeException expected) {}

        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingEnabled_dataAppNotPrivileged() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureUpdateAppPackageOk(UPDATE_APP_PACKAGE_NAME);
        configureDataAppPackageNotPrivileged(DATA_APP_PACKAGE_NAME);

        try {
            // Initialize the tracker.
            mPackageTracker.start();
            fail();
        } catch (RuntimeException expected) {}

        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
     }

    @Test
    public void trackingEnabled_storageInitializationFails() throws Exception {
        // Create a PackageStateStorage that will fail to initialize.
        PackageStatusStorage packageStatusStorage =
                new PackageStatusStorage(new File("/system/does/not/exist"));

        // Create a new PackageTracker to use the bad storage.
        mPackageTracker = new PackageTracker(
                mFakeClock,
                mMockConfigHelper,
                mMockPackageManagerHelper,
                packageStatusStorage,
                mFakeIntentHelper);

        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertFalse(mPackageTracker.start());

        // Check the IntentHelper was not initialized.
        mFakeIntentHelper.assertNotInitialized();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
    }

    @Test
    public void trackingEnabled_packageUpdate_badUpdateAppManifestEntry() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Configure a bad manifest for the update app. Should effectively turn off tracking.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        configureUpdateAppManifestBad(UPDATE_APP_PACKAGE_NAME);
        configureDataAppManifestOk(DATA_APP_PACKAGE_NAME);
        configureUpdateAppPackageVersion(
                UPDATE_APP_PACKAGE_NAME, packageVersions.mUpdateAppVersion);
        configureDataAppPackageVersion(DATA_APP_PACKAGE_NAME, packageVersions.mDataAppVersion);
        // Simulate a tracked package being updated.
        mFakeIntentHelper.simulatePackageUpdatedEvent();

        // Assert the PackageTracker did not attempt to trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Assert the storage was not touched.
        checkPackageStorageStatusIsInitialOrReset();
    }

    @Test
    public void trackingEnabled_packageUpdate_badDataAppManifestEntry() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Configure a bad manifest for the data app. Should effectively turn off tracking.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        configureUpdateAppManifestOk(UPDATE_APP_PACKAGE_NAME);
        configureDataAppManifestBad(DATA_APP_PACKAGE_NAME);
        configureUpdateAppPackageVersion(
                UPDATE_APP_PACKAGE_NAME, packageVersions.mUpdateAppVersion);
        configureDataAppPackageVersion(DATA_APP_PACKAGE_NAME, packageVersions.mDataAppVersion);
        mFakeIntentHelper.simulatePackageUpdatedEvent();

        // Assert the PackageTracker did not attempt to trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Assert the storage was not touched.
        checkPackageStorageStatusIsInitialOrReset();
    }

    @Test
    public void trackingEnabled_packageUpdate_responseWithToken_success() throws Exception {
        trackingEnabled_packageUpdate_responseWithToken(true);
    }

    @Test
    public void trackingEnabled_packageUpdate_responseWithToken_failed() throws Exception {
        trackingEnabled_packageUpdate_responseWithToken(false);
    }

    private void trackingEnabled_packageUpdate_responseWithToken(boolean success) throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate a tracked package being updated.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Get the token that was passed to the intent helper, and pass it back.
        CheckToken token = mFakeIntentHelper.captureAndResetLastToken();
        mPackageTracker.recordCheckResult(token, success);

        // Check storage and reliability triggering state.
        if (success) {
            checkUpdateCheckSuccessful(packageVersions);
        } else {
            checkUpdateCheckFailed(packageVersions);
        }
    }

    @Test
    public void trackingEnabled_packageUpdate_responseWithoutTokenCausesStorageReset_success()
            throws Exception {
        trackingEnabled_packageUpdate_responseWithoutTokenCausesStorageReset(true);
    }

    @Test
    public void trackingEnabled_packageUpdate_responseWithoutTokenCausesStorageReset_failed()
            throws Exception {
        trackingEnabled_packageUpdate_responseWithoutTokenCausesStorageReset(false);
    }

    private void trackingEnabled_packageUpdate_responseWithoutTokenCausesStorageReset(
            boolean success) throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Set up installed app versions / manifests.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Ignore the token that was given to the intent helper, just pass null.
        mPackageTracker.recordCheckResult(null /* checkToken */, success);

        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Assert the storage was reset.
        checkPackageStorageStatusIsInitialOrReset();
    }

    /**
     * Two package updates triggered for the same package versions. The second is triggered while
     * the first is still happening.
     */
    @Test
    public void trackingEnabled_packageUpdate_twoChecksNoPackageChange_secondWhileFirstInProgress()
            throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Get the first token.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions, token1.mPackageVersions);

        // Now attempt to generate another check while the first is in progress and without having
        // updated the package versions. The PackageTracker should trigger again for safety.
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions, token2.mPackageVersions);
        assertEquals(token1.mPackageVersions, token2.mPackageVersions);
        assertTrue(token1.mOptimisticLockId != token2.mOptimisticLockId);
    }

    /**
     * Two package updates triggered for the same package versions. The second happens after
     * the first has succeeded.
     */
    @Test
    public void trackingEnabled_packageUpdate_twoChecksNoPackageChange_sequential()
            throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Get the token.
        CheckToken token = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions, token.mPackageVersions);

        // Simulate a successful check.
        mPackageTracker.recordCheckResult(token, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions);

        // Now attempt to generate another check, but without having updated the package. The
        // PackageTracker should be smart enough to recognize there's nothing to do here.
        simulatePackageInstallation(packageVersions);

        // Assert the PackageTracker did not attempt to trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions);
    }

    /**
     * Two package updates triggered for the same package versions. The second is triggered after
     * the first has failed.
     */
    @Test
    public void trackingEnabled_packageUpdate_afterFailure() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Get the first token.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions, token1.mPackageVersions);

        // Simulate an *unsuccessful* check.
        mPackageTracker.recordCheckResult(token1, false /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckFailed(packageVersions);

        // Now generate another check, but without having updated the package. The
        // PackageTracker should recognize the last check failed and trigger again.
        simulatePackageInstallation(packageVersions);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions);

        // Get the second token.
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();

        // Assert some things about the tokens.
        assertEquals(packageVersions, token2.mPackageVersions);
        assertTrue(token1.mOptimisticLockId != token2.mOptimisticLockId);

        // For completeness, now simulate this check was successful.
        mPackageTracker.recordCheckResult(token2, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions);
    }

    /**
     * Two package updates triggered for different package versions. The second is triggered while
     * the first is still happening.
     */
    @Test
    public void trackingEnabled_packageUpdate_twoChecksWithPackageChange_firstCheckInProcess()
            throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions1 =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions1);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions1);

        // Get the first token.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions1, token1.mPackageVersions);

        // Simulate a tracked package being updated a second time (before the response for the
        // first has been received).
        PackageVersions packageVersions2 =
                new PackageVersions(3 /* updateAppPackageVersion */, 4 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions2);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions2);

        // Get the second token.
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions2, token2.mPackageVersions);

        // token1 should be invalid because the token2 was generated.
        mPackageTracker.recordCheckResult(token1, true /* success */);

        // Reliability triggering should still be enabled.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Check the expected storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_STARTED, packageVersions2);

        // token2 should still be accepted.
        mPackageTracker.recordCheckResult(token2, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions2);
    }

    /**
     * Two package updates triggered for different package versions. The second is triggered after
     * the first has completed successfully.
     */
    @Test
    public void trackingEnabled_packageUpdate_twoChecksWithPackageChange_sequential()
            throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions1 =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions1);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions1);

        // Get the first token.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions1, token1.mPackageVersions);

        // token1 should be accepted.
        mPackageTracker.recordCheckResult(token1, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions1);

        // Simulate a tracked package being updated a second time.
        PackageVersions packageVersions2 =
                new PackageVersions(3 /* updateAppPackageVersion */, 4 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions2);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions2);

        // Get the second token.
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions2, token2.mPackageVersions);

        // token2 should still be accepted.
        mPackageTracker.recordCheckResult(token2, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions2);
    }

    /**
     * Replaying the same token twice.
     */
    @Test
    public void trackingEnabled_packageUpdate_sameTokenReplayFails() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        configureValidApplications();

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate package installation.
        PackageVersions packageVersions1 =
                new PackageVersions(2 /* updateAppPackageVersion */, 3 /* dataAppPackageVersion */);
        simulatePackageInstallation(packageVersions1);

        // Confirm an update was triggered.
        checkUpdateCheckTriggered(packageVersions1);

        // Get the first token.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions1, token1.mPackageVersions);

        // token1 should be accepted.
        mPackageTracker.recordCheckResult(token1, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions1);

        // Apply token1 again.
        mPackageTracker.recordCheckResult(token1, true /* success */);

        // Check the expected storage state. No real way to tell if it has been updated, but
        // we can check the final state is still what it should be.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions1);

        // Under the covers we expect it to fail to update because the storage should recognize that
        // the token is no longer valid.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Peek inside the package tracker to make sure it is tracking failure counts properly.
        assertEquals(1, mPackageTracker.getCheckFailureCountForTests());
    }

    @Test
    public void trackingEnabled_reliabilityTrigger_firstTime_initialStorage() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        PackageVersions packageVersions = configureValidApplications();

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatusIsInitialOrReset();

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(packageVersions);

        // Confirm the token was correct.
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();
        assertEquals(packageVersions, token1.mPackageVersions);

        // token1 should be accepted.
        mPackageTracker.recordCheckResult(token1, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions);
    }

    @Test
    public void trackingEnabled_reliabilityTrigger_afterRebootNoTriggerNeeded() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();
        PackageVersions packageVersions = configureValidApplications();

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did not attempt to trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(packageVersions);
    }

    /**
     * Simulates the device starting where the storage records do not match the installed app
     * versions. The reliability trigger should cause the package tracker to perform a check.
     */
    @Test
    public void trackingEnabled_reliabilityTrigger_afterRebootTriggerNeededBecausePreviousFailed()
            throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();
        configureReliabilityConfigSettingsOk();

        PackageVersions oldPackageVersions = new PackageVersions(1, 1);
        PackageVersions currentPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(currentPackageVersions);

        // Simulate the update check completing successfully.
        CheckToken checkToken = mFakeIntentHelper.captureAndResetLastToken();
        mPackageTracker.recordCheckResult(checkToken, true /* success */);

        // Check storage and reliability triggering state.
        checkUpdateCheckSuccessful(currentPackageVersions);
    }

    /**
     * Simulates persistent failures of the reliability check. It should stop after the configured
     * number of checks.
     */
    @Test
    public void trackingEnabled_reliabilityTrigger_repeatedFailures() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();

        int retriesAllowed = 3;
        int checkDelayMillis = 5 * 60 * 1000;
        configureReliabilityConfigSettings(retriesAllowed, checkDelayMillis);

        PackageVersions oldPackageVersions = new PackageVersions(1, 1);
        PackageVersions currentPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        for (int i = 0; i < retriesAllowed + 1; i++) {
            // Simulate a reliability trigger.
            mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

            // Assert the PackageTracker did trigger an update.
            checkUpdateCheckTriggered(currentPackageVersions);

            // Check the PackageTracker failure count before calling recordCheckResult.
            assertEquals(i, mPackageTracker.getCheckFailureCountForTests());

            // Simulate a check failure.
            CheckToken checkToken = mFakeIntentHelper.captureAndResetLastToken();
            mPackageTracker.recordCheckResult(checkToken, false /* success */);

            // Peek inside the package tracker to make sure it is tracking failure counts properly.
            assertEquals(i + 1, mPackageTracker.getCheckFailureCountForTests());

            // Confirm nothing has changed.
            mFakeIntentHelper.assertUpdateNotTriggered();
            checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE,
                    currentPackageVersions);

            // Check reliability triggering is in the correct state.
            if (i <= retriesAllowed) {
                mFakeIntentHelper.assertReliabilityTriggerScheduled();
            } else {
                mFakeIntentHelper.assertReliabilityTriggerNotScheduled();
            }
        }
    }

    @Test
    public void trackingEnabled_reliabilityTrigger_failureCountIsReset() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();

        int retriesAllowed = 3;
        int checkDelayMillis = 5 * 60 * 1000;
        configureReliabilityConfigSettings(retriesAllowed, checkDelayMillis);

        PackageVersions oldPackageVersions = new PackageVersions(1, 1);
        PackageVersions currentPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Fail (retries - 1) times.
        for (int i = 0; i < retriesAllowed - 1; i++) {
            // Simulate a reliability trigger.
            mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

            // Assert the PackageTracker did trigger an update.
            checkUpdateCheckTriggered(currentPackageVersions);

            // Check the PackageTracker failure count before calling recordCheckResult.
            assertEquals(i, mPackageTracker.getCheckFailureCountForTests());

            // Simulate a check failure.
            CheckToken checkToken = mFakeIntentHelper.captureAndResetLastToken();
            mPackageTracker.recordCheckResult(checkToken, false /* success */);

            // Peek inside the package tracker to make sure it is tracking failure counts properly.
            assertEquals(i + 1, mPackageTracker.getCheckFailureCountForTests());

            // Confirm nothing has changed.
            mFakeIntentHelper.assertUpdateNotTriggered();
            checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE,
                    currentPackageVersions);

            // Check reliability triggering is still enabled.
            mFakeIntentHelper.assertReliabilityTriggerScheduled();
        }

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(currentPackageVersions);

        // Check the PackageTracker failure count before calling recordCheckResult.
        assertEquals(retriesAllowed - 1, mPackageTracker.getCheckFailureCountForTests());

        // On the last possible try, succeed.
        CheckToken checkToken = mFakeIntentHelper.captureAndResetLastToken();
        mPackageTracker.recordCheckResult(checkToken, true /* success */);

        checkUpdateCheckSuccessful(currentPackageVersions);
    }

    /**
     * Simulates reliability triggers happening too close together. Package tracker should ignore
     * the ones it doesn't need.
     */
    @Test
    public void trackingEnabled_reliabilityTrigger_tooSoon() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();

        int retriesAllowed = 5;
        int checkDelayMillis = 5 * 60 * 1000;
        configureReliabilityConfigSettings(retriesAllowed, checkDelayMillis);

        PackageVersions oldPackageVersions = new PackageVersions(1, 1);
        PackageVersions currentPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, oldPackageVersions);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(currentPackageVersions);
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();

        // Increment the clock, but not enough.
        mFakeClock.incrementClock(checkDelayMillis - 1);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did not trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();
        checkPackageStorageStatus(PackageStatus.CHECK_STARTED, currentPackageVersions);
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Increment the clock slightly more. Should now consider the response overdue.
        mFakeClock.incrementClock(2);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Triggering should have happened.
        checkUpdateCheckTriggered(currentPackageVersions);
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();

        // Check a new token was generated.
        assertFalse(token1.equals(token2));
    }

    /**
     * Tests what happens when a package update doesn't complete and a reliability trigger cleans
     * up for it.
     */
    @Test
    public void trackingEnabled_reliabilityTrigger_afterPackageUpdateDidNotComplete()
            throws Exception {

        // Set up device configuration.
        configureTrackingEnabled();

        int retriesAllowed = 5;
        int checkDelayMillis = 5 * 60 * 1000;
        configureReliabilityConfigSettings(retriesAllowed, checkDelayMillis);

        PackageVersions currentPackageVersions = new PackageVersions(1, 1);
        PackageVersions newPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_SUCCESS, currentPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Simulate a reliability trigger.
        simulatePackageInstallation(newPackageVersions);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(newPackageVersions);
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();

        // Increment the clock, but not enough.
        mFakeClock.incrementClock(checkDelayMillis + 1);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker triggered an update.
        checkUpdateCheckTriggered(newPackageVersions);
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();

        // Check a new token was generated.
        assertFalse(token1.equals(token2));

        // Simulate the reliability check completing.
        mPackageTracker.recordCheckResult(token2, true /* success */);

        // Check everything is now as it should be.
        checkUpdateCheckSuccessful(newPackageVersions);
    }

    /**
     * Simulates a reliability trigger happening too soon after a package update trigger occurred.
     */
    @Test
    public void trackingEnabled_reliabilityTriggerAfterUpdate_tooSoon() throws Exception {
        // Set up device configuration.
        configureTrackingEnabled();

        int retriesAllowed = 5;
        int checkDelayMillis = 5 * 60 * 1000;
        configureReliabilityConfigSettings(retriesAllowed, checkDelayMillis);

        PackageVersions currentPackageVersions = new PackageVersions(1, 1);
        PackageVersions newPackageVersions = new PackageVersions(2, 2);

        // Simulate there being a newer version installed than the one recorded in storage.
        configureValidApplications(currentPackageVersions);

        // Force the storage into a state we want.
        mPackageStatusStorage.forceCheckStateForTests(
                PackageStatus.CHECK_COMPLETED_SUCCESS, currentPackageVersions);

        // Initialize the package tracker.
        assertTrue(mPackageTracker.start());

        // Check the intent helper is properly configured.
        checkIntentHelperInitializedAndReliabilityTrackingEnabled();

        // Check the initial storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, currentPackageVersions);

        // Simulate a package update trigger.
        simulatePackageInstallation(newPackageVersions);

        // Assert the PackageTracker did trigger an update.
        checkUpdateCheckTriggered(newPackageVersions);
        CheckToken token1 = mFakeIntentHelper.captureAndResetLastToken();

        // Increment the clock, but not enough.
        mFakeClock.incrementClock(checkDelayMillis - 1);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Assert the PackageTracker did not trigger an update.
        mFakeIntentHelper.assertUpdateNotTriggered();
        checkPackageStorageStatus(PackageStatus.CHECK_STARTED, newPackageVersions);
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Increment the clock slightly more. Should now consider the response overdue.
        mFakeClock.incrementClock(2);

        // Simulate a reliability trigger.
        mPackageTracker.triggerUpdateIfNeeded(false /* packageChanged */);

        // Triggering should have happened.
        checkUpdateCheckTriggered(newPackageVersions);
        CheckToken token2 = mFakeIntentHelper.captureAndResetLastToken();

        // Check a new token was generated.
        assertFalse(token1.equals(token2));
    }

    @Test
    public void dump() {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        mPackageTracker.dump(printWriter);

        assertFalse(stringWriter.toString().isEmpty());
    }

    private void simulatePackageInstallation(PackageVersions packageVersions) throws Exception {
        configureApplicationsValidManifests(packageVersions);

        // Simulate a tracked package being updated.
        mFakeIntentHelper.simulatePackageUpdatedEvent();
    }

    /**
     * Checks an update check was triggered, reliability triggering is therefore enabled and the
     * storage state reflects that there is a check in progress.
     */
    private void checkUpdateCheckTriggered(PackageVersions packageVersions) {
        // Assert the PackageTracker attempted to trigger an update.
        mFakeIntentHelper.assertUpdateTriggered();

        // If an update check was triggered reliability triggering should always be enabled to
        // ensure that it can be completed if it fails.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Check the expected storage state.
        checkPackageStorageStatus(PackageStatus.CHECK_STARTED, packageVersions);
    }

    private void checkUpdateCheckFailed(PackageVersions packageVersions) {
        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();

        // Assert the storage was updated.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, packageVersions);
    }

    private void checkUpdateCheckSuccessful(PackageVersions packageVersions) {
        // Check reliability triggering state.
        mFakeIntentHelper.assertReliabilityTriggerNotScheduled();

        // Assert the storage was updated.
        checkPackageStorageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);

        // Peek inside the package tracker to make sure it is tracking failure counts properly.
        assertEquals(0, mPackageTracker.getCheckFailureCountForTests());
    }

    private PackageVersions configureValidApplications() throws Exception {
        configureValidApplications(INITIAL_APP_PACKAGE_VERSIONS);
        return INITIAL_APP_PACKAGE_VERSIONS;
    }

    private void configureValidApplications(PackageVersions versions) throws Exception {
        configureUpdateAppPackageOk(UPDATE_APP_PACKAGE_NAME);
        configureDataAppPackageOk(DATA_APP_PACKAGE_NAME);
        configureApplicationsValidManifests(versions);
    }

    private void configureApplicationsValidManifests(PackageVersions versions) throws Exception {
        configureUpdateAppManifestOk(UPDATE_APP_PACKAGE_NAME);
        configureDataAppManifestOk(DATA_APP_PACKAGE_NAME);
        configureUpdateAppPackageVersion(UPDATE_APP_PACKAGE_NAME, versions.mUpdateAppVersion);
        configureDataAppPackageVersion(DATA_APP_PACKAGE_NAME, versions.mDataAppVersion);
    }

    private void configureUpdateAppPackageVersion(String updateAppPackageName,
            long updataAppPackageVersion) throws Exception {
        when(mMockPackageManagerHelper.getInstalledPackageVersion(updateAppPackageName))
                .thenReturn(updataAppPackageVersion);
    }

    private void configureDataAppPackageVersion(String dataAppPackageName,
            long dataAppPackageVersion) throws Exception {
        when(mMockPackageManagerHelper.getInstalledPackageVersion(dataAppPackageName))
                .thenReturn(dataAppPackageVersion);
    }

    private void configureUpdateAppManifestOk(String updateAppPackageName) throws Exception {
        Intent expectedIntent = RulesUpdaterContract.createUpdaterIntent(updateAppPackageName);
        when(mMockPackageManagerHelper.receiverRegistered(
                filterEquals(expectedIntent),
                eq(RulesUpdaterContract.TRIGGER_TIME_ZONE_RULES_CHECK_PERMISSION)))
                .thenReturn(true);
        when(mMockPackageManagerHelper.usesPermission(
                updateAppPackageName, RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION))
                .thenReturn(true);
    }

    private void configureUpdateAppManifestBad(String updateAppPackageName) throws Exception {
        Intent expectedIntent = RulesUpdaterContract.createUpdaterIntent(updateAppPackageName);
        when(mMockPackageManagerHelper.receiverRegistered(
                filterEquals(expectedIntent),
                eq(RulesUpdaterContract.TRIGGER_TIME_ZONE_RULES_CHECK_PERMISSION)))
                .thenReturn(false);
        // Has permission, but that shouldn't matter if the check above is false.
        when(mMockPackageManagerHelper.usesPermission(
                updateAppPackageName, RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION))
                .thenReturn(true);
    }

    private void configureDataAppManifestOk(String dataAppPackageName) throws Exception {
        when(mMockPackageManagerHelper.contentProviderRegistered(
                TimeZoneRulesDataContract.AUTHORITY, dataAppPackageName))
                .thenReturn(true);
    }

    private void configureDataAppManifestBad(String dataAppPackageName) throws Exception {
        // Simulate the data app not exposing the content provider we require.
        when(mMockPackageManagerHelper.contentProviderRegistered(
                TimeZoneRulesDataContract.AUTHORITY, dataAppPackageName))
                .thenReturn(false);
    }

    private void configureTrackingEnabled() {
        when(mMockConfigHelper.isTrackingEnabled()).thenReturn(true);
    }

    private void configureTrackingDisabled() {
        when(mMockConfigHelper.isTrackingEnabled()).thenReturn(false);
    }

    private void configureReliabilityConfigSettings(int retriesAllowed, int checkDelayMillis) {
        when(mMockConfigHelper.getFailedCheckRetryCount()).thenReturn(retriesAllowed);
        when(mMockConfigHelper.getCheckTimeAllowedMillis()).thenReturn(checkDelayMillis);
    }

    private void configureReliabilityConfigSettingsOk() {
        configureReliabilityConfigSettings(5, 5 * 60 * 1000);
    }

    private void configureUpdateAppPackageOk(String updateAppPackageName) throws Exception {
        when(mMockConfigHelper.getUpdateAppPackageName()).thenReturn(updateAppPackageName);
        when(mMockPackageManagerHelper.isPrivilegedApp(updateAppPackageName)).thenReturn(true);
    }

    private void configureUpdateAppPackageNotPrivileged(String updateAppPackageName)
            throws Exception {
        when(mMockConfigHelper.getUpdateAppPackageName()).thenReturn(updateAppPackageName);
        when(mMockPackageManagerHelper.isPrivilegedApp(updateAppPackageName)).thenReturn(false);
    }

    private void configureUpdateAppPackageNameMissing() {
        when(mMockConfigHelper.getUpdateAppPackageName()).thenReturn(null);
    }

    private void configureDataAppPackageOk(String dataAppPackageName) throws Exception {
        when(mMockConfigHelper.getDataAppPackageName()).thenReturn(dataAppPackageName);
        when(mMockPackageManagerHelper.isPrivilegedApp(dataAppPackageName)).thenReturn(true);
    }

    private void configureDataAppPackageNotPrivileged(String dataAppPackageName)
            throws Exception {
        when(mMockConfigHelper.getUpdateAppPackageName()).thenReturn(dataAppPackageName);
        when(mMockPackageManagerHelper.isPrivilegedApp(dataAppPackageName)).thenReturn(false);
    }

    private void configureDataAppPackageNameMissing() {
        when(mMockConfigHelper.getDataAppPackageName()).thenThrow(new RuntimeException());
    }

    private void checkIntentHelperInitializedAndReliabilityTrackingEnabled() {
        // Verify that calling start initialized the IntentHelper as well.
        mFakeIntentHelper.assertInitialized(UPDATE_APP_PACKAGE_NAME, DATA_APP_PACKAGE_NAME);

        // Assert that reliability tracking is always enabled after initialization.
        mFakeIntentHelper.assertReliabilityTriggerScheduled();
    }

    private void checkPackageStorageStatus(
            int expectedCheckStatus, PackageVersions expectedPackageVersions) {
        PackageStatus packageStatus = mPackageStatusStorage.getPackageStatus();
        assertEquals(expectedCheckStatus, packageStatus.mCheckStatus);
        assertEquals(expectedPackageVersions, packageStatus.mVersions);
    }

    private void checkPackageStorageStatusIsInitialOrReset() {
        assertNull(mPackageStatusStorage.getPackageStatus());
    }

    private static CheckToken createArbitraryCheckToken() {
        return new CheckToken(1, INITIAL_APP_PACKAGE_VERSIONS);
    }

    /**
     * A fake IntentHelper implementation for use in tests.
     */
    private static class FakeIntentHelper implements PackageTrackerIntentHelper {

        private PackageTracker mPackageTracker;
        private String mUpdateAppPackageName;
        private String mDataAppPackageName;

        private CheckToken mLastToken;

        private boolean mReliabilityTriggerScheduled;

        @Override
        public void initialize(String updateAppPackageName, String dataAppPackageName,
                PackageTracker packageTracker) {
            assertNotNull(updateAppPackageName);
            assertNotNull(dataAppPackageName);
            assertNotNull(packageTracker);
            mPackageTracker = packageTracker;
            mUpdateAppPackageName = updateAppPackageName;
            mDataAppPackageName = dataAppPackageName;
        }

        public void assertInitialized(
                String expectedUpdateAppPackageName, String expectedDataAppPackageName) {
            assertNotNull(mPackageTracker);
            assertEquals(expectedUpdateAppPackageName, mUpdateAppPackageName);
            assertEquals(expectedDataAppPackageName, mDataAppPackageName);
        }

        public void assertNotInitialized() {
            assertNull(mPackageTracker);
        }

        @Override
        public void sendTriggerUpdateCheck(CheckToken checkToken) {
            if (mLastToken != null) {
                fail("lastToken already set");
            }
            mLastToken = checkToken;
        }

        @Override
        public void scheduleReliabilityTrigger(long minimumDelayMillis) {
            mReliabilityTriggerScheduled = true;
        }

        @Override
        public void unscheduleReliabilityTrigger() {
            mReliabilityTriggerScheduled = false;
        }

        public void assertReliabilityTriggerScheduled() {
            assertTrue(mReliabilityTriggerScheduled);
        }

        public void assertReliabilityTriggerNotScheduled() {
            assertFalse(mReliabilityTriggerScheduled);
        }

        public void assertUpdateTriggered() {
            assertNotNull(mLastToken);
        }

        public void assertUpdateNotTriggered() {
            assertNull(mLastToken);
        }

        public CheckToken captureAndResetLastToken() {
            CheckToken toReturn = mLastToken;
            assertNotNull("No update triggered", toReturn);
            mLastToken = null;
            return toReturn;
        }

        public void simulatePackageUpdatedEvent() {
            mPackageTracker.triggerUpdateIfNeeded(true /* packageChanged */);
        }
    }

    private static class FakeClock extends Clock {

        private long currentTime = 1000;

        @Override
        public long millis() {
            return currentTime;
        }

        public void incrementClock(long millis) {
            currentTime += millis;
        }

        @Override
        public ZoneId getZone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Registers a mockito parameter matcher that uses {@link Intent#filterEquals(Intent)}. to
     * check the parameter against the intent supplied.
     */
    private static Intent filterEquals(final Intent expected) {
        final Matcher<Intent> m = new BaseMatcher<Intent>() {
            @Override
            public boolean matches(Object actual) {
                return actual != null && expected.filterEquals((Intent) actual);
            }
            @Override
            public void describeTo(Description description) {
                description.appendText(expected.toString());
            }
        };
        return argThat(m);
    }
}
