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

package com.android.systemui.media.controls.ui.binder

import android.app.WallpaperColors
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Trace
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.animation.Expandable
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.shared.model.NUM_REQUIRED_RECOMMENDATIONS
import com.android.systemui.media.controls.ui.animation.surfaceFromScheme
import com.android.systemui.media.controls.ui.animation.textPrimaryFromScheme
import com.android.systemui.media.controls.ui.animation.textSecondaryFromScheme
import com.android.systemui.media.controls.ui.controller.MediaViewController
import com.android.systemui.media.controls.ui.util.MediaArtworkHelper
import com.android.systemui.media.controls.ui.view.RecommendationViewHolder
import com.android.systemui.media.controls.ui.viewmodel.MediaRecViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaRecommendationsViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaRecsCardViewModel
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.util.animation.TransitionLayout
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaRecommendationsViewBinder"
private const val MEDIA_REC_SCRIM_START_ALPHA = 0.15f
private const val MEDIA_REC_SCRIM_END_ALPHA = 1.0f

object MediaRecommendationsViewBinder {

    /** Binds recommendations view holder to the given view-model */
    fun bind(
        viewHolder: RecommendationViewHolder,
        viewModel: MediaRecommendationsViewModel,
        mediaViewController: MediaViewController,
        falsingManager: FalsingManager,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
    ) {
        mediaViewController.recsConfigurationChangeListener = this::updateRecommendationsVisibility
        val cardView = viewHolder.recommendations
        cardView.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.mediaRecsCard.collectLatest { viewModel ->
                            viewModel?.let {
                                bindRecsCard(
                                    viewHolder,
                                    it,
                                    mediaViewController,
                                    falsingManager,
                                    backgroundDispatcher,
                                    mainDispatcher,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun bindRecsCard(
        viewHolder: RecommendationViewHolder,
        viewModel: MediaRecsCardViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
    ) {
        // Set up media control location and its listener.
        viewModel.onLocationChanged(viewController.currentEndLocation)
        viewController.locationChangeListener = viewModel.onLocationChanged

        // Bind main card.
        viewHolder.recommendations.contentDescription =
            viewModel.contentDescription.invoke(viewController.isGutsVisible)

        viewHolder.recommendations.setOnClickListener {
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return@setOnClickListener
            viewModel.onClicked(Expandable.fromView(it))
        }

        viewHolder.recommendations.setOnLongClickListener {
            if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY))
                return@setOnLongClickListener true
            if (!viewController.isGutsVisible) {
                openGuts(viewHolder, viewModel, viewController)
            } else {
                closeGuts(viewHolder, viewModel, viewController)
            }
            return@setOnLongClickListener true
        }

        // Bind colors
        val appIcon = viewModel.mediaRecs.first().appIcon
        fetchAndUpdateColors(viewHolder, appIcon, backgroundDispatcher, mainDispatcher)
        // Bind all recommendations.
        bindRecommendationsList(
            viewHolder,
            viewModel.mediaRecs,
            falsingManager,
            backgroundDispatcher,
            mainDispatcher,
        )
        updateRecommendationsVisibility(viewController, viewHolder.recommendations)

        // Set visibility of recommendations.
        val expandedSet: ConstraintSet = viewController.expandedLayout
        val collapsedSet: ConstraintSet = viewController.collapsedLayout
        viewHolder.mediaTitles.forEach {
            setVisibleAndAlpha(expandedSet, it.id, viewModel.areTitlesVisible)
            setVisibleAndAlpha(collapsedSet, it.id, viewModel.areTitlesVisible)
        }
        viewHolder.mediaSubtitles.forEach {
            setVisibleAndAlpha(expandedSet, it.id, viewModel.areSubtitlesVisible)
            setVisibleAndAlpha(collapsedSet, it.id, viewModel.areSubtitlesVisible)
        }

        bindRecommendationsGuts(viewHolder, viewModel, viewController, falsingManager)

        viewController.refreshState()
    }

    private fun bindRecommendationsGuts(
        viewHolder: RecommendationViewHolder,
        viewModel: MediaRecsCardViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
    ) {
        val gutsViewHolder = viewHolder.gutsViewHolder
        val gutsViewModel = viewModel.gutsMenu

        gutsViewHolder.gutsText.text = gutsViewModel.gutsText
        gutsViewHolder.dismissText.visibility = View.VISIBLE
        gutsViewHolder.dismiss.isEnabled = true
        gutsViewHolder.dismiss.setOnClickListener {
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return@setOnClickListener
            closeGuts(viewHolder, viewModel, viewController)
            gutsViewModel.onDismissClicked()
        }

        gutsViewHolder.cancelText.background = gutsViewModel.cancelTextBackground
        gutsViewHolder.cancel.setOnClickListener {
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                closeGuts(viewHolder, viewModel, viewController)
            }
        }

        gutsViewHolder.settings.setOnClickListener {
            if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                gutsViewModel.onSettingsClicked.invoke()
            }
        }

        gutsViewHolder.setDismissible(gutsViewModel.isDismissEnabled)
    }

