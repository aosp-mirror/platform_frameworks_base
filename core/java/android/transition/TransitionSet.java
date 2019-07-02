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

package android.transition;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AndroidRuntimeException;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * A TransitionSet is a parent of child transitions (including other
 * TransitionSets). Using TransitionSets enables more complex
 * choreography of transitions, where some sets play {@link #ORDERING_TOGETHER} and
 * others play {@link #ORDERING_SEQUENTIAL}. For example, {@link AutoTransition}
 * uses a TransitionSet to sequentially play a Fade(Fade.OUT), followed by
 * a {@link ChangeBounds}, followed by a Fade(Fade.OUT) transition.
 *
 * <p>A TransitionSet can be described in a resource file by using the
 * tag <code>transitionSet</code>, along with the standard
 * attributes of {@link android.R.styleable#TransitionSet} and
 * {@link android.R.styleable#Transition}. Child transitions of the
 * TransitionSet object can be loaded by adding those child tags inside the
 * enclosing <code>transitionSet</code> tag. For example, the following xml
 * describes a TransitionSet that plays a Fade and then a ChangeBounds
 * transition on the affected view targets:</p>
 * <pre>
 *     &lt;transitionSet xmlns:android="http://schemas.android.com/apk/res/android"
 *             android:transitionOrdering="sequential"&gt;
 *         &lt;fade/&gt;
 *         &lt;changeBounds/&gt;
 *     &lt;/transitionSet&gt;
 * </pre>
 */
public class TransitionSet extends Transition {
    /**
     * Flag indicating the the interpolator changed.
     */
    private static final int FLAG_CHANGE_INTERPOLATOR = 0x01;
    /**
     * Flag indicating the the propagation changed.
     */
    private static final int FLAG_CHANGE_PROPAGATION = 0x02;
    /**
     * Flag indicating the the path motion changed.
     */
    private static final int FLAG_CHANGE_PATH_MOTION = 0x04;
    /**
     * Flag indicating the the epicentera callback changed.
     */
    static final int FLAG_CHANGE_EPICENTER = 0x08;

    ArrayList<Transition> mTransitions = new ArrayList<Transition>();
    private boolean mPlayTogether = true;
    int mCurrentListeners;
    boolean mStarted = false;
    // Flags to know whether or not the interpolator, path motion, epicenter, propagation
    // have changed
    private int mChangeFlags = 0;

    /**
     * A flag used to indicate that the child transitions of this set
     * should all start at the same time.
     */
    public static final int ORDERING_TOGETHER = 0;
    /**
     * A flag used to indicate that the child transitions of this set should
     * play in sequence; when one child transition ends, the next child
     * transition begins. Note that a transition does not end until all
     * instances of it (which are playing on all applicable targets of the
     * transition) end.
     */
    public static final int ORDERING_SEQUENTIAL = 1;

    /**
     * Constructs an empty transition set. Add child transitions to the
     * set by calling {@link #addTransition(Transition)} )}. By default,
     * child transitions will play {@link #ORDERING_TOGETHER together}.
     */
    public TransitionSet() {
    }

    public TransitionSet(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TransitionSet);
        int ordering = a.getInt(R.styleable.TransitionSet_transitionOrdering,
                TransitionSet.ORDERING_TOGETHER);
        setOrdering(ordering);
        a.recycle();
    }

    /**
     * Sets the play order of this set's child transitions.
     *
     * @param ordering {@link #ORDERING_TOGETHER} to play this set's child
     * transitions together, {@link #ORDERING_SEQUENTIAL} to play the child
     * transitions in sequence.
     * @return This transitionSet object.
     */
    public TransitionSet setOrdering(int ordering) {
        switch (ordering) {
            case ORDERING_SEQUENTIAL:
                mPlayTogether = false;
                break;
            case ORDERING_TOGETHER:
                mPlayTogether = true;
                break;
            default:
                throw new AndroidRuntimeException("Invalid parameter for TransitionSet " +
                        "ordering: " + ordering);
        }
        return this;
    }

    /**
     * Returns the ordering of this TransitionSet. By default, the value is
     * {@link #ORDERING_TOGETHER}.
     *
     * @return {@link #ORDERING_TOGETHER} if child transitions will play at the same
     * time, {@link #ORDERING_SEQUENTIAL} if they will play in sequence.
     *
     * @see #setOrdering(int)
     */
    public int getOrdering() {
        return mPlayTogether ? ORDERING_TOGETHER : ORDERING_SEQUENTIAL;
    }

    /**
     * Adds child transition to this set. The order in which this child transition
     * is added relative to other child transitions that are added, in addition to
     * the {@link #getOrdering() ordering} property, determines the
     * order in which the transitions are started.
     *
     * <p>If this transitionSet has a {@link #getDuration() duration},
     * {@link #getInterpolator() interpolator}, {@link #getPropagation() propagation delay},
     * {@link #getPathMotion() path motion}, or
     * {@link #setEpicenterCallback(EpicenterCallback) epicenter callback}
     * set on it, the child transition will inherit the values that are set.
     * Transitions are assumed to have a maximum of one transitionSet parent.</p>
     *
     * @param transition A non-null child transition to be added to this set.
     * @return This transitionSet object.
     */
    public TransitionSet addTransition(Transition transition) {
        if (transition != null) {
            addTransitionInternal(transition);
            if (mDuration >= 0) {
                transition.setDuration(mDuration);
            }
            if ((mChangeFlags & FLAG_CHANGE_INTERPOLATOR) != 0) {
                transition.setInterpolator(getInterpolator());
            }
            if ((mChangeFlags & FLAG_CHANGE_PROPAGATION) != 0) {
                transition.setPropagation(getPropagation());
            }
            if ((mChangeFlags & FLAG_CHANGE_PATH_MOTION) != 0) {
                transition.setPathMotion(getPathMotion());
            }
            if ((mChangeFlags & FLAG_CHANGE_EPICENTER) != 0) {
                transition.setEpicenterCallback(getEpicenterCallback());
            }
        }
        return this;
    }

    private void addTransitionInternal(Transition transition) {
        mTransitions.add(transition);
        transition.mParent = this;
    }

    /**
     * Returns the number of child transitions in the TransitionSet.
     *
     * @return The number of child transitions in the TransitionSet.
     * @see #addTransition(Transition)
     * @see #getTransitionAt(int)
     */
    public int getTransitionCount() {
        return mTransitions.size();
    }

    /**
     * Returns the child Transition at the specified position in the TransitionSet.
     *
     * @param index The position of the Transition to retrieve.
     * @see #addTransition(Transition)
     * @see #getTransitionCount()
     */
    public Transition getTransitionAt(int index) {
        if (index < 0 || index >= mTransitions.size()) {
            return null;
        }
        return mTransitions.get(index);
    }

    /**
     * Setting a non-negative duration on a TransitionSet causes all of the child
     * transitions (current and future) to inherit this duration.
     *
     * @param duration The length of the animation, in milliseconds.
     * @return This transitionSet object.
     */
    @Override
    public TransitionSet setDuration(long duration) {
        super.setDuration(duration);
        if (mDuration >= 0 && mTransitions != null) {
            int numTransitions = mTransitions.size();
            for (int i = 0; i < numTransitions; ++i) {
                mTransitions.get(i).setDuration(duration);
            }
        }
        return this;
    }

    @Override
    public TransitionSet setStartDelay(long startDelay) {
        return (TransitionSet) super.setStartDelay(startDelay);
    }

    @Override
    public TransitionSet setInterpolator(TimeInterpolator interpolator) {
        mChangeFlags |= FLAG_CHANGE_INTERPOLATOR;
        if (mTransitions != null) {
            int numTransitions = mTransitions.size();
            for (int i = 0; i < numTransitions; ++i) {
                mTransitions.get(i).setInterpolator(interpolator);
            }
        }
        return (TransitionSet) super.setInterpolator(interpolator);
    }

    @Override
    public TransitionSet addTarget(View target) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).addTarget(target);
        }
        return (TransitionSet) super.addTarget(target);
    }

    @Override
    public TransitionSet addTarget(int targetId) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).addTarget(targetId);
        }
        return (TransitionSet) super.addTarget(targetId);
    }

    @Override
    public TransitionSet addTarget(String targetName) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).addTarget(targetName);
        }
        return (TransitionSet) super.addTarget(targetName);
    }

    @Override
    public TransitionSet addTarget(Class targetType) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).addTarget(targetType);
        }
        return (TransitionSet) super.addTarget(targetType);
    }

    @Override
    public TransitionSet addListener(TransitionListener listener) {
        return (TransitionSet) super.addListener(listener);
    }

    @Override
    public TransitionSet removeTarget(int targetId) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).removeTarget(targetId);
        }
        return (TransitionSet) super.removeTarget(targetId);
    }

    @Override
    public TransitionSet removeTarget(View target) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).removeTarget(target);
        }
        return (TransitionSet) super.removeTarget(target);
    }

    @Override
    public TransitionSet removeTarget(Class target) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).removeTarget(target);
        }
        return (TransitionSet) super.removeTarget(target);
    }

    @Override
    public TransitionSet removeTarget(String target) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).removeTarget(target);
        }
        return (TransitionSet) super.removeTarget(target);
    }

    @Override
    public Transition excludeTarget(View target, boolean exclude) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).excludeTarget(target, exclude);
        }
        return super.excludeTarget(target, exclude);
    }

    @Override
    public Transition excludeTarget(String targetName, boolean exclude) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).excludeTarget(targetName, exclude);
        }
        return super.excludeTarget(targetName, exclude);
    }

    @Override
    public Transition excludeTarget(int targetId, boolean exclude) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).excludeTarget(targetId, exclude);
        }
        return super.excludeTarget(targetId, exclude);
    }

    @Override
    public Transition excludeTarget(Class type, boolean exclude) {
        for (int i = 0; i < mTransitions.size(); i++) {
            mTransitions.get(i).excludeTarget(type, exclude);
        }
        return super.excludeTarget(type, exclude);
    }

    @Override
    public TransitionSet removeListener(TransitionListener listener) {
        return (TransitionSet) super.removeListener(listener);
    }

    @Override
    public void setPathMotion(PathMotion pathMotion) {
        super.setPathMotion(pathMotion);
        mChangeFlags |= FLAG_CHANGE_PATH_MOTION;
        if (mTransitions != null) {
            for (int i = 0; i < mTransitions.size(); i++) {
                mTransitions.get(i).setPathMotion(pathMotion);
            }
        }
    }

    /**
     * Removes the specified child transition from this set.
     *
     * @param transition The transition to be removed.
     * @return This transitionSet object.
     */
    public TransitionSet removeTransition(Transition transition) {
        mTransitions.remove(transition);
        transition.mParent = null;
        return this;
    }

    /**
     * Sets up listeners for each of the child transitions. This is used to
     * determine when this transition set is finished (all child transitions
     * must finish first).
     */
    private void setupStartEndListeners() {
        TransitionSetListener listener = new TransitionSetListener(this);
        for (Transition childTransition : mTransitions) {
            childTransition.addListener(listener);
        }
        mCurrentListeners = mTransitions.size();
    }

    /**
     * This listener is used to detect when all child transitions are done, at
     * which point this transition set is also done.
     */
    static class TransitionSetListener extends TransitionListenerAdapter {
        TransitionSet mTransitionSet;
        TransitionSetListener(TransitionSet transitionSet) {
            mTransitionSet = transitionSet;
        }
        @Override
        public void onTransitionStart(Transition transition) {
            if (!mTransitionSet.mStarted) {
                mTransitionSet.start();
                mTransitionSet.mStarted = true;
            }
        }

        @Override
        public void onTransitionEnd(Transition transition) {
            --mTransitionSet.mCurrentListeners;
            if (mTransitionSet.mCurrentListeners == 0) {
                // All child trans
                mTransitionSet.mStarted = false;
                mTransitionSet.end();
            }
            transition.removeListener(this);
        }
    }

    /**
     * @hide
     */
    @Override
    protected void createAnimators(ViewGroup sceneRoot, TransitionValuesMaps startValues,
            TransitionValuesMaps endValues, ArrayList<TransitionValues> startValuesList,
            ArrayList<TransitionValues> endValuesList) {
        long startDelay = getStartDelay();
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; i++) {
            Transition childTransition = mTransitions.get(i);
            // We only set the start delay on the first transition if we are playing
            // the transitions sequentially.
            if (startDelay > 0 && (mPlayTogether || i == 0)) {
                long childStartDelay = childTransition.getStartDelay();
                if (childStartDelay > 0) {
                    childTransition.setStartDelay(startDelay + childStartDelay);
                } else {
                    childTransition.setStartDelay(startDelay);
                }
            }
            childTransition.createAnimators(sceneRoot, startValues, endValues, startValuesList,
                    endValuesList);
        }
    }

    /**
     * @hide
     */
    @Override
    protected void runAnimators() {
        if (mTransitions.isEmpty()) {
            start();
            end();
            return;
        }
        setupStartEndListeners();
        int numTransitions = mTransitions.size();
        if (!mPlayTogether) {
            // Setup sequence with listeners
            // TODO: Need to add listeners in such a way that we can remove them later if canceled
            for (int i = 1; i < numTransitions; ++i) {
                Transition previousTransition = mTransitions.get(i - 1);
                final Transition nextTransition = mTransitions.get(i);
                previousTransition.addListener(new TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        nextTransition.runAnimators();
                        transition.removeListener(this);
                    }
                });
            }
            Transition firstTransition = mTransitions.get(0);
            if (firstTransition != null) {
                firstTransition.runAnimators();
            }
        } else {
            for (int i = 0; i < numTransitions; ++i) {
                mTransitions.get(i).runAnimators();
            }
        }
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        if (isValidTarget(transitionValues.view)) {
            for (Transition childTransition : mTransitions) {
                if (childTransition.isValidTarget(transitionValues.view)) {
                    childTransition.captureStartValues(transitionValues);
                    transitionValues.targetedTransitions.add(childTransition);
                }
            }
        }
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        if (isValidTarget(transitionValues.view)) {
            for (Transition childTransition : mTransitions) {
                if (childTransition.isValidTarget(transitionValues.view)) {
                    childTransition.captureEndValues(transitionValues);
                    transitionValues.targetedTransitions.add(childTransition);
                }
            }
        }
    }

    @Override
    void capturePropagationValues(TransitionValues transitionValues) {
        super.capturePropagationValues(transitionValues);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).capturePropagationValues(transitionValues);
        }
    }

    /** @hide */
    @Override
    public void pause(View sceneRoot) {
        super.pause(sceneRoot);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).pause(sceneRoot);
        }
    }

    /** @hide */
    @Override
    public void resume(View sceneRoot) {
        super.resume(sceneRoot);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).resume(sceneRoot);
        }
    }

    /** @hide */
    @Override
    protected void cancel() {
        super.cancel();
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).cancel();
        }
    }

    /** @hide */
    @Override
    void forceToEnd(ViewGroup sceneRoot) {
        super.forceToEnd(sceneRoot);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).forceToEnd(sceneRoot);
        }
    }

    @Override
    TransitionSet setSceneRoot(ViewGroup sceneRoot) {
        super.setSceneRoot(sceneRoot);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).setSceneRoot(sceneRoot);
        }
        return (TransitionSet) this;
    }

    @Override
    void setCanRemoveViews(boolean canRemoveViews) {
        super.setCanRemoveViews(canRemoveViews);
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).setCanRemoveViews(canRemoveViews);
        }
    }

    @Override
    public void setPropagation(TransitionPropagation propagation) {
        super.setPropagation(propagation);
        mChangeFlags |= FLAG_CHANGE_PROPAGATION;
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).setPropagation(propagation);
        }
    }

    @Override
    public void setEpicenterCallback(EpicenterCallback epicenterCallback) {
        super.setEpicenterCallback(epicenterCallback);
        mChangeFlags |= FLAG_CHANGE_EPICENTER;
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            mTransitions.get(i).setEpicenterCallback(epicenterCallback);
        }
    }

    @Override
    String toString(String indent) {
        String result = super.toString(indent);
        for (int i = 0; i < mTransitions.size(); ++i) {
            result += "\n" + mTransitions.get(i).toString(indent + "  ");
        }
        return result;
    }

    @Override
    public TransitionSet clone() {
        TransitionSet clone = (TransitionSet) super.clone();
        clone.mTransitions = new ArrayList<Transition>();
        int numTransitions = mTransitions.size();
        for (int i = 0; i < numTransitions; ++i) {
            clone.addTransitionInternal((Transition) mTransitions.get(i).clone());
        }
        return clone;
    }
}
