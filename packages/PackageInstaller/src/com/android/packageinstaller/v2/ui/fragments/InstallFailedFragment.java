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

package com.android.packageinstaller.v2.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallFailed;
import com.android.packageinstaller.v2.ui.InstallActionListener;

/**
 * Dialog to show when the installation failed. Depending on the failure code, an appropriate
 * message would be shown to the user. This dialog is shown only when the caller does not want the
 * install result back.
 */
public class InstallFailedFragment extends DialogFragment {

    private static final String TAG = InstallFailedFragment.class.getSimpleName();
    private final InstallFailed mDialogData;
    private InstallActionListener mInstallActionListener;

    public InstallFailedFragment(InstallFailed dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mInstallActionListener = (InstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getAppLabel())
            .setIcon(mDialogData.getAppIcon())
            .setView(dialogView)
            .setPositiveButton(R.string.done,
                (dialogInt, which) -> mInstallActionListener.onNegativeResponse(
                    mDialogData.getStageCode()))
            .create();
        setExplanationFromErrorCode(mDialogData.getStatusCode(), dialogView);

        return dialog;
    }

    /**
     * Unhide the appropriate label for the statusCode.
     *
     * @param statusCode The status code from the package installer.
     */
    private void setExplanationFromErrorCode(int statusCode, View dialogView) {
        Log.d(TAG, "Installation status code: " + statusCode);

        View viewToEnable;
        switch (statusCode) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                viewToEnable = dialogView.requireViewById(R.id.install_failed_blocked);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                viewToEnable = dialogView.requireViewById(R.id.install_failed_conflict);
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                viewToEnable = dialogView.requireViewById(R.id.install_failed_incompatible);
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                viewToEnable = dialogView.requireViewById(R.id.install_failed_invalid_apk);
                break;
            default:
                viewToEnable = dialogView.requireViewById(R.id.install_failed);
                break;
        }

        viewToEnable.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mInstallActionListener.onNegativeResponse(mDialogData.getStageCode());
    }
}
