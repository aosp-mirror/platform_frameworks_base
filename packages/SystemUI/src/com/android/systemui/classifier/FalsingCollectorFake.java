/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.classifier;

import android.view.MotionEvent;

/** */
public class FalsingCollectorFake implements FalsingCollector {
    @Override
    public void onSuccessfulUnlock() {
    }

    @Override
    public void onNotificationActive() {
    }

    @Override
    public void setShowingAod(boolean showingAod) {
    }

    @Override
    public void onNotificationStartDraggingDown() {
    }

    @Override
    public void onNotificationStopDraggingDown() {
    }

    @Override
    public void setNotificationExpanded() {
    }

    @Override
    public void onQsDown() {
    }

    @Override
    public void setQsExpanded(boolean expanded) {
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onTrackingStarted(boolean secure) {
    }

    @Override
    public void onTrackingStopped() {
    }

    @Override
    public void onLeftAffordanceOn() {
    }

    @Override
    public void onCameraOn() {
    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {
    }

    @Override
    public void onAffordanceSwipingAborted() {
    }

    @Override
    public void onStartExpandingFromPulse() {
    }

    @Override
    public void onExpansionFromPulseStopped() {
    }

    @Override
    public void onScreenOnFromTouch() {
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void onUnlockHintStarted() {
    }

    @Override
    public void onCameraHintStarted() {
    }

    @Override
    public void onLeftAffordanceHintStarted() {
    }

    @Override
    public void onScreenTurningOn() {
    }

    @Override
    public void onScreenOff() {
    }

    @Override
    public void onNotificationStopDismissing() {
    }

    @Override
    public void onNotificationDismissed() {
    }

    @Override
    public void onNotificationStartDismissing() {
    }

    @Override
    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {
    }

    @Override
    public void onBouncerShown() {
    }

    @Override
    public void onBouncerHidden() {
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
    }

    @Override
    public void onMotionEventComplete() {
    }

    @Override
    public void avoidGesture() {
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void updateFalseConfidence(FalsingClassifier.Result result) {
    }
}
