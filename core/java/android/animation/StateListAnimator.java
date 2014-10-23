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

package android.animation;

import android.content.res.ConstantState;
import android.util.StateSet;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Lets you define a number of Animators that will run on the attached View depending on the View's
 * drawable state.
 * <p>
 * It can be defined in an XML file with the <code>&lt;selector></code> element.
 * Each State Animator is defined in a nested <code>&lt;item></code> element.
 *
 * @attr ref android.R.styleable#DrawableStates_state_focused
 * @attr ref android.R.styleable#DrawableStates_state_window_focused
 * @attr ref android.R.styleable#DrawableStates_state_enabled
 * @attr ref android.R.styleable#DrawableStates_state_checkable
 * @attr ref android.R.styleable#DrawableStates_state_checked
 * @attr ref android.R.styleable#DrawableStates_state_selected
 * @attr ref android.R.styleable#DrawableStates_state_activated
 * @attr ref android.R.styleable#DrawableStates_state_active
 * @attr ref android.R.styleable#DrawableStates_state_single
 * @attr ref android.R.styleable#DrawableStates_state_first
 * @attr ref android.R.styleable#DrawableStates_state_middle
 * @attr ref android.R.styleable#DrawableStates_state_last
 * @attr ref android.R.styleable#DrawableStates_state_pressed
 * @attr ref android.R.styleable#StateListAnimatorItem_animation
 */
public class StateListAnimator implements Cloneable {

    private ArrayList<Tuple> mTuples = new ArrayList<Tuple>();
    private Tuple mLastMatch = null;
    private Animator mRunningAnimator = null;
    private WeakReference<View> mViewRef;
    private StateListAnimatorConstantState mConstantState;
    private AnimatorListenerAdapter mAnimatorListener;
    private int mChangingConfigurations;

    public StateListAnimator() {
        initAnimatorListener();
    }

