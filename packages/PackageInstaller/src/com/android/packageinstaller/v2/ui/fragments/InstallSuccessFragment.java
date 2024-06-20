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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallSuccess;
import com.android.packageinstaller.v2.ui.InstallActionListener;
import java.util.List;

/**
 * Dialog to show on a successful installation. This dialog is shown only when the caller does not
 * want the install result back.
 */
public class InstallSuccessFragment extends DialogFragment {

    private static final String LOG_TAG = InstallSuccessFragment.class.getSimpleName();
    private final InstallSuccess mDialogData;
    private AlertDialog mDialog;
    private InstallActionListener mInstallActionListener;
    private PackageManager mPm;

    public InstallSuccessFragment(InstallSuccess dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mInstallActionListener = (InstallActionListener) context;
        mPm = context.getPackageManager();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Log.i(LOG_TAG, "Creating " + LOG_TAG + "\n" + mDialogData);
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getAppLabel())
            .setIcon(mDialogData.getAppIcon())
            .setView(dialogView)
            .setNegativeButton(R.string.done,
                (dialog, which) -> mInstallActionListener.onNegativeResponse(
                    mDialogData.getStageCode()))
            .setPositiveButton(R.string.launch, (dialog, which) -> {})
            .create();

        dialogView.requireViewById(R.id.install_success).setVisibility(View.VISIBLE);

        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Button launchButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        boolean visible = false;
        if (mDialogData.getResultIntent() != null) {
            List<ResolveInfo> list = mPm.queryIntentActivities(mDialogData.getResultIntent(), 0);
            if (list.size() > 0) {
                visible = true;
            }
        }
        if (visible) {
            launchButton.setOnClickListener(view -> {
                Log.i(LOG_TAG, "Finished installing and launching " +
                    mDialogData.getAppLabel());
                mInstallActionListener.openInstalledApp(mDialogData.getResultIntent());
            });
        } else {
            launchButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        Log.i(LOG_TAG, "Finished installing " + mDialogData.getAppLabel());
        mInstallActionListener.onNegativeResponse(mDialogData.getStageCode());
    }
}
