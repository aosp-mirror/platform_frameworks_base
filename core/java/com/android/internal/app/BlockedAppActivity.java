/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;

/**
 * A dialog shown to the user when they try to launch an app that is not allowed in lock task
 * mode. The intent to start this activity must be created with the static factory method provided
 * below.
 */
public class BlockedAppActivity extends AlertActivity {

    private static final String TAG = "BlockedAppActivity";
    private static final String PACKAGE_NAME = "com.android.internal.app";
    private static final String EXTRA_BLOCKED_PACKAGE = PACKAGE_NAME + ".extra.BLOCKED_PACKAGE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, /* defaultValue= */ -1);
        if (userId < 0) {
            Slog.wtf(TAG, "Invalid user: " + userId);
            finish();
            return;
        }

        String packageName = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE);
        if (TextUtils.isEmpty(packageName)) {
            Slog.wtf(TAG, "Invalid package: " + packageName);
            finish();
            return;
        }

        CharSequence appLabel = getAppLabel(userId, packageName);

        mAlertParams.mTitle = getString(R.string.app_blocked_title);
        mAlertParams.mMessage = getString(R.string.app_blocked_message, appLabel);
        mAlertParams.mPositiveButtonText = getString(android.R.string.ok);
        setupAlert();
    }

    private CharSequence getAppLabel(int userId, String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo aInfo =
                    pm.getApplicationInfoAsUser(packageName, /* flags= */ 0, userId);
            return aInfo.loadLabel(pm);
        } catch (PackageManager.NameNotFoundException ne) {
            Slog.e(TAG, "Package " + packageName + " not found", ne);
        }
        return packageName;
    }


    /** Creates an intent that launches {@link BlockedAppActivity}. */
    public static Intent createIntent(int userId, String packageName) {
        return new Intent()
                .setClassName("android", BlockedAppActivity.class.getName())
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_BLOCKED_PACKAGE, packageName);
    }
}
