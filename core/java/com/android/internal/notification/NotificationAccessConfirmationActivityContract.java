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
import android.content.Intent;

public final class NotificationAccessConfirmationActivityContract {
    private static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.settings",
            "com.android.settings.notification.NotificationAccessConfirmationActivity");
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_COMPONENT_NAME = "component_name";
    public static final String EXTRA_PACKAGE_TITLE = "package_title";

    public static Intent launcherIntent(int userId, ComponentName component, String packageTitle) {
        return new Intent()
                .setComponent(COMPONENT_NAME)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_COMPONENT_NAME, component)
                .putExtra(EXTRA_PACKAGE_TITLE, packageTitle);
    }
}
