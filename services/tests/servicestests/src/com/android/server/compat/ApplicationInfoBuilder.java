/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.compat;

import android.content.pm.ApplicationInfo;

class ApplicationInfoBuilder {
    private boolean mIsDebuggable;
    private int mTargetSdk;
    private int mUid;
    private String mPackageName;
    private long mVersionCode;
    private boolean mIsSystemApp;

    private ApplicationInfoBuilder() {
        mTargetSdk = -1;
    }

    static ApplicationInfoBuilder create() {
        return new ApplicationInfoBuilder();
    }

    ApplicationInfoBuilder withTargetSdk(int targetSdk) {
        mTargetSdk = targetSdk;
        return this;
    }

    ApplicationInfoBuilder debuggable() {
        mIsDebuggable = true;
        return this;
    }

    ApplicationInfoBuilder systemApp() {
        mIsSystemApp = true;
        return this;
    }

    ApplicationInfoBuilder withUid(int uid) {
        mUid = uid;
        return this;
    }

    ApplicationInfoBuilder withPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    ApplicationInfoBuilder withVersionCode(Long versionCode) {
        mVersionCode = versionCode;
        return this;
    }

    ApplicationInfo build() {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        if (mIsDebuggable) {
            applicationInfo.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        }
        applicationInfo.packageName = mPackageName;
        applicationInfo.targetSdkVersion = mTargetSdk;
        applicationInfo.longVersionCode = mVersionCode;
        applicationInfo.uid = mUid;
        if (mIsSystemApp) {
            applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        return applicationInfo;
    }
}
