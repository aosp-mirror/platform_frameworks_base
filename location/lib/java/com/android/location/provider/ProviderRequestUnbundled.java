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

import android.location.LocationRequest;

import com.android.internal.location.ProviderRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is an interface to Provider Requests for unbundled applications.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled
 * applications, and must remain API stable. See README.txt in the root
 * of this package for more information.
 */
public final class ProviderRequestUnbundled {
    private final ProviderRequest mRequest;

    /** @hide */
    public ProviderRequestUnbundled(ProviderRequest request) {
        mRequest = request;
    }

    public boolean getReportLocation() {
        return mRequest.reportLocation;
    }

    public long getInterval() {
        return mRequest.interval;
    }

    public boolean getForceLocation() {
        return mRequest.forceLocation;
    }

    /**
     * Never null.
     */
    public List<LocationRequestUnbundled> getLocationRequests() {
        List<LocationRequestUnbundled> result = new ArrayList<LocationRequestUnbundled>(
                mRequest.locationRequests.size());
        for (LocationRequest r : mRequest.locationRequests) {
          result.add(new LocationRequestUnbundled(r));
        }
        return result;
    }

    @Override
    public String toString() {
        return mRequest.toString();
    }
}
