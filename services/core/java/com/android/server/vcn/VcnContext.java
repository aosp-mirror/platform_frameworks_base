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

package com.android.server.vcn;

import android.annotation.NonNull;
import android.content.Context;
import android.net.IpSecTransformState;
import android.net.vcn.FeatureFlags;
import android.net.vcn.FeatureFlagsImpl;
import android.os.Looper;

import java.util.Objects;

/**
 * A simple class to pass around context information.
 *
 * @hide
 */
public class VcnContext {
    @NonNull private final Context mContext;
    @NonNull private final Looper mLooper;
    @NonNull private final VcnNetworkProvider mVcnNetworkProvider;
    @NonNull private final FeatureFlags mFeatureFlags;
    private final boolean mIsInTestMode;

    public VcnContext(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull VcnNetworkProvider vcnNetworkProvider,
            boolean isInTestMode) {
        mContext = Objects.requireNonNull(context, "Missing context");
        mLooper = Objects.requireNonNull(looper, "Missing looper");
        mVcnNetworkProvider = Objects.requireNonNull(vcnNetworkProvider, "Missing networkProvider");
        mIsInTestMode = isInTestMode;

        // Auto-generated class
        mFeatureFlags = new FeatureFlagsImpl();
    }

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @NonNull
    public Looper getLooper() {
        return mLooper;
    }

    @NonNull
    public VcnNetworkProvider getVcnNetworkProvider() {
        return mVcnNetworkProvider;
    }

    public boolean isInTestMode() {
        return mIsInTestMode;
    }

    public boolean isFlagNetworkMetricMonitorEnabled() {
        return mFeatureFlags.networkMetricMonitor();
    }

    public boolean isFlagIpSecTransformStateEnabled() {
        // TODO: b/328844044: Ideally this code should gate the behavior by checking the
        // android.net.platform.flags.ipsec_transform_state flag but that flag is not accessible
        // right now. We should either update the code when the flag is accessible or remove the
        // legacy behavior after VIC SDK finalization
        try {
            new IpSecTransformState.Builder();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    public FeatureFlags getFeatureFlags() {
        return mFeatureFlags;
    }

    public boolean isFlagSafeModeTimeoutConfigEnabled() {
        return mFeatureFlags.safeModeTimeoutConfig();
    }

    /**
     * Verifies that the caller is running on the VcnContext Thread.
     *
     * @throwsIllegalStateException if the caller is not running on the VcnContext Thread.
     */
    public void ensureRunningOnLooperThread() {
        if (getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException("Not running on VcnMgmtSvc thread");
        }
    }
}
