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

package android.location;

import android.location.Criteria;
import android.location.Location;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.WorkSource;

/**
 * Binder interface for services that implement location providers.
 *
 * {@hide}
 */
interface ILocationProvider {
    boolean requiresNetwork();
    boolean requiresSatellite();
    boolean requiresCell();
    boolean hasMonetaryCost();
    boolean supportsAltitude();
    boolean supportsSpeed();
    boolean supportsBearing();
    int getPowerRequirement();
    boolean meetsCriteria(in Criteria criteria);
    int getAccuracy();
    void enable();
    void disable();
    int getStatus(out Bundle extras);
    long getStatusUpdateTime();
    String getInternalState();
    void enableLocationTracking(boolean enable);
    void setMinTime(long minTime, in WorkSource ws);
    void updateNetworkState(int state, in NetworkInfo info);
    void updateLocation(in Location location);
    boolean sendExtraCommand(String command, inout Bundle extras);
    void addListener(int uid);
    void removeListener(int uid);
}
