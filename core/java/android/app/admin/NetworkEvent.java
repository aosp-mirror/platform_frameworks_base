/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.admin;

import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFormatException;

/**
 * An abstract class that represents a network event.
 */
public abstract class NetworkEvent implements Parcelable {

    /** @hide */
    static final int PARCEL_TOKEN_DNS_EVENT = 1;
    /** @hide */
    static final int PARCEL_TOKEN_CONNECT_EVENT = 2;

    /** The package name of the UID that performed the query. */
    String packageName;

    /** The timestamp of the event being reported in milliseconds. */
    long timestamp;

    /** @hide */
    NetworkEvent() {
        //empty constructor
    }

    /** @hide */
    NetworkEvent(String packageName, long timestamp) {
        this.packageName = packageName;
        this.timestamp = timestamp;
    }

    /**
     * Returns the package name of the UID that performed the query, as returned by
     * {@link PackageManager#getNameForUid}.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the timestamp of the event being reported in milliseconds, the difference between
     * the time the event was reported and midnight, January 1, 1970 UTC.
     */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<NetworkEvent> CREATOR
            = new Parcelable.Creator<NetworkEvent>() {
        public NetworkEvent createFromParcel(Parcel in) {
            final int initialPosition = in.dataPosition();
            final int parcelToken = in.readInt();
            // we need to move back to the position from before we read parcelToken
            in.setDataPosition(initialPosition);
            switch (parcelToken) {
                case PARCEL_TOKEN_DNS_EVENT:
                    return DnsEvent.CREATOR.createFromParcel(in);
                case PARCEL_TOKEN_CONNECT_EVENT:
                    return ConnectEvent.CREATOR.createFromParcel(in);
                default:
                    throw new ParcelFormatException("Unexpected NetworkEvent token in parcel: "
                            + parcelToken);
            }
        }

        public NetworkEvent[] newArray(int size) {
            return new NetworkEvent[size];
        }
    };

    @Override
    public abstract void writeToParcel(Parcel out, int flags);
}

