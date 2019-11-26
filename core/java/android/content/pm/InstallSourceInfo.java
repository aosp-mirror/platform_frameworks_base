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

    @Nullable private final String mOriginatingPackageName;

    @Nullable private final String mInstallingPackageName;

    /** @hide */
    public InstallSourceInfo(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installingPackageName) {
        this.mInitiatingPackageName = initiatingPackageName;
        this.mOriginatingPackageName = originatingPackageName;
        this.mInstallingPackageName = installingPackageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInitiatingPackageName);
        dest.writeString(mOriginatingPackageName);
        dest.writeString(mInstallingPackageName);
    }

    private InstallSourceInfo(Parcel source) {
        mInitiatingPackageName = source.readString();
        mOriginatingPackageName = source.readString();
        mInstallingPackageName = source.readString();
    }

    /** The name of the package that requested the installation, or null if not available. */
    @Nullable
    public String getInitiatingPackageName() {
        return mInitiatingPackageName;
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
     * Note that this may differ from the initiating package name and can be modified.
     *
     * @see PackageManager#setInstallerPackageName(String, String)
     */
    @Nullable
    public String getInstallingPackageName() {
        return mInstallingPackageName;
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
