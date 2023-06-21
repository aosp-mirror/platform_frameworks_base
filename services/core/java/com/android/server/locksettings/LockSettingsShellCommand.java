/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.app.ActivityManager;
import android.app.admin.PasswordMetrics;
import android.content.Context;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.RequestThrottledException;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;

import java.io.PrintWriter;
import java.util.List;

class LockSettingsShellCommand extends ShellCommand {

    private static final String COMMAND_SET_PATTERN = "set-pattern";
    private static final String COMMAND_SET_PIN = "set-pin";
    private static final String COMMAND_SET_PASSWORD = "set-password";
    private static final String COMMAND_CLEAR = "clear";
    private static final String COMMAND_SET_DISABLED = "set-disabled";
    private static final String COMMAND_VERIFY = "verify";
    private static final String COMMAND_GET_DISABLED = "get-disabled";
    private static final String COMMAND_REMOVE_CACHE = "remove-cache";
    private static final String COMMAND_SET_ROR_PROVIDER_PACKAGE =
            "set-resume-on-reboot-provider-package";
    private static final String COMMAND_REQUIRE_STRONG_AUTH =
            "require-strong-auth";
    private static final String COMMAND_HELP = "help";

    private int mCurrentUserId;
    private final LockPatternUtils mLockPatternUtils;
    private final Context mContext;
    private final int mCallingPid;
    private final int mCallingUid;

    private String mOld = "";
    private String mNew = "";

    LockSettingsShellCommand(LockPatternUtils lockPatternUtils, Context context, int callingPid,
            int callingUid) {
        mLockPatternUtils = lockPatternUtils;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try {
            mCurrentUserId = ActivityManager.getService().getCurrentUser().id;

            parseArgs();
            if (!mLockPatternUtils.hasSecureLockScreen()) {
                switch (cmd) {
                    case COMMAND_HELP:
                    case COMMAND_GET_DISABLED:
                    case COMMAND_SET_DISABLED:
                    case COMMAND_SET_ROR_PROVIDER_PACKAGE:
                        break;
                    default:
                        getErrPrintWriter().println(
                                "The device does not support lock screen - ignoring the command.");
                        return -1;
                }
            }
            switch (cmd) {
                // Commands that do not require authentication go here.
                case COMMAND_REMOVE_CACHE:
                    runRemoveCache();
                    return 0;
                case COMMAND_SET_ROR_PROVIDER_PACKAGE:
                    runSetResumeOnRebootProviderPackage();
                    return 0;
                case COMMAND_REQUIRE_STRONG_AUTH:
                    runRequireStrongAuth();
                    return 0;
                case COMMAND_HELP:
                    onHelp();
                    return 0;
                case COMMAND_GET_DISABLED:
                    runGetDisabled();
                    return 0;
                case COMMAND_SET_DISABLED:
                    // Note: if the user has an LSKF, then this has no immediate effect but instead
                    // just ensures the lockscreen will be disabled later when the LSKF is cleared.
                    runSetDisabled();
                    return 0;
            }
            if (!checkCredential()) {
                return -1;
            }
            boolean success = true;
            switch (cmd) {
                case COMMAND_SET_PATTERN:
                    success = runSetPattern();
                    break;
                case COMMAND_SET_PASSWORD:
                    success = runSetPassword();
                    break;
                case COMMAND_SET_PIN:
                    success = runSetPin();
                    break;
                case COMMAND_CLEAR:
                    success = runClear();
                    break;
                case COMMAND_VERIFY:
                    runVerify();
                    break;
                default:
                    getErrPrintWriter().println("Unknown command: " + cmd);
                    break;
            }
            return success ? 0 : -1;
        } catch (Exception e) {
            getErrPrintWriter().println("Error while executing command: " + cmd);
            e.printStackTrace(getErrPrintWriter());
            return -1;
        }
    }

    private void runVerify() {
        // The command is only run if the credential is correct.
        getOutPrintWriter().println("Lock credential verified successfully");
    }

