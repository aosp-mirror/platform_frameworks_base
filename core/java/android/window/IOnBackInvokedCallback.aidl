/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License

 */

package android.window;

import android.window.BackMotionEvent;

/**
 * Interface that wraps a {@link OnBackInvokedCallback} object, to be stored in window manager
 * and called from back handling process when back is invoked.
 *
 * @hide
 */
oneway interface IOnBackInvokedCallback {
   /**
    * Called when a back gesture has been started, or back button has been pressed down.
    * Wraps {@link OnBackInvokedCallback#onBackStarted(BackEvent)}.
    *
    * @param backMotionEvent The {@link BackMotionEvent} containing information about the touch
    *        or button press.
    */
    void onBackStarted(in BackMotionEvent backMotionEvent);

    /**
     * Called on back gesture progress.
     * Wraps {@link OnBackInvokedCallback#onBackProgressed(BackEvent)}.
     *
     * @param backMotionEvent The {@link BackMotionEvent} containing information about the latest
     *                        touch point and the progress that the back animation should seek to.
     */
    void onBackProgressed(in BackMotionEvent backMotionEvent);

    /**
     * Called when a back gesture or back button press has been cancelled.
     * Wraps {@link OnBackInvokedCallback#onBackCancelled()}.
     */
    void onBackCancelled();

    /**
     * Called when a back gesture has been completed and committed, or back button pressed
     * has been released and committed.
     * Wraps {@link OnBackInvokedCallback#onBackInvoked()}.
     */
    void onBackInvoked();

    /**
     * Sets whether the back gesture is past the trigger threshold.
     */
    void setTriggerBack(in boolean triggerBack);
}
