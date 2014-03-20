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

import android.media.routeprovider.RouteConnection;
import android.media.routeprovider.RouteProviderService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an event that a route provider is sending to a particular
 * {@link RouteConnection}. Events are associated with a specific interface
 * supported by the connection and sent through the {@link RouteProviderService}.
 * This class isn't used directly by apps.
 *
 * @hide
 */
public class RouteEvent implements Parcelable {
    private final IBinder mConnection;
    private final String mIface;
    private final String mEvent;
    private final Bundle mExtras;

    /**
     * @param connection The connection that this event is for
     * @param iface The interface the sender used
     * @param event The event or command
     * @param extras Any extras included with the event
     */
    public RouteEvent(IBinder connection, String iface, String event, Bundle extras) {
        mConnection = connection;
        mIface = iface;
        mEvent = event;
        mExtras = extras;
    }

    private RouteEvent(Parcel in) {
        mConnection = in.readStrongBinder();
        mIface = in.readString();
        mEvent = in.readString();
        mExtras = in.readBundle();
    }

    /**
     * Get the connection this event was sent on.
     *
     * @return The connection this event is using
     */
    public IBinder getConnection() {
        return mConnection;
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
        dest.writeStrongBinder(mConnection);
        dest.writeString(mIface);
        dest.writeString(mEvent);
        dest.writeBundle(mExtras);
    }

    public static final Parcelable.Creator<RouteEvent> CREATOR
            = new Parcelable.Creator<RouteEvent>() {
        @Override
        public RouteEvent createFromParcel(Parcel in) {
            return new RouteEvent(in);
        }

        @Override
        public RouteEvent[] newArray(int size) {
            return new RouteEvent[size];
        }
    };
}
