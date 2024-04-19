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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Process
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.logging.InstanceId
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaRecommendationsInteractor
import com.android.systemui.media.controls.shared.model.MediaRecModel
import com.android.systemui.media.controls.shared.model.MediaRecommendationsModel
import com.android.systemui.media.controls.ui.animation.accentPrimaryFromScheme
import com.android.systemui.media.controls.ui.animation.surfaceFromScheme
import com.android.systemui.media.controls.ui.animation.textPrimaryFromScheme
import com.android.systemui.media.controls.ui.animation.textSecondaryFromScheme
import com.android.systemui.media.controls.ui.controller.MediaViewController.Companion.GUTS_ANIMATION_DURATION
import com.android.systemui.media.controls.ui.util.MediaArtworkHelper
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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

    val mediaRecsCard: Flow<MediaRecsCardViewModel?> =
        interactor.recommendations
            .map { recsCard -> toRecsViewModel(recsCard) }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    /**
     * Called whenever the recommendation has been expired or removed by the user. This method
     * removes the recommendation card entirely from the carousel.
     */
    private fun onMediaRecommendationsDismissed(
        key: String,
        uid: Int,
        packageName: String,
        dismissIntent: Intent?,
        instanceId: InstanceId?
    ) {
        // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_DISMISS_EVENT).
        logger.logLongPressDismiss(uid, packageName, instanceId)
        interactor.removeMediaRecommendations(key, dismissIntent, GUTS_DISMISS_DELAY_MS_DURATION)
    }

    private fun onClicked(
        expandable: Expandable,
        intent: Intent?,
        packageName: String,
        instanceId: InstanceId?,
        index: Int
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
        // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_CLICK_EVENT).

        // set the package name of the player added by recommendation once the media is loaded.
        interactor.switchToMediaControl(packageName)

        interactor.startClickIntent(expandable, intent)
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

        val scheme =
            MediaArtworkHelper.getColorScheme(applicationContext, model.packageName, TAG)
                ?: return null

        // Capture width & height from views in foreground for artwork scaling in background
        val width =
            applicationContext.resources.getDimensionPixelSize(R.dimen.qs_media_rec_album_width)
        val height =
            applicationContext.resources.getDimensionPixelSize(
                R.dimen.qs_media_rec_album_height_expanded
            )

        val appIcon = applicationContext.packageManager.getApplicationIcon(model.packageName)
        val textPrimaryColor = textPrimaryFromScheme(scheme)
        val textSecondaryColor = textSecondaryFromScheme(scheme)
        val backgroundColor = surfaceFromScheme(scheme)

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
                    titleColor = textPrimaryColor,
                    subtitle = mediaRecModel.subtitle ?: "",
                    subtitleColor = textSecondaryColor,
                    progress = (progress * 100).toInt(),
                    progressColor = textPrimaryColor,
                    albumIcon =
                        getRecCoverBackground(
                            mediaRecModel.icon,
                            width,
                            height,
                        ),
                    appIcon = appIcon,
                    onClicked = { expandable, index ->
                        onClicked(
                            expandable,
                            mediaRecModel.intent,
                            model.packageName,
                            model.instanceId,
                            index,
                        )
                    }
                )
            }
        // Subtitles should only be visible if titles are visible.
        areSubtitlesVisible = areTitlesVisible && areSubtitlesVisible

        return MediaRecsCardViewModel(
            contentDescription = { gutsVisible ->
                if (gutsVisible) {
                    applicationContext.getString(
                        R.string.controls_media_close_session,
                        model.appName
                    )
                } else {
                    applicationContext.getString(R.string.controls_media_smartspace_rec_header)
                }
            },
            cardColor = backgroundColor,
            cardTitleColor = textPrimaryColor,
            onClicked = { expandable ->
                onClicked(
                    expandable,
                    model.dismissIntent,
                    model.packageName,
                    model.instanceId,
                    index = -1
                )
            },
            onLongClicked = {
                logger.logLongPressOpen(model.uid, model.packageName, model.instanceId)
            },
            mediaRecs = mediaRecs,
            areTitlesVisible = areTitlesVisible,
            areSubtitlesVisible = areSubtitlesVisible,
            gutsMenu = toGutsViewModel(model, scheme),
        )
    }

    private fun toGutsViewModel(
        model: MediaRecommendationsModel,
        scheme: ColorScheme
    ): GutsViewModel {
        return GutsViewModel(
            gutsText =
                applicationContext.getString(R.string.controls_media_close_session, model.appName),
            textPrimaryColor = textPrimaryFromScheme(scheme),
            accentPrimaryColor = accentPrimaryFromScheme(scheme),
            surfaceColor = surfaceFromScheme(scheme),
            onDismissClicked = {
                onMediaRecommendationsDismissed(
                    model.key,
                    model.uid,
                    model.packageName,
                    model.dismissIntent,
                    model.instanceId
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

    /** Returns the recommendation album cover of [width]x[height] size. */
    private suspend fun getRecCoverBackground(icon: Icon?, width: Int, height: Int): Drawable =
        withContext(backgroundDispatcher) {
            return@withContext MediaArtworkHelper.getWallpaperColor(
                    applicationContext,
                    backgroundDispatcher,
                    icon,
                    TAG,
                )
                ?.let { wallpaperColors ->
                    addGradientToRecommendationAlbum(
                        icon!!,
                        ColorScheme(wallpaperColors, true, Style.CONTENT),
                        width,
                        height
                    )
                }
                ?: ColorDrawable(Color.TRANSPARENT)
        }

    private fun addGradientToRecommendationAlbum(
        artworkIcon: Icon,
        mutableColorScheme: ColorScheme,
        width: Int,
        height: Int
    ): LayerDrawable {
        // First try scaling rec card using bitmap drawable.
        // If returns null, set drawable bounds.
        val albumArt =
            getScaledRecommendationCover(artworkIcon, width, height)
                ?: MediaArtworkHelper.getScaledBackground(
                    applicationContext,
                    artworkIcon,
                    width,
                    height
                )
        val gradient =
            AppCompatResources.getDrawable(applicationContext, R.drawable.qs_media_rec_scrim)
                ?.mutate() as GradientDrawable
        return MediaArtworkHelper.setUpGradientColorOnDrawable(
            albumArt,
            gradient,
            mutableColorScheme,
            MEDIA_REC_SCRIM_START_ALPHA,
            MEDIA_REC_SCRIM_END_ALPHA
        )
    }

    private fun setUpMediaRecContentDescription(
        mediaRec: MediaRecModel,
        appName: CharSequence?
    ): CharSequence {
        // Set up the accessibility label for the media item.
        val artistName = mediaRec.extras?.getString(KEY_SMARTSPACE_ARTIST_NAME, "")
        return if (artistName.isNullOrEmpty()) {
            applicationContext.getString(
                R.string.controls_media_smartspace_rec_item_no_artist_description,
                mediaRec.title,
                appName
            )
        } else {
            applicationContext.getString(
                R.string.controls_media_smartspace_rec_item_description,
                mediaRec.title,
                artistName,
                appName
            )
        }
    }

    /** Returns a [Drawable] of a given [artworkIcon] scaled to [width]x[height] size, . */
    private fun getScaledRecommendationCover(
        artworkIcon: Icon,
        width: Int,
        height: Int
    ): Drawable? {
        check(width > 0) { "Width must be a positive number but was $width" }
        check(height > 0) { "Height must be a positive number but was $height" }

        return if (
            artworkIcon.type == Icon.TYPE_BITMAP || artworkIcon.type == Icon.TYPE_ADAPTIVE_BITMAP
        ) {
            artworkIcon.bitmap?.let {
                val bitmap = Bitmap.createScaledBitmap(it, width, height, false)
                BitmapDrawable(applicationContext.resources, bitmap)
            }
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "MediaRecommendationsViewModel"
        private const val KEY_SMARTSPACE_ARTIST_NAME = "artist_name"
        private const val MEDIA_REC_SCRIM_START_ALPHA = 0.15f
        private const val MEDIA_REC_SCRIM_END_ALPHA = 1.0f
        /**
         * Delay duration is based on [GUTS_ANIMATION_DURATION], it should have 100 ms increase in
         * order to let the animation end.
         */
        private const val GUTS_DISMISS_DELAY_MS_DURATION = 334L
    }
}
