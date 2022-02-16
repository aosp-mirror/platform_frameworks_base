/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.net;

import android.annotation.NonNull;
import android.net.NetworkStats;
import android.net.NetworkTemplate;

public abstract class NetworkStatsManagerInternal {
    /** Return network layer usage total for traffic that matches template. */
    public abstract long getNetworkTotalBytes(NetworkTemplate template, long start, long end);

    /** Return network layer usage per-UID for traffic that matches template. */
    public abstract NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end);

    /** Mark given UID as being in foreground for stats purposes. */
    public abstract void setUidForeground(int uid, boolean uidForeground);

    /** Advise persistance threshold; may be overridden internally. */
    public abstract void advisePersistThreshold(long thresholdBytes);

    /** Force update of statistics. */
    public abstract void forceUpdate();

    /**
     * Set the warning and limit to all registered custom network stats providers.
     * Note that invocation of any interface will be sent to all providers.
     */
    public abstract void setStatsProviderWarningAndLimitAsync(@NonNull String iface, long warning,
            long limit);
}
