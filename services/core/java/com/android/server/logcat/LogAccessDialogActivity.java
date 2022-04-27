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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.logcat.ILogcatManagerService;
import android.util.Slog;
import android.view.InflateException;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.R;

/**
 * Dialog responsible for obtaining user consent per-use log access
 */
public class LogAccessDialogActivity extends Activity implements
        View.OnClickListener {
    private static final String TAG = LogAccessDialogActivity.class.getSimpleName();
    private Context mContext;

    private final ILogcatManagerService mLogcatManagerService =
            ILogcatManagerService.Stub.asInterface(ServiceManager.getService("logcat"));

    private String mPackageName;

    private int mUid;
    private int mGid;
    private int mPid;
    private int mFd;
    private String mAlertTitle;
    private AlertDialog.Builder mAlertDialog;
    private AlertDialog mAlert;
    private View mAlertView;

    private static final int DIALOG_TIME_OUT = Build.IS_DEBUGGABLE ? 60000 : 300000;
    private static final int MSG_DISMISS_DIALOG = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            mContext = this;

            // retrieve Intent extra information
            Intent intent = getIntent();
            getIntentInfo(intent);

            // retrieve the title string from passed intent extra
            mAlertTitle = getTitleString(mContext, mPackageName, mUid);

            // creaet View
            mAlertView = createView();

            // create AlertDialog
            mAlertDialog = new AlertDialog.Builder(this);
            mAlertDialog.setView(mAlertView);

            // show Alert
            mAlert = mAlertDialog.create();
            mAlert.show();

            // set Alert Timeout
            mHandler.sendEmptyMessageDelayed(MSG_DISMISS_DIALOG, DIALOG_TIME_OUT);

        } catch (Exception e) {
            try {
                Slog.e(TAG, "onCreate failed, declining the logd access", e);
                mLogcatManagerService.decline(mUid, mGid, mPid, mFd);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Fails to call remote functions", ex);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAlert != null && mAlert.isShowing()) {
            mAlert.dismiss();
        }
        mAlert = null;
    }

    private void getIntentInfo(Intent intent) throws Exception {

        if (intent == null) {
            throw new NullPointerException("Intent is null");
        }

        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (mPackageName == null || mPackageName.length() == 0) {
            throw new NullPointerException("Package Name is null");
        }

        mUid = intent.getIntExtra("com.android.server.logcat.uid", 0);
        mGid = intent.getIntExtra("com.android.server.logcat.gid", 0);
        mPid = intent.getIntExtra("com.android.server.logcat.pid", 0);
        mFd = intent.getIntExtra("com.android.server.logcat.fd", 0);
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_DISMISS_DIALOG:
                    if (mAlert != null) {
                        mAlert.dismiss();
                        mAlert = null;
                        try {
                            mLogcatManagerService.decline(mUid, mGid, mPid, mFd);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Fails to call remote functions", e);
                        }
                    }
                    break;

                default:
                    break;
            }
        }
    };

    private String getTitleString(Context context, String callingPackage, int uid)
            throws Exception {

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            throw new NullPointerException("PackageManager is null");
        }

        CharSequence appLabel = pm.getApplicationInfoAsUser(callingPackage,
                PackageManager.MATCH_DIRECT_BOOT_AUTO,
                UserHandle.getUserId(uid)).loadLabel(pm);
        if (appLabel == null || appLabel.length() == 0) {
            throw new NameNotFoundException("Application Label is null");
        }

        String titleString = context.getString(
                com.android.internal.R.string.log_access_confirmation_title, appLabel);
        if (titleString == null || titleString.length() == 0) {
            throw new NullPointerException("Title is null");
        }

        return titleString;
    }

    /**
     * Returns the dialog view.
     * If we cannot retrieve the package name, it returns null and we decline the full device log
     * access
     */
    private View createView() throws Exception {

        final View view = getLayoutInflater().inflate(
                R.layout.log_access_user_consent_dialog_permission, null /*root*/);

        if (view == null) {
            throw new InflateException();
        }

        ((TextView) view.findViewById(R.id.log_access_dialog_title))
            .setText(mAlertTitle);

        Button button_allow = (Button) view.findViewById(R.id.log_access_dialog_allow_button);
        button_allow.setOnClickListener(this);

        Button button_deny = (Button) view.findViewById(R.id.log_access_dialog_deny_button);
        button_deny.setOnClickListener(this);

        return view;

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.log_access_dialog_allow_button:
                try {
                    mLogcatManagerService.approve(mUid, mGid, mPid, mFd);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Fails to call remote functions", e);
                }
                finish();
                break;
            case R.id.log_access_dialog_deny_button:
                try {
                    mLogcatManagerService.decline(mUid, mGid, mPid, mFd);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Fails to call remote functions", e);
                }
                finish();
                break;
        }
    }
}
