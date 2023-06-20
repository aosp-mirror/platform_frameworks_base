/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.packageinstaller;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NO_HISTORY;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * This activity is launched when a new application is installed via side loading
 * The package is first parsed and the user is notified of parse errors via a dialog.
 * If the package is successfully parsed, the user is notified to turn on the install unknown
 * applications setting. A memory check is made at this point and the user is notified of out
 * of memory conditions if any. If the package is already existing on the device,
 * a confirmation dialog (to replace the existing package) is presented to the user.
 * Based on the user response the package is then installed by launching InstallAppConfirm
 * sub activity. All state transitions are handled in this activity
 */
public class PackageInstallerActivity extends AlertActivity {
    private static final String TAG = "PackageInstaller";

    private static final int REQUEST_TRUST_EXTERNAL_SOURCE = 1;

    static final String SCHEME_PACKAGE = "package";

    static final String EXTRA_CALLING_PACKAGE = "EXTRA_CALLING_PACKAGE";
    static final String EXTRA_CALLING_ATTRIBUTION_TAG = "EXTRA_CALLING_ATTRIBUTION_TAG";
    static final String EXTRA_ORIGINAL_SOURCE_INFO = "EXTRA_ORIGINAL_SOURCE_INFO";
    static final String EXTRA_STAGED_SESSION_ID = "EXTRA_STAGED_SESSION_ID";
    private static final String ALLOW_UNKNOWN_SOURCES_KEY =
            PackageInstallerActivity.class.getName() + "ALLOW_UNKNOWN_SOURCES_KEY";

    private int mSessionId = -1;
    private Uri mPackageURI;
    private Uri mOriginatingURI;
    private Uri mReferrerURI;
    private int mOriginatingUid = Process.INVALID_UID;
    private String mOriginatingPackage; // The package name corresponding to #mOriginatingUid
    private int mActivityResultCode = Activity.RESULT_CANCELED;
    private int mPendingUserActionReason = -1;

    private final boolean mLocalLOGV = false;
    PackageManager mPm;
    AppOpsManager mAppOpsManager;
    UserManager mUserManager;
    PackageInstaller mInstaller;
    PackageInfo mPkgInfo;
    String mCallingPackage;
    private String mCallingAttributionTag;
    ApplicationInfo mSourceInfo;

    /**
     * A collection of unknown sources listeners that are actively listening for app ops mode
     * changes
     */
    private List<UnknownSourcesListener> mActiveUnknownSourcesListeners = new ArrayList<>(1);

    // ApplicationInfo object primarily used for already existing applications
    private ApplicationInfo mAppInfo;

    // Buttons to indicate user acceptance
    private Button mOk;

    private PackageUtil.AppSnippet mAppSnippet;

    static final String PREFS_ALLOWED_SOURCES = "allowed_sources";

    // Dialog identifiers used in showDialog
    private static final int DLG_BASE = 0;
    private static final int DLG_PACKAGE_ERROR = DLG_BASE + 1;
    private static final int DLG_OUT_OF_SPACE = DLG_BASE + 2;
    private static final int DLG_INSTALL_ERROR = DLG_BASE + 3;
    private static final int DLG_ANONYMOUS_SOURCE = DLG_BASE + 4;
    private static final int DLG_EXTERNAL_SOURCE_BLOCKED = DLG_BASE + 5;

    // If unknown sources are temporary allowed
    private boolean mAllowUnknownSources;

    // Would the mOk button be enabled if this activity would be resumed
    private boolean mEnableOk = false;

