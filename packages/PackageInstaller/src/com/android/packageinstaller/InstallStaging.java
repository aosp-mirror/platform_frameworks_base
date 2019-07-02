/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.android.internal.app.AlertActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * If a package gets installed from an content URI this step loads the package and turns it into
 * and installation from a file. Then it re-starts the installation as usual.
 */
public class InstallStaging extends AlertActivity {
    private static final String LOG_TAG = InstallStaging.class.getSimpleName();

    private static final String STAGED_FILE = "STAGED_FILE";

    /** Currently running task that loads the file from the content URI into a file */
    private @Nullable StagingAsyncTask mStagingTask;

    /** The file the package is in */
    private @Nullable File mStagedFile;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAlert.setIcon(R.drawable.ic_file_download);
        mAlert.setTitle(getString(R.string.app_name_unknown));
        mAlert.setView(R.layout.install_content_view);
        mAlert.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel),
                (ignored, ignored2) -> {
                    if (mStagingTask != null) {
                        mStagingTask.cancel(true);
                    }
                    setResult(RESULT_CANCELED);
                    finish();
                }, null);
        setupAlert();
        requireViewById(R.id.staging).setVisibility(View.VISIBLE);

        if (savedInstanceState != null) {
            mStagedFile = new File(savedInstanceState.getString(STAGED_FILE));

            if (!mStagedFile.exists()) {
                mStagedFile = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // This is the first onResume in a single life of the activity
        if (mStagingTask == null) {
            // File does not exist, or became invalid
            if (mStagedFile == null) {
                // Create file delayed to be able to show error
                try {
                    mStagedFile = TemporaryFileManager.getStagedFile(this);
                } catch (IOException e) {
                    showError();
                    return;
                }
            }

            mStagingTask = new StagingAsyncTask();
            mStagingTask.execute(getIntent().getData());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(STAGED_FILE, mStagedFile.getPath());
    }

    @Override
    protected void onDestroy() {
        if (mStagingTask != null) {
            mStagingTask.cancel(true);
        }

        super.onDestroy();
    }

    /**
     * Show an error message and set result as error.
     */
    private void showError() {
        (new ErrorDialog()).showAllowingStateLoss(getFragmentManager(), "error");

        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_INSTALL_RESULT,
                PackageManager.INSTALL_FAILED_INVALID_APK);
        setResult(RESULT_FIRST_USER, result);
    }

    /**
     * Dialog for errors while staging.
     */
    public static class ErrorDialog extends DialogFragment {
        private Activity mActivity;

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);

            mActivity = (Activity) context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog alertDialog = new AlertDialog.Builder(mActivity)
                    .setMessage(R.string.Parse_error_dlg_text)
                    .setPositiveButton(R.string.ok,
                            (dialog, which) -> mActivity.finish())
                    .create();
            alertDialog.setCanceledOnTouchOutside(false);

            return alertDialog;
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);

            mActivity.finish();
        }
    }

    private final class StagingAsyncTask extends AsyncTask<Uri, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Uri... params) {
            if (params == null || params.length <= 0) {
                return false;
            }
            Uri packageUri = params[0];
            try (InputStream in = getContentResolver().openInputStream(packageUri)) {
                // Despite the comments in ContentResolver#openInputStream the returned stream can
                // be null.
                if (in == null) {
                    return false;
                }

                try (OutputStream out = new FileOutputStream(mStagedFile)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) >= 0) {
                        // Be nice and respond to a cancellation
                        if (isCancelled()) {
                            return false;
                        }
                        out.write(buffer, 0, bytesRead);
                    }
                }
            } catch (IOException | SecurityException | IllegalStateException e) {
                Log.w(LOG_TAG, "Error staging apk from content URI", e);
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                // Now start the installation again from a file
                Intent installIntent = new Intent(getIntent());
                installIntent.setClass(InstallStaging.this, DeleteStagedFileOnResult.class);
                installIntent.setData(Uri.fromFile(mStagedFile));

                if (installIntent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
                }

                installIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(installIntent);

                InstallStaging.this.finish();
            } else {
                showError();
            }
        }
    }
}
