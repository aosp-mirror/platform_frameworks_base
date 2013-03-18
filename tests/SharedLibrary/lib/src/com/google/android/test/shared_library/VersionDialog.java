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
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;

/**
 * This is the dialog we show when the library's version is older than
 * the version the app needs.
 */
public class VersionDialog extends DialogFragment {
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();

        // Need to use our library's resources for showing the dialog.
        final Context context;
        try {
            context = activity.createPackageContext(SharedLibraryMain.LIBRARY_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Can't find my package!", e);
        }

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
                        // Launch play store into the details of our app.
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id="
                                            + SharedLibraryMain.LIBRARY_PACKAGE)));
                        } catch (android.content.ActivityNotFoundException anfe) {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("http://play.google.com/store/apps/details?id="
                                            + SharedLibraryMain.LIBRARY_PACKAGE)));
                        }
                    }
                });
        return builder.create();
    }
}
