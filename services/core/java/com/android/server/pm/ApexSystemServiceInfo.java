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

package com.android.server.pm;

import android.annotation.Nullable;

/**
 * A helper class that contains information about apex-system-service to be used within system
 * server process.
 */
public final class ApexSystemServiceInfo implements Comparable<ApexSystemServiceInfo> {

    final String mName;
    @Nullable
    final String mJarPath;
    final int mInitOrder;

    public ApexSystemServiceInfo(String name, String jarPath, int initOrder) {
        this.mName = name;
        this.mJarPath = jarPath;
        this.mInitOrder = initOrder;
    }

    public String getName() {
        return mName;
    }

    public String getJarPath() {
        return mJarPath;
    }

    public int getInitOrder() {
        return mInitOrder;
    }

    @Override
    public int compareTo(ApexSystemServiceInfo other) {
        if (mInitOrder == other.mInitOrder) {
            return mName.compareTo(other.mName);
        }
        // higher initOrder values take precedence
        return -Integer.compare(mInitOrder, other.mInitOrder);
    }
}
