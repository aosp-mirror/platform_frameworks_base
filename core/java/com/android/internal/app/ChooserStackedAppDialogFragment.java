/*
 * Copyright (C) 2019 The Android Open Source Project
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


package com.android.internal.app;

import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;

import com.android.internal.app.chooser.MultiDisplayResolveInfo;

/**
 * Shows individual actions for a "stacked" app target - such as an app with multiple posting
 * streams represented in the Sharesheet.
 */
public class ChooserStackedAppDialogFragment extends DialogFragment
        implements DialogInterface.OnClickListener {
    private static final String TITLE_KEY = "title";
    private static final String PINNED_KEY = "pinned";

    private MultiDisplayResolveInfo mTargetInfos;
    private CharSequence[] mLabels;
    private int mParentWhich;

    public ChooserStackedAppDialogFragment() {
    }

    public ChooserStackedAppDialogFragment(CharSequence title,
            MultiDisplayResolveInfo targets, CharSequence[] labels, int parentWhich) {
        Bundle args = new Bundle();
        args.putCharSequence(TITLE_KEY, title);
        mTargetInfos = targets;
        mLabels = labels;
        mParentWhich = parentWhich;
        setArguments(args);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Bundle args = getArguments();
        return new Builder(getContext())
                .setCancelable(true)
                .setItems(mLabels, this)
                .setTitle(args.getCharSequence(TITLE_KEY))
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final Bundle args = getArguments();
        mTargetInfos.setSelected(which);
        ((ChooserActivity) getActivity()).startSelected(mParentWhich, false, true);
        dismiss();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Dismiss on config changed (eg: rotation)
        // TODO: Maintain state on config change
        super.onConfigurationChanged(newConfig);
        dismiss();
    }
}
