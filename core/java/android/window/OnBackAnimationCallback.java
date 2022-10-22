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

package android.window;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Dialog;
import android.view.View;

/**
 * Interface for applications to register back animation callbacks along their custom back
 * handling.
 * <p>
 * This allows the client to customize various back behaviors by overriding the corresponding
 * callback methods.
 * <p>
 * Callback instances can be added to and removed from {@link OnBackInvokedDispatcher}, held
 * by classes that implement {@link OnBackInvokedDispatcherOwner} (such as {@link Activity},
 * {@link Dialog} and {@link View}).
 * <p>
 * When back is triggered, callbacks on the in-focus window are invoked in reverse order in which
 * they are added within the same priority. Between different priorities, callbacks with higher
 * priority are invoked first.
 * <p>
 * @see OnBackInvokedCallback
 * @hide
 */
public interface OnBackAnimationCallback extends OnBackInvokedCallback {
    /**
     * Called when a back gesture has been started, or back button has been pressed down.
     *
     * @param backEvent An {@link BackEvent} object describing the progress event.
     */
    default void onBackStarted(@NonNull BackEvent backEvent) {}

    /**
     * Called on back gesture progress.
     *
     * @param backEvent An {@link BackEvent} object describing the progress event.
     *
     * @see BackEvent
     */
    default void onBackProgressed(@NonNull BackEvent backEvent) { }

    /**
     * Called when a back gesture or back button press has been cancelled.
     */
    default void onBackCancelled() { }
}
