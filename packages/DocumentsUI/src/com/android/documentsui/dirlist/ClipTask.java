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
 * limitations under the License.
 */

package com.android.documentsui.dirlist;

import android.app.Activity;
import android.os.AsyncTask;
import android.support.design.widget.Snackbar;

import com.android.documentsui.R;
import com.android.documentsui.Shared;
import com.android.documentsui.Snackbars;

/**
 * AsyncTask that performs a supplied runnable (presumably doing some clippy thing)in background,
 * then shows a toast reciting how many fantastic things have been clipped.
 */
final class ClipTask extends AsyncTask<Void, Void, Void> {

    private Runnable mOperation;
    private int mSelectionSize;
    private Activity mActivity;

    ClipTask(Activity activity, Runnable operation, int selectionSize) {
        mActivity = activity;
        mOperation = operation;
        mSelectionSize = selectionSize;
    }

    @Override
    protected Void doInBackground(Void... params) {
        // Clip operation varies (cut or past) and has different inputs.
        // To increase sharing we accept the no ins/outs operation as a plain runnable.
        mOperation.run();
        return null;
    }

    @Override
    protected void onPostExecute(Void result) {
        String msg = Shared.getQuantityString(
                mActivity,
                R.plurals.clipboard_files_clipped,
                mSelectionSize);

        Snackbars.makeSnackbar(mActivity, msg, Snackbar.LENGTH_SHORT)
                .show();
    }
}
