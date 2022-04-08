/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server.location;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.location.Location;
import android.os.Bundle;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;

/**
 * A mock location provider used by LocationManagerService to implement test providers.
 *
 * {@hide}
 */
public class MockProvider extends AbstractLocationProvider {

    @Nullable private Location mLocation;

    public MockProvider(ProviderProperties properties) {
        // using a direct executor is ok because this class has no locks that could deadlock
        super(DIRECT_EXECUTOR, Collections.emptySet());
        setProperties(properties);
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
        reportLocation(location);
    }

    @Override
    public void onSetRequest(ProviderRequest request) {}

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {}

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("last mock location=" + mLocation);
    }
}