    private suspend fun bindRecommendationsList(
        viewHolder: RecommendationViewHolder,
        mediaRecs: List<MediaRecViewModel>,
        falsingManager: FalsingManager,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
    ) {
        mediaRecs.forEachIndexed { index, mediaRecViewModel ->
            if (index >= NUM_REQUIRED_RECOMMENDATIONS) return@forEachIndexed

            val appIconView = viewHolder.mediaAppIcons[index]
            appIconView.clearColorFilter()
            appIconView.setImageDrawable(mediaRecViewModel.appIcon)

            val mediaCoverContainer = viewHolder.mediaCoverContainers[index]
            mediaCoverContainer.setOnClickListener {
                if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return@setOnClickListener
                mediaRecViewModel.onClicked(Expandable.fromView(it), index)
            }
            mediaCoverContainer.setOnLongClickListener {
                if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY))
                    return@setOnLongClickListener true
                (it.parent as View).performLongClick()
                return@setOnLongClickListener true
            }

            val mediaCover = viewHolder.mediaCoverItems[index]
            bindRecommendationArtwork(
                mediaCover.context,
                viewHolder,
                mediaRecViewModel,
                index,
                backgroundDispatcher,
                mainDispatcher,
            )
            mediaCover.contentDescription = mediaRecViewModel.contentDescription

            val title = viewHolder.mediaTitles[index]
            title.text = mediaRecViewModel.title

            val subtitle = viewHolder.mediaSubtitles[index]
            subtitle.text = mediaRecViewModel.subtitle

