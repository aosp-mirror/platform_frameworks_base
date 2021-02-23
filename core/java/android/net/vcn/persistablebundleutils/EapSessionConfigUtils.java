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

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.eap.EapSessionConfig;
import android.net.eap.EapSessionConfig.EapMethodConfig;
import android.net.eap.EapSessionConfig.EapMsChapV2Config;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.Objects;

/**
 * Provides utility methods to convert EapSessionConfig to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class EapSessionConfigUtils {
    private static final String EAP_ID_KEY = "EAP_ID_KEY";
    private static final String EAP_MSCHAP_V2_CONFIG_KEY = "EAP_MSCHAP_V2_CONFIG_KEY";

    /** Serializes an EapSessionConfig to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull EapSessionConfig config) {
        final PersistableBundle result = new PersistableBundle();

        result.putPersistableBundle(
                EAP_ID_KEY, PersistableBundleUtils.fromByteArray(config.getEapIdentity()));

        if (config.getEapMsChapV2Config() != null) {
            result.putPersistableBundle(
                    EAP_MSCHAP_V2_CONFIG_KEY,
                    EapMsChapV2ConfigUtils.toPersistableBundle(config.getEapMsChapV2Config()));
        }

        // TODO: Handle other EAP methods: EAP-SIM, EAP-AKA, EAP-AKA', EAP-TTLS.

        return result;
    }

    /** Constructs an EapSessionConfig by deserializing a PersistableBundle. */
    @NonNull
    public static EapSessionConfig fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final EapSessionConfig.Builder builder = new EapSessionConfig.Builder();

        final PersistableBundle eapIdBundle = in.getPersistableBundle(EAP_ID_KEY);
        Objects.requireNonNull(eapIdBundle, "EAP ID was null");
        builder.setEapIdentity(PersistableBundleUtils.toByteArray(eapIdBundle));

        final PersistableBundle msChapV2Bundle = in.getPersistableBundle(EAP_MSCHAP_V2_CONFIG_KEY);
        if (msChapV2Bundle != null) {
            EapMsChapV2ConfigUtils.setBuilderByReadingPersistableBundle(msChapV2Bundle, builder);
        }

        // TODO: Handle other EAP methods: EAP-SIM, EAP-AKA, EAP-AKA', EAP-TTLS.
        return builder.build();
    }

    private static class EapMethodConfigUtils {
        private static final String METHOD_TYPE = "METHOD_TYPE";

        /** Serializes an EapMethodConfig to a PersistableBundle. */
        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapMethodConfig config) {
            final PersistableBundle result = new PersistableBundle();
            result.putInt(METHOD_TYPE, config.getMethodType());
            return result;
        }
    }

    private static final class EapMsChapV2ConfigUtils extends EapMethodConfigUtils {
        private static final String USERNAME_KEY = "USERNAME_KEY";
        private static final String PASSWORD_KEY = "PASSWORD_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapMsChapV2Config config) {
            final PersistableBundle result = EapMethodConfigUtils.toPersistableBundle(config);
            result.putString(USERNAME_KEY, config.getUsername());
            result.putString(PASSWORD_KEY, config.getPassword());

            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");
            builder.setEapMsChapV2Config(in.getString(USERNAME_KEY), in.getString(PASSWORD_KEY));
        }
    }
}
