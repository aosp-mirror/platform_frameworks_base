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

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Defines a class that can be used to ingest system events for later processing.
 */
public interface FalsingCollector {
    /** */
    void onSuccessfulUnlock();

    /** */
    void setShowingAod(boolean showingAod);

    /** */
    boolean shouldEnforceBouncer();

    /** */
    void onScreenOnFromTouch();

    /** */
    boolean isReportingEnabled();

    /** */
    void onScreenTurningOn();

    /** */
    void onScreenOff();

    /** */
    void onBouncerShown();

    /** */
    void onBouncerHidden();

    /**
     * Call this to record a KeyEvent in the {@link com.android.systemui.plugins.FalsingManager}.
     *
     * This may decide to only collect certain KeyEvents and ignore others. Do not assume all
     * KeyEvents are collected.
     */
    void onKeyEvent(KeyEvent ev);

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

    /** Indicates an a11y action was made. */
    void onA11yAction();

    /** Initialize the class. */
    void init();
}

