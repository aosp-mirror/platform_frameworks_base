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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.view.InflateException;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOverlay;
import android.view.WindowId;
import android.view.animation.AnimationUtils;
import android.widget.ListView;
import android.widget.Spinner;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * A Transition holds information about animations that will be run on its
 * targets during a scene change. Subclasses of this abstract class may
 * choreograph several child transitions ({@link TransitionSet} or they may
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
 * specific transitions (such as {@link Fade}) may not be compatible
 * with TextureView because they rely on {@link ViewOverlay} functionality,
 * which does not currently work with TextureView.</p>
 *
 * <p>Transitions can be declared in XML resource files inside the <code>res/transition</code>
 * directory. Transition resources consist of a tag name for one of the Transition
 * subclasses along with attributes to define some of the attributes of that transition.
 * For example, here is a minimal resource file that declares a {@link ChangeBounds} transition:
 *
 * {@sample development/samples/ApiDemos/res/transition/changebounds.xml ChangeBounds}
 *
 * <p>This TransitionSet contains {@link android.transition.Explode} for visibility,
 * {@link android.transition.ChangeBounds}, {@link android.transition.ChangeTransform},
 * and {@link android.transition.ChangeClipBounds} and
 * {@link android.transition.ChangeImageTransform}:</p>
 *
 * {@sample development/samples/ApiDemos/res/transition/explode_move_together.xml MultipleTransform}
 *
 * <p>Custom transition classes may be instantiated with a <code>transition</code> tag:</p>
 * <pre>&lt;transition class="my.app.transition.CustomTransition"/></pre>
 * <p>Custom transition classes loaded from XML should have a public constructor taking
 * a {@link android.content.Context} and {@link android.util.AttributeSet}.</p>
 *
 * <p>Note that attributes for the transition are not required, just as they are
 * optional when declared in code; Transitions created from XML resources will use
 * the same defaults as their code-created equivalents. Here is a slightly more
 * elaborate example which declares a {@link TransitionSet} transition with
 * {@link ChangeBounds} and {@link Fade} child transitions:</p>
 *
 * {@sample
 * development/samples/ApiDemos/res/transition/changebounds_fadeout_sequential.xml TransitionSet}
 *
 * <p>In this example, the transitionOrdering attribute is used on the TransitionSet
 * object to change from the default {@link TransitionSet#ORDERING_TOGETHER} behavior
 * to be {@link TransitionSet#ORDERING_SEQUENTIAL} instead. Also, the {@link Fade}
 * transition uses a fadingMode of {@link Fade#OUT} instead of the default
 * out-in behavior. Finally, note the use of the <code>targets</code> sub-tag, which
 * takes a set of {@link android.R.styleable#TransitionTarget target} tags, each
 * of which lists a specific <code>targetId</code>, <code>targetClass</code>,
 * <code>targetName</code>, <code>excludeId</code>, <code>excludeClass</code>, or
 * <code>excludeName</code>, which this transition acts upon.
 * Use of targets is optional, but can be used to either limit the time spent checking
 * attributes on unchanging views, or limiting the types of animations run on specific views.
 * In this case, we know that only the <code>grayscaleContainer</code> will be
 * disappearing, so we choose to limit the {@link Fade} transition to only that view.</p>
 *
 * Further information on XML resource descriptions for transitions can be found for
 * {@link android.R.styleable#Transition}, {@link android.R.styleable#TransitionSet},
 * {@link android.R.styleable#TransitionTarget}, {@link android.R.styleable#Fade},
 * {@link android.R.styleable#Slide}, and {@link android.R.styleable#ChangeTransform}.
 *
 */
public abstract class Transition implements Cloneable {

    private static final String LOG_TAG = "Transition";
    static final boolean DBG = false;

    /**
     * With {@link #setMatchOrder(int...)}, chooses to match by View instance.
     */
    public static final int MATCH_INSTANCE = 0x1;
    private static final int MATCH_FIRST = MATCH_INSTANCE;

    /**
     * With {@link #setMatchOrder(int...)}, chooses to match by
     * {@link android.view.View#getTransitionName()}. Null names will not be matched.
     */
    public static final int MATCH_NAME = 0x2;

    /**
     * With {@link #setMatchOrder(int...)}, chooses to match by
     * {@link android.view.View#getId()}. Negative IDs will not be matched.
     */
    public static final int MATCH_ID = 0x3;

    /**
     * With {@link #setMatchOrder(int...)}, chooses to match by the {@link android.widget.Adapter}
     * item id. When {@link android.widget.Adapter#hasStableIds()} returns false, no match
     * will be made for items.
     */
    public static final int MATCH_ITEM_ID = 0x4;

    private static final int MATCH_LAST = MATCH_ITEM_ID;

    private static final String MATCH_INSTANCE_STR = "instance";
    private static final String MATCH_NAME_STR = "name";
    /** To be removed before L release */
    private static final String MATCH_VIEW_NAME_STR = "viewName";
    private static final String MATCH_ID_STR = "id";
    private static final String MATCH_ITEM_ID_STR = "itemId";

    private static final int[] DEFAULT_MATCH_ORDER = {
        MATCH_NAME,
        MATCH_INSTANCE,
        MATCH_ID,
        MATCH_ITEM_ID,
    };

    private static final PathMotion STRAIGHT_PATH_MOTION = new PathMotion() {
        @Override
        public Path getPath(float startX, float startY, float endX, float endY) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            return path;
        }
    };

    private String mName = getClass().getName();

    long mStartDelay = -1;
    long mDuration = -1;
    TimeInterpolator mInterpolator = null;
    ArrayList<Integer> mTargetIds = new ArrayList<Integer>();
    ArrayList<View> mTargets = new ArrayList<View>();
    ArrayList<String> mTargetNames = null;
    ArrayList<Class> mTargetTypes = null;
    ArrayList<Integer> mTargetIdExcludes = null;
    ArrayList<View> mTargetExcludes = null;
    ArrayList<Class> mTargetTypeExcludes = null;
    ArrayList<String> mTargetNameExcludes = null;
    ArrayList<Integer> mTargetIdChildExcludes = null;
    ArrayList<View> mTargetChildExcludes = null;
    ArrayList<Class> mTargetTypeChildExcludes = null;
    private TransitionValuesMaps mStartValues = new TransitionValuesMaps();
    private TransitionValuesMaps mEndValues = new TransitionValuesMaps();
    TransitionSet mParent = null;
    int[] mMatchOrder = DEFAULT_MATCH_ORDER;
    ArrayList<TransitionValues> mStartValuesList; // only valid after playTransition starts
    ArrayList<TransitionValues> mEndValuesList; // only valid after playTransitions starts

    // Per-animator information used for later canceling when future transitions overlap
    private static ThreadLocal<ArrayMap<Animator, AnimationInfo>> sRunningAnimators =
            new ThreadLocal<ArrayMap<Animator, AnimationInfo>>();

    // Scene Root is set at createAnimator() time in the cloned Transition
    ViewGroup mSceneRoot = null;

    // Whether removing views from their parent is possible. This is only for views
    // in the start scene, which are no longer in the view hierarchy. This property
    // is determined by whether the previous Scene was created from a layout
    // resource, and thus the views from the exited scene are going away anyway
    // and can be removed as necessary to achieve a particular effect, such as
    // removing them from parents to add them to overlays.
    boolean mCanRemoveViews = false;

    // Track all animators in use in case the transition gets canceled and needs to
    // cancel running animators
    private ArrayList<Animator> mCurrentAnimators = new ArrayList<Animator>();

    // Number of per-target instances of this Transition currently running. This count is
    // determined by calls to start() and end()
    int mNumInstances = 0;

    // Whether this transition is currently paused, due to a call to pause()
    boolean mPaused = false;

    // Whether this transition has ended. Used to avoid pause/resume on transitions
    // that have completed
    private boolean mEnded = false;

    // The set of listeners to be sent transition lifecycle events.
    ArrayList<TransitionListener> mListeners = null;

    // The set of animators collected from calls to createAnimator(),
    // to be run in runAnimators()
    ArrayList<Animator> mAnimators = new ArrayList<Animator>();

    // The function for calculating the Animation start delay.
    TransitionPropagation mPropagation;

    // The rectangular region for Transitions like Explode and TransitionPropagations
    // like CircularPropagation
    EpicenterCallback mEpicenterCallback;

    // For Fragment shared element transitions, linking views explicitly by mismatching
    // transitionNames.
    ArrayMap<String, String> mNameOverrides;

    // The function used to interpolate along two-dimensional points. Typically used
    // for adding curves to x/y View motion.
    PathMotion mPathMotion = STRAIGHT_PATH_MOTION;

    /**
     * Constructs a Transition object with no target objects. A transition with
     * no targets defaults to running on all target objects in the scene hierarchy
     * (if the transition is not contained in a TransitionSet), or all target
     * objects passed down from its parent (if it is in a TransitionSet).
     */
    public Transition() {}

    /**
     * Perform inflation from XML and apply a class-specific base style from a
     * theme attribute or style resource. This constructor of Transition allows
     * subclasses to use their own base style when they are inflating.
     *
     * @param context The Context the transition is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the transition.
     */
    public Transition(Context context, AttributeSet attrs) {

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Transition);
        long duration = a.getInt(R.styleable.Transition_duration, -1);
        if (duration >= 0) {
            setDuration(duration);
        }
        long startDelay = a.getInt(R.styleable.Transition_startDelay, -1);
        if (startDelay > 0) {
            setStartDelay(startDelay);
        }
        final int resID = a.getResourceId(com.android.internal.R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            setInterpolator(AnimationUtils.loadInterpolator(context, resID));
        }
        String matchOrder = a.getString(R.styleable.Transition_matchOrder);
        if (matchOrder != null) {
            setMatchOrder(parseMatchOrder(matchOrder));
        }
        a.recycle();
    }

    private static int[] parseMatchOrder(String matchOrderString) {
        StringTokenizer st = new StringTokenizer(matchOrderString, ",");
        int matches[] = new int[st.countTokens()];
        int index = 0;
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            if (MATCH_ID_STR.equalsIgnoreCase(token)) {
                matches[index] = Transition.MATCH_ID;
            } else if (MATCH_INSTANCE_STR.equalsIgnoreCase(token)) {
                matches[index] = Transition.MATCH_INSTANCE;
            } else if (MATCH_NAME_STR.equalsIgnoreCase(token)) {
                matches[index] = Transition.MATCH_NAME;
            } else if (MATCH_VIEW_NAME_STR.equalsIgnoreCase(token)) {
                matches[index] = Transition.MATCH_NAME;
            } else if (MATCH_ITEM_ID_STR.equalsIgnoreCase(token)) {
                matches[index] = Transition.MATCH_ITEM_ID;
            } else if (token.isEmpty()) {
                int[] smallerMatches = new int[matches.length - 1];
                System.arraycopy(matches, 0, smallerMatches, 0, index);
                matches = smallerMatches;
                index--;
            } else {
                throw new InflateException("Unknown match type in matchOrder: '" + token + "'");
            }
            index++;
        }
        return matches;
    }

    /**
     * Sets the duration of this transition. By default, there is no duration
     * (indicated by a negative number), which means that the Animator created by
     * the transition will have its own specified duration. If the duration of a
     * Transition is set, that duration will override the Animator duration.
     *
     * @param duration The length of the animation, in milliseconds.
     * @return This transition object.
     * @attr ref android.R.styleable#Transition_duration
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
     * @return The duration set on this transition, in milliseconds, if one has been
     * set, otherwise returns a negative number.
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
     * @return This transition object.
     * @attr ref android.R.styleable#Transition_startDelay
     */
    public Transition setStartDelay(long startDelay) {
        mStartDelay = startDelay;
        return this;
    }

    /**
     * Returns the startDelay set on this transition. If no startDelay has been set,
     * the returned value will be negative, indicating that resulting animators will
     * retain their own startDelays.
     *
     * @return The startDelay set on this transition, in milliseconds, if one has
     * been set, otherwise returns a negative number.
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
     * @return This transition object.
     * @attr ref android.R.styleable#Transition_interpolator
     */
    public Transition setInterpolator(TimeInterpolator interpolator) {
        mInterpolator = interpolator;
        return this;
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
     * object passed into {@link #captureStartValues(TransitionValues)} that
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
     * This method creates an animation that will be run for this transition
     * given the information in the startValues and endValues structures captured
     * earlier for the start and end scenes. Subclasses of Transition should override
     * this method. The method should only be called by the transition system; it is
     * not intended to be called from external classes.
     *
     * <p>This method is called by the transition's parent (all the way up to the
     * topmost Transition in the hierarchy) with the sceneRoot and start/end
     * values that the transition may need to set up initial target values
     * and construct an appropriate animation. For example, if an overall
     * Transition is a {@link TransitionSet} consisting of several
     * child transitions in sequence, then some of the child transitions may
     * want to set initial values on target views prior to the overall
     * Transition commencing, to put them in an appropriate state for the
     * delay between that start and the child Transition start time. For
     * example, a transition that fades an item in may wish to set the starting
     * alpha value to 0, to avoid it blinking in prior to the transition
     * actually starting the animation. This is necessary because the scene
     * change that triggers the Transition will automatically set the end-scene
     * on all target views, so a Transition that wants to animate from a
     * different value should set that value prior to returning from this method.</p>
     *
     * <p>Additionally, a Transition can perform logic to determine whether
     * the transition needs to run on the given target and start/end values.
     * For example, a transition that resizes objects on the screen may wish
     * to avoid running for views which are not present in either the start
     * or end scenes.</p>
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
     *
     * @param sceneRoot The root of the transition hierarchy.
     * @param startValues The values for a specific target in the start scene.
     * @param endValues The values for the target in the end scene.
     * @return A Animator to be started at the appropriate time in the
     * overall transition for this scene change. A null value means no animation
     * should be run.
     */
    public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        return null;
    }

    /**
     * Sets the order in which Transition matches View start and end values.
     * <p>
     * The default behavior is to match first by {@link android.view.View#getTransitionName()},
     * then by View instance, then by {@link android.view.View#getId()} and finally
     * by its item ID if it is in a direct child of ListView. The caller can
     * choose to have only some or all of the values of {@link #MATCH_INSTANCE},
     * {@link #MATCH_NAME}, {@link #MATCH_ITEM_ID}, and {@link #MATCH_ID}. Only
     * the match algorithms supplied will be used to determine whether Views are the
     * the same in both the start and end Scene. Views that do not match will be considered
     * as entering or leaving the Scene.
     * </p>
     * @param matches A list of zero or more of {@link #MATCH_INSTANCE},
     *                {@link #MATCH_NAME}, {@link #MATCH_ITEM_ID}, and {@link #MATCH_ID}.
     *                If none are provided, then the default match order will be set.
     */
    public void setMatchOrder(int... matches) {
        if (matches == null || matches.length == 0) {
            mMatchOrder = DEFAULT_MATCH_ORDER;
        } else {
            for (int i = 0; i < matches.length; i++) {
                int match = matches[i];
                if (!isValidMatch(match)) {
                    throw new IllegalArgumentException("matches contains invalid value");
                }
                if (alreadyContains(matches, i)) {
                    throw new IllegalArgumentException("matches contains a duplicate value");
                }
            }
            mMatchOrder = matches.clone();
        }
    }

    private static boolean isValidMatch(int match) {
        return (match >= MATCH_FIRST && match <= MATCH_LAST);
    }

    private static boolean alreadyContains(int[] array, int searchIndex) {
        int value = array[searchIndex];
        for (int i = 0; i < searchIndex; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match start/end values by View instance. Adds matched values to mStartValuesList
     * and mEndValuesList and removes them from unmatchedStart and unmatchedEnd.
     */
    private void matchInstances(ArrayMap<View, TransitionValues> unmatchedStart,
            ArrayMap<View, TransitionValues> unmatchedEnd) {
        for (int i = unmatchedStart.size() - 1; i >= 0; i--) {
            View view = unmatchedStart.keyAt(i);
            if (view != null && isValidTarget(view)) {
                TransitionValues end = unmatchedEnd.remove(view);
                if (end != null && end.view != null && isValidTarget(end.view)) {
                    TransitionValues start = unmatchedStart.removeAt(i);
                    mStartValuesList.add(start);
                    mEndValuesList.add(end);
                }
            }
        }
    }

    /**
     * Match start/end values by Adapter item ID. Adds matched values to mStartValuesList
     * and mEndValuesList and removes them from unmatchedStart and unmatchedEnd, using
     * startItemIds and endItemIds as a guide for which Views have unique item IDs.
     */
    private void matchItemIds(ArrayMap<View, TransitionValues> unmatchedStart,
            ArrayMap<View, TransitionValues> unmatchedEnd,
            LongSparseArray<View> startItemIds, LongSparseArray<View> endItemIds) {
        int numStartIds = startItemIds.size();
        for (int i = 0; i < numStartIds; i++) {
            View startView = startItemIds.valueAt(i);
            if (startView != null && isValidTarget(startView)) {
                View endView = endItemIds.get(startItemIds.keyAt(i));
                if (endView != null && isValidTarget(endView)) {
                    TransitionValues startValues = unmatchedStart.get(startView);
                    TransitionValues endValues = unmatchedEnd.get(endView);
                    if (startValues != null && endValues != null) {
                        mStartValuesList.add(startValues);
                        mEndValuesList.add(endValues);
                        unmatchedStart.remove(startView);
                        unmatchedEnd.remove(endView);
                    }
                }
            }
        }
    }

    /**
     * Match start/end values by Adapter view ID. Adds matched values to mStartValuesList
     * and mEndValuesList and removes them from unmatchedStart and unmatchedEnd, using
     * startIds and endIds as a guide for which Views have unique IDs.
     */
    private void matchIds(ArrayMap<View, TransitionValues> unmatchedStart,
            ArrayMap<View, TransitionValues> unmatchedEnd,
            SparseArray<View> startIds, SparseArray<View> endIds) {
        int numStartIds = startIds.size();
        for (int i = 0; i < numStartIds; i++) {
            View startView = startIds.valueAt(i);
            if (startView != null && isValidTarget(startView)) {
                View endView = endIds.get(startIds.keyAt(i));
                if (endView != null && isValidTarget(endView)) {
                    TransitionValues startValues = unmatchedStart.get(startView);
                    TransitionValues endValues = unmatchedEnd.get(endView);
                    if (startValues != null && endValues != null) {
                        mStartValuesList.add(startValues);
                        mEndValuesList.add(endValues);
                        unmatchedStart.remove(startView);
                        unmatchedEnd.remove(endView);
                    }
                }
            }
        }
    }

    /**
     * Match start/end values by Adapter transitionName. Adds matched values to mStartValuesList
     * and mEndValuesList and removes them from unmatchedStart and unmatchedEnd, using
     * startNames and endNames as a guide for which Views have unique transitionNames.
     */
    private void matchNames(ArrayMap<View, TransitionValues> unmatchedStart,
            ArrayMap<View, TransitionValues> unmatchedEnd,
            ArrayMap<String, View> startNames, ArrayMap<String, View> endNames) {
        int numStartNames = startNames.size();
        for (int i = 0; i < numStartNames; i++) {
            View startView = startNames.valueAt(i);
            if (startView != null && isValidTarget(startView)) {
                View endView = endNames.get(startNames.keyAt(i));
                if (endView != null && isValidTarget(endView)) {
                    TransitionValues startValues = unmatchedStart.get(startView);
                    TransitionValues endValues = unmatchedEnd.get(endView);
                    if (startValues != null && endValues != null) {
                        mStartValuesList.add(startValues);
                        mEndValuesList.add(endValues);
                        unmatchedStart.remove(startView);
                        unmatchedEnd.remove(endView);
                    }
                }
            }
        }
    }

    /**
     * Adds all values from unmatchedStart and unmatchedEnd to mStartValuesList and mEndValuesList,
     * assuming that there is no match between values in the list.
     */
    private void addUnmatched(ArrayMap<View, TransitionValues> unmatchedStart,
            ArrayMap<View, TransitionValues> unmatchedEnd) {
        // Views that only exist in the start Scene
        for (int i = 0; i < unmatchedStart.size(); i++) {
            final TransitionValues start = unmatchedStart.valueAt(i);
            if (isValidTarget(start.view)) {
                mStartValuesList.add(start);
                mEndValuesList.add(null);
            }
        }

        // Views that only exist in the end Scene
        for (int i = 0; i < unmatchedEnd.size(); i++) {
            final TransitionValues end = unmatchedEnd.valueAt(i);
            if (isValidTarget(end.view)) {
                mEndValuesList.add(end);
                mStartValuesList.add(null);
            }
        }
    }

    private void matchStartAndEnd(TransitionValuesMaps startValues,
            TransitionValuesMaps endValues) {
        ArrayMap<View, TransitionValues> unmatchedStart =
                new ArrayMap<View, TransitionValues>(startValues.viewValues);
        ArrayMap<View, TransitionValues> unmatchedEnd =
                new ArrayMap<View, TransitionValues>(endValues.viewValues);

        for (int i = 0; i < mMatchOrder.length; i++) {
            switch (mMatchOrder[i]) {
                case MATCH_INSTANCE:
                    matchInstances(unmatchedStart, unmatchedEnd);
                    break;
                case MATCH_NAME:
                    matchNames(unmatchedStart, unmatchedEnd,
                            startValues.nameValues, endValues.nameValues);
                    break;
                case MATCH_ID:
                    matchIds(unmatchedStart, unmatchedEnd,
                            startValues.idValues, endValues.idValues);
                    break;
                case MATCH_ITEM_ID:
                    matchItemIds(unmatchedStart, unmatchedEnd,
                            startValues.itemIdValues, endValues.itemIdValues);
                    break;
            }
        }
        addUnmatched(unmatchedStart, unmatchedEnd);
    }

    /**
     * This method, essentially a wrapper around all calls to createAnimator for all
     * possible target views, is called with the entire set of start/end
     * values. The implementation in Transition iterates through these lists
     * and calls {@link #createAnimator(ViewGroup, TransitionValues, TransitionValues)}
     * with each set of start/end values on this transition. The
     * TransitionSet subclass overrides this method and delegates it to
     * each of its children in succession.
     *
     * @hide
     */
    protected void createAnimators(ViewGroup sceneRoot, TransitionValuesMaps startValues,
            TransitionValuesMaps endValues, ArrayList<TransitionValues> startValuesList,
            ArrayList<TransitionValues> endValuesList) {
        if (DBG) {
            Log.d(LOG_TAG, "createAnimators() for " + this);
        }
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        long minStartDelay = Long.MAX_VALUE;
        int minAnimator = mAnimators.size();
        SparseLongArray startDelays = new SparseLongArray();
        int startValuesListCount = startValuesList.size();
        for (int i = 0; i < startValuesListCount; ++i) {
            TransitionValues start = startValuesList.get(i);
            TransitionValues end = endValuesList.get(i);
            if (start != null && !start.targetedTransitions.contains(this)) {
                start = null;
            }
            if (end != null && !end.targetedTransitions.contains(this)) {
                end = null;
            }
            if (start == null && end == null) {
                continue;
            }
            // Only bother trying to animate with values that differ between start/end
            boolean isChanged = start == null || end == null || isTransitionRequired(start, end);
            if (isChanged) {
                if (DBG) {
                    View view = (end != null) ? end.view : start.view;
                    Log.d(LOG_TAG, "  differing start/end values for view " + view);
                    if (start == null || end == null) {
                        Log.d(LOG_TAG, "    " + ((start == null) ?
                                "start null, end non-null" : "start non-null, end null"));
                    } else {
                        for (String key : start.values.keySet()) {
                            Object startValue = start.values.get(key);
                            Object endValue = end.values.get(key);
                            if (startValue != endValue && !startValue.equals(endValue)) {
                                Log.d(LOG_TAG, "    " + key + ": start(" + startValue +
                                        "), end(" + endValue + ")");
                            }
                        }
                    }
                }
                // TODO: what to do about targetIds and itemIds?
                Animator animator = createAnimator(sceneRoot, start, end);
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
                        if (mPropagation != null) {
                            long delay = mPropagation
                                    .getStartDelay(sceneRoot, this, start, end);
                            startDelays.put(mAnimators.size(), delay);
                            minStartDelay = Math.min(delay, minStartDelay);
                        }
                        AnimationInfo info = new AnimationInfo(view, getName(), this,
                                sceneRoot.getWindowId(), infoValues);
                        runningAnimators.put(animator, info);
                        mAnimators.add(animator);
                    }
                }
            }
        }
        if (startDelays.size() != 0) {
            for (int i = 0; i < startDelays.size(); i++) {
                int index = startDelays.keyAt(i);
                Animator animator = mAnimators.get(index);
                long delay = startDelays.valueAt(i) - minStartDelay + animator.getStartDelay();
                animator.setStartDelay(delay);
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
    boolean isValidTarget(View target) {
        if (target == null) {
            return false;
        }
        int targetId = target.getId();
        if (mTargetIdExcludes != null && mTargetIdExcludes.contains(targetId)) {
            return false;
        }
        if (mTargetExcludes != null && mTargetExcludes.contains(target)) {
            return false;
        }
        if (mTargetTypeExcludes != null && target != null) {
            int numTypes = mTargetTypeExcludes.size();
            for (int i = 0; i < numTypes; ++i) {
                Class type = mTargetTypeExcludes.get(i);
                if (type.isInstance(target)) {
                    return false;
                }
            }
        }
        if (mTargetNameExcludes != null && target != null && target.getTransitionName() != null) {
            if (mTargetNameExcludes.contains(target.getTransitionName())) {
                return false;
            }
        }
        if (mTargetIds.size() == 0 && mTargets.size() == 0 &&
                (mTargetTypes == null || mTargetTypes.isEmpty()) &&
                (mTargetNames == null || mTargetNames.isEmpty())) {
            return true;
        }
        if (mTargetIds.contains(targetId) || mTargets.contains(target)) {
            return true;
        }
        if (mTargetNames != null && mTargetNames.contains(target.getTransitionName())) {
            return true;
        }
        if (mTargetTypes != null) {
            for (int i = 0; i < mTargetTypes.size(); ++i) {
                if (mTargetTypes.get(i).isInstance(target)) {
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
     * transition hierarchy.
     *
     * @hide
     */
    protected void runAnimators() {
        if (DBG) {
            Log.d(LOG_TAG, "runAnimators() on " + this);
        }
        start();
        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        // Now start every Animator that was previously created for this transition
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
     * Captures the values in the start scene for the properties that this
     * transition monitors. These values are then passed as the startValues
     * structure in a later call to
     * {@link #createAnimator(ViewGroup, TransitionValues, TransitionValues)}.
     * The main concern for an implementation is what the
     * properties are that the transition cares about and what the values are
     * for all of those properties. The start and end values will be compared
     * later during the
     * {@link #createAnimator(android.view.ViewGroup, TransitionValues, TransitionValues)}
     * method to determine what, if any, animations, should be run.
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
     *
     * @see #captureEndValues(TransitionValues)
     * @see #createAnimator(ViewGroup, TransitionValues, TransitionValues)
     */
    public abstract void captureStartValues(TransitionValues transitionValues);

    /**
     * Captures the values in the end scene for the properties that this
     * transition monitors. These values are then passed as the endValues
     * structure in a later call to
     * {@link #createAnimator(ViewGroup, TransitionValues, TransitionValues)}.
     * The main concern for an implementation is what the
     * properties are that the transition cares about and what the values are
     * for all of those properties. The start and end values will be compared
     * later during the
     * {@link #createAnimator(android.view.ViewGroup, TransitionValues, TransitionValues)}
     * method to determine what, if any, animations, should be run.
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
     *
     * @see #captureStartValues(TransitionValues)
     * @see #createAnimator(ViewGroup, TransitionValues, TransitionValues)
     */
    public abstract void captureEndValues(TransitionValues transitionValues);

    /**
     * Adds the id of a target view that this Transition is interested in
     * animating. By default, there are no targetIds, and a Transition will
     * listen for changes on every view in the hierarchy below the sceneRoot
     * of the Scene being transitioned into. Setting targetIds constrains
     * the Transition to only listen for, and act on, views with these IDs.
     * Views with different IDs, or no IDs whatsoever, will be ignored.
     *
     * <p>Note that using ids to specify targets implies that ids should be unique
     * within the view hierarchy underneath the scene root.</p>
     *
     * @see View#getId()
     * @param targetId The id of a target view, must be a positive number.
     * @return The Transition to which the targetId is added.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).addTarget(someId);</code>
     */
    public Transition addTarget(int targetId) {
        if (targetId > 0) {
            mTargetIds.add(targetId);
        }
        return this;
    }

    /**
     * Adds the transitionName of a target view that this Transition is interested in
     * animating. By default, there are no targetNames, and a Transition will
     * listen for changes on every view in the hierarchy below the sceneRoot
     * of the Scene being transitioned into. Setting targetNames constrains
     * the Transition to only listen for, and act on, views with these transitionNames.
     * Views with different transitionNames, or no transitionName whatsoever, will be ignored.
     *
     * <p>Note that transitionNames should be unique within the view hierarchy.</p>
     *
     * @see android.view.View#getTransitionName()
     * @param targetName The transitionName of a target view, must be non-null.
     * @return The Transition to which the target transitionName is added.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).addTarget(someName);</code>
     */
    public Transition addTarget(String targetName) {
        if (targetName != null) {
            if (mTargetNames == null) {
                mTargetNames = new ArrayList<String>();
            }
            mTargetNames.add(targetName);
        }
        return this;
    }

    /**
     * Adds the Class of a target view that this Transition is interested in
     * animating. By default, there are no targetTypes, and a Transition will
     * listen for changes on every view in the hierarchy below the sceneRoot
     * of the Scene being transitioned into. Setting targetTypes constrains
     * the Transition to only listen for, and act on, views with these classes.
     * Views with different classes will be ignored.
     *
     * <p>Note that any View that can be cast to targetType will be included, so
     * if targetType is <code>View.class</code>, all Views will be included.</p>
     *
     * @see #addTarget(int)
     * @see #addTarget(android.view.View)
     * @see #excludeTarget(Class, boolean)
     * @see #excludeChildren(Class, boolean)
     *
     * @param targetType The type to include when running this transition.
     * @return The Transition to which the target class was added.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).addTarget(ImageView.class);</code>
     */
    public Transition addTarget(Class targetType) {
        if (targetType != null) {
            if (mTargetTypes == null) {
                mTargetTypes = new ArrayList<Class>();
            }
            mTargetTypes.add(targetType);
        }
        return this;
    }

    /**
     * Removes the given targetId from the list of ids that this Transition
     * is interested in animating.
     *
     * @param targetId The id of a target view, must be a positive number.
     * @return The Transition from which the targetId is removed.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).removeTargetId(someId);</code>
     */
    public Transition removeTarget(int targetId) {
        if (targetId > 0) {
            mTargetIds.remove((Integer)targetId);
        }
        return this;
    }

    /**
     * Removes the given targetName from the list of transitionNames that this Transition
     * is interested in animating.
     *
     * @param targetName The transitionName of a target view, must not be null.
     * @return The Transition from which the targetName is removed.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).removeTargetName(someName);</code>
     */
    public Transition removeTarget(String targetName) {
        if (targetName != null && mTargetNames != null) {
            mTargetNames.remove(targetName);
        }
        return this;
    }

    /**
     * Whether to add the given id to the list of target ids to exclude from this
     * transition. The <code>exclude</code> parameter specifies whether the target
     * should be added to or removed from the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeChildren(int, boolean)
     * @see #excludeTarget(View, boolean)
     * @see #excludeTarget(Class, boolean)
     *
     * @param targetId The id of a target to ignore when running this transition.
     * @param exclude Whether to add the target to or remove the target from the
     * current list of excluded targets.
     * @return This transition object.
     */
    public Transition excludeTarget(int targetId, boolean exclude) {
        if (targetId >= 0) {
            mTargetIdExcludes = excludeObject(mTargetIdExcludes, targetId, exclude);
        }
        return this;
    }

    /**
     * Whether to add the given transitionName to the list of target transitionNames to exclude
     * from this transition. The <code>exclude</code> parameter specifies whether the target
     * should be added to or removed from the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded by their
     * id, their instance reference, their transitionName, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeTarget(View, boolean)
     * @see #excludeTarget(int, boolean)
     * @see #excludeTarget(Class, boolean)
     *
     * @param targetName The name of a target to ignore when running this transition.
     * @param exclude Whether to add the target to or remove the target from the
     * current list of excluded targets.
     * @return This transition object.
     */
    public Transition excludeTarget(String targetName, boolean exclude) {
        mTargetNameExcludes = excludeObject(mTargetNameExcludes, targetName, exclude);
        return this;
    }

    /**
     * Whether to add the children of the given id to the list of targets to exclude
     * from this transition. The <code>exclude</code> parameter specifies whether
     * the children of the target should be added to or removed from the excluded list.
     * Excluding children in this way provides a simple mechanism for excluding all
     * children of specific targets, rather than individually excluding each
     * child individually.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeTarget(int, boolean)
     * @see #excludeChildren(View, boolean)
     * @see #excludeChildren(Class, boolean)
     *
     * @param targetId The id of a target whose children should be ignored when running
     * this transition.
     * @param exclude Whether to add the target to or remove the target from the
     * current list of excluded-child targets.
     * @return This transition object.
     */
    public Transition excludeChildren(int targetId, boolean exclude) {
        if (targetId >= 0) {
            mTargetIdChildExcludes = excludeObject(mTargetIdChildExcludes, targetId, exclude);
        }
        return this;
    }

    /**
     * Whether to add the given target to the list of targets to exclude from this
     * transition. The <code>exclude</code> parameter specifies whether the target
     * should be added to or removed from the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeChildren(View, boolean)
     * @see #excludeTarget(int, boolean)
     * @see #excludeTarget(Class, boolean)
     *
     * @param target The target to ignore when running this transition.
     * @param exclude Whether to add the target to or remove the target from the
     * current list of excluded targets.
     * @return This transition object.
     */
    public Transition excludeTarget(View target, boolean exclude) {
        mTargetExcludes = excludeObject(mTargetExcludes, target, exclude);
        return this;
    }

    /**
     * Whether to add the children of given target to the list of target children
     * to exclude from this transition. The <code>exclude</code> parameter specifies
     * whether the target should be added to or removed from the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeTarget(View, boolean)
     * @see #excludeChildren(int, boolean)
     * @see #excludeChildren(Class, boolean)
     *
     * @param target The target to ignore when running this transition.
     * @param exclude Whether to add the target to or remove the target from the
     * current list of excluded targets.
     * @return This transition object.
     */
    public Transition excludeChildren(View target, boolean exclude) {
        mTargetChildExcludes = excludeObject(mTargetChildExcludes, target, exclude);
        return this;
    }

    /**
     * Utility method to manage the boilerplate code that is the same whether we
     * are excluding targets or their children.
     */
    private static <T> ArrayList<T> excludeObject(ArrayList<T> list, T target, boolean exclude) {
        if (target != null) {
            if (exclude) {
                list = ArrayListManager.add(list, target);
            } else {
                list = ArrayListManager.remove(list, target);
            }
        }
        return list;
    }

    /**
     * Whether to add the given type to the list of types to exclude from this
     * transition. The <code>exclude</code> parameter specifies whether the target
     * type should be added to or removed from the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeChildren(Class, boolean)
     * @see #excludeTarget(int, boolean)
     * @see #excludeTarget(View, boolean)
     *
     * @param type The type to ignore when running this transition.
     * @param exclude Whether to add the target type to or remove it from the
     * current list of excluded target types.
     * @return This transition object.
     */
    public Transition excludeTarget(Class type, boolean exclude) {
        mTargetTypeExcludes = excludeObject(mTargetTypeExcludes, type, exclude);
        return this;
    }

    /**
     * Whether to add the given type to the list of types whose children should
     * be excluded from this transition. The <code>exclude</code> parameter
     * specifies whether the target type should be added to or removed from
     * the excluded list.
     *
     * <p>Excluding targets is a general mechanism for allowing transitions to run on
     * a view hierarchy while skipping target views that should not be part of
     * the transition. For example, you may want to avoid animating children
     * of a specific ListView or Spinner. Views can be excluded either by their
     * id, or by their instance reference, or by the Class of that view
     * (eg, {@link Spinner}).</p>
     *
     * @see #excludeTarget(Class, boolean)
     * @see #excludeChildren(int, boolean)
     * @see #excludeChildren(View, boolean)
     *
     * @param type The type to ignore when running this transition.
     * @param exclude Whether to add the target type to or remove it from the
     * current list of excluded target types.
     * @return This transition object.
     */
    public Transition excludeChildren(Class type, boolean exclude) {
        mTargetTypeChildExcludes = excludeObject(mTargetTypeChildExcludes, type, exclude);
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
     * <p>The target list is like the {@link #addTarget(int) targetId}
     * list except this list specifies the actual View instances, not the ids
     * of the views. This is an important distinction when scene changes involve
     * view hierarchies which have been inflated separately; different views may
     * share the same id but not actually be the same instance. If the transition
     * should treat those views as the same, then {@link #addTarget(int)} should be used
     * instead of {@link #addTarget(View)}. If, on the other hand, scene changes involve
     * changes all within the same view hierarchy, among views which do not
     * necessarily have ids set on them, then the target list of views may be more
     * convenient.</p>
     *
     * @see #addTarget(int)
     * @param target A View on which the Transition will act, must be non-null.
     * @return The Transition to which the target is added.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).addTarget(someView);</code>
     */
    public Transition addTarget(View target) {
        mTargets.add(target);
        return this;
    }

    /**
     * Removes the given target from the list of targets that this Transition
     * is interested in animating.
     *
     * @param target The target view, must be non-null.
     * @return Transition The Transition from which the target is removed.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).removeTarget(someView);</code>
     */
    public Transition removeTarget(View target) {
        if (target != null) {
            mTargets.remove(target);
        }
        return this;
    }

    /**
     * Removes the given target from the list of targets that this Transition
     * is interested in animating.
     *
     * @param target The type of the target view, must be non-null.
     * @return Transition The Transition from which the target is removed.
     * Returning the same object makes it easier to chain calls during
     * construction, such as
     * <code>transitionSet.addTransitions(new Fade()).removeTarget(someType);</code>
     */
    public Transition removeTarget(Class target) {
        if (target != null) {
            mTargetTypes.remove(target);
        }
        return this;
    }

    /**
     * Returns the list of target IDs that this transition limits itself to
     * tracking and animating. If the list is null or empty for
     * {@link #getTargetIds()}, {@link #getTargets()}, {@link #getTargetNames()}, and
     * {@link #getTargetTypes()} then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target IDs
     */
    public List<Integer> getTargetIds() {
        return mTargetIds;
    }

    /**
     * Returns the list of target views that this transition limits itself to
     * tracking and animating. If the list is null or empty for
     * {@link #getTargetIds()}, {@link #getTargets()}, {@link #getTargetNames()}, and
     * {@link #getTargetTypes()} then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target views
     */
    public List<View> getTargets() {
        return mTargets;
    }

    /**
     * Returns the list of target transitionNames that this transition limits itself to
     * tracking and animating. If the list is null or empty for
     * {@link #getTargetIds()}, {@link #getTargets()}, {@link #getTargetNames()}, and
     * {@link #getTargetTypes()} then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target transitionNames
     */
    public List<String> getTargetNames() {
        return mTargetNames;
    }

    /**
     * To be removed before L release.
     * @hide
     */
    public List<String> getTargetViewNames() {
        return mTargetNames;
    }

    /**
     * Returns the list of target transitionNames that this transition limits itself to
     * tracking and animating. If the list is null or empty for
     * {@link #getTargetIds()}, {@link #getTargets()}, {@link #getTargetNames()}, and
     * {@link #getTargetTypes()} then this transition is
     * not limited to specific views, and will handle changes to any views
     * in the hierarchy of a scene change.
     *
     * @return the list of target Types
     */
    public List<Class> getTargetTypes() {
        return mTargetTypes;
    }

    /**
     * Recursive method that captures values for the given view and the
     * hierarchy underneath it.
     * @param sceneRoot The root of the view hierarchy being captured
     * @param start true if this capture is happening before the scene change,
     * false otherwise
     */
    void captureValues(ViewGroup sceneRoot, boolean start) {
        clearValues(start);
        if ((mTargetIds.size() > 0 || mTargets.size() > 0)
                && (mTargetNames == null || mTargetNames.isEmpty())
                && (mTargetTypes == null || mTargetTypes.isEmpty())) {
            for (int i = 0; i < mTargetIds.size(); ++i) {
                int id = mTargetIds.get(i);
                View view = sceneRoot.findViewById(id);
                if (view != null) {
                    TransitionValues values = new TransitionValues();
                    values.view = view;
                    if (start) {
                        captureStartValues(values);
                    } else {
                        captureEndValues(values);
                    }
                    values.targetedTransitions.add(this);
                    capturePropagationValues(values);
                    if (start) {
                        addViewValues(mStartValues, view, values);
                    } else {
                        addViewValues(mEndValues, view, values);
                    }
                }
            }
            for (int i = 0; i < mTargets.size(); ++i) {
                View view = mTargets.get(i);
                TransitionValues values = new TransitionValues();
                values.view = view;
                if (start) {
                    captureStartValues(values);
                } else {
                    captureEndValues(values);
                }
                values.targetedTransitions.add(this);
                capturePropagationValues(values);
                if (start) {
                    addViewValues(mStartValues, view, values);
                } else {
                    addViewValues(mEndValues, view, values);
                }
            }
        } else {
            captureHierarchy(sceneRoot, start);
        }
        if (!start && mNameOverrides != null) {
            int numOverrides = mNameOverrides.size();
            ArrayList<View> overriddenViews = new ArrayList<View>(numOverrides);
            for (int i = 0; i < numOverrides; i++) {
                String fromName = mNameOverrides.keyAt(i);
                overriddenViews.add(mStartValues.nameValues.remove(fromName));
            }
            for (int i = 0; i < numOverrides; i++) {
                View view = overriddenViews.get(i);
                if (view != null) {
                    String toName = mNameOverrides.valueAt(i);
                    mStartValues.nameValues.put(toName, view);
                }
            }
        }
    }

    static void addViewValues(TransitionValuesMaps transitionValuesMaps,
            View view, TransitionValues transitionValues) {
        transitionValuesMaps.viewValues.put(view, transitionValues);
        int id = view.getId();
        if (id >= 0) {
            if (transitionValuesMaps.idValues.indexOfKey(id) >= 0) {
                // Duplicate IDs cannot match by ID.
                transitionValuesMaps.idValues.put(id, null);
            } else {
                transitionValuesMaps.idValues.put(id, view);
            }
        }
        String name = view.getTransitionName();
        if (name != null) {
            if (transitionValuesMaps.nameValues.containsKey(name)) {
                // Duplicate transitionNames: cannot match by transitionName.
                transitionValuesMaps.nameValues.put(name, null);
            } else {
                transitionValuesMaps.nameValues.put(name, view);
            }
        }
        if (view.getParent() instanceof ListView) {
            ListView listview = (ListView) view.getParent();
            if (listview.getAdapter().hasStableIds()) {
                int position = listview.getPositionForView(view);
                long itemId = listview.getItemIdAtPosition(position);
                if (transitionValuesMaps.itemIdValues.indexOfKey(itemId) >= 0) {
                    // Duplicate item IDs: cannot match by item ID.
                    View alreadyMatched = transitionValuesMaps.itemIdValues.get(itemId);
                    if (alreadyMatched != null) {
                        alreadyMatched.setHasTransientState(false);
                        transitionValuesMaps.itemIdValues.put(itemId, null);
                    }
                } else {
                    view.setHasTransientState(true);
                    transitionValuesMaps.itemIdValues.put(itemId, view);
                }
            }
        }
    }

    /**
     * Clear valuesMaps for specified start/end state
     *
     * @param start true if the start values should be cleared, false otherwise
     */
    void clearValues(boolean start) {
        if (start) {
            mStartValues.viewValues.clear();
            mStartValues.idValues.clear();
            mStartValues.itemIdValues.clear();
            mStartValues.nameValues.clear();
            mStartValuesList = null;
        } else {
            mEndValues.viewValues.clear();
            mEndValues.idValues.clear();
            mEndValues.itemIdValues.clear();
            mEndValues.nameValues.clear();
            mEndValuesList = null;
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
        int id = view.getId();
        if (mTargetIdExcludes != null && mTargetIdExcludes.contains(id)) {
            return;
        }
        if (mTargetExcludes != null && mTargetExcludes.contains(view)) {
            return;
        }
        if (mTargetTypeExcludes != null && view != null) {
            int numTypes = mTargetTypeExcludes.size();
            for (int i = 0; i < numTypes; ++i) {
                if (mTargetTypeExcludes.get(i).isInstance(view)) {
                    return;
                }
            }
        }
        if (view.getParent() instanceof ViewGroup) {
            TransitionValues values = new TransitionValues();
            values.view = view;
            if (start) {
                captureStartValues(values);
            } else {
                captureEndValues(values);
            }
            values.targetedTransitions.add(this);
            capturePropagationValues(values);
            if (start) {
                addViewValues(mStartValues, view, values);
            } else {
                addViewValues(mEndValues, view, values);
            }
        }
        if (view instanceof ViewGroup) {
            // Don't traverse child hierarchy if there are any child-excludes on this view
            if (mTargetIdChildExcludes != null && mTargetIdChildExcludes.contains(id)) {
                return;
            }
            if (mTargetChildExcludes != null && mTargetChildExcludes.contains(view)) {
                return;
            }
            if (mTargetTypeChildExcludes != null) {
                int numTypes = mTargetTypeChildExcludes.size();
                for (int i = 0; i < numTypes; ++i) {
                    if (mTargetTypeChildExcludes.get(i).isInstance(view)) {
                        return;
                    }
                }
            }
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
    public TransitionValues getTransitionValues(View view, boolean start) {
        if (mParent != null) {
            return mParent.getTransitionValues(view, start);
        }
        TransitionValuesMaps valuesMaps = start ? mStartValues : mEndValues;
        return valuesMaps.viewValues.get(view);
    }

    /**
     * Find the matched start or end value for a given View. This is only valid
     * after playTransition starts. For example, it will be valid in
     * {@link #createAnimator(android.view.ViewGroup, TransitionValues, TransitionValues)}, but not
     * in {@link #captureStartValues(TransitionValues)}.
     *
     * @param view The view to find the match for.
     * @param viewInStart Is View from the start values or end values.
     * @return The matching TransitionValues for view in either start or end values, depending
     * on viewInStart or null if there is no match for the given view.
     */
    TransitionValues getMatchedTransitionValues(View view, boolean viewInStart) {
        if (mParent != null) {
            return mParent.getMatchedTransitionValues(view, viewInStart);
        }
        ArrayList<TransitionValues> lookIn = viewInStart ? mStartValuesList : mEndValuesList;
        if (lookIn == null) {
            return null;
        }
        int count = lookIn.size();
        int index = -1;
        for (int i = 0; i < count; i++) {
            TransitionValues values = lookIn.get(i);
            if (values == null) {
                // Null values are always added to the end of the list, so we know to stop now.
                return null;
            }
            if (values.view == view) {
                index = i;
                break;
            }
        }
        TransitionValues values = null;
        if (index >= 0) {
            ArrayList<TransitionValues> matchIn = viewInStart ? mEndValuesList : mStartValuesList;
            values = matchIn.get(index);
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
    public void pause(View sceneRoot) {
        if (!mEnded) {
            ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
            int numOldAnims = runningAnimators.size();
            if (sceneRoot != null) {
                WindowId windowId = sceneRoot.getWindowId();
                for (int i = numOldAnims - 1; i >= 0; i--) {
                    AnimationInfo info = runningAnimators.valueAt(i);
                    if (info.view != null && windowId != null && windowId.equals(info.windowId)) {
                        Animator anim = runningAnimators.keyAt(i);
                        anim.pause();
                    }
                }
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
    }

    /**
     * Resumes this transition, sending out calls to {@link
     * TransitionListener#onTransitionPause(Transition)} to all listeners
     * and pausing all running animators started by this transition.
     *
     * @hide
     */
    public void resume(View sceneRoot) {
        if (mPaused) {
            if (!mEnded) {
                ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
                int numOldAnims = runningAnimators.size();
                WindowId windowId = sceneRoot.getWindowId();
                for (int i = numOldAnims - 1; i >= 0; i--) {
                    AnimationInfo info = runningAnimators.valueAt(i);
                    if (info.view != null && windowId != null && windowId.equals(info.windowId)) {
                        Animator anim = runningAnimators.keyAt(i);
                        anim.resume();
                    }
                }
                if (mListeners != null && mListeners.size() > 0) {
                    ArrayList<TransitionListener> tmpListeners =
                            (ArrayList<TransitionListener>) mListeners.clone();
                    int numListeners = tmpListeners.size();
                    for (int i = 0; i < numListeners; ++i) {
                        tmpListeners.get(i).onTransitionResume(this);
                    }
                }
            }
            mPaused = false;
        }
    }

    /**
     * Called by TransitionManager to play the transition. This calls
     * createAnimators() to set things up and create all of the animations and then
     * runAnimations() to actually start the animations.
     */
    void playTransition(ViewGroup sceneRoot) {
        mStartValuesList = new ArrayList<TransitionValues>();
        mEndValuesList = new ArrayList<TransitionValues>();
        matchStartAndEnd(mStartValues, mEndValues);

        ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
        int numOldAnims = runningAnimators.size();
        WindowId windowId = sceneRoot.getWindowId();
        for (int i = numOldAnims - 1; i >= 0; i--) {
            Animator anim = runningAnimators.keyAt(i);
            if (anim != null) {
                AnimationInfo oldInfo = runningAnimators.get(anim);
                if (oldInfo != null && oldInfo.view != null && oldInfo.windowId == windowId) {
                    TransitionValues oldValues = oldInfo.values;
                    View oldView = oldInfo.view;
                    TransitionValues startValues = getTransitionValues(oldView, true);
                    TransitionValues endValues = getMatchedTransitionValues(oldView, true);
                    if (startValues == null && endValues == null) {
                        endValues = mEndValues.viewValues.get(oldView);
                    }
                    boolean cancel = (startValues != null || endValues != null) &&
                            oldInfo.transition.isTransitionRequired(oldValues, endValues);
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

        createAnimators(sceneRoot, mStartValues, mEndValues, mStartValuesList, mEndValuesList);
        runAnimators();
    }

    /**
     * Returns whether or not the transition should create an Animator, based on the values
     * captured during {@link #captureStartValues(TransitionValues)} and
     * {@link #captureEndValues(TransitionValues)}. The default implementation compares the
     * property values returned from {@link #getTransitionProperties()}, or all property values if
     * {@code getTransitionProperties()} returns null. Subclasses may override this method to
     * provide logic more specific to the transition implementation.
     *
     * @param startValues the values from captureStartValues, This may be {@code null} if the
     *                    View did not exist in the start state.
     * @param endValues the values from captureEndValues. This may be {@code null} if the View
     *                  did not exist in the end state.
     */
    public boolean isTransitionRequired(@Nullable TransitionValues startValues,
            @Nullable TransitionValues endValues) {
        boolean valuesChanged = false;
        // if startValues null, then transition didn't care to stash values,
        // and won't get canceled
        if (startValues != null && endValues != null) {
            String[] properties = getTransitionProperties();
            if (properties != null) {
                int count = properties.length;
                for (int i = 0; i < count; i++) {
                    if (isValueChanged(startValues, endValues, properties[i])) {
                        valuesChanged = true;
                        break;
                    }
                }
            } else {
                for (String key : startValues.values.keySet()) {
                    if (isValueChanged(startValues, endValues, key)) {
                        valuesChanged = true;
                        break;
                    }
                }
            }
        }
        return valuesChanged;
    }

    private static boolean isValueChanged(TransitionValues oldValues, TransitionValues newValues,
            String key) {
        if (oldValues.values.containsKey(key) != newValues.values.containsKey(key)) {
            // The transition didn't care about this particular value, so we don't care, either.
            return false;
        }
        Object oldValue = oldValues.values.get(key);
        Object newValue = newValues.values.get(key);
        boolean changed;
        if (oldValue == null && newValue == null) {
            // both are null
            changed = false;
        } else if (oldValue == null || newValue == null) {
            // one is null
            changed = true;
        } else {
            // neither is null
            changed = !oldValue.equals(newValue);
        }
        if (DBG && changed) {
            Log.d(LOG_TAG, "Transition.playTransition: " +
                    "oldValue != newValue for " + key +
                    ": old, new = " + oldValue + ", " + newValue);
        }
        return changed;
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
                animator.setStartDelay(getStartDelay() + animator.getStartDelay());
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
     * TransitionSet classes prior to a Transition subclass starting;
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
            mEnded = false;
        }
        mNumInstances++;
    }

    /**
     * This method is called automatically by the Transition and
     * TransitionSet classes when a transition finishes, either because
     * a transition did nothing (returned a null Animator from
     * {@link Transition#createAnimator(ViewGroup, TransitionValues,
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
                View view = mStartValues.itemIdValues.valueAt(i);
                if (view != null) {
                    view.setHasTransientState(false);
                }
            }
            for (int i = 0; i < mEndValues.itemIdValues.size(); ++i) {
                View view = mEndValues.itemIdValues.valueAt(i);
                if (view != null) {
                    view.setHasTransientState(false);
                }
            }
            mEnded = true;
        }
    }

    /**
     * This method cancels a transition that is currently running.
     *
     * @hide
     */
    protected void cancel() {
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
     * @return This transition object.
     */
    public Transition addListener(TransitionListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<TransitionListener>();
        }
        mListeners.add(listener);
        return this;
    }

    /**
     * Removes a listener from the set listening to this animation.
     *
     * @param listener the listener to be removed from the current set of
     * listeners for this transition.
     * @return This transition object.
     */
    public Transition removeListener(TransitionListener listener) {
        if (mListeners == null) {
            return this;
        }
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            mListeners = null;
        }
        return this;
    }

    /**
     * Sets the callback to use to find the epicenter of a Transition. A null value indicates
     * that there is no epicenter in the Transition and onGetEpicenter() will return null.
     * Transitions like {@link android.transition.Explode} use a point or Rect to orient
     * the direction of travel. This is called the epicenter of the Transition and is
     * typically centered on a touched View. The
     * {@link android.transition.Transition.EpicenterCallback} allows a Transition to
     * dynamically retrieve the epicenter during a Transition.
     * @param epicenterCallback The callback to use to find the epicenter of the Transition.
     */
    public void setEpicenterCallback(EpicenterCallback epicenterCallback) {
        mEpicenterCallback = epicenterCallback;
    }

    /**
     * Returns the callback used to find the epicenter of the Transition.
     * Transitions like {@link android.transition.Explode} use a point or Rect to orient
     * the direction of travel. This is called the epicenter of the Transition and is
     * typically centered on a touched View. The
     * {@link android.transition.Transition.EpicenterCallback} allows a Transition to
     * dynamically retrieve the epicenter during a Transition.
     * @return the callback used to find the epicenter of the Transition.
     */
    public EpicenterCallback getEpicenterCallback() {
        return mEpicenterCallback;
    }

    /**
     * Returns the epicenter as specified by the
     * {@link android.transition.Transition.EpicenterCallback} or null if no callback exists.
     * @return the epicenter as specified by the
     * {@link android.transition.Transition.EpicenterCallback} or null if no callback exists.
     * @see #setEpicenterCallback(android.transition.Transition.EpicenterCallback)
     */
    public Rect getEpicenter() {
        if (mEpicenterCallback == null) {
            return null;
        }
        return mEpicenterCallback.onGetEpicenter(this);
    }

    /**
     * Sets the algorithm used to calculate two-dimensional interpolation.
     * <p>
     *     Transitions such as {@link android.transition.ChangeBounds} move Views, typically
     *     in a straight path between the start and end positions. Applications that desire to
     *     have these motions move in a curve can change how Views interpolate in two dimensions
     *     by extending PathMotion and implementing
     *     {@link android.transition.PathMotion#getPath(float, float, float, float)}.
     * </p>
     * <p>
     *     When describing in XML, use a nested XML tag for the path motion. It can be one of
     *     the built-in tags <code>arcMotion</code> or <code>patternPathMotion</code> or it can
     *     be a custom PathMotion using <code>pathMotion</code> with the <code>class</code>
     *     attributed with the fully-described class name. For example:</p>
     * <pre>
     * {@code
     * <changeBounds>
     *     <pathMotion class="my.app.transition.MyPathMotion"/>
     * </changeBounds>
     * }
     * </pre>
     * <p>or</p>
     * <pre>
     * {@code
     * <changeBounds>
     *   <arcMotion android:minimumHorizontalAngle="15"
     *     android:minimumVerticalAngle="0" android:maximumAngle="90"/>
     * </changeBounds>
     * }
     * </pre>
     *
     * @param pathMotion Algorithm object to use for determining how to interpolate in two
     *                   dimensions. If null, a straight-path algorithm will be used.
     * @see android.transition.ArcMotion
     * @see PatternPathMotion
     * @see android.transition.PathMotion
     */
    public void setPathMotion(PathMotion pathMotion) {
        if (pathMotion == null) {
            mPathMotion = STRAIGHT_PATH_MOTION;
        } else {
            mPathMotion = pathMotion;
        }
    }

    /**
     * Returns the algorithm object used to interpolate along two dimensions. This is typically
     * used to determine the View motion between two points.
     *
     * <p>
     *     When describing in XML, use a nested XML tag for the path motion. It can be one of
     *     the built-in tags <code>arcMotion</code> or <code>patternPathMotion</code> or it can
     *     be a custom PathMotion using <code>pathMotion</code> with the <code>class</code>
     *     attributed with the fully-described class name. For example:</p>
     * <pre>{@code
     * <changeBounds>
     *     <pathMotion class="my.app.transition.MyPathMotion"/>
     * </changeBounds>}
     * </pre>
     * <p>or</p>
     * <pre>{@code
     * <changeBounds>
     *   <arcMotion android:minimumHorizontalAngle="15"
     *              android:minimumVerticalAngle="0"
     *              android:maximumAngle="90"/>
     * </changeBounds>}
     * </pre>
     *
     * @return The algorithm object used to interpolate along two dimensions.
     * @see android.transition.ArcMotion
     * @see PatternPathMotion
     * @see android.transition.PathMotion
     */
    public PathMotion getPathMotion() {
        return mPathMotion;
    }

    /**
     * Sets the method for determining Animator start delays.
     * When a Transition affects several Views like {@link android.transition.Explode} or
     * {@link android.transition.Slide}, there may be a desire to have a "wave-front" effect
     * such that the Animator start delay depends on position of the View. The
     * TransitionPropagation specifies how the start delays are calculated.
     * @param transitionPropagation The class used to determine the start delay of
     *                              Animators created by this Transition. A null value
     *                              indicates that no delay should be used.
     */
    public void setPropagation(TransitionPropagation transitionPropagation) {
        mPropagation = transitionPropagation;
    }

    /**
     * Returns the {@link android.transition.TransitionPropagation} used to calculate Animator start
     * delays.
     * When a Transition affects several Views like {@link android.transition.Explode} or
     * {@link android.transition.Slide}, there may be a desire to have a "wave-front" effect
     * such that the Animator start delay depends on position of the View. The
     * TransitionPropagation specifies how the start delays are calculated.
     * @return the {@link android.transition.TransitionPropagation} used to calculate Animator start
     * delays. This is null by default.
     */
    public TransitionPropagation getPropagation() {
        return mPropagation;
    }

    /**
     * Captures TransitionPropagation values for the given view and the
     * hierarchy underneath it.
     */
    void capturePropagationValues(TransitionValues transitionValues) {
        if (mPropagation != null && !transitionValues.values.isEmpty()) {
            String[] propertyNames = mPropagation.getPropagationProperties();
            if (propertyNames == null) {
                return;
            }
            boolean containsAll = true;
            for (int i = 0; i < propertyNames.length; i++) {
                if (!transitionValues.values.containsKey(propertyNames[i])) {
                    containsAll = false;
                    break;
                }
            }
            if (!containsAll) {
                mPropagation.captureValues(transitionValues);
            }
        }
    }

    Transition setSceneRoot(ViewGroup sceneRoot) {
        mSceneRoot = sceneRoot;
        return this;
    }

    void setCanRemoveViews(boolean canRemoveViews) {
        mCanRemoveViews = canRemoveViews;
    }

    public boolean canRemoveViews() {
        return mCanRemoveViews;
    }

    /**
     * Sets the shared element names -- a mapping from a name at the start state to
     * a different name at the end state.
     * @hide
     */
    public void setNameOverrides(ArrayMap<String, String> overrides) {
        mNameOverrides = overrides;
    }

    /** @hide */
    public ArrayMap<String, String> getNameOverrides() {
        return mNameOverrides;
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
            clone.mStartValues = new TransitionValuesMaps();
            clone.mEndValues = new TransitionValuesMaps();
            clone.mStartValuesList = null;
            clone.mEndValuesList = null;
        } catch (CloneNotSupportedException e) {}

        return clone;
    }

    /**
     * Returns the name of this Transition. This name is used internally to distinguish
     * between different transitions to determine when interrupting transitions overlap.
     * For example, a ChangeBounds running on the same target view as another ChangeBounds
     * should determine whether the old transition is animating to different end values
     * and should be canceled in favor of the new transition.
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
        if (mTargetIds.size() > 0 || mTargets.size() > 0) {
            result += "tgts(";
            if (mTargetIds.size() > 0) {
                for (int i = 0; i < mTargetIds.size(); ++i) {
                    if (i > 0) {
                        result += ", ";
                    }
                    result += mTargetIds.get(i);
                }
            }
            if (mTargets.size() > 0) {
                for (int i = 0; i < mTargets.size(); ++i) {
                    if (i > 0) {
                        result += ", ";
                    }
                    result += mTargets.get(i);
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
         * Note that cancel may be called by a parent {@link TransitionSet} on
         * a child transition which has not yet started. This allows the child
         * transition to restore state on target objects which was set at
         * {@link #createAnimator(android.view.ViewGroup, TransitionValues, TransitionValues)
         * createAnimator()} time.
         *
         * @param transition The transition which was canceled.
         */
        void onTransitionCancel(Transition transition);

        /**
         * Notification when a transition is paused.
         * Note that createAnimator() may be called by a parent {@link TransitionSet} on
         * a child transition which has not yet started. This allows the child
         * transition to restore state on target objects which was set at
         * {@link #createAnimator(android.view.ViewGroup, TransitionValues, TransitionValues)
         * createAnimator()} time.
         *
         * @param transition The transition which was paused.
         */
        void onTransitionPause(Transition transition);

        /**
         * Notification when a transition is resumed.
         * Note that resume() may be called by a parent {@link TransitionSet} on
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
     * @hide
     */
    public static class AnimationInfo {
        public View view;
        String name;
        TransitionValues values;
        WindowId windowId;
        Transition transition;

        AnimationInfo(View view, String name, Transition transition,
                WindowId windowId, TransitionValues values) {
            this.view = view;
            this.name = name;
            this.values = values;
            this.windowId = windowId;
            this.transition = transition;
        }
    }

    /**
     * Utility class for managing typed ArrayLists efficiently. In particular, this
     * can be useful for lists that we don't expect to be used often (eg, the exclude
     * lists), so we'd like to keep them nulled out by default. This causes the code to
     * become tedious, with constant null checks, code to allocate when necessary,
     * and code to null out the reference when the list is empty. This class encapsulates
     * all of that functionality into simple add()/remove() methods which perform the
     * necessary checks, allocation/null-out as appropriate, and return the
     * resulting list.
     */
    private static class ArrayListManager {

        /**
         * Add the specified item to the list, returning the resulting list.
         * The returned list can either the be same list passed in or, if that
         * list was null, the new list that was created.
         *
         * Note that the list holds unique items; if the item already exists in the
         * list, the list is not modified.
         */
        static <T> ArrayList<T> add(ArrayList<T> list, T item) {
            if (list == null) {
                list = new ArrayList<T>();
            }
            if (!list.contains(item)) {
                list.add(item);
            }
            return list;
        }

        /**
         * Remove the specified item from the list, returning the resulting list.
         * The returned list can either the be same list passed in or, if that
         * list becomes empty as a result of the remove(), the new list was created.
         */
        static <T> ArrayList<T> remove(ArrayList<T> list, T item) {
            if (list != null) {
                list.remove(item);
                if (list.isEmpty()) {
                    list = null;
                }
            }
            return list;
        }
    }

    /**
     * Class to get the epicenter of Transition. Use
     * {@link #setEpicenterCallback(android.transition.Transition.EpicenterCallback)} to
     * set the callback used to calculate the epicenter of the Transition. Override
     * {@link #getEpicenter()} to return the rectangular region in screen coordinates of
     * the epicenter of the transition.
     * @see #setEpicenterCallback(android.transition.Transition.EpicenterCallback)
     */
    public static abstract class EpicenterCallback {

        /**
         * Implementers must override to return the epicenter of the Transition in screen
         * coordinates. Transitions like {@link android.transition.Explode} depend upon
         * an epicenter for the Transition. In Explode, Views move toward or away from the
         * center of the epicenter Rect along the vector between the epicenter and the center
         * of the View appearing and disappearing. Some Transitions, such as
         * {@link android.transition.Fade} pay no attention to the epicenter.
         *
         * @param transition The transition for which the epicenter applies.
         * @return The Rect region of the epicenter of <code>transition</code> or null if
         * there is no epicenter.
         */
        public abstract Rect onGetEpicenter(Transition transition);
    }
}
