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

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.view.WindowManager;

final class AppErrorDialog extends BaseErrorDialog {
    private final ActivityManagerService mService;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;

    // Event 'what' codes
    static final int FORCE_QUIT = 0;
    static final int FORCE_QUIT_AND_REPORT = 1;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;
    
    public AppErrorDialog(Context context, ActivityManagerService service,
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
                    com.android.internal.R.string.aerr_application,
                    name.toString(), app.info.processName));
        } else {
            name = app.processName;
            setMessage(res.getString(
                    com.android.internal.R.string.aerr_process,
                    name.toString()));
        }

        setCancelable(false);

        setButton(DialogInterface.BUTTON_POSITIVE,
                res.getText(com.android.internal.R.string.force_close),
                mHandler.obtainMessage(FORCE_QUIT));

        if (app.errorReportReceiver != null) {
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    res.getText(com.android.internal.R.string.report),
                    mHandler.obtainMessage(FORCE_QUIT_AND_REPORT));
        }

        setTitle(res.getText(com.android.internal.R.string.aerr_title));
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + app.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (app.persistent) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(FORCE_QUIT),
                DISMISS_TIMEOUT);
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            synchronized (mService) {
                if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
                    mProc.crashDialog = null;
                }
            }
            mResult.set(msg.what);

            // Make sure we don't have time timeout still hanging around.
            removeMessages(FORCE_QUIT);

            // If this is a timeout we won't be automatically closed, so go
            // ahead and explicitly dismiss ourselves just in case.
            dismiss();
        }
    };

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            mResult.set(FORCE_QUIT);
        }
        super.dismiss();
    }
}
