/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.stack

import android.annotation.ColorInt
import android.annotation.IntDef
import android.annotation.LayoutRes
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.media.KeyguardMediaController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.people.DataListener
import com.android.systemui.statusbar.notification.people.PeopleHubViewAdapter
import com.android.systemui.statusbar.notification.people.PeopleHubViewBoundary
import com.android.systemui.statusbar.notification.people.PersonViewModel
import com.android.systemui.statusbar.notification.people.Subscription
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.children
import com.android.systemui.util.foldToSparseArray
import javax.inject.Inject

/**
 * Manages the boundaries of the two notification sections (high priority and low priority). Also
 * shows/hides the headers for those sections where appropriate.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
class NotificationSectionsManager @Inject internal constructor(
    private val activityStarter: ActivityStarter,
    private val statusBarStateController: StatusBarStateController,
    private val configurationController: ConfigurationController,
    private val peopleHubViewAdapter: PeopleHubViewAdapter,
    private val keyguardMediaController: KeyguardMediaController,
    private val sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val logger: NotificationSectionsLogger
) : SectionProvider {

    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onLocaleListChanged() {
            reinflateViews(LayoutInflater.from(parent.context))
        }
    }

    private val peopleHubViewBoundary: PeopleHubViewBoundary = object : PeopleHubViewBoundary {
        override fun setVisible(isVisible: Boolean) {
            if (peopleHubVisible != isVisible) {
                peopleHubVisible = isVisible
                if (initialized) {
                    updateSectionBoundaries("PeopleHub visibility changed")
                }
            }
        }

        override val associatedViewForClickAnimation: View
            get() = peopleHeaderView!!

        override val personViewAdapters: Sequence<DataListener<PersonViewModel?>>
            get() = peopleHeaderView!!.personViewAdapters
    }

    private lateinit var parent: NotificationStackScrollLayout
    private var initialized = false
    private var onClearSilentNotifsClickListener: View.OnClickListener? = null

    @get:VisibleForTesting
    var silentHeaderView: SectionHeaderView? = null
        private set

    @get:VisibleForTesting
    var alertingHeaderView: SectionHeaderView? = null
        private set

    @get:VisibleForTesting
    var incomingHeaderView: SectionHeaderView? = null
        private set

    @get:VisibleForTesting
    var peopleHeaderView: PeopleHubView? = null
        private set

    @set:VisibleForTesting
    var peopleHubVisible = false
    private var peopleHubSubscription: Subscription? = null

    @get:VisibleForTesting
    var mediaControlsView: MediaHeaderView? = null
        private set

    /** Must be called before use.  */
    fun initialize(parent: NotificationStackScrollLayout, layoutInflater: LayoutInflater) {
        check(!initialized) { "NotificationSectionsManager already initialized" }
        initialized = true
        this.parent = parent
        reinflateViews(layoutInflater)
        configurationController.addCallback(configurationListener)
    }

    private fun <T : ExpandableView> reinflateView(
        view: T?,
        layoutInflater: LayoutInflater,
        @LayoutRes layoutResId: Int
    ): T {
        var oldPos = -1
        view?.let {
            view.transientContainer?.removeView(view)
            if (view.parent === parent) {
                oldPos = parent.indexOfChild(view)
                parent.removeView(view)
            }
        }
        val inflated = layoutInflater.inflate(layoutResId, parent, false) as T
        if (oldPos != -1) {
            parent.addView(inflated, oldPos)
        }
        return inflated
    }

    fun createSectionsForBuckets(): Array<NotificationSection> =
            sectionsFeatureManager.getNotificationBuckets()
                    .map { NotificationSection(parent, it) }
                    .toTypedArray()

    /**
     * Reinflates the entire notification header, including all decoration views.
     */
    fun reinflateViews(layoutInflater: LayoutInflater) {
        silentHeaderView = reinflateView(
                silentHeaderView, layoutInflater, R.layout.status_bar_notification_section_header
        ).apply {
            setHeaderText(R.string.notification_section_header_gentle)
            setOnHeaderClickListener { onGentleHeaderClick() }
            setOnClearAllClickListener { onClearGentleNotifsClick(it) }
        }
        alertingHeaderView = reinflateView(
                alertingHeaderView, layoutInflater, R.layout.status_bar_notification_section_header
        ).apply {
            setHeaderText(R.string.notification_section_header_alerting)
            setOnHeaderClickListener { onGentleHeaderClick() }
        }
        peopleHubSubscription?.unsubscribe()
        peopleHubSubscription = null
        peopleHeaderView = reinflateView(peopleHeaderView, layoutInflater, R.layout.people_strip)
        if (ENABLE_SNOOZED_CONVERSATION_HUB) {
            peopleHubSubscription = peopleHubViewAdapter.bindView(peopleHubViewBoundary)
        }
        incomingHeaderView = reinflateView(
                incomingHeaderView, layoutInflater, R.layout.status_bar_notification_section_header
        ).apply {
            setHeaderText(R.string.notification_section_header_incoming)
            setOnHeaderClickListener { onGentleHeaderClick() }
        }
        mediaControlsView =
                reinflateView(mediaControlsView, layoutInflater, R.layout.keyguard_media_header)
                        .also(keyguardMediaController::attach)
    }

    override fun beginsSection(view: View, previous: View?): Boolean =
            view === silentHeaderView ||
            view === mediaControlsView ||
            view === peopleHeaderView ||
            view === alertingHeaderView ||
            view === incomingHeaderView ||
            getBucket(view) != getBucket(previous)

    private fun getBucket(view: View?): Int? = when {
        view === silentHeaderView -> BUCKET_SILENT
        view === incomingHeaderView -> BUCKET_HEADS_UP
        view === mediaControlsView -> BUCKET_MEDIA_CONTROLS
        view === peopleHeaderView -> BUCKET_PEOPLE
        view === alertingHeaderView -> BUCKET_ALERTING
        view is ExpandableNotificationRow -> view.entry.bucket
        else -> null
    }

    private fun logShadeContents() = parent.children.forEachIndexed { i, child ->
        when {
            child === incomingHeaderView -> logger.logIncomingHeader(i)
            child === mediaControlsView -> logger.logMediaControls(i)
            child === peopleHeaderView -> logger.logConversationsHeader(i)
            child === alertingHeaderView -> logger.logAlertingHeader(i)
            child === silentHeaderView -> logger.logSilentHeader(i)
            child !is ExpandableNotificationRow -> logger.logOther(i, child.javaClass)
            else -> {
                val isHeadsUp = child.isHeadsUp
                when (child.entry.bucket) {
                    BUCKET_HEADS_UP -> logger.logHeadsUp(i, isHeadsUp)
                    BUCKET_PEOPLE -> logger.logConversation(i, isHeadsUp)
                    BUCKET_ALERTING -> logger.logAlerting(i, isHeadsUp)
                    BUCKET_SILENT -> logger.logSilent(i, isHeadsUp)
                }
            }
        }
    }

    private val isUsingMultipleSections: Boolean
        get() = sectionsFeatureManager.getNumberOfBuckets() > 1

    @VisibleForTesting
    fun updateSectionBoundaries() = updateSectionBoundaries("test")

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    fun updateSectionBoundaries(reason: String) {
        if (!isUsingMultipleSections) {
            return
        }
        logger.logStartSectionUpdate(reason)

        // The overall strategy here is to iterate over the current children of mParent, looking
        // for where the sections headers are currently positioned, and where each section begins.
        // Then, once we find the start of a new section, we track that position as the "target" for
        // the section header, adjusted for the case where existing headers are in front of that
        // target, but won't be once they are moved / removed after the pass has completed.
        val showHeaders = statusBarStateController.state != StatusBarState.KEYGUARD
        val usingPeopleFiltering = sectionsFeatureManager.isFilteringEnabled()
        val usingMediaControls = sectionsFeatureManager.isMediaControlsEnabled()

        var peopleNotifsPresent = false
        var currentMediaControlsIdx = -1
        val mediaControlsTarget = if (usingMediaControls) 0 else -1
        var currentIncomingHeaderIdx = -1
        var incomingHeaderTarget = -1
        var currentPeopleHeaderIdx = -1
        var peopleHeaderTarget = -1
        var currentAlertingHeaderIdx = -1
        var alertingHeaderTarget = -1
        var currentGentleHeaderIdx = -1
        var gentleHeaderTarget = -1

        var lastNotifIndex = 0
        var lastIncomingIndex = -1
        var prev: ExpandableNotificationRow? = null

        for ((i, child) in parent.children.withIndex()) {
            when {
                // Track the existing positions of the headers
                child === incomingHeaderView -> {
                    logger.logIncomingHeader(i)
                    currentIncomingHeaderIdx = i
                }
                child === mediaControlsView -> {
                    logger.logMediaControls(i)
                    currentMediaControlsIdx = i
                }
                child === peopleHeaderView -> {
                    logger.logConversationsHeader(i)
                    currentPeopleHeaderIdx = i
                }
                child === alertingHeaderView -> {
                    logger.logAlertingHeader(i)
                    currentAlertingHeaderIdx = i
                }
                child === silentHeaderView -> {
                    logger.logSilentHeader(i)
                    currentGentleHeaderIdx = i
                }
                child !is ExpandableNotificationRow -> logger.logOther(i, child.javaClass)
                else -> {
                    lastNotifIndex = i
                    // Is there a section discontinuity? This usually occurs due to HUNs
                    if (prev?.entry?.bucket?.let { it > child.entry.bucket } == true) {
                        // Remove existing headers, and move the Incoming header if necessary
                        if (alertingHeaderTarget != -1) {
                            if (showHeaders && incomingHeaderTarget != -1) {
                                incomingHeaderTarget = alertingHeaderTarget
                            }
                            alertingHeaderTarget = -1
                        }
                        if (peopleHeaderTarget != -1) {
                            if (showHeaders && incomingHeaderTarget != -1) {
                                incomingHeaderTarget = peopleHeaderTarget
                            }
                            peopleHeaderTarget = -1
                        }
                        if (showHeaders && incomingHeaderTarget == -1) {
                            incomingHeaderTarget = 0
                        }
                        // Walk backwards changing all previous notifications to the Incoming
                        // section
                        for (j in i - 1 downTo lastIncomingIndex + 1) {
                            val prevChild = parent.getChildAt(j)
                            if (prevChild is ExpandableNotificationRow) {
                                prevChild.entry.bucket = BUCKET_HEADS_UP
                            }
                        }
                        // Track the new bottom of the Incoming section
                        lastIncomingIndex = i - 1
                    }
                    val isHeadsUp = child.isHeadsUp
                    when (child.entry.bucket) {
                        BUCKET_FOREGROUND_SERVICE -> logger.logForegroundService(i, isHeadsUp)
                        BUCKET_PEOPLE -> {
                            logger.logConversation(i, isHeadsUp)
                            peopleNotifsPresent = true
                            if (showHeaders && peopleHeaderTarget == -1) {
                                peopleHeaderTarget = i
                                // Offset the target if there are other headers before this that
                                // will be moved.
                                if (currentPeopleHeaderIdx != -1) {
                                    peopleHeaderTarget--
                                }
                                if (currentAlertingHeaderIdx != -1) {
                                    peopleHeaderTarget--
                                }
                                if (currentGentleHeaderIdx != -1) {
                                    peopleHeaderTarget--
                                }
                            }
                        }
                        BUCKET_ALERTING -> {
                            logger.logAlerting(i, isHeadsUp)
                            if (showHeaders && usingPeopleFiltering && alertingHeaderTarget == -1) {
                                alertingHeaderTarget = i
                                // Offset the target if there are other headers before this that
                                // will be moved.
                                if (currentAlertingHeaderIdx != -1) {
                                    alertingHeaderTarget--
                                }
                                if (currentGentleHeaderIdx != -1) {
                                    alertingHeaderTarget--
                                }
                            }
                        }
                        BUCKET_SILENT -> {
                            logger.logSilent(i, isHeadsUp)
                            if (showHeaders && gentleHeaderTarget == -1) {
                                gentleHeaderTarget = i
                                // Offset the target if there are other headers before this that
                                // will be moved.
                                if (currentGentleHeaderIdx != -1) {
                                    gentleHeaderTarget--
                                }
                            }
                        }
                    }

                    prev = child
                }
            }
        }

        if (showHeaders && usingPeopleFiltering && peopleHubVisible && peopleHeaderTarget == -1) {
            // Insert the people header even if there are no people visible, in order to show
            // the hub. Put it directly above the next header.
            peopleHeaderTarget = when {
                alertingHeaderTarget != -1 -> alertingHeaderTarget
                gentleHeaderTarget != -1 -> gentleHeaderTarget
                else -> lastNotifIndex // Put it at the end of the list.
            }
            // Offset the target to account for the current position of the people header.
            if (currentPeopleHeaderIdx != -1 && currentPeopleHeaderIdx < peopleHeaderTarget) {
                peopleHeaderTarget--
            }
        }

        logger.logStr("New header target positions:")
        logger.logIncomingHeader(incomingHeaderTarget)
        logger.logMediaControls(mediaControlsTarget)
        logger.logConversationsHeader(peopleHeaderTarget)
        logger.logAlertingHeader(alertingHeaderTarget)
        logger.logSilentHeader(gentleHeaderTarget)

        // Add headers in reverse order to preserve indices
        silentHeaderView?.let {
            adjustHeaderVisibilityAndPosition(gentleHeaderTarget, it, currentGentleHeaderIdx)
        }
        alertingHeaderView?.let {
            adjustHeaderVisibilityAndPosition(alertingHeaderTarget, it, currentAlertingHeaderIdx)
        }
        peopleHeaderView?.let {
            adjustHeaderVisibilityAndPosition(peopleHeaderTarget, it, currentPeopleHeaderIdx)
        }
        incomingHeaderView?.let {
            adjustHeaderVisibilityAndPosition(incomingHeaderTarget, it, currentIncomingHeaderIdx)
        }
        mediaControlsView?.let {
            adjustViewPosition(mediaControlsTarget, it, currentMediaControlsIdx)
        }

        logger.logStr("Final order:")
        logShadeContents()
        logger.logStr("Section boundary update complete")

        // Update headers to reflect state of section contents
        silentHeaderView?.setAreThereDismissableGentleNotifs(
                parent.hasActiveClearableNotifications(NotificationStackScrollLayout.ROWS_GENTLE)
        )
        peopleHeaderView?.canSwipe = showHeaders && peopleHubVisible && !peopleNotifsPresent
        if (peopleHeaderTarget != currentPeopleHeaderIdx) {
            peopleHeaderView?.resetTranslation()
        }
    }

    private fun adjustHeaderVisibilityAndPosition(
        targetPosition: Int,
        header: StackScrollerDecorView,
        currentPosition: Int
    ) {
        adjustViewPosition(targetPosition, header, currentPosition)
        if (targetPosition != -1 && currentPosition == -1) {
            header.isContentVisible = true
        }
    }

    private fun adjustViewPosition(
        targetPosition: Int,
        view: ExpandableView,
        currentPosition: Int
    ) {
        if (targetPosition == -1) {
            if (currentPosition != -1) {
                parent.removeView(view)
            }
        } else {
            if (currentPosition == -1) {
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                view.transientContainer?.removeTransientView(view)
                view.transientContainer = null
                parent.addView(view, targetPosition)
            } else {
                parent.changeViewPosition(view, targetPosition)
            }
        }
    }

    private sealed class SectionBounds {

        data class Many(
            val first: ActivatableNotificationView,
            val last: ActivatableNotificationView
        ) : SectionBounds()

        data class One(val lone: ActivatableNotificationView) : SectionBounds()
        object None : SectionBounds()

        fun addNotif(notif: ActivatableNotificationView): SectionBounds = when (this) {
            is None -> One(notif)
            is One -> Many(lone, notif)
            is Many -> copy(last = notif)
        }

        fun updateSection(section: NotificationSection): Boolean = when (this) {
            is None -> section.setFirstAndLastVisibleChildren(null, null)
            is One -> section.setFirstAndLastVisibleChildren(lone, lone)
            is Many -> section.setFirstAndLastVisibleChildren(first, last)
        }

        private fun NotificationSection.setFirstAndLastVisibleChildren(
            first: ActivatableNotificationView?,
            last: ActivatableNotificationView?
        ): Boolean {
            val firstChanged = setFirstVisibleChild(first)
            val lastChanged = setLastVisibleChild(last)
            return firstChanged || lastChanged
        }
    }

    /**
     * Updates the boundaries (as tracked by their first and last views) of the priority sections.
     *
     * @return `true` If the last view in the top section changed (so we need to animate).
     */
    fun updateFirstAndLastViewsForAllSections(
        sections: Array<NotificationSection>,
        children: List<ActivatableNotificationView>
    ): Boolean {
        // Create mapping of bucket to section
        val sectionBounds = children.asSequence()
                // Group children by bucket
                .groupingBy {
                    getBucket(it)
                            ?: throw IllegalArgumentException("Cannot find section bucket for view")
                }
                // Combine each bucket into a SectionBoundary
                .foldToSparseArray(
                        SectionBounds.None,
                        size = sections.size,
                        operation = SectionBounds::addNotif
                )
        // Update each section with the associated boundary, tracking if there was a change
        val changed = sections.fold(false) { changed, section ->
            val bounds = sectionBounds[section.bucket] ?: SectionBounds.None
            bounds.updateSection(section) || changed
        }
        if (DEBUG) {
            logSections(sections)
        }
        return changed
    }

    private fun logSections(sections: Array<NotificationSection>) {
        for (i in sections.indices) {
            val s = sections[i]
            val fs = when (val first = s.firstVisibleChild) {
                null -> "(null)"
                is ExpandableNotificationRow -> first.entry.key
                else -> Integer.toHexString(System.identityHashCode(first))
            }
            val ls = when (val last = s.lastVisibleChild) {
                null -> "(null)"
                is ExpandableNotificationRow -> last.entry.key
                else -> Integer.toHexString(System.identityHashCode(last))
            }
            Log.d(TAG, "updateSections: f=$fs s=$i")
            Log.d(TAG, "updateSections: l=$ls s=$i")
        }
    }

    private fun onGentleHeaderClick() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_SETTINGS)
        activityStarter.startActivity(
                intent,
                true,
                true,
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun onClearGentleNotifsClick(v: View) {
        onClearSilentNotifsClickListener?.onClick(v)
    }

    /** Listener for when the "clear all" button is clicked on the gentle notification header. */
    fun setOnClearSilentNotifsClickListener(listener: View.OnClickListener) {
        onClearSilentNotifsClickListener = listener
    }

    fun hidePeopleRow() {
        peopleHubVisible = false
        updateSectionBoundaries("PeopleHub dismissed")
    }

    fun setHeaderForegroundColor(@ColorInt color: Int) {
        peopleHeaderView?.setTextColor(color)
        silentHeaderView?.setForegroundColor(color)
        alertingHeaderView?.setForegroundColor(color)
    }

    companion object {
        private const val TAG = "NotifSectionsManager"
        private const val DEBUG = false
        private const val ENABLE_SNOOZED_CONVERSATION_HUB = false
    }
}

/**
 * For now, declare the available notification buckets (sections) here so that other
 * presentation code can decide what to do based on an entry's buckets
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
        prefix = ["BUCKET_"],
        value = [
            BUCKET_UNKNOWN, BUCKET_MEDIA_CONTROLS, BUCKET_HEADS_UP, BUCKET_FOREGROUND_SERVICE,
            BUCKET_PEOPLE, BUCKET_ALERTING, BUCKET_SILENT
        ]
)
annotation class PriorityBucket

const val BUCKET_UNKNOWN = 0
const val BUCKET_MEDIA_CONTROLS = 1
const val BUCKET_HEADS_UP = 2
const val BUCKET_FOREGROUND_SERVICE = 3
const val BUCKET_PEOPLE = 4
const val BUCKET_ALERTING = 5
const val BUCKET_SILENT = 6
