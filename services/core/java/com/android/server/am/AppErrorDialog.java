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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import com.android.internal.util.DogbinUtils;
import com.android.internal.util.DogbinUtils.UploadResultCallback;

import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {

    private static final String TAG = "AppErrorDialog";

    private final ActivityManagerService mService;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;
    private final boolean mIsRestartable;
    private String mPaste;

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
    static final int APP_INFO = 8;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        Resources res = context.getResources();

        mService = service;
        mProc = data.proc;
        mResult = data.result;
        mIsRestartable = (data.taskId != INVALID_TASK_ID || data.isRestartableForService)
                && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG, 0) != 0;
        mPaste = data.paste;
        BidiFormatter bidi = BidiFormatter.getInstance();

        CharSequence name;
        if ((mProc.pkgList.size() == 1) &&
                (name = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            setTitle(res.getString(
                    data.repeating ? com.android.internal.R.string.aerr_application_repeated
                            : com.android.internal.R.string.aerr_application,
                    bidi.unicodeWrap(name.toString()),
                    bidi.unicodeWrap(mProc.info.processName)));
        } else {
            name = mProc.processName;
            setTitle(res.getString(
                    data.repeating ? com.android.internal.R.string.aerr_process_repeated
                            : com.android.internal.R.string.aerr_process,
                    bidi.unicodeWrap(name.toString())));
        }

        setCancelable(true);
        setCancelMessage(mHandler.obtainMessage(CANCEL));

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + mProc.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (mProc.isPersistent()) {
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
        final FrameLayout frame = findViewById(android.R.id.custom);
        final Context context = getContext();
        LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.app_error_dialog, frame, true);

        final boolean hasReceiver = mProc.errorReportReceiver != null;

        final TextView restart = findViewById(com.android.internal.R.id.aerr_restart);
        restart.setOnClickListener(this);
        restart.setVisibility(mIsRestartable ? View.VISIBLE : View.GONE);
        final TextView report = findViewById(com.android.internal.R.id.aerr_report);
        report.setOnClickListener(this);
        report.setVisibility(hasReceiver ? View.VISIBLE : View.GONE);
        final TextView copy = findViewById(com.android.internal.R.id.aerr_copy);
        copy.setOnClickListener(this);
        final TextView close = findViewById(com.android.internal.R.id.aerr_close);
        close.setOnClickListener(this);
        final TextView appInfo = findViewById(com.android.internal.R.id.aerr_app_info);
        appInfo.setOnClickListener(this);

        boolean showMute = !Build.IS_USER && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_MUTE_IN_CRASH_DIALOG, 0) != 0;
        final TextView mute = findViewById(com.android.internal.R.id.aerr_mute);
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
            setResult(msg.what);
            dismiss();
        }
    };

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            setResult(FORCE_QUIT);
        }
        super.dismiss();
    }

    private void setResult(int result) {
        synchronized (mService) {
            if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
                mProc.crashDialog = null;
            }
        }
        mResult.set(result);

        // Make sure we don't have time timeout still hanging around.
        mHandler.removeMessages(TIMEOUT);
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
            case com.android.internal.R.id.aerr_copy:
                postToDogbinAndCopyURL();
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_app_info:
                mHandler.obtainMessage(APP_INFO).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_mute:
                mHandler.obtainMessage(MUTE).sendToTarget();
                break;
            default:
                break;
        }
    }

    private void postToDogbinAndCopyURL() {
        // Post to dogbin
        DogbinUtils.upload(mPaste, new UploadResultCallback() {
            public void onSuccess(String url) {
                // Copy to clipboard
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Log URL", url));

                // Show toast
                Toast.makeText(getContext(), com.android.internal.R.string.url_copy_success, Toast.LENGTH_LONG).show();
            }

            public void onFail(String message, Exception e) {
                Toast.makeText(getContext(), com.android.internal.R.string.url_copy_failed, Toast.LENGTH_LONG).show();
                Log.e(TAG, message, e);
            }
        });
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
        int taskId;
        boolean repeating;
        ProcessRecord proc;
        boolean isRestartableForService;
        String paste;
    }
}
