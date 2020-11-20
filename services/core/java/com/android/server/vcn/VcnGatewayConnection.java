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
import android.annotation.Nullable;
import android.net.vcn.VcnGatewayConnectionConfig;
import android.os.Handler;
import android.os.ParcelUuid;

import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkTrackerCallback;

import java.util.Objects;

/**
 * A single VCN Gateway Connection, providing a single public-facing VCN network.
 *
 * <p>This class handles mobility events, performs retries, and tracks safe-mode conditions.
 *
 * @hide
 */
public class VcnGatewayConnection extends Handler implements UnderlyingNetworkTrackerCallback {
    private static final String TAG = VcnGatewayConnection.class.getSimpleName();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final UnderlyingNetworkTracker mUnderlyingNetworkTracker;
    @NonNull private final VcnGatewayConnectionConfig mConnectionConfig;
    @NonNull private final Dependencies mDeps;

    public VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnGatewayConnectionConfig connectionConfig) {
        this(vcnContext, subscriptionGroup, connectionConfig, new Dependencies());
    }

    private VcnGatewayConnection(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnGatewayConnectionConfig connectionConfig,
            @NonNull Dependencies deps) {
        super(Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mConnectionConfig = Objects.requireNonNull(connectionConfig, "Missing connectionConfig");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mUnderlyingNetworkTracker =
                mDeps.newUnderlyingNetworkTracker(mVcnContext, subscriptionGroup, this);
    }

    /** Tears down this GatewayConnection, and any resources used */
    public void teardown() {
        mUnderlyingNetworkTracker.teardown();
    }

    private static class Dependencies {
        public UnderlyingNetworkTracker newUnderlyingNetworkTracker(
                VcnContext vcnContext,
                ParcelUuid subscriptionGroup,
                UnderlyingNetworkTrackerCallback callback) {
            return new UnderlyingNetworkTracker(vcnContext, subscriptionGroup, callback);
        }
    }

    @Override
    public void onSelectedUnderlyingNetworkChanged(@Nullable UnderlyingNetworkRecord underlying) {}
}
