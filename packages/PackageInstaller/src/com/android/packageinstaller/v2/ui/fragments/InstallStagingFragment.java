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
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;

public class InstallStagingFragment extends DialogFragment {

    private static final String TAG = InstallStagingFragment.class.getSimpleName();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.staging).setVisibility(View.VISIBLE);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.app_name_unknown))
            .setIcon(R.drawable.ic_file_download)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create();

        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }
}
