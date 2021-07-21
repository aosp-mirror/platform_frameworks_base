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

package android.timezone;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.i18n.timezone.TimeZoneDataFiles;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Objects;

/**
 * Version information associated with the set of time zone data on a device.
 *
 * <p>Time Zone Data Sets have a major ({@link #getFormatMajorVersion()}) and minor
 * ({@link #currentFormatMinorVersion()}) version number:
 * <ul>
 *   <li>Major version numbers are mutually incompatible. e.g. v2 is not compatible with a v1 or a
 *   v3 device.</li>
 *   <li>Minor version numbers are backwards compatible. e.g. a v2.2 data set will work
 *   on a v2.1 device but not a v2.3 device. The minor version is reset to 1 when the major version
 *   is incremented.</li>
 * </ul>
 *
 * <p>Data sets contain time zone rules and other data associated wtih a tzdb release
 * ({@link #getRulesVersion()}) and an additional Android-specific revision number
 * ({@link #getRevision()}).
 *
 * <p>See platform/system/timezone/README.android for more information.
 * @hide
 */
@VisibleForTesting
public final class TzDataSetVersion {

    /**
     * Returns the major tz data format version supported by this device.
     */
    public static int currentFormatMajorVersion() {
        return com.android.i18n.timezone.TzDataSetVersion.currentFormatMajorVersion();
    }

    /**
     * Returns the minor tz data format version supported by this device.
     */
    public static int currentFormatMinorVersion() {
        return com.android.i18n.timezone.TzDataSetVersion.currentFormatMinorVersion();
    }

    /**
     * Returns true if the version information provided would be compatible with this device, i.e.
     * with the current system image, and set of active modules.
     */
    public static boolean isCompatibleWithThisDevice(TzDataSetVersion tzDataSetVersion) {
        return com.android.i18n.timezone.TzDataSetVersion.isCompatibleWithThisDevice(
                tzDataSetVersion.mDelegate);
    }

    /**
     * Reads the current Android time zone data set version file.
     */
    @NonNull
    public static TzDataSetVersion read() throws IOException, TzDataSetException {
        try {
            return new TzDataSetVersion(TimeZoneDataFiles.readTimeZoneModuleVersion());
        } catch (com.android.i18n.timezone.TzDataSetVersion.TzDataSetException e) {
            throw new TzDataSetException(e.getMessage(), e);
        }
    }

    /**
     * A checked exception used in connection with time zone data sets.
     * @hide
     */
    public static final class TzDataSetException extends Exception {

        /** Creates an instance with a message. */
        public TzDataSetException(String message) {
            super(message);
        }

        /** Creates an instance with a message and a cause. */
        public TzDataSetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @NonNull
    private final com.android.i18n.timezone.TzDataSetVersion mDelegate;

    private TzDataSetVersion(@NonNull com.android.i18n.timezone.TzDataSetVersion delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /** Returns the major version number. See {@link TzDataSetVersion}. */
    public int getFormatMajorVersion() {
        return mDelegate.getFormatMajorVersion();
    }

    /** Returns the minor version number. See {@link TzDataSetVersion}. */
    public int getFormatMinorVersion() {
        return mDelegate.getFormatMinorVersion();
    }

    /** Returns the tzdb version string. See {@link TzDataSetVersion}. */
    @NonNull
    public String getRulesVersion() {
        return mDelegate.getRulesVersion();
    }

    /** Returns the Android revision. See {@link TzDataSetVersion}. */
    public int getRevision() {
        return mDelegate.getRevision();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TzDataSetVersion that = (TzDataSetVersion) o;
        return mDelegate.equals(that.mDelegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelegate);
    }

    @Override
    public String toString() {
        return mDelegate.toString();
    }
}
