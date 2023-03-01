/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.locales;

import static android.os.Process.INVALID_UID;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Holds data used to report the AppSupportedLocalesChanged atom.
 */
public final class AppSupportedLocalesChangedAtomRecord {
    // The uid which invoked this update.
    final int mCallingUid;
    // The uid for which the override of app’s supported locales change is being done.
    int mTargetUid = INVALID_UID;
    // The total number of locales in the override LocaleConfig.
    int mNumLocales = -1;
    // Whether the override is removed LocaleConfig from the storage.
    boolean mOverrideRemoved = false;
    // Whether the new override LocaleConfig is the same as the app’s LocaleConfig.
    boolean mSameAsResConfig = false;
    // Whether the new override LocaleConfig is the same as the previously effective one. This means
    // a comparison with the previous override LocaleConfig if there was one, and a comparison with
    // the resource LocaleConfig if no override was present.
    boolean mSameAsPrevConfig = false;
    // Application supported locales changed status.
    int mStatus = FrameworkStatsLog
            .APP_SUPPORTED_LOCALES_CHANGED__STATUS__STATUS_UNSPECIFIED;

    AppSupportedLocalesChangedAtomRecord(int callingUid) {
        this.mCallingUid = callingUid;
    }

    void setTargetUid(int targetUid) {
        this.mTargetUid = targetUid;
    }

    void setNumLocales(int numLocales) {
        this.mNumLocales = numLocales;
    }

    void setOverrideRemoved(boolean overrideRemoved) {
        this.mOverrideRemoved = overrideRemoved;
    }

    void setSameAsResConfig(boolean sameAsResConfig) {
        this.mSameAsResConfig = sameAsResConfig;
    }

    void setSameAsPrevConfig(boolean sameAsPrevConfig) {
        this.mSameAsPrevConfig = sameAsPrevConfig;
    }

    void setStatus(int status) {
        this.mStatus = status;
    }
}
