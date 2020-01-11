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

import android.annotation.Nullable;
import android.content.Context;
import android.location.Location;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A mock location provider used by LocationManagerService to implement test providers.
 *
 * {@hide}
 */
public class MockProvider extends AbstractLocationProvider {

    @Nullable private Location mLocation;

    public MockProvider(Context context, ProviderProperties properties) {
        // using a direct executor is only acceptable because this class is so simple it is trivial
        // to verify that it does not acquire any locks or re-enter LMS from callbacks
        super(context, Runnable::run);
        setProperties(properties);
    }

    /** Sets the enabled state of this mock provider. */
    public void setProviderEnabled(boolean enabled) {
        setEnabled(enabled);
    }

    /** Sets the location to report for this mock provider. */
    public void setProviderLocation(Location l) {
        Location location = new Location(l);
        location.setIsFromMockProvider(true);
        mLocation = location;
        reportLocation(location);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("last mock location=" + mLocation);
    }

    @Override
    public void onSetRequest(ProviderRequest request) {}
}
