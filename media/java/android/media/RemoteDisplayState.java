/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Information available from IRemoteDisplayProvider about available remote displays.
 *
 * Clients must not modify the contents of this object.
 * @hide
 */
public final class RemoteDisplayState implements Parcelable {
    // Note: These constants are used by the remote display provider API.
    // Do not change them!
    public static final String SERVICE_INTERFACE =
            "com.android.media.remotedisplay.RemoteDisplayProvider";
    public static final int DISCOVERY_MODE_NONE = 0;
    public static final int DISCOVERY_MODE_PASSIVE = 1;
    public static final int DISCOVERY_MODE_ACTIVE = 2;

    /**
     * A list of all remote displays.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final ArrayList<RemoteDisplayInfo> displays;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public RemoteDisplayState() {
        displays = new ArrayList<RemoteDisplayInfo>();
    }

    RemoteDisplayState(Parcel src) {
        displays = src.createTypedArrayList(RemoteDisplayInfo.CREATOR);
    }

    public boolean isValid() {
        if (displays == null) {
            return false;
        }
        final int count = displays.size();
        for (int i = 0; i < count; i++) {
            if (!displays.get(i).isValid()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(displays);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RemoteDisplayState> CREATOR =
            new Parcelable.Creator<RemoteDisplayState>() {
        @Override
        public RemoteDisplayState createFromParcel(Parcel in) {
            return new RemoteDisplayState(in);
        }

        @Override
        public RemoteDisplayState[] newArray(int size) {
            return new RemoteDisplayState[size];
        }
    };

    public static final class RemoteDisplayInfo implements Parcelable {
        // Note: These constants are used by the remote display provider API.
        // Do not change them!
        public static final int STATUS_NOT_AVAILABLE = 0;
        public static final int STATUS_IN_USE = 1;
        public static final int STATUS_AVAILABLE = 2;
        public static final int STATUS_CONNECTING = 3;
        public static final int STATUS_CONNECTED = 4;

        public static final int PLAYBACK_VOLUME_VARIABLE =
                MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE;
        public static final int PLAYBACK_VOLUME_FIXED =
                MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED;

        public String id;
        public String name;
        public String description;
        public int status;
        public int volume;
        public int volumeMax;
        public int volumeHandling;
        public int presentationDisplayId;

        public RemoteDisplayInfo(String id) {
            this.id = id;
            status = STATUS_NOT_AVAILABLE;
            volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED;
            presentationDisplayId = -1;
        }

        public RemoteDisplayInfo(RemoteDisplayInfo other) {
            id = other.id;
            name = other.name;
            description = other.description;
            status = other.status;
            volume = other.volume;
            volumeMax = other.volumeMax;
            volumeHandling = other.volumeHandling;
            presentationDisplayId = other.presentationDisplayId;
        }

        RemoteDisplayInfo(Parcel in) {
            id = in.readString();
            name = in.readString();
            description = in.readString();
            status = in.readInt();
            volume = in.readInt();
            volumeMax = in.readInt();
            volumeHandling = in.readInt();
            presentationDisplayId = in.readInt();
        }

        public boolean isValid() {
            return !TextUtils.isEmpty(id) && !TextUtils.isEmpty(name);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(id);
            dest.writeString(name);
            dest.writeString(description);
            dest.writeInt(status);
            dest.writeInt(volume);
            dest.writeInt(volumeMax);
            dest.writeInt(volumeHandling);
            dest.writeInt(presentationDisplayId);
        }

        @Override
        public String toString() {
            return "RemoteDisplayInfo{ id=" + id
                    + ", name=" + name
                    + ", description=" + description
                    + ", status=" + status
                    + ", volume=" + volume
                    + ", volumeMax=" + volumeMax
                    + ", volumeHandling=" + volumeHandling
                    + ", presentationDisplayId=" + presentationDisplayId
                    + " }";
        }

        @SuppressWarnings("hiding")
        public static final @android.annotation.NonNull Parcelable.Creator<RemoteDisplayInfo> CREATOR =
                new Parcelable.Creator<RemoteDisplayInfo>() {
            @Override
            public RemoteDisplayInfo createFromParcel(Parcel in) {
                return new RemoteDisplayInfo(in);
            }

            @Override
            public RemoteDisplayInfo[] newArray(int size) {
                return new RemoteDisplayInfo[size];
            }
        };
    }
}
