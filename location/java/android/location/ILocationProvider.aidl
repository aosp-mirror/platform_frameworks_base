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

import android.os.Bundle;

/**
 * An interface for location providers implemented outside of the system process.
 *
 * {@hide}
 */
interface ILocationProvider {

    /* for LocationProvider */
    boolean requiresNetwork();
    boolean requiresSatellite();
    boolean requiresCell();
    boolean hasMonetaryCost();
    boolean supportsAltitude();
    boolean supportsSpeed();
    boolean supportsBearing();
    int getPowerRequirement();
    int getAccuracy();

    /* for LocationProviderImpl */
    void enable();
    void disable();
    boolean isEnabled();
    int getStatus(out Bundle extras);
    long getStatusUpdateTime();
    void enableLocationTracking(boolean enable);
    void setMinTime(long minTime);
    void updateNetworkState(int state);
    boolean sendExtraCommand(String command, inout Bundle extras);

    /* the following are only used for NetworkLocationProvider */
    void updateCellLockStatus(boolean acquired);
    void addListener(in String[] applications);
    void removeListener(in String[] applications);
}
