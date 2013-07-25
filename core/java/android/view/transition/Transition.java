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
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOverlay;
import android.widget.ListView;

import java.util.ArrayList;

/**
 * A Transition holds information about animations that will be run on its
 * targets during a scene change. Subclasses of this abstract class may
 * choreograph several child transitions ({@link TransitionGroup} or they may
 * perform custom animations themselves. Any Transition has two main jobs:
 * (1) capture property values, and (2) play animations based on changes to
 * captured property values. A custom transition knows what property values
 * on View objects are of interest to it, and also knows how to animate
 * changes to those values. For example, the {@link Fade} transition tracks
 * changes to visibility-related properties and is able to construct and run
 * animations that fade items in or out based on changes to those properties.
 *
 * <p>Note: Transitions may not work correctly with either {@link SurfaceView}
 * or {@link TextureView}, due to the way that these views are displayed
 * on the screen. For SurfaceView, the problem is that the view is updated from
 * a non-UI thread, so changes to the view due to transitions (such as moving
 * and resizing the view) may be out of sync with the display inside those bounds.
 * TextureView is more compatible with transitions in general, but some
 * specific transitions (such as {@link Crossfade}) may not be compatible
 * with TextureView because they rely on {@link ViewOverlay} functionality,
 * which does not currently work with TextureView.</p>
 */
public abstract class Transition implements Cloneable {

    private static final String LOG_TAG = "Transition";
    static final boolean DBG = false;

    private String mName = getClass().getName();

    long mStartDelay = -1;
    long mDuration = -1;
    TimeInterpolator mInterpolator = null;
    int[] mTargetIds;
    View[] mTargets;
    private TransitionValuesMaps mStartValues = new TransitionValuesMaps();
    private TransitionValuesMaps mEndValues = new TransitionValuesMaps();
    TransitionGroup mParent = null;

    // Per-animator information used for later canceling when future transitions overlap
    private static ThreadLocal<ArrayMap<Animator, AnimationInfo>> sRunningAnimators =
            new ThreadLocal<ArrayMap<Animator, AnimationInfo>>();

    // Scene Root is set at play() time in the cloned Transition
    ViewGroup mSceneRoot = null;

    // Track all animators in use in case the transition gets canceled and needs to
    // cancel running animators
    private ArrayList<Animator> mCurrentAnimators = new ArrayList<Animator>();

    // Number of per-target instances of this Transition currently running. This count is
    // determined by calls to start() and end()
    int mNumInstances = 0;

    // Whether this transition is currently paused, due to a call to pause()
    boolean mPaused = false;

    // The set of listeners to be sent transition lifecycle events.
    ArrayList<TransitionListener> mListeners = null;

    // The set of animators collected from calls to play(), to be run in runAnimations()
    ArrayList<Animator> mAnimators = new ArrayList<Animator>();

    /**
     * Constructs a Transition object with no target objects. A transition with
     * no targets defaults to running on all target objects in the scene hierarchy
     * (if the transition is not contained in a TransitionGroup), or all target
     * objects passed down from its parent (if it is in a TransitionGroup).
     */
    public Transition() {}

    /**
     * Sets the duration of this transition. By default, there is no duration
     * (indicated by a negative number), which means that the Animator created by
     * the transition will have its own specified duration. If the duration of a
     * Transition is set, that duration will override the Animator duration.
     *
     * @param duration The length of the animation, in milliseconds.
     * @return This transition object.
     */
    public Transition setDuration(long duration) {
        mDuration = duration;
        return this;
    }

    /**
     * Returns the duration set on this transition. If no duration has been set,
     * the returned value will be negative, indicating that resulting animators will
     * retain their own durations.
     *
     * @return The duration set on this transition, if one has been set, otherwise
     * returns a negative number.
     */
    public long getDuration() {
        return mDuration;
    }

    /**
     * Sets the startDelay of this transition. By default, there is no delay
     * (indicated by a negative number), which means that the Animator created by
     * the transition will have its own specified startDelay. If the delay of a
     * Transition is set, that delay will override the Animator delay.
     *
     * @param startDelay The length of the delay, in milliseconds.
     */
    public void setStartDelay(long startDelay) {
        mStartDelay = startDelay;
    }

    /**
     * Returns the startDelay set on this transition. If no startDelay has been set,
     * the returned value will be negative, indicating that resulting animators will
     * retain their own startDelays.
     *
     * @return The startDealy set on this transition, if one has been set, otherwise
     * returns a negative number.
     */
    public long getStartDelay() {
        return mStartDelay;
    }

    /**
     * Sets the interpolator of this transition. By default, the interpolator
     * is null, which means that the Animator created by the transition
     * will have its own specified interpolator. If the interpolator of a
     * Transition is set, that interpolator will override the Animator interpolator.
     *
     * @param interpolator The time interpolator used by the transition
     */
    public void setInterpolator(TimeInterpolator interpolator) {
        mInterpolator = interpolator;
    }

