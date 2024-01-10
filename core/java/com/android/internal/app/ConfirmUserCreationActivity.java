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

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.R;

/**
 * Activity to confirm with the user that it is ok to create a new user, as requested by
 * an app. It has to do some checks to decide what kind of prompt the user should be shown.
 * Particularly, it needs to check if the account requested already exists on another user.
 */
public class ConfirmUserCreationActivity extends AlertActivity
        implements DialogInterface.OnClickListener {

    private static final String TAG = "CreateUser";

    private String mUserName;
    private String mAccountName;
    private String mAccountType;
    private PersistableBundle mAccountOptions;
    private boolean mCanProceed;
    private UserManager mUserManager;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        Intent intent = getIntent();
        mUserName = intent.getStringExtra(UserManager.EXTRA_USER_NAME);
        mAccountName = intent.getStringExtra(UserManager.EXTRA_USER_ACCOUNT_NAME);
        mAccountType = intent.getStringExtra(UserManager.EXTRA_USER_ACCOUNT_TYPE);
        mAccountOptions = (PersistableBundle)
                intent.getParcelableExtra(UserManager.EXTRA_USER_ACCOUNT_OPTIONS);

        mUserManager = getSystemService(UserManager.class);

        String message = checkUserCreationRequirements();

        if (message == null) {
            finish();
            return;
        }
        final AlertController.AlertParams ap = mAlertParams;
        ap.mMessage = message;
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mPositiveButtonListener = this;

        // Show the negative button if the user actually has a choice
        if (mCanProceed) {
            ap.mNegativeButtonText = getString(android.R.string.cancel);
            ap.mNegativeButtonListener = this;
        }
        setupAlert();
    }

    private String checkUserCreationRequirements() {
        final String callingPackage = getCallingPackage();
        if (callingPackage == null) {
            throw new SecurityException(
                    "User Creation intent must be launched with startActivityForResult");
        }
        final ApplicationInfo appInfo;
        try {
            appInfo = getPackageManager().getApplicationInfo(callingPackage, 0);
        } catch (NameNotFoundException nnfe) {
            throw new SecurityException(
                    "Cannot find the calling package");
        }
        final String message;
        // Check the user restrictions
        boolean cantCreateUser = mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER)
                || !mUserManager.isAdminUser();
        // Check the system state and user count
        boolean cantCreateAnyMoreUsers = !mUserManager.canAddMoreUsers();
        // Check the account existence
        final Account account = new Account(mAccountName, mAccountType);
        boolean accountExists = mAccountName != null && mAccountType != null
                && (AccountManager.get(this).someUserHasAccount(account)
                    | mUserManager.someUserHasSeedAccount(mAccountName, mAccountType));
        mCanProceed = true;
        final String appName = appInfo.loadLabel(getPackageManager()).toString();
        if (cantCreateUser) {
            setResult(UserManager.USER_CREATION_FAILED_NOT_PERMITTED);
            return null;
        } else if (!(isUserPropertyWithinLimit(mUserName, UserManager.MAX_USER_NAME_LENGTH)
                && isUserPropertyWithinLimit(mAccountName, UserManager.MAX_ACCOUNT_STRING_LENGTH)
                && isUserPropertyWithinLimit(mAccountType, UserManager.MAX_ACCOUNT_STRING_LENGTH))
                || (mAccountOptions != null && !mAccountOptions.isBundleContentsWithinLengthLimit(
                UserManager.MAX_ACCOUNT_OPTIONS_LENGTH))) {
            setResult(UserManager.USER_CREATION_FAILED_NOT_PERMITTED);
            Log.i(TAG, "User properties must not exceed their character limits");
            return null;
        } else if (cantCreateAnyMoreUsers) {
            setResult(UserManager.USER_CREATION_FAILED_NO_MORE_USERS);
            return null;
        } else if (accountExists) {
            message = getString(R.string.user_creation_account_exists, appName, mAccountName);
        } else {
            message = getString(R.string.user_creation_adding, appName, mAccountName);
        }
        return message;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        setResult(RESULT_CANCELED);
        if (which == BUTTON_POSITIVE && mCanProceed) {
            Log.i(TAG, "Ok, creating user");
            UserInfo user = mUserManager.createUser(mUserName, 0);
            if (user == null) {
                Log.e(TAG, "Couldn't create user");
                finish();
                return;
            }
            mUserManager.setSeedAccountData(user.id, mAccountName, mAccountType, mAccountOptions);
            setResult(RESULT_OK);
        }
        finish();
    }

    private boolean isUserPropertyWithinLimit(String property, int limit) {
        return property == null || property.length() <= limit;
    }
}
