/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.android.internal.R;

/**
 * A dialog shown to the user when they try to launch an app from a quiet profile
 * ({@link UserManager#isQuietModeEnabled(UserHandle)}, or when the app is suspended by the
 * profile owner or device owner.
 */
public class UnlaunchableAppActivity extends Activity
        implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener {
    private static final String TAG = "UnlaunchableAppActivity";

    private static final int UNLAUNCHABLE_REASON_QUIET_MODE = 1;
    private static final int UNLAUNCHABLE_REASON_SUSPENDED_PACKAGE = 2;
    private static final String EXTRA_UNLAUNCHABLE_REASON = "unlaunchable_reason";

    private int mUserId;
    private int mReason;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mReason = intent.getIntExtra(EXTRA_UNLAUNCHABLE_REASON, -1);
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

        if (mUserId == UserHandle.USER_NULL) {
            Log.wtf(TAG, "Invalid user id: " + mUserId + ". Stopping.");
            finish();
            return;
        }

        String dialogTitle;
        String dialogMessage;
        if (mReason == UNLAUNCHABLE_REASON_QUIET_MODE) {
            dialogTitle = getResources().getString(R.string.work_mode_off_title);
            dialogMessage = getResources().getString(R.string.work_mode_off_message);
        } else if (mReason == UNLAUNCHABLE_REASON_SUSPENDED_PACKAGE) {
            PackageManager pm = getPackageManager();
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            String packageLabel = packageName;
            try {
                Context userContext = createPackageContextAsUser(packageName, 0,
                        UserHandle.of(mUserId));
                ApplicationInfo appInfo = userContext.getApplicationInfo();
                if (appInfo != null) {
                    packageLabel = userContext.getPackageManager().getApplicationLabel(appInfo)
                            .toString();
                }
            } catch (NameNotFoundException e) {
            }
            dialogTitle = String.format(getResources().getString(R.string.suspended_package_title),
                    packageLabel);
            dialogMessage = dpm.getShortSupportMessageForUser(dpm.getProfileOwnerAsUser(mUserId),
                    mUserId);
            if (dialogMessage == null) {
                dialogMessage = String.format(
                        getResources().getString(R.string.suspended_package_message),
                        dpm.getProfileOwnerNameAsUser(mUserId));
            }
        } else {
            Log.wtf(TAG, "Invalid unlaunchable type: " + mReason);
            finish();
            return;
        }

        View rootView = LayoutInflater.from(this).inflate(R.layout.unlaunchable_app_activity, null);
        TextView titleView = (TextView)rootView.findViewById(R.id.unlaunchable_app_title);
        TextView messageView = (TextView)rootView.findViewById(R.id.unlaunchable_app_message);
        titleView.setText(dialogTitle);
        messageView.setText(dialogMessage);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(rootView)
                .setOnDismissListener(this);
        if (mReason == UNLAUNCHABLE_REASON_QUIET_MODE) {
            builder.setPositiveButton(R.string.work_mode_turn_on, this)
                    .setNegativeButton(R.string.cancel, null);
        } else {
            builder.setPositiveButton(R.string.ok, null);
        }
        builder.show();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (mReason == UNLAUNCHABLE_REASON_QUIET_MODE && which == DialogInterface.BUTTON_POSITIVE) {
            UserManager.get(this).setQuietModeEnabled(mUserId, false);
        }
    }

    private static final Intent createBaseIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("android", UnlaunchableAppActivity.class.getName()));
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return intent;
    }

    public static Intent createInQuietModeDialogIntent(int userId) {
        Intent intent = createBaseIntent();
        intent.putExtra(EXTRA_UNLAUNCHABLE_REASON, UNLAUNCHABLE_REASON_QUIET_MODE);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return intent;
    }

    public static Intent createPackageSuspendedDialogIntent(String packageName, int userId) {
        Intent intent = createBaseIntent();
        intent.putExtra(EXTRA_UNLAUNCHABLE_REASON, UNLAUNCHABLE_REASON_SUSPENDED_PACKAGE);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        return intent;
    }
}
