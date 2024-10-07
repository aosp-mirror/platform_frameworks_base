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

import android.view.RemoteAnimationTarget;
import android.window.IBackAnimationFinishedCallback;

/**
 * Interface that is used to callback from window manager to the process that runs a back gesture
 * animation to start or cancel it.
 *
 * {@hide}
 */
oneway interface IBackAnimationRunner {

    /**
     * Called when the system needs to cancel the current animation. This can be due to the
     * wallpaper not drawing in time, or the handler not finishing the animation within a predefined
     * amount of time.
     *
     */
    void onAnimationCancelled() = 1;

    /**
     * Called when the system is ready for the handler to start animating all the visible tasks.
     * @param apps The list of departing (type=MODE_CLOSING) and entering (type=MODE_OPENING)
     *             windows to animate,
     * @param prepareOpenTransition If non-null, the animation should start after receive open
     *             transition
     * @param finishedCallback The callback to invoke when the animation is finished.
     */
    void onAnimationStart(
            in RemoteAnimationTarget[] apps,
            in IBinder prepareOpenTransition,
            in IBackAnimationFinishedCallback finishedCallback) = 2;
}