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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 *  Encapsulates the telecom audio state, including the current audio routing, supported audio
 *  routing and mute.
 */
public final class CallAudioState implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value={ROUTE_EARPIECE, ROUTE_BLUETOOTH, ROUTE_WIRED_HEADSET, ROUTE_SPEAKER},
            flag=true)
    public @interface CallAudioRoute {}

    /** Direct the audio stream through the device's earpiece. */
    public static final int ROUTE_EARPIECE      = 0x00000001;

    /** Direct the audio stream through Bluetooth. */
    public static final int ROUTE_BLUETOOTH     = 0x00000002;

    /** Direct the audio stream through a wired headset. */
    public static final int ROUTE_WIRED_HEADSET = 0x00000004;

    /** Direct the audio stream through the device's speakerphone. */
    public static final int ROUTE_SPEAKER       = 0x00000008;

    /** Direct the audio stream through another device. */
    public static final int ROUTE_STREAMING     = 0x00000010;

    /**
     * Direct the audio stream through the device's earpiece or wired headset if one is
     * connected.
     */
    public static final int ROUTE_WIRED_OR_EARPIECE = ROUTE_EARPIECE | ROUTE_WIRED_HEADSET;

    /**
     * Bit mask of all possible audio routes.
     *
     * @hide
     **/
    public static final int ROUTE_ALL = ROUTE_EARPIECE | ROUTE_BLUETOOTH | ROUTE_WIRED_HEADSET |
            ROUTE_SPEAKER | ROUTE_STREAMING;

    private final boolean isMuted;
    private final int route;
    private final int supportedRouteMask;
    private final BluetoothDevice activeBluetoothDevice;
    private final Collection<BluetoothDevice> supportedBluetoothDevices;

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
    public CallAudioState(boolean muted, @CallAudioRoute int route,
            @CallAudioRoute int supportedRouteMask) {
        this(muted, route, supportedRouteMask, null, Collections.emptyList());
    }

    /** @hide */
    @TestApi
    public CallAudioState(boolean isMuted, @CallAudioRoute int route,
            @CallAudioRoute int supportedRouteMask,
            @Nullable BluetoothDevice activeBluetoothDevice,
            @NonNull Collection<BluetoothDevice> supportedBluetoothDevices) {
        this.isMuted = isMuted;
        this.route = route;
        this.supportedRouteMask = supportedRouteMask;
        this.activeBluetoothDevice = activeBluetoothDevice;
        this.supportedBluetoothDevices = supportedBluetoothDevices;
    }

    /** @hide */
    public CallAudioState(CallAudioState state) {
        isMuted = state.isMuted();
        route = state.getRoute();
        supportedRouteMask = state.getSupportedRouteMask();
        activeBluetoothDevice = state.activeBluetoothDevice;
        supportedBluetoothDevices = state.getSupportedBluetoothDevices();
    }

    /** @hide */
    @SuppressWarnings("deprecation")
    public CallAudioState(AudioState state) {
        isMuted = state.isMuted();
        route = state.getRoute();
        supportedRouteMask = state.getSupportedRouteMask();
        activeBluetoothDevice = null;
        supportedBluetoothDevices = Collections.emptyList();
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
        if (supportedBluetoothDevices.size() != state.supportedBluetoothDevices.size()) {
            return false;
        }
        for (BluetoothDevice device : supportedBluetoothDevices) {
            if (!state.supportedBluetoothDevices.contains(device)) {
                return false;
            }
        }
        return Objects.equals(activeBluetoothDevice, state.activeBluetoothDevice) && isMuted() ==
                state.isMuted() && getRoute() == state.getRoute() && getSupportedRouteMask() ==
                state.getSupportedRouteMask();
    }

    @Override
    public String toString() {
        String bluetoothDeviceList = supportedBluetoothDevices.stream()
                .map(BluetoothDevice::toString).collect(Collectors.joining(", "));

        return String.format(Locale.US,
                "[AudioState isMuted: %b, route: %s, supportedRouteMask: %s, " +
                        "activeBluetoothDevice: [%s], supportedBluetoothDevices: [%s]]",
                isMuted,
                audioRouteToString(route),
                audioRouteToString(supportedRouteMask),
                activeBluetoothDevice,
                bluetoothDeviceList);
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
    @CallAudioRoute
    public int getRoute() {
        return route;
    }

    /**
     * @return Bit mask of all routes supported by this call.
     */
    @CallAudioRoute
    public int getSupportedRouteMask() {
        if (route == ROUTE_STREAMING) {
            return ROUTE_STREAMING;
        } else {
            return supportedRouteMask;
        }
    }

    /**
     * @return Bit mask of all routes supported by this call, won't be changed by streaming state.
     *
     * @hide
     */
    @CallAudioRoute
    public int getRawSupportedRouteMask() {
        return supportedRouteMask;
    }

    /**
     * @return The {@link BluetoothDevice} through which audio is being routed.
     *         Will not be {@code null} if {@link #getRoute()} returns {@link #ROUTE_BLUETOOTH}.
     */
    public BluetoothDevice getActiveBluetoothDevice() {
        return activeBluetoothDevice;
    }

    /**
     * @return {@link List} of {@link BluetoothDevice}s that can be used for this call.
     */
    public Collection<BluetoothDevice> getSupportedBluetoothDevices() {
        return supportedBluetoothDevices;
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
        if ((route & ROUTE_STREAMING) == ROUTE_STREAMING) {
            listAppend(buffer, "STREAMING");
        }

        return buffer.toString();
    }

    /**
     * Responsible for creating AudioState objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<CallAudioState> CREATOR =
            new Parcelable.Creator<CallAudioState> () {

        @Override
        public CallAudioState createFromParcel(Parcel source) {
            boolean isMuted = source.readByte() == 0 ? false : true;
            int route = source.readInt();
            int supportedRouteMask = source.readInt();
            BluetoothDevice activeBluetoothDevice = source.readParcelable(
                    ClassLoader.getSystemClassLoader(), android.bluetooth.BluetoothDevice.class);
            List<BluetoothDevice> supportedBluetoothDevices = new ArrayList<>();
            source.readParcelableList(supportedBluetoothDevices,
                    ClassLoader.getSystemClassLoader(), android.bluetooth.BluetoothDevice.class);
            return new CallAudioState(isMuted, route,
                    supportedRouteMask, activeBluetoothDevice, supportedBluetoothDevices);
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
        destination.writeParcelable(activeBluetoothDevice, 0);
        destination.writeParcelableList(new ArrayList<>(supportedBluetoothDevices), 0);
    }

    private static void listAppend(StringBuffer buffer, String str) {
        if (buffer.length() > 0) {
            buffer.append(", ");
        }
        buffer.append(str);
    }
}
