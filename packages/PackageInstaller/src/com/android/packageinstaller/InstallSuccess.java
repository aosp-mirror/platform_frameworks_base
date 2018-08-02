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

import android.annotation.Nullable;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import java.io.File;
import java.util.List;

/**
 * Finish installation: Return status code to the caller or display "success" UI to user
 */
public class InstallSuccess extends Activity {
    private static final String LOG_TAG = InstallSuccess.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            // Return result if requested
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_SUCCEEDED);
            setResult(Activity.RESULT_OK, result);
            finish();
        } else {
            Intent intent = getIntent();
            ApplicationInfo appInfo =
                    intent.getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
            Uri packageURI = intent.getData();

            setContentView(R.layout.install_success);

            // Set header icon and title
            PackageUtil.AppSnippet as;
            PackageManager pm = getPackageManager();

            if ("package".equals(packageURI.getScheme())) {
                as = new PackageUtil.AppSnippet(pm.getApplicationLabel(appInfo),
                        pm.getApplicationIcon(appInfo));
            } else {
                File sourceFile = new File(packageURI.getPath());
                as = PackageUtil.getAppSnippet(this, appInfo, sourceFile);
            }

            PackageUtil.initSnippetForNewApp(this, as, R.id.app_snippet);

            // Set up "done" button
            findViewById(R.id.done_button).setOnClickListener(view -> {
                if (appInfo.packageName != null) {
                    Log.i(LOG_TAG, "Finished installing " + appInfo.packageName);
                }
                finish();
            });

            // Enable or disable "launch" button
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(
                    appInfo.packageName);
            boolean enabled = false;
            if (launchIntent != null) {
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(launchIntent,
                        0);
                if (list != null && list.size() > 0) {
                    enabled = true;
                }
            }

            Button launchButton = (Button)findViewById(R.id.launch_button);
            if (enabled) {
                launchButton.setOnClickListener(view -> {
                    try {
                        startActivity(launchIntent);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Log.e(LOG_TAG, "Could not start activity", e);
                    }
                    finish();
                });
            } else {
                launchButton.setEnabled(false);
            }
        }
    }
}
