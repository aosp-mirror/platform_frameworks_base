/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.Nullable;
import android.app.AnrController;
import android.app.Dialog;
import android.content.Context;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A controller to generate error dialogs in {@link ProcessRecord}.
 */
final class ErrorDialogController {
    private final ProcessRecord mApp;
    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;

    /**
     * Dialogs being displayed due to crash.
     */
    @GuardedBy("mProcLock")
    private List<AppErrorDialog> mCrashDialogs;

    /**
     * Dialogs being displayed due to app not responding.
     */
    @GuardedBy("mProcLock")
    private List<AppNotRespondingDialog> mAnrDialogs;

    /**
     * Dialogs displayed due to strict mode violation.
     */
    @GuardedBy("mProcLock")
    private List<StrictModeViolationDialog> mViolationDialogs;

    /**
     * Current wait for debugger dialog.
     */
    @GuardedBy("mProcLock")
    private AppWaitingForDebuggerDialog mWaitDialog;

    /**
     * ANR dialog controller
     */
    @GuardedBy("mProcLock")
    @Nullable
    private AnrController mAnrController;

    @GuardedBy("mProcLock")
    boolean hasCrashDialogs() {
        return mCrashDialogs != null;
    }

    @GuardedBy("mProcLock")
    List<AppErrorDialog> getCrashDialogs() {
        return mCrashDialogs;
    }

    @GuardedBy("mProcLock")
    boolean hasAnrDialogs() {
        return mAnrDialogs != null;
    }

    @GuardedBy("mProcLock")
    List<AppNotRespondingDialog> getAnrDialogs() {
        return mAnrDialogs;
    }

    @GuardedBy("mProcLock")
    boolean hasViolationDialogs() {
        return mViolationDialogs != null;
    }

    @GuardedBy("mProcLock")
    boolean hasDebugWaitingDialog() {
        return mWaitDialog != null;
    }

    @GuardedBy("mProcLock")
    void clearAllErrorDialogs() {
        clearCrashDialogs();
        clearAnrDialogs();
        clearViolationDialogs();
        clearWaitingDialog();
    }

    @GuardedBy("mProcLock")
    void clearCrashDialogs() {
        clearCrashDialogs(true /* needDismiss */);
    }

    @GuardedBy("mProcLock")
    void clearCrashDialogs(boolean needDismiss) {
        if (mCrashDialogs == null) {
            return;
        }
        if (needDismiss) {
            scheduleForAllDialogs(mCrashDialogs, Dialog::dismiss);
        }
        mCrashDialogs = null;
    }

    @GuardedBy("mProcLock")
    void clearAnrDialogs() {
        if (mAnrDialogs == null) {
            return;
        }
        scheduleForAllDialogs(mAnrDialogs, Dialog::dismiss);
        mAnrDialogs = null;
        mAnrController = null;
    }

    @GuardedBy("mProcLock")
    void clearViolationDialogs() {
        if (mViolationDialogs == null) {
            return;
        }
        scheduleForAllDialogs(mViolationDialogs, Dialog::dismiss);
        mViolationDialogs = null;
    }

    @GuardedBy("mProcLock")
    void clearWaitingDialog() {
        if (mWaitDialog == null) {
            return;
        }
        mWaitDialog.dismiss();
        mWaitDialog = null;
    }

    @GuardedBy("mProcLock")
    void scheduleForAllDialogs(List<? extends BaseErrorDialog> dialogs,
            Consumer<BaseErrorDialog> c) {
        mService.mUiHandler.post(() -> {
            if (dialogs != null) {
                forAllDialogs(dialogs, c);
            }
        });
    }

    void forAllDialogs(List<? extends BaseErrorDialog> dialogs, Consumer<BaseErrorDialog> c) {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            c.accept(dialogs.get(i));
        }
    }

    @GuardedBy("mProcLock")
    void showCrashDialogs(AppErrorDialog.Data data) {
        List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
        mCrashDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mCrashDialogs.add(new AppErrorDialog(c, mService, data));
        }
        mService.mUiHandler.post(() -> {
            List<AppErrorDialog> dialogs;
            synchronized (mProcLock) {
                dialogs = mCrashDialogs;
            }
            if (dialogs != null) {
                forAllDialogs(dialogs, Dialog::show);
            }
        });
    }

    @GuardedBy("mProcLock")
    void showAnrDialogs(AppNotRespondingDialog.Data data) {
        List<Context> contexts = getDisplayContexts(
                mApp.mErrorState.isSilentAnr() /* lastUsedOnly */);
        mAnrDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mAnrDialogs.add(new AppNotRespondingDialog(mService, c, data));
        }
        scheduleForAllDialogs(mAnrDialogs, Dialog::show);
    }

    @GuardedBy("mProcLock")
    void showViolationDialogs(AppErrorResult res) {
        List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
        mViolationDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mViolationDialogs.add(
                    new StrictModeViolationDialog(c, mService, res, mApp));
        }
        scheduleForAllDialogs(mViolationDialogs, Dialog::show);
    }

    @GuardedBy("mProcLock")
    void showDebugWaitingDialogs() {
        List<Context> contexts = getDisplayContexts(true /* lastUsedOnly */);
        final Context c = contexts.get(0);
        mWaitDialog = new AppWaitingForDebuggerDialog(mService, c, mApp);

        mService.mUiHandler.post(() -> {
            Dialog dialog;
            synchronized (mProcLock) {
                dialog = mWaitDialog;
            }
            if (dialog != null) {
                dialog.show();
            }
        });
    }

    @GuardedBy("mProcLock")
    @Nullable
    AnrController getAnrController() {
        return mAnrController;
    }

    @GuardedBy("mProcLock")
    void setAnrController(AnrController controller) {
        mAnrController = controller;
    }

    /**
     * Helper function to collect contexts from crashed app located displays.
     *
     * @param lastUsedOnly Sets to {@code true} to indicate to only get last used context.
     *                     Sets to {@code false} to collect contexts from crashed app located
     *                     displays.
     *
     * @return display context list.
     */
    private List<Context> getDisplayContexts(boolean lastUsedOnly) {
        List<Context> displayContexts = new ArrayList<>();
        if (!lastUsedOnly) {
            mApp.getWindowProcessController().getDisplayContextsWithErrorDialogs(displayContexts);
        }
        // If there is no foreground window display, fallback to last used display.
        if (displayContexts.isEmpty() || lastUsedOnly) {
            displayContexts.add(mService.mWmInternal != null
                    ? mService.mWmInternal.getTopFocusedDisplayUiContext()
                    : mService.mUiContext);
        }
        return displayContexts;
    }

    ErrorDialogController(ProcessRecord app) {
        mApp = app;
        mService = app.mService;
        mProcLock = mService.mProcLock;
    }
}
