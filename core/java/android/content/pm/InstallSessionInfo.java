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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Details for an active install session.
 */
public class InstallSessionInfo implements Parcelable {

    /** {@hide} */
    public int sessionId;
    /** {@hide} */
    public String installerPackageName;
    /** {@hide} */
    public String resolvedBaseCodePath;
    /** {@hide} */
    public float progress;
    /** {@hide} */
    public boolean sealed;
    /** {@hide} */
    public boolean open;

    /** {@hide} */
    public int mode;
    /** {@hide} */
    public long sizeBytes;
    /** {@hide} */
    public String appPackageName;
    /** {@hide} */
    public Bitmap appIcon;
    /** {@hide} */
    public CharSequence appLabel;

    /** {@hide} */
    public InstallSessionInfo() {
    }

    /** {@hide} */
    public InstallSessionInfo(Parcel source) {
        sessionId = source.readInt();
        installerPackageName = source.readString();
        resolvedBaseCodePath = source.readString();
        progress = source.readFloat();
        sealed = source.readInt() != 0;
        open = source.readInt() != 0;

        mode = source.readInt();
        sizeBytes = source.readLong();
        appPackageName = source.readString();
        appIcon = source.readParcelable(null);
        appLabel = source.readString();
    }

    /**
     * Return the ID for this session.
     */
    public int getSessionId() {
        return sessionId;
    }

    /**
     * Return the package name of the app that owns this session.
     */
    public @Nullable String getInstallerPackageName() {
        return installerPackageName;
    }

    /**
     * Return current overall progress of this session, between 0 and 1.
     * <p>
     * Note that this progress may not directly correspond to the value reported
     * by {@link PackageInstaller.Session#setProgress(float)}, as the system may
     * carve out a portion of the overall progress to represent its own internal
     * installation work.
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Return if this session is currently open.
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * Return the package name this session is working with. May be {@code null}
     * if unknown.
     */
    public @Nullable String getAppPackageName() {
        return appPackageName;
    }

    /**
     * Return an icon representing the app being installed. May be {@code null}
     * if unavailable.
     */
    public @Nullable Bitmap getAppIcon() {
        return appIcon;
    }

    /**
     * Return a label representing the app being installed. May be {@code null}
     * if unavailable.
     */
    public @Nullable CharSequence getAppLabel() {
        return appLabel;
    }

    /**
     * Return an Intent that can be started to view details about this install
     * session. This may surface actions such as pause, resume, or cancel.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     *
     * @see PackageInstaller#ACTION_SESSION_DETAILS
     */
    public @Nullable Intent getDetailsIntent() {
        final Intent intent = new Intent(PackageInstaller.ACTION_SESSION_DETAILS);
        intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        intent.setPackage(installerPackageName);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(sessionId);
        dest.writeString(installerPackageName);
        dest.writeString(resolvedBaseCodePath);
        dest.writeFloat(progress);
        dest.writeInt(sealed ? 1 : 0);
        dest.writeInt(open ? 1 : 0);

        dest.writeInt(mode);
        dest.writeLong(sizeBytes);
        dest.writeString(appPackageName);
        dest.writeParcelable(appIcon, flags);
        dest.writeString(appLabel != null ? appLabel.toString() : null);
    }

    public static final Parcelable.Creator<InstallSessionInfo>
            CREATOR = new Parcelable.Creator<InstallSessionInfo>() {
                @Override
                public InstallSessionInfo createFromParcel(Parcel p) {
                    return new InstallSessionInfo(p);
                }

                @Override
                public InstallSessionInfo[] newArray(int size) {
                    return new InstallSessionInfo[size];
                }
            };
}
