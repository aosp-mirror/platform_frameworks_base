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

import javax.inject.Inject;

/** */
public class FalsingCollectorFake implements FalsingCollector {

    public KeyEvent lastKeyEvent = null;
    public boolean avoidGestureInvoked = false;

    @Override
    public void init() {
    }

    @Inject
    public FalsingCollectorFake() {
    }

    @Override
    public void onSuccessfulUnlock() {
    }

    @Override
    public void setShowingAod(boolean showingAod) {
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onScreenOnFromTouch() {
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void onScreenTurningOn() {
    }

    @Override
    public void onScreenOff() {
    }

    @Override
    public void onBouncerShown() {
    }

    @Override
    public void onBouncerHidden() {
    }

    @Override
    public void onKeyEvent(KeyEvent ev) {
        lastKeyEvent = ev;
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
    }

    @Override
    public void onMotionEventComplete() {
    }

    @Override
    public void avoidGesture() {
        avoidGestureInvoked = true;
    }

    /**
     * @return whether {@link #avoidGesture()} was invoked.
     */
    public boolean wasLastGestureAvoided() {
        boolean wasLastGestureAvoided = avoidGestureInvoked;
        avoidGestureInvoked = false;
        return wasLastGestureAvoided;
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void updateFalseConfidence(FalsingClassifier.Result result) {
    }

    @Override
    public void onA11yAction() {
    }
}
