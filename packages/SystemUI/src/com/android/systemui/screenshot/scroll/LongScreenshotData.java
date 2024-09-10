/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.scroll;

import android.graphics.Rect;

import com.android.systemui.dagger.SysUISingleton;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

/**
 * LongScreenshotData holds on to 1 LongScreenshot reference and 1 TransitionDestination
 * reference, to facilitate indirect in-process passing.
 */
@SysUISingleton
public class LongScreenshotData {
    private final AtomicReference<ScrollCaptureController.LongScreenshot> mLongScreenshot;
    private final AtomicReference<TransitionDestination>
            mTransitionDestinationCallback;

    public interface TransitionDestination {
        /**
         * Allows the long screenshot activity to call back with a destination location (the bounds
         * on screen of the destination for the transitioning view) and a Runnable to be run once
         * the transition animation is complete.
         */
        void setTransitionDestination(Rect transitionDestination, Runnable onTransitionEnd);
    }

    @Inject
    public LongScreenshotData() {
        mLongScreenshot = new AtomicReference<>();
        mTransitionDestinationCallback = new AtomicReference<>();
    }

    /**
     * Set the holder's stored LongScreenshot.
     */
    public void setLongScreenshot(ScrollCaptureController.LongScreenshot longScreenshot) {
        mLongScreenshot.set(longScreenshot);
    }

    /**
     * @return true if the holder has a non-null LongScreenshot.
     */
    public boolean hasLongScreenshot() {
        return mLongScreenshot.get() != null;
    }

    /**
     * Return the current stored LongScreenshot, clear the holder's storage.
     */
    public ScrollCaptureController.LongScreenshot takeLongScreenshot() {
        return mLongScreenshot.getAndSet(null);
    }

    /**
     * Set the holder's TransitionDestination callback.
     */
    public void setTransitionDestinationCallback(TransitionDestination destination) {
        mTransitionDestinationCallback.set(destination);
    }

    /**
     * Return the current TransitionDestination callback and clear.
     */
    public TransitionDestination takeTransitionDestinationCallback() {
        return mTransitionDestinationCallback.getAndSet(null);
    }
}
