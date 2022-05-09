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
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.NotificationRemoteInputManager
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
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.IncomingHeader
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider
import com.android.systemui.statusbar.notification.stack.BUCKET_HEADS_UP
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
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
    private val mNotificationInterruptStateProvider: NotificationInterruptStateProvider,
    private val mRemoteInputManager: NotificationRemoteInputManager,
    @IncomingHeader private val mIncomingHeaderController: NodeController,
    @Main private val mExecutor: DelayableExecutor,
) : Coordinator {
    private val mEntriesBindingUntil = ArrayMap<String, Long>()
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
    }

    private fun onHeadsUpViewBound(entry: NotificationEntry) {
        mHeadsUpManager.showNotification(entry)
        mEntriesBindingUntil.remove(entry.key)
    }

    /**
     * Once the pipeline starts running, we can look through posted entries and quickly process
     * any that don't have groups, and thus will never gave a group alert edge case.
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
     * notifications in this list to determine what kind of group alert behavior should happen.
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
        // For each group, determine which notification(s) for a group should alert.
        postedEntriesByGroup.forEach { (groupKey, postedEntries) ->
            // get and classify the logical members
            val logicalMembers = logicalMembersByGroup[groupKey] ?: emptyList()
            val logicalSummary = logicalMembers.find { it.sbn.notification.isGroupSummary }

            // Report the start of this group's evaluation
            mLogger.logEvaluatingGroup(groupKey, postedEntries.size, logicalMembers.size)

            // If there is no logical summary, then there is no alert to transfer
            if (logicalSummary == null) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "logical-summary-missing")
                }
                return@forEach
            }

            // If summary isn't wanted to be heads up, then there is no alert to transfer
            if (!isGoingToShowHunStrict(logicalSummary)) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "logical-summary-not-alerting")
                }
                return@forEach
            }

            // The group is alerting! Overall goals:
            //  - Maybe transfer its alert to a child
            //  - Also let any/all newly alerting children still alert
            var childToReceiveParentAlert: NotificationEntry?
            var targetType = "undefined"

            // If the parent is alerting, always look at the posted notification with the newest
            // 'when', and if it is isolated with GROUP_ALERT_SUMMARY, then it should receive the
            // parent's alert.
            childToReceiveParentAlert =
                findAlertOverride(postedEntries, groupLocationsByKey::getLocation)
            if (childToReceiveParentAlert != null) {
                targetType = "alertOverride"
            }

            // If the summary is Detached and we have not picked a receiver of the alert, then we
            // need to look for the best child to alert in place of the summary.
            val isSummaryAttached = groupLocationsByKey.contains(logicalSummary.key)
            if (!isSummaryAttached && childToReceiveParentAlert == null) {
                childToReceiveParentAlert =
                    findBestTransferChild(logicalMembers, groupLocationsByKey::getLocation)
                if (childToReceiveParentAlert != null) {
                    targetType = "bestChild"
                }
            }

            // If there is no child to receive the parent alert, then just handle the posted entries
            // and return.
            if (childToReceiveParentAlert == null) {
                postedEntries.forEach {
                    handlePostedEntry(it, hunMutator, scenario = "no-transfer-target")
                }
                return@forEach
            }

            // At this point we just need to initiate the transfer
            val summaryUpdate = mPostedEntries[logicalSummary.key]

            // If the summary was not attached, then remove the alert from the detached summary.
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
                        isAlerting = mHeadsUpManager.isAlerting(logicalSummary.key),
                        isBinding = isEntryBinding(logicalSummary),
                )
                // If we transfer the alert and the summary isn't even attached, that means we
                // should ensure the summary is no longer alerting, so we remove it here.
                handlePostedEntry(
                        summaryUpdateForRemoval,
                        hunMutator,
                        scenario = "detached-summary-remove-alert")
            } else if (summaryUpdate != null) {
                mLogger.logPostedEntryWillNotEvaluate(
                        summaryUpdate,
                        reason = "attached-summary-transferred")
            }

            // Handle all posted entries -- if the child receiving the parent's alert is in the
            // list, then set its flags to ensure it alerts.
            var didAlertChildToReceiveParentAlert = false
            postedEntries.asSequence()
                    .filter { it.key != logicalSummary.key }
                    .forEach { postedEntry ->
                        if (childToReceiveParentAlert.key == postedEntry.key) {
                            // Update the child's posted update so that it
                            postedEntry.shouldHeadsUpEver = true
                            postedEntry.shouldHeadsUpAgain = true
                            handlePostedEntry(
                                    postedEntry,
                                    hunMutator,
                                    scenario = "child-alert-transfer-target-$targetType")
                            didAlertChildToReceiveParentAlert = true
                        } else {
                            handlePostedEntry(
                                    postedEntry,
                                    hunMutator,
                                    scenario = "child-alert-non-target")
                        }
                    }

            // If the child receiving the alert was not updated on this tick (which can happen in a
            // standard alert transfer scenario), then construct an update so that we can apply it.
            if (!didAlertChildToReceiveParentAlert) {
                val posted = PostedEntry(
                        childToReceiveParentAlert,
                        wasAdded = false,
                        wasUpdated = false,
                        shouldHeadsUpEver = true,
                        shouldHeadsUpAgain = true,
                        isAlerting = mHeadsUpManager.isAlerting(childToReceiveParentAlert.key),
                        isBinding = isEntryBinding(childToReceiveParentAlert),
                )
                handlePostedEntry(
                        posted,
                        hunMutator,
                        scenario = "non-posted-child-alert-transfer-target-$targetType")
            }
        }
        // After this method runs, all posted entries should have been handled (or skipped).
        mPostedEntries.clear()
    }

    /**
     * Find the posted child with the newest when, and return it if it is isolated and has
     * GROUP_ALERT_SUMMARY so that it can be alerted.
     */
    private fun findAlertOverride(
        postedEntries: List<PostedEntry>,
        locationLookupByKey: (String) -> GroupLocation,
    ): NotificationEntry? = postedEntries.asSequence()
        .filter { posted -> !posted.entry.sbn.notification.isGroupSummary }
        .sortedBy { posted -> -posted.entry.sbn.notification.`when` }
        .firstOrNull()
        ?.let { posted ->
            posted.entry.takeIf { entry ->
                locationLookupByKey(entry.key) == GroupLocation.Isolated
                        && entry.sbn.notification.groupAlertBehavior == GROUP_ALERT_SUMMARY
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
            { -it.sbn.notification.`when` },
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
                // NOTE: This might be because we're alerting (i.e. tracked by HeadsUpManager) OR
                // it could be because we're binding, and that will affect the next step.
                if (posted.shouldHeadsUpEver) {
                    // If alerting, we need to post an update.  Otherwise we're still binding,
                    // and we can just let that finish.
                    if (posted.isAlerting) {
                        hunMutator.updateNotification(posted.key, posted.shouldHeadsUpAgain)
                    }
                } else {
                    if (posted.isAlerting) {
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
            // shouldHeadsUp includes check for whether this notification should be filtered
            val shouldHeadsUpEver = mNotificationInterruptStateProvider.shouldHeadsUp(entry)
            mPostedEntries[entry.key] = PostedEntry(
                entry,
                wasAdded = true,
                wasUpdated = false,
                shouldHeadsUpEver = shouldHeadsUpEver,
                shouldHeadsUpAgain = true,
                isAlerting = false,
                isBinding = false,
            )
        }

        /**
         * Notification could've updated to be heads up or not heads up. Even if it did update to
         * heads up, if the notification specified that it only wants to alert once, don't heads
         * up again.
         */
        override fun onEntryUpdated(entry: NotificationEntry) {
            val shouldHeadsUpEver = mNotificationInterruptStateProvider.shouldHeadsUp(entry)
            val shouldHeadsUpAgain = shouldHunAgain(entry)
            val isAlerting = mHeadsUpManager.isAlerting(entry.key)
            val isBinding = isEntryBinding(entry)
            val posted = mPostedEntries.compute(entry.key) { _, value ->
                value?.also { update ->
                    update.wasUpdated = true
                    update.shouldHeadsUpEver = update.shouldHeadsUpEver || shouldHeadsUpEver
                    update.shouldHeadsUpAgain = update.shouldHeadsUpAgain || shouldHeadsUpAgain
                    update.isAlerting = isAlerting
                    update.isBinding = isBinding
                } ?: PostedEntry(
                    entry,
                    wasAdded = false,
                    wasUpdated = true,
                    shouldHeadsUpEver = shouldHeadsUpEver,
                    shouldHeadsUpAgain = shouldHeadsUpAgain,
                    isAlerting = isAlerting,
                    isBinding = isBinding,
                )
            }
            // Handle cancelling alerts here, rather than in the OnBeforeFinalizeFilter, so that
            // work can be done before the ShadeListBuilder is run. This prevents re-entrant
            // behavior between this Coordinator, HeadsUpManager, and VisualStabilityManager.
            if (posted?.shouldHeadsUpEver == false) {
                if (posted.isAlerting) {
                    // We don't want this to be interrupting anymore, let's remove it
                    mHeadsUpManager.removeNotification(posted.key, false /*removeImmediately*/)
                } else if (posted.isBinding) {
                    // Don't let the bind finish
                    cancelHeadsUpBind(posted.entry)
                }
            }
        }

        /**
         * Stop alerting HUNs that are removed from the notification collection
         */
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            mPostedEntries.remove(entry.key)
            cancelHeadsUpBind(entry)
            val entryKey = entry.key
            if (mHeadsUpManager.isAlerting(entryKey)) {
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
    }

    /**
     * Checks whether an update for a notification warrants an alert for the user.
     */
    private fun shouldHunAgain(entry: NotificationEntry): Boolean {
        return (!entry.hasInterrupted() ||
                (entry.sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) == 0)
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
     * Whether the notification is already alerting or binding so that it can imminently alert
     */
    private fun isAttemptingToShowHun(entry: ListEntry) =
        mHeadsUpManager.isAlerting(entry.key) || isEntryBinding(entry)

    /**
     * Whether the notification is already alerting/binding per [isAttemptingToShowHun] OR if it
     * has been updated so that it should alert this update.  This method is permissive because it
     * returns `true` even if the update would (in isolation of its group) cause the alert to be
     * retracted.  This is important for not retracting transferred group alerts.
     */
    private fun isGoingToShowHunNoRetract(entry: ListEntry) =
        mPostedEntries[entry.key]?.calculateShouldBeHeadsUpNoRetract ?: isAttemptingToShowHun(entry)

    /**
     * If the notification has been updated, then whether it should HUN in isolation, otherwise
     * defers to the already alerting/binding state of [isAttemptingToShowHun].  This method is
     * strict because any update which would revoke the alert supersedes the current
     * alerting/binding state.
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
    }

    data class PostedEntry(
        val entry: NotificationEntry,
        val wasAdded: Boolean,
        var wasUpdated: Boolean,
        var shouldHeadsUpEver: Boolean,
        var shouldHeadsUpAgain: Boolean,
        var isAlerting: Boolean,
        var isBinding: Boolean,
    ) {
        val key = entry.key
        val isHeadsUpAlready: Boolean
            get() = isAlerting || isBinding
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
 * HeadsUpManager temporarily believes that nothing is alerting, causing bad re-entrant behavior.
 */
private fun <R> HeadsUpManager.modifyHuns(block: (HunMutator) -> R): R {
    val mutator = HunMutatorImpl(this)
    return block(mutator).also { mutator.commitModifications() }
}

/** Mutates the HeadsUp state of notifications. */
private interface HunMutator {
    fun updateNotification(key: String, alert: Boolean)
    fun removeNotification(key: String, releaseImmediately: Boolean)
}

/**
 * [HunMutator] implementation that defers removing notifications from the HeadsUpManager until
 * after additions/updates.
 */
private class HunMutatorImpl(private val headsUpManager: HeadsUpManager) : HunMutator {
    private val deferred = mutableListOf<Pair<String, Boolean>>()

    override fun updateNotification(key: String, alert: Boolean) {
        headsUpManager.updateNotification(key, alert)
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
