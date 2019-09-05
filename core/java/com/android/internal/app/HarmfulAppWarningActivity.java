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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import com.android.internal.R;

/**
 * This dialog is shown to the user before an activity in a harmful app is launched.
 *
 * See {@code PackageManager.setHarmfulAppInfo} for more info.
 */
public class HarmfulAppWarningActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    private static final String TAG = HarmfulAppWarningActivity.class.getSimpleName();

    private static final String EXTRA_HARMFUL_APP_WARNING = "harmful_app_warning";

    private String mPackageName;
    private String mHarmfulAppWarning;
    private IntentSender mTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        mTarget = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        mHarmfulAppWarning = intent.getStringExtra(EXTRA_HARMFUL_APP_WARNING);

        if (mPackageName == null || mTarget == null || mHarmfulAppWarning == null) {
            Log.wtf(TAG, "Invalid intent: " + intent.toString());
            finish();
        }

        final ApplicationInfo applicationInfo;
        try {
            applicationInfo = getPackageManager().getApplicationInfo(mPackageName, 0 /*flags*/);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not show warning because package does not exist ", e);
            finish();
            return;
        }

        final AlertController.AlertParams p = mAlertParams;
        p.mTitle = getString(R.string.harmful_app_warning_title);
        p.mView = createView(applicationInfo);

        p.mPositiveButtonText = getString(R.string.harmful_app_warning_uninstall);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(R.string.harmful_app_warning_open_anyway);
        p.mNegativeButtonListener = this;

        mAlert.installContent(mAlertParams);
    }

    private View createView(ApplicationInfo applicationInfo) {
        final View view = getLayoutInflater().inflate(R.layout.harmful_app_warning_dialog,
                null /*root*/);
        ((TextView) view.findViewById(R.id.app_name_text))
                .setText(applicationInfo.loadSafeLabel(getPackageManager(),
                        PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                        PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                                | PackageItemInfo.SAFE_LABEL_FLAG_TRIM));
        ((TextView) view.findViewById(R.id.message))
                .setText(mHarmfulAppWarning);
        return view;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                getPackageManager().deletePackage(mPackageName, null /*observer*/, 0 /*flags*/);
                EventLogTags.writeHarmfulAppWarningUninstall(mPackageName);
                finish();
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                getPackageManager().setHarmfulAppWarning(mPackageName, null /*warning*/);

                final IntentSender target = getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
                try {
                    startIntentSenderForResult(target, -1 /*requestCode*/, null /*fillInIntent*/,
                            0 /*flagsMask*/, 0 /*flagsValue*/, 0 /*extraFlags*/);
                } catch (IntentSender.SendIntentException e) {
                    Log.e(TAG, "Error while starting intent sender", e);
                }
                EventLogTags.writeHarmfulAppWarningLaunchAnyway(mPackageName);
                finish();
                break;
        }
    }

    public static Intent createHarmfulAppWarningIntent(Context context, String targetPackageName,
            IntentSender target, CharSequence harmfulAppWarning) {
        final Intent intent = new Intent();
        intent.setClass(context, HarmfulAppWarningActivity.class);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, targetPackageName);
        intent.putExtra(Intent.EXTRA_INTENT, target);
        intent.putExtra(EXTRA_HARMFUL_APP_WARNING, harmfulAppWarning);
        return intent;
    }
}
