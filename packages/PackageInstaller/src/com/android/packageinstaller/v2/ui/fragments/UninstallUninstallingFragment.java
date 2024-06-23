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

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.UninstallUninstalling;

/**
 * Dialog to show that the app is uninstalling.
 */
public class UninstallUninstallingFragment extends DialogFragment {

    UninstallUninstalling mDialogData;

    public UninstallUninstallingFragment(UninstallUninstalling dialogData) {
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
            .setCancelable(false);
        if (mDialogData.isCloneUser()) {
            builder.setTitle(requireContext().getString(R.string.uninstalling_cloned_app,
                mDialogData.getAppLabel()));
        } else {
            builder.setTitle(requireContext().getString(R.string.uninstalling_app,
                mDialogData.getAppLabel()));
        }
        Dialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);

        return dialog;
    }
}
