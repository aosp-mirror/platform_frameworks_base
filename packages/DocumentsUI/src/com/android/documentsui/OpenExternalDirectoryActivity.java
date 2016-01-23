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

package com.android.documentsui;

import static android.os.Environment.isStandardDirectory;
import static com.android.documentsui.Shared.DEBUG;

import java.io.File;
import java.io.IOException;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

/**
 * Activity responsible for handling {@link Intent#ACTION_OPEN_EXTERNAL_DOCUMENT}.
 */
public class OpenExternalDirectoryActivity extends Activity {
    private static final String TAG = "OpenExternalDirectoryActivity";
    private static final String FM_TAG = "open_external_directory";
    private static final String EXTERNAL_STORAGE_AUTH = "com.android.externalstorage.documents";
    private static final String EXTRA_FILE = "com.android.documentsui.FILE";
    private static final String EXTRA_APP_LABEL = "com.android.documentsui.APP_LABEL";
    private static final String EXTRA_VOLUME_LABEL = "com.android.documentsui.VOLUME_LABEL";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        if (intent == null || intent.getData() == null) {
            Log.d(TAG, "missing intent or intent data: " + intent);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final String path = intent.getData().getPath();
        final int userId = UserHandle.myUserId();
        if (!showFragment(this, userId, path)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
    }

    /**
     * Validates the given {@code path} and display the appropriate dialog asking the user to grant
     * access to it.
     */
    static boolean showFragment(Activity activity, int userId, String path) {
        Log.d(TAG, "showFragment() for path " + path + " and user " + userId);
        if (path == null) {
            Log.e(TAG, "INTERNAL ERROR: showFragment() with null path");
            return false;
        }
        File file;
        try {
            file = new File(new File(path).getCanonicalPath());
        } catch (IOException e) {
            Log.e(TAG, "Could not get canonical file from " + path);
            return false;
        }
        final StorageManager sm =
                (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);

        final String root = file.getParent();
        final String directory = file.getName();

        // Verify directory is valid.
        if (TextUtils.isEmpty(directory) || !isStandardDirectory(directory)) {
            Log.d(TAG, "Directory '" + directory + "' is not standard (full path: '" + path + "')");
            return false;
        }

        // Gets volume label and converted path
        String volumeLabel = null;
        final List<VolumeInfo> volumes = sm.getVolumes();
        if (DEBUG) Log.d(TAG, "Number of volumes: " + volumes.size());
        for (VolumeInfo volume : volumes) {
            if (isRightVolume(volume, root, userId)) {
                final File internalRoot = volume.getInternalPathForUser(userId);
                // Must convert path before calling getDocIdForFileCreateNewDir()
                if (DEBUG) Log.d(TAG, "Converting " + root + " to " + internalRoot);
                file = new File(internalRoot, directory);
                volumeLabel = sm.getBestVolumeDescription(volume);
                break;
            }
        }
        if (volumeLabel == null) {
            Log.e(TAG, "Could not get volume for " + path);
            return false;
        }

        // Gets the package label.
        final String appLabel = getAppLabel(activity);
        if (appLabel == null) {
            return false;
        }

        // Sets args that will be retrieve on onCreate()
        final Bundle args = new Bundle();
        args.putString(EXTRA_FILE, file.getAbsolutePath());
        args.putString(EXTRA_VOLUME_LABEL, volumeLabel);
        args.putString(EXTRA_APP_LABEL, appLabel);

        final FragmentManager fm = activity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        final OpenExternalDirectoryDialogFragment fragment =
                new OpenExternalDirectoryDialogFragment();
        fragment.setArguments(args);
        ft.add(fragment, FM_TAG);
        ft.commitAllowingStateLoss();

        return true;
    }

    private static String getAppLabel(Activity activity) {
        final String packageName = activity.getCallingPackage();
        final PackageManager pm = activity.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Could not get label for package " + packageName);
            return null;
        }
    }

    private static boolean isRightVolume(VolumeInfo volume, String root, int userId) {
        final File userPath = volume.getPathForUser(userId);
        final String path = userPath == null ? null : volume.getPathForUser(userId).getPath();
        final boolean isVisible = volume.isVisibleForWrite(userId);
        if (DEBUG) {
            Log.d(TAG, "Volume: " + volume + " userId: " + userId + " root: " + root
                    + " volumePath: " + volume.getPath().getPath()
                    + " pathForUser: " + path
                    + " internalPathForUser: " + volume.getInternalPath()
                    + " isVisible: " + isVisible);
        }
        return volume.isVisibleForWrite(userId) && root.equals(path);
    }

    private static Intent createGrantedUriPermissionsIntent(ContentProviderClient provider,
            File file) {
        // Calls ExternalStorageProvider to get the doc id for the file
        final Bundle bundle;
        try {
            bundle = provider.call("getDocIdForFileCreateNewDir", file.getPath(), null);
        } catch (RemoteException e) {
            Log.e(TAG, "Did not get doc id from External Storage provider for " + file, e);
            return null;
        }
        final String docId = bundle == null ? null : bundle.getString("DOC_ID");
        if (docId == null) {
            Log.e(TAG, "Did not get doc id from External Storage provider for " + file);
            return null;
        }
        Log.d(TAG, "doc id for " + file + ": " + docId);

        final Uri uri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTH, docId);
        if (uri == null) {
            Log.e(TAG, "Could not get URI for doc id " + docId);
            return null;
        }

        if (DEBUG) Log.d(TAG, "URI for " + file + ": " + uri);
        final Intent intent = new Intent();
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    private static class OpenExternalDirectoryDialogFragment extends DialogFragment {

        private File mFile;
        private String mVolumeLabel;
        private String mAppLabel;
        private ContentProviderClient mExternalStorageClient;
        private ContentResolver mResolver;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final Bundle args = getArguments();
            if (args != null) {
                mFile = new File(args.getString(EXTRA_FILE));
                mVolumeLabel = args.getString(EXTRA_VOLUME_LABEL);
                mAppLabel = args.getString(EXTRA_APP_LABEL);
                mResolver = getContext().getContentResolver();
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mExternalStorageClient != null) {
                mExternalStorageClient.close();
            }
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String folder = mFile.getName();
            final Activity activity = getActivity();
            final OnClickListener listener = new OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent intent = null;
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        intent = createGrantedUriPermissionsIntent(getExternalStorageClient(),
                                mFile);
                    }
                    if (which == DialogInterface.BUTTON_NEGATIVE || intent == null) {
                        activity.setResult(RESULT_CANCELED);
                    } else {
                        activity.setResult(RESULT_OK, intent);
                    }
                    activity.finish();
                }
            };

            final CharSequence message = TextUtils
                    .expandTemplate(
                            getText(R.string.open_external_dialog_request), mAppLabel, folder,
                            mVolumeLabel);
            return new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                    .setMessage(message)
                    .setPositiveButton(R.string.allow, listener)
                    .setNegativeButton(R.string.deny, listener)
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);
            final Activity activity = getActivity();
            activity.setResult(RESULT_CANCELED);
            activity.finish();
        }

        private synchronized ContentProviderClient getExternalStorageClient() {
            if (mExternalStorageClient == null) {
                mExternalStorageClient =
                        mResolver.acquireContentProviderClient(EXTERNAL_STORAGE_AUTH);
            }
            return mExternalStorageClient;
        }
    }
}
