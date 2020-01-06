/**
 * Copyright (c) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.app.PendingIntent;

/**
  * Binder interface to communicate with the Java-based statistics service helper.
  * Contains parcelable objects available only in Java.
  * {@hide}
  */
interface IStatsManagerService {

    /**
     * Registers the given pending intent for this config key. This intent is invoked when the
     * memory consumed by the metrics for this configuration approach the pre-defined limits. There
     * can be at most one listener per config key.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void setDataFetchOperation(long configId, in PendingIntent pendingIntent,
        in String packageName);

    /**
     * Removes the data fetch operation for the specified configuration.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void removeDataFetchOperation(long configId, in String packageName);

    /**
     * Registers the given pending intent for this packagename. This intent is invoked when the
     * active status of any of the configs sent by this package changes and will contain a list of
     * config ids that are currently active. It also returns the list of configs that are currently
     * active. There can be at most one active configs changed listener per package.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    long[] setActiveConfigsChangedOperation(in PendingIntent pendingIntent, in String packageName);

    /**
     * Removes the active configs changed operation for the specified package name.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void removeActiveConfigsChangedOperation(in String packageName);

    /**
     * Set the PendingIntent to be used when broadcasting subscriber
     * information to the given subscriberId within the given config.
     *
     * Suppose that the calling uid has added a config with key configKey, and that in this config
     * it is specified that when a particular anomaly is detected, a broadcast should be sent to
     * a BroadcastSubscriber with id subscriberId. This function links the given pendingIntent with
     * that subscriberId (for that config), so that this pendingIntent is used to send the broadcast
     * when the anomaly is detected.
     *
     * This function can only be called by the owner (uid) of the config. It must be called each
     * time statsd starts. Later calls overwrite previous calls; only one PendingIntent is stored.
     *
     * Requires Manifest.permission.DUMP.
     */
    void setBroadcastSubscriber(long configKey, long subscriberId, in PendingIntent pendingIntent,
                                in String packageName);
}