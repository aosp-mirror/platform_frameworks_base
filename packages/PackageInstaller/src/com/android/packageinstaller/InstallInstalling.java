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

import static android.content.pm.PackageInstaller.SessionParams.UID_UNKNOWN;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.android.internal.app.AlertActivity;
import com.android.internal.content.InstallLocationUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Send package to the package manager and handle results from package manager. Once the
 * installation succeeds, start {@link InstallSuccess} or {@link InstallFailed}.
 * <p>This has two phases: First send the data to the package manager, then wait until the package
 * manager processed the result.</p>
 */
public class InstallInstalling extends AlertActivity {
    private static final String LOG_TAG = InstallInstalling.class.getSimpleName();

    private static final String SESSION_ID = "com.android.packageinstaller.SESSION_ID";
    private static final String INSTALL_ID = "com.android.packageinstaller.INSTALL_ID";

    private static final String BROADCAST_ACTION =
            "com.android.packageinstaller.ACTION_INSTALL_COMMIT";

    /** Task that sends the package to the package installer */
    private InstallingAsyncTask mInstallingTask;

    /** Id of the session to install the package */
    private int mSessionId;

    /** Id of the install event we wait for */
    private int mInstallId;

    /** URI of package to install */
    private Uri mPackageURI;

    /** The button that can cancel this dialog */
    private Button mCancelButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ApplicationInfo appInfo = getIntent()
                .getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        mPackageURI = getIntent().getData();

        if ("package".equals(mPackageURI.getScheme())) {
            try {
                getPackageManager().installExistingPackage(appInfo.packageName);
                launchSuccess();
            } catch (PackageManager.NameNotFoundException e) {
                launchFailure(PackageInstaller.STATUS_FAILURE,
                        PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
            }
        } else {
            final File sourceFile = new File(mPackageURI.getPath());
            PackageUtil.AppSnippet as = PackageUtil.getAppSnippet(this, appInfo, sourceFile);

            mAlert.setIcon(as.icon);
            mAlert.setTitle(as.label);
            mAlert.setView(R.layout.install_content_view);
            mAlert.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel),
                    (ignored, ignored2) -> {
                        if (mInstallingTask != null) {
                            mInstallingTask.cancel(true);
                        }

                        if (mSessionId > 0) {
                            getPackageManager().getPackageInstaller().abandonSession(mSessionId);
                            mSessionId = 0;
                        }

                        setResult(RESULT_CANCELED);
                        finish();
                    }, null);
            setupAlert();
            requireViewById(R.id.installing).setVisibility(View.VISIBLE);

            if (savedInstanceState != null) {
                mSessionId = savedInstanceState.getInt(SESSION_ID);
                mInstallId = savedInstanceState.getInt(INSTALL_ID);

                // Reregister for result; might instantly call back if result was delivered while
                // activity was destroyed
                try {
                    InstallEventReceiver.addObserver(this, mInstallId,
                            this::launchFinishBasedOnResult);
                } catch (EventResultPersister.OutOfIdsException e) {
                    // Does not happen
                }
            } else {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                final Uri referrerUri = getIntent().getParcelableExtra(Intent.EXTRA_REFERRER);
                params.setPackageSource(
                        referrerUri != null ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                                : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
                params.setInstallAsInstantApp(false);
                params.setReferrerUri(referrerUri);
                params.setOriginatingUri(getIntent()
                        .getParcelableExtra(Intent.EXTRA_ORIGINATING_URI));
                params.setOriginatingUid(getIntent().getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                        UID_UNKNOWN));
                params.setInstallerPackageName(getIntent().getStringExtra(
                        Intent.EXTRA_INSTALLER_PACKAGE_NAME));
                params.setInstallReason(PackageManager.INSTALL_REASON_USER);

                File file = new File(mPackageURI.getPath());
                try {
                    final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
                    final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                            input.reset(), file, /* flags */ 0);
                    if (result.isError()) {
                        Log.e(LOG_TAG, "Cannot parse package " + file + ". Assuming defaults.");
                        Log.e(LOG_TAG,
                                "Cannot calculate installed size " + file + ". Try only apk size.");
                        params.setSize(file.length());
                    } else {
                        final PackageLite pkg = result.getResult();
                        params.setAppPackageName(pkg.getPackageName());
                        params.setInstallLocation(pkg.getInstallLocation());
                        params.setSize(InstallLocationUtils.calculateInstalledSize(pkg,
                                params.abiOverride));
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG,
                            "Cannot calculate installed size " + file + ". Try only apk size.");
                    params.setSize(file.length());
                }