    private void startInstallConfirm() {
        TextView viewToEnable;

        if (mAppInfo != null) {
            viewToEnable = requireViewById(R.id.install_confirm_question_update);

            final CharSequence existingUpdateOwnerLabel = getExistingUpdateOwnerLabel();
            final CharSequence requestedUpdateOwnerLabel = getApplicationLabel(mCallingPackage);
            if (!TextUtils.isEmpty(existingUpdateOwnerLabel)
                    && mPendingUserActionReason == PackageInstaller.REASON_REMIND_OWNERSHIP) {
                String updateOwnerString =
                        getString(R.string.install_confirm_question_update_owner_reminder,
                                requestedUpdateOwnerLabel, existingUpdateOwnerLabel);
                Spanned styledUpdateOwnerString =
                        Html.fromHtml(updateOwnerString, Html.FROM_HTML_MODE_LEGACY);
                viewToEnable.setText(styledUpdateOwnerString);
                mOk.setText(R.string.update_anyway);
            } else {
                mOk.setText(R.string.update);
            }
        } else {
            // This is a new application with no permissions.
            viewToEnable = requireViewById(R.id.install_confirm_question);
        }

        viewToEnable.setVisibility(View.VISIBLE);

        mEnableOk = true;
        mOk.setEnabled(true);
        mOk.setFilterTouchesWhenObscured(true);
    }

