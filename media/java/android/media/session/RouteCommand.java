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
package android.media.session;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a command that an application may send to a route.
 * <p>
 * Commands are associated with a specific route and interface supported by that
 * route and sent through the session. This class isn't used directly by apps.
 *
 * @hide
 */
public final class RouteCommand implements Parcelable {
    private final String mRoute;
    private final String mIface;
    private final String mEvent;
    private final Bundle mExtras;

    /**
     * @param route The id of the route this event is being sent on
     * @param iface The interface the sender used
     * @param event The event or command
     * @param extras Any extras included with the event
     */
    public RouteCommand(String route, String iface, String event, Bundle extras) {
        mRoute = route;
        mIface = iface;
        mEvent = event;
        mExtras = extras;
    }

    private RouteCommand(Parcel in) {
        mRoute = in.readString();
        mIface = in.readString();
        mEvent = in.readString();
        mExtras = in.readBundle();
    }

    /**
     * Get the id for the route this event was sent on.
     *
     * @return The route id this event is using
     */
    public String getRouteInfo() {
        return mRoute;
    }

    /**
     * Get the interface this event was sent from
     *
     * @return The interface for this event
     */
    public String getIface() {
        return mIface;
    }

    /**
     * Get the action/name of the event.
     *
     * @return The name of event/command.
     */
    public String getEvent() {
        return mEvent;
    }

    /**
     * Get any extras included with the event.
     *
     * @return The bundle included with the event or null
     */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mRoute);
        dest.writeString(mIface);
        dest.writeString(mEvent);
        dest.writeBundle(mExtras);
    }

    public static final Parcelable.Creator<RouteCommand> CREATOR
            = new Parcelable.Creator<RouteCommand>() {
        @Override
        public RouteCommand createFromParcel(Parcel in) {
            return new RouteCommand(in);
        }

        @Override
        public RouteCommand[] newArray(int size) {
            return new RouteCommand[size];
        }
    };
}