                try {
                    mInstallId = InstallEventReceiver
                            .addObserver(this, EventResultPersister.GENERATE_NEW_ID,
                                    this::launchFinishBasedOnResult);
                } catch (EventResultPersister.OutOfIdsException e) {
                    launchFailure(PackageInstaller.STATUS_FAILURE,
                            PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
                }

                try {
                    mSessionId = getPackageManager().getPackageInstaller().createSession(params);
                } catch (IOException e) {
                    launchFailure(PackageInstaller.STATUS_FAILURE,
                            PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
                }
            }

            mCancelButton = mAlert.getButton(DialogInterface.BUTTON_NEGATIVE);
        }
    }

    /**
     * Launch the "success" version of the final package installer dialog
     */
    private void launchSuccess() {
        Intent successIntent = new Intent(getIntent());
        successIntent.setClass(this, InstallSuccess.class);
        successIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        startActivity(successIntent);
        finish();
    }

    /**
     * Launch the "failure" version of the final package installer dialog
     *
     * @param statusCode    The generic status code as returned by the package installer.
     * @param legacyStatus  The status as used internally in the package manager.
     * @param statusMessage The status description.
     */
    private void launchFailure(int statusCode, int legacyStatus, String statusMessage) {
        Intent failureIntent = new Intent(getIntent());
        failureIntent.setClass(this, InstallFailed.class);
        failureIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        failureIntent.putExtra(PackageInstaller.EXTRA_STATUS, statusCode);
        failureIntent.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, legacyStatus);
        failureIntent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, statusMessage);

        startActivity(failureIntent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // This is the first onResume in a single life of the activity
        if (mInstallingTask == null) {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo sessionInfo = installer.getSessionInfo(mSessionId);

            if (sessionInfo != null && !sessionInfo.isActive()) {
                mInstallingTask = new InstallingAsyncTask();
                mInstallingTask.execute();
            } else {
                // we will receive a broadcast when the install is finished
                mCancelButton.setEnabled(false);
                setFinishOnTouchOutside(false);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(SESSION_ID, mSessionId);
        outState.putInt(INSTALL_ID, mInstallId);
    }

    @Override
    public void onBackPressed() {
        if (mCancelButton.isEnabled()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (mInstallingTask != null) {
            mInstallingTask.cancel(true);
            synchronized (mInstallingTask) {
                while (!mInstallingTask.isDone) {
                    try {
                        mInstallingTask.wait();
                    } catch (InterruptedException e) {
                        Log.i(LOG_TAG, "Interrupted while waiting for installing task to cancel",
                                e);
                    }
                }
            }
        }

        InstallEventReceiver.removeObserver(this, mInstallId);

        super.onDestroy();
    }

    /**
     * Launch the appropriate finish activity (success or failed) for the installation result.
     *
     * @param statusCode    The installation result.
     * @param legacyStatus  The installation as used internally in the package manager.
     * @param statusMessage The detailed installation result.
     */
    private void launchFinishBasedOnResult(int statusCode, int legacyStatus, String statusMessage) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            launchSuccess();
        } else {
            launchFailure(statusCode, legacyStatus, statusMessage);
        }
    }

    /**
     * Send the package to the package installer and then register a event result observer that
     * will call {@link #launchFinishBasedOnResult(int, int, String)}
     */
    private final class InstallingAsyncTask extends AsyncTask<Void, Void,
            PackageInstaller.Session> {
        volatile boolean isDone;

        @Override
        protected PackageInstaller.Session doInBackground(Void... params) {
            PackageInstaller.Session session;
            try {
                session = getPackageManager().getPackageInstaller().openSession(mSessionId);
            } catch (IOException e) {
                synchronized (this) {
                    isDone = true;
                    notifyAll();
                }
                return null;
            }

            session.setStagingProgress(0);

            try {
                File file = new File(mPackageURI.getPath());

                try (InputStream in = new FileInputStream(file)) {
                    long sizeBytes = file.length();
                    try (OutputStream out = session
                            .openWrite("PackageInstaller", 0, sizeBytes)) {
                        byte[] buffer = new byte[1024 * 1024];
                        while (true) {
                            int numRead = in.read(buffer);

                            if (numRead == -1) {
                                session.fsync(out);
                                break;
                            }

                            if (isCancelled()) {
                                session.close();
                                break;
                            }

                            out.write(buffer, 0, numRead);
                            if (sizeBytes > 0) {
                                float fraction = ((float) numRead / (float) sizeBytes);
                                session.addProgress(fraction);
                            }
                        }
                    }
                }

                return session;
            } catch (IOException | SecurityException e) {
                Log.e(LOG_TAG, "Could not write package", e);

                session.close();

                return null;
            } finally {
                synchronized (this) {
                    isDone = true;
                    notifyAll();
                }
            }
        }

        @Override
        protected void onPostExecute(PackageInstaller.Session session) {
            if (session != null) {
                Intent broadcastIntent = new Intent(BROADCAST_ACTION);
                broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                broadcastIntent.setPackage(getPackageName());
                broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, mInstallId);

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        InstallInstalling.this,
                        mInstallId,
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

                session.commit(pendingIntent.getIntentSender());
                mCancelButton.setEnabled(false);
                setFinishOnTouchOutside(false);
            } else {
                getPackageManager().getPackageInstaller().abandonSession(mSessionId);

                if (!isCancelled()) {
                    launchFailure(PackageInstaller.STATUS_FAILURE,
                            PackageManager.INSTALL_FAILED_INVALID_APK, null);
                }
            }
        }
    }
}
