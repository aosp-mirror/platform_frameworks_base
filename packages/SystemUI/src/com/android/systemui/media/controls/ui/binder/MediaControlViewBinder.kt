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

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Trace
import android.util.Pair
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.settingslib.widget.AdaptiveIcon
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.ui.animation.AnimationBindHandler
import com.android.systemui.media.controls.ui.animation.ColorSchemeTransition
import com.android.systemui.media.controls.ui.controller.MediaViewController
import com.android.systemui.media.controls.ui.util.MediaArtworkHelper
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.viewmodel.MediaActionViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaControlViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaControlViewModel.Companion.MEDIA_PLAYER_SCRIM_END_ALPHA
import com.android.systemui.media.controls.ui.viewmodel.MediaControlViewModel.Companion.MEDIA_PLAYER_SCRIM_START_ALPHA
import com.android.systemui.media.controls.ui.viewmodel.MediaControlViewModel.Companion.SEMANTIC_ACTIONS_ALL
import com.android.systemui.media.controls.ui.viewmodel.MediaControlViewModel.Companion.SEMANTIC_ACTIONS_COMPACT
import com.android.systemui.media.controls.ui.viewmodel.MediaOutputSwitcherViewModel
import com.android.systemui.media.controls.ui.viewmodel.MediaPlayerViewModel
import com.android.systemui.media.controls.util.MediaDataUtils
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.surfaceeffects.ripple.MultiRippleView
import com.android.systemui.surfaceeffects.ripple.RippleAnimation
import com.android.systemui.surfaceeffects.ripple.RippleAnimationConfig
import com.android.systemui.surfaceeffects.ripple.RippleShader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MediaControlViewBinder {

    fun bind(
        viewHolder: MediaViewHolder,
        viewModel: MediaControlViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
        @Background backgroundDispatcher: CoroutineDispatcher,
        @Main mainDispatcher: CoroutineDispatcher,
        mediaFlags: MediaFlags,
    ) {
        val mediaCard = viewHolder.player
        mediaCard.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.player.collectLatest { playerViewModel ->
                        playerViewModel?.let {
                            bindMediaCard(
                                viewHolder,
                                viewController,
                                it,
                                falsingManager,
                                backgroundDispatcher,
                                mainDispatcher,
                                mediaFlags
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun bindMediaCard(
        viewHolder: MediaViewHolder,
        viewController: MediaViewController,
        viewModel: MediaPlayerViewModel,
        falsingManager: FalsingManager,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
        mediaFlags: MediaFlags,
    ) {
        with(viewHolder) {
            // AlbumView uses a hardware layer so that clipping of the foreground is handled with
            // clipping the album art. Otherwise album art shows through at the edges.
            albumView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            turbulenceNoiseView.setBlendMode(BlendMode.SCREEN)
            loadingEffectView.setBlendMode(BlendMode.SCREEN)
            loadingEffectView.visibility = View.INVISIBLE

            player.contentDescription =
                viewModel.contentDescription.invoke(viewController.isGutsVisible)
            player.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    if (!viewController.isGutsVisible) {
                        viewModel.onClicked(Expandable.fromView(player))
                    }
                }
            }
            player.setOnLongClickListener {
                if (!falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                    if (!viewController.isGutsVisible) {
                        openGuts(viewHolder, viewController, viewModel)
                    } else {
                        closeGuts(viewHolder, viewController, viewModel)
                    }
                }
                return@setOnLongClickListener true
            }
        }

        viewController.bindSeekBar(viewModel.onSeek, viewModel.onBindSeekbar)
        bindOutputSwitcherModel(
            viewHolder,
            viewModel.outputSwitcher,
            viewController,
            falsingManager
        )
        bindGutsViewModel(viewHolder, viewModel, viewController, falsingManager)
        bindActionButtons(viewHolder, viewModel, viewController, falsingManager)
        bindScrubbingTime(viewHolder, viewModel, viewController)

        val isSongUpdated = bindSongMetadata(viewHolder, viewModel, viewController)

        bindArtworkAndColor(
            viewHolder,
            viewModel,
            viewController,
            backgroundDispatcher,
            mainDispatcher,
            isSongUpdated
        )

        // TODO: We don't need to refresh this state constantly, only if the
        // state actually changed to something which might impact the
        // measurement. State refresh interferes with the translation
        // animation, only run it if it's not running.
        if (!viewController.metadataAnimationHandler.isRunning) {
            // Don't refresh in scene framework, because it will calculate
            // with invalid layout sizes
            if (!mediaFlags.isSceneContainerEnabled()) {
                viewController.refreshState()
            }
        }

        if (viewModel.playTurbulenceNoise) {
            viewController.setUpTurbulenceNoise()
        }
    }

    private fun bindOutputSwitcherModel(
        viewHolder: MediaViewHolder,
        viewModel: MediaOutputSwitcherViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
    ) {
        with(viewHolder.seamless) {
            visibility = View.VISIBLE
            isEnabled = viewModel.isTapEnabled
            contentDescription = viewModel.deviceString
            setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.MODERATE_PENALTY)) {
                    viewModel.onClicked.invoke(Expandable.fromView(viewHolder.seamlessButton))
                }
            }
        }
        when (viewModel.deviceIcon) {
            is Icon.Loaded -> {
                val icon = viewModel.deviceIcon.drawable
                if (icon is AdaptiveIcon) {
                    icon.setBackgroundColor(viewController.colorSchemeTransition.bgColor)
                }
                viewHolder.seamlessIcon.setImageDrawable(icon)
            }
            is Icon.Resource -> viewHolder.seamlessIcon.setImageResource(viewModel.deviceIcon.res)
        }
        viewHolder.seamlessButton.alpha = viewModel.alpha
        viewHolder.seamlessText.text = viewModel.deviceString
    }

    private fun bindGutsViewModel(
        viewHolder: MediaViewHolder,
        viewModel: MediaPlayerViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
    ) {
        val gutsViewHolder = viewHolder.gutsViewHolder
        val model = viewModel.gutsMenu
        with(gutsViewHolder) {
            gutsText.text = model.gutsText
            dismissText.visibility = if (model.isDismissEnabled) View.VISIBLE else View.GONE
            dismiss.isEnabled = model.isDismissEnabled
            dismiss.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    model.onDismissClicked.invoke()
                }
            }
            cancelText.background = model.cancelTextBackground
            cancel.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    closeGuts(viewHolder, viewController, viewModel)
                }
            }
            settings.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    model.onSettingsClicked.invoke()
                }
            }
            setDismissible(model.isDismissEnabled)
            setTextPrimaryColor(model.textPrimaryColor)
            setAccentPrimaryColor(model.accentPrimaryColor)
            setSurfaceColor(model.surfaceColor)
        }
    }

    private fun bindActionButtons(
        viewHolder: MediaViewHolder,
        viewModel: MediaPlayerViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
    ) {
        val genericButtons = MediaViewHolder.genericButtonIds.map { viewHolder.getAction(it) }
        val expandedSet = viewController.expandedLayout
        val collapsedSet = viewController.collapsedLayout
        if (viewModel.useSemanticActions) {
            // Hide all generic buttons
            genericButtons.forEach {
                setVisibleAndAlpha(expandedSet, it.id, false)
                setVisibleAndAlpha(collapsedSet, it.id, false)
            }

            SEMANTIC_ACTIONS_ALL.forEachIndexed { index, id ->
                val buttonView = viewHolder.getAction(id)
                val buttonModel = viewModel.actionButtons[index]
                if (buttonView.id == R.id.actionPrev) {
                    viewController.setUpPrevButtonInfo(
                        buttonModel.isEnabled,
                        buttonModel.notVisibleValue
                    )
                } else if (buttonView.id == R.id.actionNext) {
                    viewController.setUpNextButtonInfo(
                        buttonModel.isEnabled,
                        buttonModel.notVisibleValue
                    )
                }
                val animHandler = (buttonView.tag ?: AnimationBindHandler()) as AnimationBindHandler
                animHandler.tryExecute {
                    if (buttonModel.isEnabled) {
                        if (animHandler.updateRebindId(buttonModel.rebindId)) {
                            animHandler.unregisterAll()
                            animHandler.tryRegister(buttonModel.icon)
                            animHandler.tryRegister(buttonModel.background)
                            bindButtonCommon(
                                buttonView,
                                viewHolder.multiRippleView,
                                buttonModel,
                                viewController,
                                falsingManager,
                            )
                        }
                    } else {
                        animHandler.unregisterAll()
                        clearButton(buttonView)
                    }
                    val visible =
                        buttonModel.isEnabled &&
                            (buttonModel.isVisibleWhenScrubbing || !viewController.isScrubbing)
                    setSemanticButtonVisibleAndAlpha(
                        viewHolder.getAction(id),
                        viewController.expandedLayout,
                        viewController.collapsedLayout,
                        visible,
                        buttonModel.notVisibleValue,
                        buttonModel.showInCollapsed
                    )
                }
            }
        } else {
            // Hide buttons that only appear for semantic actions
            SEMANTIC_ACTIONS_COMPACT.forEach { buttonId ->
                setVisibleAndAlpha(expandedSet, buttonId, visible = false)
                setVisibleAndAlpha(expandedSet, buttonId, visible = false)
            }

            // Set all generic buttons
            genericButtons.forEachIndexed { index, button ->
                if (index < viewModel.actionButtons.size) {
                    val action = viewModel.actionButtons[index]
                    bindButtonCommon(
                        button,
                        viewHolder.multiRippleView,
                        action,
                        viewController,
                        falsingManager,
                    )
                    setVisibleAndAlpha(expandedSet, button.id, visible = true)
                    setVisibleAndAlpha(collapsedSet, button.id, visible = action.showInCollapsed)
                } else {
                    // Hide any unused buttons
                    clearButton(button)
                    setVisibleAndAlpha(expandedSet, button.id, visible = false)
                    setVisibleAndAlpha(collapsedSet, button.id, visible = false)
                }
            }
        }
        updateSeekBarVisibility(viewController.expandedLayout, viewController.isSeekBarEnabled)
    }

    private fun bindButtonCommon(
        button: ImageButton,
        multiRippleView: MultiRippleView,
        actionViewModel: MediaActionViewModel,
        viewController: MediaViewController,
        falsingManager: FalsingManager,
    ) {
        button.setImageDrawable(actionViewModel.icon)
        button.background = actionViewModel.background
        button.contentDescription = actionViewModel.contentDescription
        button.isEnabled = actionViewModel.isEnabled
        if (actionViewModel.isEnabled) {
            button.setOnClickListener {
                if (!falsingManager.isFalseTap(FalsingManager.MODERATE_PENALTY)) {
                    actionViewModel.onClicked.invoke(it.id)

                    viewController.multiRippleController.play(
                        createTouchRippleAnimation(
                            button,
                            viewController.colorSchemeTransition,
                            multiRippleView
                        )
                    )

                    if (actionViewModel.icon is Animatable) {
                        actionViewModel.icon.start()
                    }

                    if (actionViewModel.background is Animatable) {
                        actionViewModel.background.start()
                    }
                }
            }
        }
    }

    private fun bindSongMetadata(
        viewHolder: MediaViewHolder,
        viewModel: MediaPlayerViewModel,
        viewController: MediaViewController,
    ): Boolean {
        val expandedSet = viewController.expandedLayout
        val collapsedSet = viewController.collapsedLayout

        return viewController.metadataAnimationHandler.setNext(
            Triple(viewModel.titleName, viewModel.artistName, viewModel.isExplicitVisible),
            {
                viewHolder.titleText.text = viewModel.titleName
                viewHolder.artistText.text = viewModel.artistName
                setVisibleAndAlpha(
                    expandedSet,
                    R.id.media_explicit_indicator,
                    viewModel.isExplicitVisible
                )
                setVisibleAndAlpha(
                    collapsedSet,
                    R.id.media_explicit_indicator,
                    viewModel.isExplicitVisible
                )

                // refreshState is required here to resize the text views (and prevent ellipsis)
                viewController.refreshState()
            },
            {
                // After finishing the enter animation, we refresh state. This could pop if
                // something is incorrectly bound, but needs to be run if other elements were
                // updated while the enter animation was running
                viewController.refreshState()
            }
        )
    }

    private suspend fun bindArtworkAndColor(
        viewHolder: MediaViewHolder,
        viewModel: MediaPlayerViewModel,
        viewController: MediaViewController,
        backgroundDispatcher: CoroutineDispatcher,
        mainDispatcher: CoroutineDispatcher,
        updateBackground: Boolean,
    ) {
        val traceCookie = viewHolder.hashCode()
        val traceName = "MediaControlViewBinder#bindArtworkAndColor"
        Trace.beginAsyncSection(traceName, traceCookie)
        if (updateBackground) {
            viewController.isArtworkBound = false
        }
        // Capture width & height from views in foreground for artwork scaling in background
        val width = viewController.widthInSceneContainerPx
        val height = viewController.heightInSceneContainerPx
        withContext(backgroundDispatcher) {
            val artwork =
                if (viewModel.shouldAddGradient) {
                    addGradientToPlayerAlbum(
                        viewHolder.albumView.context,
                        viewModel.backgroundCover!!,
                        viewModel.colorScheme,
                        width,
                        height
                    )
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
            withContext(mainDispatcher) {
                // Transition Colors to current color scheme
                val colorSchemeChanged =
                    viewController.colorSchemeTransition.updateColorScheme(viewModel.colorScheme)
                val albumView = viewHolder.albumView

                // Set up width of album view constraint.
                viewController.expandedLayout.getConstraint(albumView.id).layout.mWidth = width
                viewController.collapsedLayout.getConstraint(albumView.id).layout.mWidth = width

                albumView.setPadding(0, 0, 0, 0)
                if (
                    updateBackground ||
                        colorSchemeChanged ||
                        (!viewController.isArtworkBound && viewModel.shouldAddGradient)
                ) {
                    viewController.prevArtwork?.let {
                        // Since we throw away the last transition, this will pop if your
                        // backgrounds are cycled too fast (or the correct background arrives very
                        // soon after the metadata changes).
                        val transitionDrawable = TransitionDrawable(arrayOf(it, artwork))

                        scaleTransitionDrawableLayer(transitionDrawable, 0, width, height)
                        scaleTransitionDrawableLayer(transitionDrawable, 1, width, height)
                        transitionDrawable.setLayerGravity(0, Gravity.CENTER)
                        transitionDrawable.setLayerGravity(1, Gravity.CENTER)
                        transitionDrawable.isCrossFadeEnabled = true

                        albumView.setImageDrawable(transitionDrawable)
                        transitionDrawable.startTransition(
                            if (viewModel.shouldAddGradient) 333 else 80
                        )
                    }
                        ?: albumView.setImageDrawable(artwork)
                }
                viewController.isArtworkBound = viewModel.shouldAddGradient
                viewController.prevArtwork = artwork

                if (viewModel.useGrayColorFilter) {
                    // Used for resume players to use launcher icon
                    viewHolder.appIcon.colorFilter = getGrayscaleFilter()
                    when (viewModel.launcherIcon) {
                        is Icon.Loaded ->
                            viewHolder.appIcon.setImageDrawable(viewModel.launcherIcon.drawable)
                        is Icon.Resource ->
                            viewHolder.appIcon.setImageResource(viewModel.launcherIcon.res)
                    }
                } else {
                    viewHolder.appIcon.setColorFilter(
                        viewController.colorSchemeTransition.accentPrimary.targetColor
                    )
                    viewHolder.appIcon.setImageIcon(viewModel.appIcon)
                }
                Trace.endAsyncSection(traceName, traceCookie)
            }
        }
    }

    private fun scaleTransitionDrawableLayer(
        transitionDrawable: TransitionDrawable,
        layer: Int,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val drawable = transitionDrawable.getDrawable(layer) ?: return
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        val scale =
            MediaDataUtils.getScaleFactor(Pair(width, height), Pair(targetWidth, targetHeight))
        if (scale == 0f) return
        transitionDrawable.setLayerSize(layer, (scale * width).toInt(), (scale * height).toInt())
    }

    private fun addGradientToPlayerAlbum(
        context: Context,
        artworkIcon: android.graphics.drawable.Icon,
        mutableColorScheme: ColorScheme,
        width: Int,
        height: Int
    ): LayerDrawable {
        val albumArt = MediaArtworkHelper.getScaledBackground(context, artworkIcon, width, height)
        return MediaArtworkHelper.setUpGradientColorOnDrawable(
            albumArt,
            context.getDrawable(R.drawable.qs_media_scrim)?.mutate() as GradientDrawable,
            mutableColorScheme,
            MEDIA_PLAYER_SCRIM_START_ALPHA,
            MEDIA_PLAYER_SCRIM_END_ALPHA
        )
    }

    private fun clearButton(button: ImageButton) {
        button.setImageDrawable(null)
        button.contentDescription = null
        button.isEnabled = false
        button.background = null
    }

    private fun bindScrubbingTime(
        viewHolder: MediaViewHolder,
        viewModel: MediaPlayerViewModel,
        viewController: MediaViewController,
    ) {
        val expandedSet = viewController.expandedLayout
        val visible = viewModel.canShowTime && viewController.isScrubbing
        viewController.canShowScrubbingTime = viewModel.canShowTime
        setVisibleAndAlpha(expandedSet, viewHolder.scrubbingElapsedTimeView.id, visible)
        setVisibleAndAlpha(expandedSet, viewHolder.scrubbingTotalTimeView.id, visible)
        // Collapsed view is always GONE as set in XML, so doesn't need to be updated dynamically.
    }

    private fun createTouchRippleAnimation(
        button: ImageButton,
        colorSchemeTransition: ColorSchemeTransition,
        multiRippleView: MultiRippleView
    ): RippleAnimation {
        val maxSize = (multiRippleView.width * 2).toFloat()
        return RippleAnimation(
            RippleAnimationConfig(
                RippleShader.RippleShape.CIRCLE,
                duration = 1500L,
                centerX = button.x + button.width * 0.5f,
                centerY = button.y + button.height * 0.5f,
                maxSize,
                maxSize,
                button.context.resources.displayMetrics.density,
                colorSchemeTransition.accentPrimary.currentColor,
                opacity = 100,
                sparkleStrength = 0f,
                baseRingFadeParams = null,
                sparkleRingFadeParams = null,
                centerFillFadeParams = null,
                shouldDistort = false
            )
        )
    }

    private fun openGuts(
        viewHolder: MediaViewHolder,
        viewController: MediaViewController,
        viewModel: MediaPlayerViewModel,
    ) {
        viewHolder.marquee(true, MediaViewController.GUTS_ANIMATION_DURATION)
        viewController.openGuts()
        viewHolder.player.contentDescription = viewModel.contentDescription.invoke(true)
        viewModel.onLongClicked.invoke()
    }

    private fun closeGuts(
        viewHolder: MediaViewHolder,
        viewController: MediaViewController,
        viewModel: MediaPlayerViewModel,
    ) {
        viewHolder.marquee(false, MediaViewController.GUTS_ANIMATION_DURATION)
        viewController.closeGuts(false)
        viewHolder.player.contentDescription = viewModel.contentDescription.invoke(false)
    }

    fun setVisibleAndAlpha(set: ConstraintSet, resId: Int, visible: Boolean) {
        setVisibleAndAlpha(set, resId, visible, ConstraintSet.GONE)
    }

    private fun setVisibleAndAlpha(
        set: ConstraintSet,
        resId: Int,
        visible: Boolean,
        notVisibleValue: Int
    ) {
        set.setVisibility(resId, if (visible) ConstraintSet.VISIBLE else notVisibleValue)
        set.setAlpha(resId, if (visible) 1.0f else 0.0f)
    }

    fun updateSeekBarVisibility(constraintSet: ConstraintSet, isSeekBarEnabled: Boolean) {
        if (isSeekBarEnabled) {
            constraintSet.setVisibility(R.id.media_progress_bar, ConstraintSet.VISIBLE)
            constraintSet.setAlpha(R.id.media_progress_bar, 1.0f)
        } else {
            constraintSet.setVisibility(R.id.media_progress_bar, ConstraintSet.INVISIBLE)
            constraintSet.setAlpha(R.id.media_progress_bar, 0.0f)
        }
    }

    fun setSemanticButtonVisibleAndAlpha(
        button: ImageButton,
        expandedSet: ConstraintSet,
        collapsedSet: ConstraintSet,
        visible: Boolean,
        notVisibleValue: Int,
        showInCollapsed: Boolean
    ) {
        if (notVisibleValue == ConstraintSet.INVISIBLE) {
            // Since time views should appear instead of buttons.
            button.isFocusable = visible
            button.isClickable = visible
        }
        setVisibleAndAlpha(expandedSet, button.id, visible, notVisibleValue)
        setVisibleAndAlpha(collapsedSet, button.id, visible = visible && showInCollapsed)
    }

    private fun getGrayscaleFilter(): ColorMatrixColorFilter {
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        return ColorMatrixColorFilter(matrix)
    }
}
