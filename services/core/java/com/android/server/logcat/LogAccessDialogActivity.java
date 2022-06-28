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

import android.annotation.StyleRes;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.R;
import com.android.server.LocalServices;

/**
 * Dialog responsible for obtaining user consent per-use log access
 */
public class LogAccessDialogActivity extends Activity implements
        View.OnClickListener {
    private static final String TAG = LogAccessDialogActivity.class.getSimpleName();

    private static final int DIALOG_TIME_OUT = Build.IS_DEBUGGABLE ? 60000 : 300000;
    private static final int MSG_DISMISS_DIALOG = 0;

    private final LogcatManagerService.LogcatManagerServiceInternal mLogcatManagerInternal =
            LocalServices.getService(LogcatManagerService.LogcatManagerServiceInternal.class);

    private String mPackageName;
    private int mUid;

    private String mAlertTitle;
    private AlertDialog.Builder mAlertDialog;
    private AlertDialog mAlert;
    private View mAlertView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // retrieve Intent extra information
        if (!readIntentInfo(getIntent())) {
            Slog.e(TAG, "Invalid Intent extras, finishing");
            finish();
            return;
        }

        // retrieve the title string from passed intent extra
        try {
            mAlertTitle = getTitleString(this, mPackageName, mUid);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Unable to fetch label of package " + mPackageName, e);
            declineLogAccess();
            finish();
            return;
        }

        // create View
        boolean isDarkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int themeId = isDarkTheme ? android.R.style.Theme_DeviceDefault_Dialog_Alert :
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        mAlertView = createView(themeId);

        // create AlertDialog
        mAlertDialog = new AlertDialog.Builder(this, themeId);
        mAlertDialog.setView(mAlertView);
        mAlertDialog.setOnCancelListener(dialog -> declineLogAccess());
        mAlertDialog.setOnDismissListener(dialog -> finish());

        // show Alert
        mAlert = mAlertDialog.create();
        mAlert.show();

        // set Alert Timeout
        mHandler.sendEmptyMessageDelayed(MSG_DISMISS_DIALOG, DIALOG_TIME_OUT);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations() && mAlert != null && mAlert.isShowing()) {
            mAlert.dismiss();
        }
        mAlert = null;
    }

    private boolean readIntentInfo(Intent intent) {
        if (intent == null) {
            Slog.e(TAG, "Intent is null");
            return false;
        }

        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (mPackageName == null || mPackageName.length() == 0) {
            Slog.e(TAG, "Missing package name extra");
            return false;
        }

        if (!intent.hasExtra(Intent.EXTRA_UID)) {
            Slog.e(TAG, "Missing EXTRA_UID");
            return false;
        }

        mUid = intent.getIntExtra(Intent.EXTRA_UID, 0);

        return true;
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_DISMISS_DIALOG:
                    if (mAlert != null) {
                        mAlert.dismiss();
                        mAlert = null;
                        declineLogAccess();
                    }
                    break;

                default:
                    break;
            }
        }
    };

    private String getTitleString(Context context, String callingPackage, int uid)
            throws NameNotFoundException {
        PackageManager pm = context.getPackageManager();

        CharSequence appLabel = pm.getApplicationInfoAsUser(callingPackage,
                PackageManager.MATCH_DIRECT_BOOT_AUTO,
                UserHandle.getUserId(uid)).loadLabel(pm);

        String titleString = context.getString(
                com.android.internal.R.string.log_access_confirmation_title, appLabel);

        return titleString;
    }

    /**
     * Returns the dialog view.
     * If we cannot retrieve the package name, it returns null and we decline the full device log
     * access
     */
    private View createView(@StyleRes int themeId) {
        Context themedContext = new ContextThemeWrapper(getApplicationContext(), themeId);
        final View view = LayoutInflater.from(themedContext).inflate(
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
                mLogcatManagerInternal.approveAccessForClient(mUid, mPackageName);
                finish();
                break;
            case R.id.log_access_dialog_deny_button:
                declineLogAccess();
                finish();
                break;
        }
    }

    private void declineLogAccess() {
        mLogcatManagerInternal.declineAccessForClient(mUid, mPackageName);
    }
}
