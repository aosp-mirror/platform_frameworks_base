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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.media.KeyguardMediaController
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.statusbar.notification.collection.render.ShadeViewManager
import com.android.systemui.statusbar.notification.dagger.AlertingHeader
import com.android.systemui.statusbar.notification.dagger.IncomingHeader
import com.android.systemui.statusbar.notification.dagger.PeopleHeader
import com.android.systemui.statusbar.notification.dagger.SilentHeader
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.children
import com.android.systemui.util.foldToSparseArray
import com.android.systemui.util.takeUntil
import javax.inject.Inject

/**
 * Manages the boundaries of the notification sections (incoming, conversations, high priority, and
 * low priority).
 *
 * In the legacy notification pipeline, this is responsible for correctly positioning all section
 * headers after the [NotificationStackScrollLayout] has had notifications added/removed/changed. In
 * the new pipeline, this is handled as part of the [ShadeViewManager].
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
class NotificationSectionsManager @Inject internal constructor(
    private val statusBarStateController: StatusBarStateController,
    private val configurationController: ConfigurationController,
    private val keyguardMediaController: KeyguardMediaController,
    private val sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val logger: NotificationSectionsLogger,
    @IncomingHeader private val incomingHeaderController: SectionHeaderController,
    @PeopleHeader private val peopleHeaderController: SectionHeaderController,
    @AlertingHeader private val alertingHeaderController: SectionHeaderController,
    @SilentHeader private val silentHeaderController: SectionHeaderController
) : SectionProvider {

    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onLocaleListChanged() {
            reinflateViews(LayoutInflater.from(parent.context))
        }
    }

    private lateinit var parent: NotificationStackScrollLayout
    private var initialized = false

    @VisibleForTesting
    val silentHeaderView: SectionHeaderView?
        get() = silentHeaderController.headerView

    @VisibleForTesting
    val alertingHeaderView: SectionHeaderView?
        get() = alertingHeaderController.headerView

    @VisibleForTesting
    val incomingHeaderView: SectionHeaderView?
        get() = incomingHeaderController.headerView

    @VisibleForTesting
    val peopleHeaderView: SectionHeaderView?
        get() = peopleHeaderController.headerView

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
        silentHeaderController.reinflateView(parent)
        alertingHeaderController.reinflateView(parent)
        peopleHeaderController.reinflateView(parent)
        incomingHeaderController.reinflateView(parent)
        mediaControlsView =
                reinflateView(mediaControlsView, layoutInflater, R.layout.keyguard_media_header)
        keyguardMediaController.attachSinglePaneContainer(mediaControlsView)
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

    private fun logShadeChild(i: Int, child: View) {
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
    private fun logShadeContents() = parent.children.forEachIndexed(::logShadeChild)

    private val isUsingMultipleSections: Boolean
        get() = sectionsFeatureManager.getNumberOfBuckets() > 1

    @VisibleForTesting
    fun updateSectionBoundaries() = updateSectionBoundaries("test")

    private interface SectionUpdateState<out T : ExpandableView> {
        val header: T
        var currentPosition: Int?
        var targetPosition: Int?
        fun adjustViewPosition()
    }

    private fun <T : ExpandableView> expandableViewHeaderState(header: T): SectionUpdateState<T> =
            object : SectionUpdateState<T> {
                override val header = header
                override var currentPosition: Int? = null
                override var targetPosition: Int? = null

                override fun adjustViewPosition() {
                    val target = targetPosition
                    val current = currentPosition
                    if (target == null) {
                        if (current != null) {
                            parent.removeView(header)
                        }
                    } else {
                        if (current == null) {
                            // If the header is animating away, it will still have a parent, so
                            // detach it first
                            // TODO: We should really cancel the active animations here. This will
                            //  happen automatically when the view's intro animation starts, but
                            //  it's a fragile link.
                            header.transientContainer?.removeTransientView(header)
                            header.transientContainer = null
                            parent.addView(header, target)
                        } else {
                            parent.changeViewPosition(header, target)
                        }
                    }
                }
    }

    private fun <T : StackScrollerDecorView> decorViewHeaderState(
        header: T
    ): SectionUpdateState<T> {
        val inner = expandableViewHeaderState(header)
        return object : SectionUpdateState<T> by inner {
            override fun adjustViewPosition() {
                inner.adjustViewPosition()
                if (targetPosition != null && currentPosition == null) {
                    header.isContentVisible = true
                }
            }
        }
    }

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
        val usingMediaControls = sectionsFeatureManager.isMediaControlsEnabled()

        val mediaState = mediaControlsView?.let(::expandableViewHeaderState)
        val incomingState = incomingHeaderView?.let(::decorViewHeaderState)
        val peopleState = peopleHeaderView?.let(::decorViewHeaderState)
        val alertingState = alertingHeaderView?.let(::decorViewHeaderState)
        val gentleState = silentHeaderView?.let(::decorViewHeaderState)

        fun getSectionState(view: View): SectionUpdateState<ExpandableView>? = when {
            view === mediaControlsView -> mediaState
            view === incomingHeaderView -> incomingState
            view === peopleHeaderView -> peopleState
            view === alertingHeaderView -> alertingState
            view === silentHeaderView -> gentleState
            else -> null
        }

        val headersOrdered = sequenceOf(
                mediaState, incomingState, peopleState, alertingState, gentleState
        ).filterNotNull()

        var peopleNotifsPresent = false
        var nextBucket: Int? = null
        var inIncomingSection = false

        // Iterating backwards allows for easier construction of the Incoming section, as opposed
        // to backtracking when a discontinuity in the sections is discovered.
        // Iterating to -1 in order to support the case where a header is at the very top of the
        // shade.
        for (i in parent.childCount - 1 downTo -1) {
            val child: View? = parent.getChildAt(i)

            child?.let {
                logShadeChild(i, child)
                // If this child is a header, update the tracked positions
                getSectionState(child)?.let { state ->
                    state.currentPosition = i
                    // If headers that should appear above this one in the shade already have a
                    // target index, then we need to decrement them in order to account for this one
                    // being either removed, or moved below them.
                    headersOrdered.takeUntil { it === state }
                            .forEach { it.targetPosition = it.targetPosition?.minus(1) }
                }
            }

            val row = (child as? ExpandableNotificationRow)
                    ?.takeUnless { it.visibility == View.GONE }

            // Is there a section discontinuity? This usually occurs due to HUNs
            inIncomingSection = inIncomingSection || nextBucket?.let { next ->
                row?.entry?.bucket?.let { curr -> next < curr }
            } == true

            if (inIncomingSection) {
                // Update the bucket to reflect that it's being placed in the Incoming section
                row?.entry?.bucket = BUCKET_HEADS_UP
            }

            // Insert a header in front of the next row, if there's a boundary between it and this
            // row, or if it is the topmost row.
            val isSectionBoundary = nextBucket != null &&
                    (child == null || row != null && nextBucket != row.entry.bucket)
            if (isSectionBoundary && showHeaders) {
                when (nextBucket) {
                    BUCKET_SILENT -> gentleState?.targetPosition = i + 1
                }
            }

            row ?: continue

            // Check if there are any people notifications
            peopleNotifsPresent = peopleNotifsPresent || row.entry.bucket == BUCKET_PEOPLE
            nextBucket = row.entry.bucket
        }

        mediaState?.targetPosition = if (usingMediaControls) 0 else null

        logger.logStr("New header target positions:")
        logger.logMediaControls(mediaState?.targetPosition ?: -1)
        logger.logIncomingHeader(incomingState?.targetPosition ?: -1)
        logger.logConversationsHeader(peopleState?.targetPosition ?: -1)
        logger.logAlertingHeader(alertingState?.targetPosition ?: -1)
        logger.logSilentHeader(gentleState?.targetPosition ?: -1)

        // Update headers in reverse order to preserve indices, otherwise movements earlier in the
        // list will affect the target indices of the headers later in the list.
        headersOrdered.asIterable().reversed().forEach { it.adjustViewPosition() }

        logger.logStr("Final order:")
        logShadeContents()
        logger.logStr("Section boundary update complete")

        // Update headers to reflect state of section contents
        silentHeaderView?.run {
            val hasActiveClearableNotifications = this@NotificationSectionsManager.parent
                    .hasActiveClearableNotifications(NotificationStackScrollLayout.ROWS_GENTLE)
            setClearSectionButtonEnabled(hasActiveClearableNotifications)
        }
    }

    private sealed class SectionBounds {

        data class Many(
            val first: ExpandableView,
            val last: ExpandableView
        ) : SectionBounds()

        data class One(val lone: ExpandableView) : SectionBounds()
        object None : SectionBounds()

        fun addNotif(notif: ExpandableView): SectionBounds = when (this) {
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
            first: ExpandableView?,
            last: ExpandableView?
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
        children: List<ExpandableView>
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

    fun setHeaderForegroundColor(@ColorInt color: Int) {
        peopleHeaderView?.setForegroundColor(color)
        silentHeaderView?.setForegroundColor(color)
        alertingHeaderView?.setForegroundColor(color)
    }

    companion object {
        private const val TAG = "NotifSectionsManager"
        private const val DEBUG = false
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