    /**
     * Returns the interpolator set on this transition. If no interpolator has been set,
     * the returned value will be null, indicating that resulting animators will
     * retain their own interpolators.
     *
     * @return The interpolator set on this transition, if one has been set, otherwise
     * returns null.
     */
    public TimeInterpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * Returns the set of property names used stored in the {@link TransitionValues}
     * object passed into {@link #captureValues(TransitionValues, boolean)} that
     * this transition cares about for the purposes of canceling overlapping animations.
     * When any transition is started on a given scene root, all transitions
     * currently running on that same scene root are checked to see whether the
     * properties on which they based their animations agree with the end values of
     * the same properties in the new transition. If the end values are not equal,
     * then the old animation is canceled since the new transition will start a new
     * animation to these new values. If the values are equal, the old animation is
     * allowed to continue and no new animation is started for that transition.
     *
     * <p>A transition does not need to override this method. However, not doing so
     * will mean that the cancellation logic outlined in the previous paragraph
     * will be skipped for that transition, possibly leading to artifacts as
     * old transitions and new transitions on the same targets run in parallel,
     * animating views toward potentially different end values.</p>
     *
     * @return An array of property names as described in the class documentation for
     * {@link TransitionValues}. The default implementation returns <code>null</code>.
     */
    public String[] getTransitionProperties() {
        return null;
    }

    /**
     * This method is called by the transition's parent (all the way up to the
     * topmost Transition in the hierarchy) with the sceneRoot and start/end
     * values that the transition may need to set up initial target values
     * and construct an appropriate animation. For example, if an overall
     * Transition is a {@link TransitionGroup} consisting of several
     * child transitions in sequence, then some of the child transitions may
     * want to set initial values on target views prior to the overall
     * Transition commencing, to put them in an appropriate state for the
     * delay between that start and the child Transition start time. For
     * example, a transition that fades an item in may wish to set the starting
     * alpha value to 0, to avoid it blinking in prior to the transition
     * actually starting the animation. This is necessary because the scene
     * change that triggers the Transition will automatically set the end-scene
     * on all target views, so a Transition that wants to animate from a
     * different value should set that value prior to returning from this method.
     *
     * <p>Additionally, a Transition can perform logic to determine whether
     * the transition needs to run on the given target and start/end values.
     * For example, a transition that resizes objects on the screen may wish
     * to avoid running for views which are not present in either the start
     * or end scenes. A return value of <code>null</code> indicates that
     * no animation should run. The default implementation returns null.</p>
     *
     * <p>If there is an animator created and returned from this method, the
     * transition mechanism will apply any applicable duration, startDelay,
     * and interpolator to that animation and start it. A return value of
     * <code>null</code> indicates that no animation should run. The default
     * implementation returns null.</p>
     *
     * <p>The method is called for every applicable target object, which is
     * stored in the {@link TransitionValues#view} field.</p>
     *
     * @param sceneRoot
     * @param startValues
     * @param endValues
     * @return A non-null Animator to be started at the appropriate time in the
     * overall transition for this scene change, null otherwise.
     */
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        return null;
    }

