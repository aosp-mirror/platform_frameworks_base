/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.net.vcn.persistablebundleutils;

import android.annotation.NonNull;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.os.PersistableBundle;

import java.util.Objects;

/**
 * Utility class to convert Tunnel Connection Params to/from PersistableBundle
 *
 * @hide
 */
public final class TunnelConnectionParamsUtils {
    private static final int EXPECTED_BUNDLE_KEY_CNT = 1;

    private static final String PARAMS_TYPE_IKE = "IKE";

    /** Serializes an IkeTunnelConnectionParams to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull IkeTunnelConnectionParams params) {
        final PersistableBundle result = new PersistableBundle();

        result.putPersistableBundle(
                PARAMS_TYPE_IKE,
                IkeTunnelConnectionParamsUtils.serializeIkeParams(
                        (IkeTunnelConnectionParams) params));
        return result;
    }

    /** Constructs an IkeTunnelConnectionParams by deserializing a PersistableBundle. */
    @NonNull
    public static IkeTunnelConnectionParams fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        if (in.keySet().size() != EXPECTED_BUNDLE_KEY_CNT) {
            throw new IllegalArgumentException(
                    String.format(
                            "Expect PersistableBundle to have %d element but found: %s",
                            EXPECTED_BUNDLE_KEY_CNT, in.keySet()));
        }

        if (in.get(PARAMS_TYPE_IKE) != null) {
            return IkeTunnelConnectionParamsUtils.deserializeIkeParams(
                    in.getPersistableBundle(PARAMS_TYPE_IKE));
        }

        throw new IllegalArgumentException(
                "Invalid Tunnel Connection Params type " + in.keySet().iterator().next());
    }

    private static final class IkeTunnelConnectionParamsUtils {
        private static final String IKE_PARAMS_KEY = "IKE_PARAMS_KEY";
        private static final String CHILD_PARAMS_KEY = "CHILD_PARAMS_KEY";

        @NonNull
        public static PersistableBundle serializeIkeParams(
                @NonNull IkeTunnelConnectionParams ikeParams) {
            final PersistableBundle result = new PersistableBundle();

            result.putPersistableBundle(
                    IKE_PARAMS_KEY,
                    IkeSessionParamsUtils.toPersistableBundle(ikeParams.getIkeSessionParams()));
            result.putPersistableBundle(
                    CHILD_PARAMS_KEY,
                    TunnelModeChildSessionParamsUtils.toPersistableBundle(
                            ikeParams.getTunnelModeChildSessionParams()));
            return result;
        }

        @NonNull
        public static IkeTunnelConnectionParams deserializeIkeParams(
                @NonNull PersistableBundle in) {
            final PersistableBundle ikeBundle = in.getPersistableBundle(IKE_PARAMS_KEY);
            final PersistableBundle childBundle = in.getPersistableBundle(CHILD_PARAMS_KEY);
            Objects.requireNonNull(ikeBundle, "IkeSessionParams was null");
            Objects.requireNonNull(ikeBundle, "TunnelModeChildSessionParams was null");

            final IkeSessionParams ikeParams =
                    IkeSessionParamsUtils.fromPersistableBundle(ikeBundle);
            final TunnelModeChildSessionParams childParams =
                    TunnelModeChildSessionParamsUtils.fromPersistableBundle(childBundle);
            return new IkeTunnelConnectionParams(ikeParams, childParams);
        }
    }
}
