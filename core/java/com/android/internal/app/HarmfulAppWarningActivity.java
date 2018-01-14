/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.util.Log;
import com.android.internal.R;

/**
 * This dialog is shown to the user before an activity in a harmful app is launched.
 *
 * See {@code PackageManager.setHarmfulAppInfo} for more info.
 */
public class HarmfulAppWarningActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    private static final String TAG = "HarmfulAppWarningActivity";

    private static final String EXTRA_HARMFUL_APP_WARNING = "harmful_app_warning";

    private String mPackageName;
    private String mHarmfulAppWarning;
    private IntentSender mTarget;

    // [b/63909431] STOPSHIP replace placeholder UI with final Harmful App Warning UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        mTarget = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        mHarmfulAppWarning = intent.getStringExtra(EXTRA_HARMFUL_APP_WARNING);

        if (mPackageName == null || mTarget == null || mHarmfulAppWarning == null) {
            Log.wtf(TAG, "Invalid intent: " + intent.toString());
            finish();
        }

        AlertController.AlertParams p = mAlertParams;
        p.mTitle = getString(R.string.harmful_app_warning_title);
        p.mMessage = mHarmfulAppWarning;
        p.mPositiveButtonText = getString(R.string.harmful_app_warning_launch_anyway);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(R.string.harmful_app_warning_uninstall);
        p.mNegativeButtonListener = this;

        mAlert.installContent(mAlertParams);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                getPackageManager().setHarmfulAppWarning(mPackageName, null);

                IntentSender target = getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
                try {
                    startIntentSenderForResult(target, -1, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    // ignore..
                }
                finish();
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                getPackageManager().deletePackage(mPackageName, null, 0);
                finish();
                break;
        }
    }

    public static Intent createHarmfulAppWarningIntent(Context context, String targetPackageName,
            IntentSender target, CharSequence harmfulAppWarning) {
        Intent intent = new Intent();
        intent.setClass(context, HarmfulAppWarningActivity.class);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, targetPackageName);
        intent.putExtra(Intent.EXTRA_INTENT, target);
        intent.putExtra(EXTRA_HARMFUL_APP_WARNING, harmfulAppWarning);
        return intent;
    }
}
