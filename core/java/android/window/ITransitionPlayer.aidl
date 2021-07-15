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
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

/**
 * Implemented by WMShell to initiate and play transition animations.
 * The flow (with {@link IWindowOrganizerController}) looks like this:
 * <p><ol>
 *  <li>Core starts an activity and calls {@link #requestStartTransition}
 *  <li>This TransitionPlayer impl does whatever, then calls
 *      {@link IWindowOrganizerController#startTransition} to tell Core to formally start (until
 *      this happens, Core will collect changes on the transition, but won't consider it ready to
 *      animate).
 *  <li>Once all collected changes on the transition have finished drawing, Core will then call
 *      {@link #onTransitionReady} here to delegate the actual animation.
 *  <li>Once this TransitionPlayer impl finishes animating, it notifies Core via
 *      {@link IWindowOrganizerController#finishTransition}. At this point, ITransitionPlayer's
 *      responsibilities end.
 * </ul>
 *
 * {@hide}
 */
oneway interface ITransitionPlayer {

    /**
     * Called when all participants of a transition are ready to animate. This is in response to
     * {@link IWindowOrganizerController#startTransition}.
     *
     * @param transitionToken An identifying token for the transition that is now ready to animate.
     * @param info A collection of all the changes encapsulated by this transition.
     * @param t A surface transaction containing the surface state prior to animating.
     * @param finishT A surface transaction that will reset parenting/layering and generally put
     *                surfaces into their final (post-transition) state. Apply this after playing
     *                the animation but before calling finish.
     */
    void onTransitionReady(in IBinder transitionToken, in TransitionInfo info,
            in SurfaceControl.Transaction t, in SurfaceControl.Transaction finishT);

    /**
     * Called when something in WMCore requires a transition to play -- for example when an Activity
     * is started in a new Task.
     *
     * @param transitionToken An identifying token for the transition that needs to be started.
     *                        Pass this to {@link IWindowOrganizerController#startTransition}.
     * @param request Information about this particular request.
     */
    void requestStartTransition(in IBinder transitionToken, in TransitionRequestInfo request);
}
