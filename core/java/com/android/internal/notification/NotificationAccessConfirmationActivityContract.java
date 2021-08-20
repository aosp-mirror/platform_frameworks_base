/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.notification;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.android.internal.R;

/**
 * This class provides methods to create intents for NotificationAccessConfirmationActivity.
 */
public final class NotificationAccessConfirmationActivityContract {
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_COMPONENT_NAME = "component_name";
    public static final String EXTRA_PACKAGE_TITLE = "package_title";

    /**
     * Creates a launcher intent for NotificationAccessConfirmationActivity.
     */
    public static Intent launcherIntent(Context context, int userId, ComponentName component,
            String packageTitle) {
        return new Intent()
                .setComponent(ComponentName.unflattenFromString(context.getString(
                        R.string.config_notificationAccessConfirmationActivity)))
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_COMPONENT_NAME, component)
                .putExtra(EXTRA_PACKAGE_TITLE, packageTitle);
    }
}
