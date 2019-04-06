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

package android.service.watchdog;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A PackageInfo contains a package supporting explicit health checks and the
 * timeout in {@link System#uptimeMillis} across reboots after which health
 * check requests from clients are failed.
 *
 * @hide
 */
@SystemApi
public final class PackageInfo implements Parcelable {
    // TODO: Receive from DeviceConfig flag
    private static final long DEFAULT_HEALTH_CHECK_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final String mPackageName;
    private final long mHealthCheckTimeoutMillis;

    /**
     * Creates a new instance.
     *
     * @param packageName the package name
     * @param durationMillis the duration in milliseconds, must be greater than or
     * equal to 0. If it is 0, it will use a system default value.
     */
    public PackageInfo(@NonNull String packageName, long healthCheckTimeoutMillis) {
        mPackageName = Preconditions.checkNotNull(packageName);
        if (healthCheckTimeoutMillis == 0) {
            mHealthCheckTimeoutMillis = DEFAULT_HEALTH_CHECK_TIMEOUT_MILLIS;
        } else {
            mHealthCheckTimeoutMillis = Preconditions.checkArgumentNonnegative(
                    healthCheckTimeoutMillis);
        }
    }

    private PackageInfo(Parcel parcel) {
        mPackageName = parcel.readString();
        mHealthCheckTimeoutMillis = parcel.readLong();
    }

    /**
     * Gets the package name.
     *
     * @return the package name
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * Gets the timeout in milliseconds to evaluate an explicit health check result after a request.
     *
     * @return the duration in {@link System#uptimeMillis} across reboots
     */
    public long getHealthCheckTimeoutMillis() {
        return mHealthCheckTimeoutMillis;
    }

    @Override
    public String toString() {
        return "PackageInfo{" + mPackageName + ", " + mHealthCheckTimeoutMillis + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof PackageInfo)) {
            return false;
        }

        PackageInfo otherInfo = (PackageInfo) other;
        return Objects.equals(otherInfo.getHealthCheckTimeoutMillis(), mHealthCheckTimeoutMillis)
                && Objects.equals(otherInfo.getPackageName(), mPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mHealthCheckTimeoutMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeLong(mHealthCheckTimeoutMillis);
    }

    public static final @NonNull Creator<PackageInfo> CREATOR = new Creator<PackageInfo>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                return new PackageInfo(source);
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return new PackageInfo[size];
            }
        };
}
