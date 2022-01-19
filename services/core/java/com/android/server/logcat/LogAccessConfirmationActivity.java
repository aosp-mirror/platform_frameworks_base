/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.logcat;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.logcat.ILogcatManagerService;
import android.util.Slog;
import android.view.View;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;


/**
 * This dialog is shown to the user before an activity in a harmful app is launched.
 *
 * See {@code PackageManager.setLogcatAppInfo} for more info.
 */
public class LogAccessConfirmationActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    private static final String TAG = LogAccessConfirmationActivity.class.getSimpleName();

    private String mPackageName;
    private IntentSender mTarget;
    private final ILogcatManagerService mLogcatManagerService =
            ILogcatManagerService.Stub.asInterface(ServiceManager.getService("logcat"));

    private int mUid;
    private int mGid;
    private int mPid;
    private int mFd;

    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_GID = "gid";
    private static final String EXTRA_PID = "pid";
    private static final String EXTRA_FD = "fd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        mUid = intent.getIntExtra("uid", 0);
        mGid = intent.getIntExtra("gid", 0);
        mPid = intent.getIntExtra("pid", 0);
        mFd = intent.getIntExtra("fd", 0);

        final AlertController.AlertParams p = mAlertParams;
        p.mTitle = getString(R.string.log_access_confirmation_title);
        p.mView = createView();

        p.mPositiveButtonText = getString(R.string.log_access_confirmation_allow);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(R.string.log_access_confirmation_deny);
        p.mNegativeButtonListener = this;

        mAlert.installContent(mAlertParams);
    }

    private View createView() {
        final View view = getLayoutInflater().inflate(R.layout.harmful_app_warning_dialog,
                null /*root*/);
        ((TextView) view.findViewById(R.id.app_name_text))
                .setText(mPackageName);
        ((TextView) view.findViewById(R.id.message))
                .setText(getIntent().getExtras().getString("body"));
        return view;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                try {
                    mLogcatManagerService.approve(mUid, mGid, mPid, mFd);
                } catch (Throwable t) {
                    Slog.e(TAG, "Could not start the LogcatManagerService.", t);
                }
                finish();
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                try {
                    mLogcatManagerService.decline(mUid, mGid, mPid, mFd);
                } catch (Throwable t) {
                    Slog.e(TAG, "Could not start the LogcatManagerService.", t);
                }
                finish();
                break;
        }
    }

    /**
     * Create the Intent for a LogAccessConfirmationActivity.
     */
    public static Intent createIntent(Context context, String targetPackageName,
            IntentSender target, int uid, int gid, int pid, int fd) {
        final Intent intent = new Intent();
        intent.setClass(context, LogAccessConfirmationActivity.class);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, targetPackageName);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_GID, gid);
        intent.putExtra(EXTRA_PID, pid);
        intent.putExtra(EXTRA_FD, fd);

        return intent;
    }

}
