/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.os.Build;
import android.os.Handler;

import com.android.window.flags.Flags;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility processor for InputEvents that allows events to be adjusted before and
 * after it is sent to the application.
 *
 * {@hide}
 */
public class InputEventCompatProcessor {

    protected Context mContext;
    protected int mTargetSdkVersion;
    private final LetterboxScrollProcessor mLetterboxScrollProcessor;

    /** List of events to be used to return the processed events */
    private final List<InputEvent> mProcessedEvents;

    public InputEventCompatProcessor(Context context) {
        this(context, null);
    }

    public InputEventCompatProcessor(Context context, Handler handler) {
        mContext = context;
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        if (Flags.scrollingFromLetterbox()) {
            mLetterboxScrollProcessor = new LetterboxScrollProcessor(mContext, handler);
        } else {
            mLetterboxScrollProcessor = null;
        }

        mProcessedEvents = new ArrayList<>();
    }


    /**
     * Processes the InputEvent for compatibility before it is sent to the app, allowing for the
     * generation of more than one event if necessary.
     *
     * @param inputEvent The InputEvent to process.
     * @return The list of adjusted events, or null if no adjustments are needed. The list is empty
     * if the event should be ignored. Do not keep a reference to the output as the list is reused.
     */
    public List<InputEvent> processInputEventForCompatibility(InputEvent inputEvent) {
        mProcessedEvents.clear();

        // Process the event for StylusButtonCompatibility.
        final InputEvent stylusCompatEvent = processStylusButtonCompatibility(inputEvent);

        // Process the event for LetterboxScrollCompatibility.
        List<MotionEvent> letterboxScrollCompatEvents = processLetterboxScrollCompatibility(
                stylusCompatEvent != null ? stylusCompatEvent : inputEvent);

        // If no adjustments are needed for LetterboxCompatibility.
        if (letterboxScrollCompatEvents == null) {
            // If stylus compatibility made adjustments, return that adjusted event.
            if (stylusCompatEvent != null) {
                mProcessedEvents.add(stylusCompatEvent);
                return mProcessedEvents;
            }
            // Otherwise, return null to indicate no adjustments.
            return null;
        }

        // Otherwise if LetterboxCompatibility made adjustments, return the list of adjusted events.
        mProcessedEvents.addAll(letterboxScrollCompatEvents);
        return mProcessedEvents;
    }

    /**
     * Processes the InputEvent for compatibility before it is finished by calling
     * InputEventReceiver#finishInputEvent().
     *
     * @param inputEvent The InputEvent to process.
     * @return The InputEvent to finish, or null if it should not be finished.
     */
    public InputEvent processInputEventBeforeFinish(InputEvent inputEvent) {
        if (mLetterboxScrollProcessor != null && inputEvent instanceof MotionEvent motionEvent) {
            // LetterboxScrollProcessor may have generated events while processing motion events.
            return mLetterboxScrollProcessor.processMotionEventBeforeFinish(motionEvent);
        }

        // No changes needed
        return inputEvent;
    }


    private List<MotionEvent> processLetterboxScrollCompatibility(InputEvent inputEvent) {
        if (mLetterboxScrollProcessor != null
                && inputEvent instanceof MotionEvent motionEvent
                && motionEvent.getAction() != MotionEvent.ACTION_OUTSIDE) {
            return mLetterboxScrollProcessor.processMotionEvent(motionEvent);
        }
        return null;
    }


    private InputEvent processStylusButtonCompatibility(InputEvent inputEvent) {
        if (mTargetSdkVersion < Build.VERSION_CODES.M && inputEvent instanceof MotionEvent) {
            MotionEvent motion = (MotionEvent) inputEvent;
            final int mask =
                    MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;
            final int buttonState = motion.getButtonState();
            final int compatButtonState = (buttonState & mask) >> 4;
            if (compatButtonState != 0) {
                motion.setButtonState(buttonState | compatButtonState);
            }
            return motion;
        }
        return null;
    }
}
