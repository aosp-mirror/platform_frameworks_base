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
import android.annotation.TestApi;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

/**
 * Encapsulates a request to modify the state of multiple lights.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class LightsRequest {

    /** Visible to {@link LightsManager.Session}. */
    final int[] mLightIds;

    /** Visible to {@link LightsManager.Session}. */
    final LightState[] mLightStates;

    /**
     * Can only be constructed via {@link LightsRequest.Builder#build()}.
     */
    private LightsRequest(SparseArray<LightState> changes) {
        final int n = changes.size();
        mLightIds = new int[n];
        mLightStates = new LightState[n];
        for (int i = 0; i < n; i++) {
            mLightIds[i] = changes.keyAt(i);
            mLightStates[i] = changes.valueAt(i);
        }
    }

    /**
     * Builder for creating device light change requests.
     */
    public static final class Builder {

        private final SparseArray<LightState> mChanges = new SparseArray<>();

        /**
         * Overrides the color and intensity of a given light.
         *
         * @param light the light to modify
         * @param state the desired color and intensity of the light
         */
        public @NonNull Builder setLight(@NonNull Light light, @NonNull LightState state) {
            Preconditions.checkNotNull(light);
            Preconditions.checkNotNull(state);
            mChanges.put(light.getId(), state);
            return this;
        }

        /**
         * Removes the override for the color and intensity of a given light.
         *
         * @param light the light to modify
         */
        public @NonNull Builder clearLight(@NonNull Light light) {
            Preconditions.checkNotNull(light);
            mChanges.put(light.getId(), null);
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
