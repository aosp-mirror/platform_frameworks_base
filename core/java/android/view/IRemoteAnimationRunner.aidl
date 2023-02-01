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

import android.view.RemoteAnimationTarget;
import android.view.IRemoteAnimationFinishedCallback;

/**
 * Interface that is used to callback from window manager to the process that runs a remote
 * animation to start or cancel it.
 *
 * {@hide}
 */
oneway interface IRemoteAnimationRunner {

    /**
     * Called when the process needs to start the remote animation.
     *
     * @param transition The old transition type. Must be one of WindowManager.TRANSIT_OLD_* values.
     * @param apps The list of apps to animate.
     * @param wallpapers The list of wallpapers to animate.
     * @param nonApps The list of non-app windows such as Bubbles to animate.
     * @param finishedCallback The callback to invoke when the animation is finished.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void onAnimationStart(int transit, in RemoteAnimationTarget[] apps,
            in RemoteAnimationTarget[] wallpapers, in RemoteAnimationTarget[] nonApps,
            in IRemoteAnimationFinishedCallback finishedCallback);

    /**
     * Called when the animation was cancelled. From this point on, any updates onto the leashes
     * won't have any effect anymore.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void onAnimationCancelled(boolean isKeyguardOccluded);
}