    private CharSequence getExistingUpdateOwnerLabel() {
        try {
            final String packageName = mPkgInfo.packageName;
            final InstallSourceInfo sourceInfo = mPm.getInstallSourceInfo(packageName);
            final String existingUpdateOwner = sourceInfo.getUpdateOwnerPackageName();
            return getApplicationLabel(existingUpdateOwner);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private CharSequence getApplicationLabel(String packageName) {
        try {
            final ApplicationInfo appInfo = mPm.getApplicationInfo(packageName,
                    ApplicationInfoFlags.of(0));
            return mPm.getApplicationLabel(appInfo);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Replace any dialog shown by the dialog with the one for the given {@link #createDialog(int)}.
     *
     * @param id The dialog type to add
     */
    private void showDialogInner(int id) {
        if (mLocalLOGV) Log.i(TAG, "showDialogInner(" + id + ")");
        DialogFragment currentDialog =
                (DialogFragment) getFragmentManager().findFragmentByTag("dialog");
        if (currentDialog != null) {
            currentDialog.dismissAllowingStateLoss();
        }

        DialogFragment newDialog = createDialog(id);
        if (newDialog != null) {
            getFragmentManager().beginTransaction()
                    .add(newDialog, "dialog").commitAllowingStateLoss();
        }
    }

    /**
     * Create a new dialog.
     *
     * @param id The id of the dialog (determines dialog type)
     *
     * @return The dialog
     */
    private DialogFragment createDialog(int id) {
        if (mLocalLOGV) Log.i(TAG, "createDialog(" + id + ")");
        switch (id) {
            case DLG_PACKAGE_ERROR:
                return PackageUtil.SimpleErrorDialog.newInstance(R.string.Parse_error_dlg_text);
            case DLG_OUT_OF_SPACE:
                return OutOfSpaceDialog.newInstance(
                        mPm.getApplicationLabel(mPkgInfo.applicationInfo));
            case DLG_INSTALL_ERROR:
                return InstallErrorDialog.newInstance(
                        mPm.getApplicationLabel(mPkgInfo.applicationInfo));
            case DLG_EXTERNAL_SOURCE_BLOCKED:
                return ExternalSourcesBlockedDialog.newInstance(mOriginatingPackage);
            case DLG_ANONYMOUS_SOURCE:
                return AnonymousSourceDialog.newInstance();
        }
        return null;
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        if (request == REQUEST_TRUST_EXTERNAL_SOURCE) {
            // Log the fact that the app is requesting an install, and is now allowed to do it
            // (before this point we could only log that it's requesting an install, but isn't
            // allowed to do it yet).
            String appOpStr =
                    AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES);
            int appOpMode = mAppOpsManager.noteOpNoThrow(appOpStr, mOriginatingUid,
                    mOriginatingPackage, mCallingAttributionTag,
                    "Successfully started package installation activity");
            if (appOpMode == AppOpsManager.MODE_ALLOWED) {
                // The user has just allowed this package to install other packages
                // (via Settings).
                mAllowUnknownSources = true;
                DialogFragment currentDialog =
                        (DialogFragment) getFragmentManager().findFragmentByTag("dialog");
                if (currentDialog != null) {
                    currentDialog.dismissAllowingStateLoss();
                }
                initiateInstall();
            } else {
                finish();
            }
        } else {
            finish();
        }
    }

    private String getPackageNameForUid(int sourceUid) {
        // If the sourceUid belongs to the system downloads provider, we explicitly return the
        // name of the Download Manager package. This is because its UID is shared with multiple
        // packages, resulting in uncertainty about which package will end up first in the list
        // of packages associated with this UID
        ApplicationInfo systemDownloadProviderInfo = PackageUtil.getSystemDownloadsProviderInfo(
                                                        mPm, sourceUid);
        if (systemDownloadProviderInfo != null) {
            return systemDownloadProviderInfo.packageName;
        }
        String[] packagesForUid = mPm.getPackagesForUid(sourceUid);
        if (packagesForUid == null) {
            return null;
        }
        if (packagesForUid.length > 1) {
            if (mCallingPackage != null) {
                for (String packageName : packagesForUid) {
                    if (packageName.equals(mCallingPackage)) {
                        return packageName;
                    }
                }
            }
            Log.i(TAG, "Multiple packages found for source uid " + sourceUid);
        }
        return packagesForUid[0];
    }

    private boolean isInstallRequestFromUnknownSource(Intent intent) {
        if (mCallingPackage != null && intent.getBooleanExtra(
                Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)) {
            if (mSourceInfo != null && mSourceInfo.isPrivilegedApp()) {
                // Privileged apps can bypass unknown sources check if they want.
                return false;
            }
        }
        if (mSourceInfo != null && checkPermission(Manifest.permission.INSTALL_PACKAGES,
                -1 /* pid */, mSourceInfo.uid) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }

    private void initiateInstall() {
        String pkgName = mPkgInfo.packageName;
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        String[] oldName = mPm.canonicalToCurrentPackageNames(new String[] { pkgName });
        if (oldName != null && oldName.length > 0 && oldName[0] != null) {
            pkgName = oldName[0];
            mPkgInfo.packageName = pkgName;
            mPkgInfo.applicationInfo.packageName = pkgName;
        }
        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            mAppInfo = mPm.getApplicationInfo(pkgName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if ((mAppInfo.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                mAppInfo = null;
            }
        } catch (NameNotFoundException e) {
            mAppInfo = null;
        }

        startInstallConfirm();
    }

    void setPmResult(int pmResult) {
        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_INSTALL_RESULT, pmResult);
        setResult(pmResult == PackageManager.INSTALL_SUCCEEDED
                ? RESULT_OK : RESULT_FIRST_USER, result);
    }

    private static PackageInfo generateStubPackageInfo(String packageName) {
        final PackageInfo info = new PackageInfo();
        final ApplicationInfo aInfo = new ApplicationInfo();
        info.applicationInfo = aInfo;
        info.packageName = info.applicationInfo.packageName = packageName;
        return info;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        if (mLocalLOGV) Log.i(TAG, "creating for user " + UserHandle.myUserId());
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        super.onCreate(null);

        if (icicle != null) {
            mAllowUnknownSources = icicle.getBoolean(ALLOW_UNKNOWN_SOURCES_KEY);
        }
        setFinishOnTouchOutside(true);

        mPm = getPackageManager();
        mAppOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        mInstaller = mPm.getPackageInstaller();
        mUserManager = (UserManager) getSystemService(Context.USER_SERVICE);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        mCallingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE);
        mCallingAttributionTag = intent.getStringExtra(EXTRA_CALLING_ATTRIBUTION_TAG);
        mSourceInfo = intent.getParcelableExtra(EXTRA_ORIGINAL_SOURCE_INFO);
        mOriginatingUid = intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                Process.INVALID_UID);
        mOriginatingPackage = (mOriginatingUid != Process.INVALID_UID)
                ? getPackageNameForUid(mOriginatingUid) : null;

        final Object packageSource;
        if (PackageInstaller.ACTION_CONFIRM_INSTALL.equals(action)) {
            final int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,
                    -1 /* defaultValue */);
            final SessionInfo info = mInstaller.getSessionInfo(sessionId);
            String resolvedPath = info.getResolvedBaseApkPath();
            if (info == null || !info.isSealed() || resolvedPath == null) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                finish();
                return;
            }

            mSessionId = sessionId;
            packageSource = Uri.fromFile(new File(resolvedPath));
            mOriginatingURI = null;
            mReferrerURI = null;
            mPendingUserActionReason = info.getPendingUserActionReason();
        } else if (PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(action)) {
            final int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,
                    -1 /* defaultValue */);
            final SessionInfo info = mInstaller.getSessionInfo(sessionId);
            if (info == null || !info.isPreApprovalRequested()) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                finish();
                return;
            }

            mSessionId = sessionId;
            packageSource = info;
            mOriginatingURI = null;
            mReferrerURI = null;
            mPendingUserActionReason = info.getPendingUserActionReason();
        } else {
            // Two possible callers:
            // 1. InstallStart with "SCHEME_PACKAGE".
            // 2. InstallStaging with "SCHEME_FILE" and EXTRA_STAGED_SESSION_ID with staged
            // session id.
            mSessionId = -1;
            packageSource = intent.getData();
            mOriginatingURI = intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            mReferrerURI = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
            mPendingUserActionReason = PackageInstaller.REASON_CONFIRM_PACKAGE_CHANGE;
        }

