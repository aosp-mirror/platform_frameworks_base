/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Installation failed: Return status code to the caller or display failure UI to user
 */
public class InstallFailed extends Activity {
    private static final String LOG_TAG = InstallFailed.class.getSimpleName();

    /** Label of the app that failed to install */
    private CharSequence mLabel;

    private AlertDialog mDialog;

    /**
     * Unhide the appropriate label for the statusCode.
     *
     * @param statusCode The status code from the package installer.
     */
    private void setExplanationFromErrorCode(int statusCode) {
        Log.d(LOG_TAG, "Installation status code: " + statusCode);

        View viewToEnable;
        switch (statusCode) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                viewToEnable = mDialog.requireViewById(R.id.install_failed_blocked);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                viewToEnable = mDialog.requireViewById(R.id.install_failed_conflict);
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                viewToEnable = mDialog.requireViewById(R.id.install_failed_incompatible);
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                viewToEnable = mDialog.requireViewById(R.id.install_failed_invalid_apk);
                break;
            default:
                viewToEnable = mDialog.requireViewById(R.id.install_failed);
                break;
        }

        viewToEnable.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(true);

        int statusCode = getIntent().getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);

        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            int legacyStatus = getIntent().getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS,
                    PackageManager.INSTALL_FAILED_INTERNAL_ERROR);

            // Return result if requested
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus);
            setResult(Activity.RESULT_FIRST_USER, result);
            finish();
        } else {
            Intent intent = getIntent();
            ApplicationInfo appInfo = intent
                    .getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
            Uri packageURI = intent.getData();

            // Set header icon and title
            PackageUtil.AppSnippet as;
            PackageManager pm = getPackageManager();
            as = intent.getParcelableExtra(PackageInstallerActivity.EXTRA_APP_SNIPPET,
                    PackageUtil.AppSnippet.class);

            // Store label for dialog
            mLabel = as.label;

            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            builder.setIcon(as.icon);
            builder.setTitle(as.label);
            builder.setView(R.layout.install_content_view);
            builder.setPositiveButton(getString(R.string.done),
                    (ignored, ignored2) -> finish());
            builder.setOnCancelListener(dialog -> {
                finish();
            });
            mDialog = builder.create();
            mDialog.show();

            // Show out of space dialog if needed
            if (statusCode == PackageInstaller.STATUS_FAILURE_STORAGE) {
                (new OutOfSpaceDialog()).show(getFragmentManager(), "outofspace");
            }

            // Get status messages
            setExplanationFromErrorCode(statusCode);
        }
    }

    /**
     * Dialog shown when we ran out of space during installation. This contains a link to the
     * "manage applications" settings page.
     */
    public static class OutOfSpaceDialog extends DialogFragment {
        private InstallFailed mActivity;

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);

            mActivity = (InstallFailed) context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.out_of_space_dlg_title)
                    .setMessage(getString(R.string.out_of_space_dlg_text, mActivity.mLabel))
                    .setPositiveButton(R.string.manage_applications, (dialog, which) -> {
                        // launch manage applications
                        Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                        startActivity(intent);
                        mActivity.finish();
                    })
                    .setNegativeButton(R.string.cancel, (dialog, which) -> mActivity.finish())
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);

            mActivity.finish();
        }
    }
}
