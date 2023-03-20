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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Encapsulates business logic to handle [MotionEvent]-based user input.
 *
 * This class is meant purely for the legacy `View`-based system to be able to pass `MotionEvent`s
 * into the newer multi-shade framework for processing.
 */
class MultiShadeMotionEventInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    private val interactor: MultiShadeInteractor,
) {

    private val isAnyShadeExpanded: StateFlow<Boolean> =
        interactor.isAnyShadeExpanded.stateIn(
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
                        isDraggingVertically = false,
                    )

                false
            }
            MotionEvent.ACTION_MOVE -> {
                interactionState?.let {
                    val pointerIndex = event.findPointerIndex(it.pointerId)
                    val currentX = event.getX(pointerIndex)
                    val currentY = event.getY(pointerIndex)
                    if (!it.isDraggingHorizontally && !it.isDraggingVertically) {
                        val xDistanceTravelled = abs(currentX - it.initialX)
                        val yDistanceTravelled = abs(currentY - it.initialY)
                        val touchSlop = ViewConfiguration.get(applicationContext).scaledTouchSlop
                        interactionState =
                            when {
                                yDistanceTravelled > touchSlop ->
                                    it.copy(isDraggingVertically = true)
                                xDistanceTravelled > touchSlop ->
                                    it.copy(isDraggingHorizontally = true)
                                else -> interactionState
                            }
                    }
                }

                // We want to intercept the rest of the gesture if we're dragging.
                interactionState.isDraggingVertically()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                // Make sure that we intercept the up or cancel if we're dragging, to handle drag
                // end and cancel.
                interactionState.isDraggingVertically()
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
                    if (it.isDraggingVertically) {
                        val pointerIndex = event.findPointerIndex(it.pointerId)
                        val previousY = it.currentY
                        val currentY = event.getY(pointerIndex)
                        interactionState =
                            it.copy(
                                currentY = currentY,
                            )

                        val yDragAmountPx = currentY - previousY
                        if (yDragAmountPx != 0f) {
                            interactor.sendProxiedInput(
                                ProxiedInputModel.OnDrag(
                                    xFraction = event.x / viewWidthPx,
                                    yDragAmountPx = yDragAmountPx,
                                )
                            )
                        }
                    }
                }

                true
            }
            MotionEvent.ACTION_UP -> {
                if (interactionState.isDraggingVertically()) {
                    // We finished dragging. Record that so the multi-shade framework can issue a
                    // fling, if the velocity reached in the drag was high enough, for example.
                    interactor.sendProxiedInput(ProxiedInputModel.OnDragEnd)
                }

                interactionState = null
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (interactionState.isDraggingVertically()) {
                    // Our drag gesture was canceled by the system. This happens primarily in one of
                    // two occasions: (a) the parent view has decided to intercept the gesture
                    // itself and/or route it to a different child view or (b) the pointer has
                    // traveled beyond the bounds of our view and/or the touch display. Either way,
                    // we pass the cancellation event to the multi-shade framework to record it.
                    // Doing that allows the multi-shade framework to know that the gesture ended to
                    // allow new gestures to be accepted.
                    interactor.sendProxiedInput(ProxiedInputModel.OnDragCancel)
                }

                interactionState = null
                true
            }
            else -> false
        }
    }

    private data class InteractionState(
        val initialX: Float,
        val initialY: Float,
        val currentY: Float,
        val pointerId: Int,
        val isDraggingHorizontally: Boolean,
        val isDraggingVertically: Boolean,
    )

    private fun InteractionState?.isDraggingVertically(): Boolean {
        return this?.isDraggingVertically == true
    }
}
