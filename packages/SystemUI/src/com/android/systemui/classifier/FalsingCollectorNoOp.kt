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

package com.android.systemui.classifier

import android.view.MotionEvent
import com.android.systemui.classifier.FalsingCollectorImpl.logDebug
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class FalsingCollectorNoOp @Inject constructor() : FalsingCollector {
    override fun init() {
        logDebug("NOOP: init")
    }

    override fun onSuccessfulUnlock() {
        logDebug("NOOP: onSuccessfulUnlock")
    }

    override fun setShowingAod(showingAod: Boolean) {
        logDebug("NOOP: setShowingAod($showingAod)")
    }

    override fun shouldEnforceBouncer(): Boolean = false

    override fun onScreenOnFromTouch() {
        logDebug("NOOP: onScreenOnFromTouch")
    }

    override fun isReportingEnabled(): Boolean = false

    override fun onScreenTurningOn() {
        logDebug("NOOP: onScreenTurningOn")
    }

    override fun onScreenOff() {
        logDebug("NOOP: onScreenOff")
    }

    override fun onBouncerShown() {
        logDebug("NOOP: onBouncerShown")
    }

    override fun onBouncerHidden() {
        logDebug("NOOP: onBouncerHidden")
    }

    override fun onTouchEvent(ev: MotionEvent) {
        logDebug("NOOP: onTouchEvent(${ev.actionMasked})")
    }

    override fun onMotionEventComplete() {
        logDebug("NOOP: onMotionEventComplete")
    }

    override fun avoidGesture() {
        logDebug("NOOP: avoidGesture")
    }

    override fun cleanup() {
        logDebug("NOOP: cleanup")
    }

    override fun updateFalseConfidence(result: FalsingClassifier.Result) {
        logDebug("NOOP: updateFalseConfidence(${result.isFalse})")
    }

    override fun onA11yAction() {
        logDebug("NOOP: onA11yAction")
    }
}
