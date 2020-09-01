/*
 * Copyright 2017 The Android Open Source Project
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

package android.os;

/**
 * Battery stats local system service interface. This is used to pass internal data out of
 * BatteryStatsImpl, as well as make unchecked calls into BatteryStatsImpl.
 *
 * @hide Only for use within Android OS.
 */
public abstract class BatteryStatsInternal {
    /**
     * Returns the wifi interfaces.
     */
    public abstract String[] getWifiIfaces();

    /**
     * Returns the mobile data interfaces.
     */
    public abstract String[] getMobileIfaces();

    /**
     * Inform battery stats how many deferred jobs existed when the app got launched and how
     * long ago was the last job execution for the app.
     * @param uid the uid of the app.
     * @param numDeferred number of deferred jobs.
     * @param sinceLast how long in millis has it been since a job was run
     */
    public abstract void noteJobsDeferred(int uid, int numDeferred, long sinceLast);
}
