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

import static android.view.WindowManager.LayoutParams.FLAG_SYSTEM_ERROR;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.util.Log;

class AppNotRespondingDialog extends BaseErrorDialog {
    private final ActivityManagerService mService;
    private final ProcessRecord mProc;
    
    public AppNotRespondingDialog(ActivityManagerService service, Context context,
            ProcessRecord app, HistoryRecord activity) {
        super(context);
        
        mService = service;
        mProc = app;
        Resources res = context.getResources();
        
        setCancelable(false);

        int resid;
        CharSequence name1 = activity != null
                ? activity.info.loadLabel(context.getPackageManager())
                : null;
        CharSequence name2 = null;
        if ((app.pkgList.size() == 1) &&
                (name2=context.getPackageManager().getApplicationLabel(app.info)) != null) {
            if (name1 != null) {
                resid = com.android.internal.R.string.anr_activity_application;
            } else {
                name1 = name2;
                name2 = app.processName;
                resid = com.android.internal.R.string.anr_application_process;
            }
        } else {
            if (name1 != null) {
                name2 = app.processName;
                resid = com.android.internal.R.string.anr_activity_process;
            } else {
                name1 = app.processName;
                resid = com.android.internal.R.string.anr_process;
            }
        }

        setMessage(name2 != null
                ? res.getString(resid, name1.toString(), name2.toString())
                : res.getString(resid, name1.toString()));

        setButton(res.getText(com.android.internal.R.string.force_close),
                mHandler.obtainMessage(1));
        setButton2(res.getText(com.android.internal.R.string.wait),
                mHandler.obtainMessage(2));
        setTitle(res.getText(com.android.internal.R.string.anr_title));
        getWindow().addFlags(FLAG_SYSTEM_ERROR);
        getWindow().setTitle("Application Not Responding: " + app.info.processName);
    }

    public void onStop() {
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    // Kill the application.
                    mService.killAppAtUsersRequest(mProc,
                            AppNotRespondingDialog.this, true);
                    break;
                case 2:
                    // Continue waiting for the application.
                    synchronized (mService) {
                        ProcessRecord app = mProc;
                        app.notResponding = false;
                        app.notRespondingReport = null;
                        if (app.anrDialog == AppNotRespondingDialog.this) {
                            app.anrDialog = null;
                        }
                    }
                    break;
            }
        }
    };
}
