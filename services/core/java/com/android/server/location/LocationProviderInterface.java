/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.location.LocationProvider;
import android.os.Bundle;
import android.os.WorkSource;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Location Manager's interface for location providers.
 * @hide
 */
public abstract class LocationProviderInterface {

    /** Get name. */
    public abstract String getName();

    /** Enable. */
    public abstract void enable();

    /** Disable. */
    public abstract void disable();

    /** Is enabled. */
    public abstract boolean isEnabled();

    /** Set request. */
    public abstract void setRequest(ProviderRequest request, WorkSource source);

    /** dump. */
    public abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /** Get properties. */
    public abstract ProviderProperties getProperties();

    /**
     * Get status.
     *
     * @deprecated Will be removed in a future release.
     */
    @Deprecated
    public int getStatus(Bundle extras) {
        return LocationProvider.AVAILABLE;
    }

    /**
     * Get status update time.
     *
     * @deprecated Will be removed in a future release.
     */
    @Deprecated
    public long getStatusUpdateTime() {
        return 0;
    }

    /** Send extra command. */
    public abstract boolean sendExtraCommand(String command, Bundle extras);
}
