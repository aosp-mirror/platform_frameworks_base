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

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This class plays a set of {@link Animatable} objects in the specified order. Animations
 * can be set up to play together, in sequence, or after a specified delay.
 *
 * <p>There are two different approaches to adding animations to a <code>Sequencer</code>:
 * either the {@link Sequencer#playTogether(Animatable[]) playTogether()} or
 * {@link Sequencer#playSequentially(Animatable[]) playSequentially()} methods can be called to add
 * a set of animations all at once, or the {@link Sequencer#play(Animatable)} can be
 * used in conjunction with methods in the {@link android.animation.Sequencer.Builder Builder}
 * class to add animations
 * one by one.</p>
 *
 * <p>It is possible to set up a <code>Sequencer</code> with circular dependencies between
 * its animations. For example, an animation a1 could be set up to start before animation a2, a2
 * before a3, and a3 before a1. The results of this configuration are undefined, but will typically
 * result in none of the affected animations being played. Because of this (and because
 * circular dependencies do not make logical sense anyway), circular dependencies
 * should be avoided, and the dependency flow of animations should only be in one direction.
 */
public final class Sequencer extends Animatable {

    /**
     * Internal variables
     * NOTE: This object implements the clone() method, making a deep copy of any referenced
     * objects. As other non-trivial fields are added to this class, make sure to add logic
     * to clone() to make deep copies of them.
     */

    /**
     * Tracks animations currently being played, so that we know what to
     * cancel or end when cancel() or end() is called on this Sequencer
     */
    private ArrayList<Animatable> mPlayingSet = new ArrayList<Animatable>();

    /**
     * Contains all nodes, mapped to their respective Animatables. When new
     * dependency information is added for an Animatable, we want to add it
     * to a single node representing that Animatable, not create a new Node
     * if one already exists.
     */
    private HashMap<Animatable, Node> mNodeMap = new HashMap<Animatable, Node>();

    /**
     * Set of all nodes created for this Sequencer. This list is used upon
     * starting the sequencer, and the nodes are placed in sorted order into the
     * sortedNodes collection.
     */
    private ArrayList<Node> mNodes = new ArrayList<Node>();

    /**
     * The sorted list of nodes. This is the order in which the animations will
     * be played. The details about when exactly they will be played depend
     * on the dependency relationships of the nodes.
     */
    private ArrayList<Node> mSortedNodes = new ArrayList<Node>();

    /**
     * Flag indicating whether the nodes should be sorted prior to playing. This
     * flag allows us to cache the previous sorted nodes so that if the sequence
     * is replayed with no changes, it does not have to re-sort the nodes again.
     */
    private boolean mNeedsSort = true;

    private SequencerAnimatableListener mSequenceListener = null;

    /**
     * Flag indicating that the Sequencer has been canceled (by calling cancel() or end()).
     * This flag is used to avoid starting other animations when currently-playing
     * child animations of this Sequencer end.
     */
    boolean mCanceled = false;

    /**
     * Sets up this Sequencer to play all of the supplied animations at the same time.
     *
     * @param sequenceItems The animations that will be started simultaneously.
     */
    public void playTogether(Animatable... sequenceItems) {
        if (sequenceItems != null) {
            mNeedsSort = true;
            Builder builder = play(sequenceItems[0]);
            for (int i = 1; i < sequenceItems.length; ++i) {
                builder.with(sequenceItems[i]);
            }
        }
    }

    /**
     * Sets up this Sequencer to play each of the supplied animations when the
     * previous animation ends.
     *
     * @param sequenceItems The aniamtions that will be started one after another.
     */
    public void playSequentially(Animatable... sequenceItems) {
        if (sequenceItems != null) {
            mNeedsSort = true;
            if (sequenceItems.length == 1) {
                play(sequenceItems[0]);
            } else {
                for (int i = 0; i < sequenceItems.length - 1; ++i) {
                    play(sequenceItems[i]).before(sequenceItems[i+1]);
                }
            }
        }
    }

    /**
     * Returns the current list of child Animatable objects controlled by this
     * Sequencer. This is a copy of the internal list; modifications to the returned list
     * will not affect the Sequencer, although changes to the underlying Animatable objects
     * will affect those objects being managed by the Sequencer.
     *
     * @return ArrayList<Animatable> The list of child animations of this Sequencer.
     */
    public ArrayList<Animatable> getChildAnimations() {
        ArrayList<Animatable> childList = new ArrayList<Animatable>();
        for (Node node : mNodes) {
            childList.add(node.animation);
        }
        return childList;
    }

    /**
     * Sets the target object for all current {@link #getChildAnimations() child animations}
     * of this Sequencer that take targets ({@link android.animation.PropertyAnimator} and
     * Sequencer).
     *
     * @param target The object being animated
     */
    public void setTarget(Object target) {
        for (Node node : mNodes) {
            Animatable animation = node.animation;
            if (animation instanceof Sequencer) {
                ((Sequencer)animation).setTarget(target);
            } else if (animation instanceof PropertyAnimator) {
                ((PropertyAnimator)animation).setTarget(target);
            }
        }
    }

    /**
     * This method creates a <code>Builder</code> object, which is used to
     * set up playing constraints. This initial <code>play()</code> method
     * tells the <code>Builder</code> the animation that is the dependency for
     * the succeeding commands to the <code>Builder</code>. For example,
     * calling <code>play(a1).with(a2)</code> sets up the Sequence to play
     * <code>a1</code> and <code>a2</code> at the same time,
     * <code>play(a1).before(a2)</code> sets up the Sequence to play
     * <code>a1</code> first, followed by <code>a2</code>, and
     * <code>play(a1).after(a2)</code> sets up the Sequence to play
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
     * @return Builder The object that constructs the sequence based on the dependencies
     * outlined in the calls to <code>play</code> and the other methods in the
     * <code>Builder</code object.
     */
    public Builder play(Animatable anim) {
        if (anim != null) {
            mNeedsSort = true;
            return new Builder(anim);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that canceling a <code>Sequencer</code> also cancels all of the animations that it is
     * responsible for.</p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public void cancel() {
        mCanceled = true;
        if (mListeners != null) {
            ArrayList<AnimatableListener> tmpListeners =
                    (ArrayList<AnimatableListener>) mListeners.clone();
            for (AnimatableListener listener : tmpListeners) {
                listener.onAnimationCancel(this);
            }
        }
        if (mSortedNodes.size() > 0) {
            for (Node node : mSortedNodes) {
                node.animation.cancel();
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that ending a <code>Sequencer</code> also ends all of the animations that it is
     * responsible for.</p>
     */
    @Override
    public void end() {
        mCanceled = true;
        if (mSortedNodes.size() != mNodes.size()) {
            // hasn't been started yet - sort the nodes now, then end them
            sortNodes();
        }
        if (mSortedNodes.size() > 0) {
            for (Node node : mSortedNodes) {
                node.animation.end();
            }
        }
    }

    /**
     * Returns true if any of the child animations of this Sequencer have been started and have not
     * yet ended.
     * @return Whether this Sequencer has been started and has not yet ended.
     */
    @Override
    public boolean isRunning() {
        for (Node node : mNodes) {
            if (node.animation.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starting this <code>Sequencer</code> will, in turn, start the animations for which
     * it is responsible. The details of when exactly those animations are started depends on
     * the dependency relationships that have been set up between the animations.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void start() {
        mCanceled = false;

        // First, sort the nodes (if necessary). This will ensure that sortedNodes
        // contains the animation nodes in the correct order.
        sortNodes();

        // nodesToStart holds the list of nodes to be started immediately. We don't want to
        // start the animations in the loop directly because we first need to set up
        // dependencies on all of the nodes. For example, we don't want to start an animation
        // when some other animation also wants to start when the first animation begins.
        ArrayList<Node> nodesToStart = new ArrayList<Node>();
        for (Node node : mSortedNodes) {
            if (mSequenceListener == null) {
                mSequenceListener = new SequencerAnimatableListener(this);
            }
            if (node.dependencies == null || node.dependencies.size() == 0) {
                nodesToStart.add(node);
            } else {
                for (Dependency dependency : node.dependencies) {
                    dependency.node.animation.addListener(
                            new DependencyListener(this, node, dependency.rule));
                }
                node.tmpDependencies = (ArrayList<Dependency>) node.dependencies.clone();
            }
            node.animation.addListener(mSequenceListener);
        }
        // Now that all dependencies are set up, start the animations that should be started.
        for (Node node : nodesToStart) {
            node.animation.start();
            mPlayingSet.add(node.animation);
        }
        if (mListeners != null) {
            ArrayList<AnimatableListener> tmpListeners =
                    (ArrayList<AnimatableListener>) mListeners.clone();
            for (AnimatableListener listener : tmpListeners) {
                listener.onAnimationStart(this);
            }
        }
    }

    @Override
    public Sequencer clone() throws CloneNotSupportedException {
        final Sequencer anim = (Sequencer) super.clone();
        /*
         * The basic clone() operation copies all items. This doesn't work very well for
         * Sequencer, because it will copy references that need to be recreated and state
         * that may not apply. What we need to do now is put the clone in an uninitialized
         * state, with fresh, empty data structures. Then we will build up the nodes list
         * manually, as we clone each Node (and its animation). The clone will then be sorted,
         * and will populate any appropriate lists, when it is started.
         */
        anim.mNeedsSort = true;
        anim.mCanceled = false;
        anim.mPlayingSet = new ArrayList<Animatable>();
        anim.mNodeMap = new HashMap<Animatable, Node>();
        anim.mNodes = new ArrayList<Node>();
        anim.mSortedNodes = new ArrayList<Node>();

        // Walk through the old nodes list, cloning each node and adding it to the new nodemap.
        // One problem is that the old node dependencies point to nodes in the old sequencer.
        // We need to track the old/new nodes in order to reconstruct the dependencies in the clone.
        HashMap<Node, Node> nodeCloneMap = new HashMap<Node, Node>(); // <old, new>
        for (Node node : mNodes) {
            Node nodeClone = node.clone();
            nodeCloneMap.put(node, nodeClone);
            anim.mNodes.add(nodeClone);
            anim.mNodeMap.put(nodeClone.animation, nodeClone);
            // Clear out the dependencies in the clone; we'll set these up manually later
            nodeClone.dependencies = null;
            nodeClone.tmpDependencies = null;
            nodeClone.nodeDependents = null;
            nodeClone.nodeDependencies = null;
            // clear out any listeners that were set up by the sequencer; these will
            // be set up when the clone's nodes are sorted
            ArrayList<AnimatableListener> cloneListeners = nodeClone.animation.getListeners();
            if (cloneListeners != null) {
                ArrayList<AnimatableListener> listenersToRemove = null;
                for (AnimatableListener listener : cloneListeners) {
                    if (listener instanceof SequencerAnimatableListener) {
                        if (listenersToRemove == null) {
                            listenersToRemove = new ArrayList<AnimatableListener>();
                        }
                        listenersToRemove.add(listener);
                    }
                }
                if (listenersToRemove != null) {
                    for (AnimatableListener listener : listenersToRemove) {
                        cloneListeners.remove(listener);
                    }
                }
            }
        }
        // Now that we've cloned all of the nodes, we're ready to walk through their
        // dependencies, mapping the old dependencies to the new nodes
        for (Node node : mNodes) {
            Node nodeClone = nodeCloneMap.get(node);
            if (node.dependencies != null) {
                for (Dependency dependency : node.dependencies) {
                    Node clonedDependencyNode = nodeCloneMap.get(dependency.node);
                    Dependency cloneDependency = new Dependency(clonedDependencyNode,
                            dependency.rule);
                    nodeClone.addDependency(cloneDependency);
                }
            }
        }

        return anim;
    }

    /**
     * This class is the mechanism by which animations are started based on events in other
     * animations. If an animation has multiple dependencies on other animations, then
     * all dependencies must be satisfied before the animation is started.
     */
    private static class DependencyListener implements AnimatableListener {

        private Sequencer mSequencer;

        // The node upon which the dependency is based.
        private Node mNode;

        // The Dependency rule (WITH or AFTER) that the listener should wait for on
        // the node
        private int mRule;

        public DependencyListener(Sequencer sequencer, Node node, int rule) {
            this.mSequencer = sequencer;
            this.mNode = node;
            this.mRule = rule;
        }

        /**
         * Ignore cancel events for now. We may want to handle this eventually,
         * to prevent follow-on animations from running when some dependency
         * animation is canceled.
         */
        public void onAnimationCancel(Animatable animation) {
        }

        /**
         * An end event is received - see if this is an event we are listening for
         */
        public void onAnimationEnd(Animatable animation) {
            if (mRule == Dependency.AFTER) {
                startIfReady(animation);
            }
        }

        /**
         * Ignore repeat events for now
         */
        public void onAnimationRepeat(Animatable animation) {
        }

        /**
         * A start event is received - see if this is an event we are listening for
         */
        public void onAnimationStart(Animatable animation) {
            if (mRule == Dependency.WITH) {
                startIfReady(animation);
            }
        }

        /**
         * Check whether the event received is one that the node was waiting for.
         * If so, mark it as complete and see whether it's time to start
         * the animation.
         * @param dependencyAnimation the animation that sent the event.
         */
        private void startIfReady(Animatable dependencyAnimation) {
            if (mSequencer.mCanceled) {
                // if the parent Sequencer was canceled, then don't start any dependent anims
                return;
            }
            Dependency dependencyToRemove = null;
            for (Dependency dependency : mNode.tmpDependencies) {
                if (dependency.rule == mRule &&
                        dependency.node.animation == dependencyAnimation) {
                    // rule fired - remove the dependency and listener and check to
                    // see whether it's time to start the animation
                    dependencyToRemove = dependency;
                    dependencyAnimation.removeListener(this);
                    break;
                }
            }
            mNode.tmpDependencies.remove(dependencyToRemove);
            if (mNode.tmpDependencies.size() == 0) {
                // all dependencies satisfied: start the animation
                mNode.animation.start();
                mSequencer.mPlayingSet.add(mNode.animation);
            }
        }

    }

    private class SequencerAnimatableListener implements AnimatableListener {

        private Sequencer mSequencer;

        SequencerAnimatableListener(Sequencer sequencer) {
            mSequencer = sequencer;
        }

        public void onAnimationCancel(Animatable animation) {
            if (mPlayingSet.size() == 0) {
                if (mListeners != null) {
                    for (AnimatableListener listener : mListeners) {
                        listener.onAnimationCancel(mSequencer);
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        public void onAnimationEnd(Animatable animation) {
            animation.removeListener(this);
            mPlayingSet.remove(animation);
            ArrayList<Node> sortedNodes = mSequencer.mSortedNodes;
            boolean allDone = true;
            for (Node node : sortedNodes) {
                if (node.animation.isRunning()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                // If this was the last child animation to end, then notify listeners that this
                // sequencer has ended
                if (mListeners != null) {
                    ArrayList<AnimatableListener> tmpListeners =
                            (ArrayList<AnimatableListener>) mListeners.clone();
                    for (AnimatableListener listener : tmpListeners) {
                        listener.onAnimationEnd(mSequencer);
                    }
                }
            }
        }

        // Nothing to do
        public void onAnimationRepeat(Animatable animation) {
        }

        // Nothing to do
        public void onAnimationStart(Animatable animation) {
        }

    }

    /**
     * This method sorts the current set of nodes, if needed. The sort is a simple
     * DependencyGraph sort, which goes like this:
     * - All nodes without dependencies become 'roots'
     * - while roots list is not null
     * -   for each root r
     * -     add r to sorted list
     * -     remove r as a dependency from any other node
     * -   any nodes with no dependencies are added to the roots list
     */
    private void sortNodes() {
        if (mNeedsSort) {
            mSortedNodes.clear();
            ArrayList<Node> roots = new ArrayList<Node>();
            for (Node node : mNodes) {
                if (node.dependencies == null || node.dependencies.size() == 0) {
                    roots.add(node);
                }
            }
            ArrayList<Node> tmpRoots = new ArrayList<Node>();
            while (roots.size() > 0) {
                for (Node root : roots) {
                    mSortedNodes.add(root);
                    if (root.nodeDependents != null) {
                        for (Node node : root.nodeDependents) {
                            node.nodeDependencies.remove(root);
                            if (node.nodeDependencies.size() == 0) {
                                tmpRoots.add(node);
                            }
                        }
                    }
                }
                roots.clear();
                roots.addAll(tmpRoots);
                tmpRoots.clear();
            }
            mNeedsSort = false;
            if (mSortedNodes.size() != mNodes.size()) {
                throw new IllegalStateException("Circular dependencies cannot exist"
                        + " in Sequencer");
            }
        } else {
            // Doesn't need sorting, but still need to add in the nodeDependencies list
            // because these get removed as the event listeners fire and the dependencies
            // are satisfied
            for (Node node : mNodes) {
                if (node.dependencies != null && node.dependencies.size() > 0) {
                    for (Dependency dependency : node.dependencies) {
                        if (node.nodeDependencies == null) {
                            node.nodeDependencies = new ArrayList<Node>();
                        }
                        if (!node.nodeDependencies.contains(dependency.node)) {
                            node.nodeDependencies.add(dependency.node);
                        }
                    }
                }
            }
        }
    }

    /**
     * Dependency holds information about the node that some other node is
     * dependent upon and the nature of that dependency.
     *
     */
    private static class Dependency {
        static final int WITH = 0; // dependent node must start with this dependency node
        static final int AFTER = 1; // dependent node must start when this dependency node finishes

        // The node that the other node with this Dependency is dependent upon
        public Node node;

        // The nature of the dependency (WITH or AFTER)
        public int rule;

        public Dependency(Node node, int rule) {
            this.node = node;
            this.rule = rule;
        }
    }

    /**
     * A Node is an embodiment of both the Animatable that it wraps as well as
     * any dependencies that are associated with that Animation. This includes
     * both dependencies upon other nodes (in the dependencies list) as
     * well as dependencies of other nodes upon this (in the nodeDependents list).
     */
    private static class Node implements Cloneable {
        public Animatable animation;

        /**
         *  These are the dependencies that this node's animation has on other
         *  nodes. For example, if this node's animation should begin with some
         *  other animation ends, then there will be an item in this node's
         *  dependencies list for that other animation's node.
         */
        public ArrayList<Dependency> dependencies = null;

        /**
         * tmpDependencies is a runtime detail. We use the dependencies list for sorting.
         * But we also use the list to keep track of when multiple dependencies are satisfied,
         * but removing each dependency as it is satisfied. We do not want to remove
         * the dependency itself from the list, because we need to retain that information
         * if the sequencer is launched in the future. So we create a copy of the dependency
         * list when the sequencer starts and use this tmpDependencies list to track the
         * list of satisfied dependencies.
         */
        public ArrayList<Dependency> tmpDependencies = null;

        /**
         * nodeDependencies is just a list of the nodes that this Node is dependent upon.
         * This information is used in sortNodes(), to determine when a node is a root.
         */
        public ArrayList<Node> nodeDependencies = null;

        /**
         * nodeDepdendents is the list of nodes that have this node as a dependency. This
         * is a utility field used in sortNodes to facilitate removing this node as a
         * dependency when it is a root node.
         */
        public ArrayList<Node> nodeDependents = null;

        /**
         * Constructs the Node with the animation that it encapsulates. A Node has no
         * dependencies by default; dependencies are added via the addDependency()
         * method.
         *
         * @param animation The animation that the Node encapsulates.
         */
        public Node(Animatable animation) {
            this.animation = animation;
        }

        /**
         * Add a dependency to this Node. The dependency includes information about the
         * node that this node is dependency upon and the nature of the dependency.
         * @param dependency
         */
        public void addDependency(Dependency dependency) {
            if (dependencies == null) {
                dependencies = new ArrayList<Dependency>();
                nodeDependencies = new ArrayList<Node>();
            }
            dependencies.add(dependency);
            if (!nodeDependencies.contains(dependency.node)) {
                nodeDependencies.add(dependency.node);
            }
            Node dependencyNode = dependency.node;
            if (dependencyNode.nodeDependents == null) {
                dependencyNode.nodeDependents = new ArrayList<Node>();
            }
            dependencyNode.nodeDependents.add(this);
        }

        @Override
        public Node clone() throws CloneNotSupportedException {
            Node node = (Node) super.clone();
            node.animation = (Animatable) animation.clone();
            return node;
        }
    }

    /**
     * The <code>Builder</code> object is a utility class to facilitate adding animations to a
     * <code>Sequencer</code> along with the relationships between the various animations. The
     * intention of the <code>Builder</code> methods, along with the {@link
     * Sequencer#play(Animatable) play()} method of <code>Sequencer</code> is to make it possible to
     * express the dependency relationships of animations in a natural way. Developers can also use
     * the {@link Sequencer#playTogether(Animatable[]) playTogether()} and {@link
     * Sequencer#playSequentially(Animatable[]) playSequentially()} methods if these suit the need,
     * but it might be easier in some situations to express the sequence of animations in pairs.
     * <p/>
     * <p>The <code>Builder</code> object cannot be constructed directly, but is rather constructed
     * internally via a call to {@link Sequencer#play(Animatable)}.</p>
     * <p/>
     * <p>For example, this sets up a Sequencer to play anim1 and anim2 at the same time, anim3 to
     * play when anim2 finishes, and anim4 to play when anim3 finishes:</p>
     * <pre>
     *     Sequencer s = new Sequencer();
     *     s.play(anim1).with(anim2);
     *     s.play(anim2).before(anim3);
     *     s.play(anim4).after(anim3);
     * </pre>
     * <p/>
     * <p>Note in the example that both {@link Builder#before(Animatable)} and {@link
     * Builder#after(Animatable)} are used. These are just different ways of expressing the same
     * relationship and are provided to make it easier to say things in a way that is more natural,
     * depending on the situation.</p>
     * <p/>
     * <p>It is possible to make several calls into the same <code>Builder</code> object to express
     * multiple relationships. However, note that it is only the animation passed into the initial
     * {@link Sequencer#play(Animatable)} method that is the dependency in any of the successive
     * calls to the <code>Builder</code> object. For example, the following code starts both anim2
     * and anim3 when anim1 ends; there is no direct dependency relationship between anim2 and
     * anim3:
     * <pre>
     *   Sequencer s = new Sequencer();
     *   s.play(anim1).before(anim2).before(anim3);
     * </pre>
     * If the desired result is to play anim1 then anim2 then anim3, this code expresses the
     * relationship correctly:</p>
     * <pre>
     *   Sequencer s = new Sequencer();
     *   s.play(anim1).before(anim2);
     *   s.play(anim2).before(anim3);
     * </pre>
     * <p/>
     * <p>Note that it is possible to express relationships that cannot be resolved and will not
     * result in sensible results. For example, <code>play(anim1).after(anim1)</code> makes no
     * sense. In general, circular dependencies like this one (or more indirect ones where a depends
     * on b, which depends on c, which depends on a) should be avoided. Only create sequences that
     * can boil down to a simple, one-way relationship of animations starting with, before, and
     * after other, different, animations.</p>
     */
    public class Builder {

        /**
         * This tracks the current node being processed. It is supplied to the play() method
         * of Sequencer and passed into the constructor of Builder.
         */
        private Node mCurrentNode;

        /**
         * package-private constructor. Builders are only constructed by Sequencer, when the
         * play() method is called.
         *
         * @param anim The animation that is the dependency for the other animations passed into
         * the other methods of this Builder object.
         */
        Builder(Animatable anim) {
            mCurrentNode = mNodeMap.get(anim);
            if (mCurrentNode == null) {
                mCurrentNode = new Node(anim);
                mNodeMap.put(anim, mCurrentNode);
                mNodes.add(mCurrentNode);
            }
        }

        /**
         * Sets up the given animation to play at the same time as the animation supplied in the
         * {@link Sequencer#play(Animatable)} call that created this <code>Builder</code> object.
         *
         * @param anim The animation that will play when the animation supplied to the
         * {@link Sequencer#play(Animatable)} method starts.
         */
        public void with(Animatable anim) {
            Node node = mNodeMap.get(anim);
            if (node == null) {
                node = new Node(anim);
                mNodeMap.put(anim, node);
                mNodes.add(node);
            }
            Dependency dependency = new Dependency(mCurrentNode, Dependency.WITH);
            node.addDependency(dependency);
        }

        /**
         * Sets up the given animation to play when the animation supplied in the
         * {@link Sequencer#play(Animatable)} call that created this <code>Builder</code> object
         * ends.
         *
         * @param anim The animation that will play when the animation supplied to the
         * {@link Sequencer#play(Animatable)} method ends.
         */
        public void before(Animatable anim) {
            Node node = mNodeMap.get(anim);
            if (node == null) {
                node = new Node(anim);
                mNodeMap.put(anim, node);
                mNodes.add(node);
            }
            Dependency dependency = new Dependency(mCurrentNode, Dependency.AFTER);
            node.addDependency(dependency);
        }

        /**
         * Sets up the given animation to play when the animation supplied in the
         * {@link Sequencer#play(Animatable)} call that created this <code>Builder</code> object
         * to start when the animation supplied in this method call ends.
         *
         * @param anim The animation whose end will cause the animation supplied to the
         * {@link Sequencer#play(Animatable)} method to play.
         */
        public void after(Animatable anim) {
            Node node = mNodeMap.get(anim);
            if (node == null) {
                node = new Node(anim);
                mNodeMap.put(anim, node);
                mNodes.add(node);
            }
            Dependency dependency = new Dependency(node, Dependency.AFTER);
            mCurrentNode.addDependency(dependency);
        }

        /**
         * Sets up the animation supplied in the
         * {@link Sequencer#play(Animatable)} call that created this <code>Builder</code> object
         * to play when the given amount of time elapses.
         *
         * @param delay The number of milliseconds that should elapse before the
         * animation starts.
         */
        public void after(long delay) {
            // setup dummy Animator just to run the clock
            after(new Animator(delay, 0f, 1f));
        }

    }

}
