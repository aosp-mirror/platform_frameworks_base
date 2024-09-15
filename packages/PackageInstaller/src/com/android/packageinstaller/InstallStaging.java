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

import static android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH;

import static com.android.packageinstaller.PackageInstallerActivity.EXTRA_STAGED_SESSION_ID;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.Manifest;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * If a package gets installed from a content URI this step stages the installation session
 * reading bytes from the URI.
 */
public class InstallStaging extends Activity {
    private static final String LOG_TAG = InstallStaging.class.getSimpleName();

    private static final String STAGED_SESSION_ID = "STAGED_SESSION_ID";

    private @Nullable PackageInstaller mInstaller;

    /** Currently running task that loads the file from the content URI into a file */
    private @Nullable StagingAsyncTask mStagingTask;

    /** The session the package is in */
    private int mStagedSessionId;

    private AlertDialog mDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mInstaller = getPackageManager().getPackageInstaller();

        setFinishOnTouchOutside(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setIcon(R.drawable.ic_file_download);
        builder.setTitle(getString(R.string.app_name_unknown));
        builder.setView(R.layout.install_content_view);
        builder.setNegativeButton(getString(R.string.cancel),
                (ignored, ignored2) -> {
                    if (mStagingTask != null) {
                        mStagingTask.cancel(true);
                    }

                    cleanupStagingSession();

                    setResult(RESULT_CANCELED);
                    finish();
                });
        builder.setOnCancelListener(dialog -> {
            if (mStagingTask != null) {
                mStagingTask.cancel(true);
            }

            cleanupStagingSession();

            setResult(RESULT_CANCELED);
            finish();
        });
        mDialog = builder.create();
        mDialog.show();
        mDialog.requireViewById(com.android.packageinstaller.R.id.staging)
            .setVisibility(View.VISIBLE);

        if (savedInstanceState != null) {
            mStagedSessionId = savedInstanceState.getInt(STAGED_SESSION_ID, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // This is the first onResume in a single life of the activity.
        if (mStagingTask == null) {
            if (mStagedSessionId > 0) {
                final PackageInstaller.SessionInfo info = mInstaller.getSessionInfo(
                        mStagedSessionId);
                if (info == null || !info.isActive() || info.getResolvedBaseApkPath() == null) {
                    Log.w(LOG_TAG, "Session " + mStagedSessionId + " in funky state; ignoring");
                    if (info != null) {
                        cleanupStagingSession();
                    }
                    mStagedSessionId = 0;
                }
            }

            // Session does not exist, or became invalid.
            if (mStagedSessionId <= 0) {
                // Create session here to be able to show error.
                final Uri packageUri = getIntent().getData();
                final AssetFileDescriptor afd = openAssetFileDescriptor(packageUri);
                try {
                    ParcelFileDescriptor pfd = afd != null ? afd.getParcelFileDescriptor() : null;
                    PackageInstaller.SessionParams params = createSessionParams(
                            mInstaller, getIntent(), pfd, packageUri.toString());
                    mStagedSessionId = mInstaller.createSession(params);
                } catch (IOException e) {
                    Log.w(LOG_TAG, "Failed to create a staging session", e);
                    showError();
                    return;
                } finally {
                    PackageUtil.safeClose(afd);
                }
            }

            mStagingTask = new StagingAsyncTask();
            mStagingTask.execute();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(STAGED_SESSION_ID, mStagedSessionId);
    }

    @Override
    protected void onDestroy() {
        if (mStagingTask != null) {
            mStagingTask.cancel(true);
        }
        if (mDialog != null) {
            mDialog.dismiss();
        }
        super.onDestroy();
    }

    private AssetFileDescriptor openAssetFileDescriptor(Uri uri) {
        try {
            return getContentResolver().openAssetFileDescriptor(uri, "r");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to open asset file descriptor", e);
            return null;
        }
    }

    private static PackageInstaller.SessionParams createSessionParams(
            @NonNull PackageInstaller installer, @NonNull Intent intent,
            @Nullable ParcelFileDescriptor pfd, @NonNull String debugPathName) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        final Uri referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
        params.setPackageSource(
                referrerUri != null ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                        : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
        params.setInstallAsInstantApp(false);
        params.setReferrerUri(referrerUri);
        params.setOriginatingUri(intent
                .getParcelableExtra(Intent.EXTRA_ORIGINATING_URI));
        params.setOriginatingUid(intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                Process.INVALID_UID));
        params.setInstallerPackageName(intent.getStringExtra(
                Intent.EXTRA_INSTALLER_PACKAGE_NAME));
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // Disable full screen intent usage by for sideloads.
        params.setPermissionState(Manifest.permission.USE_FULL_SCREEN_INTENT,
                PackageInstaller.SessionParams.PERMISSION_STATE_DENIED);

        if (pfd != null) {
            try {
                final PackageInstaller.InstallInfo result = installer.readInstallInfo(pfd,
                        debugPathName, 0);
                params.setAppPackageName(result.getPackageName());
                params.setInstallLocation(result.getInstallLocation());
                params.setSize(result.calculateInstalledSize(params, pfd));
            } catch (PackageInstaller.PackageParsingException | IOException e) {
                Log.e(LOG_TAG, "Cannot parse package " + debugPathName + ". Assuming defaults.", e);
                Log.e(LOG_TAG,
                        "Cannot calculate installed size " + debugPathName
                                + ". Try only apk size.");
                params.setSize(pfd.getStatSize());
            }
        } else {
            Log.e(LOG_TAG, "Cannot parse package " + debugPathName + ". Assuming defaults.");
        }
        return params;
    }

    private void cleanupStagingSession() {
        if (mStagedSessionId > 0) {
            try {
                mInstaller.abandonSession(mStagedSessionId);
            } catch (SecurityException ignored) {

            }
            mStagedSessionId = 0;
        }
    }

    /**
     * Show an error message and set result as error.
     */
    private void showError() {
        getFragmentManager().beginTransaction()
                .add(new ErrorDialog(), "error").commitAllowingStateLoss();

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

    private final class StagingAsyncTask extends
            AsyncTask<Void, Integer, PackageInstaller.SessionInfo> {
        private ProgressBar mProgressBar = null;

        private long getContentSizeBytes() {
            try (AssetFileDescriptor afd = openAssetFileDescriptor(getIntent().getData())) {
                return afd != null ? afd.getLength() : UNKNOWN_LENGTH;
            } catch (IOException ignored) {
                return UNKNOWN_LENGTH;
            }
        }

        @Override
        protected void onPreExecute() {
            final long sizeBytes = getContentSizeBytes();
            if (sizeBytes > 0 && mDialog != null) {
                mProgressBar = mDialog.requireViewById(R.id.progress_indeterminate);
            }
            if (mProgressBar != null) {
                mProgressBar.setProgress(0);
                mProgressBar.setMax(100);
                mProgressBar.setIndeterminate(false);
            }
        }

        @Override
        protected PackageInstaller.SessionInfo doInBackground(Void... params) {
            Uri packageUri = getIntent().getData();
            try (PackageInstaller.Session session = mInstaller.openSession(mStagedSessionId);
                 InputStream in = getContentResolver().openInputStream(packageUri)) {
                session.setStagingProgress(0);

                if (in == null) {
                    return null;
                }

                long sizeBytes = getContentSizeBytes();

                long totalRead = 0;
                try (OutputStream out = session.openWrite("PackageInstaller", 0, sizeBytes)) {
                    byte[] buffer = new byte[1024 * 1024];
                    while (true) {
                        int numRead = in.read(buffer);

                        if (numRead == -1) {
                            session.fsync(out);
                            break;
                        }

                        if (isCancelled()) {
                            break;
                        }

                        out.write(buffer, 0, numRead);
                        if (sizeBytes > 0) {
                            totalRead += numRead;
                            float fraction = ((float) totalRead / (float) sizeBytes);
                            session.setStagingProgress(fraction);
                            publishProgress((int) (fraction * 100.0));
                        }
                    }
                }

                return mInstaller.getSessionInfo(mStagedSessionId);
            } catch (IOException | SecurityException | IllegalStateException
                     | IllegalArgumentException e) {
                Log.w(LOG_TAG, "Error staging apk from content URI", e);
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            if (mProgressBar != null && progress != null && progress.length > 0) {
                mProgressBar.setProgress(progress[0], true);
            }
        }

        @Override
        protected void onPostExecute(PackageInstaller.SessionInfo sessionInfo) {
            if (sessionInfo == null || !sessionInfo.isActive()
                    || sessionInfo.getResolvedBaseApkPath() == null) {
                Log.w(LOG_TAG, "Session info is invalid: " + sessionInfo);
                cleanupStagingSession();
                showError();
                return;
            }

            // Pass the staged session to the installer.
            Intent installIntent = new Intent(getIntent());
            installIntent.setClass(InstallStaging.this, DeleteStagedFileOnResult.class);
            installIntent.setData(Uri.fromFile(new File(sessionInfo.getResolvedBaseApkPath())));

            installIntent.putExtra(EXTRA_STAGED_SESSION_ID, mStagedSessionId);

            if (installIntent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
                installIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            }

            installIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

            startActivity(installIntent);

            InstallStaging.this.finish();
        }
    }
}
