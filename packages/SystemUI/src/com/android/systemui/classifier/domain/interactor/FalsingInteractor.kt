/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.classifier.domain.interactor

import android.view.MotionEvent
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.FalsingClassifier
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorActual
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.Penalty
import javax.inject.Inject

/**
 * Exposes the subset of the [FalsingCollector] and [FalsingManager] APIs that's required by
 * external callers.
 *
 * E.g. methods of the above APIs that are not exposed by this class either don't need to be invoked
 * by external callers (as they're already called by the scene framework) or haven't been added yet.
 */
@SysUISingleton
class FalsingInteractor
@Inject
constructor(
    @FalsingCollectorActual private val collector: FalsingCollector,
    private val manager: FalsingManager,
) {
    /**
     * Notifies of a [MotionEvent] that passed through the UI.
     *
     * Must call [onMotionEventComplete] when done with this event.
     */
    fun onTouchEvent(event: MotionEvent) = collector.onTouchEvent(event)

    /**
     * Notifies that a [MotionEvent] has finished being dispatched through the UI.
     *
     * Must be called after each call to [onTouchEvent].
     */
    fun onMotionEventComplete() = collector.onMotionEventComplete()

    /**
     * Instructs the falsing system to ignore the rest of the current input gesture; automatically
     * resets when another gesture is started (with the next down event).
     */
    fun avoidGesture() = collector.avoidGesture()

    /**
     * Inserts the given [result] into the falsing system, affecting future runs of the classifier
     * as if this was a result that had organically happened before.
     */
    fun updateFalseConfidence(result: FalsingClassifier.Result) =
        collector.updateFalseConfidence(result)

    /** Returns `true` if the gesture should be rejected. */
    fun isFalseTouch(@Classifier.InteractionType interactionType: Int): Boolean =
        manager.isFalseTouch(interactionType)

    /** Returns `true` if the tap gesture should be rejected */
    fun isFalseTap(@Penalty penalty: Int): Boolean = manager.isFalseTap(penalty)
}

inline fun FalsingInteractor.runIfNotFalseTap(
    penalty: Int = FalsingManager.LOW_PENALTY,
    action: () -> Unit,
) {
    if (!isFalseTap(penalty)) {
        action()
    }
}
