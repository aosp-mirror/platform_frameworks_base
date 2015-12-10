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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * Information available from MediaRouterService about the state perceived by
 * a particular client and the routes that are available to it.
 *
 * Clients must not modify the contents of this object.
 * @hide
 */
public final class MediaRouterClientState implements Parcelable {
    /**
     * A list of all known routes.
     */
    public final ArrayList<RouteInfo> routes;

    /**
     * The id of the current globally selected route, or null if none.
     * Globally selected routes override any other route selections that applications
     * may have made.  Used for remote displays.
     */
    public String globallySelectedRouteId;

    public MediaRouterClientState() {
        routes = new ArrayList<RouteInfo>();
    }

    MediaRouterClientState(Parcel src) {
        routes = src.createTypedArrayList(RouteInfo.CREATOR);
        globallySelectedRouteId = src.readString();
    }

    public RouteInfo getRoute(String id) {
        final int count = routes.size();
        for (int i = 0; i < count; i++) {
            final RouteInfo route = routes.get(i);
            if (route.id.equals(id)) {
                return route;
            }
        }
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(routes);
        dest.writeString(globallySelectedRouteId);
    }

    @Override
    public String toString() {
        return "MediaRouterClientState{ globallySelectedRouteId="
                + globallySelectedRouteId + ", routes=" + routes.toString() + " }";
    }

    public static final Parcelable.Creator<MediaRouterClientState> CREATOR =
            new Parcelable.Creator<MediaRouterClientState>() {
        @Override
        public MediaRouterClientState createFromParcel(Parcel in) {
            return new MediaRouterClientState(in);
        }

        @Override
        public MediaRouterClientState[] newArray(int size) {
            return new MediaRouterClientState[size];
        }
    };

    public static final class RouteInfo implements Parcelable {
        public String id;
        public String name;
        public String description;
        public int supportedTypes;
        public boolean enabled;
        public int statusCode;
        public int playbackType;
        public int playbackStream;
        public int volume;
        public int volumeMax;
        public int volumeHandling;
        public int presentationDisplayId;
        public @MediaRouter.RouteInfo.DeviceType int deviceType;

        public RouteInfo(String id) {
            this.id = id;
            enabled = true;
            statusCode = MediaRouter.RouteInfo.STATUS_NONE;
            playbackType = MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE;
            playbackStream = -1;
            volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED;
            presentationDisplayId = -1;
            deviceType = MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN;
        }

        public RouteInfo(RouteInfo other) {
            id = other.id;
            name = other.name;
            description = other.description;
            supportedTypes = other.supportedTypes;
            enabled = other.enabled;
            statusCode = other.statusCode;
            playbackType = other.playbackType;
            playbackStream = other.playbackStream;
            volume = other.volume;
            volumeMax = other.volumeMax;
            volumeHandling = other.volumeHandling;
            presentationDisplayId = other.presentationDisplayId;
            deviceType = other.deviceType;
        }

        RouteInfo(Parcel in) {
            id = in.readString();
            name = in.readString();
            description = in.readString();
            supportedTypes = in.readInt();
            enabled = in.readInt() != 0;
            statusCode = in.readInt();
            playbackType = in.readInt();
            playbackStream = in.readInt();
            volume = in.readInt();
            volumeMax = in.readInt();
            volumeHandling = in.readInt();
            presentationDisplayId = in.readInt();
            deviceType = in.readInt();
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
            dest.writeInt(supportedTypes);
            dest.writeInt(enabled ? 1 : 0);
            dest.writeInt(statusCode);
            dest.writeInt(playbackType);
            dest.writeInt(playbackStream);
            dest.writeInt(volume);
            dest.writeInt(volumeMax);
            dest.writeInt(volumeHandling);
            dest.writeInt(presentationDisplayId);
            dest.writeInt(deviceType);
        }

        @Override
        public String toString() {
            return "RouteInfo{ id=" + id
                    + ", name=" + name
                    + ", description=" + description
                    + ", supportedTypes=0x" + Integer.toHexString(supportedTypes)
                    + ", enabled=" + enabled
                    + ", statusCode=" + statusCode
                    + ", playbackType=" + playbackType
                    + ", playbackStream=" + playbackStream
                    + ", volume=" + volume
                    + ", volumeMax=" + volumeMax
                    + ", volumeHandling=" + volumeHandling
                    + ", presentationDisplayId=" + presentationDisplayId
                    + ", deviceType=" + deviceType
                    + " }";
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<RouteInfo> CREATOR =
                new Parcelable.Creator<RouteInfo>() {
            @Override
            public RouteInfo createFromParcel(Parcel in) {
                return new RouteInfo(in);
            }

            @Override
            public RouteInfo[] newArray(int size) {
                return new RouteInfo[size];
            }
        };
    }
}
