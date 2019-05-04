/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context;
import android.net.Uri;
import android.view.MotionEvent;

import java.io.PrintWriter;

/**
 * When the phone is locked, listens to touch, sensor and phone events and sends them to
 * DataCollector and HumanInteractionClassifier.
 *
 * It does not collect touch events when the bouncer shows up.
 */
public class FalsingManagerFactory {
    private static FalsingManager sInstance = null;

    private FalsingManagerFactory() {}

    public static FalsingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FalsingManagerImpl(context);
        }
        return sInstance;
    }

    public interface FalsingManager {
        void onSucccessfulUnlock();

        void onNotificationActive();

        void setShowingAod(boolean showingAod);

        void onNotificatonStartDraggingDown();

        boolean isUnlockingDisabled();

        boolean isFalseTouch();

        void onNotificatonStopDraggingDown();

        void setNotificationExpanded();

        boolean isClassiferEnabled();

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

        void onNotificatonStopDismissing();

        void onNotificationDismissed();

        void onNotificatonStartDismissing();

        void onNotificationDoubleTap(boolean accepted, float dx, float dy);

        void onBouncerShown();

        void onBouncerHidden();

        void onTouchEvent(MotionEvent ev, int width, int height);

        void dump(PrintWriter pw);
    }
}
