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

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;
import static android.content.res.Resources.ID_NULL;

import android.Manifest;
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.KeyguardManager;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

public class SuspendedAppActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private static final String TAG = SuspendedAppActivity.class.getSimpleName();
    private static final String PACKAGE_NAME = "com.android.internal.app";

    public static final String EXTRA_SUSPENDED_PACKAGE = PACKAGE_NAME + ".extra.SUSPENDED_PACKAGE";
    public static final String EXTRA_SUSPENDING_PACKAGE =
            PACKAGE_NAME + ".extra.SUSPENDING_PACKAGE";
    public static final String EXTRA_DIALOG_INFO = PACKAGE_NAME + ".extra.DIALOG_INFO";
    public static final String EXTRA_ACTIVITY_OPTIONS = PACKAGE_NAME + ".extra.ACTIVITY_OPTIONS";
    public static final String EXTRA_UNSUSPEND_INTENT = PACKAGE_NAME + ".extra.UNSUSPEND_INTENT";

    private Intent mMoreDetailsIntent;
    private IntentSender mOnUnsuspend;
    private String mSuspendedPackage;
    private String mSuspendingPackage;
    private int mNeutralButtonAction;
    private int mUserId;
    private PackageManager mPm;
    private UsageStatsManager mUsm;
    private Resources mSuspendingAppResources;
    private SuspendDialogInfo mSuppliedDialogInfo;
    private Bundle mOptions;
    private BroadcastReceiver mUnsuspendReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGES_UNSUSPENDED.equals(intent.getAction())) {
                final String[] unsuspended = intent.getStringArrayExtra(
                        Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (ArrayUtils.contains(unsuspended, mSuspendedPackage)) {
                    if (!isFinishing()) {
                        Slog.w(TAG, "Package " + mSuspendedPackage
                                + " got unsuspended while the dialog was visible. Finishing.");
                        SuspendedAppActivity.this.finish();
                    }
                }
            }
        }
    };

    private CharSequence getAppLabel(String packageName) {
        try {
            return mPm.getApplicationInfoAsUser(packageName, 0, mUserId).loadLabel(mPm);
        } catch (PackageManager.NameNotFoundException ne) {
            Slog.e(TAG, "Package " + packageName + " not found", ne);
        }
        return packageName;
    }

    private Intent getMoreDetailsActivity() {
        final Intent moreDetailsIntent = new Intent(Intent.ACTION_SHOW_SUSPENDED_APP_DETAILS)
                .setPackage(mSuspendingPackage);
        final String requiredPermission = Manifest.permission.SEND_SHOW_SUSPENDED_APP_DETAILS;
        final ResolveInfo resolvedInfo = mPm.resolveActivityAsUser(moreDetailsIntent,
                MATCH_DIRECT_BOOT_UNAWARE | MATCH_DIRECT_BOOT_AWARE, mUserId);
        if (resolvedInfo != null && resolvedInfo.activityInfo != null
                && requiredPermission.equals(resolvedInfo.activityInfo.permission)) {
            moreDetailsIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, mSuspendedPackage)
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
        if (mSuppliedDialogInfo != null) {
            final int titleId = mSuppliedDialogInfo.getTitleResId();
            final String title = mSuppliedDialogInfo.getTitle();
            if (titleId != ID_NULL && mSuspendingAppResources != null) {
                try {
                    return mSuspendingAppResources.getString(titleId);
                } catch (Resources.NotFoundException nfe) {
                    Slog.e(TAG, "Could not resolve string resource id " + titleId);
                }
            } else if (title != null) {
                return title;
            }
        }
        return getString(R.string.app_suspended_title);
    }

    private String resolveDialogMessage() {
        final CharSequence suspendedAppLabel = getAppLabel(mSuspendedPackage);
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
                getAppLabel(mSuspendingPackage));
    }

    /**
     * Returns a text to be displayed on the neutral button or {@code null} if the button should
     * not be shown.
     */
    @Nullable
    private String resolveNeutralButtonText() {
        final int defaultButtonTextId;
        switch (mNeutralButtonAction) {
            case BUTTON_ACTION_MORE_DETAILS:
                if (mMoreDetailsIntent == null) {
                    return null;
                }
                defaultButtonTextId = R.string.app_suspended_more_details;
                break;
            case BUTTON_ACTION_UNSUSPEND:
                defaultButtonTextId = R.string.app_suspended_unsuspend_message;
                break;
            default:
                Slog.w(TAG, "Unknown neutral button action: " + mNeutralButtonAction);
                return null;
        }
        if (mSuppliedDialogInfo != null) {
            final int buttonTextId = mSuppliedDialogInfo.getNeutralButtonTextResId();
            final String buttonText = mSuppliedDialogInfo.getNeutralButtonText();
            if (buttonTextId != ID_NULL && mSuspendingAppResources != null) {
                try {
                    return mSuspendingAppResources.getString(buttonTextId);
                } catch (Resources.NotFoundException nfe) {
                    Slog.e(TAG, "Could not resolve string resource id " + buttonTextId);
                }
            } else if (buttonText != null) {
                return buttonText;
            }
        }
        return getString(defaultButtonTextId);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mPm = getPackageManager();
        mUsm = getSystemService(UsageStatsManager.class);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        final Intent intent = getIntent();
        mOptions = intent.getBundleExtra(EXTRA_ACTIVITY_OPTIONS);
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        if (mUserId < 0) {
            Slog.wtf(TAG, "Invalid user: " + mUserId);
            finish();
            return;
        }
        mSuspendedPackage = intent.getStringExtra(EXTRA_SUSPENDED_PACKAGE);
        mSuspendingPackage = intent.getStringExtra(EXTRA_SUSPENDING_PACKAGE);
        mSuppliedDialogInfo = intent.getParcelableExtra(EXTRA_DIALOG_INFO);
        mOnUnsuspend = intent.getParcelableExtra(EXTRA_UNSUSPEND_INTENT);
        if (mSuppliedDialogInfo != null) {
            try {
                mSuspendingAppResources = createContextAsUser(
                        UserHandle.of(mUserId), /* flags */ 0).getPackageManager()
                        .getResourcesForApplication(mSuspendingPackage);
            } catch (PackageManager.NameNotFoundException ne) {
                Slog.e(TAG, "Could not find resources for " + mSuspendingPackage, ne);
            }
        }
        mNeutralButtonAction = (mSuppliedDialogInfo != null)
                ? mSuppliedDialogInfo.getNeutralButtonAction() : BUTTON_ACTION_MORE_DETAILS;
        mMoreDetailsIntent = (mNeutralButtonAction == BUTTON_ACTION_MORE_DETAILS)
                ? getMoreDetailsActivity() : null;

        final AlertController.AlertParams ap = mAlertParams;
        ap.mIcon = resolveIcon();
        ap.mTitle = resolveTitle();
        ap.mMessage = resolveDialogMessage();
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNeutralButtonText = resolveNeutralButtonText();
        ap.mPositiveButtonListener = ap.mNeutralButtonListener = this;

        requestDismissKeyguardIfNeeded(ap.mMessage);

        setupAlert();

        final IntentFilter unsuspendFilter = new IntentFilter(Intent.ACTION_PACKAGES_UNSUSPENDED);
        registerReceiverAsUser(mUnsuspendReceiver, UserHandle.of(mUserId), unsuspendFilter, null,
                null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUnsuspendReceiver);
    }

    private void requestDismissKeyguardIfNeeded(CharSequence dismissMessage) {
        final KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, dismissMessage,
                    new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissError() {
                            Slog.e(TAG, "Error while dismissing keyguard."
                                    + " Keeping the dialog visible.");
                        }

                        @Override
                        public void onDismissCancelled() {
                            Slog.w(TAG, "Keyguard dismiss was cancelled. Finishing.");
                            SuspendedAppActivity.this.finish();
                        }
                    });
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case AlertDialog.BUTTON_NEUTRAL:
                switch (mNeutralButtonAction) {
                    case BUTTON_ACTION_MORE_DETAILS:
                        if (mMoreDetailsIntent != null) {
                            startActivityAsUser(mMoreDetailsIntent, mOptions,
                                    UserHandle.of(mUserId));
                        } else {
                            Slog.wtf(TAG, "Neutral button should not have existed!");
                        }
                        break;
                    case BUTTON_ACTION_UNSUSPEND:
                        final IPackageManager ipm = AppGlobals.getPackageManager();
                        try {
                            final String[] errored = ipm.setPackagesSuspendedAsUser(
                                    new String[]{mSuspendedPackage}, false, null, null, null,
                                    mSuspendingPackage, mUserId);
                            if (ArrayUtils.contains(errored, mSuspendedPackage)) {
                                Slog.e(TAG, "Could not unsuspend " + mSuspendedPackage);
                                break;
                            }
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Can't talk to system process", re);
                            break;
                        }
                        final Intent reportUnsuspend = new Intent()
                                .setAction(Intent.ACTION_PACKAGE_UNSUSPENDED_MANUALLY)
                                .putExtra(Intent.EXTRA_PACKAGE_NAME, mSuspendedPackage)
                                .setPackage(mSuspendingPackage)
                                .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        sendBroadcastAsUser(reportUnsuspend, UserHandle.of(mUserId));

                        if (mOnUnsuspend != null) {
                            try {
                                mOnUnsuspend.sendIntent(this, 0, null, null, null);
                            } catch (IntentSender.SendIntentException e) {
                                Slog.e(TAG, "Error while starting intent " + mOnUnsuspend, e);
                            }
                        }
                        break;
                    default:
                        Slog.e(TAG, "Unexpected action on neutral button: " + mNeutralButtonAction);
                        break;
                }
                break;
        }
        mUsm.reportUserInteraction(mSuspendingPackage, mUserId);
        finish();
    }

    public static Intent createSuspendedAppInterceptIntent(String suspendedPackage,
            String suspendingPackage, SuspendDialogInfo dialogInfo, Bundle options,
            IntentSender onUnsuspend, int userId) {
        return new Intent()
                .setClassName("android", SuspendedAppActivity.class.getName())
                .putExtra(EXTRA_SUSPENDED_PACKAGE, suspendedPackage)
                .putExtra(EXTRA_DIALOG_INFO, dialogInfo)
                .putExtra(EXTRA_SUSPENDING_PACKAGE, suspendingPackage)
                .putExtra(EXTRA_UNSUSPEND_INTENT, onUnsuspend)
                .putExtra(EXTRA_ACTIVITY_OPTIONS, options)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