    private void initAnimatorListener() {
        mAnimatorListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animation.setTarget(null);
                if (mRunningAnimator == animation) {
                    mRunningAnimator = null;
                }
            }
        };
    }

    /**
     * Associates the given animator with the provided drawable state specs so that it will be run
     * when the View's drawable state matches the specs.
     *
     * @param specs The drawable state specs to match against
     * @param animator The animator to run when the specs match
     */
    public void addState(int[] specs, Animator animator) {
        Tuple tuple = new Tuple(specs, animator);
        tuple.mAnimator.addListener(mAnimatorListener);
        mTuples.add(tuple);
        mChangingConfigurations |= animator.getChangingConfigurations();
    }

    /**
     * Returns the current {@link android.animation.Animator} which is started because of a state
     * change.
     *
     * @return The currently running Animator or null if no Animator is running
     * @hide
     */
    public Animator getRunningAnimator() {
        return mRunningAnimator;
    }

    /**
     * @hide
     */
    public View getTarget() {
        return mViewRef == null ? null : mViewRef.get();
    }

    /**
     * Called by View
     * @hide
     */
    public void setTarget(View view) {
        final View current = getTarget();
        if (current == view) {
            return;
        }
        if (current != null) {
            clearTarget();
        }
        if (view != null) {
            mViewRef = new WeakReference<View>(view);
        }

    }

    private void clearTarget() {
        final int size = mTuples.size();
        for (int i = 0; i < size; i++) {
            mTuples.get(i).mAnimator.setTarget(null);
        }
        mViewRef = null;
        mLastMatch = null;
        mRunningAnimator = null;
    }

    @Override
    public StateListAnimator clone() {
        try {
            StateListAnimator clone = (StateListAnimator) super.clone();
            clone.mTuples = new ArrayList<Tuple>(mTuples.size());
            clone.mLastMatch = null;
            clone.mRunningAnimator = null;
            clone.mViewRef = null;
            clone.mAnimatorListener = null;
            clone.initAnimatorListener();
            final int tupleSize = mTuples.size();
            for (int i = 0; i < tupleSize; i++) {
                final Tuple tuple = mTuples.get(i);
                final Animator animatorClone = tuple.mAnimator.clone();
                animatorClone.removeListener(mAnimatorListener);
                clone.addState(tuple.mSpecs, animatorClone);
            }
            clone.setChangingConfigurations(getChangingConfigurations());
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("cannot clone state list animator", e);
        }
    }

    /**
     * Called by View
     * @hide
     */
    public void setState(int[] state) {
        Tuple match = null;
        final int count = mTuples.size();
        for (int i = 0; i < count; i++) {
            final Tuple tuple = mTuples.get(i);
            if (StateSet.stateSetMatches(tuple.mSpecs, state)) {
                match = tuple;
                break;
            }
        }
        if (match == mLastMatch) {
            return;
        }
        if (mLastMatch != null) {
            cancel();
        }
        mLastMatch = match;
        if (match != null) {
            start(match);
        }
    }

    private void start(Tuple match) {
        match.mAnimator.setTarget(getTarget());
        mRunningAnimator = match.mAnimator;
        mRunningAnimator.start();
    }

    private void cancel() {
        if (mRunningAnimator != null) {
            mRunningAnimator.cancel();
            mRunningAnimator = null;
        }
    }

    /**
     * @hide
     */
    public ArrayList<Tuple> getTuples() {
        return mTuples;
    }

    /**
     * If there is an animation running for a recent state change, ends it.
     * <p>
     * This causes the animation to assign the end value(s) to the View.
     */
    public void jumpToCurrentState() {
        if (mRunningAnimator != null) {
            mRunningAnimator.end();
        }
    }

    /**
     * Return a mask of the configuration parameters for which this animator may change, requiring
     * that it be re-created.  The default implementation returns whatever was provided through
     * {@link #setChangingConfigurations(int)} or 0 by default.
     *
     * @return Returns a mask of the changing configuration parameters, as defined by
     * {@link android.content.pm.ActivityInfo}.
     *
     * @see android.content.pm.ActivityInfo
     * @hide
     */
    public int getChangingConfigurations() {
        return mChangingConfigurations;
    }

    /**
     * Set a mask of the configuration parameters for which this animator may change, requiring
     * that it should be recreated from resources instead of being cloned.
     *
     * @param configs A mask of the changing configuration parameters, as
     * defined by {@link android.content.pm.ActivityInfo}.
     *
     * @see android.content.pm.ActivityInfo
     * @hide
     */
    public void setChangingConfigurations(int configs) {
        mChangingConfigurations = configs;
    }

    /**
     * Sets the changing configurations value to the union of the current changing configurations
     * and the provided configs.
     * This method is called while loading the animator.
     * @hide
     */
    public void appendChangingConfigurations(int configs) {
        mChangingConfigurations |= configs;
    }

    /**
     * Return a {@link android.content.res.ConstantState} instance that holds the shared state of
     * this Animator.
     * <p>
     * This constant state is used to create new instances of this animator when needed. Default
     * implementation creates a new {@link StateListAnimatorConstantState}. You can override this
     * method to provide your custom logic or return null if you don't want this animator to be
     * cached.
     *
     * @return The {@link android.content.res.ConstantState} associated to this Animator.
     * @see android.content.res.ConstantState
     * @see #clone()
     * @hide
     */
    public ConstantState<StateListAnimator> createConstantState() {
        return new StateListAnimatorConstantState(this);
    }

    /**
     * @hide
     */
    public static class Tuple {

        final int[] mSpecs;

        final Animator mAnimator;

        private Tuple(int[] specs, Animator animator) {
            mSpecs = specs;
            mAnimator = animator;
        }

        /**
         * @hide
         */
        public int[] getSpecs() {
            return mSpecs;
        }

        /**
         * @hide
         */
        public Animator getAnimator() {
            return mAnimator;
        }
    }

    /**
     * Creates a constant state which holds changing configurations information associated with the
     * given Animator.
     * <p>
     * When new instance is called, default implementation clones the Animator.
     */
    private static class StateListAnimatorConstantState
            extends ConstantState<StateListAnimator> {

        final StateListAnimator mAnimator;

        int mChangingConf;

        public StateListAnimatorConstantState(StateListAnimator animator) {
            mAnimator = animator;
            mAnimator.mConstantState = this;
            mChangingConf = mAnimator.getChangingConfigurations();
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConf;
        }

        @Override
        public StateListAnimator newInstance() {
            final StateListAnimator clone = mAnimator.clone();
            clone.mConstantState = this;
            return clone;
        }
    }
}