            val progressBar = viewHolder.mediaProgressBars[index]
            progressBar.progress = mediaRecViewModel.progress
            if (mediaRecViewModel.progress == 0) {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun openGuts(
        viewHolder: RecommendationViewHolder,
        viewModel: MediaRecsCardViewModel,
        mediaViewController: MediaViewController,
    ) {
        viewHolder.marquee(true, MediaViewController.GUTS_ANIMATION_DURATION)
        mediaViewController.openGuts()
        viewHolder.recommendations.contentDescription = viewModel.contentDescription.invoke(true)
        viewModel.onLongClicked.invoke()
    }

    private fun closeGuts(
        viewHolder: RecommendationViewHolder,
        mediaRecsCardViewModel: MediaRecsCardViewModel,
        mediaViewController: MediaViewController,
    ) {
        viewHolder.marquee(false, MediaViewController.GUTS_ANIMATION_DURATION)
        mediaViewController.closeGuts(false)
        viewHolder.recommendations.contentDescription =
            mediaRecsCardViewModel.contentDescription.invoke(false)
    }

    private fun setVisibleAndAlpha(set: ConstraintSet, resId: Int, visible: Boolean) {
        set.setVisibility(resId, if (visible) ConstraintSet.VISIBLE else ConstraintSet.GONE)
        set.setAlpha(resId, if (visible) 1.0f else 0.0f)
    }

    fun updateRecommendationsVisibility(
        mediaViewController: MediaViewController,
        cardView: TransitionLayout,
    ) {
        val fittedRecsNum = getNumberOfFittedRecommendations(cardView.context)
        val expandedSet = mediaViewController.expandedLayout
        val collapsedSet = mediaViewController.collapsedLayout
        val mediaCoverContainers = getMediaCoverContainers(cardView)
        // Hide media cover that cannot fit in the recommendation card.
        mediaCoverContainers.forEachIndexed { index, container ->
            setVisibleAndAlpha(expandedSet, container.id, index < fittedRecsNum)
            setVisibleAndAlpha(collapsedSet, container.id, index < fittedRecsNum)
        }
    }

    private fun getMediaCoverContainers(cardView: TransitionLayout): List<ViewGroup> {
        return listOf<ViewGroup>(
            cardView.requireViewById(R.id.media_cover1_container),
            cardView.requireViewById(R.id.media_cover2_container),
            cardView.requireViewById(R.id.media_cover3_container),
        )
    }

    private fun getNumberOfFittedRecommendations(context: Context): Int {
        val res = context.resources
        val config = res.configuration
        val defaultDpWidth = res.getInteger(R.integer.default_qs_media_rec_width_dp)
        val recCoverWidth =
            (res.getDimensionPixelSize(R.dimen.qs_media_rec_album_width) +
                res.getDimensionPixelSize(R.dimen.qs_media_info_spacing) * 2)

        // On landscape, media controls should take half of the screen width.
        val displayAvailableDpWidth =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                config.screenWidthDp / 2
            } else {
                config.screenWidthDp
            }
        val fittedNum =
            if (displayAvailableDpWidth > defaultDpWidth) {
                val recCoverDefaultWidth =
                    res.getDimensionPixelSize(R.dimen.qs_media_rec_default_width)
                recCoverDefaultWidth / recCoverWidth
            } else {
                val displayAvailableWidth =
                    TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            displayAvailableDpWidth.toFloat(),
                            res.displayMetrics,
                        )
                        .toInt()
                displayAvailableWidth / recCoverWidth
            }
        return min(fittedNum.toDouble(), NUM_REQUIRED_RECOMMENDATIONS.toDouble()).toInt()
    }

    private suspend fun bindRecommendationArtwork(
        context: Context,
        viewHolder: RecommendationViewHolder,
        viewModel: MediaRecViewModel,
        index: Int,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
    ) {
        val traceCookie = viewHolder.hashCode()
        val traceName = "MediaRecommendationsViewBinder#bindRecommendationArtwork"
        Trace.beginAsyncSection(traceName, traceCookie)

        // Capture width & height from views in foreground for artwork scaling in background
        val width = context.resources.getDimensionPixelSize(R.dimen.qs_media_rec_album_width)
        val height =
            context.resources.getDimensionPixelSize(R.dimen.qs_media_rec_album_height_expanded)

        withContext(backgroundDispatcher) {
            val artwork =
                getRecCoverBackground(
                    context,
                    viewModel.albumIcon,
                    width,
                    height,
                    backgroundDispatcher,
                )
            withContext(mainDispatcher) {
                val mediaCover = viewHolder.mediaCoverItems[index]
                val coverMatrix = Matrix(mediaCover.imageMatrix)
                coverMatrix.postScale(1.25f, 1.25f, 0.5f * width, 0.5f * height)
                mediaCover.imageMatrix = coverMatrix
                mediaCover.setImageDrawable(artwork)
            }
        }
    }

    /** Returns the recommendation album cover of [width]x[height] size. */
    private suspend fun getRecCoverBackground(
        context: Context,
        icon: Icon?,
        width: Int,
        height: Int,
        backgroundDispatcher: CoroutineDispatcher,
    ): Drawable =
        withContext(backgroundDispatcher) {
            return@withContext MediaArtworkHelper.getWallpaperColor(
                    context,
                    backgroundDispatcher,
                    icon,
                    TAG,
                )
                ?.let { wallpaperColors ->
                    addGradientToRecommendationAlbum(
                        context,
                        icon!!,
                        ColorScheme(wallpaperColors, true, Style.CONTENT),
                        width,
                        height,
                    )
                } ?: ColorDrawable(Color.TRANSPARENT)
        }

    private fun addGradientToRecommendationAlbum(
        context: Context,
        artworkIcon: Icon,
        mutableColorScheme: ColorScheme,
        width: Int,
        height: Int,
    ): LayerDrawable {
        // First try scaling rec card using bitmap drawable.
        // If returns null, set drawable bounds.
        val albumArt =
            getScaledRecommendationCover(context, artworkIcon, width, height)
                ?: MediaArtworkHelper.getScaledBackground(context, artworkIcon, width, height)
        val gradient =
            AppCompatResources.getDrawable(context, R.drawable.qs_media_rec_scrim)?.mutate()
                as GradientDrawable
        return MediaArtworkHelper.setUpGradientColorOnDrawable(
            albumArt,
            gradient,
            mutableColorScheme,
            MEDIA_REC_SCRIM_START_ALPHA,
            MEDIA_REC_SCRIM_END_ALPHA,
        )
    }

    /** Returns a [Drawable] of a given [artworkIcon] scaled to [width]x[height] size, . */
    private fun getScaledRecommendationCover(
        context: Context,
        artworkIcon: Icon,
        width: Int,
        height: Int,
    ): Drawable? {
        check(width > 0) { "Width must be a positive number but was $width" }
        check(height > 0) { "Height must be a positive number but was $height" }

        return if (
            artworkIcon.type == Icon.TYPE_BITMAP || artworkIcon.type == Icon.TYPE_ADAPTIVE_BITMAP
        ) {
            artworkIcon.bitmap?.let {
                val bitmap = Bitmap.createScaledBitmap(it, width, height, false)
                BitmapDrawable(context.resources, bitmap)
            }
        } else {
            null
        }
    }

    private suspend fun fetchAndUpdateColors(
        viewHolder: RecommendationViewHolder,
        appIcon: Drawable,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
    ) =
        withContext(backgroundDispatcher) {
            val colorScheme =
                ColorScheme(WallpaperColors.fromDrawable(appIcon), /* darkTheme= */ true)
            withContext(mainDispatcher) {
                val backgroundColor = surfaceFromScheme(colorScheme)
                val textPrimaryColor = textPrimaryFromScheme(colorScheme)
                val textSecondaryColor = textSecondaryFromScheme(colorScheme)

                viewHolder.cardTitle.setTextColor(textPrimaryColor)
                viewHolder.recommendations.setBackgroundTintList(
                    ColorStateList.valueOf(backgroundColor)
                )

                viewHolder.mediaTitles.forEach { it.setTextColor(textPrimaryColor) }
                viewHolder.mediaSubtitles.forEach { it.setTextColor(textSecondaryColor) }
                viewHolder.mediaProgressBars.forEach {
                    it.progressTintList = ColorStateList.valueOf(textPrimaryColor)
                }

                viewHolder.gutsViewHolder.setColors(colorScheme)
            }
        }
}
