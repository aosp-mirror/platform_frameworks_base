/**
 * Copyright (c) 2017, The Android Open Source Project
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

import android.os.IStatsPullerCallback;
import android.os.ParcelFileDescriptor;

/**
  * Binder interface to communicate with the statistics management service.
  * {@hide}
  */
interface IStatsManager {
    /**
     * Tell the stats daemon that the android system server is up and running.
     */
    oneway void systemRunning();

    /**
     * Tell the stats daemon that the StatsCompanionService is up and running.
     * Two-way binder call so that caller knows message received.
     */
    void statsCompanionReady();

    /**
     * Tells statsd that an anomaly may have occurred, so statsd can check whether this is so and
     * act accordingly.
     * Two-way binder call so that caller's method (and corresponding wakelocks) will linger.
     */
    void informAnomalyAlarmFired();

    /**
     * Tells statsd that it is time to poll some stats. Statsd will be responsible for determing
     * what stats to poll and initiating the polling.
     * Two-way binder call so that caller's method (and corresponding wakelocks) will linger.
     */
    void informPollAlarmFired();

    /**
     * Tells statsd that it is time to handle periodic alarms. Statsd will be responsible for
     * determing what alarm subscriber to trigger.
     * Two-way binder call so that caller's method (and corresponding wakelocks) will linger.
     */
    void informAlarmForSubscriberTriggeringFired();

    /**
     * Tells statsd that the device is about to shutdown.
     */
    void informDeviceShutdown();

    /**
     * Inform statsd about a file descriptor for a pipe through which we will pipe version
     * and package information for each uid.
     * Versions and package information are supplied via UidData proto where info for each app
     * is captured in its own element of a repeated ApplicationInfo message.
     */
    oneway void informAllUidData(in ParcelFileDescriptor fd);

    /**
     * Inform statsd what the uid, version, version_string, and installer are for one app that was
     * updated.
     */
    oneway void informOnePackage(in String app, in int uid, in long version,
        in String version_string, in String installer);

    /**
     * Inform stats that an app was removed.
     */
    oneway void informOnePackageRemoved(in String app, in int uid);

    /**
     * Fetches data for the specified configuration key. Returns a byte array representing proto
     * wire-encoded of ConfigMetricsReportList.
     *
     * Requires Manifest.permission.DUMP.
     */
    byte[] getData(in long key, in String packageName);

    /**
     * Fetches metadata across statsd. Returns byte array representing wire-encoded proto.
     *
     * Requires Manifest.permission.DUMP.
     */
    byte[] getMetadata(in String packageName);

    /**
     * Sets a configuration with the specified config key and subscribes to updates for this
     * configuration key. Broadcasts will be sent if this configuration needs to be collected.
     * The configuration must be a wire-encoded StatsdConfig. The receiver for this data is
     * registered in a separate function.
     *
     * Requires Manifest.permission.DUMP.
     */
    void addConfiguration(in long configKey, in byte[] config, in String packageName);

    /**
     * Registers the given pending intent for this config key. This intent is invoked when the
     * memory consumed by the metrics for this configuration approach the pre-defined limits. There
     * can be at most one listener per config key.
     *
     * Requires Manifest.permission.DUMP.
     */
    void setDataFetchOperation(long configKey, in IBinder intentSender, in String packageName);

    /**
     * Removes the data fetch operation for the specified configuration.
     *
     * Requires Manifest.permission.DUMP.
     */
    void removeDataFetchOperation(long configKey, in String packageName);

    /**
     * Registers the given pending intent for this packagename. This intent is invoked when the
     * active status of any of the configs sent by this package changes and will contain a list of
     * config ids that are currently active. It also returns the list of configs that are currently
     * active. There can be at most one active configs changed listener per package.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    long[] setActiveConfigsChangedOperation(in IBinder intentSender, in String packageName);

    /**
     * Removes the active configs changed operation for the specified package name.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS.
     */
    void removeActiveConfigsChangedOperation(in String packageName);

    /**
     * Removes the configuration with the matching config key. No-op if this config key does not
     * exist.
     *
     * Requires Manifest.permission.DUMP.
     */
    void removeConfiguration(in long configKey, in String packageName);

    /**
     * Set the IIntentSender (i.e. PendingIntent) to be used when broadcasting subscriber
     * information to the given subscriberId within the given config.
     *
     * Suppose that the calling uid has added a config with key configKey, and that in this config
     * it is specified that when a particular anomaly is detected, a broadcast should be sent to
     * a BroadcastSubscriber with id subscriberId. This function links the given intentSender with
     * that subscriberId (for that config), so that this intentSender is used to send the broadcast
     * when the anomaly is detected.
     *
     * This function can only be called by the owner (uid) of the config. It must be called each
     * time statsd starts. Later calls overwrite previous calls; only one intentSender is stored.
     *
     * intentSender must be convertible into an IntentSender using IntentSender(IBinder)
     * and cannot be null.
     *
     * Requires Manifest.permission.DUMP.
     */
    void setBroadcastSubscriber(long configKey, long subscriberId, in IBinder intentSender,
                                in String packageName);

    /**
     * Undoes setBroadcastSubscriber() for the (configKey, subscriberId) pair.
     * Any broadcasts associated with subscriberId will henceforth not be sent.
     * No-op if this (configKey, subsriberId) pair was not associated with an IntentSender.
     *
     * Requires Manifest.permission.DUMP.
     */
    void unsetBroadcastSubscriber(long configKey, long subscriberId, in String packageName);

    /**
     * Apps can send an atom via this application breadcrumb with the specified label and state for
     * this label. This allows building custom metrics and predicates.
     */
    void sendAppBreadcrumbAtom(int label, int state);

    /**
     * Registers a puller callback function that, when invoked, pulls the data
     * for the specified vendor atom tag.
     *
     * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS
     */
    oneway void registerPullerCallback(int atomTag, IStatsPullerCallback pullerCallback,
                                       String packageName);

   /**
    * Unregisters a puller callback function for the given vendor atom.
    *
    * Requires Manifest.permission.DUMP and Manifest.permission.PACKAGE_USAGE_STATS
    */
   oneway void unregisterPullerCallback(int atomTag, String packageName);

    /**
     * The install requires staging.
     */
    const int FLAG_REQUIRE_STAGING = 0x01;

    /**
     * Rollback is enabled with this install.
     */
    const int FLAG_ROLLBACK_ENABLED = 0x02;

    /**
     * Requires low latency monitoring.
     */
    const int FLAG_REQUIRE_LOW_LATENCY_MONITOR = 0x04;

    /**
     * Logs an event for binary push for module updates.
     */
     oneway void sendBinaryPushStateChangedAtom(in String trainName, in long trainVersionCode,
         in int options, in int state, in long[] experimentId);

    /**
     * Logs an event for watchdog rollbacks.
     */
     oneway void sendWatchdogRollbackOccurredAtom(in int rollbackType, in String packageName,
         in long packageVersionCode);

    /**
     * Returns the most recently registered experiment IDs.
     */
    long[] getRegisteredExperimentIds();
}
