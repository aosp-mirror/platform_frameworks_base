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

import static android.content.res.Resources.ID_NULL;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;

public class SuspendedAppActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private static final String TAG = SuspendedAppActivity.class.getSimpleName();
    private static final String PACKAGE_NAME = "com.android.internal.app";

    public static final String EXTRA_SUSPENDED_PACKAGE = PACKAGE_NAME + ".extra.SUSPENDED_PACKAGE";
    public static final String EXTRA_SUSPENDING_PACKAGE =
            PACKAGE_NAME + ".extra.SUSPENDING_PACKAGE";
    public static final String EXTRA_DIALOG_INFO = PACKAGE_NAME + ".extra.DIALOG_INFO";

    private Intent mMoreDetailsIntent;
    private int mUserId;
    private PackageManager mPm;
    private Resources mSuspendingAppResources;
    private SuspendDialogInfo mSuppliedDialogInfo;

    private CharSequence getAppLabel(String packageName) {
        try {
            return mPm.getApplicationInfoAsUser(packageName, 0, mUserId).loadLabel(mPm);
        } catch (PackageManager.NameNotFoundException ne) {
            Slog.e(TAG, "Package " + packageName + " not found", ne);
        }
        return packageName;
    }

    private Intent getMoreDetailsActivity(String suspendingPackage, String suspendedPackage,
            int userId) {
        final Intent moreDetailsIntent = new Intent(Intent.ACTION_SHOW_SUSPENDED_APP_DETAILS)
                .setPackage(suspendingPackage);
        final String requiredPermission = Manifest.permission.SEND_SHOW_SUSPENDED_APP_DETAILS;
        final ResolveInfo resolvedInfo = mPm.resolveActivityAsUser(moreDetailsIntent, 0, userId);
        if (resolvedInfo != null && resolvedInfo.activityInfo != null
                && requiredPermission.equals(resolvedInfo.activityInfo.permission)) {
            moreDetailsIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, suspendedPackage)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return moreDetailsIntent;
        }
        return null;
    }

    private Drawable resolveIcon() {
        final int iconId = (mSuppliedDialogInfo != null) ? mSuppliedDialogInfo.getIconResId()
                : ID_NULL;
        if (iconId != ID_NULL && mSuspendingAppResources != null) {
            try {
                return mSuspendingAppResources.getDrawable(iconId, getTheme());
            } catch (Resources.NotFoundException nfe) {
                Slog.e(TAG, "Could not resolve drawable resource id " + iconId);
            }
        }
        return null;
    }

    private String resolveTitle() {
        final int titleId = (mSuppliedDialogInfo != null) ? mSuppliedDialogInfo.getTitleResId()
                : ID_NULL;
        if (titleId != ID_NULL && mSuspendingAppResources != null) {
            try {
                return mSuspendingAppResources.getString(titleId);
            } catch (Resources.NotFoundException nfe) {
                Slog.e(TAG, "Could not resolve string resource id " + titleId);
            }
        }
        return getString(R.string.app_suspended_title);
    }

    private String resolveDialogMessage(String suspendingPkg, String suspendedPkg) {
        final CharSequence suspendedAppLabel = getAppLabel(suspendedPkg);
        if (mSuppliedDialogInfo != null) {
            final int messageId = mSuppliedDialogInfo.getDialogMessageResId();
            final String message = mSuppliedDialogInfo.getDialogMessage();
            if (messageId != ID_NULL && mSuspendingAppResources != null) {
                try {
                    return mSuspendingAppResources.getString(messageId, suspendedAppLabel);
                } catch (Resources.NotFoundException nfe) {
                    Slog.e(TAG, "Could not resolve string resource id " + messageId);
                }
            } else if (message != null) {
                return String.format(getResources().getConfiguration().getLocales().get(0), message,
                        suspendedAppLabel);
            }
        }
        return getString(R.string.app_suspended_default_message, suspendedAppLabel,
                getAppLabel(suspendingPkg));
    }

    private String resolveNeutralButtonText() {
        final int buttonTextId = (mSuppliedDialogInfo != null)
                ? mSuppliedDialogInfo.getNeutralButtonTextResId() : ID_NULL;
        if (buttonTextId != ID_NULL && mSuspendingAppResources != null) {
            try {
                return mSuspendingAppResources.getString(buttonTextId);
            } catch (Resources.NotFoundException nfe) {
                Slog.e(TAG, "Could not resolve string resource id " + buttonTextId);
            }
        }
        return getString(R.string.app_suspended_more_details);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mPm = getPackageManager();
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        final Intent intent = getIntent();
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        if (mUserId < 0) {
            Slog.wtf(TAG, "Invalid user: " + mUserId);
            finish();
            return;
        }
        final String suspendedPackage = intent.getStringExtra(EXTRA_SUSPENDED_PACKAGE);
        final String suspendingPackage = intent.getStringExtra(EXTRA_SUSPENDING_PACKAGE);
        mSuppliedDialogInfo = intent.getParcelableExtra(EXTRA_DIALOG_INFO);
        if (mSuppliedDialogInfo != null) {
            try {
                mSuspendingAppResources = mPm.getResourcesForApplicationAsUser(suspendingPackage,
                        mUserId);
            } catch (PackageManager.NameNotFoundException ne) {
                Slog.e(TAG, "Could not find resources for " + suspendingPackage, ne);
            }
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mIcon = resolveIcon();
        ap.mTitle = resolveTitle();
        ap.mMessage = resolveDialogMessage(suspendingPackage, suspendedPackage);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        mMoreDetailsIntent = getMoreDetailsActivity(suspendingPackage, suspendedPackage, mUserId);
        if (mMoreDetailsIntent != null) {
            ap.mNeutralButtonText = resolveNeutralButtonText();
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

    public static Intent createSuspendedAppInterceptIntent(String suspendedPackage,
            String suspendingPackage, SuspendDialogInfo dialogInfo, int userId) {
        return new Intent()
                .setClassName("android", SuspendedAppActivity.class.getName())
                .putExtra(EXTRA_SUSPENDED_PACKAGE, suspendedPackage)
                .putExtra(EXTRA_DIALOG_INFO, dialogInfo)
                .putExtra(EXTRA_SUSPENDING_PACKAGE, suspendingPackage)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
