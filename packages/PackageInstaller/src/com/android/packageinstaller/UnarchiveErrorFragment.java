/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.Nullable;

public class UnarchiveErrorFragment extends DialogFragment implements
        DialogInterface.OnClickListener {

    private static final String TAG = "UnarchiveErrorFragment";

    private int mStatus;

    @Nullable
    private PendingIntent mExtraIntent;

    @Nullable
    private String mInstallerPackageName;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle args = getArguments();
        mStatus = args.getInt(PackageInstaller.EXTRA_UNARCHIVE_STATUS, -1);
        mExtraIntent = args.getParcelable(Intent.EXTRA_INTENT, PendingIntent.class);
        long requiredBytes = args.getLong(UnarchiveErrorActivity.EXTRA_REQUIRED_BYTES);
        mInstallerPackageName = args.getString(
                UnarchiveErrorActivity.EXTRA_INSTALLER_PACKAGE_NAME);
        String installerAppTitle = args.getString(UnarchiveErrorActivity.EXTRA_INSTALLER_TITLE);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());

        dialogBuilder.setTitle(getDialogTitle(mStatus, installerAppTitle));
        dialogBuilder.setMessage(getBodyText(mStatus, installerAppTitle, requiredBytes));

        addButtons(dialogBuilder, mStatus);

        return dialogBuilder.create();
    }

    private void addButtons(AlertDialog.Builder dialogBuilder, int status) {
        switch (status) {
            case PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED:
                dialogBuilder.setPositiveButton(R.string.unarchive_action_required_continue, this);
                dialogBuilder.setNegativeButton(R.string.close, this);
                break;
            case PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE:
                dialogBuilder.setPositiveButton(R.string.unarchive_clear_storage_button, this);
                dialogBuilder.setNegativeButton(R.string.close, this);
                break;
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED:
                dialogBuilder.setPositiveButton(R.string.external_sources_settings, this);
                dialogBuilder.setNegativeButton(R.string.close, this);
                break;
            case PackageInstaller.UNARCHIVAL_ERROR_NO_CONNECTIVITY:
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED:
            case PackageInstaller.UNARCHIVAL_GENERIC_ERROR:
                dialogBuilder.setPositiveButton(android.R.string.ok, this);
                break;
            default:
                // This should never happen through normal API usage.
                throw new IllegalArgumentException("Invalid unarchive status " + status);
        }
    }

    private String getBodyText(int status, String installerAppTitle, long requiredBytes) {
        switch (status) {
            case PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED:
                return getString(R.string.unarchive_action_required_body);
            case PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE:
                return String.format(getString(R.string.unarchive_error_storage_body),
                        Formatter.formatShortFileSize(getActivity(), requiredBytes));
            case PackageInstaller.UNARCHIVAL_ERROR_NO_CONNECTIVITY:
                return getString(R.string.unarchive_error_offline_body);
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED:
                return String.format(getString(R.string.unarchive_error_installer_disabled_body),
                        installerAppTitle);
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED:
                return String.format(
                        getString(R.string.unarchive_error_installer_uninstalled_body),
                        installerAppTitle);
            case PackageInstaller.UNARCHIVAL_GENERIC_ERROR:
                return getString(R.string.unarchive_error_generic_body);
            default:
                // This should never happen through normal API usage.
                throw new IllegalArgumentException("Invalid unarchive status " + status);
        }
    }

    private String getDialogTitle(int status, String installerAppTitle) {
        switch (status) {
            case PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED:
                return getString(R.string.unarchive_action_required_title);
            case PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE:
                return getString(R.string.unarchive_error_storage_title);
            case PackageInstaller.UNARCHIVAL_ERROR_NO_CONNECTIVITY:
                return getString(R.string.unarchive_error_offline_title);
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED:
                return String.format(getString(R.string.unarchive_error_installer_disabled_title),
                        installerAppTitle);
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED:
                return String.format(
                        getString(R.string.unarchive_error_installer_uninstalled_title),
                        installerAppTitle);
            case PackageInstaller.UNARCHIVAL_GENERIC_ERROR:
                return getString(R.string.unarchive_error_generic_title);
            default:
                // This should never happen through normal API usage.
                throw new IllegalArgumentException("Invalid unarchive status " + status);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which != Dialog.BUTTON_POSITIVE) {
            return;
        }

        try {
            onClickInternal();
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Failed to start intent after onClick.", e);
        }
    }

    private void onClickInternal() throws IntentSender.SendIntentException {
        Activity activity = getActivity();
        if (activity == null) {
            // This probably shouldn't happen in practice.
            Log.i(TAG, "Lost reference to activity, cannot act onClick.");
            return;
        }

        switch (mStatus) {
            case PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED:
                activity.startIntentSender(mExtraIntent.getIntentSender(), /* fillInIntent= */
                        null, /* flagsMask= */ 0, FLAG_ACTIVITY_NEW_TASK, /* extraFlags= */ 0);
                break;
            case PackageInstaller.UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE:
                if (mExtraIntent != null) {
                    activity.startIntentSender(mExtraIntent.getIntentSender(), /* fillInIntent= */
                            null, /* flagsMask= */ 0, FLAG_ACTIVITY_NEW_TASK, /* extraFlags= */ 0);
                } else {
                    Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                    startActivity(intent);
                }
                break;
            case PackageInstaller.UNARCHIVAL_ERROR_INSTALLER_DISABLED:
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", mInstallerPackageName, null);
                intent.setData(uri);
                startActivity(intent);
                break;
            default:
                // Do nothing. The rest of the dialogs are purely informational.
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (isAdded()) {
            getActivity().finish();
        }
    }
}
