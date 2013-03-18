/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.google.android.test.shared_library;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class SharedLibraryMain {
    static String LIBRARY_PACKAGE = "com.google.android.test.shared_library";

    /**
     * Base version of the library.
     */
    public static int VERSION_BASE = 1;

    /**
     * The second version of the library.
     */
    public static int VERSION_SECOND = 2;

    /**
     * Return the version number of the currently installed library.
     */
    public static int getVersion(Context context) {
        PackageInfo pi = null;
        try {
            pi = context.getPackageManager().getPackageInfo(LIBRARY_PACKAGE, 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Can't find my package!", e);
        }
    }

    /**
     * Check that the library's version is at least the given minimum version,
     * displaying a dialog to have the user install an update if that is not true.
     * The dialog is displayed as a DialogFragment in your activity if a newer
     * version is needed.  If a newer version is needed, false is returned.
     */
    public static boolean ensureVersion(final Activity activity, int minVersion) {
        final FragmentManager fm = activity.getFragmentManager();
        final String dialogTag = LIBRARY_PACKAGE + ":version";
        Fragment curDialog = fm.findFragmentByTag(dialogTag);

        if (getVersion(activity) >= minVersion) {
            // Library version is sufficient.  Make sure any version dialog
            // we had shown is removed before returning.
            if (curDialog != null) {
                fm.beginTransaction().remove(curDialog).commitAllowingStateLoss();
            }
            return true;
        }

        // The current version of the library does not meet the required version.
        // If we don't already have a version dialog displayed, display it now.
        if (curDialog == null) {
            curDialog = new VersionDialog();
            fm.beginTransaction().add(curDialog, dialogTag).commitAllowingStateLoss();
        }

        // Tell the caller that the current version is not sufficient.
        return false;
    }
}
