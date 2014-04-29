/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parameters that define an installation session.
 *
 * {@hide}
 */
public class PackageInstallerParams implements Parcelable {

    // TODO: extend to support remaining VerificationParams

    /** {@hide} */
    public boolean fullInstall;
    /** {@hide} */
    public int installFlags;
    /** {@hide} */
    public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
    /** {@hide} */
    public Signature[] signatures;
    /** {@hide} */
    public long deltaSize = -1;
    /** {@hide} */
    public Bitmap icon;
    /** {@hide} */
    public String title;
    /** {@hide} */
    public Uri originatingUri;
    /** {@hide} */
    public Uri referrerUri;

    public PackageInstallerParams() {
    }

    /** {@hide} */
    public PackageInstallerParams(Parcel source) {
        this.fullInstall = source.readInt() != 0;
        this.installFlags = source.readInt();
        this.installLocation = source.readInt();
        this.signatures = (Signature[]) source.readParcelableArray(null);
        deltaSize = source.readLong();
        if (source.readInt() != 0) {
            icon = Bitmap.CREATOR.createFromParcel(source);
        }
        title = source.readString();
        originatingUri = Uri.CREATOR.createFromParcel(source);
        referrerUri = Uri.CREATOR.createFromParcel(source);
    }

    public void setFullInstall(boolean fullInstall) {
        this.fullInstall = fullInstall;
    }

    public void setInstallFlags(int installFlags) {
        this.installFlags = installFlags;
    }

    public void setInstallLocation(int installLocation) {
        this.installLocation = installLocation;
    }

    public void setSignatures(Signature[] signatures) {
        this.signatures = signatures;
    }

    public void setDeltaSize(long deltaSize) {
        this.deltaSize = deltaSize;
    }

    public void setIcon(Bitmap icon) {
        this.icon = icon;
    }

    public void setTitle(CharSequence title) {
        this.title = (title != null) ? title.toString() : null;
    }

    public void setOriginatingUri(Uri originatingUri) {
        this.originatingUri = originatingUri;
    }

    public void setReferrerUri(Uri referrerUri) {
        this.referrerUri = referrerUri;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(fullInstall ? 1 : 0);
        dest.writeInt(installFlags);
        dest.writeInt(installLocation);
        dest.writeParcelableArray(signatures, flags);
        dest.writeLong(deltaSize);
        if (icon != null) {
            dest.writeInt(1);
            icon.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(title);
        dest.writeParcelable(originatingUri, flags);
        dest.writeParcelable(referrerUri, flags);
    }

    public static final Parcelable.Creator<PackageInstallerParams>
            CREATOR = new Parcelable.Creator<PackageInstallerParams>() {
                @Override
                public PackageInstallerParams createFromParcel(Parcel p) {
                    return new PackageInstallerParams(p);
                }

                @Override
                public PackageInstallerParams[] newArray(int size) {
                    return new PackageInstallerParams[size];
                }
            };
}
