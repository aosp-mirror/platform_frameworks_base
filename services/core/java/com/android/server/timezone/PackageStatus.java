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

package com.android.server.timezone;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information about the status of the time zone update / data packages that are persisted by the
 * Android system.
 */
final class PackageStatus {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ CHECK_STARTED, CHECK_COMPLETED_SUCCESS, CHECK_COMPLETED_FAILURE })
    @interface CheckStatus {}

    /** A time zone update check has been started but not yet completed. */
    static final int CHECK_STARTED = 1;
    /** A time zone update check has been completed and succeeded. */
    static final int CHECK_COMPLETED_SUCCESS = 2;
    /** A time zone update check has been completed and failed. */
    static final int CHECK_COMPLETED_FAILURE = 3;

    @CheckStatus
    final int mCheckStatus;

    // Non-null
    final PackageVersions mVersions;

    PackageStatus(@CheckStatus int checkStatus, PackageVersions versions) {
        this.mCheckStatus = checkStatus;
        if (checkStatus < 1 || checkStatus > 3) {
            throw new IllegalArgumentException("Unknown checkStatus " + checkStatus);
        }
        if (versions == null) {
            throw new NullPointerException("versions == null");
        }
        this.mVersions = versions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PackageStatus that = (PackageStatus) o;

        if (mCheckStatus != that.mCheckStatus) {
            return false;
        }
        return mVersions.equals(that.mVersions);
    }

    @Override
    public int hashCode() {
        int result = mCheckStatus;
        result = 31 * result + mVersions.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "PackageStatus{" +
                "mCheckStatus=" + mCheckStatus +
                ", mVersions=" + mVersions +
                '}';
    }
}
