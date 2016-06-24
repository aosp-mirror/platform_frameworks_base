/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.ActivityThread;
import android.app.Application;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This class plays a set of {@link Animator} objects in the specified order. Animations
 * can be set up to play together, in sequence, or after a specified delay.
 *
 * <p>There are two different approaches to adding animations to a <code>AnimatorSet</code>:
 * either the {@link AnimatorSet#playTogether(Animator[]) playTogether()} or
 * {@link AnimatorSet#playSequentially(Animator[]) playSequentially()} methods can be called to add
 * a set of animations all at once, or the {@link AnimatorSet#play(Animator)} can be
 * used in conjunction with methods in the {@link AnimatorSet.Builder Builder}
 * class to add animations
 * one by one.</p>
 *
 * <p>It is possible to set up a <code>AnimatorSet</code> with circular dependencies between
 * its animations. For example, an animation a1 could be set up to start before animation a2, a2
 * before a3, and a3 before a1. The results of this configuration are undefined, but will typically
 * result in none of the affected animations being played. Because of this (and because
 * circular dependencies do not make logical sense anyway), circular dependencies
 * should be avoided, and the dependency flow of animations should only be in one direction.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about animating with {@code AnimatorSet}, read the
 * <a href="{@docRoot}guide/topics/graphics/prop-animation.html#choreography">Property
 * Animation</a> developer guide.</p>
 * </div>
 */
public final class AnimatorSet extends Animator {

    private static final String TAG = "AnimatorSet";
    /**
     * Internal variables
     * NOTE: This object implements the clone() method, making a deep copy of any referenced
     * objects. As other non-trivial fields are added to this class, make sure to add logic
     * to clone() to make deep copies of them.
     */

    /**
     * Tracks animations currently being played, so that we know what to
     * cancel or end when cancel() or end() is called on this AnimatorSet
     */
    private ArrayList<Animator> mPlayingSet = new ArrayList<Animator>();

    /**
     * Contains all nodes, mapped to their respective Animators. When new
     * dependency information is added for an Animator, we want to add it
     * to a single node representing that Animator, not create a new Node
     * if one already exists.
     */
    private ArrayMap<Animator, Node> mNodeMap = new ArrayMap<Animator, Node>();

    /**
     * Set of all nodes created for this AnimatorSet. This list is used upon
     * starting the set, and the nodes are placed in sorted order into the
     * sortedNodes collection.
     */
    private ArrayList<Node> mNodes = new ArrayList<Node>();

    /**
     * Animator Listener that tracks the lifecycle of each Animator in the set. It will be added
     * to each Animator before they start and removed after they end.
     */
    private AnimatorSetListener mSetListener = new AnimatorSetListener(this);

    /**
     * Flag indicating that the AnimatorSet has been manually
     * terminated (by calling cancel() or end()).
     * This flag is used to avoid starting other animations when currently-playing
     * child animations of this AnimatorSet end. It also determines whether cancel/end
     * notifications are sent out via the normal AnimatorSetListener mechanism.
     */
    private boolean mTerminated = false;

    /**
     * Tracks whether any change has been made to the AnimatorSet, which is then used to
     * determine whether the dependency graph should be re-constructed.
     */
    private boolean mDependencyDirty = false;

    /**
     * Indicates whether an AnimatorSet has been start()'d, whether or
     * not there is a nonzero startDelay.
     */
    private boolean mStarted = false;

    // The amount of time in ms to delay starting the animation after start() is called
    private long mStartDelay = 0;

    // Animator used for a nonzero startDelay
    private ValueAnimator mDelayAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(0);

    // Root of the dependency tree of all the animators in the set. In this tree, parent-child
    // relationship captures the order of animation (i.e. parent and child will play sequentially),
    // and sibling relationship indicates "with" relationship, as sibling animators start at the
    // same time.
    private Node mRootNode = new Node(mDelayAnim);

    // How long the child animations should last in ms. The default value is negative, which
    // simply means that there is no duration set on the AnimatorSet. When a real duration is
    // set, it is passed along to the child animations.
    private long mDuration = -1;

    // Records the interpolator for the set. Null value indicates that no interpolator
    // was set on this AnimatorSet, so it should not be passed down to the children.
    private TimeInterpolator mInterpolator = null;

    // Whether the AnimatorSet can be reversed.
    private boolean mReversible = true;
    // The total duration of finishing all the Animators in the set.
    private long mTotalDuration = 0;

    // In pre-N releases, calling end() before start() on an animator set is no-op. But that is not
    // consistent with the behavior for other animator types. In order to keep the behavior
    // consistent within Animation framework, when end() is called without start(), we will start
    // the animator set and immediately end it for N and forward.
    private final boolean mShouldIgnoreEndWithoutStart;

    public AnimatorSet() {
        super();
        mNodeMap.put(mDelayAnim, mRootNode);
        mNodes.add(mRootNode);
        // Set the flag to ignore calling end() without start() for pre-N releases
        Application app = ActivityThread.currentApplication();
        if (app == null || app.getApplicationInfo() == null) {
            mShouldIgnoreEndWithoutStart = true;
        } else if (app.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N) {
            mShouldIgnoreEndWithoutStart = true;
        } else {
            mShouldIgnoreEndWithoutStart = false;
        }
    }

    /**
     * Sets up this AnimatorSet to play all of the supplied animations at the same time.
     * This is equivalent to calling {@link #play(Animator)} with the first animator in the
     * set and then {@link Builder#with(Animator)} with each of the other animators. Note that
     * an Animator with a {@link Animator#setStartDelay(long) startDelay} will not actually
     * start until that delay elapses, which means that if the first animator in the list
     * supplied to this constructor has a startDelay, none of the other animators will start
     * until that first animator's startDelay has elapsed.
     *
     * @param items The animations that will be started simultaneously.
     */
    public void playTogether(Animator... items) {
        if (items != null) {
            Builder builder = play(items[0]);
            for (int i = 1; i < items.length; ++i) {
                builder.with(items[i]);
            }
        }
    }

    /**
     * Sets up this AnimatorSet to play all of the supplied animations at the same time.
     *
     * @param items The animations that will be started simultaneously.
     */
    public void playTogether(Collection<Animator> items) {
        if (items != null && items.size() > 0) {
            Builder builder = null;
            for (Animator anim : items) {
                if (builder == null) {
                    builder = play(anim);
                } else {
                    builder.with(anim);
                }
            }
        }
    }

    /**
     * Sets up this AnimatorSet to play each of the supplied animations when the
     * previous animation ends.
     *
     * @param items The animations that will be started one after another.
     */
    public void playSequentially(Animator... items) {
        if (items != null) {
            if (items.length == 1) {
                play(items[0]);
            } else {
                mReversible = false;
                for (int i = 0; i < items.length - 1; ++i) {
                    play(items[i]).before(items[i + 1]);
                }
            }
        }
    }

    /**
     * Sets up this AnimatorSet to play each of the supplied animations when the
     * previous animation ends.
     *
     * @param items The animations that will be started one after another.
     */
    public void playSequentially(List<Animator> items) {
        if (items != null && items.size() > 0) {
            if (items.size() == 1) {
                play(items.get(0));
            } else {
                mReversible = false;
                for (int i = 0; i < items.size() - 1; ++i) {
                    play(items.get(i)).before(items.get(i + 1));
                }
            }
        }
    }

    /**
     * Returns the current list of child Animator objects controlled by this
     * AnimatorSet. This is a copy of the internal list; modifications to the returned list
     * will not affect the AnimatorSet, although changes to the underlying Animator objects
     * will affect those objects being managed by the AnimatorSet.
     *
     * @return ArrayList<Animator> The list of child animations of this AnimatorSet.
     */
    public ArrayList<Animator> getChildAnimations() {
        ArrayList<Animator> childList = new ArrayList<Animator>();
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node != mRootNode) {
                childList.add(node.mAnimation);
            }
        }
        return childList;
    }

    /**
     * Sets the target object for all current {@link #getChildAnimations() child animations}
     * of this AnimatorSet that take targets ({@link ObjectAnimator} and
     * AnimatorSet).
     *
     * @param target The object being animated
     */
    @Override
    public void setTarget(Object target) {
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            Animator animation = node.mAnimation;
            if (animation instanceof AnimatorSet) {
                ((AnimatorSet)animation).setTarget(target);
            } else if (animation instanceof ObjectAnimator) {
                ((ObjectAnimator)animation).setTarget(target);
            }
        }
    }

    /**
     * @hide
     */
    @Override
    public int getChangingConfigurations() {
        int conf = super.getChangingConfigurations();
        final int nodeCount = mNodes.size();
        for (int i = 0; i < nodeCount; i ++) {
            conf |= mNodes.get(i).mAnimation.getChangingConfigurations();
        }
        return conf;
    }

    /**
     * Sets the TimeInterpolator for all current {@link #getChildAnimations() child animations}
     * of this AnimatorSet. The default value is null, which means that no interpolator
     * is set on this AnimatorSet. Setting the interpolator to any non-null value
     * will cause that interpolator to be set on the child animations
     * when the set is started.
     *
     * @param interpolator the interpolator to be used by each child animation of this AnimatorSet
     */
    @Override
    public void setInterpolator(TimeInterpolator interpolator) {
        mInterpolator = interpolator;
    }

    @Override
    public TimeInterpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * This method creates a <code>Builder</code> object, which is used to
     * set up playing constraints. This initial <code>play()</code> method
     * tells the <code>Builder</code> the animation that is the dependency for
     * the succeeding commands to the <code>Builder</code>. For example,
     * calling <code>play(a1).with(a2)</code> sets up the AnimatorSet to play
     * <code>a1</code> and <code>a2</code> at the same time,
     * <code>play(a1).before(a2)</code> sets up the AnimatorSet to play
     * <code>a1</code> first, followed by <code>a2</code>, and
     * <code>play(a1).after(a2)</code> sets up the AnimatorSet to play
     * <code>a2</code> first, followed by <code>a1</code>.
     *
     * <p>Note that <code>play()</code> is the only way to tell the
     * <code>Builder</code> the animation upon which the dependency is created,
     * so successive calls to the various functions in <code>Builder</code>
     * will all refer to the initial parameter supplied in <code>play()</code>
     * as the dependency of the other animations. For example, calling
     * <code>play(a1).before(a2).before(a3)</code> will play both <code>a2</code>
     * and <code>a3</code> when a1 ends; it does not set up a dependency between
     * <code>a2</code> and <code>a3</code>.</p>
     *
     * @param anim The animation that is the dependency used in later calls to the
     * methods in the returned <code>Builder</code> object. A null parameter will result
     * in a null <code>Builder</code> return value.
     * @return Builder The object that constructs the AnimatorSet based on the dependencies
     * outlined in the calls to <code>play</code> and the other methods in the
     * <code>Builder</code object.
     */
    public Builder play(Animator anim) {
        if (anim != null) {
            return new Builder(anim);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that canceling a <code>AnimatorSet</code> also cancels all of the animations that it
     * is responsible for.</p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public void cancel() {
        mTerminated = true;
        if (isStarted()) {
            ArrayList<AnimatorListener> tmpListeners = null;
            if (mListeners != null) {
                tmpListeners = (ArrayList<AnimatorListener>) mListeners.clone();
                int size = tmpListeners.size();
                for (int i = 0; i < size; i++) {
                    tmpListeners.get(i).onAnimationCancel(this);
                }
            }
            ArrayList<Animator> playingSet = new ArrayList<>(mPlayingSet);
            int setSize = playingSet.size();
            for (int i = 0; i < setSize; i++) {
                playingSet.get(i).cancel();
            }
            if (tmpListeners != null) {
                int size = tmpListeners.size();
                for (int i = 0; i < size; i++) {
                    tmpListeners.get(i).onAnimationEnd(this);
                }
            }
            mStarted = false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that ending a <code>AnimatorSet</code> also ends all of the animations that it is
     * responsible for.</p>
     */
    @Override
    public void end() {
        if (mShouldIgnoreEndWithoutStart && !isStarted()) {
            return;
        }
        mTerminated = true;
        if (isStarted()) {
            endRemainingAnimations();
        }
        if (mListeners != null) {
            ArrayList<AnimatorListener> tmpListeners =
                    (ArrayList<AnimatorListener>) mListeners.clone();
            for (int i = 0; i < tmpListeners.size(); i++) {
                tmpListeners.get(i).onAnimationEnd(this);
            }
        }
        mStarted = false;
    }

    /**
     * Iterate the animations that haven't finished or haven't started, and end them.
     */
    private void endRemainingAnimations() {
        ArrayList<Animator> remainingList = new ArrayList<Animator>(mNodes.size());
        remainingList.addAll(mPlayingSet);

        int index = 0;
        while (index < remainingList.size()) {
            Animator anim = remainingList.get(index);
            anim.end();
            index++;
            Node node = mNodeMap.get(anim);
            if (node.mChildNodes != null) {
                int childSize = node.mChildNodes.size();
                for (int i = 0; i < childSize; i++) {
                    Node child = node.mChildNodes.get(i);
                    if (child.mLatestParent != node) {
                        continue;
                    }
                    remainingList.add(child.mAnimation);
                }
            }
        }
    }


    /**
     * Returns true if any of the child animations of this AnimatorSet have been started and have
     * not yet ended. Child animations will not be started until the AnimatorSet has gone past
     * its initial delay set through {@link #setStartDelay(long)}.
     *
     * @return Whether this AnimatorSet has gone past the initial delay, and at least one child
     *         animation has been started and not yet ended.
     */
    @Override
    public boolean isRunning() {
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node != mRootNode && node.mAnimation.isStarted()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isStarted() {
        return mStarted;
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called.
     *
     * @return the number of milliseconds to delay running the animation
     */
    @Override
    public long getStartDelay() {
        return mStartDelay;
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called. Note that the start delay should always be non-negative. Any
     * negative start delay will be clamped to 0 on N and above.
     *
     * @param startDelay The amount of the delay, in milliseconds
     */
    @Override
    public void setStartDelay(long startDelay) {
        // Clamp start delay to non-negative range.
        if (startDelay < 0) {
            Log.w(TAG, "Start delay should always be non-negative");
            startDelay = 0;
        }
        long delta = startDelay - mStartDelay;
        if (delta == 0) {
            return;
        }
        mStartDelay = startDelay;
        if (mStartDelay > 0) {
            mReversible = false;
        }
        if (!mDependencyDirty) {
            // Dependency graph already constructed, update all the nodes' start/end time
            int size = mNodes.size();
            for (int i = 0; i < size; i++) {
                Node node = mNodes.get(i);
                if (node == mRootNode) {
                    node.mEndTime = mStartDelay;
                } else {
                    node.mStartTime = node.mStartTime == DURATION_INFINITE ?
                            DURATION_INFINITE : node.mStartTime + delta;
                    node.mEndTime = node.mEndTime == DURATION_INFINITE ?
                            DURATION_INFINITE : node.mEndTime + delta;
                }
            }
            // Update total duration, if necessary.
            if (mTotalDuration != DURATION_INFINITE) {
                mTotalDuration += delta;
            }
        }
    }

    /**
     * Gets the length of each of the child animations of this AnimatorSet. This value may
     * be less than 0, which indicates that no duration has been set on this AnimatorSet
     * and each of the child animations will use their own duration.
     *
     * @return The length of the animation, in milliseconds, of each of the child
     * animations of this AnimatorSet.
     */
    @Override
    public long getDuration() {
        return mDuration;
    }

    /**
     * Sets the length of each of the current child animations of this AnimatorSet. By default,
     * each child animation will use its own duration. If the duration is set on the AnimatorSet,
     * then each child animation inherits this duration.
     *
     * @param duration The length of the animation, in milliseconds, of each of the child
     * animations of this AnimatorSet.
     */
    @Override
    public AnimatorSet setDuration(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("duration must be a value of zero or greater");
        }
        mDependencyDirty = true;
        // Just record the value for now - it will be used later when the AnimatorSet starts
        mDuration = duration;
        return this;
    }

    @Override
    public void setupStartValues() {
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node != mRootNode) {
                node.mAnimation.setupStartValues();
            }
        }
    }

    @Override
    public void setupEndValues() {
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node != mRootNode) {
                node.mAnimation.setupEndValues();
            }
        }
    }

    @Override
    public void pause() {
        boolean previouslyPaused = mPaused;
        super.pause();
        if (!previouslyPaused && mPaused) {
            if (mDelayAnim.isStarted()) {
                // If delay hasn't passed, pause the start delay animator.
                mDelayAnim.pause();
            } else {
                int size = mNodes.size();
                for (int i = 0; i < size; i++) {
                    Node node = mNodes.get(i);
                    if (node != mRootNode) {
                        node.mAnimation.pause();
                    }
                }
            }
        }
    }

    @Override
    public void resume() {
        boolean previouslyPaused = mPaused;
        super.resume();
        if (previouslyPaused && !mPaused) {
            if (mDelayAnim.isStarted()) {
                // If start delay hasn't passed, resume the previously paused start delay animator
                mDelayAnim.resume();
            } else {
                int size = mNodes.size();
                for (int i = 0; i < size; i++) {
                    Node node = mNodes.get(i);
                    if (node != mRootNode) {
                        node.mAnimation.resume();
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starting this <code>AnimatorSet</code> will, in turn, start the animations for which
     * it is responsible. The details of when exactly those animations are started depends on
     * the dependency relationships that have been set up between the animations.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void start() {
        mTerminated = false;
        mStarted = true;
        mPaused = false;

        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            node.mEnded = false;
            node.mAnimation.setAllowRunningAsynchronously(false);
        }

        if (mInterpolator != null) {
            for (int i = 0; i < size; i++) {
                Node node = mNodes.get(i);
                node.mAnimation.setInterpolator(mInterpolator);
            }
        }

        updateAnimatorsDuration();
        createDependencyGraph();

        // Now that all dependencies are set up, start the animations that should be started.
        boolean setIsEmpty = false;
        if (mStartDelay > 0) {
            start(mRootNode);
        } else if (mNodes.size() > 1) {
            // No delay, but there are other animators in the set
            onChildAnimatorEnded(mDelayAnim);
        } else {
            // Set is empty, no delay, no other animation. Skip to end in this case
            setIsEmpty = true;
        }

        if (mListeners != null) {
            ArrayList<AnimatorListener> tmpListeners =
                    (ArrayList<AnimatorListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onAnimationStart(this);
            }
        }
        if (setIsEmpty) {
            // In the case of empty AnimatorSet, we will trigger the onAnimationEnd() right away.
            onChildAnimatorEnded(mDelayAnim);
        }
    }

    private void updateAnimatorsDuration() {
        if (mDuration >= 0) {
            // If the duration was set on this AnimatorSet, pass it along to all child animations
            int size = mNodes.size();
            for (int i = 0; i < size; i++) {
                Node node = mNodes.get(i);
                // TODO: don't set the duration of the timing-only nodes created by AnimatorSet to
                // insert "play-after" delays
                node.mAnimation.setDuration(mDuration);
            }
        }
        mDelayAnim.setDuration(mStartDelay);
    }

    void start(final Node node) {
        final Animator anim = node.mAnimation;
        mPlayingSet.add(anim);
        anim.addListener(mSetListener);
        anim.start();
    }

    @Override
    public AnimatorSet clone() {
        final AnimatorSet anim = (AnimatorSet) super.clone();
        /*
         * The basic clone() operation copies all items. This doesn't work very well for
         * AnimatorSet, because it will copy references that need to be recreated and state
         * that may not apply. What we need to do now is put the clone in an uninitialized
         * state, with fresh, empty data structures. Then we will build up the nodes list
         * manually, as we clone each Node (and its animation). The clone will then be sorted,
         * and will populate any appropriate lists, when it is started.
         */
        final int nodeCount = mNodes.size();
        anim.mTerminated = false;
        anim.mStarted = false;
        anim.mPlayingSet = new ArrayList<Animator>();
        anim.mNodeMap = new ArrayMap<Animator, Node>();
        anim.mNodes = new ArrayList<Node>(nodeCount);
        anim.mReversible = mReversible;
        anim.mSetListener = new AnimatorSetListener(anim);

        // Walk through the old nodes list, cloning each node and adding it to the new nodemap.
        // One problem is that the old node dependencies point to nodes in the old AnimatorSet.
        // We need to track the old/new nodes in order to reconstruct the dependencies in the clone.

        for (int n = 0; n < nodeCount; n++) {
            final Node node = mNodes.get(n);
            Node nodeClone = node.clone();
            node.mTmpClone = nodeClone;
            anim.mNodes.add(nodeClone);
            anim.mNodeMap.put(nodeClone.mAnimation, nodeClone);

            // clear out any listeners that were set up by the AnimatorSet
            final ArrayList<AnimatorListener> cloneListeners = nodeClone.mAnimation.getListeners();
            if (cloneListeners != null) {
                for (int i = cloneListeners.size() - 1; i >= 0; i--) {
                    final AnimatorListener listener = cloneListeners.get(i);
                    if (listener instanceof AnimatorSetListener) {
                        cloneListeners.remove(i);
                    }
                }
            }
        }

        anim.mRootNode = mRootNode.mTmpClone;
        anim.mDelayAnim = (ValueAnimator) anim.mRootNode.mAnimation;

        // Now that we've cloned all of the nodes, we're ready to walk through their
        // dependencies, mapping the old dependencies to the new nodes
        for (int i = 0; i < nodeCount; i++) {
            Node node = mNodes.get(i);
            // Update dependencies for node's clone
            node.mTmpClone.mLatestParent = node.mLatestParent == null ?
                    null : node.mLatestParent.mTmpClone;
            int size = node.mChildNodes == null ? 0 : node.mChildNodes.size();
            for (int j = 0; j < size; j++) {
                node.mTmpClone.mChildNodes.set(j, node.mChildNodes.get(j).mTmpClone);
            }
            size = node.mSiblings == null ? 0 : node.mSiblings.size();
            for (int j = 0; j < size; j++) {
                node.mTmpClone.mSiblings.set(j, node.mSiblings.get(j).mTmpClone);
            }
            size = node.mParents == null ? 0 : node.mParents.size();
            for (int j = 0; j < size; j++) {
                node.mTmpClone.mParents.set(j, node.mParents.get(j).mTmpClone);
            }
        }

        for (int n = 0; n < nodeCount; n++) {
            mNodes.get(n).mTmpClone = null;
        }
        return anim;
    }


    private static class AnimatorSetListener implements AnimatorListener {

        private AnimatorSet mAnimatorSet;

        AnimatorSetListener(AnimatorSet animatorSet) {
            mAnimatorSet = animatorSet;
        }

        public void onAnimationCancel(Animator animation) {

            if (!mAnimatorSet.mTerminated) {
                // Listeners are already notified of the AnimatorSet canceling in cancel().
                // The logic below only kicks in when animations end normally
                if (mAnimatorSet.mPlayingSet.size() == 0) {
                    ArrayList<AnimatorListener> listeners = mAnimatorSet.mListeners;
                    if (listeners != null) {
                        int numListeners = listeners.size();
                        for (int i = 0; i < numListeners; ++i) {
                            listeners.get(i).onAnimationCancel(mAnimatorSet);
                        }
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        public void onAnimationEnd(Animator animation) {
            animation.removeListener(this);
            mAnimatorSet.mPlayingSet.remove(animation);
            mAnimatorSet.onChildAnimatorEnded(animation);
        }

        // Nothing to do
        public void onAnimationRepeat(Animator animation) {
        }

        // Nothing to do
        public void onAnimationStart(Animator animation) {
        }

    }

    private void onChildAnimatorEnded(Animator animation) {
        Node animNode = mNodeMap.get(animation);
        animNode.mEnded = true;

        if (!mTerminated) {
            List<Node> children = animNode.mChildNodes;
            // Start children animations, if any.
            int childrenSize = children == null ? 0 : children.size();
            for (int i = 0; i < childrenSize; i++) {
                if (children.get(i).mLatestParent == animNode) {
                    start(children.get(i));
                }
            }
            // Listeners are already notified of the AnimatorSet ending in cancel() or
            // end(); the logic below only kicks in when animations end normally
            boolean allDone = true;
            // Traverse the tree and find if there's any unfinished node
            int size = mNodes.size();
            for (int i = 0; i < size; i++) {
                if (!mNodes.get(i).mEnded) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                // If this was the last child animation to end, then notify listeners that this
                // AnimatorSet has ended
                if (mListeners != null) {
                    ArrayList<AnimatorListener> tmpListeners =
                            (ArrayList<AnimatorListener>) mListeners.clone();
                    int numListeners = tmpListeners.size();
                    for (int i = 0; i < numListeners; ++i) {
                        tmpListeners.get(i).onAnimationEnd(this);
                    }
                }
                mStarted = false;
                mPaused = false;
            }
        }
    }

    /**
     * AnimatorSet is only reversible when the set contains no sequential animation, and no child
     * animators have a start delay.
     * @hide
     */
    @Override
    public boolean canReverse() {
        if (!mReversible)  {
            return false;
        }
        // Loop to make sure all the Nodes can reverse.
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (!node.mAnimation.canReverse() || node.mAnimation.getStartDelay() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @hide
     */
    @Override
    public void reverse() {
        if (canReverse()) {
            int size = mNodes.size();
            for (int i = 0; i < size; i++) {
                Node node = mNodes.get(i);
                node.mAnimation.reverse();
            }
        }
    }

    @Override
    public String toString() {
        String returnVal = "AnimatorSet@" + Integer.toHexString(hashCode()) + "{";
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            returnVal += "\n    " + node.mAnimation.toString();
        }
        return returnVal + "\n}";
    }

    private void printChildCount() {
        // Print out the child count through a level traverse.
        ArrayList<Node> list = new ArrayList<>(mNodes.size());
        list.add(mRootNode);
        Log.d(TAG, "Current tree: ");
        int index = 0;
        while (index < list.size()) {
            int listSize = list.size();
            StringBuilder builder = new StringBuilder();
            for (; index < listSize; index++) {
                Node node = list.get(index);
                int num = 0;
                if (node.mChildNodes != null) {
                    for (int i = 0; i < node.mChildNodes.size(); i++) {
                        Node child = node.mChildNodes.get(i);
                        if (child.mLatestParent == node) {
                            num++;
                            list.add(child);
                        }
                    }
                }
                builder.append(" ");
                builder.append(num);
            }
            Log.d(TAG, builder.toString());
        }
    }

    private void createDependencyGraph() {
        if (!mDependencyDirty) {
            // Check whether any duration of the child animations has changed
            boolean durationChanged = false;
            for (int i = 0; i < mNodes.size(); i++) {
                Animator anim = mNodes.get(i).mAnimation;
                if (mNodes.get(i).mTotalDuration != anim.getTotalDuration()) {
                    durationChanged = true;
                    break;
                }
            }
            if (!durationChanged) {
                return;
            }
        }

        mDependencyDirty = false;
        // Traverse all the siblings and make sure they have all the parents
        int size = mNodes.size();
        for (int i = 0; i < size; i++) {
            mNodes.get(i).mParentsAdded = false;
        }
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node.mParentsAdded) {
                continue;
            }

            node.mParentsAdded = true;
            if (node.mSiblings == null) {
                continue;
            }

            // Find all the siblings
            findSiblings(node, node.mSiblings);
            node.mSiblings.remove(node);

            // Get parents from all siblings
            int siblingSize = node.mSiblings.size();
            for (int j = 0; j < siblingSize; j++) {
                node.addParents(node.mSiblings.get(j).mParents);
            }

            // Now make sure all siblings share the same set of parents
            for (int j = 0; j < siblingSize; j++) {
                Node sibling = node.mSiblings.get(j);
                sibling.addParents(node.mParents);
                sibling.mParentsAdded = true;
            }
        }

        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            if (node != mRootNode && node.mParents == null) {
                node.addParent(mRootNode);
            }
        }

        // Do a DFS on the tree
        ArrayList<Node> visited = new ArrayList<Node>(mNodes.size());
        // Assign start/end time
        mRootNode.mStartTime = 0;
        mRootNode.mEndTime = mDelayAnim.getDuration();
        updatePlayTime(mRootNode, visited);

        long maxEndTime = 0;
        for (int i = 0; i < size; i++) {
            Node node = mNodes.get(i);
            node.mTotalDuration = node.mAnimation.getTotalDuration();
            if (node.mEndTime == DURATION_INFINITE) {
                maxEndTime = DURATION_INFINITE;
                break;
            } else {
                maxEndTime = node.mEndTime > maxEndTime ? node.mEndTime : maxEndTime;
            }
        }
        mTotalDuration = maxEndTime;
    }

    /**
     * Based on parent's start/end time, calculate children's start/end time. If cycle exists in
     * the graph, all the nodes on the cycle will be marked to start at {@link #DURATION_INFINITE},
     * meaning they will ever play.
     */
    private void updatePlayTime(Node parent,  ArrayList<Node> visited) {
        if (parent.mChildNodes == null) {
            if (parent == mRootNode) {
                // All the animators are in a cycle
                for (int i = 0; i < mNodes.size(); i++) {
                    Node node = mNodes.get(i);
                    if (node != mRootNode) {
                        node.mStartTime = DURATION_INFINITE;
                        node.mEndTime = DURATION_INFINITE;
                    }
                }
            }
            return;
        }

        visited.add(parent);
        int childrenSize = parent.mChildNodes.size();
        for (int i = 0; i < childrenSize; i++) {
            Node child = parent.mChildNodes.get(i);
            int index = visited.indexOf(child);
            if (index >= 0) {
                // Child has been visited, cycle found. Mark all the nodes in the cycle.
                for (int j = index; j < visited.size(); j++) {
                    visited.get(j).mLatestParent = null;
                    visited.get(j).mStartTime = DURATION_INFINITE;
                    visited.get(j).mEndTime = DURATION_INFINITE;
                }
                child.mStartTime = DURATION_INFINITE;
                child.mEndTime = DURATION_INFINITE;
                child.mLatestParent = null;
                Log.w(TAG, "Cycle found in AnimatorSet: " + this);
                continue;
            }

            if (child.mStartTime != DURATION_INFINITE) {
                if (parent.mEndTime == DURATION_INFINITE) {
                    child.mLatestParent = parent;
                    child.mStartTime = DURATION_INFINITE;
                    child.mEndTime = DURATION_INFINITE;
                } else {
                    if (parent.mEndTime >= child.mStartTime) {
                        child.mLatestParent = parent;
                        child.mStartTime = parent.mEndTime;
                    }

                    long duration = child.mAnimation.getTotalDuration();
                    child.mEndTime = duration == DURATION_INFINITE ?
                            DURATION_INFINITE : child.mStartTime + duration;
                }
            }
            updatePlayTime(child, visited);
        }
        visited.remove(parent);
    }

    // Recursively find all the siblings
    private void findSiblings(Node node, ArrayList<Node> siblings) {
        if (!siblings.contains(node)) {
            siblings.add(node);
            if (node.mSiblings == null) {
                return;
            }
            for (int i = 0; i < node.mSiblings.size(); i++) {
                findSiblings(node.mSiblings.get(i), siblings);
            }
        }
    }

    /**
     * @hide
     * TODO: For animatorSet defined in XML, we can use a flag to indicate what the play order
     * if defined (i.e. sequential or together), then we can use the flag instead of calculating
     * dynamically. Note that when AnimatorSet is empty this method returns true.
     * @return whether all the animators in the set are supposed to play together
     */
    public boolean shouldPlayTogether() {
        updateAnimatorsDuration();
        createDependencyGraph();
        // All the child nodes are set out to play right after the delay animation
        return mRootNode.mChildNodes == null || mRootNode.mChildNodes.size() == mNodes.size() - 1;
    }

    @Override
    public long getTotalDuration() {
        updateAnimatorsDuration();
        createDependencyGraph();
        return mTotalDuration;
    }

    private Node getNodeForAnimation(Animator anim) {
        Node node = mNodeMap.get(anim);
        if (node == null) {
            node = new Node(anim);
            mNodeMap.put(anim, node);
            mNodes.add(node);
        }
        return node;
    }

    /**
     * A Node is an embodiment of both the Animator that it wraps as well as
     * any dependencies that are associated with that Animation. This includes
     * both dependencies upon other nodes (in the dependencies list) as
     * well as dependencies of other nodes upon this (in the nodeDependents list).
     */
    private static class Node implements Cloneable {
        Animator mAnimation;

        /**
         * Child nodes are the nodes associated with animations that will be played immediately
         * after current node.
         */
        ArrayList<Node> mChildNodes = null;

        /**
         * Temporary field to hold the clone in AnimatorSet#clone. Cleaned after clone is complete
         */
        private Node mTmpClone = null;

        /**
         * Flag indicating whether the animation in this node is finished. This flag
         * is used by AnimatorSet to check, as each animation ends, whether all child animations
         * are mEnded and it's time to send out an end event for the entire AnimatorSet.
         */
        boolean mEnded = false;

        /**
         * Nodes with animations that are defined to play simultaneously with the animation
         * associated with this current node.
         */
        ArrayList<Node> mSiblings;

        /**
         * Parent nodes are the nodes with animations preceding current node's animation. Parent
         * nodes here are derived from user defined animation sequence.
         */
        ArrayList<Node> mParents;

        /**
         * Latest parent is the parent node associated with a animation that finishes after all
         * the other parents' animations.
         */
        Node mLatestParent = null;

        boolean mParentsAdded = false;
        long mStartTime = 0;
        long mEndTime = 0;
        long mTotalDuration = 0;

        /**
         * Constructs the Node with the animation that it encapsulates. A Node has no
         * dependencies by default; dependencies are added via the addDependency()
         * method.
         *
         * @param animation The animation that the Node encapsulates.
         */
        public Node(Animator animation) {
            this.mAnimation = animation;
        }

        @Override
        public Node clone() {
            try {
                Node node = (Node) super.clone();
                node.mAnimation = mAnimation.clone();
                if (mChildNodes != null) {
                    node.mChildNodes = new ArrayList<>(mChildNodes);
                }
                if (mSiblings != null) {
                    node.mSiblings = new ArrayList<>(mSiblings);
                }
                if (mParents != null) {
                    node.mParents = new ArrayList<>(mParents);
                }
                node.mEnded = false;
                return node;
            } catch (CloneNotSupportedException e) {
               throw new AssertionError();
            }
        }

        void addChild(Node node) {
            if (mChildNodes == null) {
                mChildNodes = new ArrayList<>();
            }
            if (!mChildNodes.contains(node)) {
                mChildNodes.add(node);
                node.addParent(this);
            }
        }

        public void addSibling(Node node) {
            if (mSiblings == null) {
                mSiblings = new ArrayList<Node>();
            }
            if (!mSiblings.contains(node)) {
                mSiblings.add(node);
                node.addSibling(this);
            }
        }

        public void addParent(Node node) {
            if (mParents == null) {
                mParents =  new ArrayList<Node>();
            }
            if (!mParents.contains(node)) {
                mParents.add(node);
                node.addChild(this);
            }
        }

        public void addParents(ArrayList<Node> parents) {
            if (parents == null) {
                return;
            }
            int size = parents.size();
            for (int i = 0; i < size; i++) {
                addParent(parents.get(i));
            }
        }
    }

    /**
     * The <code>Builder</code> object is a utility class to facilitate adding animations to a
     * <code>AnimatorSet</code> along with the relationships between the various animations. The
     * intention of the <code>Builder</code> methods, along with the {@link
     * AnimatorSet#play(Animator) play()} method of <code>AnimatorSet</code> is to make it possible
     * to express the dependency relationships of animations in a natural way. Developers can also
     * use the {@link AnimatorSet#playTogether(Animator[]) playTogether()} and {@link
     * AnimatorSet#playSequentially(Animator[]) playSequentially()} methods if these suit the need,
     * but it might be easier in some situations to express the AnimatorSet of animations in pairs.
     * <p/>
     * <p>The <code>Builder</code> object cannot be constructed directly, but is rather constructed
     * internally via a call to {@link AnimatorSet#play(Animator)}.</p>
     * <p/>
     * <p>For example, this sets up a AnimatorSet to play anim1 and anim2 at the same time, anim3 to
     * play when anim2 finishes, and anim4 to play when anim3 finishes:</p>
     * <pre>
     *     AnimatorSet s = new AnimatorSet();
     *     s.play(anim1).with(anim2);
     *     s.play(anim2).before(anim3);
     *     s.play(anim4).after(anim3);
     * </pre>
     * <p/>
     * <p>Note in the example that both {@link Builder#before(Animator)} and {@link
     * Builder#after(Animator)} are used. These are just different ways of expressing the same
     * relationship and are provided to make it easier to say things in a way that is more natural,
     * depending on the situation.</p>
     * <p/>
     * <p>It is possible to make several calls into the same <code>Builder</code> object to express
     * multiple relationships. However, note that it is only the animation passed into the initial
     * {@link AnimatorSet#play(Animator)} method that is the dependency in any of the successive
     * calls to the <code>Builder</code> object. For example, the following code starts both anim2
     * and anim3 when anim1 ends; there is no direct dependency relationship between anim2 and
     * anim3:
     * <pre>
     *   AnimatorSet s = new AnimatorSet();
     *   s.play(anim1).before(anim2).before(anim3);
     * </pre>
     * If the desired result is to play anim1 then anim2 then anim3, this code expresses the
     * relationship correctly:</p>
     * <pre>
     *   AnimatorSet s = new AnimatorSet();
     *   s.play(anim1).before(anim2);
     *   s.play(anim2).before(anim3);
     * </pre>
     * <p/>
     * <p>Note that it is possible to express relationships that cannot be resolved and will not
     * result in sensible results. For example, <code>play(anim1).after(anim1)</code> makes no
     * sense. In general, circular dependencies like this one (or more indirect ones where a depends
     * on b, which depends on c, which depends on a) should be avoided. Only create AnimatorSets
     * that can boil down to a simple, one-way relationship of animations starting with, before, and
     * after other, different, animations.</p>
     */
    public class Builder {

        /**
         * This tracks the current node being processed. It is supplied to the play() method
         * of AnimatorSet and passed into the constructor of Builder.
         */
        private Node mCurrentNode;

        /**
         * package-private constructor. Builders are only constructed by AnimatorSet, when the
         * play() method is called.
         *
         * @param anim The animation that is the dependency for the other animations passed into
         * the other methods of this Builder object.
         */
        Builder(Animator anim) {
            mDependencyDirty = true;
            mCurrentNode = getNodeForAnimation(anim);
        }

        /**
         * Sets up the given animation to play at the same time as the animation supplied in the
         * {@link AnimatorSet#play(Animator)} call that created this <code>Builder</code> object.
         *
         * @param anim The animation that will play when the animation supplied to the
         * {@link AnimatorSet#play(Animator)} method starts.
         */
        public Builder with(Animator anim) {
            Node node = getNodeForAnimation(anim);
            mCurrentNode.addSibling(node);
            return this;
        }

        /**
         * Sets up the given animation to play when the animation supplied in the
         * {@link AnimatorSet#play(Animator)} call that created this <code>Builder</code> object
         * ends.
         *
         * @param anim The animation that will play when the animation supplied to the
         * {@link AnimatorSet#play(Animator)} method ends.
         */
        public Builder before(Animator anim) {
            mReversible = false;
            Node node = getNodeForAnimation(anim);
            mCurrentNode.addChild(node);
            return this;
        }

        /**
         * Sets up the given animation to play when the animation supplied in the
         * {@link AnimatorSet#play(Animator)} call that created this <code>Builder</code> object
         * to start when the animation supplied in this method call ends.
         *
         * @param anim The animation whose end will cause the animation supplied to the
         * {@link AnimatorSet#play(Animator)} method to play.
         */
        public Builder after(Animator anim) {
            mReversible = false;
            Node node = getNodeForAnimation(anim);
            mCurrentNode.addParent(node);
            return this;
        }

        /**
         * Sets up the animation supplied in the
         * {@link AnimatorSet#play(Animator)} call that created this <code>Builder</code> object
         * to play when the given amount of time elapses.
         *
         * @param delay The number of milliseconds that should elapse before the
         * animation starts.
         */
        public Builder after(long delay) {
            // setup dummy ValueAnimator just to run the clock
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(delay);
            after(anim);
            return this;
        }

    }

}
