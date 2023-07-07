/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.media.controls.ui.MediaLocation
import java.lang.IllegalArgumentException
import javax.inject.Inject

private const val INSTANCE_ID_MAX = 1 shl 20

/** A helper class to log events related to the media controls */
@SysUISingleton
class MediaUiEventLogger @Inject constructor(private val logger: UiEventLogger) {

    private val instanceIdSequence = InstanceIdSequence(INSTANCE_ID_MAX)

    /** Get a new instance ID for a new media control */
    fun getNewInstanceId(): InstanceId {
        return instanceIdSequence.newInstanceId()
    }

    fun logActiveMediaAdded(
        uid: Int,
        packageName: String,
        instanceId: InstanceId,
        playbackLocation: Int
    ) {
        val event =
            when (playbackLocation) {
                MediaData.PLAYBACK_LOCAL -> MediaUiEvent.LOCAL_MEDIA_ADDED
                MediaData.PLAYBACK_CAST_LOCAL -> MediaUiEvent.CAST_MEDIA_ADDED
                MediaData.PLAYBACK_CAST_REMOTE -> MediaUiEvent.REMOTE_MEDIA_ADDED
                else -> throw IllegalArgumentException("Unknown playback location")
            }
        logger.logWithInstanceId(event, uid, packageName, instanceId)
    }

    fun logPlaybackLocationChange(
        uid: Int,
        packageName: String,
        instanceId: InstanceId,
        playbackLocation: Int
    ) {
        val event =
            when (playbackLocation) {
                MediaData.PLAYBACK_LOCAL -> MediaUiEvent.TRANSFER_TO_LOCAL
                MediaData.PLAYBACK_CAST_LOCAL -> MediaUiEvent.TRANSFER_TO_CAST
                MediaData.PLAYBACK_CAST_REMOTE -> MediaUiEvent.TRANSFER_TO_REMOTE
                else -> throw IllegalArgumentException("Unknown playback location")
            }
        logger.logWithInstanceId(event, uid, packageName, instanceId)
    }

    fun logResumeMediaAdded(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.RESUME_MEDIA_ADDED, uid, packageName, instanceId)
    }

    fun logActiveConvertedToResume(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.ACTIVE_TO_RESUME, uid, packageName, instanceId)
    }

    fun logMediaTimeout(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.MEDIA_TIMEOUT, uid, packageName, instanceId)
    }

    fun logMediaRemoved(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.MEDIA_REMOVED, uid, packageName, instanceId)
    }

    fun logMediaCarouselPage(position: Int) {
        // Since this operation is on the carousel, we don't include package information
        logger.logWithPosition(MediaUiEvent.CAROUSEL_PAGE, 0, null, position)
    }

    fun logSwipeDismiss() {
        // Since this operation is on the carousel, we don't include package information
        logger.log(MediaUiEvent.DISMISS_SWIPE)
    }

    fun logLongPressOpen(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.OPEN_LONG_PRESS, uid, packageName, instanceId)
    }

    fun logLongPressDismiss(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.DISMISS_LONG_PRESS, uid, packageName, instanceId)
    }

    fun logLongPressSettings(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.OPEN_SETTINGS_LONG_PRESS,
            uid,
            packageName,
            instanceId
        )
    }

    fun logCarouselSettings() {
        // Since this operation is on the carousel, we don't include package information
        logger.log(MediaUiEvent.OPEN_SETTINGS_CAROUSEL)
    }

    fun logTapAction(buttonId: Int, uid: Int, packageName: String, instanceId: InstanceId) {
        val event =
            when (buttonId) {
                R.id.actionPlayPause -> MediaUiEvent.TAP_ACTION_PLAY_PAUSE
                R.id.actionPrev -> MediaUiEvent.TAP_ACTION_PREV
                R.id.actionNext -> MediaUiEvent.TAP_ACTION_NEXT
                else -> MediaUiEvent.TAP_ACTION_OTHER
            }

        logger.logWithInstanceId(event, uid, packageName, instanceId)
    }

    fun logSeek(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.ACTION_SEEK, uid, packageName, instanceId)
    }

    fun logOpenOutputSwitcher(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.OPEN_OUTPUT_SWITCHER, uid, packageName, instanceId)
    }

    fun logTapContentView(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(MediaUiEvent.MEDIA_TAP_CONTENT_VIEW, uid, packageName, instanceId)
    }

    fun logCarouselPosition(@MediaLocation location: Int) {
        val event =
            when (location) {
                MediaHierarchyManager.LOCATION_QQS -> MediaUiEvent.MEDIA_CAROUSEL_LOCATION_QQS
                MediaHierarchyManager.LOCATION_QS -> MediaUiEvent.MEDIA_CAROUSEL_LOCATION_QS
                MediaHierarchyManager.LOCATION_LOCKSCREEN ->
                    MediaUiEvent.MEDIA_CAROUSEL_LOCATION_LOCKSCREEN
                MediaHierarchyManager.LOCATION_DREAM_OVERLAY ->
                    MediaUiEvent.MEDIA_CAROUSEL_LOCATION_DREAM
                else -> throw IllegalArgumentException("Unknown media carousel location $location")
            }
        logger.log(event)
    }

    fun logRecommendationAdded(packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_RECOMMENDATION_ADDED,
            0,
            packageName,
            instanceId
        )
    }

    fun logRecommendationRemoved(packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_RECOMMENDATION_REMOVED,
            0,
            packageName,
            instanceId
        )
    }

    fun logRecommendationActivated(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_RECOMMENDATION_ACTIVATED,
            uid,
            packageName,
            instanceId
        )
    }

    fun logRecommendationItemTap(packageName: String, instanceId: InstanceId, position: Int) {
        logger.logWithInstanceIdAndPosition(
            MediaUiEvent.MEDIA_RECOMMENDATION_ITEM_TAP,
            0,
            packageName,
            instanceId,
            position
        )
    }

    fun logRecommendationCardTap(packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_RECOMMENDATION_CARD_TAP,
            0,
            packageName,
            instanceId
        )
    }

    fun logOpenBroadcastDialog(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_OPEN_BROADCAST_DIALOG,
            uid,
            packageName,
            instanceId
        )
    }

    fun logSingleMediaPlayerInCarousel(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_CAROUSEL_SINGLE_PLAYER,
            uid,
            packageName,
            instanceId
        )
    }

    fun logMultipleMediaPlayersInCarousel(uid: Int, packageName: String, instanceId: InstanceId) {
        logger.logWithInstanceId(
            MediaUiEvent.MEDIA_CAROUSEL_MULTIPLE_PLAYERS,
            uid,
            packageName,
            instanceId
        )
    }
}

