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

import com.android.internal.location.ProviderProperties;

/**
 * This class is a public API for unbundled providers,
 * that hides the (hidden framework) ProviderProperties.
 * <p>Do _not_ remove public methods on this class.
 */
public final class ProviderPropertiesUnbundled {
    private final ProviderProperties mProperties;

    public static ProviderPropertiesUnbundled create(boolean requiresNetwork,
            boolean requiresSatellite, boolean requiresCell, boolean hasMonetaryCost,
            boolean supportsAltitude, boolean supportsSpeed, boolean supportsBearing,
            int powerRequirement, int accuracy) {
        return new ProviderPropertiesUnbundled(new ProviderProperties(requiresNetwork,
                requiresSatellite, requiresCell, hasMonetaryCost, supportsAltitude, supportsSpeed,
                supportsBearing, powerRequirement, accuracy));
    }

    private ProviderPropertiesUnbundled(ProviderProperties properties) {
        mProperties = properties;
    }

    public ProviderProperties getProviderProperties() {
        return mProperties;
    }

    @Override
    public String toString() {
        return mProperties.toString();
    }
}
