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
package android.view.transition;

import android.animation.Animator;
import android.util.AndroidRuntimeException;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A TransitionGroup is a parent of child transitions (including other
 * TransitionGroups). Using TransitionGroups enables more complex
 * choreography of transitions, where some groups play {@link #TOGETHER} and
 * others play {@link #SEQUENTIALLY}. For example, {@link AutoTransition}
 * uses a TransitionGroup to sequentially play a Fade(Fade.OUT), followed by
 * a {@link Move}, followed by a Fade(Fade.OUT) transition.
 */
public class TransitionGroup extends Transition {

    ArrayList<Transition> mTransitions = new ArrayList<Transition>();
    private boolean mPlayTogether = true;
    int mCurrentListeners;
    boolean mStarted = false;

    /**
     * A flag used to indicate that the child transitions of this group
     * should all start at the same time.
     */
    public static final int TOGETHER = 0;
    /**
     * A flag used to indicate that the child transitions of this group should
     * play in sequence; when one child transition ends, the next child
     * transition begins. Note that a transition does not end until all
     * instances of it (which are playing on all applicable targets of the
     * transition) end.
     */
    public static final int SEQUENTIALLY = 1;

    /**
     * Constructs an empty transition group. Add child transitions to the
     * group by calling to {@link #addTransitions(Transition...)} )}. By default,
     * child transitions will play {@link #TOGETHER}.
     */
    public TransitionGroup() {
    }

    /**
     * Constructs an empty transition group with the specified ordering.
     *
     * @param ordering {@link #TOGETHER} to start this group's child
     * transitions together, {@link #SEQUENTIALLY} to play the child
     * transitions in sequence.
     * @see #setOrdering(int)
     */
    public TransitionGroup(int ordering) {
        setOrdering(ordering);
    }

    /**
     * Sets the play order of this group's child transitions.
     *
     * @param ordering {@link #TOGETHER} to start this group's child
     * transitions together, {@link #SEQUENTIALLY} to play the child
     * transitions in sequence.
     */
    public void setOrdering(int ordering) {
        switch (ordering) {
            case SEQUENTIALLY:
                mPlayTogether = false;
                break;
            case TOGETHER:
                mPlayTogether = true;
                break;
            default:
                throw new AndroidRuntimeException("Invalid parameter for TransitionGroup " +
                        "ordering: " + ordering);
        }
    }

    /**
     * Adds child transitions to this group. The order of the child transitions
     * passed in determines the order in which they are started.
     *
     * @param transitions A list of child transition to be added to this group.
     */
    public void addTransitions(Transition... transitions) {
        if (transitions != null) {
            int numTransitions = transitions.length;
            for (int i = 0; i < numTransitions; ++i) {
                mTransitions.add(transitions[i]);
                if (mDuration >= 0) {
                    transitions[0].setDuration(mDuration);
                }
            }
        }
    }

    /**
     * Setting a non-negative duration on a TransitionGroup causes all of the child
     * transitions (current and future) to inherit this duration.
     *
     * @param duration The length of the animation, in milliseconds.
     * @return This transitionGroup object.
     */
    @Override
    public Transition setDuration(long duration) {
        super.setDuration(duration);
        if (mDuration >= 0) {
            int numTransitions = mTransitions.size();
            for (int i = 0; i < numTransitions; ++i) {
                mTransitions.get(i).setDuration(duration);
            }
        }
        return this;
    }

    /**
     * Removes the specified child transition from this group.
     *
     * @param transition The transition to be removed.
     */
    public void removeTransition(Transition transition) {
        mTransitions.remove(transition);
    }

    /**
     * Sets up listeners for each of the child transitions. This is used to
     * determine when this transition group is finished (all child transitions
     * must finish first).
     */
    private void setupStartEndListeners() {
        for (Transition childTransition : mTransitions) {
            childTransition.addListener(mListener);
        }
        mCurrentListeners = mTransitions.size();
    }

    /**
     * This listener is used to detect when all child transitions are done, at
     * which point this transition group is also done.
     */
    private TransitionListener mListener = new TransitionListenerAdapter() {
        @Override
        public void onTransitionStart(Transition transition) {
            if (!mStarted) {
                startTransition();
                mStarted = true;
            }
        }

        @Override
        public void onTransitionEnd(Transition transition) {
            --mCurrentListeners;
            if (mCurrentListeners == 0) {
                // All child trans
                mStarted = false;
                endTransition();
            }
            transition.removeListener(this);
        }
    };

    /**
     * @hide
     */
    @Override
    protected void prePlay(ViewGroup sceneRoot,
            HashMap<View, TransitionValues> startValues,
            SparseArray<TransitionValues> startIdValues,
            LongSparseArray<TransitionValues> startItemIdValues,
            HashMap<View, TransitionValues> endValues,
            SparseArray<TransitionValues> endIdValues,
            LongSparseArray<TransitionValues> endItemIdValues) {
        for (Transition childTransition : mTransitions) {
            childTransition.prePlay(sceneRoot, startValues, startIdValues, startItemIdValues,
                    endValues, endIdValues, endItemIdValues);
        }
    }

    /**
     * @hide
     */
    @Override
    protected void play(ViewGroup sceneRoot,
            final HashMap<View, TransitionValues> startValues,
            final SparseArray<TransitionValues> startIdValues,
            final LongSparseArray<TransitionValues> startItemIdValues,
            final HashMap<View, TransitionValues> endValues,
            final SparseArray<TransitionValues> endIdValues,
            final LongSparseArray<TransitionValues> endItemIdValues) {
        setupStartEndListeners();
        final ViewGroup finalSceneRoot = sceneRoot;
        if (!mPlayTogether) {
            // Setup sequence with listeners
            // TODO: Need to add listeners in such a way that we can remove them later if canceled
            for (int i = 1; i < mTransitions.size(); ++i) {
                Transition previousTransition = mTransitions.get(i - 1);
                final Transition nextTransition = mTransitions.get(i);
                previousTransition.addListener(new TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        nextTransition.play(finalSceneRoot,
                                startValues, startIdValues, startItemIdValues,
                                endValues, endIdValues, endItemIdValues);
                        transition.removeListener(this);
                    }
                });
            }
            Transition firstTransition = mTransitions.get(0);
            if (firstTransition != null) {
                firstTransition.play(finalSceneRoot, startValues, startIdValues, startItemIdValues,
                        endValues, endIdValues, endItemIdValues);
            }
        } else {
            for (Transition childTransition : mTransitions) {
                childTransition.play(finalSceneRoot, startValues, startIdValues, startItemIdValues,
                        endValues, endIdValues, endItemIdValues);
            }
        }
    }

    @Override
    protected Animator play(ViewGroup sceneRoot,
            TransitionValues startValues, TransitionValues endValues) {
        final View view = (endValues != null) ? endValues.view :
                (startValues != null) ? startValues.view : null;
        final int targetId = (view != null) ? view.getId() : -1;
        // TODO: not sure this is a valid check - what about auto-targets? No need for ids.
        if (targetId < 0) {
            return null;
        }
        setupStartEndListeners();
        if (!mPlayTogether) {
            final ViewGroup finalSceneRoot = sceneRoot;
            final TransitionValues finalStartValues = startValues;
            final TransitionValues finalEndValues = endValues;
            // Setup sequence with listeners
            // TODO: Need to add listeners in such a way that we can remove them later if canceled
            for (int i = 1; i < mTransitions.size(); ++i) {
                Transition previousTransition = mTransitions.get(i - 1);
                final Transition nextTransition = mTransitions.get(i);
                previousTransition.addListener(new TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        nextTransition.startTransition();
                        if (nextTransition.isValidTarget(view, targetId)) {
                            animate(nextTransition.play(finalSceneRoot, finalStartValues,
                                    finalEndValues));
                        } else {
                            nextTransition.endTransition();
                        }
                    }
                });
            }
            Transition firstTransition = mTransitions.get(0);
            if (firstTransition != null) {
                firstTransition.startTransition();
                if (firstTransition.isValidTarget(view, targetId)) {
                    animate(firstTransition.play(finalSceneRoot, finalStartValues, finalEndValues));
                } else {
                    firstTransition.endTransition();
                }
            }
        } else {
            for (Transition childTransition : mTransitions) {
                childTransition.startTransition();
                if (childTransition.isValidTarget(view, targetId)) {
                    animate(childTransition.play(sceneRoot, startValues, endValues));
                } else {
                    childTransition.endTransition();
                }
            }
        }
        return null;
    }

    @Override
    protected void captureValues(TransitionValues transitionValues, boolean start) {
        int targetId = transitionValues.view.getId();
        for (Transition childTransition : mTransitions) {
            if (childTransition.isValidTarget(transitionValues.view, targetId)) {
                childTransition.captureValues(transitionValues, start);
            }
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

}
