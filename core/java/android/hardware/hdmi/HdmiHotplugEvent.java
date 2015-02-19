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

package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that describes the HDMI port hotplug event.
 *
 * @hide
 */
@SystemApi
public final class HdmiHotplugEvent implements Parcelable {

    private final int mPort;
    private final boolean mConnected;

    /**
     * Constructor.
     *
     * <p>Marked as hidden so only system can create the instance.
     *
     * @hide
     */
    public HdmiHotplugEvent(int port, boolean connected) {
        mPort = port;
        mConnected = connected;
    }

    /**
     * Returns the port number for which the event occurred.
     *
     * @return port number
     */
    public int getPort() {
        return mPort;
    }

    /**
     * Returns the connection status associated with this event
     *
     * @return true if the device gets connected; otherwise false
     */
    public boolean isConnected() {
        return mConnected;
    }

    /**
     * Describes the kinds of special objects contained in this Parcelable's
     * marshalled representation.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flattens this object in to a Parcel.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *        May be 0 or {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPort);
        dest.writeByte((byte) (mConnected ? 1 : 0));
    }

    public static final Parcelable.Creator<HdmiHotplugEvent> CREATOR
            = new Parcelable.Creator<HdmiHotplugEvent>() {
        /**
         * Rebuilds a {@link HdmiHotplugEvent} previously stored with
         * {@link Parcelable#writeToParcel(Parcel, int)}.
         *
         * @param p {@link HdmiHotplugEvent} object to read the Rating from
         * @return a new {@link HdmiHotplugEvent} created from the data in the parcel
         */
        @Override
        public HdmiHotplugEvent createFromParcel(Parcel p) {
            int port = p.readInt();
            boolean connected = p.readByte() == 1;
            return new HdmiHotplugEvent(port, connected);
        }
        @Override
        public HdmiHotplugEvent[] newArray(int size) {
            return new HdmiHotplugEvent[size];
        }
    };
}
