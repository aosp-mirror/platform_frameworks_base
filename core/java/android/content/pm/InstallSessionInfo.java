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
    public int progress;

    /** {@hide} */
    public int mode;
    /** {@hide} */
    public String packageName;
    /** {@hide} */
    public Bitmap icon;
    /** {@hide} */
    public CharSequence title;

    /** {@hide} */
    public InstallSessionInfo() {
    }

    /** {@hide} */
    public InstallSessionInfo(Parcel source) {
        sessionId = source.readInt();
        installerPackageName = source.readString();
        progress = source.readInt();

        mode = source.readInt();
        packageName = source.readString();
        icon = source.readParcelable(null);
        title = source.readString();
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
    public String getInstallerPackageName() {
        return installerPackageName;
    }

    /**
     * Return current overall progress of this session, between 0 and 100.
     * <p>
     * Note that this progress may not directly correspond to the value reported
     * by {@link PackageInstaller.Session#setProgress(int)}, as the system may
     * carve out a portion of the overall progress to represent its own internal
     * installation work.
     */
    public int getProgress() {
        return progress;
    }

    /**
     * Return the package name this session is working with. May be {@code null}
     * if unknown.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Return an icon representing the app being installed. May be {@code null}
     * if unavailable.
     */
    public Bitmap getIcon() {
        return icon;
    }

    /**
     * Return a title representing the app being installed. May be {@code null}
     * if unavailable.
     */
    public CharSequence getTitle() {
        return title;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(sessionId);
        dest.writeString(installerPackageName);
        dest.writeInt(progress);

        dest.writeInt(mode);
        dest.writeString(packageName);
        dest.writeParcelable(icon, flags);
        dest.writeString(title != null ? title.toString() : null);
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