    /**
     * This version of play() is called with the entire set of start/end
     * values. The implementation in Transition iterates through these lists
     * and calls {@link #play(ViewGroup, TransitionValues, TransitionValues)}
     * with each set of start/end values on this transition. The
     * TransitionGroup subclass overrides this method and delegates it to
     * each of its children in succession.
     *
     * @hide
     */
    protected void play(ViewGroup sceneRoot, TransitionValuesMaps startValues,
            TransitionValuesMaps endValues) {
        if (DBG) {
            Log.d(LOG_TAG, "play() for " + this);
        }
        ArrayMap<View, TransitionValues> endCopy =
                new ArrayMap<View, TransitionValues>(endValues.viewValues);
        SparseArray<TransitionValues> endIdCopy =
                new SparseArray<TransitionValues>(endValues.idValues.size());
        for (int i = 0; i < endValues.idValues.size(); ++i) {
            int id = endValues.idValues.keyAt(i);
            endIdCopy.put(id, endValues.idValues.valueAt(i));
        }
        LongSparseArray<TransitionValues> endItemIdCopy =
                new LongSparseArray<TransitionValues>(endValues.itemIdValues.size());
        for (int i = 0; i < endValues.itemIdValues.size(); ++i) {
            long id = endValues.itemIdValues.keyAt(i);
            endItemIdCopy.put(id, endValues.itemIdValues.valueAt(i));
        }
        // Walk through the start values, playing everything we find
        // Remove from the end set as we go
        ArrayList<TransitionValues> startValuesList = new ArrayList<TransitionValues>();
        ArrayList<TransitionValues> endValuesList = new ArrayList<TransitionValues>();
        for (View view : startValues.viewValues.keySet()) {
            TransitionValues start = null;
            TransitionValues end = null;
            boolean isInListView = false;
            if (view.getParent() instanceof ListView) {
                isInListView = true;
            }
            if (!isInListView) {
                int id = view.getId();
                start = startValues.viewValues.get(view) != null ?
                        startValues.viewValues.get(view) : startValues.idValues.get(id);
                if (endValues.viewValues.get(view) != null) {
                    end = endValues.viewValues.get(view);
                    endCopy.remove(view);
                } else {
                    end = endValues.idValues.get(id);
                    View removeView = null;
                    for (View viewToRemove : endCopy.keySet()) {
                        if (viewToRemove.getId() == id) {
                            removeView = viewToRemove;
                        }
                    }
                    if (removeView != null) {
                        endCopy.remove(removeView);
                    }
                }
                endIdCopy.remove(id);
                if (isValidTarget(view, id)) {
                    startValuesList.add(start);
                    endValuesList.add(end);
                }
            } else {
                ListView parent = (ListView) view.getParent();
                if (parent.getAdapter().hasStableIds()) {
                    int position = parent.getPositionForView(view);
                    long itemId = parent.getItemIdAtPosition(position);
                    start = startValues.itemIdValues.get(itemId);
                    endItemIdCopy.remove(itemId);
                    // TODO: deal with targetIDs for itemIDs for ListView items
                    startValuesList.add(start);
                    endValuesList.add(end);
                }
            }
        }
        int startItemIdCopySize = startValues.itemIdValues.size();
        for (int i = 0; i < startItemIdCopySize; ++i) {
            long id = startValues.itemIdValues.keyAt(i);
            if (isValidTarget(null, id)) {
                TransitionValues start = startValues.itemIdValues.get(id);
                TransitionValues end = endValues.itemIdValues.get(id);
                endItemIdCopy.remove(id);
                startValuesList.add(start);
                endValuesList.add(end);
            }
        }
        // Now walk through the remains of the end set
        for (View view : endCopy.keySet()) {
            int id = view.getId();
            if (isValidTarget(view, id)) {
                TransitionValues start = startValues.viewValues.get(view) != null ?
                        startValues.viewValues.get(view) : startValues.idValues.get(id);
                TransitionValues end = endCopy.get(view);
                endIdCopy.remove(id);
                startValuesList.add(start);
                endValuesList.add(end);
            }
        }
        int endIdCopySize = endIdCopy.size();
        for (int i = 0; i < endIdCopySize; ++i) {
            int id = endIdCopy.keyAt(i);
            if (isValidTarget(null, id)) {
                TransitionValues start = startValues.idValues.get(id);
                TransitionValues end = endIdCopy.get(id);
                startValuesList.add(start);
                endValuesList.add(end);
            }
        }
        int endItemIdCopySize = endItemIdCopy.size();
        for (int i = 0; i < endItemIdCopySize; ++i) {
            long id = endItemIdCopy.keyAt(i);
            // TODO: Deal with targetIDs and itemIDs
            TransitionValues start = startValues.itemIdValues.get(id);
            TransitionValues end = endItemIdCopy.get(id);
            startValuesList.add(start);
            endValuesList.add(end);
        }
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        for (int i = 0; i < startValuesList.size(); ++i) {
            TransitionValues start = startValuesList.get(i);
            TransitionValues end = endValuesList.get(i);
            // Only bother trying to animate with values that differ between start/end
            if (start != null || end != null) {
                if (start == null || !start.equals(end)) {
                    if (DBG) {
                        View view = (end != null) ? end.view : start.view;
                        Log.d(LOG_TAG, "  differing start/end values for view " +
                                view);
                        if (start == null || end == null) {
                            if (start == null) {
                                Log.d(LOG_TAG, "    " + ((start == null) ?
                                        "start null, end non-null" : "start non-null, end null"));
                            }
                        } else {
                            for (String key : start.values.keySet()) {
                                Object startValue = start.values.get(key);
                                Object endValue = end.values.get(key);
                                if (startValue != endValue && !startValue.equals(endValue)) {
                                    Log.d(LOG_TAG, "    " + key + ": start(" + startValue +
                                            "), end(" + endValue +")");
                                }
                            }
                        }
                    }
                    // TODO: what to do about targetIds and itemIds?
                    Animator animator = play(sceneRoot, start, end);
                    if (animator != null) {
                        // Save animation info for future cancellation purposes
                        View view = null;
                        TransitionValues infoValues = null;
                        if (end != null) {
                            view = end.view;
                            String[] properties = getTransitionProperties();
                            if (view != null && properties != null && properties.length > 0) {
                                infoValues = new TransitionValues();
                                infoValues.view = view;
                                TransitionValues newValues = endValues.viewValues.get(view);
                                if (newValues != null) {
                                    for (int j = 0; j < properties.length; ++j) {
                                        infoValues.values.put(properties[j],
                                                newValues.values.get(properties[j]));
                                    }
                                }
                                int numExistingAnims = runningAnimators.size();
                                for (int j = 0; j < numExistingAnims; ++j) {
                                    Animator anim = runningAnimators.keyAt(j);
                                    AnimationInfo info = runningAnimators.get(anim);
                                    if (info.values != null && info.view == view &&
                                            ((info.name == null && getName() == null) ||
                                            info.name.equals(getName()))) {
                                        if (info.values.equals(infoValues)) {
                                            // Favor the old animator
                                            animator = null;
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            view = (start != null) ? start.view : null;
                        }
                        if (animator != null) {
                            AnimationInfo info = new AnimationInfo(view, getName(), infoValues);
                            runningAnimators.put(animator, info);
                            mAnimators.add(animator);
                        }
                    }
                }
            }
        }
    }

    /**
     * Internal utility method for checking whether a given view/id
     * is valid for this transition, where "valid" means that either
     * the Transition has no target/targetId list (the default, in which
     * cause the transition should act on all views in the hiearchy), or
     * the given view is in the target list or the view id is in the
     * targetId list. If the target parameter is null, then the target list
     * is not checked (this is in the case of ListView items, where the
     * views are ignored and only the ids are used).
     */
    boolean isValidTarget(View target, long targetId) {
        if (mTargetIds == null && mTargets == null) {
            return true;
        }
        if (mTargetIds != null) {
            for (int i = 0; i < mTargetIds.length; ++i) {
                if (mTargetIds[i] == targetId) {
                    return true;
                }
            }
        }
        if (target != null && mTargets != null) {
            for (int i = 0; i < mTargets.length; ++i) {
                if (mTargets[i] == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ArrayMap<Animator, AnimationInfo> getRunningAnimators() {
        ArrayMap<Animator, AnimationInfo> runningAnimators = sRunningAnimators.get();
        if (runningAnimators == null) {
            runningAnimators = new ArrayMap<Animator, AnimationInfo>();
            sRunningAnimators.set(runningAnimators);
        }
        return runningAnimators;
    }

    /**
     * This is called internally once all animations have been set up by the
     * transition hierarchy. \
     *
     * @hide
     */
    protected void runAnimations() {
        if (DBG) {
            Log.d(LOG_TAG, "runAnimations() on " + this);
        }
        start();
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        // Now start every Animator that was previously created for this transition in play()
        for (Animator anim : mAnimators) {
            if (DBG) {
                Log.d(LOG_TAG, "  anim: " + anim);
            }
            if (runningAnimators.containsKey(anim)) {
                start();
                runAnimator(anim, runningAnimators);
            }
        }
        mAnimators.clear();
        end();
    }

    private void runAnimator(Animator animator,
            final ArrayMap<Animator, AnimationInfo> runningAnimators) {
        if (animator != null) {
            // TODO: could be a single listener instance for all of them since it uses the param
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mCurrentAnimators.add(animation);
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnimators.remove(animation);
                    mCurrentAnimators.remove(animation);
                }
            });
            animate(animator);
        }
    }

    /**
     * Captures the current scene of values for the properties that this
     * transition monitors. These values can be either the start or end
     * values used in a subsequent call to
     * {@link #play(ViewGroup, TransitionValues, TransitionValues)}, as indicated by
     * <code>start</code>. The main concern for an implementation is what the
     * properties are that the transition cares about and what the values are
     * for all of those properties. The start and end values will be compared
     * later during the
     * {@link #play(android.view.ViewGroup, TransitionValues, TransitionValues)}
     * method to determine what, if any, animations, should be run.
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
    protected abstract void captureValues(TransitionValues transitionValues, boolean start);

    /**
     * Sets the ids of target views that this Transition is interested in
     * animating. By default, there are no targetIds, and a Transition will
     * listen for changes on every view in the hierarchy below the sceneRoot
     * of the Scene being transitioned into. Setting targetIDs constrains
     * the Transition to only listen for, and act on, views with these IDs.
     * Views with different IDs, or no IDs whatsoever, will be ignored.
     *
     * @see View#getId()
     * @param targetIds A list of IDs which specify the set of Views on which
     * the Transition will act.
     * @return Transition The Transition on which the targetIds have been set.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionGroup.addTransitions(new Fade()).setTargetIds(someId);</code>
     */
    public Transition setTargetIds(int... targetIds) {
        int numTargets = targetIds.length;
        mTargetIds = new int[numTargets];
        System.arraycopy(targetIds, 0, mTargetIds, 0, numTargets);
        return this;
    }

    /**
     * Sets the target view instances that this Transition is interested in
     * animating. By default, there are no targets, and a Transition will
     * listen for changes on every view in the hierarchy below the sceneRoot
     * of the Scene being transitioned into. Setting targets constrains
     * the Transition to only listen for, and act on, these views.
     * All other views will be ignored.
     *
     * <p>The target list is like the {@link #setTargetIds(int...) targetId}
     * list except this list specifies the actual View instances, not the ids
     * of the views. This is an important distinction when scene changes involve
     * view hierarchies which have been inflated separately; different views may
     * share the same id but not actually be the same instance. If the transition
     * should treat those views as the same, then seTargetIds() should be used
     * instead of setTargets(). If, on the other hand, scene changes involve
     * changes all within the same view hierarchy, among views which do not
     * necessary have ids set on them, then the target list may be more
     * convenient.</p>
     *
     * @see #setTargetIds(int...)
     * @param targets A list of Views on which the Transition will act.
     * @return Transition The Transition on which the targets have been set.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionGroup.addTransitions(new Fade()).setTargets(someView);</code>
     */
    public Transition setTargets(View... targets) {
        int numTargets = targets.length;
        mTargets = new View[numTargets];
        System.arraycopy(targets, 0, mTargets, 0, numTargets);
        return this;
    }

    /**
     * Returns the array of target IDs that this transition limits itself to
     * tracking and animating. If the array is null for both this method and
     * {@link #getTargets()}, then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target IDs
     */
    public int[] getTargetIds() {
        return mTargetIds;
    }

    /**
     * Returns the array of target views that this transition limits itself to
     * tracking and animating. If the array is null for both this method and
     * {@link #getTargetIds()}, then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target views
     */
    public View[] getTargets() {
        return mTargets;
    }

    /**
     * Recursive method that captures values for the given view and the
     * hierarchy underneath it.
     * @param sceneRoot The root of the view hierarchy being captured
     * @param start true if this capture is happening before the scene change,
     * false otherwise
     */
    void captureValues(ViewGroup sceneRoot, boolean start) {
        if (start) {
            mStartValues.viewValues.clear();
            mStartValues.idValues.clear();
            mStartValues.itemIdValues.clear();
        } else {
            mEndValues.viewValues.clear();
            mEndValues.idValues.clear();
            mEndValues.itemIdValues.clear();
        }
        if (mTargetIds != null && mTargetIds.length > 0 ||
                mTargets != null && mTargets.length > 0) {
            if (mTargetIds != null) {
                for (int i = 0; i < mTargetIds.length; ++i) {
                    int id = mTargetIds[i];
                    View view = sceneRoot.findViewById(id);
                    if (view != null) {
                        TransitionValues values = new TransitionValues();
                        values.view = view;
                        captureValues(values, start);
                        if (start) {
                            mStartValues.viewValues.put(view, values);
                            if (id >= 0) {
                                mStartValues.idValues.put(id, values);
                            }
                        } else {
                            mEndValues.viewValues.put(view, values);
                            if (id >= 0) {
                                mEndValues.idValues.put(id, values);
                            }
                        }
                    }
                }
            }
            if (mTargets != null) {
                for (int i = 0; i < mTargets.length; ++i) {
                    View view = mTargets[i];
                    if (view != null) {
                        TransitionValues values = new TransitionValues();
                        values.view = view;
                        captureValues(values, start);
                        if (start) {
                            mStartValues.viewValues.put(view, values);
                        } else {
                            mEndValues.viewValues.put(view, values);
                        }
                    }
                }
            }
        } else {
            captureHierarchy(sceneRoot, start);
        }
    }

    /**
     * Recursive method which captures values for an entire view hierarchy,
     * starting at some root view. Transitions without targetIDs will use this
     * method to capture values for all possible views.
     *
     * @param view The view for which to capture values. Children of this View
     * will also be captured, recursively down to the leaf nodes.
     * @param start true if values are being captured in the start scene, false
     * otherwise.
     */
    private void captureHierarchy(View view, boolean start) {
        if (view == null) {
            return;
        }
        boolean isListViewItem = false;
        if (view.getParent() instanceof ListView) {
            isListViewItem = true;
        }
        if (isListViewItem && !((ListView) view.getParent()).getAdapter().hasStableIds()) {
            // ignore listview children unless we can track them with stable IDs
            return;
        }
        long id;
        if (!isListViewItem) {
            id = view.getId();
        } else {
            ListView listview = (ListView) view.getParent();
            int position = listview.getPositionForView(view);
            id = listview.getItemIdAtPosition(position);
            view.setHasTransientState(true);
        }
        TransitionValues values = new TransitionValues();
        values.view = view;
        captureValues(values, start);
        if (start) {
            if (!isListViewItem) {
                mStartValues.viewValues.put(view, values);
                if (id >= 0) {
                    mStartValues.idValues.put((int) id, values);
                }
            } else {
                mStartValues.itemIdValues.put(id, values);
            }
        } else {
            if (!isListViewItem) {
                mEndValues.viewValues.put(view, values);
                if (id >= 0) {
                    mEndValues.idValues.put((int) id, values);
                }
            } else {
                mEndValues.itemIdValues.put(id, values);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); ++i) {
                captureHierarchy(parent.getChildAt(i), start);
            }
        }
    }

    /**
     * This method can be called by transitions to get the TransitionValues for
     * any particular view during the transition-playing process. This might be
     * necessary, for example, to query the before/after state of related views
     * for a given transition.
     */
    protected TransitionValues getTransitionValues(View view, boolean start) {
        if (mParent != null) {
            return mParent.getTransitionValues(view, start);
        }
        TransitionValuesMaps valuesMaps = start ? mStartValues : mEndValues;
        TransitionValues values = valuesMaps.viewValues.get(view);
        if (values == null) {
            int id = view.getId();
            if (id >= 0) {
                values = valuesMaps.idValues.get(id);
            }
            if (values == null && view.getParent() instanceof ListView) {
                ListView listview = (ListView) view.getParent();
                int position = listview.getPositionForView(view);
                long itemId = listview.getItemIdAtPosition(position);
                values = valuesMaps.itemIdValues.get(itemId);
            }
            // TODO: Doesn't handle the case where a view was parented to a
            // ListView (with an itemId), but no longer is
        }
        return values;
    }

    /**
     * Pauses this transition, sending out calls to {@link
     * TransitionListener#onTransitionPause(Transition)} to all listeners
     * and pausing all running animators started by this transition.
     *
     * @hide
     */
    public void pause() {
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        int numOldAnims = runningAnimators.size();
        for (int i = numOldAnims - 1; i >= 0; i--) {
            Animator anim = runningAnimators.keyAt(i);
            anim.pause();
        }
        if (mListeners != null && mListeners.size() > 0) {
            ArrayList<TransitionListener> tmpListeners =
                    (ArrayList<TransitionListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onTransitionPause(this);
            }
        }
        mPaused = true;
    }

    /**
     * Resumes this transition, sending out calls to {@link
     * TransitionListener#onTransitionPause(Transition)} to all listeners
     * and pausing all running animators started by this transition.
     *
     * @hide
     */
    public void resume() {
        if (mPaused) {
            ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
            int numOldAnims = runningAnimators.size();
            for (int i = numOldAnims - 1; i >= 0; i--) {
                Animator anim = runningAnimators.keyAt(i);
                anim.resume();
            }
            if (mListeners != null && mListeners.size() > 0) {
                ArrayList<TransitionListener> tmpListeners =
                        (ArrayList<TransitionListener>) mListeners.clone();
                int numListeners = tmpListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    tmpListeners.get(i).onTransitionResume(this);
                }
            }
            mPaused = false;
        }
    }

    /**
     * Called by TransitionManager to play the transition. This calls
     * play() to set things up and create all of the animations and then
     * runAnimations() to actually start the animations.
     */
    void playTransition(ViewGroup sceneRoot) {
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        int numOldAnims = runningAnimators.size();
        for (int i = numOldAnims - 1; i >= 0; i--) {
            Animator anim = runningAnimators.keyAt(i);
            if (anim != null) {
                anim.resume();
                AnimationInfo oldInfo = runningAnimators.get(anim);
                if (oldInfo != null) {
                    boolean cancel = false;
                    TransitionValues oldValues = oldInfo.values;
                    View oldView = oldInfo.view;
                    TransitionValues newValues = mEndValues.viewValues != null ?
                            mEndValues.viewValues.get(oldView) : null;
                    if (oldValues == null || newValues == null) {
                        if (oldValues != null || newValues != null) {
                            cancel = true;
                        }
                    } else {
                        for (String key : oldValues.values.keySet()) {
                            Object oldValue = oldValues.values.get(key);
                            Object newValue = newValues.values.get(key);
                            if ((oldValue == null && newValue != null) ||
                                    (oldValue != null && !oldValue.equals(newValue))) {
                                cancel = true;
                                if (DBG) {
                                    Log.d(LOG_TAG, "Transition.play: oldValue != newValue for " +
                                            key + ": old, new = " + oldValue + ", " + newValue);
                                }
                                break;
                            }
                        }
                    }
                    if (cancel) {
                        if (anim.isRunning() || anim.isStarted()) {
                            if (DBG) {
                                Log.d(LOG_TAG, "Canceling anim " + anim);
                            }
                            anim.cancel();
                        } else {
                            if (DBG) {
                                Log.d(LOG_TAG, "removing anim from info list: " + anim);
                            }
                            runningAnimators.remove(anim);
                        }
                    }
                }
            }
        }

        // setup() must be called on entire transition hierarchy and set of views
        // before calling play() on anything; every transition needs a chance to set up
        // target views appropriately before transitions begin running
        play(sceneRoot, mStartValues, mEndValues);
        runAnimations();
    }

    /**
     * This is a utility method used by subclasses to handle standard parts of
     * setting up and running an Animator: it sets the {@link #getDuration()
     * duration} and the {@link #getStartDelay() startDelay}, starts the
     * animation, and, when the animator ends, calls {@link #end()}.
     *
     * @param animator The Animator to be run during this transition.
     *
     * @hide
     */
    protected void animate(Animator animator) {
        // TODO: maybe pass auto-end as a boolean parameter?
        if (animator == null) {
            end();
        } else {
            if (getDuration() >= 0) {
                animator.setDuration(getDuration());
            }
            if (getStartDelay() >= 0) {
                animator.setStartDelay(getStartDelay());
            }
            if (getInterpolator() != null) {
                animator.setInterpolator(getInterpolator());
            }
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    end();
                    animation.removeListener(this);
                }
            });
            animator.start();
        }
    }

