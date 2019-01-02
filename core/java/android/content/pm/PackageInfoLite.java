/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.content.PackageHelper;

/**
 * Basic information about a package as specified in its manifest.
 * Utility class used in PackageManager methods
 * @hide
 */
public class PackageInfoLite implements Parcelable {
    /**
     * The name of this package.  From the &lt;manifest&gt; tag's "name"
     * attribute.
     */
    public String packageName;

    /** Names of any split APKs, ordered by parsed splitName */
    public String[] splitNames;

    /**
     * The android:versionCode of the package.
     * @deprecated Use {@link #getLongVersionCode()} instead, which includes both
     * this and the additional
     * {@link android.R.styleable#AndroidManifest_versionCode versionCodeMajor} attribute.
     */
    @Deprecated
    public int versionCode;

    /**
     * @hide
     * The android:versionCodeMajor of the package.
     */
    public int versionCodeMajor;

    /**
     * Return {@link #versionCode} and {@link #versionCodeMajor} combined together as a
     * single long value.  The {@link #versionCodeMajor} is placed in the upper 32 bits.
     */
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    /** Revision code of base APK */
    public int baseRevisionCode;
    /** Revision codes of any split APKs, ordered by parsed splitName */
    public int[] splitRevisionCodes;

    /**
     * The android:multiArch flag from the package manifest. If set,
     * we will extract all native libraries for the given app, not just those
     * from the preferred ABI.
     */
    public boolean multiArch;

    /**
     * Specifies the recommended install location. Can be one of
     * {@link PackageHelper#RECOMMEND_INSTALL_INTERNAL} to install on internal storage,
     * {@link PackageHelper#RECOMMEND_INSTALL_EXTERNAL} to install on external media,
     * {@link PackageHelper#RECOMMEND_FAILED_INSUFFICIENT_STORAGE} for storage errors,
     * or {@link PackageHelper#RECOMMEND_FAILED_INVALID_APK} for parse errors.
     */
    public int recommendedInstallLocation;
    public int installLocation;

    public VerifierInfo[] verifiers;

    public PackageInfoLite() {
    }

    public String toString() {
        return "PackageInfoLite{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(packageName);
        dest.writeStringArray(splitNames);
        dest.writeInt(versionCode);
        dest.writeInt(versionCodeMajor);
        dest.writeInt(baseRevisionCode);
        dest.writeIntArray(splitRevisionCodes);
        dest.writeInt(recommendedInstallLocation);
        dest.writeInt(installLocation);
        dest.writeInt(multiArch ? 1 : 0);

        if (verifiers == null || verifiers.length == 0) {
            dest.writeInt(0);
        } else {
            dest.writeInt(verifiers.length);
            dest.writeTypedArray(verifiers, parcelableFlags);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final Parcelable.Creator<PackageInfoLite> CREATOR
            = new Parcelable.Creator<PackageInfoLite>() {
        public PackageInfoLite createFromParcel(Parcel source) {
            return new PackageInfoLite(source);
        }

        public PackageInfoLite[] newArray(int size) {
            return new PackageInfoLite[size];
        }
    };

    private PackageInfoLite(Parcel source) {
        packageName = source.readString();
        splitNames = source.createStringArray();
        versionCode = source.readInt();
        versionCodeMajor = source.readInt();
        baseRevisionCode = source.readInt();
        splitRevisionCodes = source.createIntArray();
        recommendedInstallLocation = source.readInt();
        installLocation = source.readInt();
        multiArch = (source.readInt() != 0);

        final int verifiersLength = source.readInt();
        if (verifiersLength == 0) {
            verifiers = new VerifierInfo[0];
        } else {
            verifiers = new VerifierInfo[verifiersLength];
            source.readTypedArray(verifiers, VerifierInfo.CREATOR);
        }
    }
}
