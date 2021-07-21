/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;

final class PackageAndUser {
    public final @NonNull String packageName;
    public final @UserIdInt int userId;

    PackageAndUser(@NonNull String packageName, @UserIdInt int userId) {
        this.packageName = packageName;
        this.userId = userId;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PackageAndUser)) {
            return false;
        }
        PackageAndUser other = (PackageAndUser) obj;
        return packageName.equals(other.packageName) && userId == other.userId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + packageName.hashCode();
        result = prime * result + userId;
        return result;
    }

    @Override
    public String toString() {
        return String.format("PackageAndUser{packageName=%s, userId=%d}", packageName, userId);
    }
}
