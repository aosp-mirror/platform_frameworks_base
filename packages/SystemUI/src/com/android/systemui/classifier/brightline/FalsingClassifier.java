/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import android.view.MotionEvent;

import com.android.systemui.classifier.Classifier;
import com.android.systemui.util.ProximitySensor;

import java.util.List;

/**
 * Base class for rules that determine False touches.
 */
abstract class FalsingClassifier {
    private final FalsingDataProvider mDataProvider;

    FalsingClassifier(FalsingDataProvider dataProvider) {
        this.mDataProvider = dataProvider;
    }

    List<MotionEvent> getRecentMotionEvents() {
        return mDataProvider.getRecentMotionEvents();
    }

    MotionEvent getFirstMotionEvent() {
        return mDataProvider.getFirstRecentMotionEvent();
    }

    MotionEvent getLastMotionEvent() {
        return mDataProvider.getLastMotionEvent();
    }

    boolean isHorizontal() {
        return mDataProvider.isHorizontal();
    }

    boolean isRight() {
        return mDataProvider.isRight();
    }

    boolean isVertical() {
        return mDataProvider.isVertical();
    }

    boolean isUp() {
        return mDataProvider.isUp();
    }

    float getAngle() {
        return mDataProvider.getAngle();
    }

    int getWidthPixels() {
        return mDataProvider.getWidthPixels();
    }

    int getHeightPixels() {
        return mDataProvider.getHeightPixels();
    }

    float getXdpi() {
        return mDataProvider.getXdpi();
    }

    float getYdpi() {
        return mDataProvider.getYdpi();
    }

    final @Classifier.InteractionType int getInteractionType() {
        return mDataProvider.getInteractionType();
    }

    final void setInteractionType(@Classifier.InteractionType int interactionType) {
        mDataProvider.setInteractionType(interactionType);
    }

    /**
     * Called whenever a MotionEvent occurs.
     *
     * Useful for classifiers that need to see every MotionEvent, but most can probably
     * use {@link #getRecentMotionEvents()} instead, which will return a list of MotionEvents.
     */
    void onTouchEvent(MotionEvent motionEvent) {};

    /**
     * Called when a ProximityEvent occurs (change in near/far).
     */
    void onProximityEvent(ProximitySensor.ProximityEvent proximityEvent) {};

    /**
     * The phone screen has turned on and we need to begin falsing detection.
     */
    void onSessionStarted() {};

    /**
     * The phone screen has turned off and falsing data can be discarded.
     */
    void onSessionEnded() {};

    /**
     * Returns true if the data captured so far looks like a false touch.
     */
    abstract boolean isFalseTouch();

    static void logDebug(String msg) {
        BrightLineFalsingManager.logDebug(msg);
    }

    static void logInfo(String msg) {
        BrightLineFalsingManager.logInfo(msg);
    }

    static void logError(String msg) {
        BrightLineFalsingManager.logError(msg);
    }
}
