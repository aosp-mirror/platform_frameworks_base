/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.os.UserHandle;

/**
 * Navigation bar app information.
 */
class AppInfo {
    private final ComponentName mComponentName;
    private final UserHandle mUser;

    public AppInfo(ComponentName componentName, UserHandle user) {
        if (componentName == null || user == null) throw new IllegalArgumentException();
        mComponentName = componentName;
        mUser = user;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public UserHandle getUser() {
        return mUser;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final AppInfo other = (AppInfo) obj;
        return mComponentName.equals(other.mComponentName) && mUser.equals(other.mUser);
    }
}