    @Override
    public void onHelp() {
        try (final PrintWriter pw = getOutPrintWriter();) {
            pw.println("lockSettings service commands:");
            pw.println("");
            pw.println("NOTE: when lock screen is set, all commands require the --old <CREDENTIAL>"
                    + " argument.");
            pw.println("");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  get-disabled [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Checks whether lock screen is disabled.");
            pw.println("");
            pw.println("  set-disabled [--old <CREDENTIAL>] [--user USER_ID] <true|false>");
            pw.println("    When true, disables lock screen.");
            pw.println("");
            pw.println("  set-pattern [--old <CREDENTIAL>] [--user USER_ID] <PATTERN>");
            pw.println("    Sets the lock screen as pattern, using the given PATTERN to unlock.");
            pw.println("");
            pw.println("  set-pin [--old <CREDENTIAL>] [--user USER_ID] <PIN>");
            pw.println("    Sets the lock screen as PIN, using the given PIN to unlock.");
            pw.println("");
            pw.println("  set-password [--old <CREDENTIAL>] [--user USER_ID] <PASSWORD>");
            pw.println("    Sets the lock screen as password, using the given PASSOWRD to unlock.");
            pw.println("");
            pw.println("  clear [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Clears the lock credentials.");
            pw.println("");
            pw.println("  verify [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Verifies the lock credentials.");
            pw.println("");
            pw.println("  remove-cache [--user USER_ID]");
            pw.println("    Removes cached unified challenge for the managed profile.");
            pw.println("");
            pw.println("  set-resume-on-reboot-provider-package <package_name>");
            pw.println("    Sets the package name for server based resume on reboot service "
                    + "provider.");
            pw.println("");
            pw.println("  require-strong-auth [--user USER_ID] <reason>");
            pw.println("    Requires the strong authentication. The current supported reasons: "
                    + "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN.");
            pw.println("");
        }
    }

    private void parseArgs() {
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("--old".equals(opt)) {
                mOld = getNextArgRequired();
            } else if ("--user".equals(opt)) {
                mCurrentUserId = UserHandle.parseUserArg(getNextArgRequired());
                if (mCurrentUserId == UserHandle.USER_CURRENT) {
                    mCurrentUserId = ActivityManager.getCurrentUser();
                }
            } else {
                getErrPrintWriter().println("Unknown option: " + opt);
                throw new IllegalArgumentException();
            }
        }
        mNew = getNextArg();
    }

    private LockscreenCredential getOldCredential() {
        if (TextUtils.isEmpty(mOld)) {
            return LockscreenCredential.createNone();
        }
        if (mLockPatternUtils.isLockPasswordEnabled(mCurrentUserId)) {
            final int quality = mLockPatternUtils.getKeyguardStoredPasswordQuality(mCurrentUserId);
            if (LockPatternUtils.isQualityAlphabeticPassword(quality)) {
                return LockscreenCredential.createPassword(mOld);
            } else {
                return LockscreenCredential.createPin(mOld);
            }
        }
        if (mLockPatternUtils.isLockPatternEnabled(mCurrentUserId)) {
            return LockscreenCredential.createPattern(LockPatternUtils.byteArrayToPattern(
                    mOld.getBytes()));
        }
        // User supplied some old credential but the device has neither password nor pattern,
        // so just return a password credential (and let it be rejected during LSS verification)
        return LockscreenCredential.createPassword(mOld);

    }

    private boolean runSetPattern() {
        final LockscreenCredential pattern = LockscreenCredential.createPattern(
                LockPatternUtils.byteArrayToPattern(mNew.getBytes()));
        if (!isNewCredentialSufficient(pattern)) {
            return false;
        }
        mLockPatternUtils.setLockCredential(pattern, getOldCredential(), mCurrentUserId);
        getOutPrintWriter().println("Pattern set to '" + mNew + "'");
        return true;
    }

    private boolean runSetPassword() {
        final LockscreenCredential password = LockscreenCredential.createPassword(mNew);
        if (!isNewCredentialSufficient(password)) {
            return false;
        }
        mLockPatternUtils.setLockCredential(password, getOldCredential(), mCurrentUserId);
        getOutPrintWriter().println("Password set to '" + mNew + "'");
        return true;
    }

