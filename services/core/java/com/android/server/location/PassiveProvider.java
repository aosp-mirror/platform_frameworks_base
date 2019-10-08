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

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.os.Bundle;
import android.os.WorkSource;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A passive location provider reports locations received from other providers
 * for clients that want to listen passively without actually triggering
 * location updates.
 *
 * {@hide}
 */
public class PassiveProvider extends AbstractLocationProvider {

    private static final ProviderProperties PROPERTIES = new ProviderProperties(
            false, false, false, false, false, false, false,
            Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);

    private boolean mReportLocation;

    public PassiveProvider(Context context, LocationProviderManager locationProviderManager) {
        super(context, locationProviderManager);

        mReportLocation = false;

        setProperties(PROPERTIES);
        setEnabled(true);
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        mReportLocation = request.reportLocation;
    }

    public void updateLocation(Location location) {
        if (mReportLocation) {
            reportLocation(location);
        }
    }

    @Override
    public void sendExtraCommand(String command, Bundle extras) {}

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(" report location=" + mReportLocation);
    }
}
