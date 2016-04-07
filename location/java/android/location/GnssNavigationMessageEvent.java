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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * A class implementing a container for data associated with a navigation message event.
 * Events are delivered to registered instances of {@link Callback}.
 * @removed
 */
public final class GnssNavigationMessageEvent implements Parcelable {
    /**
     * The status of GNSS measurements event.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_GNSS_LOCATION_DISABLED})
    public @interface GnssNavigationMessageStatus {}

    /**
     * The system does not support tracking of GNSS Navigation Messages.
     *
     * This status will not change in the future.
     */
    public static final int STATUS_NOT_SUPPORTED = 0;

    /**
     * GNSS Navigation Messages are successfully being tracked, it will receive updates once they
     * are available.
     */
    public static final int STATUS_READY = 1;

    /**
     * GNSS provider or Location is disabled, updated will not be received until they are enabled.
     */
    public static final int STATUS_GNSS_LOCATION_DISABLED = 2;

    private final GnssNavigationMessage mNavigationMessage;

    /**
     * Used for receiving GNSS satellite Navigation Messages from the GNSS engine.
     *
     * <p>You can implement this interface and call
     * {@link LocationManager#registerGnssNavigationMessageCallback}.
     */
    public static abstract class Callback {

        /**
         * Returns the latest collected GNSS Navigation Message.
         */
        public void onGnssNavigationMessageReceived(GnssNavigationMessageEvent event) {}

        /**
         * Returns the latest status of the GNSS Navigation Messages sub-system.
         */
        public void onStatusChanged(@GnssNavigationMessageStatus int status) {}
    }

    public GnssNavigationMessageEvent(GnssNavigationMessage message) {
        if (message == null) {
            throw new InvalidParameterException("Parameter 'message' must not be null.");
        }
        mNavigationMessage = message;
    }

    @NonNull
    public GnssNavigationMessage getNavigationMessage() {
        return mNavigationMessage;
    }

    public static final Creator<GnssNavigationMessageEvent> CREATOR =
            new Creator<GnssNavigationMessageEvent>() {
                @Override
                public GnssNavigationMessageEvent createFromParcel(Parcel in) {
                    ClassLoader classLoader = getClass().getClassLoader();
                    GnssNavigationMessage navigationMessage = in.readParcelable(classLoader);
                    return new GnssNavigationMessageEvent(navigationMessage);
                }

                @Override
                public GnssNavigationMessageEvent[] newArray(int size) {
                    return new GnssNavigationMessageEvent[size];
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

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GnssNavigationMessageEvent:\n\n");
        builder.append(mNavigationMessage.toString());
        builder.append("\n]");
        return builder.toString();
    }
}
