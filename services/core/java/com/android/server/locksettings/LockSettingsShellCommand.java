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

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static com.android.internal.widget.LockPatternUtils.stringToPattern;

import android.app.ActivityManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ShellCommand;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.RequestThrottledException;

class LockSettingsShellCommand extends ShellCommand {

    private static final String COMMAND_SET_PATTERN = "set-pattern";
    private static final String COMMAND_SET_PIN = "set-pin";
    private static final String COMMAND_SET_PASSWORD = "set-password";
    private static final String COMMAND_CLEAR = "clear";
    private static final String COMMAND_SP = "sp";
    private static final String COMMAND_SET_DISABLED = "set-disabled";
    private static final String COMMAND_VERIFY = "verify";
    private static final String COMMAND_GET_DISABLED = "get-disabled";

    private int mCurrentUserId;
    private final LockPatternUtils mLockPatternUtils;
    private final Context mContext;
    private String mOld = "";
    private String mNew = "";

    LockSettingsShellCommand(Context context, LockPatternUtils lockPatternUtils) {
        mContext = context;
        mLockPatternUtils = lockPatternUtils;
    }

    @Override
    public int onCommand(String cmd) {
        try {
            mCurrentUserId = ActivityManager.getService().getCurrentUser().id;

            parseArgs();
            if (!checkCredential()) {
                return -1;
            }
            switch (cmd) {
                case COMMAND_SET_PATTERN:
                    runSetPattern();
                    break;
                case COMMAND_SET_PASSWORD:
                    runSetPassword();
                    break;
                case COMMAND_SET_PIN:
                    runSetPin();
                    break;
                case COMMAND_CLEAR:
                    runClear();
                    break;
                case COMMAND_SP:
                    runChangeSp();
                    break;
                case COMMAND_SET_DISABLED:
                    runSetDisabled();
                    break;
                case COMMAND_VERIFY:
                    runVerify();
                    break;
                case COMMAND_GET_DISABLED:
                    runGetDisabled();
                    break;
                default:
                    getErrPrintWriter().println("Unknown command: " + cmd);
                    break;
            }
            return 0;
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
    }

    private void parseArgs() {
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("--old".equals(opt)) {
                mOld = getNextArgRequired();
            } else if ("--user".equals(opt)) {
                mCurrentUserId = Integer.parseInt(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Unknown option: " + opt);
                throw new IllegalArgumentException();
            }
        }
        mNew = getNextArg();
    }

    private void runChangeSp() {
        if (mNew != null ) {
            if ("1".equals(mNew)) {
                mLockPatternUtils.enableSyntheticPassword();
                getOutPrintWriter().println("Synthetic password enabled");
            } else if ("0".equals(mNew)) {
                mLockPatternUtils.disableSyntheticPassword();
                getOutPrintWriter().println("Synthetic password disabled");
            }
        }
        getOutPrintWriter().println(String.format("SP Enabled = %b",
                mLockPatternUtils.isSyntheticPasswordEnabled()));
    }

    private void runSetPattern() {
        byte[] oldBytes = mOld != null ? mOld.getBytes() : null;
        mLockPatternUtils.saveLockPattern(stringToPattern(mNew), oldBytes, mCurrentUserId);
        getOutPrintWriter().println("Pattern set to '" + mNew + "'");
    }

    private void runSetPassword() {
        byte[] newBytes = mNew != null ? mNew.getBytes() : null;
        byte[] oldBytes = mOld != null ? mOld.getBytes() : null;
        mLockPatternUtils.saveLockPassword(newBytes, oldBytes, PASSWORD_QUALITY_ALPHABETIC,
                mCurrentUserId);
        getOutPrintWriter().println("Password set to '" + mNew + "'");
    }

    private void runSetPin() {
        byte[] newBytes = mNew != null ? mNew.getBytes() : null;
        byte[] oldBytes = mOld != null ? mOld.getBytes() : null;
        mLockPatternUtils.saveLockPassword(newBytes, oldBytes, PASSWORD_QUALITY_NUMERIC,
                mCurrentUserId);
        getOutPrintWriter().println("Pin set to '" + mNew + "'");
    }

    private void runClear() {
        byte[] oldBytes = mOld != null ? mOld.getBytes() : null;
        mLockPatternUtils.clearLock(oldBytes, mCurrentUserId);
        getOutPrintWriter().println("Lock credential cleared");
    }

    private void runSetDisabled() throws RemoteException {
        final boolean disabled = Boolean.parseBoolean(mNew);
        mLockPatternUtils.setLockScreenDisabled(disabled, mCurrentUserId);
        getOutPrintWriter().println("Lock screen disabled set to " + disabled);
    }

    private void runGetDisabled() {
        boolean isLockScreenDisabled = mLockPatternUtils.isLockScreenDisabled(mCurrentUserId);
        getOutPrintWriter().println(isLockScreenDisabled);
    }

    private boolean checkCredential() throws RemoteException {
        final boolean havePassword = mLockPatternUtils.isLockPasswordEnabled(mCurrentUserId);
        final boolean havePattern = mLockPatternUtils.isLockPatternEnabled(mCurrentUserId);
        if (havePassword || havePattern) {
            if (mLockPatternUtils.isManagedProfileWithUnifiedChallenge(mCurrentUserId)) {
                getOutPrintWriter().println("Profile uses unified challenge");
                return false;
            }

            try {
                final boolean result;
                if (havePassword) {
                    byte[] passwordBytes = mOld != null ? mOld.getBytes() : null;
                    result = mLockPatternUtils.checkPassword(passwordBytes, mCurrentUserId);
                } else {
                    result = mLockPatternUtils.checkPattern(stringToPattern(mOld), mCurrentUserId);
                }
                if (!result) {
                    if (!mLockPatternUtils.isManagedProfileWithUnifiedChallenge(mCurrentUserId)) {
                        mLockPatternUtils.reportFailedPasswordAttempt(mCurrentUserId);
                    }
                    getOutPrintWriter().println("Old password '" + mOld + "' didn't match");
                }
                return result;
            } catch (RequestThrottledException e) {
                getOutPrintWriter().println("Request throttled");
                return false;
            }
        } else {
            return true;
        }
    }
}
