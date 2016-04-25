/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.shell;

import static com.android.shell.BugreportPrefs.STATE_HIDE;
import static com.android.shell.BugreportPrefs.STATE_SHOW;
import static com.android.shell.BugreportPrefs.STATE_UNKNOWN;
import static com.android.shell.BugreportPrefs.getWarningState;
import static com.android.shell.BugreportPrefs.setWarningState;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.CheckBox;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

/**
 * Dialog that warns about contents of a bugreport.
 */
public class BugreportWarningActivity extends AlertActivity
        implements DialogInterface.OnClickListener {

    private Intent mSendIntent;
    private CheckBox mConfirmRepeat;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mSendIntent = getIntent().getParcelableExtra(Intent.EXTRA_INTENT);

        // We need to touch the extras to unpack them so they get migrated to
        // ClipData correctly.
        mSendIntent.hasExtra(Intent.EXTRA_STREAM);

        final AlertController.AlertParams ap = mAlertParams;
        ap.mView = LayoutInflater.from(this).inflate(R.layout.confirm_repeat, null);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        mConfirmRepeat = (CheckBox) ap.mView.findViewById(android.R.id.checkbox);

        final int state = getWarningState(this, STATE_UNKNOWN);
        final boolean checked;
        if (Build.TYPE.equals("user")) {
            checked = state == STATE_HIDE; // Only checks if specifically set to.
        } else {
            checked = state != STATE_SHOW; // Checks by default.
        }
        mConfirmRepeat.setChecked(checked);

        setupAlert();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) {
            // Remember confirm state, and launch target
            setWarningState(this, mConfirmRepeat.isChecked() ? STATE_HIDE : STATE_SHOW);
            startActivity(mSendIntent);
        }

        finish();
    }
}
