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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Finish installation: Return status code to the caller or display "success" UI to user
 */
public class InstallSuccess extends Activity {
    private static final String LOG_TAG = InstallSuccess.class.getSimpleName();

    @Nullable
    private PackageUtil.AppSnippet mAppSnippet;

    @Nullable
    private String mAppPackageName;

    @Nullable
    private Intent mLaunchIntent;

    private AlertDialog mDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(true);

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
            mAppPackageName = appInfo.packageName;
            mAppSnippet = intent.getParcelableExtra(PackageInstallerActivity.EXTRA_APP_SNIPPET,
                    PackageUtil.AppSnippet.class);

            mLaunchIntent = getPackageManager().getLaunchIntentForPackage(mAppPackageName);

            bindUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindUi();
    }

    private void bindUi() {
        if (mAppSnippet == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(mAppSnippet.icon);
        builder.setTitle(mAppSnippet.label);
        builder.setView(R.layout.install_content_view);
        builder.setPositiveButton(getString(R.string.launch), null);
        builder.setNegativeButton(getString(R.string.done),
                (ignored, ignored2) -> {
                    if (mAppPackageName != null) {
                        Log.i(LOG_TAG, "Finished installing " + mAppPackageName);
                    }
                    finish();
                });
        builder.setOnCancelListener(dialog -> {
            if (mAppPackageName != null) {
                Log.i(LOG_TAG, "Finished installing " + mAppPackageName);
            }
            finish();
        });
        mDialog = builder.create();
        mDialog.show();
        mDialog.requireViewById(R.id.install_success).setVisibility(View.VISIBLE);
        // Enable or disable "launch" button
        boolean enabled = false;
        if (mLaunchIntent != null) {
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(mLaunchIntent,
                    0);
            if (list != null && list.size() > 0) {
                enabled = true;
            }
        }

        Button launchButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (enabled) {
            launchButton.setOnClickListener(view -> {
                setResult(Activity.RESULT_OK, mLaunchIntent);
                finish();
            });
        } else {
            launchButton.setEnabled(false);
        }
    }
}
