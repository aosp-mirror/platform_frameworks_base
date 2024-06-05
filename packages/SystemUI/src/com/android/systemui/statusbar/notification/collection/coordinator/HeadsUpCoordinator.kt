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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.app.Notification.GROUP_ALERT_SUMMARY
import android.util.ArrayMap
import android.util.ArraySet
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.IncomingHeader
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.statusbar.notification.stack.BUCKET_HEADS_UP
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Coordinates heads up notification (HUN) interactions with the notification pipeline based on
 * the HUN state reported by the [HeadsUpManager]. In this class we only consider one
 * notification, in particular the [HeadsUpManager.getTopEntry], to be HeadsUpping at a
 * time even though other notifications may be queued to heads up next.
 *
 * The current HUN, but not HUNs that are queued to heads up, will be:
 * - Lifetime extended until it's no longer heads upping.
 * - Promoted out of its group if it's a child of a group.
 * - In the HeadsUpCoordinatorSection. Ordering is configured in [NotifCoordinators].
 * - Removed from HeadsUpManager if it's removed from the NotificationCollection.
 *
 * Note: The inflation callback in [PreparationCoordinator] handles showing HUNs.
 */
@CoordinatorScope
class HeadsUpCoordinator @Inject constructor(
    private val mLogger: HeadsUpCoordinatorLogger,
    private val mSystemClock: SystemClock,
    private val mHeadsUpManager: HeadsUpManager,
    private val mHeadsUpViewBinder: HeadsUpViewBinder,
    private val mVisualInterruptionDecisionProvider: VisualInterruptionDecisionProvider,
    private val mRemoteInputManager: NotificationRemoteInputManager,
    private val mLaunchFullScreenIntentProvider: LaunchFullScreenIntentProvider,
    private val mFlags: NotifPipelineFlags,
    @IncomingHeader private val mIncomingHeaderController: NodeController,
    @Main private val mExecutor: DelayableExecutor
) : Coordinator {
    private val mEntriesBindingUntil = ArrayMap<String, Long>()
    private val mEntriesUpdateTimes = ArrayMap<String, Long>()
    private val mFSIUpdateCandidates = ArrayMap<String, Long>()
    private var mEndLifetimeExtension: OnEndLifetimeExtensionCallback? = null
    private lateinit var mNotifPipeline: NotifPipeline
    private var mNow: Long = -1
    private val mPostedEntries = LinkedHashMap<String, PostedEntry>()

    // notifs we've extended the lifetime for with cancellation callbacks
    private val mNotifsExtendingLifetime = ArrayMap<NotificationEntry, Runnable?>()

    override fun attach(pipeline: NotifPipeline) {
        mNotifPipeline = pipeline
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener)
        pipeline.addCollectionListener(mNotifCollectionListener)
        pipeline.addOnBeforeTransformGroupsListener(::onBeforeTransformGroups)
        pipeline.addOnBeforeFinalizeFilterListener(::onBeforeFinalizeFilter)
        pipeline.addPromoter(mNotifPromoter)
        pipeline.addNotificationLifetimeExtender(mLifetimeExtender)
        mRemoteInputManager.addActionPressListener(mActionPressListener)
    }

    private fun onHeadsUpViewBound(entry: NotificationEntry) {
        mHeadsUpManager.showNotification(entry)
        mEntriesBindingUntil.remove(entry.key)
    }

    /**
     * Once the pipeline starts running, we can look through posted entries and quickly process
     * any that don't have groups, and thus will never gave a group heads up edge case.
     */
    fun onBeforeTransformGroups(list: List<ListEntry>) {
        mNow = mSystemClock.currentTimeMillis()
        if (mPostedEntries.isEmpty()) {
            return
        }
        // Process all non-group adds/updates
        mHeadsUpManager.modifyHuns { hunMutator ->
            mPostedEntries.values.toList().forEach { posted ->
                if (!posted.entry.sbn.isGroup) {
                    handlePostedEntry(posted, hunMutator, "non-group")
                    mPostedEntries.remove(posted.key)
                }
            }
        }
    }

    /**
     * Once we have a nearly final shade list (not including what's pruned for inflation reasons),
     * we know that stability and [NotifPromoter]s have been applied, so we can use the location of
     * notifications in this list to determine what kind of group heads up behavior should happen.
     */
    fun onBeforeFinalizeFilter(list: List<ListEntry>) = mHeadsUpManager.modifyHuns { hunMutator ->
        // Nothing to do if there are no other adds/updates
        if (mPostedEntries.isEmpty()) {
            return@modifyHuns
        }
        // Calculate a bunch of information about the logical group and the locations of group
        // entries in the nearly-finalized shade list.  These may be used in the per-group loop.
        val postedEntriesByGroup = mPostedEntries.values.groupBy { it.entry.sbn.groupKey }
        val logicalMembersByGroup = mNotifPipeline.allNotifs.asSequence()
            .filter { postedEntriesByGroup.contains(it.sbn.groupKey) }
            .groupBy { it.sbn.groupKey }
        val groupLocationsByKey: Map<String, GroupLocation> by lazy { getGroupLocationsByKey(list) }
        mLogger.logEvaluatingGroups(postedEntriesByGroup.size)
        // For each group, determine which notification(s) for a group should heads up.
        postedEntriesByGroup.forEach { (groupKey, postedEntries) ->
            // get and classify the logical members
            val logicalMembers = logicalMembersByGroup[groupKey] ?: emptyList()
            val logicalSummary = logicalMembers.find { it.sbn.notification.isGroupSummary }

            // Report the start of this group's evaluation
            mLogger.logEvaluatingGroup(groupKey, postedEntries.size, logicalMembers.size)

            // If there is no logical summary, then there is no heads up to transfer
            if (logicalSummary == null) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "logical-summary-missing")
                }
                return@forEach
            }

            // If summary isn't wanted to be heads up, then there is no heads up to transfer
            if (!isGoingToShowHunStrict(logicalSummary)) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "logical-summary-not-heads-up")
                }
                return@forEach
            }

            // The group is heads up! Overall goals:
            //  - Maybe transfer its heads up to a child
            //  - Also let any/all newly heads up children still heads up
            var childToReceiveParentHeadsUp: NotificationEntry?
            var targetType = "undefined"

            // If the parent is heads up, always look at the posted notification with the newest
            // 'when', and if it is isolated with GROUP_ALERT_SUMMARY, then it should receive the
            // parent's heads up.
            childToReceiveParentHeadsUp =
                findHeadsUpOverride(postedEntries, groupLocationsByKey::getLocation)
            if (childToReceiveParentHeadsUp != null) {
                targetType = "headsUpOverride"
            }

            // If the summary is Detached and we have not picked a receiver of the heads up, then we
            // need to look for the best child to heads up in place of the summary.
            val isSummaryAttached = groupLocationsByKey.contains(logicalSummary.key)
            if (!isSummaryAttached && childToReceiveParentHeadsUp == null) {
                childToReceiveParentHeadsUp =
                    findBestTransferChild(logicalMembers, groupLocationsByKey::getLocation)
                if (childToReceiveParentHeadsUp != null) {
                    targetType = "bestChild"
                }
            }

            // If there is no child to receive the parent heads up, then just handle the posted
            // entries and return.
            if (childToReceiveParentHeadsUp == null) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "no-transfer-target")
                }
                return@forEach
            }

            // At this point we just need to initiate the transfer
            val summaryUpdate = mPostedEntries[logicalSummary.key]

            // Because we now know for certain that some child is going to heads up for this summary
            // (as we have found a child to transfer the heads up to), mark the group as having
            // interrupted. This will allow us to know in the future that the "should heads up"
            // state of this group has already been handled, just not via the summary entry itself.
            logicalSummary.setInterruption()
            mLogger.logSummaryMarkedInterrupted(logicalSummary.key, childToReceiveParentHeadsUp.key)

            // If the summary was not attached, then remove the heads up from the detached summary.
            // Otherwise we can simply ignore its posted update.
            if (!isSummaryAttached) {
                val summaryUpdateForRemoval = summaryUpdate?.also {
                    it.shouldHeadsUpEver = false
                } ?: PostedEntry(
                        logicalSummary,
                        wasAdded = false,
                        wasUpdated = false,
                        shouldHeadsUpEver = false,
                        shouldHeadsUpAgain = false,
                        isHeadsUpEntry = mHeadsUpManager.isHeadsUpEntry(logicalSummary.key),
                        isBinding = isEntryBinding(logicalSummary),
                )
                // If we transfer the heads up notification and the summary isn't even attached,
                // that means we should ensure the summary is no longer a heads up notification,
                // so we remove it here.
                handlePostedEntry(
                        summaryUpdateForRemoval,
                        hunMutator,
                        scenario = "detached-summary-remove-heads-up")
            } else if (summaryUpdate != null) {
                mLogger.logPostedEntryWillNotEvaluate(
                        summaryUpdate,
                        reason = "attached-summary-transferred")
            }

            // Handle all posted entries -- if the child receiving the parent's heads up is in the
            // list, then set its flags to ensure it heads up.
            var didHeadsUpChildToReceiveParentHeadsUp = false
            postedEntries.asSequence()
                    .filter { it.key != logicalSummary.key }
                    .forEach { postedEntry ->
                        if (childToReceiveParentHeadsUp.key == postedEntry.key) {
                            // Update the child's posted update so that it
                            postedEntry.shouldHeadsUpEver = true
                            postedEntry.shouldHeadsUpAgain = true
                            handlePostedEntry(
                                    postedEntry,
                                    hunMutator,
                                    scenario = "child-heads-up-transfer-target-$targetType")
                            didHeadsUpChildToReceiveParentHeadsUp = true
                        } else {
                            handlePostedEntry(
                                    postedEntry,
                                    hunMutator,
                                    scenario = "child-heads-up-non-target")
                        }
                    }

            // If the child receiving the heads up notification was not updated on this tick
            // (which can happen in a standard heads up transfer scenario), then construct an update
            // so that we can apply it.
            if (!didHeadsUpChildToReceiveParentHeadsUp) {
                val posted = PostedEntry(
                        childToReceiveParentHeadsUp,
                        wasAdded = false,
                        wasUpdated = false,
                        shouldHeadsUpEver = true,
                        shouldHeadsUpAgain = true,
                        isHeadsUpEntry =
                                mHeadsUpManager.isHeadsUpEntry(childToReceiveParentHeadsUp.key),
                        isBinding = isEntryBinding(childToReceiveParentHeadsUp),
                )
                handlePostedEntry(
                        posted,
                        hunMutator,
                        scenario = "non-posted-child-heads-up-transfer-target-$targetType")
            }
        }
        // After this method runs, all posted entries should have been handled (or skipped).
        mPostedEntries.clear()

        // Also take this opportunity to clean up any stale entry update times
        cleanUpEntryTimes()
    }

    /**
     * Find the posted child with the newest when, and return it if it is isolated and has
     * GROUP_ALERT_SUMMARY so that it can be heads uped.
     */
    private fun findHeadsUpOverride(
        postedEntries: List<PostedEntry>,
        locationLookupByKey: (String) -> GroupLocation,
    ): NotificationEntry? = postedEntries.asSequence()
        .filter { posted -> !posted.entry.sbn.notification.isGroupSummary }
        .sortedBy { posted ->
            -posted.entry.sbn.notification.getWhen()
        }
        .firstOrNull()
        ?.let { posted ->
            posted.entry.takeIf { entry ->
                locationLookupByKey(entry.key) == GroupLocation.Isolated &&
                        entry.sbn.notification.groupAlertBehavior == GROUP_ALERT_SUMMARY
            }
        }

    /**
     * Of children which are attached, look for the child to receive the notification:
     * First prefer children which were updated, then looking for the ones with the newest 'when'
     */
    private fun findBestTransferChild(
        logicalMembers: List<NotificationEntry>,
        locationLookupByKey: (String) -> GroupLocation,
    ): NotificationEntry? = logicalMembers.asSequence()
        .filter { !it.sbn.notification.isGroupSummary }
        .filter { locationLookupByKey(it.key) != GroupLocation.Detached }
        .sortedWith(compareBy(
            { !mPostedEntries.contains(it.key) },
            { -it.sbn.notification.getWhen() },
        ))
        .firstOrNull()

    private fun getGroupLocationsByKey(list: List<ListEntry>): Map<String, GroupLocation> =
        mutableMapOf<String, GroupLocation>().also { map ->
            list.forEach { topLevelEntry ->
                when (topLevelEntry) {
                    is NotificationEntry -> map[topLevelEntry.key] = GroupLocation.Isolated
                    is GroupEntry -> {
                        topLevelEntry.summary?.let { summary ->
                            map[summary.key] = GroupLocation.Summary
                        }
                        topLevelEntry.children.forEach { child ->
                            map[child.key] = GroupLocation.Child
                        }
                    }
                    else -> error("unhandled type $topLevelEntry")
                }
            }
        }

    private fun handlePostedEntry(posted: PostedEntry, hunMutator: HunMutator, scenario: String) {
        mLogger.logPostedEntryWillEvaluate(posted, scenario)
        if (posted.wasAdded) {
            if (posted.shouldHeadsUpEver) {
                bindForAsyncHeadsUp(posted)
            }
        } else {
            if (posted.isHeadsUpAlready) {
                // NOTE: This might be because we're showing heads up (i.e. tracked by
                // HeadsUpManager) OR it could be because we're binding, and that will affect the
                // next step.
                if (posted.shouldHeadsUpEver) {
                    // If showing heads up, we need to post an update. Otherwise we're still
                    // binding, and we can just let that finish.
                    if (posted.isHeadsUpEntry) {
                        hunMutator.updateNotification(posted.key, posted.shouldHeadsUpAgain)
                    }
                } else {
                    if (posted.isHeadsUpEntry) {
                        // We don't want this to be interrupting anymore, let's remove it
                        hunMutator.removeNotification(posted.key, false /*removeImmediately*/)
                    } else {
                        // Don't let the bind finish
                        cancelHeadsUpBind(posted.entry)
                    }
                }
            } else if (posted.shouldHeadsUpEver && posted.shouldHeadsUpAgain) {
                // This notification was updated to be heads up, show it!
                bindForAsyncHeadsUp(posted)
            }
        }
    }

    private fun cancelHeadsUpBind(entry: NotificationEntry) {
        mEntriesBindingUntil.remove(entry.key)
        mHeadsUpViewBinder.abortBindCallback(entry)
    }

    private fun bindForAsyncHeadsUp(posted: PostedEntry) {
        // TODO: Add a guarantee to bindHeadsUpView of some kind of callback if the bind is
        //  cancelled so that we don't need to have this sad timeout hack.
        mEntriesBindingUntil[posted.key] = mNow + BIND_TIMEOUT
        mHeadsUpViewBinder.bindHeadsUpView(posted.entry, this::onHeadsUpViewBound)
    }

    private val mNotifCollectionListener = object : NotifCollectionListener {
        /**
         * Notification was just added and if it should heads up, bind the view and then show it.
         */
        override fun onEntryAdded(entry: NotificationEntry) {
            // First check whether this notification should launch a full screen intent, and
            // launch it if needed.
            val fsiDecision =
                mVisualInterruptionDecisionProvider.makeUnloggedFullScreenIntentDecision(entry)
            mVisualInterruptionDecisionProvider.logFullScreenIntentDecision(fsiDecision)
            if (fsiDecision.shouldInterrupt) {
                mLaunchFullScreenIntentProvider.launchFullScreenIntent(entry)
            } else if (fsiDecision.wouldInterruptWithoutDnd) {
                // If DND was the only reason this entry was suppressed, note it for potential
                // reconsideration on later ranking updates.
                addForFSIReconsideration(entry, mSystemClock.currentTimeMillis())
            }

            // makeAndLogHeadsUpDecision includes check for whether this notification should be
            // filtered
            val shouldHeadsUpEver =
                mVisualInterruptionDecisionProvider.makeAndLogHeadsUpDecision(entry).shouldInterrupt
            mPostedEntries[entry.key] = PostedEntry(
                entry,
                wasAdded = true,
                wasUpdated = false,
                shouldHeadsUpEver = shouldHeadsUpEver,
                shouldHeadsUpAgain = true,
                isHeadsUpEntry = false,
                isBinding = false,
            )

            // Record the last updated time for this key
            setUpdateTime(entry, mSystemClock.currentTimeMillis())
        }

        /**
         * Notification could've updated to be heads up or not heads up. Even if it did update to
         * heads up, if the notification specified that it only wants to heads up once, don't heads
         * up again.
         */
        override fun onEntryUpdated(entry: NotificationEntry) {
            val shouldHeadsUpEver =
                mVisualInterruptionDecisionProvider.makeAndLogHeadsUpDecision(entry).shouldInterrupt
            val shouldHeadsUpAgain = shouldHunAgain(entry)
            val isHeadsUpEntry = mHeadsUpManager.isHeadsUpEntry(entry.key)
            val isBinding = isEntryBinding(entry)
            val posted = mPostedEntries.compute(entry.key) { _, value ->
                value?.also { update ->
                    update.wasUpdated = true
                    update.shouldHeadsUpEver = shouldHeadsUpEver
                    update.shouldHeadsUpAgain = update.shouldHeadsUpAgain || shouldHeadsUpAgain
                    update.isHeadsUpEntry = isHeadsUpEntry
                    update.isBinding = isBinding
                } ?: PostedEntry(
                    entry,
                    wasAdded = false,
                    wasUpdated = true,
                    shouldHeadsUpEver = shouldHeadsUpEver,
                    shouldHeadsUpAgain = shouldHeadsUpAgain,
                    isHeadsUpEntry = isHeadsUpEntry,
                    isBinding = isBinding,
                )
            }
            // Handle cancelling heads up here, rather than in the OnBeforeFinalizeFilter, so that
            // work can be done before the ShadeListBuilder is run. This prevents re-entrant
            // behavior between this Coordinator, HeadsUpManager, and VisualStabilityManager.
            if (posted?.shouldHeadsUpEver == false) {
                if (posted.isHeadsUpEntry) {
                    // We don't want this to be interrupting anymore, let's remove it
                    mHeadsUpManager.removeNotification(posted.key, false /*removeImmediately*/)
                } else if (posted.isBinding) {
                    // Don't let the bind finish
                    cancelHeadsUpBind(posted.entry)
                }
            }

            // Update last updated time for this entry
            setUpdateTime(entry, mSystemClock.currentTimeMillis())
        }

        /**
         * Stop showing as heads up once removed from the notification collection
         */
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            mPostedEntries.remove(entry.key)
            mEntriesUpdateTimes.remove(entry.key)
            cancelHeadsUpBind(entry)
            val entryKey = entry.key
            if (mHeadsUpManager.isHeadsUpEntry(entryKey)) {
                // TODO: This should probably know the RemoteInputCoordinator's conditions,
                //  or otherwise reference that coordinator's state, rather than replicate its logic
                val removeImmediatelyForRemoteInput = (mRemoteInputManager.isSpinning(entryKey) &&
                        !NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY)
                mHeadsUpManager.removeNotification(entry.key, removeImmediatelyForRemoteInput)
            }
        }

        override fun onEntryCleanUp(entry: NotificationEntry) {
            mHeadsUpViewBinder.abortBindCallback(entry)
        }

        /**
         * Identify notifications whose heads-up state changes when the notification rankings are
         * updated, and have those changed notifications heads up if necessary.
         *
         * This method will occur after any operations in onEntryAdded or onEntryUpdated, so any
         * handling of ranking changes needs to take into account that we may have just made a
         * PostedEntry for some of these notifications.
         */
        override fun onRankingApplied() {
            // Because a ranking update may cause some notifications that are no longer (or were
            // never) in mPostedEntries to need to heads up, we need to check every notification
            // known to the pipeline.
            for (entry in mNotifPipeline.allNotifs) {
                // Only consider entries that are recent enough, since we want to apply a fairly
                // strict threshold for when an entry should be updated via only ranking and not an
                // app-provided notification update.
                if (!isNewEnoughForRankingUpdate(entry)) continue

                // The only entries we consider heads up for here are entries that have never
                // interrupted and that now say they should heads up or FSI; if they've heads uped in
                // the past, we don't want to incorrectly heads up a second time if there wasn't an
                // explicit notification update.
                if (entry.hasInterrupted()) continue

                // Before potentially allowing heads-up, check for any candidates for a FSI launch.
                // Any entry that is a candidate meets two criteria:
                //   - was suppressed from FSI launch only by a DND suppression
                //   - is within the recency window for reconsideration
                // If any of these entries are no longer suppressed, launch the FSI now.
                if (isCandidateForFSIReconsideration(entry)) {
                    val decision =
                        mVisualInterruptionDecisionProvider.makeUnloggedFullScreenIntentDecision(
                            entry
                        )
                    if (decision.shouldInterrupt) {
                        // Log both the launch of the full screen and also that this was via a
                        // ranking update, and finally revoke candidacy for FSI reconsideration
                        mLogger.logEntryUpdatedToFullScreen(entry.key, decision.logReason)
                        mVisualInterruptionDecisionProvider.logFullScreenIntentDecision(decision)
                        mLaunchFullScreenIntentProvider.launchFullScreenIntent(entry)
                        mFSIUpdateCandidates.remove(entry.key)

                        // if we launch the FSI then this is no longer a candidate for HUN
                        continue
                    } else if (decision.wouldInterruptWithoutDnd) {
                        // decision has not changed; no need to log
                    } else {
                        // some other condition is now blocking FSI; log that and revoke candidacy
                        // for FSI reconsideration
                        mLogger.logEntryDisqualifiedFromFullScreen(entry.key, decision.logReason)
                        mVisualInterruptionDecisionProvider.logFullScreenIntentDecision(decision)
                        mFSIUpdateCandidates.remove(entry.key)
                    }
                }

                // The cases where we should consider this notification to be updated:
                // - if this entry is not present in PostedEntries, and is now in a shouldHeadsUp
                //   state
                // - if it is present in PostedEntries and the previous state of shouldHeadsUp
                //   differs from the updated one
                val decision =
                    mVisualInterruptionDecisionProvider.makeUnloggedHeadsUpDecision(entry)
                val shouldHeadsUpEver = decision.shouldInterrupt
                val postedShouldHeadsUpEver = mPostedEntries[entry.key]?.shouldHeadsUpEver ?: false
                val shouldUpdateEntry = postedShouldHeadsUpEver != shouldHeadsUpEver

                if (shouldUpdateEntry) {
                    mLogger.logEntryUpdatedByRanking(
                        entry.key,
                        shouldHeadsUpEver,
                        decision.logReason
                    )
                    onEntryUpdated(entry)
                }
            }
        }
    }

    /**
     * Checks whether an update for a notification warrants an heads up for the user.
     */
    private fun shouldHunAgain(entry: NotificationEntry): Boolean {
        return (!entry.hasInterrupted() ||
                (entry.sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) == 0)
    }

    /**
     * Sets the updated time for the given entry to the specified time.
     */
    @VisibleForTesting
    fun setUpdateTime(entry: NotificationEntry, time: Long) {
        mEntriesUpdateTimes[entry.key] = time
    }

    /**
     * Add the entry to the list of entries potentially considerable for FSI ranking update, where
     * the provided time is the time the entry was added.
     */
    @VisibleForTesting
    fun addForFSIReconsideration(entry: NotificationEntry, time: Long) {
        mFSIUpdateCandidates[entry.key] = time
    }

    /**
     * Checks whether the entry is new enough to be updated via ranking update.
     * We want to avoid updating an entry too long after it was originally posted/updated when we're
     * only reacting to a ranking change, as relevant ranking updates are expected to come in
     * fairly soon after the posting of a notification.
     */
    private fun isNewEnoughForRankingUpdate(entry: NotificationEntry): Boolean {
        // If we don't have an update time for this key, default to "too old"
        if (!mEntriesUpdateTimes.containsKey(entry.key)) return false

        val updateTime = mEntriesUpdateTimes[entry.key] ?: return false
        return (mSystemClock.currentTimeMillis() - updateTime) <= MAX_RANKING_UPDATE_DELAY_MS
    }

    /**
     * Checks whether the entry is present new enough for reconsideration for full screen launch.
     * The time window is the same as for ranking update, but this doesn't allow a potential update
     * to an entry with full screen intent to count for timing purposes.
     */
    private fun isCandidateForFSIReconsideration(entry: NotificationEntry): Boolean {
        val addedTime = mFSIUpdateCandidates[entry.key] ?: return false
        return (mSystemClock.currentTimeMillis() - addedTime) <= MAX_RANKING_UPDATE_DELAY_MS
    }

    private fun cleanUpEntryTimes() {
        // Because we won't update entries that are older than this amount of time anyway, clean
        // up any entries that are too old to notify from both the general and FSI specific lists.

        // Anything newer than this time is still within the window.
        val timeThreshold = mSystemClock.currentTimeMillis() - MAX_RANKING_UPDATE_DELAY_MS

        val toRemove = ArraySet<String>()
        for ((key, updateTime) in mEntriesUpdateTimes) {
            if (updateTime == null || timeThreshold > updateTime) {
                toRemove.add(key)
            }
        }
        mEntriesUpdateTimes.removeAll(toRemove)

        val toRemoveForFSI = ArraySet<String>()
        for ((key, addedTime) in mFSIUpdateCandidates) {
            if (addedTime == null || timeThreshold > addedTime) {
                toRemoveForFSI.add(key)
            }
        }
        mFSIUpdateCandidates.removeAll(toRemoveForFSI)
    }

    /**
     * When an action is pressed on a notification, make sure we don't lifetime-extend it in the
     * future by informing the HeadsUpManager, and make sure we don't keep lifetime-extending it if
     * we already are.
     *
     * @see HeadsUpManager.setUserActionMayIndirectlyRemove
     * @see HeadsUpManager.canRemoveImmediately
     */
    private val mActionPressListener = Consumer<NotificationEntry> { entry ->
        mHeadsUpManager.setUserActionMayIndirectlyRemove(entry)
        mExecutor.execute { endNotifLifetimeExtensionIfExtended(entry) }
    }

    private val mLifetimeExtender = object : NotifLifetimeExtender {
        override fun getName() = TAG

        override fun setCallback(callback: OnEndLifetimeExtensionCallback) {
            mEndLifetimeExtension = callback
        }

        override fun maybeExtendLifetime(entry: NotificationEntry, reason: Int): Boolean {
            if (mHeadsUpManager.canRemoveImmediately(entry.key)) {
                return false
            }
            if (isSticky(entry)) {
                val removeAfterMillis = mHeadsUpManager.getEarliestRemovalTime(entry.key)
                mNotifsExtendingLifetime[entry] = mExecutor.executeDelayed({
                    mHeadsUpManager.removeNotification(entry.key, /* releaseImmediately */ true)
                }, removeAfterMillis)
            } else {
                mExecutor.execute {
                    mHeadsUpManager.removeNotification(entry.key, /* releaseImmediately */ false)
                }
                mNotifsExtendingLifetime[entry] = null
            }
            return true
        }

        override fun cancelLifetimeExtension(entry: NotificationEntry) {
            mNotifsExtendingLifetime.remove(entry)?.run()
        }
    }

    private val mNotifPromoter = object : NotifPromoter(TAG) {
        override fun shouldPromoteToTopLevel(entry: NotificationEntry): Boolean =
            isGoingToShowHunNoRetract(entry)
    }

    val sectioner = object : NotifSectioner("HeadsUp", BUCKET_HEADS_UP) {
        override fun isInSection(entry: ListEntry): Boolean =
            // TODO: This check won't notice if a child of the group is going to HUN...
            isGoingToShowHunNoRetract(entry)

        override fun getComparator(): NotifComparator {
            return object : NotifComparator("HeadsUp") {
                override fun compare(o1: ListEntry, o2: ListEntry): Int =
                    mHeadsUpManager.compare(o1.representativeEntry, o2.representativeEntry)
            }
        }

        override fun getHeaderNodeController(): NodeController? =
            // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and mIncomingHeaderController
            if (RankingCoordinator.SHOW_ALL_SECTIONS) mIncomingHeaderController else null
    }

    private val mOnHeadsUpChangedListener = object : OnHeadsUpChangedListener {
        override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
            if (!isHeadsUp) {
                mNotifPromoter.invalidateList("headsUpEnded: ${entry.logKey}")
                mHeadsUpViewBinder.unbindHeadsUpView(entry)
                endNotifLifetimeExtensionIfExtended(entry)
            }
        }
    }

    private fun isSticky(entry: NotificationEntry) = mHeadsUpManager.isSticky(entry.key)

    private fun isEntryBinding(entry: ListEntry): Boolean {
        val bindingUntil = mEntriesBindingUntil[entry.key]
        return bindingUntil != null && bindingUntil >= mNow
    }

    /**
     * Whether the notification is already heads up or binding so that it can imminently heads up
     */
    private fun isAttemptingToShowHun(entry: ListEntry) =
        mHeadsUpManager.isHeadsUpEntry(entry.key) || isEntryBinding(entry)

    /**
     * Whether the notification is already heads up/binding per [isAttemptingToShowHun] OR if it
     * has been updated so that it should heads up this update.  This method is permissive because
     * it returns `true` even if the update would (in isolation of its group) cause the heads up to
     * be retracted.  This is important for not retracting transferred group heads ups.
     */
    private fun isGoingToShowHunNoRetract(entry: ListEntry) =
        mPostedEntries[entry.key]?.calculateShouldBeHeadsUpNoRetract ?: isAttemptingToShowHun(entry)

    /**
     * If the notification has been updated, then whether it should HUN in isolation, otherwise
     * defers to the already heads up/binding state of [isAttemptingToShowHun].  This method is
     * strict because any update which would revoke the heads up supersedes the current
     * heads up/binding state.
     */
    private fun isGoingToShowHunStrict(entry: ListEntry) =
        mPostedEntries[entry.key]?.calculateShouldBeHeadsUpStrict ?: isAttemptingToShowHun(entry)

    private fun endNotifLifetimeExtensionIfExtended(entry: NotificationEntry) {
        if (mNotifsExtendingLifetime.contains(entry)) {
            mNotifsExtendingLifetime.remove(entry)?.run()
            mEndLifetimeExtension?.onEndLifetimeExtension(mLifetimeExtender, entry)
        }
    }

    companion object {
        private const val TAG = "HeadsUpCoordinator"
        private const val BIND_TIMEOUT = 1000L

        // This value is set to match MAX_SOUND_DELAY_MS in NotificationRecord.
        private const val MAX_RANKING_UPDATE_DELAY_MS: Long = 2000
    }

    data class PostedEntry(
        val entry: NotificationEntry,
        val wasAdded: Boolean,
        var wasUpdated: Boolean,
        var shouldHeadsUpEver: Boolean,
        var shouldHeadsUpAgain: Boolean,
        var isHeadsUpEntry: Boolean,
        var isBinding: Boolean,
    ) {
        val key = entry.key
        val isHeadsUpAlready: Boolean
            get() = isHeadsUpEntry || isBinding
        val calculateShouldBeHeadsUpStrict: Boolean
            get() = shouldHeadsUpEver && (wasAdded || shouldHeadsUpAgain || isHeadsUpAlready)
        val calculateShouldBeHeadsUpNoRetract: Boolean
            get() = isHeadsUpAlready || (shouldHeadsUpEver && (wasAdded || shouldHeadsUpAgain))
    }
}

