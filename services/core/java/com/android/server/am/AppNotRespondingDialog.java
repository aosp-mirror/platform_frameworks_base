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

import android.content.pm.ApplicationInfo;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.BidiFormatter;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

final class AppNotRespondingDialog extends BaseErrorDialog implements View.OnClickListener {
    private static final String TAG = "AppNotRespondingDialog";

    // Event 'what' codes
    static final int FORCE_CLOSE = 1;
    static final int WAIT = 2;
    static final int WAIT_AND_REPORT = 3;

    public static final int CANT_SHOW = -1;
    public static final int ALREADY_SHOWING = -2;

    private final ActivityManagerService mService;
    private final ProcessRecord mProc;

    public AppNotRespondingDialog(ActivityManagerService service, Context context, Data data) {
        super(context);

        mService = service;
        mProc = data.proc;
        Resources res = context.getResources();

        setCancelable(false);

        int resid;
        CharSequence name1 = data.aInfo != null
                ? data.aInfo.loadLabel(context.getPackageManager())
                : null;
        CharSequence name2 = null;
        if ((mProc.pkgList.size() == 1) &&
                (name2=context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            if (name1 != null) {
                resid = com.android.internal.R.string.anr_activity_application;
            } else {
                name1 = name2;
                name2 = mProc.processName;
                resid = com.android.internal.R.string.anr_application_process;
            }
        } else {
            if (name1 != null) {
                name2 = mProc.processName;
                resid = com.android.internal.R.string.anr_activity_process;
            } else {
                name1 = mProc.processName;
                resid = com.android.internal.R.string.anr_process;
            }
        }

        BidiFormatter bidi = BidiFormatter.getInstance();

        setTitle(name2 != null
                ? res.getString(resid, bidi.unicodeWrap(name1.toString()), bidi.unicodeWrap(name2.toString()))
                : res.getString(resid, bidi.unicodeWrap(name1.toString())));

        if (data.aboveSystem) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Not Responding: " + mProc.info.processName);
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final FrameLayout frame = findViewById(android.R.id.custom);
        final Context context = getContext();
        LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.app_anr_dialog, frame, true);

        final TextView report = findViewById(com.android.internal.R.id.aerr_report);
        report.setOnClickListener(this);
        final boolean hasReceiver = mProc.errorReportReceiver != null;
        report.setVisibility(hasReceiver ? View.VISIBLE : View.GONE);
        final TextView close = findViewById(com.android.internal.R.id.aerr_close);
        close.setOnClickListener(this);
        final TextView wait = findViewById(com.android.internal.R.id.aerr_wait);
        wait.setOnClickListener(this);

        findViewById(com.android.internal.R.id.customPanel).setVisibility(View.VISIBLE);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case com.android.internal.R.id.aerr_report:
                mHandler.obtainMessage(WAIT_AND_REPORT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_CLOSE).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_wait:
                mHandler.obtainMessage(WAIT).sendToTarget();
                break;
            default:
                break;
        }
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            Intent appErrorIntent = null;

            MetricsLogger.action(getContext(), MetricsProto.MetricsEvent.ACTION_APP_ANR,
                    msg.what);

            switch (msg.what) {
                case FORCE_CLOSE:
                    // Kill the application.
                    mService.killAppAtUsersRequest(mProc, AppNotRespondingDialog.this);
                    break;
                case WAIT_AND_REPORT:
                case WAIT:
                    // Continue waiting for the application.
                    synchronized (mService) {
                        ProcessRecord app = mProc;

                        if (msg.what == WAIT_AND_REPORT) {
                            appErrorIntent = mService.mAppErrors.createAppErrorIntentLocked(app,
                                    System.currentTimeMillis(), null);
                        }

                        app.setNotResponding(false);
                        app.notRespondingReport = null;
                        if (app.anrDialog == AppNotRespondingDialog.this) {
                            app.anrDialog = null;
                        }
                        mService.mServices.scheduleServiceTimeoutLocked(app);
                    }
                    break;
            }

            if (appErrorIntent != null) {
                try {
                    getContext().startActivity(appErrorIntent);
                } catch (ActivityNotFoundException e) {
                    Slog.w(TAG, "bug report receiver dissappeared", e);
                }
            }

            dismiss();
        }
    };

    static class Data {
        final ProcessRecord proc;
        final ApplicationInfo aInfo;
        final boolean aboveSystem;

        Data(ProcessRecord proc, ApplicationInfo aInfo, boolean aboveSystem) {
            this.proc = proc;
            this.aInfo = aInfo;
            this.aboveSystem = aboveSystem;
        }
    }
}
