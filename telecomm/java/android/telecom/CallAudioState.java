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

package android.telecom;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/**
 *  Encapsulates the telecom audio state, including the current audio routing, supported audio
 *  routing and mute.
 */
public final class CallAudioState implements Parcelable {
    /** Direct the audio stream through the device's earpiece. */
    public static final int ROUTE_EARPIECE      = 0x00000001;

    /** Direct the audio stream through Bluetooth. */
    public static final int ROUTE_BLUETOOTH     = 0x00000002;

    /** Direct the audio stream through a wired headset. */
    public static final int ROUTE_WIRED_HEADSET = 0x00000004;

    /** Direct the audio stream through the device's speakerphone. */
    public static final int ROUTE_SPEAKER       = 0x00000008;

    /**
     * Direct the audio stream through the device's earpiece or wired headset if one is
     * connected.
     */
    public static final int ROUTE_WIRED_OR_EARPIECE = ROUTE_EARPIECE | ROUTE_WIRED_HEADSET;

    /** Bit mask of all possible audio routes. */
    private static final int ROUTE_ALL = ROUTE_EARPIECE | ROUTE_BLUETOOTH | ROUTE_WIRED_HEADSET |
            ROUTE_SPEAKER;

    private final boolean isMuted;
    private final int route;
    private final int supportedRouteMask;

    /**
     * Constructor for a {@link CallAudioState} object.
     *
     * @param muted {@code true} if the call is muted, {@code false} otherwise.
     * @param route The current audio route being used.
     * Allowed values:
     * {@link #ROUTE_EARPIECE}
     * {@link #ROUTE_BLUETOOTH}
     * {@link #ROUTE_WIRED_HEADSET}
     * {@link #ROUTE_SPEAKER}
     * @param supportedRouteMask Bit mask of all routes supported by this call. This should be a
     * bitwise combination of the following values:
     * {@link #ROUTE_EARPIECE}
     * {@link #ROUTE_BLUETOOTH}
     * {@link #ROUTE_WIRED_HEADSET}
     * {@link #ROUTE_SPEAKER}
     */
    public CallAudioState(boolean muted, int route, int supportedRouteMask) {
        this.isMuted = muted;
        this.route = route;
        this.supportedRouteMask = supportedRouteMask;
    }

    /** @hide */
    public CallAudioState(CallAudioState state) {
        isMuted = state.isMuted();
        route = state.getRoute();
        supportedRouteMask = state.getSupportedRouteMask();
    }

    /** @hide */
    @SuppressWarnings("deprecation")
    public CallAudioState(AudioState state) {
        isMuted = state.isMuted();
        route = state.getRoute();
        supportedRouteMask = state.getSupportedRouteMask();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CallAudioState)) {
            return false;
        }
        CallAudioState state = (CallAudioState) obj;
        return isMuted() == state.isMuted() && getRoute() == state.getRoute() &&
                getSupportedRouteMask() == state.getSupportedRouteMask();
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "[AudioState isMuted: %b, route: %s, supportedRouteMask: %s]",
                isMuted,
                audioRouteToString(route),
                audioRouteToString(supportedRouteMask));
    }

    /**
     * @return {@code true} if the call is muted, {@code false} otherwise.
     */
    public boolean isMuted() {
        return isMuted;
    }

    /**
     * @return The current audio route being used.
     */
    public int getRoute() {
        return route;
    }

    /**
     * @return Bit mask of all routes supported by this call.
     */
    public int getSupportedRouteMask() {
        return supportedRouteMask;
    }

    /**
     * Converts the provided audio route into a human readable string representation.
     *
     * @param route to convert into a string.
     *
     * @return String representation of the provided audio route.
     */
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

    /**
     * Responsible for creating AudioState objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<CallAudioState> CREATOR =
            new Parcelable.Creator<CallAudioState> () {

        @Override
        public CallAudioState createFromParcel(Parcel source) {
            boolean isMuted = source.readByte() == 0 ? false : true;
            int route = source.readInt();
            int supportedRouteMask = source.readInt();
            return new CallAudioState(isMuted, route, supportedRouteMask);
        }

        @Override
        public CallAudioState[] newArray(int size) {
            return new CallAudioState[size];
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

    private static void listAppend(StringBuffer buffer, String str) {
        if (buffer.length() > 0) {
            buffer.append(", ");
        }
        buffer.append(str);
    }
}
