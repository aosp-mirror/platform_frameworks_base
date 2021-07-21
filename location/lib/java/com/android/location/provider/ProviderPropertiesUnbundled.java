/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.NonNull;
import android.location.provider.ProviderProperties;

import java.util.Objects;

/**
 * Represents provider properties for unbundled applications.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled applications, and must remain
 * API stable.
 */
public final class ProviderPropertiesUnbundled {

    /**
     * Create new instance of {@link ProviderPropertiesUnbundled} with the given arguments.
     */
    public static @NonNull ProviderPropertiesUnbundled create(boolean requiresNetwork,
            boolean requiresSatellite, boolean requiresCell, boolean hasMonetaryCost,
            boolean supportsAltitude, boolean supportsSpeed, boolean supportsBearing,
            int powerUsage, int accuracy) {
        return new ProviderPropertiesUnbundled(new ProviderProperties.Builder()
                .setHasNetworkRequirement(requiresNetwork)
                .setHasSatelliteRequirement(requiresSatellite)
                .setHasCellRequirement(requiresCell)
                .setHasMonetaryCost(requiresCell)
                .setHasAltitudeSupport(requiresCell)
                .setHasSpeedSupport(requiresCell)
                .setHasBearingSupport(requiresCell)
                .setPowerUsage(powerUsage)
                .setAccuracy(accuracy)
                .build());
    }

    private final ProviderProperties mProperties;

    private ProviderPropertiesUnbundled(ProviderProperties properties) {
        mProperties = Objects.requireNonNull(properties);
    }

    /** @hide */
    public @NonNull ProviderProperties getProviderProperties() {
        return mProperties;
    }

    @Override
    public String toString() {
        return mProperties.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ProviderPropertiesUnbundled that = (ProviderPropertiesUnbundled) o;
        return mProperties.equals(that.mProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProperties);
    }
}
