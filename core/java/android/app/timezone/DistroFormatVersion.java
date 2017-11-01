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

package android.app.timezone;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Versioning information about a distro's format or a device's supported format.
 *
 * <p>The following properties are included:
 * <dl>
 *     <dt>majorVersion</dt>
 *     <dd>the major distro format version. Major versions differences are not compatible - e.g.
 *     2 is not compatible with 1 or 3.</dd>
 *     <dt>minorVersion</dt>
 *     <dd>the minor distro format version. Minor versions should be backwards compatible iff the
 *     major versions match exactly, i.e. version 2.2 will be compatible with 2.1 devices but not
 *     2.3 devices.</dd>
 * </dl>
 *
 * @hide
 */
public final class DistroFormatVersion implements Parcelable {

    private final int mMajorVersion;
    private final int mMinorVersion;

    public DistroFormatVersion(int majorVersion, int minorVersion) {
        mMajorVersion = Utils.validateVersion("major", majorVersion);
        mMinorVersion = Utils.validateVersion("minor", minorVersion);
    }

    public static final Creator<DistroFormatVersion> CREATOR = new Creator<DistroFormatVersion>() {
        public DistroFormatVersion createFromParcel(Parcel in) {
            int majorVersion = in.readInt();
            int minorVersion = in.readInt();
            return new DistroFormatVersion(majorVersion, minorVersion);
        }

        public DistroFormatVersion[] newArray(int size) {
            return new DistroFormatVersion[size];
        }
    };

    public int getMajorVersion() {
        return mMajorVersion;
    }

    public int getMinorVersion() {
        return mMinorVersion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mMajorVersion);
        out.writeInt(mMinorVersion);
    }

    /**
     * If this object describes a device's supported version and the parameter describes a distro's
     * version, this method returns whether the device would accept the distro.
     */
    public boolean supports(DistroFormatVersion distroFormatVersion) {
        return mMajorVersion == distroFormatVersion.mMajorVersion
                && mMinorVersion <= distroFormatVersion.mMinorVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DistroFormatVersion that = (DistroFormatVersion) o;

        if (mMajorVersion != that.mMajorVersion) {
            return false;
        }
        return mMinorVersion == that.mMinorVersion;
    }

    @Override
    public int hashCode() {
        int result = mMajorVersion;
        result = 31 * result + mMinorVersion;
        return result;
    }

    @Override
    public String toString() {
        return "DistroFormatVersion{"
                + "mMajorVersion=" + mMajorVersion
                + ", mMinorVersion=" + mMinorVersion
                + '}';
    }
}
