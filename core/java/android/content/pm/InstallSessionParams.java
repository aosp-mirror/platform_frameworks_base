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

import android.annotation.Nullable;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.IndentingPrintWriter;

/**
 * Parameters for creating a new {@link PackageInstaller.Session}.
 */
public class InstallSessionParams implements Parcelable {

    /**
     * Mode for an install session whose staged APKs should fully replace any
     * existing APKs for the target app.
     */
    public static final int MODE_FULL_INSTALL = 1;

    /**
     * Mode for an install session that should inherit any existing APKs for the
     * target app, unless they have been explicitly overridden (based on split
     * name) by the session. For example, this can be used to add one or more
     * split APKs to an existing installation.
     * <p>
     * If there are no existing APKs for the target app, this behaves like
     * {@link #MODE_FULL_INSTALL}.
     */
    public static final int MODE_INHERIT_EXISTING = 2;

    /** {@hide} */
    public int mode;
    /** {@hide} */
    public int installFlags;
    /** {@hide} */
    public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
    /** {@hide} */
    public Signature[] signatures;
    /** {@hide} */
    public long sizeBytes = -1;
    /** {@hide} */
    public String appPackageName;
    /** {@hide} */
    public Bitmap appIcon;
    /** {@hide} */
    public CharSequence appLabel;
    /** {@hide} */
    public Uri originatingUri;
    /** {@hide} */
    public Uri referrerUri;
    /** {@hide} */
    public String abiOverride;

    /**
     * Construct parameters for a new package install session.
     *
     * @param mode one of {@link #MODE_FULL_INSTALL} or
     *            {@link #MODE_INHERIT_EXISTING} describing how the session
     *            should interact with an existing app.
     */
    public InstallSessionParams(int mode) {
        this.mode = mode;
    }

    /** {@hide} */
    public InstallSessionParams(Parcel source) {
        mode = source.readInt();
        installFlags = source.readInt();
        installLocation = source.readInt();
        signatures = (Signature[]) source.readParcelableArray(null);
        sizeBytes = source.readLong();
        appPackageName = source.readString();
        appIcon = source.readParcelable(null);
        appLabel = source.readString();
        originatingUri = source.readParcelable(null);
        referrerUri = source.readParcelable(null);
        abiOverride = source.readString();
    }

    /**
     * Provide value of {@link PackageInfo#installLocation}, which may be used
     * to determine where the app will be staged. Defaults to
     * {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}.
     */
    public void setInstallLocation(int installLocation) {
        this.installLocation = installLocation;
    }

    /**
     * Optionally provide a set of certificates for the app being installed.
     * <p>
     * If the APKs staged in the session aren't consistent with these
     * signatures, the install will fail. Regardless of this value, all APKs in
     * the app must have the same signing certificates.
     *
     * @see PackageInfo#signatures
     */
    public void setSignatures(@Nullable Signature[] signatures) {
        this.signatures = signatures;
    }

    /**
     * Optionally indicate the total size (in bytes) of all APKs that will be
     * delivered in this session. The system may use this to ensure enough disk
     * space exists before proceeding, or to estimate container size for
     * installations living on external storage.
     *
     * @see PackageInfo#INSTALL_LOCATION_AUTO
     * @see PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL
     */
    public void setSize(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /**
     * Optionally set the package name of the app being installed. It's strongly
     * recommended that you provide this value when known, so that observers can
     * communicate installing apps to users.
     * <p>
     * If the APKs staged in the session aren't consistent with this package
     * name, the install will fail. Regardless of this value, all APKs in the
     * app must have the same package name.
     */
    public void setAppPackageName(@Nullable String appPackageName) {
        this.appPackageName = appPackageName;
    }

    /**
     * Optionally set an icon representing the app being installed. This should
     * be at least {@link android.R.dimen#app_icon_size} in both dimensions.
     */
    public void setAppIcon(@Nullable Bitmap appIcon) {
        this.appIcon = appIcon;
    }

    /**
     * Optionally set a label representing the app being installed.
     */
    public void setAppLabel(@Nullable CharSequence appLabel) {
        this.appLabel = appLabel;
    }

    /**
     * Optionally set the URI where this package was downloaded from. Used for
     * verification purposes.
     *
     * @see Intent#EXTRA_ORIGINATING_URI
     */
    public void setOriginatingUri(@Nullable Uri originatingUri) {
        this.originatingUri = originatingUri;
    }

    /**
     * Optionally set the URI that referred you to install this package. Used
     * for verification purposes.
     *
     * @see Intent#EXTRA_REFERRER
     */
    public void setReferrerUri(@Nullable Uri referrerUri) {
        this.referrerUri = referrerUri;
    }

    /** {@hide} */
    public void dump(IndentingPrintWriter pw) {
        pw.printPair("mode", mode);
        pw.printHexPair("installFlags", installFlags);
        pw.printPair("installLocation", installLocation);
        pw.printPair("signatures", (signatures != null));
        pw.printPair("sizeBytes", sizeBytes);
        pw.printPair("appPackageName", appPackageName);
        pw.printPair("appIcon", (appIcon != null));
        pw.printPair("appLabel", appLabel);
        pw.printPair("originatingUri", originatingUri);
        pw.printPair("referrerUri", referrerUri);
        pw.printPair("abiOverride", abiOverride);
        pw.println();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mode);
        dest.writeInt(installFlags);
        dest.writeInt(installLocation);
        dest.writeParcelableArray(signatures, flags);
        dest.writeLong(sizeBytes);
        dest.writeString(appPackageName);
        dest.writeParcelable(appIcon, flags);
        dest.writeString(appLabel != null ? appLabel.toString() : null);
        dest.writeParcelable(originatingUri, flags);
        dest.writeParcelable(referrerUri, flags);
        dest.writeString(abiOverride);
    }

    public static final Parcelable.Creator<InstallSessionParams>
            CREATOR = new Parcelable.Creator<InstallSessionParams>() {
                @Override
                public InstallSessionParams createFromParcel(Parcel p) {
                    return new InstallSessionParams(p);
                }

                @Override
                public InstallSessionParams[] newArray(int size) {
                    return new InstallSessionParams[size];
                }
            };
}
