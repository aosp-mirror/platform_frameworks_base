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

package com.android.systemui.unfold

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.BinderThread
import android.content.Context
import android.os.Handler
import android.os.SystemProperties
import android.util.Log
import android.view.animation.DecelerateInterpolator
import com.android.internal.foldables.FoldLockSettingAvailabilityProvider
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.statusbar.LinearSideLightRevealEffect
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_OPAQUE
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.isVerticalRotation
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.util.animation.data.repository.AnimationStatusRepository
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoldLightRevealOverlayAnimation
@Inject
constructor(
    private val context: Context,
    @UnfoldBg private val bgHandler: Handler,
    private val deviceStateRepository: DeviceStateRepository,
    private val powerInteractor: PowerInteractor,
    @Background private val applicationScope: CoroutineScope,
    private val animationStatusRepository: AnimationStatusRepository,
    private val controllerFactory: FullscreenLightRevealAnimationController.Factory
) : FullscreenLightRevealAnimation {

    private val revealProgressValueAnimator: ValueAnimator =
        ValueAnimator.ofFloat(ALPHA_OPAQUE, ALPHA_TRANSPARENT)
    private val areAnimationEnabled: Flow<Boolean>
        get() = animationStatusRepository.areAnimationsEnabled()

    private lateinit var controller: FullscreenLightRevealAnimationController
    @Volatile private var readyCallback: CompletableDeferred<Runnable>? = null

    override fun init() {
        // This method will be called only on devices where this animation is enabled,
        // so normally this thread won't be created
        if (!FoldLockSettingAvailabilityProvider(context.resources).isFoldLockBehaviorAvailable) {
            return
        }

        controller =
            controllerFactory.create(
                displaySelector = { minByOrNull { it.naturalWidth } },
                effectFactory = { LinearSideLightRevealEffect(it.isVerticalRotation()) },
                overlayContainerName = SURFACE_CONTAINER_NAME
            )
        controller.init()

        applicationScope.launch(bgHandler.asCoroutineDispatcher()) {
            powerInteractor.screenPowerState.collect {
                if (it == ScreenPowerState.SCREEN_ON) {
                    readyCallback = null
                }
            }
        }

        applicationScope.launch(bgHandler.asCoroutineDispatcher()) {
            deviceStateRepository.state
                .map { it == DeviceStateRepository.DeviceState.FOLDED }
                .distinctUntilChanged()
                .flatMapLatest { isFolded ->
                    flow<Nothing> {
                            if (!areAnimationEnabled.first() || !isFolded) {
                                return@flow
                            }
                            withTimeout(WAIT_FOR_ANIMATION_TIMEOUT_MS) {
                                readyCallback = CompletableDeferred()
                                val onReady = readyCallback?.await()
                                readyCallback = null
                                controller.addOverlay(ALPHA_OPAQUE, onReady)
                                waitForScreenTurnedOn()
                            }
                            playFoldLightRevealOverlayAnimation()
                        }
                        .catchTimeoutAndLog()
                        .onCompletion {
                            controller.ensureOverlayRemoved()
                            val onReady = readyCallback?.takeIf { it.isCompleted }?.getCompleted()
                            onReady?.run()
                            readyCallback = null
                        }
                }
                .collect {}
        }
    }

    @BinderThread
    override fun onScreenTurningOn(onOverlayReady: Runnable) {
        readyCallback?.complete(onOverlayReady) ?: onOverlayReady.run()
    }

    private suspend fun waitForScreenTurnedOn() {
        powerInteractor.screenPowerState.filter { it == ScreenPowerState.SCREEN_ON }.first()
    }

    private suspend fun playFoldLightRevealOverlayAnimation() {
        revealProgressValueAnimator.duration = ANIMATION_DURATION
        revealProgressValueAnimator.interpolator = DecelerateInterpolator()
        revealProgressValueAnimator.addUpdateListener { animation ->
            controller.updateRevealAmount(animation.animatedFraction)
        }
        revealProgressValueAnimator.startAndAwaitCompletion()
    }

    private suspend fun ValueAnimator.startAndAwaitCompletion(): Unit =
        suspendCancellableCoroutine { continuation ->
            val listener =
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        continuation.resume(Unit)
                        removeListener(this)
                    }
                }
            addListener(listener)
            continuation.invokeOnCancellation { removeListener(listener) }
            start()
        }

    private fun <T> Flow<T>.catchTimeoutAndLog() = catch { exception ->
        when (exception) {
            is TimeoutCancellationException -> Log.e(TAG, "Fold light reveal animation timed out")
            else -> throw exception
        }
    }

    private companion object {
        const val TAG = "FoldLightRevealOverlayAnimation"
        const val WAIT_FOR_ANIMATION_TIMEOUT_MS = 2000L
        const val SURFACE_CONTAINER_NAME = "fold-overlay-container"
        val ANIMATION_DURATION: Long
            get() = SystemProperties.getLong("persist.fold_animation_duration", 200L)
    }
}
