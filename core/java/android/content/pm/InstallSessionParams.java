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

    // TODO: extend to support remaining VerificationParams

    /** {@hide} */
    public static final int MODE_INVALID = 0;
    /** {@hide} */
    public static final int MODE_FULL_INSTALL = 1;
    /** {@hide} */
    public static final int MODE_INHERIT_EXISTING = 2;

    /** {@hide} */
    public int mode = MODE_INVALID;
    /** {@hide} */
    public int installFlags;
    /** {@hide} */
    public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
    /** {@hide} */
    public Signature[] signatures;
    /** {@hide} */
    public long deltaSize = -1;
    /** {@hide} */
    public int progressMax = 100;
    /** {@hide} */
    public String packageName;
    /** {@hide} */
    public Bitmap icon;
    /** {@hide} */
    public CharSequence title;
    /** {@hide} */
    public Uri originatingUri;
    /** {@hide} */
    public Uri referrerUri;
    /** {@hide} */
    public String abiOverride;

    public InstallSessionParams() {
    }

    /** {@hide} */
    public InstallSessionParams(Parcel source) {
        mode = source.readInt();
        installFlags = source.readInt();
        installLocation = source.readInt();
        signatures = (Signature[]) source.readParcelableArray(null);
        deltaSize = source.readLong();
        progressMax = source.readInt();
        packageName = source.readString();
        icon = source.readParcelable(null);
        title = source.readString();
        originatingUri = source.readParcelable(null);
        referrerUri = source.readParcelable(null);
        abiOverride = source.readString();
    }

    /**
     * Set session mode indicating that it should fully replace any existing
     * APKs for this application.
     */
    public void setModeFullInstall() {
        this.mode = MODE_FULL_INSTALL;
    }

    /**
     * Set session mode indicating that it should inherit any existing APKs for
     * this application, unless they are explicitly overridden (by split name)
     * in the session.
     */
    public void setModeInheritExisting() {
        this.mode = MODE_INHERIT_EXISTING;
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
     * Optionally provide the required value of {@link PackageInfo#signatures}.
     * This can be used to assert that all staged APKs have been signed with
     * this set of specific certificates. Regardless of this value, all APKs in
     * the application must have the same signing certificates.
     */
    public void setSignatures(Signature[] signatures) {
        this.signatures = signatures;
    }

    /**
     * Indicate the expected growth in disk usage resulting from this session.
     * This may be used to ensure enough disk space exists before proceeding, or
     * to estimate container size for installations living on external storage.
     * <p>
     * This value should only reflect APK sizes.
     */
    public void setDeltaSize(long deltaSize) {
        this.deltaSize = deltaSize;
    }

    /**
     * Set the maximum progress for this session, used for normalization
     * purposes.
     *
     * @see PackageInstaller.Session#setProgress(int)
     */
    public void setProgressMax(int progressMax) {
        this.progressMax = progressMax;
    }

    /**
     * Optionally set the package name this session will be working with. It's
     * strongly recommended that you provide this value when known.
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Optionally set an icon representing the app being installed.
     */
    public void setIcon(Bitmap icon) {
        this.icon = icon;
    }

    /**
     * Optionally set a title representing the app being installed.
     */
    public void setTitle(CharSequence title) {
        this.title = title;
    }

    /**
     * Optionally set the URI where this package was downloaded from. Used for
     * verification purposes.
     *
     * @see Intent#EXTRA_ORIGINATING_URI
     */
    public void setOriginatingUri(Uri originatingUri) {
        this.originatingUri = originatingUri;
    }

    /**
     * Optionally set the URI that referred you to install this package. Used
     * for verification purposes.
     *
     * @see Intent#EXTRA_REFERRER
     */
    public void setReferrerUri(Uri referrerUri) {
        this.referrerUri = referrerUri;
    }

    /** {@hide} */
    public void dump(IndentingPrintWriter pw) {
        pw.printPair("mode", mode);
        pw.printHexPair("installFlags", installFlags);
        pw.printPair("installLocation", installLocation);
        pw.printPair("signatures", (signatures != null));
        pw.printPair("deltaSize", deltaSize);
        pw.printPair("progressMax", progressMax);
        pw.printPair("packageName", packageName);
        pw.printPair("icon", (icon != null));
        pw.printPair("title", title);
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
        dest.writeLong(deltaSize);
        dest.writeInt(progressMax);
        dest.writeString(packageName);
        dest.writeParcelable(icon, flags);
        dest.writeString(title != null ? title.toString() : null);
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
