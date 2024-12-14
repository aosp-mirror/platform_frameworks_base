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

package com.android.systemui.media.controls.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import com.android.internal.logging.InstanceId
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaRecommendationsInteractor
import com.android.systemui.media.controls.shared.model.MediaRecModel
import com.android.systemui.media.controls.shared.model.MediaRecommendationsModel
import com.android.systemui.media.controls.shared.model.NUM_REQUIRED_RECOMMENDATIONS
import com.android.systemui.media.controls.ui.controller.MediaViewController.Companion.GUTS_ANIMATION_DURATION
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaSmartspaceLogger.Companion.SMARTSPACE_CARD_CLICK_EVENT
import com.android.systemui.media.controls.util.MediaSmartspaceLogger.Companion.SMARTSPACE_CARD_DISMISS_EVENT
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Models UI state and handles user input for media recommendations */
@SysUISingleton
class MediaRecommendationsViewModel
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val interactor: MediaRecommendationsInteractor,
    private val logger: MediaUiEventLogger,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaRecsCard: Flow<MediaRecsCardViewModel?> =
        interactor.onAnyMediaConfigurationChange
            .flatMapLatest {
                interactor.recommendations.map { recsCard -> toRecsViewModel(recsCard) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    private var location = -1

    /**
     * Called whenever the recommendation has been expired or removed by the user. This method
     * removes the recommendation card entirely from the carousel.
     */
    private fun onMediaRecommendationsDismissed(
        key: String,
        uid: Int,
        packageName: String,
        dismissIntent: Intent?,
        instanceId: InstanceId?,
    ) {
        logger.logLongPressDismiss(uid, packageName, instanceId)
        interactor.removeMediaRecommendations(
            key,
            dismissIntent,
            GUTS_DISMISS_DELAY_MS_DURATION,
            SMARTSPACE_CARD_DISMISS_EVENT,
            location,
        )
    }

    private fun onClicked(
        expandable: Expandable,
        intent: Intent?,
        packageName: String,
        instanceId: InstanceId?,
        index: Int,
    ) {
        if (intent == null || intent.extras == null) {
            Log.e(TAG, "No tap action can be set up")
            return
        }

        if (index == -1) {
            logger.logRecommendationCardTap(packageName, instanceId)
        } else {
            logger.logRecommendationItemTap(packageName, instanceId, index)
        }

        // set the package name of the player added by recommendation once the media is loaded.
        interactor.switchToMediaControl(packageName)

        interactor.startClickIntent(
            expandable,
            intent,
            SMARTSPACE_CARD_CLICK_EVENT,
            location,
            index,
            NUM_REQUIRED_RECOMMENDATIONS,
        )
    }

    private suspend fun toRecsViewModel(model: MediaRecommendationsModel): MediaRecsCardViewModel? {
        if (!model.areRecommendationsValid) {
            Log.e(TAG, "Received an invalid recommendation list")
            return null
        }
        if (model.appName == null || model.uid == Process.INVALID_UID) {
            Log.w(TAG, "Fail to get media recommendation's app info")
            return null
        }

        val appIcon = getIconFromApp(model.packageName) ?: return null

        var areTitlesVisible = false
        var areSubtitlesVisible = false
        val mediaRecs =
            model.mediaRecs.map { mediaRecModel ->
                areTitlesVisible = areTitlesVisible || !mediaRecModel.title.isNullOrEmpty()
                areSubtitlesVisible = areSubtitlesVisible || !mediaRecModel.subtitle.isNullOrEmpty()
                val progress = MediaDataUtils.getDescriptionProgress(mediaRecModel.extras) ?: 0.0
                MediaRecViewModel(
                    contentDescription =
                        setUpMediaRecContentDescription(mediaRecModel, model.appName),
                    title = mediaRecModel.title ?: "",
                    subtitle = mediaRecModel.subtitle ?: "",
                    progress = (progress * 100).toInt(),
                    albumIcon = mediaRecModel.icon,
                    appIcon = appIcon,
                    onClicked = { expandable, index ->
                        onClicked(
                            expandable,
                            mediaRecModel.intent,
                            model.packageName,
                            model.instanceId,
                            index,
                        )
                    },
                )
            }
        // Subtitles should only be visible if titles are visible.
        areSubtitlesVisible = areTitlesVisible && areSubtitlesVisible

        return MediaRecsCardViewModel(
            contentDescription = { gutsVisible ->
                if (gutsVisible) {
                    applicationContext.getString(
                        R.string.controls_media_close_session,
                        model.appName,
                    )
                } else {
                    applicationContext.getString(R.string.controls_media_smartspace_rec_header)
                }
            },
            onClicked = { expandable ->
                onClicked(
                    expandable,
                    model.dismissIntent,
                    model.packageName,
                    model.instanceId,
                    index = -1,
                )
            },
            onLongClicked = {
                logger.logLongPressOpen(model.uid, model.packageName, model.instanceId)
            },
            mediaRecs = mediaRecs,
            areTitlesVisible = areTitlesVisible,
            areSubtitlesVisible = areSubtitlesVisible,
            gutsMenu = toGutsViewModel(model),
            onLocationChanged = { location = it },
        )
    }

    private fun toGutsViewModel(model: MediaRecommendationsModel): GutsViewModel {
        return GutsViewModel(
            gutsText =
                applicationContext.getString(R.string.controls_media_close_session, model.appName),
            onDismissClicked = {
                onMediaRecommendationsDismissed(
                    model.key,
                    model.uid,
                    model.packageName,
                    model.dismissIntent,
                    model.instanceId,
                )
            },
            cancelTextBackground =
                applicationContext.getDrawable(R.drawable.qs_media_outline_button),
            onSettingsClicked = {
                logger.logLongPressSettings(model.uid, model.packageName, model.instanceId)
                interactor.startSettings()
            },
        )
    }

    private fun setUpMediaRecContentDescription(
        mediaRec: MediaRecModel,
        appName: CharSequence?,
    ): CharSequence {
        // Set up the accessibility label for the media item.
        val artistName = mediaRec.extras?.getString(KEY_SMARTSPACE_ARTIST_NAME, "")
        return if (artistName.isNullOrEmpty()) {
            applicationContext.getString(
                R.string.controls_media_smartspace_rec_item_no_artist_description,
                mediaRec.title,
                appName,
            )
        } else {
            applicationContext.getString(
                R.string.controls_media_smartspace_rec_item_description,
                mediaRec.title,
                artistName,
                appName,
            )
        }
    }

    private fun getIconFromApp(packageName: String): Drawable? {
        return try {
            applicationContext.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot find icon for package $packageName", e)
            null
        }
    }

    companion object {
        private const val TAG = "MediaRecommendationsViewModel"
        private const val KEY_SMARTSPACE_ARTIST_NAME = "artist_name"
        /**
         * Delay duration is based on [GUTS_ANIMATION_DURATION], it should have 100 ms increase in
         * order to let the animation end.
         */
        private const val GUTS_DISMISS_DELAY_MS_DURATION = 334L
    }
}
