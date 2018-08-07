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

import com.android.internal.annotations.VisibleForTesting;

import android.app.timezone.RulesUpdaterContract;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemClock;
import android.provider.TimeZoneRulesDataContract;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;

/**
 * Monitors the installed applications associated with time zone updates. If the app packages are
 * updated it indicates there <em>might</em> be a time zone rules update to apply so a targeted
 * broadcast intent is used to trigger the time zone updater app.
 *
 * <p>The "update triggering" behavior of this component can be disabled via device configuration.
 *
 * <p>The package tracker listens for package updates of the time zone "updater app" and "data app".
 * It also listens for "reliability" triggers. Reliability triggers are there to ensure that the
 * package tracker handles failures reliably and are "idle maintenance" events or something similar.
 * Reliability triggers can cause a time zone update check to take place if the current state is
 * unclear. For example, it can be unclear after boot or after a failure. If there are repeated
 * failures reliability updates are halted until the next boot.
 *
 * <p>This component keeps persistent track of the most recent app packages checked to avoid
 * unnecessary expense from broadcasting intents (which will cause other app processes to spawn).
 * The current status is also stored to detect whether the most recently-generated check is
 * complete successfully. For example, if the device was interrupted while doing a check and never
 * acknowledged a check then a check will be retried the next time a "reliability trigger" event
 * happens.
 */
