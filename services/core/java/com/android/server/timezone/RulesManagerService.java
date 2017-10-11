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
import com.android.server.EventLogTags;
import com.android.server.SystemService;
import com.android.timezone.distro.DistroException;
import com.android.timezone.distro.DistroVersion;
import com.android.timezone.distro.StagedDistroOperation;
import com.android.timezone.distro.TimeZoneDistro;
import com.android.timezone.distro.installer.TimeZoneDistroInstaller;

import android.app.timezone.Callback;
import android.app.timezone.DistroFormatVersion;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.ICallback;
import android.app.timezone.IRulesManager;
import android.app.timezone.RulesManager;
import android.app.timezone.RulesState;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.icu.ICU;
import libcore.util.ZoneInfoDB;

import static android.app.timezone.RulesState.DISTRO_STATUS_INSTALLED;
import static android.app.timezone.RulesState.DISTRO_STATUS_NONE;
import static android.app.timezone.RulesState.DISTRO_STATUS_UNKNOWN;
import static android.app.timezone.RulesState.STAGED_OPERATION_INSTALL;
import static android.app.timezone.RulesState.STAGED_OPERATION_NONE;
import static android.app.timezone.RulesState.STAGED_OPERATION_UNINSTALL;
import static android.app.timezone.RulesState.STAGED_OPERATION_UNKNOWN;

public final class RulesManagerService extends IRulesManager.Stub {

    private static final String TAG = "timezone.RulesManagerService";

