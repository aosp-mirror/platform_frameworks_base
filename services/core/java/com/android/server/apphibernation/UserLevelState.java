/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import android.annotation.CurrentTimeMillisLong;

import java.text.SimpleDateFormat;

/**
 * Data class that contains hibernation state info of a package for a user.
 */
final class UserLevelState {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public String packageName;
    public boolean hibernated;
    @CurrentTimeMillisLong
    public long lastUnhibernatedMs;

    UserLevelState() {}

    UserLevelState(UserLevelState state) {
        packageName = state.packageName;
        hibernated = state.hibernated;
        lastUnhibernatedMs = state.lastUnhibernatedMs;
    }

    @Override
    public String toString() {
        return "UserLevelState{"
                + "packageName='" + packageName + '\''
                + ", hibernated=" + hibernated + '\''
                + ", lastUnhibernated=" + DATE_FORMAT.format(lastUnhibernatedMs)
                + '}';
    }
}
