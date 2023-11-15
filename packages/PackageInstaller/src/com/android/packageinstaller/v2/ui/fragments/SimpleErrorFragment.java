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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;

public class SimpleErrorFragment extends DialogFragment {

    private static final String TAG = SimpleErrorFragment.class.getSimpleName();
    private final int mMessageResId;

    public SimpleErrorFragment(int messageResId) {
        mMessageResId = messageResId;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new AlertDialog.Builder(getActivity())
            .setMessage(mMessageResId)
            .setPositiveButton(R.string.ok, (dialog, which) -> getActivity().finish())
            .create();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        getActivity().setResult(Activity.RESULT_CANCELED);
        getActivity().finish();
    }
}
