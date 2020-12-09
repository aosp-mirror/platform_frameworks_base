/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.view.SurfaceControl;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;

/**
 * Interface allowing remote processes to play transition animations.
 * The usage flow is as follows:
 * <p><ol>
 *  <li>The remote tags a lifecycle event with an IRemoteTransition (via a parameter in
 *      ActivityOptions#makeRemoteAnimation) or a transition matches a filter registered via
 *      Transitions#registerRemote.
 *  <li>Shell then associates the transition for the event with the IRemoteTransition
 *  <li>Shell receives onTransitionReady and delegates the animation to the IRemoteTransition
 *      via {@link #startAnimation}.
 *  <li>Once the IRemoteTransition is done animating, it will call the finishCallback.
 *  <li>Shell/Core finish-up the transition.
 * </ul>
 *
 * {@hide}
 */
oneway interface IRemoteTransition {
    /**
     * Starts a transition animation. Once complete, the implementation should call
     * `finishCallback`.
     */
    void startAnimation(in TransitionInfo info, in SurfaceControl.Transaction t,
            in IRemoteTransitionFinishedCallback finishCallback);
}