        // if there's nothing to do, quietly slip into the ether
        if (packageSource == null) {
            Log.w(TAG, "Unspecified source");
            setPmResult(PackageManager.INSTALL_FAILED_INVALID_URI);
            finish();
            return;
        }

        final boolean wasSetUp = processAppSnippet(packageSource);

        if (mLocalLOGV) Log.i(TAG, "wasSetUp: " + wasSetUp);

        if (!wasSetUp) {
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mLocalLOGV) Log.i(TAG, "onResume(): mAppSnippet=" + mAppSnippet);

        if (mAppSnippet != null) {
            // load dummy layout with OK button disabled until we override this layout in
            // startInstallConfirm
            bindUi();
            checkIfAllowedAndInitiateInstall();
        }

        if (mOk != null) {
            mOk.setEnabled(mEnableOk);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mOk != null) {
            // Don't allow the install button to be clicked as there might be overlays
            mOk.setEnabled(false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(ALLOW_UNKNOWN_SOURCES_KEY, mAllowUnknownSources);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        while (!mActiveUnknownSourcesListeners.isEmpty()) {
            unregister(mActiveUnknownSourcesListeners.get(0));
        }
    }

    private void bindUi() {
        mAlert.setIcon(mAppSnippet.icon);
        mAlert.setTitle(mAppSnippet.label);
        mAlert.setView(R.layout.install_content_view);
        mAlert.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.install),
                (ignored, ignored2) -> {
                    if (mOk.isEnabled()) {
                        if (mSessionId != -1) {
                            setActivityResult(RESULT_OK);
                            finish();
                        } else {
                            startInstall();
                        }
                    }
                }, null);
        mAlert.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel),
                (ignored, ignored2) -> {
                    // Cancel and finish
                    setActivityResult(RESULT_CANCELED);
                    finish();
                }, null);
        setupAlert();

        mOk = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
        mOk.setEnabled(false);

        if (!mOk.isInTouchMode()) {
            mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus();
        }
    }

    private void setActivityResult(int resultCode) {
        mActivityResultCode = resultCode;
        super.setResult(resultCode);
    }

    @Override
    public void finish() {
        if (mSessionId != -1) {
            if (mActivityResultCode == Activity.RESULT_OK) {
                mInstaller.setPermissionsResult(mSessionId, true);
            } else {
                mInstaller.setPermissionsResult(mSessionId, false);
            }
        }
        super.finish();
    }

    /**
     * Check if it is allowed to install the package and initiate install if allowed.
     */
    private void checkIfAllowedAndInitiateInstall() {
        if (mAllowUnknownSources || !isInstallRequestFromUnknownSource(getIntent())) {
            if (mLocalLOGV) Log.i(TAG, "install allowed");
            initiateInstall();
        } else {
            handleUnknownSources();
        }
    }

    private void handleUnknownSources() {
        if (mOriginatingPackage == null) {
            Log.i(TAG, "No source found for package " + mPkgInfo.packageName);
            showDialogInner(DLG_ANONYMOUS_SOURCE);
            return;
        }
        // Shouldn't use static constant directly, see b/65534401.
        final String appOpStr =
                AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES);
        final int appOpMode = mAppOpsManager.noteOpNoThrow(appOpStr, mOriginatingUid,
                mOriginatingPackage, mCallingAttributionTag,
                "Started package installation activity");
        if (mLocalLOGV) Log.i(TAG, "handleUnknownSources(): appMode=" + appOpMode);
        switch (appOpMode) {
            case AppOpsManager.MODE_DEFAULT:
                mAppOpsManager.setMode(appOpStr, mOriginatingUid,
                        mOriginatingPackage, AppOpsManager.MODE_ERRORED);
                // fall through
            case AppOpsManager.MODE_ERRORED:
                showDialogInner(DLG_EXTERNAL_SOURCE_BLOCKED);
                break;
            case AppOpsManager.MODE_ALLOWED:
                initiateInstall();
                break;
            default:
                Log.e(TAG, "Invalid app op mode " + appOpMode
                        + " for OP_REQUEST_INSTALL_PACKAGES found for uid " + mOriginatingUid);
                finish();
                break;
        }
    }

    /**
     * Parse the Uri and set up the installer for this package.
     *
     * @param packageUri The URI to parse
     *
     * @return {@code true} iff the installer could be set up
     */
    private boolean processPackageUri(final Uri packageUri) {
        mPackageURI = packageUri;
        final String scheme = packageUri.getScheme();
        final String packageName = packageUri.getSchemeSpecificPart();

        if (mLocalLOGV) Log.i(TAG, "processPackageUri(): uri=" + packageUri + ", scheme=" + scheme);

        switch (scheme) {
            case SCHEME_PACKAGE: {
                for (UserHandle handle : mUserManager.getUserHandles(true)) {
                    PackageManager pmForUser = createContextAsUser(handle, 0)
                                                .getPackageManager();
                    try {
                        if (pmForUser.canPackageQuery(mCallingPackage, packageName)) {
                            mPkgInfo = pmForUser.getPackageInfo(packageName,
                                    PackageManager.GET_PERMISSIONS
                                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        }
                    } catch (NameNotFoundException e) {
                    }
                }
                if (mPkgInfo == null) {
                    Log.w(TAG, "Requested package " + packageUri.getSchemeSpecificPart()
                            + " not available. Discontinuing installation");
                    showDialogInner(DLG_PACKAGE_ERROR);
                    setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                    return false;
                }
                CharSequence label = mPm.getApplicationLabel(mPkgInfo.applicationInfo);
                if (mLocalLOGV) Log.i(TAG, "creating snippet for " + label);
                mAppSnippet = new PackageUtil.AppSnippet(label,
                        mPm.getApplicationIcon(mPkgInfo.applicationInfo));
            } break;

            case ContentResolver.SCHEME_FILE: {
                File sourceFile = new File(packageUri.getPath());
                mPkgInfo = PackageUtil.getPackageInfo(this, sourceFile,
                        PackageManager.GET_PERMISSIONS);

                // Check for parse errors
                if (mPkgInfo == null) {
                    Log.w(TAG, "Parse error when parsing manifest. Discontinuing installation");
                    showDialogInner(DLG_PACKAGE_ERROR);
                    setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                    return false;
                }
                if (mLocalLOGV) Log.i(TAG, "creating snippet for local file " + sourceFile);
                mAppSnippet = PackageUtil.getAppSnippet(this, mPkgInfo.applicationInfo, sourceFile);
            } break;

            default: {
                throw new IllegalArgumentException("Unexpected URI scheme " + packageUri);
            }
        }

        return true;
    }

    /**
     * Use the SessionInfo and set up the installer for pre-commit install session.
     *
     * @param info The SessionInfo to compose
     *
     * @return {@code true} iff the installer could be set up
     */
    private boolean processSessionInfo(@NonNull SessionInfo info) {
        mPkgInfo = generateStubPackageInfo(info.getAppPackageName());
        mAppSnippet = new PackageUtil.AppSnippet(info.getAppLabel(),
                info.getAppIcon() != null ? new BitmapDrawable(getResources(), info.getAppIcon())
                        : getPackageManager().getDefaultActivityIcon());
        return true;
    }

    /**
     * Parse the Uri (post-commit install session) or use the SessionInfo (pre-commit install
     * session) to set up the installer for this install.
     *
     * @param source The source of package URI or SessionInfo
     *
     * @return {@code true} iff the installer could be set up
     */
    private boolean processAppSnippet(@NonNull Object source) {
        if (source instanceof Uri) {
            return processPackageUri((Uri) source);
        } else if (source instanceof SessionInfo) {
            return processSessionInfo((SessionInfo) source);
        }

        return false;
    }

    @Override
    public void onBackPressed() {
        if (mSessionId != -1) {
            setActivityResult(RESULT_CANCELED);
        }
        super.onBackPressed();
    }

    private void startInstall() {
        String installerPackageName = getIntent().getStringExtra(
                Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        int stagedSessionId = getIntent().getIntExtra(EXTRA_STAGED_SESSION_ID, 0);

        // Start subactivity to actually install the application
        Intent newIntent = new Intent();
        newIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO,
                mPkgInfo.applicationInfo);
        newIntent.setData(mPackageURI);
        newIntent.setClass(this, InstallInstalling.class);
        if (mOriginatingURI != null) {
            newIntent.putExtra(Intent.EXTRA_ORIGINATING_URI, mOriginatingURI);
        }
        if (mReferrerURI != null) {
            newIntent.putExtra(Intent.EXTRA_REFERRER, mReferrerURI);
        }
        if (mOriginatingUid != Process.INVALID_UID) {
            newIntent.putExtra(Intent.EXTRA_ORIGINATING_UID, mOriginatingUid);
        }
        if (installerPackageName != null) {
            newIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME,
                    installerPackageName);
        }
        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            newIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        }
        if (stagedSessionId > 0) {
            newIntent.putExtra(EXTRA_STAGED_SESSION_ID, stagedSessionId);
        }
        newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        if (mLocalLOGV) Log.i(TAG, "downloaded app uri=" + mPackageURI);
        startActivity(newIntent);
        finish();
    }

    /**
     * Dialog to show when the source of apk can not be identified
     */
    public static class AnonymousSourceDialog extends DialogFragment {
        static AnonymousSourceDialog newInstance() {
            return new AnonymousSourceDialog();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.anonymous_source_warning)
                    .setPositiveButton(R.string.anonymous_source_continue,
                            ((dialog, which) -> {
                                PackageInstallerActivity activity = ((PackageInstallerActivity)
                                        getActivity());

                                activity.mAllowUnknownSources = true;
                                activity.initiateInstall();
                            }))
                    .setNegativeButton(R.string.cancel, ((dialog, which) -> getActivity().finish()))
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            getActivity().finish();
        }
    }

    /**
     * An error dialog shown when the device is out of space
     */
    public static class OutOfSpaceDialog extends AppErrorDialog {
        static AppErrorDialog newInstance(@NonNull CharSequence applicationLabel) {
            OutOfSpaceDialog dialog = new OutOfSpaceDialog();
            dialog.setArgument(applicationLabel);
            return dialog;
        }

        @Override
        protected Dialog createDialog(@NonNull CharSequence argument) {
            String dlgText = getString(R.string.out_of_space_dlg_text, argument);
            return new AlertDialog.Builder(getActivity())
                    .setMessage(dlgText)
                    .setPositiveButton(R.string.manage_applications, (dialog, which) -> {
                        // launch manage applications
                        Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        getActivity().finish();
                    })
                    .setNegativeButton(R.string.cancel, (dialog, which) -> getActivity().finish())
                    .create();
        }
    }

    /**
     * A generic install-error dialog
     */
    public static class InstallErrorDialog extends AppErrorDialog {
        static AppErrorDialog newInstance(@NonNull CharSequence applicationLabel) {
            InstallErrorDialog dialog = new InstallErrorDialog();
            dialog.setArgument(applicationLabel);
            return dialog;
        }

        @Override
        protected Dialog createDialog(@NonNull CharSequence argument) {
            return new AlertDialog.Builder(getActivity())
                    .setNeutralButton(R.string.ok, (dialog, which) -> getActivity().finish())
                    .setMessage(getString(R.string.install_failed_msg, argument))
                    .create();
        }
    }

    private class UnknownSourcesListener implements AppOpsManager.OnOpChangedListener {

        @Override
        public void onOpChanged(String op, String packageName) {
            if (!mOriginatingPackage.equals(packageName)) {
                return;
            }
            unregister(this);
            mActiveUnknownSourcesListeners.remove(this);
            if (isDestroyed()) {
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isDestroyed()) {
                    startActivity(getIntent().addFlags(
                            FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP));
                }
            }, 500);

        }

    }

    private void register(UnknownSourcesListener listener) {
        mAppOpsManager.startWatchingMode(
                AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES, mOriginatingPackage,
                listener);
        mActiveUnknownSourcesListeners.add(listener);
    }

    private void unregister(UnknownSourcesListener listener) {
        mAppOpsManager.stopWatchingMode(listener);
        mActiveUnknownSourcesListeners.remove(listener);
    }

    /**
     * An error dialog shown when external sources are not allowed
     */
    public static class ExternalSourcesBlockedDialog extends AppErrorDialog {
        static AppErrorDialog newInstance(@NonNull String originationPkg) {
            ExternalSourcesBlockedDialog dialog =
                    new ExternalSourcesBlockedDialog();
            dialog.setArgument(originationPkg);
            return dialog;
        }

        @Override
        protected Dialog createDialog(@NonNull CharSequence argument) {

            final PackageInstallerActivity activity = (PackageInstallerActivity)getActivity();
            try {
                PackageManager pm = activity.getPackageManager();

                ApplicationInfo sourceInfo = pm.getApplicationInfo(argument.toString(), 0);

                return new AlertDialog.Builder(activity)
                        .setTitle(pm.getApplicationLabel(sourceInfo))
                        .setIcon(pm.getApplicationIcon(sourceInfo))
                        .setMessage(R.string.untrusted_external_source_warning)
                        .setPositiveButton(R.string.external_sources_settings,
                                (dialog, which) -> {
                                    Intent settingsIntent = new Intent();
                                    settingsIntent.setAction(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                    final Uri packageUri = Uri.parse("package:" + argument);
                                    settingsIntent.setData(packageUri);
                                    settingsIntent.setFlags(FLAG_ACTIVITY_NO_HISTORY);
                                    try {
                                        activity.register(activity.new UnknownSourcesListener());
                                        activity.startActivityForResult(settingsIntent,
                                                REQUEST_TRUST_EXTERNAL_SOURCE);
                                    } catch (ActivityNotFoundException exc) {
                                        Log.e(TAG, "Settings activity not found for action: "
                                                + Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                    }
                                })
                        .setNegativeButton(R.string.cancel,
                                (dialog, which) -> activity.finish())
                        .create();
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Did not find app info for " + argument);
                activity.finish();
                return null;
            }
        }
    }

    /**
     * Superclass for all error dialogs. Stores a single CharSequence argument
     */
    public abstract static class AppErrorDialog extends DialogFragment {
        private static final String ARGUMENT_KEY = AppErrorDialog.class.getName() + "ARGUMENT_KEY";

        protected void setArgument(@NonNull CharSequence argument) {
            Bundle args = new Bundle();
            args.putCharSequence(ARGUMENT_KEY, argument);
            setArguments(args);
        }

        protected abstract Dialog createDialog(@NonNull CharSequence argument);

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return createDialog(getArguments().getString(ARGUMENT_KEY));
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            getActivity().finish();
        }
    }
}
