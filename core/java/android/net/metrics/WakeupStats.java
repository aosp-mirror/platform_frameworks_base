/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.metrics;

import android.os.Process;
import android.os.SystemClock;

/**
 * An event logged per interface and that aggregates WakeupEvents for that interface.
 * {@hide}
 */
public class WakeupStats {

    private static final int NO_UID = -1;

    public final long creationTimeMs = SystemClock.elapsedRealtime();
    public final String iface;

    public long totalWakeups = 0;
    public long rootWakeups = 0;
    public long systemWakeups = 0;
    public long nonApplicationWakeups = 0;
    public long applicationWakeups = 0;
    public long noUidWakeups = 0;
    public long durationSec = 0;

    public WakeupStats(String iface) {
        this.iface = iface;
    }

    /** Update durationSec with current time. */
    public void updateDuration() {
        durationSec = (SystemClock.elapsedRealtime() - creationTimeMs) / 1000;
    }

    /** Update wakeup counters for the given WakeupEvent. */
    public void countEvent(WakeupEvent ev) {
        totalWakeups++;
        switch (ev.uid) {
            case Process.ROOT_UID:
                rootWakeups++;
                break;
            case Process.SYSTEM_UID:
                systemWakeups++;
                break;
            case NO_UID:
                noUidWakeups++;
                break;
            default:
                if (ev.uid >= Process.FIRST_APPLICATION_UID) {
                    applicationWakeups++;
                } else {
                    nonApplicationWakeups++;
                }
                break;
        }
    }

    @Override
    public String toString() {
        updateDuration();
        return new StringBuilder()
                .append("WakeupStats(").append(iface)
                .append(", total: ").append(totalWakeups)
                .append(", root: ").append(rootWakeups)
                .append(", system: ").append(systemWakeups)
                .append(", apps: ").append(applicationWakeups)
                .append(", non-apps: ").append(nonApplicationWakeups)
                .append(", no uid: ").append(noUidWakeups)
                .append(", ").append(durationSec).append("s)")
                .toString();
    }
}
