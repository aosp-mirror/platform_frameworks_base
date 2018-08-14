/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.ColorInt;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;

import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;

/**
 * Interface that describes an animation and bridges the animation start to the component
 * responsible for running the animation.
 */
interface AnimationAdapter {

    long STATUS_BAR_TRANSITION_DURATION = 120L;

    /**
     * @return Whether we should detach the wallpaper during the animation.
     * @see Animation#setDetachWallpaper
     */
    boolean getDetachWallpaper();

    /**
     * @return Whether we should show the wallpaper during the animation.
     * @see Animation#getShowWallpaper()
     */
    boolean getShowWallpaper();

    /**
     * @return The background color behind the animation.
     */
    @ColorInt int getBackgroundColor();

    /**
     * Requests to start the animation.
     *
     * @param animationLeash The surface to run the animation on. See {@link SurfaceAnimator} for an
     *                       overview of the mechanism. This surface needs to be released by the
     *                       component running the animation after {@code finishCallback} has been
     *                       invoked, or after the animation was cancelled.
     * @param t The Transaction to apply the initial frame of the animation.
     * @param finishCallback The callback to be invoked when the animation has finished.
     */
    void startAnimation(SurfaceControl animationLeash, Transaction t,
            OnAnimationFinishedCallback finishCallback);

    /**
     * Called when the animation that was started with {@link #startAnimation} was cancelled by the
     * window manager.
     *
     * @param animationLeash The leash passed to {@link #startAnimation}.
     */
    void onAnimationCancelled(SurfaceControl animationLeash);

    /**
     * @return The approximate duration of the animation, in milliseconds.
     */
    long getDurationHint();

    /**
     * If this animation is run as an app opening animation, this calculates the start time for all
     * status bar transitions that happen as part of the app opening animation, which will be
     * forwarded to SystemUI.
     *
     * @return the desired start time of the status bar transition, in uptime millis
     */
    long getStatusBarTransitionsStartTime();

    void dump(PrintWriter pw, String prefix);

    default void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        writeToProto(proto);
        proto.end(token);
    }

    void writeToProto(ProtoOutputStream proto);
}
