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

package com.android.server.location.provider;

import static android.location.provider.ProviderProperties.ACCURACY_FINE;
import static android.location.provider.ProviderProperties.POWER_USAGE_LOW;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.content.Context;
import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;

/**
 * A passive location provider reports locations received from other providers
 * for clients that want to listen passively without actually triggering
 * location updates.
 *
 * {@hide}
 */
public class PassiveLocationProvider extends AbstractLocationProvider {

    private static final ProviderProperties PROPERTIES = new ProviderProperties.Builder()
            .setPowerUsage(POWER_USAGE_LOW)
            .setAccuracy(ACCURACY_FINE)
            .build();

    public PassiveLocationProvider(Context context) {
        // using a direct executor is ok because this class has no locks that could deadlock
        super(DIRECT_EXECUTOR, CallerIdentity.fromContext(context), PROPERTIES,
                Collections.emptySet());
        setAllowed(true);
    }

    /**
     * Pass a location into the passive provider.
     */
    public void updateLocation(LocationResult locationResult) {
        reportLocation(locationResult);
    }

    @Override
    public void onSetRequest(ProviderRequest request) {}

    @Override
    protected void onFlush(Runnable callback) {
        callback.run();
    }

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {}

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {}
}