    private boolean runSetPin() {
        final LockscreenCredential pin = LockscreenCredential.createPin(mNew);
        if (!isNewCredentialSufficient(pin)) {
            return false;
        }
        mLockPatternUtils.setLockCredential(pin, getOldCredential(), mCurrentUserId);
        getOutPrintWriter().println("Pin set to '" + mNew + "'");
        return true;
    }

    private boolean runSetResumeOnRebootProviderPackage() {
        final String packageName = mNew;
        String name = ResumeOnRebootServiceProvider.PROP_ROR_PROVIDER_PACKAGE;
        Slog.i(TAG, "Setting " +  name + " to " + packageName);

        mContext.enforcePermission(android.Manifest.permission.BIND_RESUME_ON_REBOOT_SERVICE,
                mCallingPid, mCallingUid, TAG);
        SystemProperties.set(name, packageName);
        return true;
    }

    private boolean runRequireStrongAuth() {
        final String reason = mNew;
        int strongAuthReason;
        switch (reason) {
            case "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN":
                strongAuthReason = STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
                mCurrentUserId = UserHandle.USER_ALL;
                break;
            default:
                getErrPrintWriter().println("Unsupported reason: " + reason);
                return false;
        }
        mLockPatternUtils.requireStrongAuth(strongAuthReason, mCurrentUserId);
        getOutPrintWriter().println("Require strong auth for USER_ID "
                + mCurrentUserId + " because of " + mNew);
        return true;
    }

    private boolean runClear() {
        LockscreenCredential none = LockscreenCredential.createNone();
        if (!isNewCredentialSufficient(none)) {
            return false;
        }
        mLockPatternUtils.setLockCredential(none, getOldCredential(), mCurrentUserId);
        getOutPrintWriter().println("Lock credential cleared");
        return true;
    }

    private boolean isNewCredentialSufficient(LockscreenCredential credential) {
        final PasswordMetrics requiredMetrics =
                mLockPatternUtils.getRequestedPasswordMetrics(mCurrentUserId);
        final int requiredComplexity =
                mLockPatternUtils.getRequestedPasswordComplexity(mCurrentUserId);
        final List<PasswordValidationError> errors =
                PasswordMetrics.validateCredential(requiredMetrics, requiredComplexity, credential);
        if (!errors.isEmpty()) {
            getOutPrintWriter().println(
                    "New credential doesn't satisfy admin policies: " + errors.get(0));
            return false;
        }
        return true;
    }

    private void runSetDisabled() {
        final boolean disabled = Boolean.parseBoolean(mNew);
        mLockPatternUtils.setLockScreenDisabled(disabled, mCurrentUserId);
        getOutPrintWriter().println("Lock screen disabled set to " + disabled);
    }

    private void runGetDisabled() {
        boolean isLockScreenDisabled = mLockPatternUtils.isLockScreenDisabled(mCurrentUserId);
        getOutPrintWriter().println(isLockScreenDisabled);
    }

    private boolean checkCredential() {
        if (mLockPatternUtils.isSecure(mCurrentUserId)) {
            if (mLockPatternUtils.isManagedProfileWithUnifiedChallenge(mCurrentUserId)) {
                getOutPrintWriter().println("Profile uses unified challenge");
                return false;
            }

            try {
                final boolean result = mLockPatternUtils.checkCredential(getOldCredential(),
                        mCurrentUserId, null);
                if (!result) {
                    if (!mLockPatternUtils.isManagedProfileWithUnifiedChallenge(mCurrentUserId)) {
                        mLockPatternUtils.reportFailedPasswordAttempt(mCurrentUserId);
                    }
                    getOutPrintWriter().println("Old password '" + mOld + "' didn't match");
                } else {
                    // Resets the counter for failed password attempts to 0.
                    mLockPatternUtils.reportSuccessfulPasswordAttempt(mCurrentUserId);
                }
                return result;
            } catch (RequestThrottledException e) {
                getOutPrintWriter().println("Request throttled");
                return false;
            }
        } else {
            if (!mOld.isEmpty()) {
                getOutPrintWriter().println("Old password provided but user has no password");
                return false;
            }
            return true;
        }
    }

    private void runRemoveCache() {
        mLockPatternUtils.removeCachedUnifiedChallenge(mCurrentUserId);
        getOutPrintWriter().println("Password cached removed for user " + mCurrentUserId);
    }
}
