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
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallStage;
import com.android.packageinstaller.v2.model.InstallUserActionRequired;
import com.android.packageinstaller.v2.ui.InstallActionListener;

/**
 * Dialog to show when the source of apk can not be identified.
 */
public class AnonymousSourceFragment extends DialogFragment {

    public static String TAG = AnonymousSourceFragment.class.getSimpleName();
    @NonNull
    private InstallActionListener mInstallActionListener;
    @NonNull
    private AlertDialog mDialog;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mInstallActionListener = (InstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
       mDialog = new AlertDialog.Builder(requireContext())
            .setMessage(R.string.anonymous_source_warning)
            .setPositiveButton(R.string.anonymous_source_continue,
                ((dialog, which) -> mInstallActionListener.onPositiveResponse(
                    InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE)))
            .setNegativeButton(R.string.cancel,
                ((dialog, which) -> mInstallActionListener.onNegativeResponse(
                    InstallStage.STAGE_USER_ACTION_REQUIRED))).create();
       return mDialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mInstallActionListener.onNegativeResponse(InstallStage.STAGE_USER_ACTION_REQUIRED);
    }

    @Override
    public void onStart() {
        super.onStart();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        // This prevents tapjacking since an overlay activity started in front of Pia will
        // cause Pia to be paused.
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
    }
}
