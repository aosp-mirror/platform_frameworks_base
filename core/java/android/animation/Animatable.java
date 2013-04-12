/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.animation;

/**
 * This interface is implemented by animation-related classes that expose
 * the ability to set and get duration, startDelay, and interpolators.
 */
public interface Animatable {

    /**
     * The amount of time, in milliseconds, to delay processing the animation
     * after the animation is started. The {@link #setDuration(long)} of the
     * animation will not begin to elapse until after the startDelay has elapsed.
     *
     * @return the number of milliseconds to delay running the animation
     */
    long getStartDelay();

    /**
     * The amount of time, in milliseconds, to delay processing the animation
     * after the animation is started. The {@link #setDuration(long)} of the
     * animation will not begin to elapse until after the startDelay has elapsed.

     * @param startDelay The amount of the delay, in milliseconds
     */
    void setStartDelay(long startDelay);

    /**
     * Sets the length of the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     */
    Animatable setDuration(long duration);

    /**
     * Gets the duration of the animation.
     *
     * @return The length of the animation, in milliseconds.
     */
    long getDuration();

    /**
     * The time interpolator used in calculating the elapsed fraction of the
     * animation. The interpolator determines whether the animation runs with
     * linear or non-linear motion, such as acceleration and deceleration.
     *
     * @param value the interpolator to be used by this animation
     */
    void setInterpolator(TimeInterpolator value);

    /**
     * Returns the timing interpolator that this animation uses.
     *
     * @return The timing interpolator for this animation.
     */
    public TimeInterpolator getInterpolator();
}
