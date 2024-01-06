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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

public class UnarchiveFragment extends DialogFragment implements
        DialogInterface.OnClickListener {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        String appTitle = getArguments().getString(UnarchiveActivity.APP_TITLE);
        String installerTitle = getArguments().getString(UnarchiveActivity.INSTALLER_TITLE);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());

        dialogBuilder.setTitle(
                String.format(getContext().getString(R.string.unarchive_application_title),
                        appTitle, installerTitle));
        dialogBuilder.setMessage(R.string.unarchive_body_text);

        dialogBuilder.setPositiveButton(R.string.restore, this);
        dialogBuilder.setNegativeButton(android.R.string.cancel, this);

        return dialogBuilder.create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == Dialog.BUTTON_POSITIVE) {
            ((UnarchiveActivity) getActivity()).startUnarchive();
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
