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
import com.android.systemui.util.sensors.ThresholdSensor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Interface that decides whether a touch on the phone was accidental. i.e. Pocket Dialing.
 *
 * {@see com.android.systemui.classifier.FalsingManagerImpl}
 */
@ProvidesInterface(version = FalsingManager.VERSION)
public interface FalsingManager {
    int VERSION = 6;

    void onSuccessfulUnlock();

    boolean isUnlockingDisabled();

    /** Returns true if the gesture should be rejected. */
    boolean isFalseTouch(int interactionType);

    /**
     * Returns true if the FalsingManager thinks the last gesure was not a valid tap.
     *
     * The first parameter, robustCheck, distinctly changes behavior. When set to false,
     * this method simply looks at the last gesture and returns whether it is a tap or not, (as
     * opposed to a swipe or other non-tap gesture). When set to true, a more thorough analysis
     * is performed that can include historical interactions and other contextual cues to see
     * if the tap looks accidental.
     *
     * Set robustCheck to true if you want to validate a tap for launching an action, like opening
     * a notification. Set to false if you simply want to know if the last gesture looked like a
     * tap.
     *
     * The second parameter, falsePenalty, indicates how much this should affect future gesture
     * classifications if this tap looks like a false.
     */
    boolean isFalseTap(boolean robustCheck, double falsePenalty);

    /**
     * Returns true if the last two gestures do not look like a double tap.
     *
     * Only works on data that has already been reported to the FalsingManager. Be sure that
     * {@link com.android.systemui.classifier.FalsingCollector#onTouchEvent(MotionEvent)}
     * has already been called for all of the taps you want considered.
     *
     * This looks at the last two gestures on the screen, ensuring that they meet the following
     * criteria:
     *
     *   a) There are at least two gestures.
     *   b) The last two gestures look like taps.
     *   c) The last two gestures look like a double tap taken together.
     *
     *   This method is _not_ context aware. That is to say, if two taps occur on two neighboring
     *   views, but are otherwise close to one another, this will report a successful double tap.
     *   It is up to the caller to decide
     * @return
     */
    boolean isFalseDoubleTap();

    boolean isClassifierEnabled();

    boolean shouldEnforceBouncer();

    Uri reportRejectedTouch();

    boolean isReportingEnabled();

    /** From com.android.systemui.Dumpable. */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    void cleanup();

    /** Call to report a ProximityEvent to the FalsingManager. */
    void onProximityEvent(ThresholdSensor.ThresholdSensorEvent proximityEvent);
}
