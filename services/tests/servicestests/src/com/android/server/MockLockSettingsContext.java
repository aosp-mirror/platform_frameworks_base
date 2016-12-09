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
 * limitations under the License
 */

package com.android.server;

import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.UserManager;

public class MockLockSettingsContext extends ContextWrapper {

    private UserManager mUserManager;
    private NotificationManager mNotificationManager;

    public MockLockSettingsContext(Context base, UserManager userManager,
            NotificationManager notificationManager) {
        super(base);
        mUserManager = userManager;
        mNotificationManager = notificationManager;
    }

    @Override
    public Object getSystemService(String name) {
        if (USER_SERVICE.equals(name)) {
            return mUserManager;
        } else if (NOTIFICATION_SERVICE.equals(name)) {
            return mNotificationManager;
        } else {
            throw new RuntimeException("System service not mocked: " + name);
        }
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        // Skip permission checks for unit tests.
    }

}
