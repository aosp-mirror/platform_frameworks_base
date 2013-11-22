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

package android.hardware.location;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Geofence Hardware Request used for internal location services communication.
 *
 * @hide
 */
public final class GeofenceHardwareRequestParcelable implements Parcelable {
    private GeofenceHardwareRequest mRequest;
    private int mId;

    public GeofenceHardwareRequestParcelable(int id, GeofenceHardwareRequest request) {
        mId = id;
        mRequest = request;
    }

    /**
     * Returns the id of this request.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the latitude of this geofence.
     */
    public double getLatitude() {
        return mRequest.getLatitude();
    }

    /**
     * Returns the longitude of this geofence.
     */
    public double getLongitude() {
        return mRequest.getLongitude();
    }

    /**
     * Returns the radius of this geofence.
     */
    public double getRadius() {
        return mRequest.getRadius();
    }

    /**
     * Returns transitions monitored for this geofence.
     */
    public int getMonitorTransitions() {
        return mRequest.getMonitorTransitions();
    }

    /**
     * Returns the unknownTimer of this geofence.
     */
    public int getUnknownTimer() {
        return mRequest.getUnknownTimer();
    }

    /**
     * Returns the notification responsiveness of this geofence.
     */
    public int getNotificationResponsiveness() {
        return mRequest.getNotificationResponsiveness();
    }

    /**
     * Returns the last transition of this geofence.
     */
    public int getLastTransition() {
        return mRequest.getLastTransition();
    }

    /**
     * Returns the type of the geofence for the current request.
     */
    int getType() {
        return mRequest.getType();
    }

    /**
     * Method definitions to support Parcelable operations.
     */
    public static final Parcelable.Creator<GeofenceHardwareRequestParcelable> CREATOR = 
            new Parcelable.Creator<GeofenceHardwareRequestParcelable>() {
        @Override
        public GeofenceHardwareRequestParcelable createFromParcel(Parcel parcel) {
            int geofenceType = parcel.readInt();
            if(geofenceType != GeofenceHardwareRequest.GEOFENCE_TYPE_CIRCLE) {
                Log.e(
                        "GeofenceHardwareRequest",
                        String.format("Invalid Geofence type: %d", geofenceType));
                return null;
            }

            GeofenceHardwareRequest request = GeofenceHardwareRequest.createCircularGeofence(
                    parcel.readDouble(),
                    parcel.readDouble(),
                    parcel.readDouble());
            request.setLastTransition(parcel.readInt());
            request.setMonitorTransitions(parcel.readInt());
            request.setUnknownTimer(parcel.readInt());
            request.setNotificationResponsiveness(parcel.readInt());
            
            int id = parcel.readInt();
            return new GeofenceHardwareRequestParcelable(id, request);
        }

        @Override
        public GeofenceHardwareRequestParcelable[] newArray(int size) {
            return new GeofenceHardwareRequestParcelable[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(getType());
        parcel.writeDouble(getLatitude());
        parcel.writeDouble(getLongitude());
        parcel.writeDouble(getRadius());
        parcel.writeInt(getLastTransition());
        parcel.writeInt(getMonitorTransitions());
        parcel.writeInt(getUnknownTimer());
        parcel.writeInt(getNotificationResponsiveness());
        parcel.writeInt(getId());
    }
}
