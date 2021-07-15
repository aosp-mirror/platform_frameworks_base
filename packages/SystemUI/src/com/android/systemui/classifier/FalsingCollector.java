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

/**
 * Defines a class that can be used to ingest system events for later processing.
 */
public interface FalsingCollector {
    /** */
    void onSuccessfulUnlock();

    /** */
    void onNotificationActive();

    /** */
    void setShowingAod(boolean showingAod);

    /** */
    void onNotificationStartDraggingDown();

    /** */
    void onNotificationStopDraggingDown();

    /** */
    void setNotificationExpanded();

    /** */
    void onQsDown();

    /** */
    void setQsExpanded(boolean expanded);

    /** */
    boolean shouldEnforceBouncer();

    /** */
    void onTrackingStarted(boolean secure);

    /** */
    void onTrackingStopped();

    /** */
    void onLeftAffordanceOn();

    /** */
    void onCameraOn();

    /** */
    void onAffordanceSwipingStarted(boolean rightCorner);

    /** */
    void onAffordanceSwipingAborted();

    /** */
    void onStartExpandingFromPulse();

    /** */
    void onExpansionFromPulseStopped();

    /** */
    void onScreenOnFromTouch();

    /** */
    boolean isReportingEnabled();

    /** */
    void onUnlockHintStarted();

    /** */
    void onCameraHintStarted();

    /** */
    void onLeftAffordanceHintStarted();

    /** */
    void onScreenTurningOn();

    /** */
    void onScreenOff();

    /** */
    void onNotificationStopDismissing();

    /** */
    void onNotificationDismissed();

    /** */
    void onNotificationStartDismissing();

    /** */
    void onNotificationDoubleTap(boolean accepted, float dx, float dy);

    /** */
    void onBouncerShown();

    /** */
    void onBouncerHidden();

    /**
     * Call this to record a MotionEvent in the {@link com.android.systemui.plugins.FalsingManager}.
     *
     * Be sure to call {@link #onMotionEventComplete()} after the rest of SystemUI is done with the
     * MotionEvent.
     */
    void onTouchEvent(MotionEvent ev);

    /**
     * Call this once SystemUI has completed all processing of a given MotionEvent.
     *
     * See {@link #onTouchEvent(MotionEvent)}.
     */
    void onMotionEventComplete();

    /** */
    void avoidGesture();

    /** */
    void cleanup();

    /** */
    void updateFalseConfidence(FalsingClassifier.Result result);
}

