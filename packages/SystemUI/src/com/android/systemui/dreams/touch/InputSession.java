/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.android.systemui.dreams.touch.dagger.DreamTouchModule.INPUT_SESSION_NAME;
import static com.android.systemui.dreams.touch.dagger.DreamTouchModule.PILFER_ON_GESTURE_CONSUME;

import android.os.Looper;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link InputSession} encapsulates components behind input monitoring and handles their lifecycle.
 * Sessions are meant to be disposable; actions such as exclusively capturing touch events is modal
 * and destroying the sessions allows a reset. Additionally, {@link InputSession} is meant to have
 * a single listener for input and gesture. Any broadcasting must be accomplished elsewhere.
 */
public class InputSession {
    private final InputMonitorCompat mInputMonitor;
    private final InputChannelCompat.InputEventReceiver mInputEventReceiver;
    private final GestureDetector mGestureDetector;

    /**
     * Default session constructor.
     * @param sessionName The session name that will be applied to the underlying
     * {@link InputMonitorCompat}.
     * @param inputEventListener A listener to receive input events.
     * @param gestureListener A listener to receive gesture events.
     * @param pilferOnGestureConsume Whether touch events should be pilfered after a gesture has
     *                               been consumed.
     */
    @Inject
    public InputSession(@Named(INPUT_SESSION_NAME) String sessionName,
            InputChannelCompat.InputEventListener inputEventListener,
            GestureDetector.OnGestureListener gestureListener,
            DisplayTracker displayTracker,
            @Named(PILFER_ON_GESTURE_CONSUME) boolean pilferOnGestureConsume) {
        mInputMonitor = new InputMonitorCompat(sessionName, displayTracker.getDefaultDisplayId());
        mGestureDetector = new GestureDetector(gestureListener);

        mInputEventReceiver = mInputMonitor.getInputReceiver(Looper.getMainLooper(),
                Choreographer.getInstance(),
                ev -> {
                    // Process event. Since sometimes input may be a prerequisite for some
                    // gesture logic, process input first.
                    inputEventListener.onInputEvent(ev);

                    if (ev instanceof MotionEvent
                            && mGestureDetector.onTouchEvent((MotionEvent) ev)
                            && pilferOnGestureConsume) {
                        mInputMonitor.pilferPointers();
                    }
                });
    }

    /**
     * Destroys the {@link InputSession}, removing any component from listening to future touch
     * events.
     */
    public void dispose() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
        }

        if (mInputMonitor != null) {
            mInputMonitor.dispose();
        }
    }
}
