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
package android.media.routeprovider;

import android.media.session.RouteOptions;
import android.media.session.MediaSessionInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

/**
 * A request to connect or discover routes with certain capabilities.
 * <p>
 * Passed to a {@link RouteProviderService} when a request for discovery or to
 * connect to a route is made. This identifies the app making the request and
 * provides the full set of connection parameters they would like to use for a
 * connection. An app that can connect in multiple ways will be represented by
 * multiple requests.
 * @hide
 */
public final class RouteRequest implements Parcelable {
    private final MediaSessionInfo mSessionInfo;
    private final RouteOptions mOptions;
    private final boolean mActive;

    /**
     * @hide
     */
    public RouteRequest(MediaSessionInfo info, RouteOptions connRequest,
            boolean active) {
        mSessionInfo = info;
        mOptions = connRequest;
        mActive = active;
    }

    private RouteRequest(Parcel in) {
        mSessionInfo = MediaSessionInfo.CREATOR.createFromParcel(in);
        mOptions = RouteOptions.CREATOR.createFromParcel(in);
        mActive = in.readInt() != 0;
    }

    /**
     * Get information about the session making the request.
     *
     * @return Info on the session making the request
     */
    public MediaSessionInfo getSessionInfo() {
        return mSessionInfo;
    }

    /**
     * Get the connection options, which includes the interfaces and other
     * connection params the session wants to use with a route.
     *
     * @return The connection options
     */
    public RouteOptions getConnectionOptions() {
        return mOptions;
    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder();
        bob.append("RouteRequest {");
        bob.append("active=").append(mActive);
        bob.append(", info=").append(mSessionInfo.toString());
        bob.append(", options=").append(mOptions.toString());
        bob.append("}");
        return bob.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mSessionInfo.writeToParcel(dest, flags);
        mOptions.writeToParcel(dest, flags);
        dest.writeInt(mActive ? 1 : 0);
    }

    public static final Parcelable.Creator<RouteRequest> CREATOR
            = new Parcelable.Creator<RouteRequest>() {
        @Override
        public RouteRequest createFromParcel(Parcel in) {
            return new RouteRequest(in);
        }

        @Override
        public RouteRequest[] newArray(int size) {
            return new RouteRequest[size];
        }
    };
}
