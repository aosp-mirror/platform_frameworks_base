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

import android.annotation.Nullable;
import android.content.Context;
import android.location.LocationManager;
import android.location.LocationResult;
import android.location.provider.ProviderRequest;
import android.os.Binder;

import com.android.internal.util.Preconditions;
import com.android.server.location.injector.Injector;

import java.util.Collection;

/**
 * A location provider manager specifically for the passive provider.
 */
public class PassiveLocationProviderManager extends LocationProviderManager {

    public PassiveLocationProviderManager(Context context, Injector injector) {
        super(context, injector, LocationManager.PASSIVE_PROVIDER, null);
    }

    @Override
    public void setRealProvider(AbstractLocationProvider provider) {
        Preconditions.checkArgument(provider instanceof PassiveLocationProvider);
        super.setRealProvider(provider);
    }

    @Override
    public void setMockProvider(@Nullable MockLocationProvider provider) {
        if (provider != null) {
            throw new IllegalArgumentException("Cannot mock the passive provider");
        }
    }

    /**
     * Reports a new location to passive location provider clients.
     */
    public void updateLocation(LocationResult locationResult) {
        synchronized (mLock) {
            PassiveLocationProvider passive = (PassiveLocationProvider) mProvider.getProvider();
            Preconditions.checkState(passive != null);

            final long identity = Binder.clearCallingIdentity();
            try {
                passive.updateLocation(locationResult);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        return new ProviderRequest.Builder().setIntervalMillis(0).build();
    }

    @Override
    protected long calculateRequestDelayMillis(long newIntervalMs,
            Collection<Registration> registrations) {
        return 0;
    }

    @Override
    protected String getServiceState() {
        return mProvider.getCurrentRequest().isActive() ? "registered" : "unregistered";
    }
}
