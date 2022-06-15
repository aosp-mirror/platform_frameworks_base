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
import android.os.Handler;
import android.os.Message;
import android.view.WindowManager;

final class AppWaitingForDebuggerDialog extends BaseErrorDialog {
    final ActivityManagerService mService;
    final ProcessRecord mProc;
    private CharSequence mAppName;
    
    public AppWaitingForDebuggerDialog(ActivityManagerService service,
            Context context, ProcessRecord app) {
        super(context);
        mService = service;
        mProc = app;
        mAppName = context.getPackageManager().getApplicationLabel(app.info);

        setCancelable(false);

        StringBuilder text = new StringBuilder();
        if (mAppName != null && mAppName.length() > 0) {
            text.append("Application ");
            text.append(mAppName);
            text.append(" (process ");
            text.append(app.processName);
            text.append(")");
        } else {
            text.append("Process ");
            text.append(app.processName);
        }

        text.append(" is waiting for the debugger to attach.");

        setMessage(text.toString());
        setButton(DialogInterface.BUTTON_POSITIVE, "Force Close", mHandler.obtainMessage(1, app));
        setTitle("Waiting For Debugger");
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Waiting For Debugger: " + app.info.processName);
        getWindow().setAttributes(attrs);
    }
    
    public void onStop() {
    }

    @Override
    protected void closeDialog() {
        /* Do nothing */
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    // Kill the application.
                    mService.killAppAtUsersRequest(mProc);
                    break;
            }
        }
    };
}
