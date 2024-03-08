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

import static android.Manifest.permission.HIDE_OVERLAY_WINDOWS;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
import static android.provider.Settings.ACTION_BIOMETRIC_ENROLL;
import static android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A dialog shown to the user that prompts them to set the screen lock for the current foreground
 * user. Should be called from the context of foreground user.
 */
public class SetScreenLockDialogActivity extends AlertActivity
        implements DialogInterface.OnClickListener, DialogInterface.OnDismissListener {
    private static final String TAG = "SetScreenLockDialog";
    public static final String EXTRA_LAUNCH_REASON = "launch_reason";
    /**
     * User id associated with the workflow that wants to launch the prompt to set up the
     * screen lock
     */
    public static final String EXTRA_ORIGIN_USER_ID = "origin_user_id";
    private static final String PACKAGE_NAME = "android";
    @IntDef(prefix = "LAUNCH_REASON_", value = {
            LAUNCH_REASON_PRIVATE_SPACE_SETTINGS_ACCESS,
            LAUNCH_REASON_DISABLE_QUIET_MODE,
            LAUNCH_REASON_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LaunchReason {
    }
    public static final int LAUNCH_REASON_UNKNOWN = -1;
    public static final int LAUNCH_REASON_DISABLE_QUIET_MODE = 1;
    public static final int LAUNCH_REASON_PRIVATE_SPACE_SETTINGS_ACCESS = 2;
    private @LaunchReason int mReason;
    private int mOriginUserId;

    @Override
    @RequiresPermission(HIDE_OVERLAY_WINDOWS)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!(android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.showSetScreenLockDialog()
                && android.multiuser.Flags.enablePrivateSpaceFeatures())) {
            finish();
            return;
        }
        Intent intent = getIntent();
        mReason = intent.getIntExtra(EXTRA_LAUNCH_REASON, LAUNCH_REASON_UNKNOWN);
        mOriginUserId = intent.getIntExtra(EXTRA_ORIGIN_USER_ID, UserHandle.USER_NULL);

        if (mReason == LAUNCH_REASON_UNKNOWN) {
            Log.e(TAG, "Invalid launch reason: " + mReason);
            finish();
            return;
        }

        final KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null) {
            Log.e(TAG, "Error fetching keyguard manager");
            return;
        }
        if (km.isDeviceSecure()) {
            Log.w(TAG, "Closing the activity since screen lock is already set");
            return;
        }

        Log.d(TAG, "Launching screen lock setup dialog due to " + mReason);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.set_up_screen_lock_title)
                .setOnDismissListener(this)
                .setPositiveButton(R.string.set_up_screen_lock_action_label, this)
                .setNegativeButton(R.string.cancel, this);
        setLaunchUserSpecificMessage(builder);
        final AlertDialog dialog = builder.create();
        dialog.create();
        getWindow().setHideOverlayWindows(true);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        dialog.show();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            Intent setNewLockIntent = new Intent(ACTION_BIOMETRIC_ENROLL);
            setNewLockIntent.putExtra(EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, DEVICE_CREDENTIAL);
            startActivity(setNewLockIntent);
        } else {
            finish();
        }
    }

    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    private void setLaunchUserSpecificMessage(AlertDialog.Builder builder) {
        if (mReason == LAUNCH_REASON_PRIVATE_SPACE_SETTINGS_ACCESS) {
            // Always set private space message if launch reason is specific to private space
            builder.setMessage(R.string.private_space_set_up_screen_lock_message);
            return;
        }
        final UserManager userManager = getApplicationContext().getSystemService(UserManager.class);
        if (userManager != null) {
            UserInfo userInfo = userManager.getUserInfo(mOriginUserId);
            if (userInfo != null && userInfo.isPrivateProfile()) {
                builder.setMessage(R.string.private_space_set_up_screen_lock_message);
            }
        }
    }

    /** Returns a basic intent to display the screen lock dialog */
    public static Intent createBaseIntent(@LaunchReason int launchReason) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PACKAGE_NAME,
                SetScreenLockDialogActivity.class.getName()));
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(EXTRA_LAUNCH_REASON, launchReason);
        return intent;
    }
}