    /** The distro format supported by this device. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final DistroFormatVersion DISTRO_FORMAT_VERSION_SUPPORTED =
            new DistroFormatVersion(
                    DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                    DistroVersion.CURRENT_FORMAT_MINOR_VERSION);

    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            RulesManagerService service = RulesManagerService.create(getContext());
            service.start();

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            publishBinderService(Context.TIME_ZONE_RULES_MANAGER_SERVICE, service);

            // Publish the service instance locally so we can use it directly from within the system
            // server from TimeZoneUpdateIdler.
            publishLocalService(RulesManagerService.class, service);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String REQUIRED_UPDATER_PERMISSION =
            android.Manifest.permission.UPDATE_TIME_ZONE_RULES;
    private static final File SYSTEM_TZ_DATA_FILE = new File("/system/usr/share/zoneinfo/tzdata");
    private static final File TZ_DATA_DIR = new File("/data/misc/zoneinfo");

    private final AtomicBoolean mOperationInProgress = new AtomicBoolean(false);
    private final PermissionHelper mPermissionHelper;
    private final PackageTracker mPackageTracker;
    private final Executor mExecutor;
    private final TimeZoneDistroInstaller mInstaller;

    private static RulesManagerService create(Context context) {
        RulesManagerServiceHelperImpl helper = new RulesManagerServiceHelperImpl(context);
        return new RulesManagerService(
                helper /* permissionHelper */,
                helper /* executor */,
                PackageTracker.create(context),
                new TimeZoneDistroInstaller(TAG, SYSTEM_TZ_DATA_FILE, TZ_DATA_DIR));
    }

    // A constructor that can be used by tests to supply mocked / faked dependencies.
    RulesManagerService(PermissionHelper permissionHelper,
            Executor executor, PackageTracker packageTracker,
            TimeZoneDistroInstaller timeZoneDistroInstaller) {
        mPermissionHelper = permissionHelper;
        mExecutor = executor;
        mPackageTracker = packageTracker;
        mInstaller = timeZoneDistroInstaller;
    }

    public void start() {
        mPackageTracker.start();
    }

    @Override // Binder call
    public RulesState getRulesState() {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

        return getRulesStateInternal();
    }

    /** Like {@link #getRulesState()} without the permission check. */
    private RulesState getRulesStateInternal() {
        synchronized(this) {
            String systemRulesVersion;
            try {
                systemRulesVersion = mInstaller.getSystemRulesVersion();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to read system rules", e);
                return null;
            }

            boolean operationInProgress = this.mOperationInProgress.get();

            // Determine the staged operation status, if possible.
            DistroRulesVersion stagedDistroRulesVersion = null;
            int stagedOperationStatus = STAGED_OPERATION_UNKNOWN;
            if (!operationInProgress) {
                StagedDistroOperation stagedDistroOperation;
                try {
                    stagedDistroOperation = mInstaller.getStagedDistroOperation();
                    if (stagedDistroOperation == null) {
                        stagedOperationStatus = STAGED_OPERATION_NONE;
                    } else if (stagedDistroOperation.isUninstall) {
                        stagedOperationStatus = STAGED_OPERATION_UNINSTALL;
                    } else {
                        // Must be an install.
                        stagedOperationStatus = STAGED_OPERATION_INSTALL;
                        DistroVersion stagedDistroVersion = stagedDistroOperation.distroVersion;
                        stagedDistroRulesVersion = new DistroRulesVersion(
                                stagedDistroVersion.rulesVersion,
                                stagedDistroVersion.revision);
                    }
                } catch (DistroException | IOException e) {
                    Slog.w(TAG, "Failed to read staged distro.", e);
                }
            }

            // Determine the installed distro state, if possible.
            DistroVersion installedDistroVersion;
            int distroStatus = DISTRO_STATUS_UNKNOWN;
            DistroRulesVersion installedDistroRulesVersion = null;
            if (!operationInProgress) {
                try {
                    installedDistroVersion = mInstaller.getInstalledDistroVersion();
                    if (installedDistroVersion == null) {
                        distroStatus = DISTRO_STATUS_NONE;
                        installedDistroRulesVersion = null;
                    } else {
                        distroStatus = DISTRO_STATUS_INSTALLED;
                        installedDistroRulesVersion = new DistroRulesVersion(
                                installedDistroVersion.rulesVersion,
                                installedDistroVersion.revision);
                    }
                } catch (DistroException | IOException e) {
                    Slog.w(TAG, "Failed to read installed distro.", e);
                }
            }
            return new RulesState(systemRulesVersion, DISTRO_FORMAT_VERSION_SUPPORTED,
                    operationInProgress, stagedOperationStatus, stagedDistroRulesVersion,
                    distroStatus, installedDistroRulesVersion);
        }
    }

    @Override
    public int requestInstall(ParcelFileDescriptor distroParcelFileDescriptor,
            byte[] checkTokenBytes, ICallback callback) {

        boolean closeParcelFileDescriptorOnExit = true;
        try {
            mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

            CheckToken checkToken = null;
            if (checkTokenBytes != null) {
                checkToken = createCheckTokenOrThrow(checkTokenBytes);
            }
            EventLogTags.writeTimezoneRequestInstall(toStringOrNull(checkToken));

            synchronized (this) {
                if (distroParcelFileDescriptor == null) {
                    throw new NullPointerException("distroParcelFileDescriptor == null");
                }
                if (callback == null) {
                    throw new NullPointerException("observer == null");
                }
                if (mOperationInProgress.get()) {
                    return RulesManager.ERROR_OPERATION_IN_PROGRESS;
                }
                mOperationInProgress.set(true);

                // Execute the install asynchronously.
                mExecutor.execute(
                        new InstallRunnable(distroParcelFileDescriptor, checkToken, callback));

                // The InstallRunnable now owns the ParcelFileDescriptor, so it will close it after
                // it executes (and we do not have to).
                closeParcelFileDescriptorOnExit = false;

                return RulesManager.SUCCESS;
            }
        } finally {
            // We should close() the local ParcelFileDescriptor we were passed if it hasn't been
            // passed to another thread to handle.
            if (distroParcelFileDescriptor != null && closeParcelFileDescriptorOnExit) {
                try {
                    distroParcelFileDescriptor.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e);
                }
            }
        }
    }

    private class InstallRunnable implements Runnable {

        private final ParcelFileDescriptor mDistroParcelFileDescriptor;
        private final CheckToken mCheckToken;
        private final ICallback mCallback;

        InstallRunnable(ParcelFileDescriptor distroParcelFileDescriptor, CheckToken checkToken,
                ICallback callback) {
            mDistroParcelFileDescriptor = distroParcelFileDescriptor;
            mCheckToken = checkToken;
            mCallback = callback;
        }

        @Override
        public void run() {
            EventLogTags.writeTimezoneInstallStarted(toStringOrNull(mCheckToken));

            boolean success = false;
            // Adopt the ParcelFileDescriptor into this try-with-resources so it is closed
            // when we are done.
            try (ParcelFileDescriptor pfd = mDistroParcelFileDescriptor) {
                // The ParcelFileDescriptor owns the underlying FileDescriptor and we'll close
                // it at the end of the try-with-resources.
                final boolean isFdOwner = false;
                InputStream is = new FileInputStream(pfd.getFileDescriptor(), isFdOwner);

                TimeZoneDistro distro = new TimeZoneDistro(is);
                int installerResult = mInstaller.stageInstallWithErrorCode(distro);
                int resultCode = mapInstallerResultToApiCode(installerResult);
                EventLogTags.writeTimezoneInstallComplete(toStringOrNull(mCheckToken), resultCode);
                sendFinishedStatus(mCallback, resultCode);

                // All the installer failure modes are currently non-recoverable and won't be
                // improved by trying again. Therefore success = true.
                success = true;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to install distro.", e);
                EventLogTags.writeTimezoneInstallComplete(
                        toStringOrNull(mCheckToken), Callback.ERROR_UNKNOWN_FAILURE);
                sendFinishedStatus(mCallback, Callback.ERROR_UNKNOWN_FAILURE);
            } finally {
                // Notify the package tracker that the operation is now complete.
                mPackageTracker.recordCheckResult(mCheckToken, success);

                mOperationInProgress.set(false);
            }
        }

        private int mapInstallerResultToApiCode(int installerResult) {
            switch (installerResult) {
                case TimeZoneDistroInstaller.INSTALL_SUCCESS:
                    return Callback.SUCCESS;
                case TimeZoneDistroInstaller.INSTALL_FAIL_BAD_DISTRO_STRUCTURE:
                    return Callback.ERROR_INSTALL_BAD_DISTRO_STRUCTURE;
                case TimeZoneDistroInstaller.INSTALL_FAIL_RULES_TOO_OLD:
                    return Callback.ERROR_INSTALL_RULES_TOO_OLD;
                case TimeZoneDistroInstaller.INSTALL_FAIL_BAD_DISTRO_FORMAT_VERSION:
                    return Callback.ERROR_INSTALL_BAD_DISTRO_FORMAT_VERSION;
                case TimeZoneDistroInstaller.INSTALL_FAIL_VALIDATION_ERROR:
                    return Callback.ERROR_INSTALL_VALIDATION_ERROR;
                default:
                    return Callback.ERROR_UNKNOWN_FAILURE;
            }
        }
    }

    @Override
    public int requestUninstall(byte[] checkTokenBytes, ICallback callback) {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        EventLogTags.writeTimezoneRequestUninstall(toStringOrNull(checkToken));
        synchronized(this) {
            if (callback == null) {
                throw new NullPointerException("callback == null");
            }

            if (mOperationInProgress.get()) {
                return RulesManager.ERROR_OPERATION_IN_PROGRESS;
            }
            mOperationInProgress.set(true);

            // Execute the uninstall asynchronously.
            mExecutor.execute(new UninstallRunnable(checkToken, callback));

            return RulesManager.SUCCESS;
        }
    }

    private class UninstallRunnable implements Runnable {

        private final CheckToken mCheckToken;
        private final ICallback mCallback;

        UninstallRunnable(CheckToken checkToken, ICallback callback) {
            mCheckToken = checkToken;
            mCallback = callback;
        }

        @Override
        public void run() {
            EventLogTags.writeTimezoneUninstallStarted(toStringOrNull(mCheckToken));
            boolean packageTrackerStatus = false;
            try {
                int uninstallResult = mInstaller.stageUninstall();
                packageTrackerStatus = (uninstallResult == TimeZoneDistroInstaller.UNINSTALL_SUCCESS
                        || uninstallResult == TimeZoneDistroInstaller.UNINSTALL_NOTHING_INSTALLED);

                // Right now we just have Callback.SUCCESS / Callback.ERROR_UNKNOWN_FAILURE for
                // uninstall. All clients should be checking against SUCCESS. More granular failures
                // may be added in future.
                int callbackResultCode =
                        packageTrackerStatus ? Callback.SUCCESS : Callback.ERROR_UNKNOWN_FAILURE;
                EventLogTags.writeTimezoneUninstallComplete(
                        toStringOrNull(mCheckToken), callbackResultCode);
                sendFinishedStatus(mCallback, callbackResultCode);
            } catch (Exception e) {
                EventLogTags.writeTimezoneUninstallComplete(
                        toStringOrNull(mCheckToken), Callback.ERROR_UNKNOWN_FAILURE);
                Slog.w(TAG, "Failed to uninstall distro.", e);
                sendFinishedStatus(mCallback, Callback.ERROR_UNKNOWN_FAILURE);
            } finally {
                // Notify the package tracker that the operation is now complete.
                mPackageTracker.recordCheckResult(mCheckToken, packageTrackerStatus);

                mOperationInProgress.set(false);
            }
        }
    }

    private void sendFinishedStatus(ICallback callback, int resultCode) {
        try {
            callback.onFinished(resultCode);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to notify observer of result", e);
        }
    }

    @Override
    public void requestNothing(byte[] checkTokenBytes, boolean success) {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        EventLogTags.writeTimezoneRequestNothing(toStringOrNull(checkToken));
        mPackageTracker.recordCheckResult(checkToken, success);
        EventLogTags.writeTimezoneNothingComplete(toStringOrNull(checkToken));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!mPermissionHelper.checkDumpPermission(TAG, pw)) {
            return;
        }

        RulesState rulesState = getRulesStateInternal();
        if (args != null && args.length == 2) {
            // Formatting options used for automated tests. The format is less free-form than
            // the -format options, which are intended to be easier to parse.
            if ("-format_state".equals(args[0]) && args[1] != null) {
                for (char c : args[1].toCharArray()) {
                    switch (c) {
                        case 'p': {
                            // Report operation in progress
                            String value = "Unknown";
                            if (rulesState != null) {
                                value = Boolean.toString(rulesState.isOperationInProgress());
                            }
                            pw.println("Operation in progress: " + value);
                            break;
                        }
                        case 's': {
                            // Report system image rules version
                            String value = "Unknown";
                            if (rulesState != null) {
                                value = rulesState.getSystemRulesVersion();
                            }
                            pw.println("System rules version: " + value);
                            break;
                        }
                        case 'c': {
                            // Report current installation state
                            String value = "Unknown";
                            if (rulesState != null) {
                                value = distroStatusToString(rulesState.getDistroStatus());
                            }
                            pw.println("Current install state: " + value);
                            break;
                        }
                        case 'i': {
                            // Report currently installed version
                            String value = "Unknown";
                            if (rulesState != null) {
                                DistroRulesVersion installedRulesVersion =
                                        rulesState.getInstalledDistroRulesVersion();
                                if (installedRulesVersion == null) {
                                    value = "<None>";
                                } else {
                                    value = installedRulesVersion.toDumpString();
                                }
                            }
                            pw.println("Installed rules version: " + value);
                            break;
                        }
                        case 'o': {
                            // Report staged operation type
                            String value = "Unknown";
                            if (rulesState != null) {
                                int stagedOperationType = rulesState.getStagedOperationType();
                                value = stagedOperationToString(stagedOperationType);
                            }
                            pw.println("Staged operation: " + value);
                            break;
                        }
                        case 't': {
                            // Report staged version (i.e. the one that will be installed next boot
                            // if the staged operation is an install).
                            String value = "Unknown";
                            if (rulesState != null) {
                                DistroRulesVersion stagedDistroRulesVersion =
                                        rulesState.getStagedDistroRulesVersion();
                                if (stagedDistroRulesVersion == null) {
                                    value = "<None>";
                                } else {
                                    value = stagedDistroRulesVersion.toDumpString();
                                }
                            }
                            pw.println("Staged rules version: " + value);
                            break;
                        }
                        case 'a': {
                            // Report the active rules version (i.e. the rules in use by the current
                            // process).
                            pw.println("Active rules version (ICU, libcore): "
                                    + ICU.getTZDataVersion() + ","
                                    + ZoneInfoDB.getInstance().getVersion());
                            break;
                        }
                        default: {
                            pw.println("Unknown option: " + c);
                        }
                    }
                }
                return;
            }
        }

        pw.println("RulesManagerService state: " + toString());
        pw.println("Active rules version (ICU, libcore): " + ICU.getTZDataVersion() + ","
                + ZoneInfoDB.getInstance().getVersion());
        pw.println("Distro state: " + rulesState.toString());
        mPackageTracker.dump(pw);
    }

    /**
     * Called when the device is considered idle.
     */
    void notifyIdle() {
        // No package has changed: we are just triggering because the device is idle and there
        // *might* be work to do.
        final boolean packageChanged = false;
        mPackageTracker.triggerUpdateIfNeeded(packageChanged);
    }

    @Override
    public String toString() {
        return "RulesManagerService{" +
                "mOperationInProgress=" + mOperationInProgress +
                '}';
    }

    private static CheckToken createCheckTokenOrThrow(byte[] checkTokenBytes) {
        CheckToken checkToken;
        try {
            checkToken = CheckToken.fromByteArray(checkTokenBytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read token bytes "
                    + Arrays.toString(checkTokenBytes), e);
        }
        return checkToken;
    }

    private static String distroStatusToString(int distroStatus) {
        switch(distroStatus) {
            case DISTRO_STATUS_NONE:
                return "None";
            case DISTRO_STATUS_INSTALLED:
                return "Installed";
            case DISTRO_STATUS_UNKNOWN:
            default:
                return "Unknown";
        }
    }

    private static String stagedOperationToString(int stagedOperationType) {
        switch(stagedOperationType) {
            case STAGED_OPERATION_NONE:
                return "None";
            case STAGED_OPERATION_UNINSTALL:
                return "Uninstall";
            case STAGED_OPERATION_INSTALL:
                return "Install";
            case STAGED_OPERATION_UNKNOWN:
            default:
                return "Unknown";
        }
    }

    private static String toStringOrNull(Object obj) {
        return obj == null ? null : obj.toString();
    }
}
