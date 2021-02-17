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
package android.net.vcn;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * This class represents a control plane configuration for a Virtual Carrier Network connection.
 *
 * <p>Each {@link VcnGatewayConnectionConfig} must have a {@link VcnControlPlaneConfig}, containing
 * all connection, authentication and authorization parameters required to establish a Gateway
 * Connection with a remote endpoint.
 *
 * <p>A {@link VcnControlPlaneConfig} object can be shared by multiple {@link
 * VcnGatewayConnectionConfig}(s) if they will used for connecting with the same remote endpoint.
 *
 * @see VcnManager
 * @see VcnGatewayConnectionConfig
 *
 * @hide
 */
public abstract class VcnControlPlaneConfig {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CONFIG_TYPE_IKE})
    public @interface ConfigType {}

    /** @hide */
    public static final int CONFIG_TYPE_IKE = 1;

    private static final String CONFIG_TYPE_KEY = "mConfigType";
    @ConfigType private final int mConfigType;

    /**
     * Package private constructor.
     *
     * @hide
     */
    VcnControlPlaneConfig(int configType) {
        mConfigType = configType;
    }

    /**
     * Constructs a VcnControlPlaneConfig object by deserializing a PersistableBundle.
     *
     * @param in the {@link PersistableBundle} containing an {@link VcnControlPlaneConfig} object
     * @hide
     */
    public static VcnControlPlaneConfig fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        int configType = in.getInt(CONFIG_TYPE_KEY);
        switch (configType) {
            case CONFIG_TYPE_IKE:
                return new VcnControlPlaneIkeConfig(in);
            default:
                throw new IllegalStateException("Unrecognized configType: " + configType);
        }
    }

    /**
     * Converts this VcnControlPlaneConfig to a PersistableBundle.
     *
     * @hide
     */
    @NonNull
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = new PersistableBundle();
        result.putInt(CONFIG_TYPE_KEY, mConfigType);
        return result;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(mConfigType);
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VcnControlPlaneConfig)) {
            return false;
        }

        return mConfigType == ((VcnControlPlaneConfig) o).mConfigType;
    }

    /**
     * Returns a deep copy of this object.
     *
     * @hide
     */
    public abstract VcnControlPlaneConfig copy();
}
