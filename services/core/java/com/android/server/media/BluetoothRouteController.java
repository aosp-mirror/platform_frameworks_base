/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.os.UserHandle;

import com.android.media.flags.Flags;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provides control over bluetooth routes.
 */
/* package */ interface BluetoothRouteController {

    /**
     * Returns a new instance of {@link LegacyBluetoothRouteController}.
     *
     * <p>It may return {@link NoOpBluetoothRouteController} if Bluetooth is not supported on this
     * hardware platform.
     */
    @NonNull
    static BluetoothRouteController createInstance(@NonNull Context context,
            @NonNull BluetoothRouteController.BluetoothRoutesUpdatedListener listener) {
        Objects.requireNonNull(listener);
        BluetoothAdapter btAdapter = context.getSystemService(BluetoothManager.class).getAdapter();

        if (btAdapter == null || Flags.enableAudioPoliciesDeviceAndBluetoothController()) {
            return new NoOpBluetoothRouteController();
        } else {
            return new LegacyBluetoothRouteController(context, btAdapter, listener);
        }
    }

    /**
     * Makes the controller to listen to events from Bluetooth stack.
     *
     * @param userHandle is needed to subscribe for broadcasts on user's behalf.
     */
    void start(@NonNull UserHandle userHandle);

    /**
     * Stops the controller from listening to any Bluetooth events.
     */
    void stop();

    /**
     * Transfers Bluetooth output to the given route.
     *
     * <p>If the route is {@code null} then active route will be deactivated.
     *
     * @param routeId to switch to or {@code null} to unset the active device.
     */
    void transferTo(@Nullable String routeId);

    /**
     * Returns currently selected Bluetooth route.
     *
     * @return the selected route or {@code null} if there are no active routes.
     */
    @Nullable
    MediaRoute2Info getSelectedRoute();

    /**
     * Returns transferable routes.
     *
     * <p>A route is considered to be transferable if the bluetooth device is connected but not
     * considered as selected.
     *
     * @return list of transferable routes or an empty list.
     */
    @NonNull
    List<MediaRoute2Info> getTransferableRoutes();

    /**
     * Provides all connected Bluetooth routes.
     *
     * @return list of Bluetooth routes or an empty list.
     */
    @NonNull
    List<MediaRoute2Info> getAllBluetoothRoutes();

    /**
     * Updates the volume for all Bluetooth devices for the given profile.
     *
     * @param devices specifies the profile, may be, {@link android.bluetooth.BluetoothA2dp}, {@link
     * android.bluetooth.BluetoothLeAudio}, or {@link android.bluetooth.BluetoothHearingAid}
     * @param volume the specific volume value for the given devices or 0 if unknown.
     * @return {@code true} if updated successfully and {@code false} otherwise.
     */
    boolean updateVolumeForDevices(int devices, int volume);

    /**
     * Interface for receiving events about Bluetooth routes changes.
     */
    interface BluetoothRoutesUpdatedListener {

        /** Called when Bluetooth routes have changed. */
        void onBluetoothRoutesUpdated();
    }

    /**
     * No-op implementation of {@link BluetoothRouteController}.
     *
     * <p>Useful if the device does not support Bluetooth.
     */
    class NoOpBluetoothRouteController implements BluetoothRouteController {

        @Override
        public void start(UserHandle userHandle) {
            // no op
        }

        @Override
        public void stop() {
            // no op
        }

        @Override
        public void transferTo(String routeId) {
            // no op
        }

        @Override
        public MediaRoute2Info getSelectedRoute() {
            // no op
            return null;
        }

        @Override
        public List<MediaRoute2Info> getTransferableRoutes() {
            // no op
            return Collections.emptyList();
        }

        @Override
        public List<MediaRoute2Info> getAllBluetoothRoutes() {
            // no op
            return Collections.emptyList();
        }

        @Override
        public boolean updateVolumeForDevices(int devices, int volume) {
            // no op
            return false;
        }
    }
}
