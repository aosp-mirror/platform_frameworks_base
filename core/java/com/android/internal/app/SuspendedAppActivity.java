/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;

public class SuspendedAppActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private static final String TAG = "SuspendedAppActivity";

    public static final String EXTRA_SUSPENDED_PACKAGE =
            "SuspendedAppActivity.extra.SUSPENDED_PACKAGE";
    public static final String EXTRA_SUSPENDING_PACKAGE =
            "SuspendedAppActivity.extra.SUSPENDING_PACKAGE";
    public static final String EXTRA_DIALOG_MESSAGE = "SuspendedAppActivity.extra.DIALOG_MESSAGE";
    public static final String EXTRA_MORE_DETAILS_INTENT =
            "SuspendedAppActivity.extra.MORE_DETAILS_INTENT";

    private Intent mMoreDetailsIntent;
    private int mUserId;

    private CharSequence getAppLabel(String packageName) {
        final PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationInfoAsUser(packageName, 0, mUserId).loadLabel(pm);
        } catch (PackageManager.NameNotFoundException ne) {
            Slog.e(TAG, "Package " + packageName + " not found", ne);
        }
        return packageName;
    }

    @Override
    public void onCreate(Bundle icicle) {
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        super.onCreate(icicle);

        final Intent intent = getIntent();
        mMoreDetailsIntent = intent.getParcelableExtra(EXTRA_MORE_DETAILS_INTENT);
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        if (mUserId < 0) {
            Slog.wtf(TAG, "Invalid user: " + mUserId);
            finish();
            return;
        }
        final String suppliedMessage = intent.getStringExtra(EXTRA_DIALOG_MESSAGE);
        final CharSequence suspendedAppLabel = getAppLabel(
                intent.getStringExtra(EXTRA_SUSPENDED_PACKAGE));
        final CharSequence dialogMessage;
        if (suppliedMessage == null) {
            dialogMessage = getString(R.string.app_suspended_default_message,
                    suspendedAppLabel,
                    getAppLabel(intent.getStringExtra(EXTRA_SUSPENDING_PACKAGE)));
        } else {
            dialogMessage = String.format(getResources().getConfiguration().getLocales().get(0),
                    suppliedMessage, suspendedAppLabel);
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.app_suspended_title);
        ap.mMessage = dialogMessage;
        ap.mPositiveButtonText = getString(android.R.string.ok);
        if (mMoreDetailsIntent != null) {
            ap.mNeutralButtonText = getString(R.string.app_suspended_more_details);
        }
        ap.mPositiveButtonListener = ap.mNeutralButtonListener = this;
        setupAlert();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case AlertDialog.BUTTON_NEUTRAL:
                startActivityAsUser(mMoreDetailsIntent, UserHandle.of(mUserId));
                Slog.i(TAG, "Started more details activity");
                break;
        }
        finish();
    }
}
