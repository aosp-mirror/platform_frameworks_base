/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.transition;

import android.view.ViewGroup;

/**
 * Extend <code>TransitionPropagation</code> to customize start delays for Animators created
 * in {@link android.transition.Transition#createAnimator(ViewGroup,
 * TransitionValues, TransitionValues)}. A Transition such as {@link android.transition.Explode}
 * defaults to using {@link android.transition.CircularPropagation} and Views closer to the
 * epicenter will move out of the scene later and into the scene sooner than Views farther
 * from the epicenter, giving the appearance of inertia. With no TransitionPropagation, all
 * Views will react simultaneously to the start of the transition.
 *
 * @see Transition#setPropagation(TransitionPropagation)
 * @see Transition#getEpicenter()
 */
public abstract class TransitionPropagation {
    /**
     * Called by Transition to alter the Animator start delay. All start delays will be adjusted
     * such that the minimum becomes zero.
     * @param sceneRoot The root of the View hierarchy running the transition.
     * @param transition The transition that created the Animator
     * @param startValues The values for a specific target in the start scene.
     * @param endValues The values for the target in the end scene.
     * @return A start delay to use with the Animator created by <code>transition</code>. The
     * delay will be offset by the minimum delay of all <code>TransitionPropagation</code>s
     * used in the Transition so that the smallest delay will be 0. Returned values may be
     * negative.
     */
    public abstract long getStartDelay(ViewGroup sceneRoot, Transition transition,
            TransitionValues startValues, TransitionValues endValues);

    /**
     * Captures the values in the start or end scene for the properties that this
     * transition propagation monitors. These values are then passed as the startValues
     * or endValues structure in a later call to
     * {@link #getStartDelay(ViewGroup, Transition, TransitionValues, TransitionValues)}.
     * The main concern for an implementation is what the
     * properties are that the transition cares about and what the values are
     * for all of those properties. The start and end values will be compared
     * later during the
     * {@link #getStartDelay(ViewGroup, Transition, TransitionValues, TransitionValues)}.
     * method to determine the start delay.
     *
     * <p>Subclasses must implement this method. The method should only be called by the
     * transition system; it is not intended to be called from external classes.</p>
     *
     * @param transitionValues The holder for any values that the Transition
     * wishes to store. Values are stored in the <code>values</code> field
     * of this TransitionValues object and are keyed from
     * a String value. For example, to store a view's rotation value,
     * a transition might call
     * <code>transitionValues.values.put("appname:transitionname:rotation",
     * view.getRotation())</code>. The target view will already be stored in
     * the transitionValues structure when this method is called.
     */
    public abstract void captureValues(TransitionValues transitionValues);

    /**
     * Returns the set of property names stored in the {@link TransitionValues}
     * object passed into {@link #captureValues(TransitionValues)} that
     * this transition propagation cares about for the purposes of preventing
     * duplicate capturing of property values.

     * <p>A <code>TransitionPropagation</code> must override this method to prevent
     * duplicate capturing of values and must contain at least one </p>
     *
     * @return An array of property names as described in the class documentation for
     * {@link TransitionValues}.
     */
    public abstract String[] getPropagationProperties() ;
}
