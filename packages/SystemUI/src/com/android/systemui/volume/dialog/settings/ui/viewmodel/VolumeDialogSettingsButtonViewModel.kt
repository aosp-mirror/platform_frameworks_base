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

package com.android.systemui.volume.dialog.settings.ui.viewmodel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import androidx.annotation.ColorInt
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.android.internal.R as internalR
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.lottie.await
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.settings.domain.VolumeDialogSettingsButtonInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.shared.model.filterData
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.suspendCancellableCoroutine

class VolumeDialogSettingsButtonViewModel
@Inject
constructor(
    @Application private val context: Context,
    @UiBackground private val uiBgCoroutineContext: CoroutineContext,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    mediaOutputInteractor: MediaOutputInteractor,
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
    private val interactor: VolumeDialogSettingsButtonInteractor,
) {

    @SuppressLint("UseCompatLoadingForDrawables")
    private val drawables: Flow<Drawables> =
        flow {
                val color = context.getColor(internalR.color.materialColorPrimary)
                emit(
                    Drawables(
                        start =
                            LottieCompositionFactory.fromRawRes(context, R.raw.audio_bars_in)
                                .await()
                                .toDrawable { setColor(color) },
                        playing =
                            LottieCompositionFactory.fromRawRes(context, R.raw.audio_bars_playing)
                                .await()
                                .toDrawable {
                                    repeatCount = LottieDrawable.INFINITE
                                    repeatMode = LottieDrawable.RESTART
                                    setColor(color)
                                },
                        stop =
                            LottieCompositionFactory.fromRawRes(context, R.raw.audio_bars_out)
                                .await()
                                .toDrawable { setColor(color) },
                        idle = context.getDrawable(R.drawable.audio_bars_idle)!!,
                    )
                )
            }
            .buffer()
            .flowOn(uiBgCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    val isVisible = interactor.isVisible
    val icon: Flow<Drawable> =
        mediaOutputInteractor.defaultActiveMediaSession
            .filterData()
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null)
                } else {
                    mediaDeviceSessionInteractor.playbackState(session)
                }
            }
            .runningFold(null) { playbackStates: PlaybackStates?, playbackState: PlaybackState? ->
                val isCurrentActive = playbackState?.isActive ?: false
                if (playbackStates != null && isCurrentActive == playbackState?.isActive) {
                    return@runningFold playbackStates
                }
                playbackStates?.copy(
                    isPreviousActive = playbackStates.isCurrentActive,
                    isCurrentActive = isCurrentActive,
                ) ?: PlaybackStates(isPreviousActive = null, isCurrentActive = isCurrentActive)
            }
            .filterNotNull()
            // only apply the most recent state if we wait for the animation.
            .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            // distinct again because the changed state might've been dropped by the buffer
            .distinctUntilChangedBy { it.isCurrentActive }
            .transform { emitDrawables(it) }
            .runningFold(null) { previous: Drawable?, current: Drawable ->
                // wait for the previous animation to finish before starting the new one
                // this also waits for the current loop of the playing animation to finish
                (previous as? LottieDrawable)?.awaitFinish()
                (current as? LottieDrawable)?.start()
                current
            }
            .filterNotNull()

    private suspend fun FlowCollector<Drawable>.emitDrawables(playbackStates: PlaybackStates) {
        val animations = drawables.first()
        val stateChanged =
            playbackStates.isPreviousActive != null &&
                playbackStates.isPreviousActive != playbackStates.isCurrentActive
        if (playbackStates.isCurrentActive) {
            if (stateChanged) {
                emit(animations.start)
            }
            emit(animations.playing)
        } else {
            if (stateChanged) {
                emit(animations.stop)
            }
            emit(animations.idle)
        }
    }

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }

    private data class PlaybackStates(val isPreviousActive: Boolean?, val isCurrentActive: Boolean)

    private data class Drawables(
        val start: LottieDrawable,
        val playing: LottieDrawable,
        val stop: LottieDrawable,
        val idle: Drawable,
    )
}

private fun LottieComposition.toDrawable(setup: LottieDrawable.() -> Unit = {}): LottieDrawable =
    LottieDrawable().also { drawable ->
        drawable.composition = this
        drawable.setup()
    }

/** Suspends until current loop of the repeating animation is finished */
private suspend fun LottieDrawable.awaitFinish() = suspendCancellableCoroutine { continuation ->
    if (!isRunning) {
        continuation.resume(Unit)
        return@suspendCancellableCoroutine
    }
    val listener =
        object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                continuation.resume(Unit)
                removeAnimatorListener(this)
            }

            override fun onAnimationEnd(animation: Animator) {
                continuation.resume(Unit)
                removeAnimatorListener(this)
            }

            override fun onAnimationCancel(animation: Animator) {
                continuation.resume(Unit)
                removeAnimatorListener(this)
            }
        }
    addAnimatorListener(listener)
    continuation.invokeOnCancellation { removeAnimatorListener(listener) }
}

/**
 * Overrides colors of the [LottieDrawable] to a specified [color]
 *
 * @see com.airbnb.lottie.LottieAnimationView
 */
private fun LottieDrawable.setColor(@ColorInt color: Int) {
    val callback = LottieValueCallback<ColorFilter>(SimpleColorFilter(color))
    addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER, callback)
}
