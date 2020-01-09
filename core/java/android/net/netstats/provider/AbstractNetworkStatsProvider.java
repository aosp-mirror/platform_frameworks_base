/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.netstats.provider;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.NetworkStats;

/**
 * A base class that allows external modules to implement a custom network statistics provider.
 * @hide
 */
@SystemApi
public abstract class AbstractNetworkStatsProvider {
    /**
     * A value used by {@link #setLimit} and {@link #setAlert} indicates there is no limit.
     */
    public static final int QUOTA_UNLIMITED = -1;

    /**
     * Called by {@code NetworkStatsService} when global polling is needed. Custom
     * implementation of providers MUST respond to it by calling
     * {@link NetworkStatsProviderCallback#onStatsUpdated} within one minute. Responding
     * later than this may cause the stats to be dropped.
     *
     * @param token a positive number identifying the new state of the system under which
     *              {@link NetworkStats} have to be gathered from now on. When this is called,
     *              custom implementations of providers MUST report the latest stats with the
     *              previous token, under which stats were being gathered so far.
     */
    public abstract void requestStatsUpdate(int token);

    /**
     * Called by {@code NetworkStatsService} when setting the interface quota for the specified
     * upstream interface. When this is called, the custom implementation should block all egress
     * packets on the {@code iface} associated with the provider when {@code quotaBytes} bytes have
     * been reached, and MUST respond to it by calling
     * {@link NetworkStatsProviderCallback#onLimitReached()}.
     *
     * @param iface the interface requiring the operation.
     * @param quotaBytes the quota defined as the number of bytes, starting from zero and counting
     *                   from now. A value of {@link #QUOTA_UNLIMITED} indicates there is no limit.
     */
    public abstract void setLimit(@NonNull String iface, long quotaBytes);

    /**
     * Called by {@code NetworkStatsService} when setting the alert bytes. Custom implementations
     * MUST call {@link NetworkStatsProviderCallback#onAlertReached()} when {@code quotaBytes} bytes
     * have been reached. Unlike {@link #setLimit(String, long)}, the custom implementation should
     * not block all egress packets.
     *
     * @param quotaBytes the quota defined as the number of bytes, starting from zero and counting
     *                   from now. A value of {@link #QUOTA_UNLIMITED} indicates there is no alert.
     */
    public abstract void setAlert(long quotaBytes);
}
