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

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

import java.util.Objects;

public class UnarchiveErrorActivity extends Activity {

    static final String EXTRA_REQUIRED_BYTES =
            "com.android.content.pm.extra.UNARCHIVE_EXTRA_REQUIRED_BYTES";
    static final String EXTRA_INSTALLER_PACKAGE_NAME =
            "com.android.content.pm.extra.UNARCHIVE_INSTALLER_PACKAGE_NAME";
    static final String EXTRA_INSTALLER_TITLE =
            "com.android.content.pm.extra.UNARCHIVE_INSTALLER_TITLE";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(null);

        Bundle extras = getIntent().getExtras();
        int unarchivalStatus = extras.getInt(PackageInstaller.EXTRA_UNARCHIVE_STATUS);
        long requiredBytes = extras.getLong(EXTRA_REQUIRED_BYTES);
        PendingIntent intent = extras.getParcelable(Intent.EXTRA_INTENT, PendingIntent.class);
        String installerPackageName = extras.getString(EXTRA_INSTALLER_PACKAGE_NAME);
        // We cannot derive this from the package name because the installer might not be installed
        // anymore.
        String installerAppTitle = extras.getString(EXTRA_INSTALLER_TITLE);

        if (unarchivalStatus == PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED) {
            Objects.requireNonNull(intent);
        }

        FragmentTransaction ft = getFragmentManager().beginTransaction();
        Fragment prev = getFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }

        Bundle args = new Bundle();
        args.putInt(PackageInstaller.EXTRA_UNARCHIVE_STATUS, unarchivalStatus);
        args.putLong(EXTRA_REQUIRED_BYTES, requiredBytes);
        if (intent != null) {
            args.putParcelable(Intent.EXTRA_INTENT, intent);
        }
        if (installerPackageName != null) {
            args.putString(EXTRA_INSTALLER_PACKAGE_NAME, installerPackageName);
        }
        if (installerAppTitle != null) {
            args.putString(EXTRA_INSTALLER_TITLE, installerAppTitle);
        }

        DialogFragment fragment = new UnarchiveErrorFragment();
        fragment.setArguments(args);
        fragment.show(ft, "dialog");
    }
}
