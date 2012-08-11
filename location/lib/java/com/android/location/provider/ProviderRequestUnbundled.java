/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.provider;

import java.util.List;

import android.location.LocationRequest;

import com.android.internal.location.ProviderRequest;

/**
 * This class is a public API for unbundled providers,
 * that hides the (hidden framework) ProviderRequest.
 * <p>Do _not_ remove public methods on this class.
 */
public final class ProviderRequestUnbundled {
    private final ProviderRequest mRequest;

    public ProviderRequestUnbundled(ProviderRequest request) {
        mRequest = request;
    }

    public boolean getReportLocation() {
        return mRequest.reportLocation;
    }

    public long getInterval() {
        return mRequest.interval;
    }

    /**
     * Never null.
     */
    public List<LocationRequest> getLocationRequests() {
        return mRequest.locationRequests;
    }

    @Override
    public String toString() {
        return mRequest.toString();
    }
}