// Also made non-final so it can be mocked.
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class PackageTracker {
    private static final String TAG = "timezone.PackageTracker";

    private final PackageManagerHelper mPackageManagerHelper;
    private final PackageTrackerIntentHelper mIntentHelper;
    private final ConfigHelper mConfigHelper;
    private final PackageStatusStorage mPackageStatusStorage;
    private final Clock mElapsedRealtimeClock;

    // False if tracking is disabled.
    private boolean mTrackingEnabled;

    // These fields may be null if package tracking is disabled.
    private String mUpdateAppPackageName;
    private String mDataAppPackageName;

    // The time a triggered check is allowed to take before it is considered overdue.
    private int mCheckTimeAllowedMillis;
    // The number of failed checks in a row before reliability checks should stop happening.
    private long mFailedCheckRetryCount;

    /*
     * The minimum delay between a successive reliability triggers / other operations. Should to be
     * larger than mCheckTimeAllowedMillis to avoid reliability triggers happening during package
     * update checks.
     */
    private int mDelayBeforeReliabilityCheckMillis;

    // Reliability check state: If a check was triggered but not acknowledged within
    // mCheckTimeAllowedMillis then another one can be triggered.
    private Long mLastTriggerTimestamp = null;

    // Reliability check state: Whether any checks have been triggered at all.
    private boolean mCheckTriggered;

    // Reliability check state: A count of how many failures have occurred consecutively.
    private int mCheckFailureCount;

    /** Creates the {@link PackageTracker} for normal use. */
    static PackageTracker create(Context context) {
        Clock elapsedRealtimeClock = SystemClock.elapsedRealtimeClock();
        PackageTrackerHelperImpl helperImpl = new PackageTrackerHelperImpl(context);
        File storageDir = FileUtils.createDir(Environment.getDataSystemDirectory(), "timezone");
        return new PackageTracker(
                elapsedRealtimeClock /* elapsedRealtimeClock */,
                helperImpl /* configHelper */,
                helperImpl /* packageManagerHelper */,
                new PackageStatusStorage(storageDir),
                new PackageTrackerIntentHelperImpl(context));
    }

    // A constructor that can be used by tests to supply mocked / faked dependencies.
    PackageTracker(Clock elapsedRealtimeClock, ConfigHelper configHelper,
            PackageManagerHelper packageManagerHelper, PackageStatusStorage packageStatusStorage,
            PackageTrackerIntentHelper intentHelper) {
        mElapsedRealtimeClock = elapsedRealtimeClock;
        mConfigHelper = configHelper;
        mPackageManagerHelper = packageManagerHelper;
        mPackageStatusStorage = packageStatusStorage;
        mIntentHelper = intentHelper;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected synchronized boolean start() {
        mTrackingEnabled = mConfigHelper.isTrackingEnabled();
        if (!mTrackingEnabled) {
            Slog.i(TAG, "Time zone updater / data package tracking explicitly disabled.");
            return false;
        }

        mUpdateAppPackageName = mConfigHelper.getUpdateAppPackageName();
        mDataAppPackageName = mConfigHelper.getDataAppPackageName();
        mCheckTimeAllowedMillis = mConfigHelper.getCheckTimeAllowedMillis();
        mFailedCheckRetryCount = mConfigHelper.getFailedCheckRetryCount();
        mDelayBeforeReliabilityCheckMillis = mCheckTimeAllowedMillis + (60 * 1000);

        // Validate the device configuration including the application packages.
        // The manifest entries in the apps themselves are not validated until use as they can
        // change and we don't want to prevent the system server starting due to a bad application.
        throwIfDeviceSettingsOrAppsAreBad();

        // Explicitly start in a reliability state where reliability triggering will do something.
        mCheckTriggered = false;
        mCheckFailureCount = 0;

        // Initialize the storage, as needed.
        try {
            mPackageStatusStorage.initialize();
        } catch (IOException e) {
            Slog.w(TAG, "PackageTracker storage could not be initialized.", e);
            return false;
        }

        // Initialize the intent helper.
        mIntentHelper.initialize(mUpdateAppPackageName, mDataAppPackageName, this);

        // Schedule a reliability trigger so we will have at least one after boot. This will allow
        // us to catch if a package updated wasn't handled to completion. There's no hurry: it's ok
        // to delay for a while before doing this even if idle.
        mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);

        Slog.i(TAG, "Time zone updater / data package tracking enabled");
        return true;
    }

    /**
     * Performs checks that confirm the system image has correctly configured package
     * tracking configuration. Only called if package tracking is enabled. Throws an exception if
     * the device is configured badly which will prevent the device booting.
     */
    private void throwIfDeviceSettingsOrAppsAreBad() {
        // None of the checks below can be based on application manifest settings, otherwise a bad
        // update could leave the device in an unbootable state. See validateDataAppManifest() and
        // validateUpdaterAppManifest() for softer errors.

        throwRuntimeExceptionIfNullOrEmpty(
                mUpdateAppPackageName, "Update app package name missing.");
        throwRuntimeExceptionIfNullOrEmpty(mDataAppPackageName, "Data app package name missing.");
        if (mFailedCheckRetryCount < 1) {
            throw logAndThrowRuntimeException("mFailedRetryCount=" + mFailedCheckRetryCount, null);
        }
        if (mCheckTimeAllowedMillis < 1000) {
            throw logAndThrowRuntimeException(
                    "mCheckTimeAllowedMillis=" + mCheckTimeAllowedMillis, null);
        }

        // Validate the updater application package.
        try {
            if (!mPackageManagerHelper.isPrivilegedApp(mUpdateAppPackageName)) {
                throw logAndThrowRuntimeException(
                        "Update app " + mUpdateAppPackageName + " must be a priv-app.", null);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw logAndThrowRuntimeException("Could not determine update app package details for "
                    + mUpdateAppPackageName, e);
        }
        Slog.d(TAG, "Update app " + mUpdateAppPackageName + " is valid.");

        // Validate the data application package.
        try {
            if (!mPackageManagerHelper.isPrivilegedApp(mDataAppPackageName)) {
                throw logAndThrowRuntimeException(
                        "Data app " + mDataAppPackageName + " must be a priv-app.", null);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw logAndThrowRuntimeException("Could not determine data app package details for "
                    + mDataAppPackageName, e);
        }
        Slog.d(TAG, "Data app " + mDataAppPackageName + " is valid.");
    }

    /**
     * Inspects the current in-memory state, installed packages and storage state to determine if an
     * update check is needed and then trigger if it is.
     *
     * @param packageChanged true if this method was called because a known packaged definitely
     *     changed, false if the cause is a reliability trigger
     */
    public synchronized void triggerUpdateIfNeeded(boolean packageChanged) {
        if (!mTrackingEnabled) {
            throw new IllegalStateException("Unexpected call. Tracking is disabled.");
        }

        // Validate the applications' current manifest entries: make sure they are configured as
        // they should be. These are not fatal and just means that no update is triggered: we don't
        // want to take down the system server if an OEM or Google have pushed a bad update to
        // an application.
        boolean updaterAppManifestValid = validateUpdaterAppManifest();
        boolean dataAppManifestValid = validateDataAppManifest();
        if (!updaterAppManifestValid || !dataAppManifestValid) {
            Slog.e(TAG, "No update triggered due to invalid application manifest entries."
                    + " updaterApp=" + updaterAppManifestValid
                    + ", dataApp=" + dataAppManifestValid);

            // There's no point in doing any reliability triggers if the current packages are bad.
            mIntentHelper.unscheduleReliabilityTrigger();
            return;
        }

        if (!packageChanged) {
            // This call was made because the device is doing a "reliability" check.
            // 4 possible cases:
            // 1) No check has previously triggered since restart. We want to trigger in this case.
            // 2) A check has previously triggered and it is in progress. We want to trigger if
            //    the response is overdue.
            // 3) A check has previously triggered and it failed. We want to trigger, but only if
            //    we're not in a persistent failure state.
            // 4) A check has previously triggered and it succeeded.
            //    We don't want to trigger, and want to stop future triggers.

            if (!mCheckTriggered) {
                // Case 1.
                Slog.d(TAG, "triggerUpdateIfNeeded: First reliability trigger.");
            } else if (isCheckInProgress()) {
                // Case 2.
                if (!isCheckResponseOverdue()) {
                    // A check is in progress but hasn't been given time to succeed.
                    Slog.d(TAG,
                            "triggerUpdateIfNeeded: checkComplete call is not yet overdue."
                                    + " Not triggering.");
                    // Don't do any work now but we do schedule a future reliability trigger.
                    mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
                    return;
                }
            } else if (mCheckFailureCount > mFailedCheckRetryCount) {
                // Case 3. If the system is in some kind of persistent failure state we don't want
                // to keep checking, so just stop.
                Slog.i(TAG, "triggerUpdateIfNeeded: number of allowed consecutive check failures"
                        + " exceeded. Stopping reliability triggers until next reboot or package"
                        + " update.");
                mIntentHelper.unscheduleReliabilityTrigger();
                return;
            } else if (mCheckFailureCount == 0) {
                // Case 4.
                Slog.i(TAG, "triggerUpdateIfNeeded: No reliability check required. Last check was"
                        + " successful.");
                mIntentHelper.unscheduleReliabilityTrigger();
                return;
            }
        }

        // Read the currently installed data / updater package versions.
        PackageVersions currentInstalledVersions = lookupInstalledPackageVersions();
        if (currentInstalledVersions == null) {
            // This should not happen if the device is configured in a valid way.
            Slog.e(TAG, "triggerUpdateIfNeeded: currentInstalledVersions was null");
            mIntentHelper.unscheduleReliabilityTrigger();
            return;
        }

        // Establish the current state using package manager and stored state. Determine if we have
        // already successfully checked the installed versions.
        PackageStatus packageStatus = mPackageStatusStorage.getPackageStatus();
        if (packageStatus == null) {
            // This can imply corrupt, uninitialized storage state (e.g. first check ever on a
            // device) or after some kind of reset.
            Slog.i(TAG, "triggerUpdateIfNeeded: No package status data found. Data check needed.");
        } else if (!packageStatus.mVersions.equals(currentInstalledVersions)) {
            // The stored package version information differs from the installed version.
            // Trigger the check in all cases.
            Slog.i(TAG, "triggerUpdateIfNeeded: Stored package versions="
                    + packageStatus.mVersions + ", do not match current package versions="
                    + currentInstalledVersions + ". Triggering check.");
        } else {
            Slog.i(TAG, "triggerUpdateIfNeeded: Stored package versions match currently"
                    + " installed versions, currentInstalledVersions=" + currentInstalledVersions
                    + ", packageStatus.mCheckStatus=" + packageStatus.mCheckStatus);
            if (packageStatus.mCheckStatus == PackageStatus.CHECK_COMPLETED_SUCCESS) {
                // The last check succeeded and nothing has changed. Do nothing and disable
                // reliability checks.
                Slog.i(TAG, "triggerUpdateIfNeeded: Prior check succeeded. No need to trigger.");
                mIntentHelper.unscheduleReliabilityTrigger();
                return;
            }
        }

        // Generate a token to send to the updater app.
        CheckToken checkToken =
                mPackageStatusStorage.generateCheckToken(currentInstalledVersions);
        if (checkToken == null) {
            Slog.w(TAG, "triggerUpdateIfNeeded: Unable to generate check token."
                    + " Not sending check request.");
            // Trigger again later: perhaps we'll have better luck.
            mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
            return;
        }

        // Trigger the update check.
        mIntentHelper.sendTriggerUpdateCheck(checkToken);
        mCheckTriggered = true;

        // Update the reliability check state in case the update fails.
        setCheckInProgress();

        // Schedule a reliability trigger in case the update check doesn't succeed and there is no
        // response at all. It will be cancelled if the check is successful in recordCheckResult.
        mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
    }

    /**
     * Used to record the result of a check. Can be called even if active package tracking is
     * disabled.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected synchronized void recordCheckResult(CheckToken checkToken, boolean success) {
        Slog.i(TAG, "recordOperationResult: checkToken=" + checkToken + " success=" + success);

        // If package tracking is disabled it means no record-keeping is required. However, we do
        // want to clear out any stored state to make it clear that the current state is unknown and
        // should tracking become enabled again (perhaps through an OTA) we'd need to perform an
        // update check.
        if (!mTrackingEnabled) {
            // This means an updater has spontaneously modified time zone data without having been
            // triggered. This can happen if the OEM is handling their own updates, but we don't
            // need to do any tracking in this case.

            if (checkToken == null) {
                // This is the expected case if tracking is disabled but an OEM is handling time
                // zone installs using their own mechanism.
                Slog.d(TAG, "recordCheckResult: Tracking is disabled and no token has been"
                        + " provided. Resetting tracking state.");
            } else {
                // This is unexpected. If tracking is disabled then no check token should have been
                // generated by the package tracker. An updater should never create its own token.
                // This could be a bug in the updater.
                Slog.w(TAG, "recordCheckResult: Tracking is disabled and a token " + checkToken
                        + " has been unexpectedly provided. Resetting tracking state.");
            }
            mPackageStatusStorage.resetCheckState();
            return;
        }

        if (checkToken == null) {
            /*
             * If the checkToken is null it suggests an install / uninstall / acknowledgement has
             * occurred without a prior trigger (or the client didn't return the token it was given
             * for some reason, perhaps a bug).
             *
             * This shouldn't happen under normal circumstances:
             *
             * If package tracking is enabled, we assume it is the package tracker responsible for
             * triggering updates and a token should have been produced and returned.
             *
             * If the OEM is handling time zone updates case package tracking should be disabled.
             *
             * This could happen in tests. The device should recover back to a known state by
             * itself rather than be left in an invalid state.
             *
             * We treat this as putting the device into an unknown state and make sure that
             * reliability triggering is enabled so we should recover.
             */
            Slog.i(TAG, "recordCheckResult: Unexpectedly missing checkToken, resetting"
                    + " storage state.");
            mPackageStatusStorage.resetCheckState();

            // Schedule a reliability trigger and reset the failure count so we know that the
            // next reliability trigger will do something.
            mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
            mCheckFailureCount = 0;
        } else {
            // This is the expected case when tracking is enabled: a check was triggered and it has
            // completed.
            boolean recordedCheckCompleteSuccessfully =
                    mPackageStatusStorage.markChecked(checkToken, success);
            if (recordedCheckCompleteSuccessfully) {
                // If we have recorded the result (whatever it was) we know there is no check in
                // progress.
                setCheckComplete();

                if (success) {
                    // Since the check was successful, no reliability trigger is required until
                    // there is a package change.
                    mIntentHelper.unscheduleReliabilityTrigger();
                    mCheckFailureCount = 0;
                } else {
                    // Enable schedule a reliability trigger to check again in future.
                    mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
                    mCheckFailureCount++;
                }
            } else {
                // The failure to record the check means an optimistic lock failure and suggests
                // that another check was triggered after the token was generated.
                Slog.i(TAG, "recordCheckResult: could not update token=" + checkToken
                        + " with success=" + success + ". Optimistic lock failure");

                // Schedule a reliability trigger to potentially try again in future.
                mIntentHelper.scheduleReliabilityTrigger(mDelayBeforeReliabilityCheckMillis);
                mCheckFailureCount++;
            }
        }
    }

    /** Access to consecutive failure counts for use in tests. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getCheckFailureCountForTests() {
        return mCheckFailureCount;
    }

    private void setCheckInProgress() {
        mLastTriggerTimestamp = mElapsedRealtimeClock.millis();
    }

    private void setCheckComplete() {
        mLastTriggerTimestamp = null;
    }

    private boolean isCheckInProgress() {
        return mLastTriggerTimestamp != null;
    }

    private boolean isCheckResponseOverdue() {
        if (mLastTriggerTimestamp == null) {
            return false;
        }
        // Risk of overflow, but highly unlikely given the implementation and not problematic.
        return mElapsedRealtimeClock.millis() > mLastTriggerTimestamp + mCheckTimeAllowedMillis;
    }

    private PackageVersions lookupInstalledPackageVersions() {
        long updatePackageVersion;
        long dataPackageVersion;
        try {
            updatePackageVersion =
                    mPackageManagerHelper.getInstalledPackageVersion(mUpdateAppPackageName);
            dataPackageVersion =
                    mPackageManagerHelper.getInstalledPackageVersion(mDataAppPackageName);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "lookupInstalledPackageVersions: Unable to resolve installed package"
                    + " versions", e);
            return null;
        }
        return new PackageVersions(updatePackageVersion, dataPackageVersion);
    }

    private boolean validateDataAppManifest() {
        // We only want to talk to a provider that exposed by the known data app package
        // so we look up the providers exposed by that app and check the well-known authority is
        // there. This prevents the case where *even if* the data app doesn't expose the provider
        // required, another app cannot expose one to replace it.
        if (!mPackageManagerHelper.contentProviderRegistered(
                TimeZoneRulesDataContract.AUTHORITY, mDataAppPackageName)) {
            // Error! Found the package but it didn't expose the correct provider.
            Slog.w(TAG, "validateDataAppManifest: Data app " + mDataAppPackageName
                    + " does not expose the required provider with authority="
                    + TimeZoneRulesDataContract.AUTHORITY);
            return false;
        }
        return true;
    }

    private boolean validateUpdaterAppManifest() {
        try {
            // The updater app is expected to have the UPDATE_TIME_ZONE_RULES permission.
            // The updater app is expected to have a receiver for the intent we are going to trigger
            // and require the TRIGGER_TIME_ZONE_RULES_CHECK.
            if (!mPackageManagerHelper.usesPermission(
                    mUpdateAppPackageName,
                    RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION)) {
                Slog.w(TAG, "validateUpdaterAppManifest: Updater app " + mDataAppPackageName
                        + " does not use permission="
                        + RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION);
                return false;
            }
            if (!mPackageManagerHelper.receiverRegistered(
                    RulesUpdaterContract.createUpdaterIntent(mUpdateAppPackageName),
                    RulesUpdaterContract.TRIGGER_TIME_ZONE_RULES_CHECK_PERMISSION)) {
                return false;
            }

            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "validateUpdaterAppManifest: Updater app " + mDataAppPackageName
                    + " does not expose the required broadcast receiver.", e);
            return false;
        }
    }

    private static void throwRuntimeExceptionIfNullOrEmpty(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw logAndThrowRuntimeException(message, null);
        }
    }

    private static RuntimeException logAndThrowRuntimeException(String message, Throwable cause) {
        Slog.wtf(TAG, message, cause);
        throw new RuntimeException(message, cause);
    }

    public void dump(PrintWriter fout) {
        fout.println("PackageTrackerState: " + toString());
        mPackageStatusStorage.dump(fout);
    }

    @Override
    public String toString() {
        return "PackageTracker{" +
                "mTrackingEnabled=" + mTrackingEnabled +
                ", mUpdateAppPackageName='" + mUpdateAppPackageName + '\'' +
                ", mDataAppPackageName='" + mDataAppPackageName + '\'' +
                ", mCheckTimeAllowedMillis=" + mCheckTimeAllowedMillis +
                ", mDelayBeforeReliabilityCheckMillis=" + mDelayBeforeReliabilityCheckMillis +
                ", mFailedCheckRetryCount=" + mFailedCheckRetryCount +
                ", mLastTriggerTimestamp=" + mLastTriggerTimestamp +
                ", mCheckTriggered=" + mCheckTriggered +
                ", mCheckFailureCount=" + mCheckFailureCount +
                '}';
    }
}
