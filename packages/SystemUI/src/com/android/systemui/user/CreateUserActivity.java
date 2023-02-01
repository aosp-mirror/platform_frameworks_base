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

package com.android.systemui.user;

import android.app.Activity;
import android.app.Dialog;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.users.EditUserInfoController;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;

import javax.inject.Inject;

/**
 * This screen shows a Dialog for choosing nickname and photo for a new user, and then delegates the
 * user creation to a UserCreator.
 */
public class CreateUserActivity extends Activity {

    /**
     * Creates an intent to start this activity.
     */
    public static Intent createIntentForStart(Context context) {
        Intent intent = new Intent(context, CreateUserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static final String TAG = "CreateUserActivity";
    private static final String DIALOG_STATE_KEY = "create_user_dialog_state";

    private final UserCreator mUserCreator;
    private final EditUserInfoController mEditUserInfoController;
    private final IActivityManager mActivityManager;
    private final ActivityStarter mActivityStarter;

    private Dialog mSetupUserDialog;

    @Inject
    public CreateUserActivity(UserCreator userCreator,
            EditUserInfoController editUserInfoController, IActivityManager activityManager,
            ActivityStarter activityStarter) {
        mUserCreator = userCreator;
        mEditUserInfoController = editUserInfoController;
        mActivityManager = activityManager;
        mActivityStarter = activityStarter;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setContentView(R.layout.activity_create_new_user);

        if (savedInstanceState != null) {
            mEditUserInfoController.onRestoreInstanceState(savedInstanceState);
        }

        mSetupUserDialog = createDialog();
        mSetupUserDialog.show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (mSetupUserDialog != null && mSetupUserDialog.isShowing()) {
            outState.putBundle(DIALOG_STATE_KEY, mSetupUserDialog.onSaveInstanceState());
        }

        mEditUserInfoController.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Bundle savedDialogState = savedInstanceState.getBundle(DIALOG_STATE_KEY);
        if (savedDialogState != null && mSetupUserDialog != null) {
            mSetupUserDialog.onRestoreInstanceState(savedDialogState);
        }
    }

    private Dialog createDialog() {
        String defaultUserName = getString(com.android.settingslib.R.string.user_new_user_name);

        return mEditUserInfoController.createDialog(
                this,
                this::startActivity,
                null,
                defaultUserName,
                getString(com.android.settingslib.R.string.user_add_user),
                this::addUserNow,
                this::finish
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mEditUserInfoController.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (mSetupUserDialog != null) {
            mSetupUserDialog.dismiss();
        }
    }

    private void addUserNow(String userName, Drawable userIcon) {
        mSetupUserDialog.dismiss();

        userName = (userName == null || userName.trim().isEmpty())
                ? getString(com.android.settingslib.R.string.user_new_user_name)
                : userName;

        mUserCreator.createUser(userName, userIcon,
                userInfo -> {
                    switchToUser(userInfo.id);
                    finishIfNeeded();
                }, () -> {
                    Log.e(TAG, "Unable to create user");
                    finishIfNeeded();
                });
    }

    private void finishIfNeeded() {
        if (!isFinishing() && !isDestroyed()) {
            finish();
        }
    }

    private void switchToUser(int userId) {
        try {
            mActivityManager.switchUser(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    /**
     * Lambda to start activity from an intent. Ensures that device is unlocked first.
     * @param intent
     * @param requestCode
     */
    private void startActivity(Intent intent, int requestCode) {
        mActivityStarter.dismissKeyguardThenExecute(() -> {
            mEditUserInfoController.startingActivityForResult();
            startActivityForResult(intent, requestCode);
            return true;
        }, /* cancel= */ null, /* afterKeyguardGone= */ true);
    }
}
