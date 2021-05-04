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

package com.android.systemui.plugins;

import android.net.Uri;
import android.view.MotionEvent;

import com.android.systemui.plugins.annotations.ProvidesInterface;

import java.io.PrintWriter;

/**
 * Interface that decides whether a touch on the phone was accidental. i.e. Pocket Dialing.
 *
 * {@see com.android.systemui.classifier.FalsingManagerImpl}
 */
@ProvidesInterface(version = FalsingManager.VERSION)
public interface FalsingManager {
    int VERSION = 5;

    void onSuccessfulUnlock();

    void onNotificationActive();

    void setShowingAod(boolean showingAod);

    void onNotificatonStartDraggingDown();

    boolean isUnlockingDisabled();

    /** Returns true if the gesture should be rejected. */
    boolean isFalseTouch(int interactionType);

    void onNotificatonStopDraggingDown();

    void setNotificationExpanded();

    boolean isClassifierEnabled();

    void onQsDown();

    void setQsExpanded(boolean expanded);

    boolean shouldEnforceBouncer();

    void onTrackingStarted(boolean secure);

    void onTrackingStopped();

    void onLeftAffordanceOn();

    void onCameraOn();

    void onAffordanceSwipingStarted(boolean rightCorner);

    void onAffordanceSwipingAborted();

    void onStartExpandingFromPulse();

    void onExpansionFromPulseStopped();

    Uri reportRejectedTouch();

    void onScreenOnFromTouch();

    boolean isReportingEnabled();

    void onUnlockHintStarted();

    void onCameraHintStarted();

    void onLeftAffordanceHintStarted();

    void onScreenTurningOn();

    void onScreenOff();

    void onNotificationStopDismissing();

    void onNotificationDismissed();

    void onNotificationStartDismissing();

    void onNotificationDoubleTap(boolean accepted, float dx, float dy);

    void onBouncerShown();

    void onBouncerHidden();

    void onTouchEvent(MotionEvent ev, int width, int height);

    void dump(PrintWriter pw);

    void cleanup();
}
