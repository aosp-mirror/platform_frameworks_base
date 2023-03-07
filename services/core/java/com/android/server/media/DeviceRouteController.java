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
import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.os.ServiceManager;

/**
 * Controls device routes.
 *
 * <p>A device route is a system wired route, for example, built-in speaker, wired
 * headsets and headphones, dock, hdmi, or usb devices.
 *
 * @see SystemMediaRoute2Provider
 */
/* package */ interface DeviceRouteController {

    /**
     * Returns a new instance of {@link DeviceRouteController}.
     */
    /* package */ static DeviceRouteController createInstance(@NonNull Context context,
            @NonNull OnDeviceRouteChangedListener onDeviceRouteChangedListener) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        IAudioService audioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));

        MediaFeatureFlagManager flagManager = MediaFeatureFlagManager.getInstance();
        boolean isUsingLegacyController = flagManager.getBoolean(
                MediaFeatureFlagManager.FEATURE_AUDIO_STRATEGIES_IS_USING_LEGACY_CONTROLLER,
                true);

        if (isUsingLegacyController) {
            return new LegacyDeviceRouteController(context,
                    audioManager,
                    audioService,
                    onDeviceRouteChangedListener);
        } else {
            return new AudioPoliciesDeviceRouteController(context,
                    audioManager,
                    audioService,
                    onDeviceRouteChangedListener);
        }
    }

    /**
     * Select the route with the given built-in or wired {@link MediaRoute2Info.Type}.
     *
     * <p>If the type is {@code null} then unselects the route and falls back to the default device
     * route observed from
     * {@link com.android.server.audio.AudioService#startWatchingRoutes(IAudioRoutesObserver)}.
     *
     * @param type device type. May be {@code null} to unselect currently selected route.
     * @return whether the selection succeeds. If the selection fails the state of the controller
     * remains intact.
     */
    boolean selectRoute(@Nullable @MediaRoute2Info.Type Integer type);

    /**
     * Returns currently selected device (built-in or wired) route.
     *
     * @return non-null device route.
     */
    @NonNull
    MediaRoute2Info getDeviceRoute();

    /**
     * Updates device route volume.
     *
     * @param volume specifies a volume for the device route or 0 for unknown.
     * @return {@code true} if updated successfully and {@code false} otherwise.
     */
    boolean updateVolume(int volume);

    /**
     * Interface for receiving events when device route has changed.
     */
    interface OnDeviceRouteChangedListener {

        /**
         * Called when device route has changed.
         *
         * @param deviceRoute non-null device route.
         */
        void onDeviceRouteChanged(@NonNull MediaRoute2Info deviceRoute);
    }

}
