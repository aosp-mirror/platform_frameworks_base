/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information about how an app was installed.
 * @see PackageManager#getInstallSourceInfo(String)
 */
public final class InstallSourceInfo implements Parcelable {

    @Nullable private final String mInitiatingPackageName;

    @Nullable private final SigningInfo mInitiatingPackageSigningInfo;

    @Nullable private final String mOriginatingPackageName;

    @Nullable private final String mInstallingPackageName;

    @Nullable private final String mUpdateOwnerPackageName;

    @Nullable private final int mPackageSource;

    /** @hide */
    public InstallSourceInfo(@Nullable String initiatingPackageName,
            @Nullable SigningInfo initiatingPackageSigningInfo,
            @Nullable String originatingPackageName, @Nullable String installingPackageName) {
        this(initiatingPackageName, initiatingPackageSigningInfo, originatingPackageName,
                installingPackageName, null /* updateOwnerPackageName */,
                PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
    }

    /** @hide */
    public InstallSourceInfo(@Nullable String initiatingPackageName,
            @Nullable SigningInfo initiatingPackageSigningInfo,
            @Nullable String originatingPackageName, @Nullable String installingPackageName,
            @Nullable String updateOwnerPackageName, int packageSource) {
        mInitiatingPackageName = initiatingPackageName;
        mInitiatingPackageSigningInfo = initiatingPackageSigningInfo;
        mOriginatingPackageName = originatingPackageName;
        mInstallingPackageName = installingPackageName;
        mUpdateOwnerPackageName = updateOwnerPackageName;
        mPackageSource = packageSource;
    }

    @Override
    public int describeContents() {
        return mInitiatingPackageSigningInfo == null
                ? 0 : mInitiatingPackageSigningInfo.describeContents();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInitiatingPackageName);
        dest.writeParcelable(mInitiatingPackageSigningInfo, flags);
        dest.writeString(mOriginatingPackageName);
        dest.writeString(mInstallingPackageName);
        dest.writeString8(mUpdateOwnerPackageName);
        dest.writeInt(mPackageSource);
    }

    private InstallSourceInfo(Parcel source) {
        mInitiatingPackageName = source.readString();
        mInitiatingPackageSigningInfo = source.readParcelable(SigningInfo.class.getClassLoader(), android.content.pm.SigningInfo.class);
        mOriginatingPackageName = source.readString();
        mInstallingPackageName = source.readString();
        mUpdateOwnerPackageName = source.readString8();
        mPackageSource = source.readInt();
    }

    /**
     * The name of the package that requested the installation, or null if not available.
     *
     * This is normally the same as the installing package name. If the installing package name
     * is changed, for example by calling
     * {@link PackageManager#setInstallerPackageName(String, String)}, the initiating package name
     * remains unchanged. It continues to identify the actual package that performed the install
     * or update.
     * <p>
     * Null may be returned if the app was not installed by a package (e.g. a system app or an app
     * installed via adb) or if the initiating package has itself been uninstalled.
     */
    @Nullable
    public String getInitiatingPackageName() {
        return mInitiatingPackageName;
    }

    /**
     * Information about the signing certificates used to sign the initiating package, if available.
     */
    @Nullable
    public SigningInfo getInitiatingPackageSigningInfo() {
        return mInitiatingPackageSigningInfo;
    }

    /**
     * The name of the package on behalf of which the initiating package requested the installation,
     * or null if not available.
     * <p>
     * For example if a downloaded APK is installed via the Package Installer this could be the
     * app that performed the download. This value is provided by the initiating package and not
     * verified by the framework.
     * <p>
     * Note that the {@code InstallSourceInfo} returned by
     * {@link PackageManager#getInstallSourceInfo(String)} will not have this information
     * available unless the calling application holds the INSTALL_PACKAGES permission.
     */
    @Nullable
    public String getOriginatingPackageName() {
        return mOriginatingPackageName;
    }

    /**
     * The name of the package responsible for the installation (the installer of record), or null
     * if not available.
     * Note that this may differ from the initiating package name and can be modified via
     * {@link PackageManager#setInstallerPackageName(String, String)}.
     * <p>
     * Null may be returned if the app was not installed by a package (e.g. a system app or an app
     * installed via adb) or if the installing package has itself been uninstalled.
     */
    @Nullable
    public String getInstallingPackageName() {
        return mInstallingPackageName;
    }

    /**
     * The name of the package that is the update owner, or null if not available.
     *
     * This indicates the update ownership enforcement is enabled for this app,
     * and which package is the update owner.
     *
     * Returns null if the update ownership enforcement is disabled for the app.
     *
     * @see PackageInstaller.SessionParams#setRequestUpdateOwnership
     */
    @Nullable
    public String getUpdateOwnerPackageName() {
        return mUpdateOwnerPackageName;
    }

    /**
     * Information about the package source when installer installed this app.
     */
    public @PackageInstaller.PackageSourceType int getPackageSource() {
        return mPackageSource;
    }

    @NonNull
    public static final Parcelable.Creator<InstallSourceInfo> CREATOR =
            new Creator<InstallSourceInfo>() {
                @Override
                public InstallSourceInfo createFromParcel(Parcel source) {
                    return new InstallSourceInfo(source);
                }

                @Override
                public InstallSourceInfo[] newArray(int size) {
                    return new InstallSourceInfo[size];
                }
            };
}
