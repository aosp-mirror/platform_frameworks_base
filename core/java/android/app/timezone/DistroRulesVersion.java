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

import static android.app.timezone.Utils.validateRulesVersion;
import static android.app.timezone.Utils.validateVersion;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Versioning information about a set of time zone rules.
 *
 * <p>The following properties are included:
 * <dl>
 *     <dt>rulesVersion</dt>
 *     <dd>the IANA rules version. e.g. "2017a"</dd>
 *     <dt>revision</dt>
 *     <dd>the revision for the rules. Allows there to be several revisions for a given IANA rules
 *     release. Numerically higher is newer.</dd>
 * </dl>
 *
 * @hide
 */
public final class DistroRulesVersion implements Parcelable {

    private final String mRulesVersion;
    private final int mRevision;

    public DistroRulesVersion(String rulesVersion, int revision) {
        mRulesVersion = validateRulesVersion("rulesVersion", rulesVersion);
        mRevision = validateVersion("revision", revision);
    }

    public static final Creator<DistroRulesVersion> CREATOR = new Creator<DistroRulesVersion>() {
        public DistroRulesVersion createFromParcel(Parcel in) {
            String rulesVersion = in.readString();
            int revision = in.readInt();
            return new DistroRulesVersion(rulesVersion, revision);
        }

        public DistroRulesVersion[] newArray(int size) {
            return new DistroRulesVersion[size];
        }
    };

    public String getRulesVersion() {
        return mRulesVersion;
    }

    public int getRevision() {
        return mRevision;
    }

    /**
     * Returns true if this DistroRulesVersion is older than the one supplied. It returns false if
     * it is the same or newer. This method compares the {@code rulesVersion} and the
     * {@code revision}.
     */
    public boolean isOlderThan(DistroRulesVersion distroRulesVersion) {
        int rulesComparison = mRulesVersion.compareTo(distroRulesVersion.mRulesVersion);
        if (rulesComparison < 0) {
            return true;
        }
        if (rulesComparison > 0) {
            return false;
        }
        return mRevision < distroRulesVersion.mRevision;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mRulesVersion);
        out.writeInt(mRevision);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DistroRulesVersion that = (DistroRulesVersion) o;

        if (mRevision != that.mRevision) {
            return false;
        }
        return mRulesVersion.equals(that.mRulesVersion);
    }

    @Override
    public int hashCode() {
        int result = mRulesVersion.hashCode();
        result = 31 * result + mRevision;
        return result;
    }

    @Override
    public String toString() {
        return "DistroRulesVersion{"
                + "mRulesVersion='" + mRulesVersion + '\''
                + ", mRevision='" + mRevision + '\''
                + '}';
    }

    public String toDumpString() {
        return mRulesVersion + "," + mRevision;
    }
}
