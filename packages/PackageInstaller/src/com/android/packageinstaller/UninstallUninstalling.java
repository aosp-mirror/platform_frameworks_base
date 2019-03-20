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

package com.android.packageinstaller;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

/**
 * Start an uninstallation, show a dialog while uninstalling and return result to the caller.
 */
public class UninstallUninstalling extends Activity implements
        EventResultPersister.EventResultObserver {
    private static final String LOG_TAG = UninstallUninstalling.class.getSimpleName();

    private static final String UNINSTALL_ID = "com.android.packageinstaller.UNINSTALL_ID";
    private static final String BROADCAST_ACTION =
            "com.android.packageinstaller.ACTION_UNINSTALL_COMMIT";

    static final String EXTRA_APP_LABEL = "com.android.packageinstaller.extra.APP_LABEL";
    static final String EXTRA_KEEP_DATA = "com.android.packageinstaller.extra.KEEP_DATA";

    private int mUninstallId;
    private ApplicationInfo mAppInfo;
    private IBinder mCallback;
    private boolean mReturnResult;
    private String mLabel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(false);

        mAppInfo = getIntent().getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        mCallback = getIntent().getIBinderExtra(PackageInstaller.EXTRA_CALLBACK);
        mReturnResult = getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        mLabel = getIntent().getStringExtra(EXTRA_APP_LABEL);

        try {
            if (savedInstanceState == null) {
                boolean allUsers = getIntent().getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS,
                        false);
                boolean keepData = getIntent().getBooleanExtra(EXTRA_KEEP_DATA, false);
                UserHandle user = getIntent().getParcelableExtra(Intent.EXTRA_USER);

                // Show dialog, which is the whole UI
                FragmentTransaction transaction = getFragmentManager().beginTransaction();
                Fragment prev = getFragmentManager().findFragmentByTag("dialog");
                if (prev != null) {
                    transaction.remove(prev);
                }
                DialogFragment dialog = new UninstallUninstallingFragment();
                dialog.setCancelable(false);
                dialog.show(transaction, "dialog");

                mUninstallId = UninstallEventReceiver.addObserver(this,
                        EventResultPersister.GENERATE_NEW_ID, this);

                Intent broadcastIntent = new Intent(BROADCAST_ACTION);
                broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, mUninstallId);
                broadcastIntent.setPackage(getPackageName());

                PendingIntent pendingIntent = PendingIntent.getBroadcast(this, mUninstallId,
                        broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                int flags = allUsers ? PackageManager.DELETE_ALL_USERS : 0;
                flags |= keepData ? PackageManager.DELETE_KEEP_DATA : 0;

                try {
                    ActivityThread.getPackageManager().getPackageInstaller().uninstall(
                            new VersionedPackage(mAppInfo.packageName,
                                    PackageManager.VERSION_CODE_HIGHEST),
                            getPackageName(), flags, pendingIntent.getIntentSender(),
                            user.getIdentifier());
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            } else {
                mUninstallId = savedInstanceState.getInt(UNINSTALL_ID);
                UninstallEventReceiver.addObserver(this, mUninstallId, this);
            }
        } catch (EventResultPersister.OutOfIdsException | IllegalArgumentException e) {
            Log.e(LOG_TAG, "Fails to start uninstall", e);
            onResult(PackageInstaller.STATUS_FAILURE, PackageManager.DELETE_FAILED_INTERNAL_ERROR,
                    null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(UNINSTALL_ID, mUninstallId);
    }

    @Override
    public void onBackPressed() {
        // do nothing
    }

    @Override
    public void onResult(int status, int legacyStatus, @Nullable String message) {
        if (mCallback != null) {
            // The caller will be informed about the result via a callback
            final IPackageDeleteObserver2 observer = IPackageDeleteObserver2.Stub
                    .asInterface(mCallback);
            try {
                observer.onPackageDeleted(mAppInfo.packageName, legacyStatus, message);
            } catch (RemoteException ignored) {
            }
        } else if (mReturnResult) {
            // The caller will be informed about the result and might decide to display it
            Intent result = new Intent();

            result.putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus);
            setResult(status == PackageInstaller.STATUS_SUCCESS ? Activity.RESULT_OK
                    : Activity.RESULT_FIRST_USER, result);
        } else {
            // This is the rare case that the caller did not ask for the result, but wanted to be
            // notified via onActivityResult when the installation finishes
            if (status != PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(this, getString(R.string.uninstall_failed_app, mLabel),
                        Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        UninstallEventReceiver.removeObserver(this, mUninstallId);

        super.onDestroy();
    }

    /**
     * Dialog that shows that the app is uninstalling.
     */
    public static class UninstallUninstallingFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());

            dialogBuilder.setCancelable(false);
            dialogBuilder.setMessage(getActivity().getString(R.string.uninstalling_app,
                    ((UninstallUninstalling) getActivity()).mLabel));

            Dialog dialog = dialogBuilder.create();
            dialog.setCanceledOnTouchOutside(false);

            return dialog;
        }
    }
}
