/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.annotation.SystemApi;
import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that represents an event for each change in the state of a monitoring system.
 *
 * @hide
 */
@SystemApi
public class GeofenceHardwareMonitorEvent implements Parcelable {
    private final int mMonitoringType;
    private final int mMonitoringStatus;
    private final int mSourceTechnologies;
    private final Location mLocation;

    public GeofenceHardwareMonitorEvent(
            int monitoringType,
            int monitoringStatus,
            int sourceTechnologies,
            Location location) {
        mMonitoringType = monitoringType;
        mMonitoringStatus = monitoringStatus;
        mSourceTechnologies = sourceTechnologies;
        mLocation = location;
    }

    /**
     * Returns the type of the monitoring system that has a change on its state.
     */
    public int getMonitoringType() {
        return mMonitoringType;
    }

    /**
     * Returns the new status associated with the monitoring system.
     */
    public int getMonitoringStatus() {
        return mMonitoringStatus;
    }

    /**
     * Returns the source technologies that the status is associated to.
     */
    public int getSourceTechnologies() {
        return mSourceTechnologies;
    }

    /**
     * Returns the last known location according to the monitoring system.
     */
    public Location getLocation() {
        return mLocation;
    }

    public static final Creator<GeofenceHardwareMonitorEvent> CREATOR =
            new Creator<GeofenceHardwareMonitorEvent>() {
                @Override
                public GeofenceHardwareMonitorEvent createFromParcel(Parcel source) {
                    ClassLoader classLoader = GeofenceHardwareMonitorEvent.class.getClassLoader();
                    int monitoringType = source.readInt();
                    int monitoringStatus = source.readInt();
                    int sourceTechnologies = source.readInt();
                    Location location = source.readParcelable(classLoader);

                    return new GeofenceHardwareMonitorEvent(
                            monitoringType,
                            monitoringStatus,
                            sourceTechnologies,
                            location);
                }

                @Override
                public GeofenceHardwareMonitorEvent[] newArray(int size) {
                    return new GeofenceHardwareMonitorEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mMonitoringType);
        parcel.writeInt(mMonitoringStatus);
        parcel.writeInt(mSourceTechnologies);
        parcel.writeParcelable(mLocation, flags);
    }

    @Override
    public String toString() {
        return String.format(
                "GeofenceHardwareMonitorEvent: type=%d, status=%d, sources=%d, location=%s",
                mMonitoringType,
                mMonitoringStatus,
                mSourceTechnologies,
                mLocation);
    }
}
