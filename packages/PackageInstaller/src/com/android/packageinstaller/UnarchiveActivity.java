/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.Manifest.permission;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.MATCH_ARCHIVED_PACKAGES;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class UnarchiveActivity extends Activity {

    public static final String EXTRA_UNARCHIVE_INTENT_SENDER =
            "android.content.pm.extra.UNARCHIVE_INTENT_SENDER";
    static final String APP_TITLE = "com.android.packageinstaller.unarchive.app_title";
    static final String INSTALLER_TITLE = "com.android.packageinstaller.unarchive.installer_title";

    private static final String TAG = "UnarchiveActivity";

    private String mPackageName;
    private IntentSender mIntentSender;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(null);

        int callingUid = getLaunchedFromUid();
        if (callingUid == Process.INVALID_UID) {
            // Cannot reach Package/ActivityManager. Aborting uninstall.
            Log.e(TAG, "Could not determine the launching uid.");

            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }

        String callingPackage = getPackageNameForUid(callingUid);
        if (callingPackage == null) {
            Log.e(TAG, "Package not found for originating uid " + callingUid);
            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }

        // We don't check the AppOpsManager here for REQUEST_INSTALL_PACKAGES because the requester
        // is not the source of the installation.
        boolean hasRequestInstallPermission = Arrays.asList(getRequestedPermissions(callingPackage))
                .contains(permission.REQUEST_INSTALL_PACKAGES);
        boolean hasInstallPermission = getBaseContext().checkPermission(permission.INSTALL_PACKAGES,
                0 /* random value for pid */, callingUid) == PackageManager.PERMISSION_GRANTED;
        if (!hasRequestInstallPermission && !hasInstallPermission) {
            Log.e(TAG, "Uid " + callingUid + " does not have "
                    + permission.REQUEST_INSTALL_PACKAGES + " or "
                    + permission.INSTALL_PACKAGES);
            setResult(Activity.RESULT_FIRST_USER);
            finish();
            return;
        }

        Bundle extras = getIntent().getExtras();
        mPackageName = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME);
        mIntentSender = extras.getParcelable(EXTRA_UNARCHIVE_INTENT_SENDER, IntentSender.class);
        Objects.requireNonNull(mPackageName);
        Objects.requireNonNull(mIntentSender);

        PackageManager pm = getPackageManager();
        try {
            String appTitle = pm.getApplicationInfo(mPackageName,
                    PackageManager.ApplicationInfoFlags.of(
                            MATCH_ARCHIVED_PACKAGES)).loadLabel(pm).toString();
            // TODO(ag/25387215) Get the real installer title here after fixing getInstallSource for
            //  archived apps.
            showDialogFragment(appTitle, "installerTitle");
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Invalid packageName: " + e.getMessage());
        }
    }

    @Nullable
    private String[] getRequestedPermissions(String callingPackage) {
        String[] requestedPermissions = null;
        try {
            requestedPermissions = getPackageManager()
                    .getPackageInfo(callingPackage, GET_PERMISSIONS).requestedPermissions;
        } catch (PackageManager.NameNotFoundException e) {
            // Should be unreachable because we've just fetched the packageName above.
            Log.e(TAG, "Package not found for " + callingPackage);
        }
        return requestedPermissions;
    }

    void startUnarchive() {
        try {
            getPackageManager().getPackageInstaller().requestUnarchive(mPackageName, mIntentSender);
        } catch (PackageManager.NameNotFoundException | IOException e) {
            Log.e(TAG, "RequestUnarchive failed with %s." + e.getMessage());
        }
    }

    private void showDialogFragment(String appTitle, String installerAppTitle) {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        Fragment prev = getFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }

        Bundle args = new Bundle();
        args.putString(APP_TITLE, appTitle);
        args.putString(INSTALLER_TITLE, installerAppTitle);
        DialogFragment fragment = new UnarchiveFragment();
        fragment.setArguments(args);
        fragment.show(ft, "dialog");
    }

    private String getPackageNameForUid(int sourceUid) {
        String[] packagesForUid = getPackageManager().getPackagesForUid(sourceUid);
        if (packagesForUid == null) {
            return null;
        }
        return packagesForUid[0];
    }
}