enum class MediaUiEvent(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "A new media control was added for media playing locally on the device")
    LOCAL_MEDIA_ADDED(1029),
    @UiEvent(doc = "A new media control was added for media cast from the device")
    CAST_MEDIA_ADDED(1030),
    @UiEvent(doc = "A new media control was added for media playing remotely")
    REMOTE_MEDIA_ADDED(1031),
    @UiEvent(doc = "The media for an existing control was transferred to local playback")
    TRANSFER_TO_LOCAL(1032),
    @UiEvent(doc = "The media for an existing control was transferred to a cast device")
    TRANSFER_TO_CAST(1033),
    @UiEvent(doc = "The media for an existing control was transferred to a remote device")
    TRANSFER_TO_REMOTE(1034),
    @UiEvent(doc = "A new resumable media control was added") RESUME_MEDIA_ADDED(1013),
    @UiEvent(doc = "An existing active media control was converted into resumable media")
    ACTIVE_TO_RESUME(1014),
    @UiEvent(doc = "A media control timed out") MEDIA_TIMEOUT(1015),
    @UiEvent(doc = "A media control was removed from the carousel") MEDIA_REMOVED(1016),
    @UiEvent(doc = "User swiped to another control within the media carousel") CAROUSEL_PAGE(1017),
    @UiEvent(doc = "The user swiped away the media carousel") DISMISS_SWIPE(1018),
    @UiEvent(doc = "The user long pressed on a media control") OPEN_LONG_PRESS(1019),
    @UiEvent(doc = "The user dismissed a media control via its long press menu")
    DISMISS_LONG_PRESS(1020),
    @UiEvent(doc = "The user opened media settings from a media control's long press menu")
    OPEN_SETTINGS_LONG_PRESS(1021),
    @UiEvent(doc = "The user opened media settings from the media carousel")
    OPEN_SETTINGS_CAROUSEL(1022),
    @UiEvent(doc = "The play/pause button on a media control was tapped")
    TAP_ACTION_PLAY_PAUSE(1023),
    @UiEvent(doc = "The previous button on a media control was tapped") TAP_ACTION_PREV(1024),
    @UiEvent(doc = "The next button on a media control was tapped") TAP_ACTION_NEXT(1025),
    @UiEvent(doc = "A custom or generic action button on a media control was tapped")
    TAP_ACTION_OTHER(1026),
    @UiEvent(doc = "The user seeked on a media control using the seekbar") ACTION_SEEK(1027),
    @UiEvent(doc = "The user opened the output switcher from a media control")
    OPEN_OUTPUT_SWITCHER(1028),
    @UiEvent(doc = "The user tapped on a media control view") MEDIA_TAP_CONTENT_VIEW(1036),
    @UiEvent(doc = "The media carousel moved to QQS") MEDIA_CAROUSEL_LOCATION_QQS(1037),
    @UiEvent(doc = "THe media carousel moved to QS") MEDIA_CAROUSEL_LOCATION_QS(1038),
    @UiEvent(doc = "The media carousel moved to the lockscreen")
    MEDIA_CAROUSEL_LOCATION_LOCKSCREEN(1039),
    @UiEvent(doc = "The media carousel moved to the dream state")
    MEDIA_CAROUSEL_LOCATION_DREAM(1040),
    @UiEvent(doc = "A media recommendation card was added to the media carousel")
    MEDIA_RECOMMENDATION_ADDED(1041),
    @UiEvent(doc = "A media recommendation card was removed from the media carousel")
    MEDIA_RECOMMENDATION_REMOVED(1042),
    @UiEvent(doc = "An existing media control was made active as a recommendation")
    MEDIA_RECOMMENDATION_ACTIVATED(1043),
    @UiEvent(doc = "User tapped on an item in a media recommendation card")
    MEDIA_RECOMMENDATION_ITEM_TAP(1044),
    @UiEvent(doc = "User tapped on a media recommendation card")
    MEDIA_RECOMMENDATION_CARD_TAP(1045),
    @UiEvent(doc = "User opened the broadcast dialog from a media control")
    MEDIA_OPEN_BROADCAST_DIALOG(1079),
    @UiEvent(doc = "The media carousel contains one media player card")
    MEDIA_CAROUSEL_SINGLE_PLAYER(1244),
    @UiEvent(doc = "The media carousel contains multiple media player cards")
    MEDIA_CAROUSEL_MULTIPLE_PLAYERS(1245);

    override fun getId() = metricId
}
