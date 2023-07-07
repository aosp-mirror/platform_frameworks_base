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

import java.util.Locale;

/**
 * Holds data used to report the ApplicationLocalesChanged atom.
 */
public final class AppLocaleChangedAtomRecord {
    private static final String DEFAULT_PREFIX = "default-";
    final int mCallingUid;
    int mTargetUid = INVALID_UID;
    String mNewLocales = DEFAULT_PREFIX;
    String mPrevLocales = DEFAULT_PREFIX;
    int mStatus = FrameworkStatsLog
            .APPLICATION_LOCALES_CHANGED__STATUS__STATUS_UNSPECIFIED;
    int mCaller = FrameworkStatsLog
            .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_UNKNOWN;
    AppLocaleChangedAtomRecord(int callingUid) {
        this.mCallingUid = callingUid;
        Locale defaultLocale = Locale.getDefault();
        if (defaultLocale != null) {
            this.mNewLocales = DEFAULT_PREFIX + defaultLocale.toLanguageTag();
            this.mPrevLocales = DEFAULT_PREFIX + defaultLocale.toLanguageTag();
        }
    }

    void setNewLocales(String newLocales) {
        this.mNewLocales = convertEmptyLocales(newLocales);
    }

    void setTargetUid(int targetUid) {
        this.mTargetUid = targetUid;
    }

    void setPrevLocales(String prevLocales) {
        this.mPrevLocales = convertEmptyLocales(prevLocales);
    }

    void setStatus(int status) {
        this.mStatus = status;
    }

    void setCaller(int caller) {
        this.mCaller = caller;
    }

    private String convertEmptyLocales(String locales) {
        String target = locales;
        if ("".equals(locales)) {
            Locale defaultLocale = Locale.getDefault();
            if (defaultLocale != null) {
                target = DEFAULT_PREFIX + defaultLocale.toLanguageTag();
            }
        }

        return target;
    }
}
