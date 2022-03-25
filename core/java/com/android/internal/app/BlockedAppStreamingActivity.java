/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;

/**
 * A dialog shown to the user when they try to launch an app that is not allowed on a virtual
 * device. The intent to start this activity must be created with the static factory method provided
 * below.
 */
public class BlockedAppStreamingActivity extends AlertActivity {

    private static final String TAG = "BlockedAppStreamingActivity";
    private static final String PACKAGE_NAME = "com.android.internal.app";
    private static final String EXTRA_BLOCKED_ACTIVITY_INFO =
            PACKAGE_NAME + ".extra.BLOCKED_ACTIVITY_INFO";
    private static final String EXTRA_STREAMED_DEVICE = PACKAGE_NAME + ".extra.STREAMED_DEVICE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        CharSequence appLabel = null;
        ActivityInfo activityInfo = intent.getParcelableExtra(EXTRA_BLOCKED_ACTIVITY_INFO);
        if (activityInfo != null) {
            appLabel = activityInfo.loadLabel(getPackageManager());
        }

        if (TextUtils.isEmpty(appLabel)) {
            Slog.wtf(TAG, "Invalid activity info: " + activityInfo);
            finish();
            return;
        }

        CharSequence streamedDeviceName = intent.getCharSequenceExtra(EXTRA_STREAMED_DEVICE);
        if (!TextUtils.isEmpty(streamedDeviceName)) {
            mAlertParams.mTitle =
                    TextUtils.equals(activityInfo.packageName,
                        getPackageManager().getPermissionControllerPackageName())
                            ? getString(R.string.app_streaming_blocked_title_for_permission_dialog)
                            : getString(R.string.app_streaming_blocked_title, appLabel);
            mAlertParams.mMessage =
                    getString(R.string.app_streaming_blocked_message, streamedDeviceName);
        } else {
            mAlertParams.mTitle = getString(R.string.app_blocked_title);
            mAlertParams.mMessage = getString(R.string.app_blocked_message, appLabel);
        }
        mAlertParams.mPositiveButtonText = getString(android.R.string.ok);
        setupAlert();
    }

    /**
     * Creates an intent that launches {@link BlockedAppStreamingActivity} when app streaming is
     * blocked.
     */
    public static Intent createIntent(ActivityInfo activityInfo, CharSequence streamedDeviceName) {
        return new Intent()
                .setClassName("android", BlockedAppStreamingActivity.class.getName())
                .putExtra(EXTRA_BLOCKED_ACTIVITY_INFO, activityInfo)
                .putExtra(EXTRA_STREAMED_DEVICE, streamedDeviceName);
    }
}
