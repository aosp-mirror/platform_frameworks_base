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

/**
 * Package version information about the time zone updater and time zone data application packages.
 */
final class PackageVersions {

    final int mUpdateAppVersion;
    final int mDataAppVersion;

    PackageVersions(int updateAppVersion, int dataAppVersion) {
        this.mUpdateAppVersion = updateAppVersion;
        this.mDataAppVersion = dataAppVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PackageVersions that = (PackageVersions) o;

        if (mUpdateAppVersion != that.mUpdateAppVersion) {
            return false;
        }
        return mDataAppVersion == that.mDataAppVersion;
    }

    @Override
    public int hashCode() {
        int result = mUpdateAppVersion;
        result = 31 * result + mDataAppVersion;
        return result;
    }

    @Override
    public String toString() {
        return "PackageVersions{" +
                "mUpdateAppVersion=" + mUpdateAppVersion +
                ", mDataAppVersion=" + mDataAppVersion +
                '}';
    }
}
