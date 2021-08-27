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

import android.app.ActivityManager;
import android.graphics.Rect;
import android.view.RemoteAnimationTarget;
import android.view.IRecentsAnimationController;
import android.window.TaskSnapshot;

/**
 * Interface that is used to callback from window manager to the process that runs a recents
 * animation to start or cancel it.
 *
 * {@hide}
 */
oneway interface IRecentsAnimationRunner {

    /**
     * Called when the system needs to cancel the current animation. This can be due to the
     * wallpaper not drawing in time, or the handler not finishing the animation within a predefined
     * amount of time.
     *
     * @param taskSnapshot If the snapshot is null, the animation will be cancelled and the leash
     *                     will be inactive immediately. Otherwise, the contents of the task will be
     *                     replaced with {@param taskSnapshot}, such that the runner's leash is
     *                     still active. As soon as the runner doesn't need the leash anymore, it
     *                     must call {@link IRecentsAnimationController#cleanupScreenshot).
     *
     * @see {@link RecentsAnimationController#cleanupScreenshot}
     */
    void onAnimationCanceled(in @nullable TaskSnapshot taskSnapshot) = 1;

    /**
     * Called when the system is ready for the handler to start animating all the visible tasks.
     *
     * @param homeContentInsets The current home app content insets
     * @param minimizedHomeBounds Specifies the bounds of the minimized home app, will be
     *                            {@code null} if the device is not currently in split screen
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void onAnimationStart(in IRecentsAnimationController controller,
            in RemoteAnimationTarget[] apps, in RemoteAnimationTarget[] wallpapers,
            in Rect homeContentInsets, in Rect minimizedHomeBounds) = 2;

    /**
     * Called when the task of an activity that has been started while the recents animation
     * was running becomes ready for control.
     */
    void onTaskAppeared(in RemoteAnimationTarget app) = 3;
}
