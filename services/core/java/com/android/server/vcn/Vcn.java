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
import android.net.NetworkRequest;
import android.net.vcn.VcnConfig;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;

import java.util.Objects;

/**
 * Represents an single instance of a VCN.
 *
 * <p>Each Vcn instance manages all tunnels for a given subscription group, including per-capability
 * networks, network selection, and multi-homing.
 *
 * @hide
 */
public class Vcn extends Handler {
    private static final String TAG = Vcn.class.getSimpleName();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final ParcelUuid mSubscriptionGroup;
    @NonNull private final Dependencies mDeps;

    @NonNull private VcnConfig mConfig;

    public Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config) {
        this(vcnContext, subscriptionGroup, config, new Dependencies());
    }

    private Vcn(
            @NonNull VcnContext vcnContext,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull VcnConfig config,
            @NonNull Dependencies deps) {
        super(Objects.requireNonNull(vcnContext, "Missing vcnContext").getLooper());
        mVcnContext = vcnContext;
        mSubscriptionGroup = Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        mDeps = Objects.requireNonNull(deps, "Missing deps");

        mConfig = Objects.requireNonNull(config, "Missing config");
    }

    /** Asynchronously updates the configuration and triggers a re-evaluation of Networks */
    public void updateConfig(@NonNull VcnConfig config) {
        Objects.requireNonNull(config, "Missing config");
        // TODO: Proxy to handler, and make config there.
    }

    /** Asynchronously tears down this Vcn instance, along with all tunnels and Networks */
    public void teardown() {
        // TODO: Proxy to handler, and teardown there.
    }

    /** Notifies this Vcn instance of a new NetworkRequest */
    public void onNetworkRequested(@NonNull NetworkRequest request, int score, int providerId) {
        Objects.requireNonNull(request, "Missing request");

        // TODO: Proxy to handler, and handle there.
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        // TODO: Do something
    }

    /** Retrieves the network score for a VCN Network */
    private int getNetworkScore() {
        // TODO: STOPSHIP: Make this use new NetworkSelection, or some magic "max in subGrp" value
        return 52;
    }

    private static class Dependencies {}
}
