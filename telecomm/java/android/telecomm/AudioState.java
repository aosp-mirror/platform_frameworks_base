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

package android.telecomm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/**
 *  Encapsulates all audio states during a call.
 */
public final class AudioState implements Parcelable {
    /** Direct the audio stream through the device's earpiece. */
    public static int ROUTE_EARPIECE      = 0x00000001;

    /** Direct the audio stream through Bluetooth. */
    public static int ROUTE_BLUETOOTH     = 0x00000002;

    /** Direct the audio stream through a wired headset. */
    public static int ROUTE_WIRED_HEADSET = 0x00000004;

    /** Direct the audio stream through the device's spakerphone. */
    public static int ROUTE_SPEAKER       = 0x00000008;

    /**
     * Direct the audio stream through the device's earpiece or wired headset if one is
     * connected.
     */
    public static int ROUTE_WIRED_OR_EARPIECE = ROUTE_EARPIECE | ROUTE_WIRED_HEADSET;

    /** Bit mask of all possible audio routes. */
    public static int ROUTE_ALL = ROUTE_EARPIECE | ROUTE_BLUETOOTH | ROUTE_WIRED_HEADSET |
            ROUTE_SPEAKER;

    /** True if the call is muted, false otherwise. */
    public final boolean isMuted;

    /** The route to use for the audio stream. */
    public final int route;

    /** Bit vector of all routes supported by this call. */
    public final int supportedRouteMask;

    public AudioState(boolean isMuted, int route, int supportedRouteMask) {
        this.isMuted = isMuted;
        this.route = route;
        this.supportedRouteMask = supportedRouteMask;
    }

    public AudioState(AudioState state) {
        isMuted = state.isMuted;
        route = state.route;
        supportedRouteMask = state.supportedRouteMask;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof AudioState)) {
            return false;
        }
        AudioState state = (AudioState) obj;
        return isMuted == state.isMuted && route == state.route &&
                supportedRouteMask == state.supportedRouteMask;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "[AudioState isMuted: %b, route; %s, supportedRouteMask: %s]",
                isMuted, audioRouteToString(route), audioRouteToString(supportedRouteMask));
    }

    /** @hide */
    public static String audioRouteToString(int route) {
        if (route == 0 || (route & ~ROUTE_ALL) != 0x0) {
            return "UNKNOWN";
        }

        StringBuffer buffer = new StringBuffer();
        if ((route & ROUTE_EARPIECE) == ROUTE_EARPIECE) {
            listAppend(buffer, "EARPIECE");
        }
        if ((route & ROUTE_BLUETOOTH) == ROUTE_BLUETOOTH) {
            listAppend(buffer, "BLUETOOTH");
        }
        if ((route & ROUTE_WIRED_HEADSET) == ROUTE_WIRED_HEADSET) {
            listAppend(buffer, "WIRED_HEADSET");
        }
        if ((route & ROUTE_SPEAKER) == ROUTE_SPEAKER) {
            listAppend(buffer, "SPEAKER");
        }

        return buffer.toString();
    }

    private static void listAppend(StringBuffer buffer, String str) {
        if (buffer.length() > 0) {
            buffer.append(", ");
        }
        buffer.append(str);
    }

    /**
     * Responsible for creating AudioState objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<AudioState> CREATOR =
            new Parcelable.Creator<AudioState> () {

        @Override
        public AudioState createFromParcel(Parcel source) {
            boolean isMuted = source.readByte() == 0 ? false : true;
            int route = source.readInt();
            int supportedRouteMask = source.readInt();
            return new AudioState(isMuted, route, supportedRouteMask);
        }

        @Override
        public AudioState[] newArray(int size) {
            return new AudioState[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes AudioState object into a serializeable Parcel.
     */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeByte((byte) (isMuted ? 1 : 0));
        destination.writeInt(route);
        destination.writeInt(supportedRouteMask);
    }
}
