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
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;

public class SharedLibraryMain {
    private static String LIBRARY_PACKAGE = "com.google.android.test.shared_library";

    /**
     * Base version of the library.
     */
    public static int VERSION_BASE = 1;

    /**
     * The second version of the library.
     */
    public static int VERSION_SECOND = 2;

    public static int getVersion(Context context) {
        PackageInfo pi = null;
        try {
            pi = context.getPackageManager().getPackageInfo(LIBRARY_PACKAGE, 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Can't find my package!", e);
        }
    }

    public static void ensureVersion(Activity activity, int minVersion) {
        if (getVersion(activity) >= minVersion) {
            return;
        }

        // The current version of the library does not meet the required version.  Show
        // a dialog to inform the user and have them update to the current version.
        // Note that updating the library will be necessity mean killing the current
        // application (so it can be re-started with the new version, so there is no
        // reason to return a result here.
        final Context context;
        try {
            context = activity.createPackageContext(LIBRARY_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Can't find my package!", e);
        }

        // Display the dialog.  Note that we don't need to deal with activity lifecycle
        // stuff because if the activity gets recreated, it will first call through to
        // ensureVersion(), causing us to either re-display the dialog if needed or let
        // it now proceed.
        final Resources res = context.getResources();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(res.getText(R.string.upgrade_title));
        builder.setMessage(res.getString(R.string.upgrade_body,
                activity.getApplicationInfo().loadLabel(activity.getPackageManager()),
                context.getApplicationInfo().loadLabel(context.getPackageManager())));
        builder.setPositiveButton(res.getText(R.string.upgrade_button),
                new Dialog.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Launch play store.
                    }
                });
        builder.show();
    }
}
