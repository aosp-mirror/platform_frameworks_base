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

import static android.net.vcn.VcnControlPlaneConfig.CONFIG_TYPE_IKE;

import android.annotation.NonNull;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.net.vcn.persistablebundleutils.IkeSessionParamsUtils;
import android.net.vcn.persistablebundleutils.TunnelModeChildSessionParamsUtils;
import android.os.PersistableBundle;
import android.util.ArraySet;
import android.util.Log;

import java.util.Objects;

/**
 * This class is an IKEv2 control plane configuration for a Virtual Carrier Network connection.
 *
 * <p>This class is an extension of the {@link VcnControlPlaneConfig}, containing IKEv2-specific
 * configuration, authentication and authorization parameters.
 *
 * @see VcnControlPlaneConfig
 */
public final class VcnControlPlaneIkeConfig extends VcnControlPlaneConfig {
    private static final String TAG = VcnControlPlaneIkeConfig.class.getSimpleName();

    private static final String IKE_PARAMS_KEY = "mIkeParams";
    @NonNull private final IkeSessionParams mIkeParams;

    private static final String CHILD_PARAMS_KEY = "mChildParams";
    @NonNull private final TunnelModeChildSessionParams mChildParams;

    private static final ArraySet<String> BUNDLE_KEY_SET = new ArraySet<>();

    {
        BUNDLE_KEY_SET.add(IKE_PARAMS_KEY);
        BUNDLE_KEY_SET.add(CHILD_PARAMS_KEY);
    }

    /**
     * Constructs a VcnControlPlaneIkeConfig object.
     *
     * @param ikeParams the IKE Session negotiation parameters
     * @param childParams the tunnel mode Child Session negotiation parameters
     */
    public VcnControlPlaneIkeConfig(
            @NonNull IkeSessionParams ikeParams,
            @NonNull TunnelModeChildSessionParams childParams) {
        super(CONFIG_TYPE_IKE);
        mIkeParams = ikeParams;
        mChildParams = childParams;
        validate();
    }

    /**
     * Constructs a VcnControlPlaneIkeConfig object by deserializing a PersistableBundle.
     *
     * @param in the {@link PersistableBundle} containing an {@link VcnControlPlaneIkeConfig} object
     * @hide
     */
    public VcnControlPlaneIkeConfig(@NonNull PersistableBundle in) {
        super(CONFIG_TYPE_IKE);
        final PersistableBundle ikeParamsBundle = in.getPersistableBundle(IKE_PARAMS_KEY);
        final PersistableBundle childParamsBundle = in.getPersistableBundle(CHILD_PARAMS_KEY);

        Objects.requireNonNull(ikeParamsBundle, "IKE Session Params was null");
        Objects.requireNonNull(childParamsBundle, "Child Session Params was null");

        mIkeParams = IkeSessionParamsUtils.fromPersistableBundle(ikeParamsBundle);
        mChildParams = TunnelModeChildSessionParamsUtils.fromPersistableBundle(childParamsBundle);

        for (String key : in.keySet()) {
            if (!BUNDLE_KEY_SET.contains(key)) {
                Log.w(TAG, "Found an unexpected key in the PersistableBundle: " + key);
            }
        }

        validate();
    }

    private void validate() {
        Objects.requireNonNull(mIkeParams, "mIkeParams was null");
        Objects.requireNonNull(mChildParams, "mChildParams was null");
    }

    /**
     * Converts this VcnControlPlaneConfig to a PersistableBundle.
     *
     * @hide
     */
    @Override
    @NonNull
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = super.toPersistableBundle();
        result.putPersistableBundle(
                IKE_PARAMS_KEY, IkeSessionParamsUtils.toPersistableBundle(mIkeParams));
        result.putPersistableBundle(
                CHILD_PARAMS_KEY,
                TunnelModeChildSessionParamsUtils.toPersistableBundle(mChildParams));
        return result;
    }

    /** Retrieves the IKE Session configuration. */
    @NonNull
    public IkeSessionParams getIkeSessionParams() {
        return mIkeParams;
    }

    /** Retrieves the tunnel mode Child Session configuration. */
    @NonNull
    public TunnelModeChildSessionParams getChildSessionParams() {
        return mChildParams;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mIkeParams, mChildParams);
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VcnControlPlaneIkeConfig)) {
            return false;
        }

        VcnControlPlaneIkeConfig other = (VcnControlPlaneIkeConfig) o;

        return super.equals(o)
                && Objects.equals(mIkeParams, other.mIkeParams)
                && Objects.equals(mChildParams, other.mChildParams);
    }

    /** @hide */
    @Override
    public VcnControlPlaneConfig copy() {
        return new VcnControlPlaneIkeConfig(
                new IkeSessionParams.Builder(mIkeParams).build(),
                new TunnelModeChildSessionParams.Builder(mChildParams).build());
    }
}