private enum class GroupLocation { Detached, Isolated, Summary, Child }

private fun Map<String, GroupLocation>.getLocation(key: String): GroupLocation =
    getOrDefault(key, GroupLocation.Detached)

/**
 * Invokes the given block with a [HunMutator] that defers all HUN removals. This ensures that the
 * HeadsUpManager is notified of additions before removals, which prevents a glitch where the
 * HeadsUpManager temporarily believes that nothing is heads up, causing bad re-entrant behavior.
 */
private fun <R> HeadsUpManager.modifyHuns(block: (HunMutator) -> R): R {
    val mutator = HunMutatorImpl(this)
    return block(mutator).also { mutator.commitModifications() }
}

/** Mutates the HeadsUp state of notifications. */
private interface HunMutator {
    fun updateNotification(key: String, shouldHeadsUpAgain: Boolean)
    fun removeNotification(key: String, releaseImmediately: Boolean)
}

/**
 * [HunMutator] implementation that defers removing notifications from the HeadsUpManager until
 * after additions/updates.
 */
private class HunMutatorImpl(private val headsUpManager: HeadsUpManager) : HunMutator {
    private val deferred = mutableListOf<Pair<String, Boolean>>()

    override fun updateNotification(key: String, shouldHeadsUpAgain: Boolean) {
        headsUpManager.updateNotification(key, shouldHeadsUpAgain)
    }

    override fun removeNotification(key: String, releaseImmediately: Boolean) {
        val args = Pair(key, releaseImmediately)
        deferred.add(args)
    }

    fun commitModifications() {
        deferred.forEach { (key, releaseImmediately) ->
            headsUpManager.removeNotification(key, releaseImmediately)
        }
        deferred.clear()
    }
}
