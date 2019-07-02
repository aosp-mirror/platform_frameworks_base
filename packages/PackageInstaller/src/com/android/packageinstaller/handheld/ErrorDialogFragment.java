/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.packageinstaller.handheld;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

import com.android.packageinstaller.UninstallerActivity;

public class ErrorDialogFragment extends DialogFragment {
    public static final String TITLE = "com.android.packageinstaller.arg.title";
    public static final String TEXT = "com.android.packageinstaller.arg.text";

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder b = new AlertDialog.Builder(getActivity())
                .setMessage(getArguments().getInt(TEXT))
                .setPositiveButton(android.R.string.ok, null);

        if (getArguments().containsKey(TITLE)) {
            b.setTitle(getArguments().getInt(TITLE));
        }

        return b.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (isAdded()) {
            if (getActivity() instanceof UninstallerActivity) {
                ((UninstallerActivity) getActivity()).dispatchAborted();
            }

            getActivity().setResult(Activity.RESULT_FIRST_USER);
            getActivity().finish();
        }
    }
}
