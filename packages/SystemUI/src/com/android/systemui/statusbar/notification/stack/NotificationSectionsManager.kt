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
import android.util.Log
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.SourceType
import com.android.systemui.statusbar.notification.collection.render.MediaContainerController
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.statusbar.notification.dagger.AlertingHeader
import com.android.systemui.statusbar.notification.dagger.IncomingHeader
import com.android.systemui.statusbar.notification.dagger.PeopleHeader
import com.android.systemui.statusbar.notification.dagger.SilentHeader
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.foldToSparseArray
import javax.inject.Inject

/**
 * Manages section headers in the NSSL.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
class NotificationSectionsManager
@Inject
internal constructor(
    private val configurationController: ConfigurationController,
    private val keyguardMediaController: KeyguardMediaController,
    private val sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val mediaContainerController: MediaContainerController,
    private val notificationRoundnessManager: NotificationRoundnessManager,
    @IncomingHeader private val incomingHeaderController: SectionHeaderController,
    @PeopleHeader private val peopleHeaderController: SectionHeaderController,
    @AlertingHeader private val alertingHeaderController: SectionHeaderController,
    @SilentHeader private val silentHeaderController: SectionHeaderController
) : SectionProvider {

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onLocaleListChanged() {
                reinflateViews()
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

    @VisibleForTesting
    val mediaControlsView: MediaContainerView?
        get() = mediaContainerController.mediaContainerView

    /** Must be called before use. */
    fun initialize(parent: NotificationStackScrollLayout) {
        check(!initialized) { "NotificationSectionsManager already initialized" }
        initialized = true
        this.parent = parent
        reinflateViews()
        configurationController.addCallback(configurationListener)
    }

    fun createSectionsForBuckets(): Array<NotificationSection> =
        sectionsFeatureManager
            .getNotificationBuckets()
            .map { NotificationSection(parent, it) }
            .toTypedArray()

    /** Reinflates the entire notification header, including all decoration views. */
    fun reinflateViews() {
        silentHeaderController.reinflateView(parent)
        alertingHeaderController.reinflateView(parent)
        peopleHeaderController.reinflateView(parent)
        incomingHeaderController.reinflateView(parent)
        mediaContainerController.reinflateView(parent)
        keyguardMediaController.attachSinglePaneContainer(mediaControlsView)
    }

    override fun beginsSection(view: View, previous: View?): Boolean =
        view === silentHeaderView ||
            view === mediaControlsView ||
            view === peopleHeaderView ||
            view === alertingHeaderView ||
            view === incomingHeaderView ||
            getBucket(view) != getBucket(previous)

    private fun getBucket(view: View?): Int? =
        when {
            view === silentHeaderView -> BUCKET_SILENT
            view === incomingHeaderView -> BUCKET_HEADS_UP
            view === mediaControlsView -> BUCKET_MEDIA_CONTROLS
            view === peopleHeaderView -> BUCKET_PEOPLE
            view === alertingHeaderView -> BUCKET_ALERTING
            view is ExpandableNotificationRow -> view.entry.bucket
            else -> null
        }

    private sealed class SectionBounds {

        data class Many(val first: ExpandableView, val last: ExpandableView) : SectionBounds()

        data class One(val lone: ExpandableView) : SectionBounds()
        object None : SectionBounds()

        fun addNotif(notif: ExpandableView): SectionBounds =
            when (this) {
                is None -> One(notif)
                is One -> Many(lone, notif)
                is Many -> copy(last = notif)
            }

        fun updateSection(section: NotificationSection): Boolean =
            when (this) {
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
        val sectionBounds =
            children
                .asSequence()
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

        // Build a set of the old first/last Views of the sections
        val oldFirstChildren = sections.mapNotNull { it.firstVisibleChild }.toSet().toMutableSet()
        val oldLastChildren = sections.mapNotNull { it.lastVisibleChild }.toSet().toMutableSet()

        // Update each section with the associated boundary, tracking if there was a change
        val changed =
            sections.fold(false) { changed, section ->
                val bounds = sectionBounds[section.bucket] ?: SectionBounds.None
                val isSectionChanged = bounds.updateSection(section)
                isSectionChanged || changed
            }

        val newFirstChildren = sections.mapNotNull { it.firstVisibleChild }
        val newLastChildren = sections.mapNotNull { it.lastVisibleChild }

        // Update the roundness of Views that weren't already in the first/last position
        newFirstChildren.forEach { firstChild ->
            val wasFirstChild = oldFirstChildren.remove(firstChild)
            if (!wasFirstChild) {
                val notAnimatedChild = !notificationRoundnessManager.isAnimatedChild(firstChild)
                val animated = firstChild.isShown && notAnimatedChild
                firstChild.requestTopRoundness(1f, SECTION, animated)
            }
        }
        newLastChildren.forEach { lastChild ->
            val wasLastChild = oldLastChildren.remove(lastChild)
            if (!wasLastChild) {
                val notAnimatedChild = !notificationRoundnessManager.isAnimatedChild(lastChild)
                val animated = lastChild.isShown && notAnimatedChild
                lastChild.requestBottomRoundness(1f, SECTION, animated)
            }
        }

        // The Views left in the set are no longer in the first/last position
        oldFirstChildren.forEach { noMoreFirstChild ->
            noMoreFirstChild.requestTopRoundness(0f, SECTION)
        }
        oldLastChildren.forEach { noMoreLastChild ->
            noMoreLastChild.requestBottomRoundness(0f, SECTION)
        }

        if (DEBUG) {
            logSections(sections)
        }
        return changed
    }

    private fun logSections(sections: Array<NotificationSection>) {
        for (i in sections.indices) {
            val s = sections[i]
            val fs =
                when (val first = s.firstVisibleChild) {
                    null -> "(null)"
                    is ExpandableNotificationRow -> first.entry.key
                    else -> Integer.toHexString(System.identityHashCode(first))
                }
            val ls =
                when (val last = s.lastVisibleChild) {
                    null -> "(null)"
                    is ExpandableNotificationRow -> last.entry.key
                    else -> Integer.toHexString(System.identityHashCode(last))
                }
            Log.d(TAG, "updateSections: f=$fs s=$i")
            Log.d(TAG, "updateSections: l=$ls s=$i")
        }
    }

    fun setHeaderForegroundColors(@ColorInt onSurface: Int, @ColorInt onSurfaceVariant: Int) {
        peopleHeaderView?.setForegroundColors(onSurface, onSurfaceVariant)
        silentHeaderView?.setForegroundColors(onSurface, onSurfaceVariant)
        alertingHeaderView?.setForegroundColors(onSurface, onSurfaceVariant)
    }

    companion object {
        private const val TAG = "NotifSectionsManager"
        private const val DEBUG = false
        private val SECTION = SourceType.from("Section")
    }
}
