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
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallUserActionRequired;
import com.android.packageinstaller.v2.ui.InstallActionListener;

/**
 * Dialog to show when the requesting user confirmation for installing an app.
 */
public class InstallConfirmationFragment extends DialogFragment {

    public static final String LOG_TAG = InstallConfirmationFragment.class.getSimpleName();
    @NonNull
    private final InstallUserActionRequired mDialogData;
    @NonNull
    private InstallActionListener mInstallActionListener;
    @NonNull
    private AlertDialog mDialog;

    public InstallConfirmationFragment(@NonNull InstallUserActionRequired dialogData) {
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
        Log.i(LOG_TAG, "Creating " + LOG_TAG + "\n" + mDialogData);
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);

        int positiveBtnTextRes;
        if (mDialogData.isAppUpdating()) {
            if (mDialogData.getSourceApp() != null) {
                positiveBtnTextRes = R.string.update_anyway;
            } else {
                positiveBtnTextRes = R.string.update;
            }
        } else {
            positiveBtnTextRes = R.string.install;
        }

        mDialog = new AlertDialog.Builder(requireContext())
            .setIcon(mDialogData.getAppIcon())
            .setTitle(mDialogData.getAppLabel())
            .setView(dialogView)
            .setPositiveButton(positiveBtnTextRes,
                (dialogInt, which) -> mInstallActionListener.onPositiveResponse(
                    InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION))
            .setNegativeButton(R.string.cancel,
                (dialogInt, which) -> mInstallActionListener.onNegativeResponse(
                    mDialogData.getStageCode()))
            .create();

        TextView viewToEnable;
        if (mDialogData.isAppUpdating()) {
            viewToEnable = dialogView.requireViewById(R.id.install_confirm_question_update);
            String sourcePackageName = mDialogData.getSourceApp();
            if (sourcePackageName != null) {
                // Show the update-ownership change message
                viewToEnable.setText(Html.fromHtml(sourcePackageName, Html.FROM_HTML_MODE_LEGACY));
            }
        } else {
            viewToEnable = dialogView.requireViewById(R.id.install_confirm_question);
        }
        viewToEnable.setVisibility(View.VISIBLE);
        viewToEnable.setMovementMethod(new ScrollingMovementMethod());

        return mDialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mInstallActionListener.onNegativeResponse(mDialogData.getStageCode());
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
