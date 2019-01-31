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
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.WorkSource;

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

    private boolean mEnabled;
    @Nullable private Location mLocation;
    private int mStatus;
    private long mStatusUpdateTime;
    private Bundle mExtras;

    public MockProvider(Context context,
            LocationProviderManager locationProviderManager, ProviderProperties properties) {
        super(context, locationProviderManager);

        mEnabled = true;
        mLocation = null;
        mStatus = LocationProvider.AVAILABLE;
        mStatusUpdateTime = 0;
        mExtras = null;

        setProperties(properties);
    }

    /** Sets the enabled state of this mock provider. */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        super.setEnabled(enabled);
    }

    /** Sets the location to report for this mock provider. */
    public void setLocation(Location l) {
        mLocation = new Location(l);
        if (!mLocation.isFromMockProvider()) {
            mLocation.setIsFromMockProvider(true);
        }
        if (mEnabled) {
            reportLocation(mLocation);
        }
    }

    /** Sets the status for this mock provider. */
    public void setStatus(int status, Bundle extras, long updateTime) {
        mStatus = status;
        mStatusUpdateTime = updateTime;
        mExtras = extras;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(" last location=" + mLocation);
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {}

    @Override
    public int getStatus(Bundle extras) {
        if (mExtras != null) {
            extras.clear();
            extras.putAll(mExtras);
        }

        return mStatus;
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    @Override
    public void sendExtraCommand(String command, Bundle extras) {}
}
