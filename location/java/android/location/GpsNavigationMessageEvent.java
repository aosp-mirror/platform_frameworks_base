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
 * limitations under the License
 */

package android.location;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.InvalidParameterException;

/**
 * A class implementing a container for data associated with a navigation message event.
 * Events are delivered to registered instances of {@link Listener}.
 *
 * @deprecated use {@link GnssNavigationMessage} instead.
 *
 * @hide
 */
@Deprecated
@SystemApi
public class GpsNavigationMessageEvent implements Parcelable {

    /**
     * The system does not support tracking of GPS Navigation Messages. This status will not change
     * in the future.
     */
    public static int STATUS_NOT_SUPPORTED = 0;

    /**
     * GPS Navigation Messages are successfully being tracked, it will receive updates once they are
     * available.
     */
    public static int STATUS_READY = 1;

    /**
     * GPS provider or Location is disabled, updated will not be received until they are enabled.
     */
    public static int STATUS_GPS_LOCATION_DISABLED = 2;

    private final GpsNavigationMessage mNavigationMessage;

    /**
     * Used for receiving GPS satellite Navigation Messages from the GPS engine.
     * You can implement this interface and call
     * {@link LocationManager#addGpsNavigationMessageListener}.
     *
     * @hide
     */
    @SystemApi
    public interface Listener {

        /**
         * Returns the latest collected GPS Navigation Message.
         */
        void onGpsNavigationMessageReceived(GpsNavigationMessageEvent event);

        /**
         * Returns the latest status of the GPS Navigation Messages sub-system.
         */
        void onStatusChanged(int status);
    }

    public GpsNavigationMessageEvent(GpsNavigationMessage message) {
        if (message == null) {
            throw new InvalidParameterException("Parameter 'message' must not be null.");
        }
        mNavigationMessage = message;
    }

    @NonNull
    public GpsNavigationMessage getNavigationMessage() {
        return mNavigationMessage;
    }

    public static final @android.annotation.NonNull Creator<GpsNavigationMessageEvent> CREATOR =
            new Creator<GpsNavigationMessageEvent>() {
                @Override
                public GpsNavigationMessageEvent createFromParcel(Parcel in) {
                    ClassLoader classLoader = getClass().getClassLoader();
                    GpsNavigationMessage navigationMessage = in.readParcelable(classLoader, android.location.GpsNavigationMessage.class);
                    return new GpsNavigationMessageEvent(navigationMessage);
                }

                @Override
                public GpsNavigationMessageEvent[] newArray(int size) {
                    return new GpsNavigationMessageEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mNavigationMessage, flags);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GpsNavigationMessageEvent:\n\n");
        builder.append(mNavigationMessage.toString());
        builder.append("\n]");
        return builder.toString();
    }
}
