/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;

final class StrictModeViolationDialog extends BaseErrorDialog {
    private final static String TAG = "StrictModeViolationDialog";

    private final ActivityManagerService mService;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;

    // Event 'what' codes
    static final int ACTION_OK = 0;
    static final int ACTION_OK_AND_REPORT = 1;

    // 1-minute timeout, then we automatically dismiss the violation
    // dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 1;

    public StrictModeViolationDialog(Context context, ActivityManagerService service,
            AppErrorResult result, ProcessRecord app) {
        super(context);

        Resources res = context.getResources();

        mService = service;
        mProc = app;
        mResult = result;
        CharSequence name;
        if ((app.pkgList.size() == 1) &&
                (name=context.getPackageManager().getApplicationLabel(app.info)) != null) {
            setMessage(res.getString(
                    com.android.internal.R.string.smv_application,
                    name.toString(), app.info.processName));
        } else {
            name = app.processName;
            setMessage(res.getString(
                    com.android.internal.R.string.smv_process,
                    name.toString()));
        }

        setCancelable(false);

        setButton(DialogInterface.BUTTON_POSITIVE,
                  res.getText(com.android.internal.R.string.dlg_ok),
                  mHandler.obtainMessage(ACTION_OK));

        if (app.errorReportReceiver != null) {
            setButton(DialogInterface.BUTTON_NEGATIVE,
                      res.getText(com.android.internal.R.string.report),
                      mHandler.obtainMessage(ACTION_OK_AND_REPORT));
        }

        getWindow().addPrivateFlags(PRIVATE_FLAG_SYSTEM_ERROR);
        getWindow().setTitle("Strict Mode Violation: " + app.info.processName);

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(ACTION_OK),
                DISMISS_TIMEOUT);
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            synchronized (mService) {
                if (mProc != null) {
                    mProc.getDialogController().clearViolationDialogs();
                }
            }
            mResult.set(msg.what);

            // If this is a timeout we won't be automatically closed, so go
            // ahead and explicitly dismiss ourselves just in case.
            dismiss();
        }
    };
}
