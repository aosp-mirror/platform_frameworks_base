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

package com.android.server.locales;

import static android.os.Process.INVALID_UID;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Holds data used to report the ApplicationLocalesChanged atom.
 */
public final class AppLocaleChangedAtomRecord {
    final int mCallingUid;
    int mTargetUid = INVALID_UID;
    String mNewLocales = "";
    String mPrevLocales = "";
    int mStatus = FrameworkStatsLog
            .APPLICATION_LOCALES_CHANGED__STATUS__STATUS_UNSPECIFIED;

    AppLocaleChangedAtomRecord(int callingUid) {
        this.mCallingUid = callingUid;
    }

    void setNewLocales(String newLocales) {
        this.mNewLocales = newLocales;
    }

    void setTargetUid(int targetUid) {
        this.mTargetUid = targetUid;
    }

    void setPrevLocales(String prevLocales) {
        this.mPrevLocales = prevLocales;
    }

    void setStatus(int status) {
        this.mStatus = status;
    }
}
