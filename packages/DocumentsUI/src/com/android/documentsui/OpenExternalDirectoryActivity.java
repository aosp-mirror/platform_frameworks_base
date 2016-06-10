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
import static android.os.Environment.STANDARD_DIRECTORIES;
import static android.os.storage.StorageVolume.EXTRA_DIRECTORY_NAME;
import static android.os.storage.StorageVolume.EXTRA_STORAGE_VOLUME;

import static com.android.documentsui.LocalPreferences.getScopedAccessPermissionStatus;
import static com.android.documentsui.LocalPreferences.PERMISSION_ASK;
import static com.android.documentsui.LocalPreferences.PERMISSION_ASK_AGAIN;
import static com.android.documentsui.LocalPreferences.PERMISSION_NEVER_ASK;
import static com.android.documentsui.LocalPreferences.setScopedAccessPermissionStatus;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_ALREADY_DENIED;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_ALREADY_GRANTED;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_DENIED;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_DENIED_AND_PERSIST;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_ERROR;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_GRANTED;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_INVALID_ARGUMENTS;
import static com.android.documentsui.Metrics.SCOPED_DIRECTORY_ACCESS_INVALID_DIRECTORY;
import static com.android.documentsui.Metrics.logInvalidScopedAccessRequest;
import static com.android.documentsui.Metrics.logValidScopedAccessRequest;
import static com.android.documentsui.Shared.DEBUG;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Activity responsible for handling {@link Intent#ACTION_OPEN_EXTERNAL_DOCUMENT}.
 */
public class OpenExternalDirectoryActivity extends Activity {
    private static final String TAG = "OpenExternalDirectory";
    private static final String FM_TAG = "open_external_directory";
    private static final String EXTERNAL_STORAGE_AUTH = "com.android.externalstorage.documents";
    private static final String EXTRA_FILE = "com.android.documentsui.FILE";
    private static final String EXTRA_APP_LABEL = "com.android.documentsui.APP_LABEL";
    private static final String EXTRA_VOLUME_LABEL = "com.android.documentsui.VOLUME_LABEL";
    private static final String EXTRA_VOLUME_UUID = "com.android.documentsui.VOLUME_UUID";
    private static final String EXTRA_IS_ROOT = "com.android.documentsui.IS_ROOT";
    private static final String EXTRA_IS_PRIMARY = "com.android.documentsui.IS_PRIMARY";
    // Special directory name representing the full volume
    static final String DIRECTORY_ROOT = "ROOT_DIRECTORY";

    private ContentProviderClient mExternalStorageClient;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            if (DEBUG) Log.d(TAG, "activity.onCreateDialog(): reusing instance");
            return;
        }

        final Intent intent = getIntent();
        if (intent == null) {
            if (DEBUG) Log.d(TAG, "missing intent");
            logInvalidScopedAccessRequest(this, SCOPED_DIRECTORY_ACCESS_INVALID_ARGUMENTS);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        final Parcelable storageVolume = intent.getParcelableExtra(EXTRA_STORAGE_VOLUME);
        if (!(storageVolume instanceof StorageVolume)) {
            if (DEBUG)
                Log.d(TAG, "extra " + EXTRA_STORAGE_VOLUME + " is not a StorageVolume: "
                        + storageVolume);
            logInvalidScopedAccessRequest(this, SCOPED_DIRECTORY_ACCESS_INVALID_ARGUMENTS);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        String directoryName = intent.getStringExtra(EXTRA_DIRECTORY_NAME );
        if (directoryName == null) {
            directoryName = DIRECTORY_ROOT;
        }
        final StorageVolume volume = (StorageVolume) storageVolume;
        if (getScopedAccessPermissionStatus(getApplicationContext(), getCallingPackage(),
                volume.getUuid(), directoryName) == PERMISSION_NEVER_ASK) {
            logValidScopedAccessRequest(this, directoryName,
                    SCOPED_DIRECTORY_ACCESS_ALREADY_DENIED);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final int userId = UserHandle.myUserId();
        if (!showFragment(this, userId, volume, directoryName)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mExternalStorageClient != null) {
            mExternalStorageClient.close();
        }
    }

    /**
     * Validates the given path (volume + directory) and display the appropriate dialog asking the
     * user to grant access to it.
     */
    private static boolean showFragment(OpenExternalDirectoryActivity activity, int userId,
            StorageVolume storageVolume, String directoryName) {
        if (DEBUG)
            Log.d(TAG, "showFragment() for volume " + storageVolume.dump() + ", directory "
                    + directoryName + ", and user " + userId);
        final boolean isRoot = directoryName.equals(DIRECTORY_ROOT);
        final boolean isPrimary = storageVolume.isPrimary();

        if (isRoot && isPrimary) {
            if (DEBUG) Log.d(TAG, "root access requested on primary volume");
            return false;
        }

        final File volumeRoot = storageVolume.getPathFile();
        File file;
        try {
            file = isRoot ? volumeRoot : new File(volumeRoot, directoryName).getCanonicalFile();
        } catch (IOException e) {
            Log.e(TAG, "Could not get canonical file for volume " + storageVolume.dump()
                    + " and directory " + directoryName);
            logInvalidScopedAccessRequest(activity, SCOPED_DIRECTORY_ACCESS_ERROR);
            return false;
        }
        final StorageManager sm =
                (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);

        final String root, directory;
        if (isRoot) {
            root = volumeRoot.getAbsolutePath();
            directory = ".";
        } else {
            root = file.getParent();
            directory = file.getName();
            // Verify directory is valid.
            if (TextUtils.isEmpty(directory) || !isStandardDirectory(directory)) {
                if (DEBUG)
                    Log.d(TAG, "Directory '" + directory + "' is not standard (full path: '"
                            + file.getAbsolutePath() + "')");
                logInvalidScopedAccessRequest(activity, SCOPED_DIRECTORY_ACCESS_INVALID_DIRECTORY);
                return false;
            }
        }

        // Gets volume label and converted path.
        String volumeLabel = null;
        String volumeUuid = null;
        final List<VolumeInfo> volumes = sm.getVolumes();
        if (DEBUG) Log.d(TAG, "Number of volumes: " + volumes.size());
        File internalRoot = null;
        boolean found = true;
        for (VolumeInfo volume : volumes) {
            if (isRightVolume(volume, root, userId)) {
                found = true;
                internalRoot = volume.getInternalPathForUser(userId);
                // Must convert path before calling getDocIdForFileCreateNewDir()
                if (DEBUG) Log.d(TAG, "Converting " + root + " to " + internalRoot);
                file = isRoot ? internalRoot : new File(internalRoot, directory);
                volumeUuid = storageVolume.getUuid();
                volumeLabel = sm.getBestVolumeDescription(volume);
                if (TextUtils.isEmpty(volumeLabel)) {
                    volumeLabel = storageVolume.getDescription(activity);
                }
                if (TextUtils.isEmpty(volumeLabel)) {
                    volumeLabel = activity.getString(android.R.string.unknownName);
                    Log.w(TAG, "No volume description  for " + volume + "; using " + volumeLabel);
                }
                break;
            }
        }
        if (internalRoot == null) {
            // Should not happen on normal circumstances, unless app crafted an invalid volume
            // using reflection or the list of mounted volumes changed.
            Log.e(TAG, "Didn't find right volume for '" + storageVolume.dump() + "' on " + volumes);
            return false;
        }

        // Checks if the user has granted the permission already.
        final Intent intent = getIntentForExistingPermission(activity, isRoot, internalRoot, file);
        if (intent != null) {
            logValidScopedAccessRequest(activity, directory,
                    SCOPED_DIRECTORY_ACCESS_ALREADY_GRANTED);
            activity.setResult(RESULT_OK, intent);
            activity.finish();
            return true;
        }

        if (!found) {
            Log.e(TAG, "Could not get volume for " + file);
            logInvalidScopedAccessRequest(activity, SCOPED_DIRECTORY_ACCESS_ERROR);
            return false;
        }

        // Gets the package label.
        final String appLabel = getAppLabel(activity);
        if (appLabel == null) {
            // Error already logged.
            return false;
        }

        // Sets args that will be retrieve on onCreate()
        final Bundle args = new Bundle();
        args.putString(EXTRA_FILE, file.getAbsolutePath());
        args.putString(EXTRA_VOLUME_LABEL, volumeLabel);
        args.putString(EXTRA_VOLUME_UUID, volumeUuid);
        args.putString(EXTRA_APP_LABEL, appLabel);
        args.putBoolean(EXTRA_IS_ROOT, isRoot);
        args.putBoolean(EXTRA_IS_PRIMARY, isPrimary);

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
            logInvalidScopedAccessRequest(activity, SCOPED_DIRECTORY_ACCESS_ERROR);
            Log.w(TAG, "Could not get label for package " + packageName);
            return null;
        }
    }

    private static boolean isRightVolume(VolumeInfo volume, String root, int userId) {
        final File userPath = volume.getPathForUser(userId);
        final String path = userPath == null ? null : volume.getPathForUser(userId).getPath();
        final boolean isMounted = volume.isMountedReadable();
        if (DEBUG)
            Log.d(TAG, "Volume: " + volume
                    + "\n\tuserId: " + userId
                    + "\n\tuserPath: " + userPath
                    + "\n\troot: " + root
                    + "\n\tpath: " + path
                    + "\n\tisMounted: " + isMounted);

        return isMounted && root.equals(path);
    }

    private static Uri getGrantedUriPermission(Context context, ContentProviderClient provider,
            File file) {
        // Calls ExternalStorageProvider to get the doc id for the file
        final Bundle bundle;
        try {
            bundle = provider.call("getDocIdForFileCreateNewDir", file.getPath(), null);
        } catch (RemoteException e) {
            Log.e(TAG, "Did not get doc id from External Storage provider for " + file, e);
            logInvalidScopedAccessRequest(context, SCOPED_DIRECTORY_ACCESS_ERROR);
            return null;
        }
        final String docId = bundle == null ? null : bundle.getString("DOC_ID");
        if (docId == null) {
            Log.e(TAG, "Did not get doc id from External Storage provider for " + file);
            logInvalidScopedAccessRequest(context, SCOPED_DIRECTORY_ACCESS_ERROR);
            return null;
        }
        if (DEBUG) Log.d(TAG, "doc id for " + file + ": " + docId);

        final Uri uri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTH, docId);
        if (uri == null) {
            Log.e(TAG, "Could not get URI for doc id " + docId);
            return null;
        }
        if (DEBUG) Log.d(TAG, "URI for " + file + ": " + uri);
        return uri;
    }

    private static Intent createGrantedUriPermissionsIntent(Context context,
            ContentProviderClient provider, File file) {
        final Uri uri = getGrantedUriPermission(context, provider, file);
        return createGrantedUriPermissionsIntent(uri);
    }

    private static Intent createGrantedUriPermissionsIntent(Uri uri) {
        final Intent intent = new Intent();
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    private static Intent getIntentForExistingPermission(OpenExternalDirectoryActivity activity,
            boolean isRoot, File root, File file) {
        final String packageName = activity.getCallingPackage();
        final ContentProviderClient storageClient = activity.getExternalStorageClient();
        final Uri grantedUri = getGrantedUriPermission(activity, storageClient, file);
        final Uri rootUri = root.equals(file) ? grantedUri
                : getGrantedUriPermission(activity, storageClient, root);

        if (DEBUG)
            Log.d(TAG, "checking if " + packageName + " already has permission for " + grantedUri
                    + " or its root (" + rootUri + ")");
        final ActivityManager am =
                (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        for (UriPermission uriPermission : am.getGrantedUriPermissions(packageName).getList()) {
            final Uri uri = uriPermission.getUri();
            if (uri == null) {
                Log.w(TAG, "null URI for " + uriPermission);
                continue;
            }
            if (uri.equals(grantedUri) || uri.equals(rootUri)) {
                if (DEBUG) Log.d(TAG, packageName + " already has permission: " + uriPermission);
                return createGrantedUriPermissionsIntent(grantedUri);
            }
        }
        if (DEBUG) Log.d(TAG, packageName + " does not have permission for " + grantedUri);
        return null;
    }

    public static class OpenExternalDirectoryDialogFragment extends DialogFragment {

        private File mFile;
        private String mVolumeUuid;
        private String mVolumeLabel;
        private String mAppLabel;
        private boolean mIsRoot;
        private boolean mIsPrimary;
        private CheckBox mDontAskAgain;
        private OpenExternalDirectoryActivity mActivity;
        private AlertDialog mDialog;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
            final Bundle args = getArguments();
            if (args != null) {
                mFile = new File(args.getString(EXTRA_FILE));
                mVolumeUuid = args.getString(EXTRA_VOLUME_UUID);
                mVolumeLabel = args.getString(EXTRA_VOLUME_LABEL);
                mAppLabel = args.getString(EXTRA_APP_LABEL);
                mIsRoot = args.getBoolean(EXTRA_IS_ROOT);
                mIsPrimary= args.getBoolean(EXTRA_IS_PRIMARY);
            }
            mActivity = (OpenExternalDirectoryActivity) getActivity();
        }

        @Override
        public void onDestroyView() {
            // Workaround for https://code.google.com/p/android/issues/detail?id=17423
            if (mDialog != null && getRetainInstance()) {
                mDialog.setDismissMessage(null);
            }
            super.onDestroyView();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            if (mDialog != null) {
                if (DEBUG) Log.d(TAG, "fragment.onCreateDialog(): reusing dialog");
                return mDialog;
            }
            if (mActivity != getActivity()) {
                // Sanity check.
                Log.wtf(TAG, "activity references don't match on onCreateDialog(): mActivity = "
                        + mActivity + " , getActivity() = " + getActivity());
                mActivity = (OpenExternalDirectoryActivity) getActivity();
            }
            final String directory = mFile.getName();
            final String directoryName = mIsRoot ? DIRECTORY_ROOT : directory;
            final Context context = mActivity.getApplicationContext();
            final OnClickListener listener = new OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent intent = null;
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        intent = createGrantedUriPermissionsIntent(mActivity,
                                mActivity.getExternalStorageClient(), mFile);
                    }
                    if (which == DialogInterface.BUTTON_NEGATIVE || intent == null) {
                        logValidScopedAccessRequest(mActivity, directoryName,
                                SCOPED_DIRECTORY_ACCESS_DENIED);
                        final boolean checked = mDontAskAgain.isChecked();
                        if (checked) {
                            logValidScopedAccessRequest(mActivity, directory,
                                    SCOPED_DIRECTORY_ACCESS_DENIED_AND_PERSIST);
                            setScopedAccessPermissionStatus(context, mActivity.getCallingPackage(),
                                    mVolumeUuid, directoryName, PERMISSION_NEVER_ASK);
                        } else {
                            setScopedAccessPermissionStatus(context, mActivity.getCallingPackage(),
                                    mVolumeUuid, directoryName, PERMISSION_ASK_AGAIN);
                        }
                        mActivity.setResult(RESULT_CANCELED);
                    } else {
                        logValidScopedAccessRequest(mActivity, directory,
                                SCOPED_DIRECTORY_ACCESS_GRANTED);
                        mActivity.setResult(RESULT_OK, intent);
                    }
                    mActivity.finish();
                }
            };

            @SuppressLint("InflateParams")
            // It's ok pass null ViewRoot on AlertDialogs.
            final View view = View.inflate(mActivity, R.layout.dialog_open_scoped_directory, null);
            final CharSequence message;
            if (mIsRoot) {
                message = TextUtils.expandTemplate(getText(
                        R.string.open_external_dialog_root_request), mAppLabel, mVolumeLabel);
            } else {
                message = TextUtils.expandTemplate(
                        getText(mIsPrimary ? R.string.open_external_dialog_request_primary_volume
                                : R.string.open_external_dialog_request),
                                mAppLabel, directory, mVolumeLabel);
            }
            final TextView messageField = (TextView) view.findViewById(R.id.message);
            messageField.setText(message);
            mDialog = new AlertDialog.Builder(mActivity, R.style.Theme_AppCompat_Light_Dialog_Alert)
                    .setView(view)
                    .setPositiveButton(R.string.allow, listener)
                    .setNegativeButton(R.string.deny, listener)
                    .create();

            mDontAskAgain = (CheckBox) view.findViewById(R.id.do_not_ask_checkbox);
            if (getScopedAccessPermissionStatus(context, mActivity.getCallingPackage(),
                    mVolumeUuid, directoryName) == PERMISSION_ASK_AGAIN) {
                mDontAskAgain.setVisibility(View.VISIBLE);
                mDontAskAgain.setOnCheckedChangeListener(new OnCheckedChangeListener() {

                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!isChecked);
                    }
                });
            }

            return mDialog;
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);
            final Activity activity = getActivity();
            logValidScopedAccessRequest(activity, mFile.getName(), SCOPED_DIRECTORY_ACCESS_DENIED);
            activity.setResult(RESULT_CANCELED);
            activity.finish();
        }
    }

    private synchronized ContentProviderClient getExternalStorageClient() {
        if (mExternalStorageClient == null) {
            mExternalStorageClient =
                    getContentResolver().acquireContentProviderClient(EXTERNAL_STORAGE_AUTH);
        }
        return mExternalStorageClient;
    }
}
