/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.media.controls.util

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shared.system.SysUiStatsLog
import javax.inject.Inject

/** Logger class for Smartspace logging events. */
@SysUISingleton
class MediaSmartspaceLogger @Inject constructor() {
    /**
     * Log Smartspace card received event
     *
     * @param instanceId id to uniquely identify a card.
     * @param uid uid for the application that media comes from.
     * @param cardinality number of card in carousel.
     * @param isRecommendationCard whether media card being logged is a recommendations card.
     * @param isSsReactivated indicates resume media card is reactivated by Smartspace
     *   recommendation signal
     * @param rank the rank for media card in the media carousel, starting from 0
     * @param receivedLatencyMillis latency in milliseconds for card received events.
     */
    fun logSmartspaceCardReceived(
        instanceId: Int,
        uid: Int,
        cardinality: Int,
        isRecommendationCard: Boolean = false,
        isSsReactivated: Boolean = false,
        rank: Int = 0,
        receivedLatencyMillis: Int = 0,
    ) {
        logSmartspaceCardReported(
            SMARTSPACE_CARD_RECEIVED_EVENT,
            instanceId,
            uid,
            surfaces =
                intArrayOf(
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE,
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN,
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY,
                ),
            cardinality,
            isRecommendationCard,
            isSsReactivated,
            rank = rank,
            receivedLatencyMillis = receivedLatencyMillis,
        )
    }

    /**
     * Log Smartspace card UI event
     *
     * @param eventId id of the event. eg: dismiss, click, or seen.
     * @param instanceId id to uniquely identify a card.
     * @param uid uid for the application that media comes from.
     * @param surface location of media carousel holding media card.
     * @param cardinality number of card in carousel.
     * @param isRecommendationCard whether media card being logged is a recommendations card.
     * @param isSsReactivated indicates resume media card is reactivated by Smartspace
     *   recommendation signal
     * @param rank the rank for media card in the media carousel, starting from 0
     * @param isSwipeToDismiss whether is to log swipe-to-dismiss event
     */
    fun logSmartspaceCardUIEvent(
        eventId: Int,
        instanceId: Int,
        uid: Int,
        surface: Int,
        cardinality: Int,
        isRecommendationCard: Boolean = false,
        isSsReactivated: Boolean = false,
        interactedSubcardRank: Int = 0,
        interactedSubcardCardinality: Int = 0,
        rank: Int = 0,
        isSwipeToDismiss: Boolean = false,
    ) {
        logSmartspaceCardReported(
            eventId,
            instanceId,
            uid,
            surfaces = intArrayOf(surface),
            cardinality,
            isRecommendationCard,
            isSsReactivated,
            interactedSubcardRank,
            interactedSubcardCardinality,
            rank = rank,
            isSwipeToDismiss = isSwipeToDismiss,
        )
    }

    /**
     * Log Smartspace events
     *
     * @param eventId UI event id (e.g. 800 for SMARTSPACE_CARD_SEEN)
     * @param instanceId id to uniquely identify a card, e.g. each headphone generates a new
     *   instanceId
     * @param uid uid for the application that media comes from
     * @param surfaces list of display surfaces the media card is on (e.g. lockscreen, shade) when
     *   the event happened
     * @param cardinality number of card in carousel.
     * @param isRecommendationCard whether media card being logged is a recommendations card.
     * @param isSsReactivated indicates resume media card is reactivated by Smartspace
     *   recommendation signal
     * @param interactedSubcardRank the rank for interacted media item for recommendation card, -1
     *   for tapping on card but not on any media item, 0 for first media item, 1 for second, etc.
     * @param interactedSubcardCardinality how many media items were shown to the user when there is
     *   user interaction
     * @param rank the rank for media card in the media carousel, starting from 0
     * @param receivedLatencyMillis latency in milliseconds for card received events. E.g. latency
     *   between headphone connection to sysUI displays media recommendation card
     * @param isSwipeToDismiss whether is to log swipe-to-dismiss event
     */
    private fun logSmartspaceCardReported(
        eventId: Int,
        instanceId: Int,
        uid: Int,
        surfaces: IntArray,
        cardinality: Int,
        isRecommendationCard: Boolean,
        isSsReactivated: Boolean,
        interactedSubcardRank: Int = 0,
        interactedSubcardCardinality: Int = 0,
        rank: Int = 0,
        receivedLatencyMillis: Int = 0,
        isSwipeToDismiss: Boolean = false,
    ) {
        surfaces.forEach { surface ->
            SysUiStatsLog.write(
                SysUiStatsLog.SMARTSPACE_CARD_REPORTED,
                eventId,
                instanceId,
                // Deprecated, replaced with AiAi feature type so we don't need to create logging
                // card type for each new feature.
                SysUiStatsLog.SMART_SPACE_CARD_REPORTED__CARD_TYPE__UNKNOWN_CARD,
                surface,
                // Use -1 as rank value to indicate user swipe to dismiss the card
                if (isSwipeToDismiss) -1 else rank,
                cardinality,
                if (isRecommendationCard) {
                    15 // MEDIA_RECOMMENDATION
                } else if (isSsReactivated) {
                    43 // MEDIA_RESUME_SS_ACTIVATED
                } else {
                    31 // MEDIA_RESUME
                },
                uid,
                interactedSubcardRank,
                interactedSubcardCardinality,
                receivedLatencyMillis,
                null, // Media cards cannot have subcards.
                null // Media cards don't have dimensions today.
            )

            if (DEBUG) {
                Log.d(
                    TAG,
                    "Log Smartspace card event id: $eventId instance id: $instanceId" +
                        " surface: $surface rank: $rank cardinality: $cardinality " +
                        "isRecommendationCard: $isRecommendationCard " +
                        "isSsReactivated: $isSsReactivated" +
                        "uid: $uid " +
                        "interactedSubcardRank: $interactedSubcardRank " +
                        "interactedSubcardCardinality: $interactedSubcardCardinality " +
                        "received_latency_millis: $receivedLatencyMillis"
                )
            }
        }
    }

    companion object {
        private const val TAG = "MediaSmartspaceLogger"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val SMARTSPACE_CARD_RECEIVED_EVENT = 759
        const val SMARTSPACE_CARD_CLICK_EVENT = 760
        const val SMARTSPACE_CARD_DISMISS_EVENT = 761
        const val SMARTSPACE_CARD_SEEN_EVENT = 800

        /**
         * Get the location of media view given [currentEndLocation]
         *
         * @return location used for Smartspace logging
         */
        fun getSurface(location: Int): Int {
            SceneContainerFlag.isUnexpectedlyInLegacyMode()
            return when (location) {
                MediaHierarchyManager.LOCATION_QQS,
                MediaHierarchyManager.LOCATION_QS -> {
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE
                }
                MediaHierarchyManager.LOCATION_LOCKSCREEN -> {
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN
                }
                MediaHierarchyManager.LOCATION_DREAM_OVERLAY -> {
                    SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY
                }
                else -> SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DEFAULT_SURFACE
            }
        }
    }
}
