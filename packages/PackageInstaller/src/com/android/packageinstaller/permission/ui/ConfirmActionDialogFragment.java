/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.fragment.app.DialogFragment;

import com.android.packageinstaller.R;

public final class ConfirmActionDialogFragment extends DialogFragment {
    public static final String ARG_MESSAGE = "MESSAGE";
    public static final String ARG_ACTION = "ACTION";

    public interface OnActionConfirmedListener {
        void onActionConfirmed(String action);
    }

    public static ConfirmActionDialogFragment newInstance(CharSequence message, String action) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(ARG_MESSAGE, message);
        arguments.putString(ARG_ACTION, action);
        ConfirmActionDialogFragment fragment = new ConfirmActionDialogFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle bundle) {
        return new AlertDialog.Builder(getContext())
                .setMessage(getArguments().getString(ARG_MESSAGE))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.grant_dialog_button_deny_anyway,
                        (dialog, which) -> {
                            Activity activity = getActivity();
                            if (activity instanceof OnActionConfirmedListener) {
                                String groupName = getArguments().getString(ARG_ACTION);
                                ((OnActionConfirmedListener) activity)
                                        .onActionConfirmed(groupName);
                            }
                        })
        .create();
    }
}
