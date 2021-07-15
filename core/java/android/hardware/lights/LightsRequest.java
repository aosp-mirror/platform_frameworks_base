/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.lights;

import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Encapsulates a request to modify the state of multiple lights.
 *
 */
public final class LightsRequest {

    /** Visible to {@link LightsManager.Session}. */
    final Map<Light, LightState> mRequests = new HashMap<>();
    final List<Integer> mLightIds = new ArrayList<>();
    final List<LightState> mLightStates = new ArrayList<>();

    /**
     * Can only be constructed via {@link LightsRequest.Builder#build()}.
     */
    private LightsRequest(Map<Light, LightState> requests) {
        mRequests.putAll(requests);
        List<Light> lights = new ArrayList<Light>(mRequests.keySet());
        for (int i = 0; i < lights.size(); i++) {
            final Light light = lights.get(i);
            mLightIds.add(i, light.getId());
            mLightStates.add(i, mRequests.get(light));
        }
    }

    /**
     * Get a list of Light as ids.
     *
     * @return List of light ids in the request.
     */
    public @NonNull List<Integer> getLights() {
        return mLightIds;
    }

    /**
     * Get a list of LightState. The states will be returned in same order as the light ids
     * returned by {@link #getLights()}.
     *
     * @return List of light states
     */
    public @NonNull List<LightState> getLightStates() {
        return mLightStates;
    }

    /**
     * Get a map of lights and states. The map will contain all the lights as keys and
     * the corresponding LightState requested as values.
     */
    public @NonNull Map<Light, LightState> getLightsAndStates() {
        return mRequests;
    }

    /**
     * Builder for creating device light change requests.
     */
    public static final class Builder {

        final Map<Light, LightState> mChanges = new HashMap<>();
        /**
         * Overrides the color and intensity of a given light.
         *
         * @param light the light to modify
         * @param state the desired color and intensity of the light
         */
        public @NonNull Builder addLight(@NonNull Light light, @NonNull LightState state) {
            Preconditions.checkNotNull(light);
            Preconditions.checkNotNull(state);
            mChanges.put(light, state);
            return this;
        }

        /**
         * Overrides the color and intensity of a given light.
         *
         * @param light the light to modify
         * @param state the desired color and intensity of the light         *
         * @deprecated Use {@link #addLight(Light, LightState)} instead.
         * @hide
         */
        @SystemApi
        @Deprecated
        public @NonNull Builder setLight(@NonNull Light light, @NonNull LightState state) {
            return addLight(light, state);
        }

        /**
         * Removes the override for the color and intensity of a given light.
         *
         * @param light the light to modify
         */
        public @NonNull Builder clearLight(@NonNull Light light) {
            Preconditions.checkNotNull(light);
            mChanges.put(light, null);
            return this;
        }

        /**
         * Create a LightsRequest object used to override lights on the device.
         *
         * <p>The generated {@link LightsRequest} should be used in
         * {@link LightsManager.Session#requestLights(LightsLightsRequest).
         */
        public @NonNull LightsRequest build() {
            return new LightsRequest(mChanges);
        }
    }
}
