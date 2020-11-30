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
package android.net.vcn;

import android.annotation.NonNull;

/**
 * This class represents a configuration for a connection to a Virtual Carrier Network gateway.
 *
 * <p>Each VcnGatewayConnectionConfig represents a single logical connection to a carrier gateway,
 * and may provide one or more telephony services (as represented by network capabilities). Each
 * gateway is expected to provide mobility for a given session as the device roams across {@link
 * Network}s.
 *
 * <p>A VCN connection based on this configuration will be brought up dynamically based on device
 * settings, and filed NetworkRequests. Underlying networks will be selected based on the services
 * required by this configuration (as represented by network capabilities), and must be part of the
 * subscription group under which this configuration is registered (see {@link
 * VcnManager#setVcnConfig}).
 *
 * <p>Services that can be provided by a VCN network, or required for underlying networks are
 * limited to services provided by cellular networks:
 *
 * <ul>
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_MMS}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_SUPL}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_DUN}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_FOTA}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_IMS}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_CBS}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_IA}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_RCS}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_XCAP}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_EIMS}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET}
 *   <li>{@link android.net.NetworkCapabilities.NET_CAPABILITY_MCX}
 * </ul>
 *
 * @hide
 */
public final class VcnGatewayConnectionConfig {
    private VcnGatewayConnectionConfig() {
        validate();
    }

    // TODO: Implement getters, validators, etc

    /**
     * Validates this configuration
     *
     * @hide
     */
    private void validate() {
        // TODO: implement validation logic
    }

    // Parcelable methods

    /** This class is used to incrementally build {@link VcnGatewayConnectionConfig} objects */
    public static class Builder {
        // TODO: Implement this builder

        /**
         * Builds and validates the VcnGatewayConnectionConfig
         *
         * @return an immutable VcnGatewayConnectionConfig instance
         */
        @NonNull
        public VcnGatewayConnectionConfig build() {
            return new VcnGatewayConnectionConfig();
        }
    }
}
