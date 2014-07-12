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

/** {@hide} */
public class InstallSessionInfo implements Parcelable {
    public int sessionId;
    public String installerPackageName;
    public int progress;

    public boolean fullInstall;
    public String packageName;
    public Bitmap icon;
    public CharSequence title;

    /** {@hide} */
    public InstallSessionInfo() {
    }

    /** {@hide} */
    public InstallSessionInfo(Parcel source) {
        sessionId = source.readInt();
        installerPackageName = source.readString();
        progress = source.readInt();

        fullInstall = source.readInt() != 0;
        packageName = source.readString();
        icon = source.readParcelable(null);
        title = source.readString();
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

        dest.writeInt(fullInstall ? 1 : 0);
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
