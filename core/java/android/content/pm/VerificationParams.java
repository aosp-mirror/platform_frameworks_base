/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents verification parameters used to verify packages to be installed.
 *
 * @deprecated callers should migrate to {@link PackageInstaller}.
 * @hide
 */
@Deprecated
public class VerificationParams implements Parcelable {
    /** A constant used to indicate that a uid value is not present. */
    public static final int NO_UID = -1;

    /** What we print out first when toString() is called. */
    private static final String TO_STRING_PREFIX = "VerificationParams{";

    /** The location of the supplementary verification file. */
    private final Uri mVerificationURI;

    /** URI referencing where the package was downloaded from. */
    private final Uri mOriginatingURI;

    /** HTTP referrer URI associated with the originatingURI. */
    private final Uri mReferrer;

    /** UID of the application that the install request originated from. */
    private final int mOriginatingUid;

    /** UID of application requesting the install */
    private int mInstallerUid;

    /**
     * Creates verification specifications for installing with application verification.
     *
     * @param verificationURI The location of the supplementary verification
     *            file. This can be a 'file:' or a 'content:' URI. May be {@code null}.
     * @param originatingURI URI referencing where the package was downloaded
     *            from. May be {@code null}.
     * @param referrer HTTP referrer URI associated with the originatingURI.
     *            May be {@code null}.
     * @param originatingUid UID of the application that the install request originated
     *            from, or NO_UID if not present
     */
    public VerificationParams(Uri verificationURI, Uri originatingURI, Uri referrer,
            int originatingUid) {
        mVerificationURI = verificationURI;
        mOriginatingURI = originatingURI;
        mReferrer = referrer;
        mOriginatingUid = originatingUid;
        mInstallerUid = NO_UID;
    }

    public Uri getVerificationURI() {
        return mVerificationURI;
    }

    public Uri getOriginatingURI() {
        return mOriginatingURI;
    }

    public Uri getReferrer() {
        return mReferrer;
    }

    /** return NO_UID if not available */
    public int getOriginatingUid() {
        return mOriginatingUid;
    }

    /** @return NO_UID when not set */
    public int getInstallerUid() {
        return mInstallerUid;
    }

    public void setInstallerUid(int uid) {
        mInstallerUid = uid;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof VerificationParams)) {
            return false;
        }

        final VerificationParams other = (VerificationParams) o;

        if (mVerificationURI == null) {
            if (other.mVerificationURI != null) {
                return false;
            }
        } else if (!mVerificationURI.equals(other.mVerificationURI)) {
            return false;
        }

        if (mOriginatingURI == null) {
            if (other.mOriginatingURI != null) {
                return false;
            }
        } else if (!mOriginatingURI.equals(other.mOriginatingURI)) {
            return false;
        }

        if (mReferrer == null) {
            if (other.mReferrer != null) {
                return false;
            }
        } else if (!mReferrer.equals(other.mReferrer)) {
            return false;
        }

        if (mOriginatingUid != other.mOriginatingUid) {
            return false;
        }

        if (mInstallerUid != other.mInstallerUid) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int hash = 3;

        hash += 5 * (mVerificationURI == null ? 1 : mVerificationURI.hashCode());
        hash += 7 * (mOriginatingURI == null ? 1 : mOriginatingURI.hashCode());
        hash += 11 * (mReferrer == null ? 1 : mReferrer.hashCode());
        hash += 13 * mOriginatingUid;
        hash += 17 * mInstallerUid;

        return hash;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(TO_STRING_PREFIX);

        sb.append("mVerificationURI=");
        sb.append(mVerificationURI.toString());
        sb.append(",mOriginatingURI=");
        sb.append(mOriginatingURI.toString());
        sb.append(",mReferrer=");
        sb.append(mReferrer.toString());
        sb.append(",mOriginatingUid=");
        sb.append(mOriginatingUid);
        sb.append(",mInstallerUid=");
        sb.append(mInstallerUid);
        sb.append('}');

        return sb.toString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mVerificationURI, 0);
        dest.writeParcelable(mOriginatingURI, 0);
        dest.writeParcelable(mReferrer, 0);
        dest.writeInt(mOriginatingUid);
        dest.writeInt(mInstallerUid);
    }


    private VerificationParams(Parcel source) {
        mVerificationURI = source.readParcelable(Uri.class.getClassLoader());
        mOriginatingURI = source.readParcelable(Uri.class.getClassLoader());
        mReferrer = source.readParcelable(Uri.class.getClassLoader());
        mOriginatingUid = source.readInt();
        mInstallerUid = source.readInt();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<VerificationParams> CREATOR =
            new Parcelable.Creator<VerificationParams>() {
        public VerificationParams createFromParcel(Parcel source) {
                return new VerificationParams(source);
        }

        public VerificationParams[] newArray(int size) {
            return new VerificationParams[size];
        }
    };
}