    /**
     * This method is called automatically by the transition and
     * TransitionGroup classes prior to a Transition subclass starting;
     * subclasses should not need to call it directly.
     *
     * @hide
     */
    protected void start() {
        if (mNumInstances == 0) {
            if (mListeners != null && mListeners.size() > 0) {
                ArrayList<TransitionListener> tmpListeners =
                        (ArrayList<TransitionListener>) mListeners.clone();
                int numListeners = tmpListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    tmpListeners.get(i).onTransitionStart(this);
                }
            }
        }
        mNumInstances++;
    }

    /**
     * This method is called automatically by the Transition and
     * TransitionGroup classes when a transition finishes, either because
     * a transition did nothing (returned a null Animator from
     * {@link Transition#play(ViewGroup, TransitionValues,
     * TransitionValues)}) or because the transition returned a valid
     * Animator and end() was called in the onAnimationEnd()
     * callback of the AnimatorListener.
     *
     * @hide
     */
    protected void end() {
        --mNumInstances;
        if (mNumInstances == 0) {
            if (mListeners != null && mListeners.size() > 0) {
                ArrayList<TransitionListener> tmpListeners =
                        (ArrayList<TransitionListener>) mListeners.clone();
                int numListeners = tmpListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    tmpListeners.get(i).onTransitionEnd(this);
                }
            }
            for (int i = 0; i < mStartValues.itemIdValues.size(); ++i) {
                TransitionValues tv = mStartValues.itemIdValues.valueAt(i);
                View v = tv.view;
                if (v.hasTransientState()) {
                    v.setHasTransientState(false);
                }
            }
            for (int i = 0; i < mEndValues.itemIdValues.size(); ++i) {
                TransitionValues tv = mEndValues.itemIdValues.valueAt(i);
                View v = tv.view;
                if (v.hasTransientState()) {
                    v.setHasTransientState(false);
                }
            }
        }
    }

    /**
     * This method cancels a transition that is currently running.
     * Implementation TBD.
     */
    protected void cancel() {
        // TODO: how does this work with instances?
        // TODO: this doesn't actually do *anything* yet
        int numAnimators = mCurrentAnimators.size();
        for (int i = numAnimators - 1; i >= 0; i--) {
            Animator animator = mCurrentAnimators.get(i);
            animator.cancel();
        }
        if (mListeners != null && mListeners.size() > 0) {
            ArrayList<TransitionListener> tmpListeners =
                    (ArrayList<TransitionListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onTransitionCancel(this);
            }
        }
    }

    /**
     * Adds a listener to the set of listeners that are sent events through the
     * life of an animation, such as start, repeat, and end.
     *
     * @param listener the listener to be added to the current set of listeners
     * for this animation.
     */
    public void addListener(TransitionListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<TransitionListener>();
        }
        mListeners.add(listener);
    }

    /**
     * Removes a listener from the set listening to this animation.
     *
     * @param listener the listener to be removed from the current set of
     * listeners for this transition.
     */
    public void removeListener(TransitionListener listener) {
        if (mListeners == null) {
            return;
        }
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            mListeners = null;
        }
    }

    /**
     * Gets the set of {@link TransitionListener} objects that are currently
     * listening for events on this <code>Transition</code> object.
     *
     * @return ArrayList<TransitionListener> The set of listeners.
     */
    public ArrayList<TransitionListener> getListeners() {
        return mListeners;
    }

    void setSceneRoot(ViewGroup sceneRoot) {
        mSceneRoot = sceneRoot;
    }

    @Override
    public String toString() {
        return toString("");
    }

    @Override
    public Transition clone() {
        Transition clone = null;
        try {
            clone = (Transition) super.clone();
            clone.mAnimators = new ArrayList<Animator>();
        } catch (CloneNotSupportedException e) {}

        return clone;
    }

    /**
     * Returns the name of this Transition. This name is used internally to distinguish
     * between different transitions to determine when interrupting transitions overlap.
     * For example, a Move running on the same target view as another Move should determine
     * whether the old transition is animating to different end values and should be
     * canceled in favor of the new transition.
     *
     * <p>By default, a Transition's name is simply the value of {@link Class#getName()},
     * but subclasses are free to override and return something different.</p>
     *
     * @return The name of this transition.
     */
    public String getName() {
        return mName;
    }

    String toString(String indent) {
        String result = indent + getClass().getSimpleName() + "@" +
                Integer.toHexString(hashCode()) + ": ";
        if (mDuration != -1) {
            result += "dur(" + mDuration + ") ";
        }
        if (mStartDelay != -1) {
            result += "dly(" + mStartDelay + ") ";
        }
        if (mInterpolator != null) {
            result += "interp(" + mInterpolator + ") ";
        }
        if (mTargetIds != null || mTargets != null) {
            result += "tgts(";
            if (mTargetIds != null) {
                for (int i = 0; i < mTargetIds.length; ++i) {
                    if (i > 0) {
                        result += ", ";
                    }
                    result += mTargetIds[i];
                }
            }
            if (mTargets != null) {
                for (int i = 0; i < mTargets.length; ++i) {
                    if (i > 0) {
                        result += ", ";
                    }
                    result += mTargets[i];
                }
            }
            result += ")";
        }
        return result;
    }

    /**
     * A transition listener receives notifications from a transition.
     * Notifications indicate transition lifecycle events.
     */
    public static interface TransitionListener {
        /**
         * Notification about the start of the transition.
         *
         * @param transition The started transition.
         */
        void onTransitionStart(Transition transition);

        /**
         * Notification about the end of the transition. Canceled transitions
         * will always notify listeners of both the cancellation and end
         * events. That is, {@link #onTransitionEnd(Transition)} is always called,
         * regardless of whether the transition was canceled or played
         * through to completion.
         *
         * @param transition The transition which reached its end.
         */
        void onTransitionEnd(Transition transition);

        /**
         * Notification about the cancellation of the transition.
         * Note that cancel() may be called by a parent {@link TransitionGroup} on
         * a child transition which has not yet started. This allows the child
         * transition to restore state on target objects which was set at
         * {@link #play(android.view.ViewGroup, TransitionValues, TransitionValues)
         * play()} time.
         *
         * @param transition The transition which was canceled.
         */
        void onTransitionCancel(Transition transition);

        /**
         * Notification when a transition is paused.
         * Note that play() may be called by a parent {@link TransitionGroup} on
         * a child transition which has not yet started. This allows the child
         * transition to restore state on target objects which was set at
         * {@link #play(android.view.ViewGroup, TransitionValues, TransitionValues)
         * play()} time.
         *
         * @param transition The transition which was paused.
         */
        void onTransitionPause(Transition transition);

        /**
         * Notification when a transition is resumed.
         * Note that resume() may be called by a parent {@link TransitionGroup} on
         * a child transition which has not yet started. This allows the child
         * transition to restore state which may have changed in an earlier call
         * to {@link #onTransitionPause(Transition)}.
         *
         * @param transition The transition which was resumed.
         */
        void onTransitionResume(Transition transition);
    }

    /**
     * Utility adapter class to avoid having to override all three methods
     * whenever someone just wants to listen for a single event.
     *
     * @hide
     * */
    public static class TransitionListenerAdapter implements TransitionListener {
        @Override
        public void onTransitionStart(Transition transition) {
        }

        @Override
        public void onTransitionEnd(Transition transition) {
        }

        @Override
        public void onTransitionCancel(Transition transition) {
        }

        @Override
        public void onTransitionPause(Transition transition) {
        }

        @Override
        public void onTransitionResume(Transition transition) {
        }
    }

    /**
     * Holds information about each animator used when a new transition starts
     * while other transitions are still running to determine whether a running
     * animation should be canceled or a new animation noop'd. The structure holds
     * information about the state that an animation is going to, to be compared to
     * end state of a new animation.
     */
    private static class AnimationInfo {
        View view;
        String name;
        TransitionValues values;

        AnimationInfo(View view, String name, TransitionValues values) {
            this.view = view;
            this.name = name;
            this.values = values;
        }
    }
}
