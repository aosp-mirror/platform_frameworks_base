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

import android.location.Criteria;
import android.location.Location;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.WorkSource;

/**
 * Location Manager's interface for location providers.
 *
 * {@hide}
 */
public interface LocationProviderInterface {
    String getName();
    boolean requiresNetwork();
    boolean requiresSatellite();
    boolean requiresCell();
    boolean hasMonetaryCost();
    boolean supportsAltitude();
    boolean supportsSpeed();
    boolean supportsBearing();
    int getPowerRequirement();
    boolean meetsCriteria(Criteria criteria);
    int getAccuracy();
    boolean isEnabled();
    void enable();
    void disable();
    int getStatus(Bundle extras);
    long getStatusUpdateTime();
    void enableLocationTracking(boolean enable);
    /* returns false if single shot is not supported */
    boolean requestSingleShotFix();
    String getInternalState();
    void setMinTime(long minTime, WorkSource ws);
    void updateNetworkState(int state, NetworkInfo info);
    void updateLocation(Location location);
    boolean sendExtraCommand(String command, Bundle extras);
    void addListener(int uid);
    void removeListener(int uid);
}
