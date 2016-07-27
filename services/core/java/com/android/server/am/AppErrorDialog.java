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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import static com.android.server.am.ActivityManagerService.IS_USER_BUILD;

final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {

    private final ActivityManagerService mService;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;
    private final boolean mRepeating;
    private final boolean mIsRestartable;

    private CharSequence mName;

    static int CANT_SHOW = -1;
    static int BACKGROUND_USER = -2;
    static int ALREADY_SHOWING = -3;

    // Event 'what' codes
    static final int FORCE_QUIT = 1;
    static final int FORCE_QUIT_AND_REPORT = 2;
    static final int RESTART = 3;
    static final int MUTE = 5;
    static final int TIMEOUT = 6;
    static final int CANCEL = 7;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        Resources res = context.getResources();

        mService = service;
        mProc = data.proc;
        mResult = data.result;
        mRepeating = data.repeating;
        mIsRestartable = data.task != null || data.isRestartableForService;
        BidiFormatter bidi = BidiFormatter.getInstance();

        if ((mProc.pkgList.size() == 1) &&
                (mName = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            setTitle(res.getString(
                    mRepeating ? com.android.internal.R.string.aerr_application_repeated
                            : com.android.internal.R.string.aerr_application,
                    bidi.unicodeWrap(mName.toString()),
                    bidi.unicodeWrap(mProc.info.processName)));
        } else {
            mName = mProc.processName;
            setTitle(res.getString(
                    mRepeating ? com.android.internal.R.string.aerr_process_repeated
                            : com.android.internal.R.string.aerr_process,
                    bidi.unicodeWrap(mName.toString())));
        }

        setCancelable(true);
        setCancelMessage(mHandler.obtainMessage(CANCEL));

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + mProc.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (mProc.persistent) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(TIMEOUT),
                DISMISS_TIMEOUT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final FrameLayout frame = (FrameLayout) findViewById(android.R.id.custom);
        final Context context = getContext();
        LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.app_error_dialog, frame, true);

        boolean hasRestart = !mRepeating && mIsRestartable;
        final boolean hasReceiver = mProc.errorReportReceiver != null;

        final TextView restart = (TextView) findViewById(com.android.internal.R.id.aerr_restart);
        restart.setOnClickListener(this);
        restart.setVisibility(hasRestart ? View.VISIBLE : View.GONE);
        final TextView report = (TextView) findViewById(com.android.internal.R.id.aerr_report);
        report.setOnClickListener(this);
        report.setVisibility(hasReceiver ? View.VISIBLE : View.GONE);
        final TextView close = (TextView) findViewById(com.android.internal.R.id.aerr_close);
        close.setVisibility(!hasRestart ? View.VISIBLE : View.GONE);
        close.setOnClickListener(this);

        boolean showMute = !IS_USER_BUILD && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        final TextView mute = (TextView) findViewById(com.android.internal.R.id.aerr_mute);
        mute.setOnClickListener(this);
        mute.setVisibility(showMute ? View.VISIBLE : View.GONE);

        findViewById(com.android.internal.R.id.customPanel).setVisibility(View.VISIBLE);
    }

    @Override
    public void onStart() {
        super.onStart();
        getContext().registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContext().unregisterReceiver(mReceiver);
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            final int result = msg.what;

            synchronized (mService) {
                if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
                    mProc.crashDialog = null;
                }
            }
            mResult.set(result);

            // Make sure we don't have time timeout still hanging around.
            removeMessages(TIMEOUT);

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

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case com.android.internal.R.id.aerr_restart:
                mHandler.obtainMessage(RESTART).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_report:
                mHandler.obtainMessage(FORCE_QUIT_AND_REPORT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_mute:
                mHandler.obtainMessage(MUTE).sendToTarget();
                break;
            default:
                break;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                cancel();
            }
        }
    };

    static class Data {
        AppErrorResult result;
        TaskRecord task;
        boolean repeating;
        ProcessRecord proc;
        boolean isRestartableForService;
    }
}
