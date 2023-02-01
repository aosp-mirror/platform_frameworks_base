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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.Network;

import com.android.internal.os.BinderCallsStats;
import com.android.server.power.stats.SystemServerCpuThreadReader.SystemServiceCpuThreadTimes;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;

/**
 * Battery stats local system service interface. This is used to pass internal data out of
 * BatteryStatsImpl, as well as make unchecked calls into BatteryStatsImpl.
 *
 * @hide Only for use within Android OS.
 */
public abstract class BatteryStatsInternal {

    public static final int CPU_WAKEUP_SUBSYSTEM_UNKNOWN = -1;
    public static final int CPU_WAKEUP_SUBSYSTEM_ALARM = 1;

    /** @hide */
    @IntDef(prefix = {"CPU_WAKEUP_SUBSYSTEM_"}, value = {
            CPU_WAKEUP_SUBSYSTEM_UNKNOWN,
            CPU_WAKEUP_SUBSYSTEM_ALARM,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface CpuWakeupSubsystem {
    }

    /**
     * Returns the wifi interfaces.
     */
    public abstract String[] getWifiIfaces();

    /**
     * Returns the mobile data interfaces.
     */
    public abstract String[] getMobileIfaces();

    /** Returns CPU times for system server thread groups. */
    public abstract SystemServiceCpuThreadTimes getSystemServiceCpuThreadTimes();

    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     *
     * <p>
     * Note: This is a slow running method and should be called from non-blocking threads only.
     * </p>
     */
    public abstract List<BatteryUsageStats> getBatteryUsageStats(
            List<BatteryUsageStatsQuery> queries);

    /**
     * Inform battery stats how many deferred jobs existed when the app got launched and how
     * long ago was the last job execution for the app.
     *
     * @param uid         the uid of the app.
     * @param numDeferred number of deferred jobs.
     * @param sinceLast   how long in millis has it been since a job was run
     */
    public abstract void noteJobsDeferred(int uid, int numDeferred, long sinceLast);

    /**
     * Informs battery stats of a data packet that woke up the CPU.
     *
     * @param network The network over which the packet arrived.
     * @param elapsedMillis The time of the packet's arrival in elapsed timebase.
     * @param uid The uid that received the packet.
     */
    public abstract void noteCpuWakingNetworkPacket(Network network, long elapsedMillis, int uid);

    /**
     * Informs battery stats of binder stats for the given work source UID.
     */
    public abstract void noteBinderCallStats(int workSourceUid, long incrementalBinderCallCount,
            Collection<BinderCallsStats.CallStat> callStats);

    /**
     * Informs battery stats of native thread IDs of threads taking incoming binder calls.
     */
    public abstract void noteBinderThreadNativeIds(int[] binderThreadNativeTids);

    /**
     * Reports any activity that could potentially have caused the CPU to wake up.
     * Accepts a timestamp to allow the reporter to report it before or after the event.
     */
    public abstract void noteCpuWakingActivity(@CpuWakeupSubsystem int subsystem,
            long elapsedMillis, @NonNull int... uids);
}
