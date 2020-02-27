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
import android.os.IPullAtomCallback;

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
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void setBroadcastSubscriber(long configKey, long subscriberId, in PendingIntent pendingIntent,
                                in String packageName);

    /**
     * Undoes setBroadcastSubscriber() for the (configKey, subscriberId) pair.
     * Any broadcasts associated with subscriberId will henceforth not be sent.
     * No-op if this (configKey, subscriberId) pair was not associated with an PendingIntent.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void unsetBroadcastSubscriber(long configKey, long subscriberId, in String packageName);

    /**
     * Returns the most recently registered experiment IDs.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    long[] getRegisteredExperimentIds();

    /**
     * Fetches metadata across statsd. Returns byte array representing wire-encoded proto.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    byte[] getMetadata(in String packageName);

    /**
     * Fetches data for the specified configuration key. Returns a byte array representing proto
     * wire-encoded of ConfigMetricsReportList.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    byte[] getData(in long key, in String packageName);

    /**
     * Sets a configuration with the specified config id and subscribes to updates for this
     * configuration id. Broadcasts will be sent if this configuration needs to be collected.
     * The configuration must be a wire-encoded StatsdConfig. The receiver for this data is
     * registered in a separate function.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void addConfiguration(in long configId, in byte[] config, in String packageName);

    /**
     * Removes the configuration with the matching config id. No-op if this config id does not
     * exist.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void removeConfiguration(in long configId, in String packageName);

    /** Tell StatsManagerService to register a puller for the given atom tag with statsd. */
    oneway void registerPullAtomCallback(int atomTag, long coolDownMillis, long timeoutMillis,
            in int[] additiveFields, IPullAtomCallback pullerCallback);

    /** Tell StatsManagerService to unregister the pulller for the given atom tag from statsd. */
    oneway void unregisterPullAtomCallback(int atomTag);
}
