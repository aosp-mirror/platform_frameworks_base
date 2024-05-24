/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui.fragments;

import static android.text.format.Formatter.formatFileSize;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.UninstallUserActionRequired;
import com.android.packageinstaller.v2.ui.UninstallActionListener;

/**
 * Dialog to show while requesting user confirmation for uninstalling an app.
 */
public class UninstallConfirmationFragment extends DialogFragment {

    private final UninstallUserActionRequired mDialogData;
    private UninstallActionListener mUninstallActionListener;

    private CheckBox mKeepData;

    public UninstallConfirmationFragment(UninstallUserActionRequired dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mUninstallActionListener = (UninstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getTitle())
            .setPositiveButton(R.string.ok,
                (dialogInt, which) -> mUninstallActionListener.onPositiveResponse(
                    mKeepData != null && mKeepData.isChecked()))
            .setNegativeButton(R.string.cancel,
                (dialogInt, which) -> mUninstallActionListener.onNegativeResponse());

        long appDataSize = mDialogData.getAppDataSize();
        if (appDataSize == 0) {
            builder.setMessage(mDialogData.getMessage());
        } else {
            View dialogView = getLayoutInflater().inflate(R.layout.uninstall_content_view, null);

            ((TextView) dialogView.requireViewById(R.id.message)).setText(mDialogData.getMessage());
            mKeepData = dialogView.requireViewById(R.id.keepData);
            mKeepData.setVisibility(View.VISIBLE);
            mKeepData.setText(getString(R.string.uninstall_keep_data,
                formatFileSize(getContext(), appDataSize)));

            builder.setView(dialogView);
        }
        return builder.create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mUninstallActionListener.onNegativeResponse();
    }
}
