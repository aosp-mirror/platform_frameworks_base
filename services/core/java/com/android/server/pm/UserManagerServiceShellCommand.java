/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.os.RoSystemProperties;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.power.ShutdownThread;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;
import java.util.List;

/**
 * Shell command implementation for the user manager service
 */
public class UserManagerServiceShellCommand extends ShellCommand {

    private static final String LOG_TAG = "UserManagerServiceShellCommand";
    @NonNull
    private final UserManagerService mService;
    @NonNull
    private final UserSystemPackageInstaller mSystemPackageInstaller;
    @NonNull
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final Context mContext;

    UserManagerServiceShellCommand(UserManagerService service,
            UserSystemPackageInstaller userSystemPackageInstaller,
            LockPatternUtils lockPatternUtils,
            Context context) {
        mService = service;
        mSystemPackageInstaller = userSystemPackageInstaller;
        mLockPatternUtils = lockPatternUtils;
        mContext = context;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("User manager (user) commands:");
        pw.println("  help");
        pw.println("    Prints this help text.");
        pw.println();
        pw.println("  list [-v | --verbose] [--all]");
        pw.println("    Prints all users on the system.");
        pw.println();
        pw.println("  report-system-user-package-whitelist-problems [-v | --verbose] "
                + "[--critical-only] [--mode MODE]");
        pw.println("    Reports all issues on user-type package allowlist XML files. Options:");
        pw.println("    -v | --verbose: shows extra info, like number of issues");
        pw.println("    --critical-only: show only critical issues, excluding warnings");
        pw.println("    --mode MODE: shows what errors would be if device used mode MODE");
        pw.println("      (where MODE is the allowlist mode integer as defined by "
                + "config_userTypePackageWhitelistMode)");
        pw.println();
        pw.println("  set-system-user-mode-emulation [--reboot | --no-restart] "
                + "<headless | full | default>");
        pw.println("    Changes whether the system user is headless, full, or default (as "
                + "defined by OEM).");
        pw.println("    WARNING: this command is meant just for development and debugging "
                + "purposes.");
        pw.println("             It should NEVER be used on automated tests.");
        pw.println("    NOTE: by default it restarts the Android runtime, unless called with");
        pw.println("          --reboot (which does a full reboot) or");
        pw.println("          --no-restart (which requires a manual restart)");
        pw.println();
        pw.println("  is-headless-system-user-mode [-v | --verbose]");
        pw.println("    Checks whether the device uses headless system user mode.");
        pw.println("    It returns the effective mode, even when using emulation");
        pw.println("    (to get the real mode as well, use -v or --verbose)");
        pw.println();
        pw.println("  is-user-visible [--display DISPLAY_ID] <USER_ID>");
        pw.println("    Checks if the given user is visible in the given display.");
        pw.println("    If the display option is not set, it uses the user's context to check");
        pw.println("    (so it emulates what apps would get from UserManager.isUserVisible())");
        pw.println();
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        try {
            switch(cmd) {
                case "list":
                    return runList();
                case "report-system-user-package-whitelist-problems":
                    return runReportPackageAllowlistProblems();
                case "set-system-user-mode-emulation":
                    return runSetSystemUserModeEmulation();
                case "is-headless-system-user-mode":
                    return runIsHeadlessSystemUserMode();
                case "is-user-visible":
                    return runIsUserVisible();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            getOutPrintWriter().println("Remote exception: " + e);
        }
        return -1;
    }

    private int runList() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean all = false;
        boolean verbose = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "--all":
                    all = true;
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }
        final IActivityManager am = ActivityManager.getService();
        final List<UserInfo> users = mService.getUsers(/* excludePartial= */ !all,
                /* excludeDying= */ false, /* excludePreCreated= */ !all);
        if (users == null) {
            pw.println("Error: couldn't get users");
            return 1;
        } else {
            final int size = users.size();
            int currentUser = UserHandle.USER_NULL;
            if (verbose) {
                pw.printf("%d users:\n\n", size);
                currentUser = am.getCurrentUser().id;
            } else {
                // NOTE: the standard "list users" command is used by integration tests and
                // hence should not be changed. If you need to add more info, use the
                // verbose option.
                pw.println("Users:");
            }
            for (int i = 0; i < size; i++) {
                final UserInfo user = users.get(i);
                final boolean running = am.isUserRunning(user.id, 0);
                if (verbose) {
                    final DevicePolicyManagerInternal dpm = LocalServices
                            .getService(DevicePolicyManagerInternal.class);
                    String deviceOwner = "";
                    String profileOwner = "";
                    if (dpm != null) {
                        final long identity = Binder.clearCallingIdentity();
                        // NOTE: dpm methods below CANNOT be called while holding the mUsersLock
                        try {
                            if (dpm.getDeviceOwnerUserId() == user.id) {
                                deviceOwner = " (device-owner)";
                            }
                            if (dpm.getProfileOwnerAsUser(user.id) != null) {
                                profileOwner = " (profile-owner)";
                            }
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                    final boolean current = user.id == currentUser;
                    final boolean hasParent = user.profileGroupId != user.id
                            && user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID;
                    final boolean visible = mService.isUserVisible(user.id);
                    pw.printf("%d: id=%d, name=%s, type=%s, flags=%s%s%s%s%s%s%s%s%s%s\n",
                            i,
                            user.id,
                            user.name,
                            user.userType.replace("android.os.usertype.", ""),
                            UserInfo.flagsToString(user.flags),
                            hasParent ? " (parentId=" + user.profileGroupId + ")" : "",
                            running ? " (running)" : "",
                            user.partial ? " (partial)" : "",
                            user.preCreated ? " (pre-created)" : "",
                            user.convertedFromPreCreated ? " (converted)" : "",
                            deviceOwner, profileOwner,
                            current ? " (current)" : "",
                            visible ? " (visible)" : ""
                    );
                } else {
                    // NOTE: the standard "list users" command is used by integration tests and
                    // hence should not be changed. If you need to add more info, use the
                    // verbose option.
                    pw.printf("\t%s%s\n", user, running ? " running" : "");
                }
            }
            return 0;
        }
    }

    private int runReportPackageAllowlistProblems() {
        final PrintWriter pw = getOutPrintWriter();
        boolean verbose = false;
        boolean criticalOnly = false;
        int mode = UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_NONE;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "--critical-only":
                    criticalOnly = true;
                    break;
                case "--mode":
                    mode = Integer.parseInt(getNextArgRequired());
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }

        Slog.d(LOG_TAG, "runReportPackageAllowlistProblems(): verbose=" + verbose
                + ", criticalOnly=" + criticalOnly
                + ", mode=" + UserSystemPackageInstaller.modeToString(mode));

        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
            mSystemPackageInstaller.dumpPackageWhitelistProblems(ipw, mode, verbose,
                    criticalOnly);
        }
        return 0;
    }


    private int runSetSystemUserModeEmulation() {
        if (!confirmBuildIsDebuggable() || !confirmIsCalledByRoot()) {
            return -1;
        }

        final PrintWriter pw = getOutPrintWriter();

        // The headless system user cannot be locked; in theory, we could just make this check
        // when going full -> headless, but it doesn't hurt to check on both (and it makes the
        // code simpler)
        if (mLockPatternUtils.isSecure(UserHandle.USER_SYSTEM)) {
            pw.println("Cannot change system user mode when it has a credential");
            return -1;
        }

        boolean restart = true;
        boolean reboot = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--reboot":
                    reboot = true;
                    break;
                case "--no-restart":
                    restart = false;
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }
        if (reboot && !restart) {
            getErrPrintWriter().println("You can use --reboot or --no-restart, but not both");
            return -1;
        }

        final String mode = getNextArgRequired();
        final boolean isHeadlessSystemUserModeCurrently = UserManager
                .isHeadlessSystemUserMode();
        final boolean changed;

        switch (mode) {
            case UserManager.SYSTEM_USER_MODE_EMULATION_FULL:
                changed = isHeadlessSystemUserModeCurrently;
                break;
            case UserManager.SYSTEM_USER_MODE_EMULATION_HEADLESS:
                changed = !isHeadlessSystemUserModeCurrently;
                break;
            case UserManager.SYSTEM_USER_MODE_EMULATION_DEFAULT:
                changed = true; // Always update when resetting to default
                break;
            default:
                getErrPrintWriter().printf("Invalid arg: %s\n", mode);
                return -1;
        }

        if (!changed) {
            pw.printf("No change needed, system user is already %s\n",
                    isHeadlessSystemUserModeCurrently ? "headless" : "full");
            return 0;
        }

        Slogf.d(LOG_TAG, "Updating system property %s to %s",
                UserManager.SYSTEM_USER_MODE_EMULATION_PROPERTY, mode);

        SystemProperties.set(UserManager.SYSTEM_USER_MODE_EMULATION_PROPERTY, mode);

        if (reboot) {
            Slog.i(LOG_TAG, "Rebooting to finalize the changes");
            pw.println("Rebooting to finalize changes");
            UiThread.getHandler()
                    .post(() -> ShutdownThread.reboot(
                            ActivityThread.currentActivityThread().getSystemUiContext(),
                            "To switch headless / full system user mode",
                            /* confirm= */ false));
        } else if (restart) {
            Slog.i(LOG_TAG, "Shutting PackageManager down");
            LocalServices.getService(PackageManagerInternal.class).shutdown();

            final IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    Slog.i(LOG_TAG, "Shutting ActivityManager down");
                    am.shutdown(/* timeout= */ 10_000);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Failed to shut down ActivityManager" + e);
                }
            }

            final int pid = Process.myPid();
            Slogf.i(LOG_TAG, "Restarting Android runtime(PID=%d) to finalize changes", pid);
            pw.println("Restarting Android runtime to finalize changes");
            pw.flush();

            // Ideally there should be a cleaner / safer option to restart system_server, but
            // that doesn't seem to be the case. For example, ShutdownThread.reboot() calls
            // pm.shutdown() and am.shutdown() (which we already are calling above), but when
            // the system is restarted through 'adb shell stop && adb shell start`, these
            // methods are not called, so just killing the process seems to be fine.

            Process.killProcess(pid);
        } else {
            pw.println("System user mode changed - please reboot (or restart Android runtime) "
                    + "to continue");
            pw.println("NOTICE: after restart, some apps might be uninstalled (and their data "
                    + "will be lost)");
        }
        return 0;
    }

    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    private int runIsUserVisible() {
        PrintWriter pw = getOutPrintWriter();
        Integer displayId = null;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--display":
                    displayId = Integer.parseInt(getNextArgRequired());
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }
        int userId = UserHandle.parseUserArg(getNextArgRequired());
        switch (userId) {
            case UserHandle.USER_ALL:
            case UserHandle.USER_CURRENT_OR_SELF:
            case UserHandle.USER_NULL:
                pw.printf("invalid value (%d) for --user option\n", userId);
                return -1;
            case UserHandle.USER_CURRENT:
                userId = ActivityManager.getCurrentUser();
                break;
        }

        boolean isVisible;
        if (displayId != null) {
            isVisible = mService.isUserVisibleOnDisplay(userId, displayId);
        } else {
            isVisible = getUserManagerForUser(userId).isUserVisible();
        }
        pw.println(isVisible);
        return 0;
    }

    private int runIsHeadlessSystemUserMode() {
        PrintWriter pw = getOutPrintWriter();

        boolean verbose = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }

        boolean isHsum = mService.isHeadlessSystemUserMode();
        if (!verbose) {
            // NOTE: do not change output below, as it's used by ITestDevice
            // (it's ok to change the verbose option though)
            pw.println(isHsum);
        } else {
            pw.printf("effective=%b real=%b\n", isHsum,
                    RoSystemProperties.MULTIUSER_HEADLESS_SYSTEM_USER);
        }
        return 0;
    }

    /**
     * Gets the {@link UserManager} associated with the context of the given user.
     */
    private UserManager getUserManagerForUser(int userId) {
        UserHandle user = UserHandle.of(userId);
        Context context = mContext.createContextAsUser(user, /* flags= */ 0);
        return context.getSystemService(UserManager.class);
    }

    /**
     * Confirms if the build is debuggable
     *
     * <p>It logs an error when it isn't.
     */
    private boolean confirmBuildIsDebuggable() {
        if (Build.isDebuggable()) {
            return true;
        }
        getErrPrintWriter().println("Command not available on user builds");
        return false;
    }

    /**
     * Confirms if the command is called when {@code adb} is rooted.
     *
     * <p>It logs an error when it isn't.
     */
    private boolean confirmIsCalledByRoot() {
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            return true;
        }
        getErrPrintWriter().println("Command only available on root user");
        return false;
    }
}
