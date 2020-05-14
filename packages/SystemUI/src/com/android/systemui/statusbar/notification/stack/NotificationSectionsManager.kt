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
            else -> when (child.entry.bucket) {
                BUCKET_HEADS_UP -> logger.logHeadsUp(i)
                BUCKET_PEOPLE -> logger.logConversation(i)
                BUCKET_ALERTING -> logger.logAlerting(i)
                BUCKET_SILENT -> logger.logSilent(i)
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
        var mediaControlsTarget = if (usingMediaControls) 0 else -1
        var currentIncomingHeaderIdx = -1
        var incomingHeaderTarget = -1
        var currentPeopleHeaderIdx = -1
        var peopleHeaderTarget = -1
        var currentAlertingHeaderIdx = -1
        var alertingHeaderTarget = -1
        var currentGentleHeaderIdx = -1
        var gentleHeaderTarget = -1

        var lastNotifIndex = 0

        parent.children.forEachIndexed { i, child ->
            // Track the existing positions of the headers
            when {
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
                    when (child.entry.bucket) {
                        BUCKET_HEADS_UP -> {
                            logger.logHeadsUp(i)
                            if (showHeaders && incomingHeaderTarget == -1) {
                                incomingHeaderTarget = i
                                // Offset the target if there are other headers before this that
                                // will be moved.
                                if (currentIncomingHeaderIdx != -1) {
                                    incomingHeaderTarget--
                                }
                                if (currentMediaControlsIdx != -1) {
                                    incomingHeaderTarget--
                                }
                                if (currentPeopleHeaderIdx != -1) {
                                    incomingHeaderTarget--
                                }
                                if (currentAlertingHeaderIdx != -1) {
                                    incomingHeaderTarget--
                                }
                                if (currentGentleHeaderIdx != -1) {
                                    incomingHeaderTarget--
                                }
                            }
                            if (mediaControlsTarget != -1) {
                                mediaControlsTarget++
                            }
                        }
                        BUCKET_FOREGROUND_SERVICE -> {
                            logger.logForegroundService(i)
                            if (mediaControlsTarget != -1) {
                                mediaControlsTarget++
                            }
                        }
                        BUCKET_PEOPLE -> {
                            logger.logConversation(i)
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
                            logger.logAlerting(i)
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
                            logger.logSilent(i)
                            if (showHeaders && gentleHeaderTarget == -1) {
                                gentleHeaderTarget = i
                                // Offset the target if there are other headers before this that
                                // will be moved.
                                if (currentGentleHeaderIdx != -1) {
                                    gentleHeaderTarget--
                                }
                            }
                        }
                        else -> throw IllegalStateException("Cannot find section bucket for view")
                    }
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
        mediaControlsView?.let {
            adjustViewPosition(mediaControlsTarget, it, currentMediaControlsIdx)
        }
        incomingHeaderView?.let {
            adjustHeaderVisibilityAndPosition(incomingHeaderTarget, it, currentIncomingHeaderIdx)
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

    /**
     * Updates the boundaries (as tracked by their first and last views) of the priority sections.
     *
     * @return `true` If the last view in the top section changed (so we need to animate).
     */
    fun updateFirstAndLastViewsForAllSections(
        sections: Array<NotificationSection>,
        children: List<ActivatableNotificationView>
    ): Boolean {
        if (sections.isEmpty() || children.isEmpty()) {
            for (s in sections) {
                s.firstVisibleChild = null
                s.lastVisibleChild = null
            }
            return false
        }
        var changed = false
        val viewsInBucket = mutableListOf<ActivatableNotificationView>()
        for (s in sections) {
            val filter = s.bucket
            viewsInBucket.clear()

            // TODO: do this in a single pass, and more better
            for (v in children) {
                val bucket = getBucket(v)
                        ?: throw IllegalArgumentException("Cannot find section bucket for view")
                if (bucket == filter) {
                    viewsInBucket.add(v)
                }
                if (viewsInBucket.size >= 1) {
                    changed = changed or s.setFirstVisibleChild(viewsInBucket[0])
                    changed = changed or
                            s.setLastVisibleChild(viewsInBucket[viewsInBucket.size - 1])
                } else {
                    changed = changed or s.setFirstVisibleChild(null)
                    changed = changed or s.setLastVisibleChild(null)
                }
            }
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
            BUCKET_HEADS_UP, BUCKET_FOREGROUND_SERVICE, BUCKET_MEDIA_CONTROLS, BUCKET_PEOPLE,
            BUCKET_ALERTING, BUCKET_SILENT
        ]
)
annotation class PriorityBucket
const val BUCKET_HEADS_UP = 0
const val BUCKET_FOREGROUND_SERVICE = 1
const val BUCKET_MEDIA_CONTROLS = 2
const val BUCKET_PEOPLE = 3
const val BUCKET_ALERTING = 4
const val BUCKET_SILENT = 5
