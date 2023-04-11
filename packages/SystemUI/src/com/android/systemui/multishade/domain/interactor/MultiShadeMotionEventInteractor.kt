/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.multishade.domain.interactor

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.android.systemui.classifier.Classifier
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.multishade.shared.math.isZero
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.shade.ShadeController
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Encapsulates business logic to handle [MotionEvent]-based user input.
 *
 * This class is meant purely for the legacy `View`-based system to be able to pass `MotionEvent`s
 * into the newer multi-shade framework for processing.
 */
@SysUISingleton
class MultiShadeMotionEventInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    private val multiShadeInteractor: MultiShadeInteractor,
    featureFlags: FeatureFlags,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val falsingManager: FalsingManager,
    private val shadeController: ShadeController,
) {
    init {
        if (featureFlags.isEnabled(Flags.DUAL_SHADE)) {
            applicationScope.launch {
                multiShadeInteractor.isAnyShadeExpanded.collect {
                    if (!it && !shadeController.isKeyguard) {
                        shadeController.makeExpandedInvisible()
                    } else {
                        shadeController.makeExpandedVisible(false)
                    }
                }
            }
        }
    }

    private val isAnyShadeExpanded: StateFlow<Boolean> =
        multiShadeInteractor.isAnyShadeExpanded.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    private val isBouncerShowing: StateFlow<Boolean> =
        keyguardTransitionInteractor
            .transitionValue(state = KeyguardState.PRIMARY_BOUNCER)
            .map { !it.isZero() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private var interactionState: InteractionState? = null

    /**
     * Returns `true` if the given [MotionEvent] and the rest of events in this gesture should be
     * passed to this interactor's [onTouchEvent] method.
     *
     * Note: the caller should continue to pass [MotionEvent] instances into this method, even if it
     * returns `false` as the gesture may be intercepted mid-stream.
     */
    fun shouldIntercept(event: MotionEvent): Boolean {
        if (isAnyShadeExpanded.value) {
            // If any shade is expanded, we assume that touch handling outside the shades is handled
            // by the scrim that appears behind the shades. No need to intercept anything here.
            return false
        }

        if (isBouncerShowing.value) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record where the pointer was placed and which pointer it was.
                interactionState =
                    InteractionState(
                        initialX = event.x,
                        initialY = event.y,
                        currentY = event.y,
                        pointerId = event.getPointerId(0),
                        isDraggingHorizontally = false,
                        isDraggingShade = false,
                    )

                false
            }
            MotionEvent.ACTION_MOVE -> {
                onMove(event)

                // We want to intercept the rest of the gesture if we're dragging the shade.
                isDraggingShade()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                // Make sure that we intercept the up or cancel if we're dragging the shade, to
                // handle drag end or cancel.
                isDraggingShade()
            else -> false
        }
    }

    /**
     * Notifies that a [MotionEvent] in a series of events of a gesture that was intercepted due to
     * the result of [shouldIntercept] has been received.
     *
     * @param event The [MotionEvent] to handle.
     * @param viewWidthPx The width of the view, in pixels.
     * @return `true` if the event was consumed, `false` otherwise.
     */
    fun onTouchEvent(event: MotionEvent, viewWidthPx: Int): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                interactionState?.let {
                    if (it.isDraggingShade) {
                        val pointerIndex = event.findPointerIndex(it.pointerId)
                        val previousY = it.currentY
                        val currentY = event.getY(pointerIndex)
                        interactionState = it.copy(currentY = currentY)

                        val yDragAmountPx = currentY - previousY

                        if (yDragAmountPx != 0f) {
                            multiShadeInteractor.sendProxiedInput(
                                ProxiedInputModel.OnDrag(
                                    xFraction = event.x / viewWidthPx,
                                    yDragAmountPx = yDragAmountPx,
                                )
                            )
                        }
                        true
                    } else {
                        onMove(event)
                        isDraggingShade()
                    }
                }
                    ?: false
            }
            MotionEvent.ACTION_UP -> {
                if (isDraggingShade()) {
                    // We finished dragging the shade. Record that so the multi-shade framework can
                    // issue a fling, if the velocity reached in the drag was high enough, for
                    // example.
                    multiShadeInteractor.sendProxiedInput(ProxiedInputModel.OnDragEnd)

                    if (falsingManager.isFalseTouch(Classifier.SHADE_DRAG)) {
                        multiShadeInteractor.collapseAll()
                    }
                }

                interactionState = null
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val removedPointerId = event.getPointerId(event.actionIndex)
                if (removedPointerId == interactionState?.pointerId && event.pointerCount > 1) {
                    // We removed the original pointer but there must be another pointer because the
                    // gesture is still ongoing. Let's switch to that pointer.
                    interactionState =
                        event.firstUnremovedPointerId(removedPointerId)?.let { replacementPointerId
                            ->
                            interactionState?.copy(
                                pointerId = replacementPointerId,
                                // We want to update the currentY of our state so that the
                                // transition to the next pointer doesn't report a big jump between
                                // the Y coordinate of the removed pointer and the Y coordinate of
                                // the replacement pointer.
                                currentY = event.getY(replacementPointerId),
                            )
                        }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDraggingShade()) {
                    // Our drag gesture was canceled by the system. This happens primarily in one of
                    // two occasions: (a) the parent view has decided to intercept the gesture
                    // itself and/or route it to a different child view or (b) the pointer has
                    // traveled beyond the bounds of our view and/or the touch display. Either way,
                    // we pass the cancellation event to the multi-shade framework to record it.
                    // Doing that allows the multi-shade framework to know that the gesture ended to
                    // allow new gestures to be accepted.
                    multiShadeInteractor.sendProxiedInput(ProxiedInputModel.OnDragCancel)

                    if (falsingManager.isFalseTouch(Classifier.SHADE_DRAG)) {
                        multiShadeInteractor.collapseAll()
                    }
                }

                interactionState = null
                true
            }
            else -> false
        }
    }

    /**
     * Handles [MotionEvent.ACTION_MOVE] and sets whether or not we are dragging shade in our
     * current interaction
     *
     * @param event The [MotionEvent] to handle.
     */
    private fun onMove(event: MotionEvent) {
        interactionState?.let {
            val pointerIndex = event.findPointerIndex(it.pointerId)
            val currentX = event.getX(pointerIndex)
            val currentY = event.getY(pointerIndex)
            if (!it.isDraggingHorizontally && !it.isDraggingShade) {
                val xDistanceTravelled = currentX - it.initialX
                val yDistanceTravelled = currentY - it.initialY
                val touchSlop = ViewConfiguration.get(applicationContext).scaledTouchSlop
                interactionState =
                    when {
                        yDistanceTravelled > touchSlop -> it.copy(isDraggingShade = true)
                        abs(xDistanceTravelled) > touchSlop ->
                            it.copy(isDraggingHorizontally = true)
                        else -> interactionState
                    }
            }
        }
    }

    private data class InteractionState(
        val initialX: Float,
        val initialY: Float,
        val currentY: Float,
        val pointerId: Int,
        /** Whether the current gesture is dragging horizontally. */
        val isDraggingHorizontally: Boolean,
        /** Whether the current gesture is dragging the shade vertically. */
        val isDraggingShade: Boolean,
    )

    private fun isDraggingShade(): Boolean {
        return interactionState?.isDraggingShade ?: false
    }

    /**
     * Returns the index of the first pointer that is not [removedPointerId] or `null`, if there is
     * no other pointer.
     */
    private fun MotionEvent.firstUnremovedPointerId(removedPointerId: Int): Int? {
        return (0 until pointerCount)
            .firstOrNull { pointerIndex ->
                val pointerId = getPointerId(pointerIndex)
                pointerId != removedPointerId
            }
            ?.let { pointerIndex -> getPointerId(pointerIndex) }
    }
}
