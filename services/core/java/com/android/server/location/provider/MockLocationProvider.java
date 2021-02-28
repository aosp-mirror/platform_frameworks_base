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

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

/**
 * A mock location provider used by LocationManagerService to implement test providers.
 *
 * {@hide}
 */
public class MockLocationProvider extends AbstractLocationProvider {

    @Nullable private Location mLocation;

    public MockLocationProvider(ProviderProperties properties, CallerIdentity identity,
            Set<String> extraAttributionTags) {
        // using a direct executor is ok because this class has no locks that could deadlock
        super(DIRECT_EXECUTOR, identity, properties, extraAttributionTags);
    }

    /** Sets the allowed state of this mock provider. */
    public void setProviderAllowed(boolean allowed) {
        setAllowed(allowed);
    }

    /** Sets the location to report for this mock provider. */
    public void setProviderLocation(Location l) {
        Location location = new Location(l);
        location.setIsFromMockProvider(true);
        mLocation = location;
        reportLocation(LocationResult.wrap(location).validate());
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("last mock location=" + mLocation);
    }
}
