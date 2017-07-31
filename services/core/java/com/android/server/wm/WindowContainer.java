/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;

import android.annotation.CallSuper;
import android.content.res.Configuration;
import android.util.Pools;

import com.android.internal.util.ToBooleanFunction;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Defines common functionality for classes that can hold windows directly or through their
 * children in a hierarchy form.
 * The test class is {@link WindowContainerTests} which must be kept up-to-date and ran anytime
 * changes are made to this class.
 */
class WindowContainer<E extends WindowContainer> implements Comparable<WindowContainer> {

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;

    /**
     * The parent of this window container.
     * For removing or setting new parent {@link #setParent} should be used, because it also
     * performs configuration updates based on new parent's settings.
     */
    private WindowContainer mParent = null;

    // List of children for this window container. List is in z-order as the children appear on
    // screen with the top-most window container at the tail of the list.
    protected final WindowList<E> mChildren = new WindowList<E>();

    /** Contains override configuration settings applied to this window container. */
    private Configuration mOverrideConfiguration = new Configuration();

    /**
     * Contains full configuration applied to this window container. Corresponds to full parent's
     * config with applied {@link #mOverrideConfiguration}.
     */
    private Configuration mFullConfiguration = new Configuration();

    /**
     * Contains merged override configuration settings from the top of the hierarchy down to this
     * particular instance. It is different from {@link #mFullConfiguration} because it starts from
     * topmost container's override config instead of global config.
     */
    private Configuration mMergedOverrideConfiguration = new Configuration();

    // The specified orientation for this window container.
    protected int mOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    private final Pools.SynchronizedPool<ForAllWindowsConsumerWrapper> mConsumerWrapperPool =
            new Pools.SynchronizedPool<>(3);

    // The owner/creator for this container. No controller if null.
    private WindowContainerController mController;

    final protected WindowContainer getParent() {
        return mParent;
    }

    final protected void setParent(WindowContainer parent) {
        mParent = parent;
        // Removing parent usually means that we've detached this entity to destroy it or to attach
        // to another parent. In both cases we don't need to update the configuration now.
        if (mParent != null) {
            // Update full configuration of this container and all its children.
            onConfigurationChanged(mParent.mFullConfiguration);
            // Update merged override configuration of this container and all its children.
            onMergedOverrideConfigurationChanged();
        }

        onParentSet();
    }

    /**
     * Callback that is triggered when @link WindowContainer#setParent(WindowContainer)} was called.
     * Supposed to be overridden and contain actions that should be executed after parent was set.
     */
    void onParentSet() {
        // Do nothing by default.
    }

    // Temp. holders for a chain of containers we are currently processing.
    private final LinkedList<WindowContainer> mTmpChain1 = new LinkedList();
    private final LinkedList<WindowContainer> mTmpChain2 = new LinkedList();

    /**
     * Adds the input window container has a child of this container in order based on the input
     * comparator.
     * @param child The window container to add as a child of this window container.
     * @param comparator Comparator to use in determining the position the child should be added to.
     *                   If null, the child will be added to the top.
     */
    @CallSuper
    protected void addChild(E child, Comparator<E> comparator) {
        if (child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.getParent().getName()
                    + " can't add to container=" + getName());
        }

        int positionToAdd = -1;
        if (comparator != null) {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (comparator.compare(child, mChildren.get(i)) < 0) {
                    positionToAdd = i;
                    break;
                }
            }
        }

        if (positionToAdd == -1) {
            mChildren.add(child);
        } else {
            mChildren.add(positionToAdd, child);
        }
        // Set the parent after we've actually added a child in case a subclass depends on this.
        child.setParent(this);
    }

    /** Adds the input window container has a child of this container at the input index. */
    @CallSuper
    void addChild(E child, int index) {
        if (child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.getParent().getName()
                    + " can't add to container=" + getName());
        }
        mChildren.add(index, child);
        // Set the parent after we've actually added a child in case a subclass depends on this.
        child.setParent(this);
    }

    /**
     * Removes the input child container from this container which is its parent.
     *
     * @return True if the container did contain the input child and it was detached.
     */
    @CallSuper
    void removeChild(E child) {
        if (mChildren.remove(child)) {
            child.setParent(null);
        } else {
            throw new IllegalArgumentException("removeChild: container=" + child.getName()
                    + " is not a child of container=" + getName());
        }
    }

    /**
     * Removes this window container and its children with no regard for what else might be going on
     * in the system. For example, the container will be removed during animation if this method is
     * called which isn't desirable. For most cases you want to call {@link #removeIfPossible()}
     * which allows the system to defer removal until a suitable time.
     */
    @CallSuper
    void removeImmediately() {
        while (!mChildren.isEmpty()) {
            final WindowContainer child = mChildren.peekLast();
            child.removeImmediately();
            // Need to do this after calling remove on the child because the child might try to
            // remove/detach itself from its parent which will cause an exception if we remove
            // it before calling remove on the child.
            mChildren.remove(child);
        }

        if (mParent != null) {
            mParent.removeChild(this);
        }

        if (mController != null) {
            setController(null);
        }
    }

    /**
     * Removes this window container and its children taking care not to remove them during a
     * critical stage in the system. For example, some containers will not be removed during
     * animation if this method is called.
     */
    // TODO: figure-out implementation that works best for this.
    // E.g. when do we remove from parent list? maybe not...
    void removeIfPossible() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.removeIfPossible();
        }
    }

    /** Returns true if this window container has the input child. */
    boolean hasChild(WindowContainer child) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer current = mChildren.get(i);
            if (current == child || current.hasChild(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Move a child from it's current place in siblings list to the specified position,
     * with an option to move all its parents to top.
     * @param position Target position to move the child to.
     * @param child Child to move to selected position.
     * @param includingParents Flag indicating whether we need to move the entire branch of the
     *                         hierarchy when we're moving a child to {@link #POSITION_TOP} or
     *                         {@link #POSITION_BOTTOM}. When moving to other intermediate positions
     *                         this flag will do nothing.
     */
    @CallSuper
    void positionChildAt(int position, E child, boolean includingParents) {

        if (child.getParent() != this) {
            throw new IllegalArgumentException("removeChild: container=" + child.getName()
                    + " is not a child of container=" + getName()
                    + " current parent=" + child.getParent());
        }

        if ((position < 0 && position != POSITION_BOTTOM)
                || (position > mChildren.size() && position != POSITION_TOP)) {
            throw new IllegalArgumentException("positionAt: invalid position=" + position
                    + ", children number=" + mChildren.size());
        }

        if (position >= mChildren.size() - 1) {
            position = POSITION_TOP;
        } else if (position == 0) {
            position = POSITION_BOTTOM;
        }

        switch (position) {
            case POSITION_TOP:
                if (mChildren.peekLast() != child) {
                    mChildren.remove(child);
                    mChildren.add(child);
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_TOP, this /* child */,
                            true /* includingParents */);
                }
                break;
            case POSITION_BOTTOM:
                if (mChildren.peekFirst() != child) {
                    mChildren.remove(child);
                    mChildren.addFirst(child);
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_BOTTOM, this /* child */,
                            true /* includingParents */);
                }
                break;
            default:
                mChildren.remove(child);
                mChildren.add(position, child);
        }
    }

    /**
     * Returns full configuration applied to this window container.
     * This method should be used for getting settings applied in each particular level of the
     * hierarchy.
     */
    Configuration getConfiguration() {
        return mFullConfiguration;
    }

    /**
     * Notify that parent config changed and we need to update full configuration.
     * @see #mFullConfiguration
     */
    void onConfigurationChanged(Configuration newParentConfig) {
        mFullConfiguration.setTo(newParentConfig);
        mFullConfiguration.updateFrom(mOverrideConfiguration);
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            child.onConfigurationChanged(mFullConfiguration);
        }
    }

    /** Returns override configuration applied to this window container. */
    Configuration getOverrideConfiguration() {
        return mOverrideConfiguration;
    }

    /**
     * Update override configuration and recalculate full config.
     * @see #mOverrideConfiguration
     * @see #mFullConfiguration
     */
    void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        mOverrideConfiguration.setTo(overrideConfiguration);
        // Update full configuration of this container and all its children.
        onConfigurationChanged(mParent != null ? mParent.getConfiguration() : EMPTY);
        // Update merged override config of this container and all its children.
        onMergedOverrideConfigurationChanged();

        if (mParent != null) {
            mParent.onDescendantOverrideConfigurationChanged();
        }
    }

    /**
     * Notify that a descendant's overrideConfiguration has changed.
     */
    void onDescendantOverrideConfigurationChanged() {
        if (mParent != null) {
            mParent.onDescendantOverrideConfigurationChanged();
        }
    }

    /**
     * Get merged override configuration from the top of the hierarchy down to this
     * particular instance. This should be reported to client as override config.
     */
    Configuration getMergedOverrideConfiguration() {
        return mMergedOverrideConfiguration;
    }

    /**
     * Update merged override configuration based on corresponding parent's config and notify all
     * its children. If there is no parent, merged override configuration will set equal to current
     * override config.
     * @see #mMergedOverrideConfiguration
     */
    private void onMergedOverrideConfigurationChanged() {
        if (mParent != null) {
            mMergedOverrideConfiguration.setTo(mParent.getMergedOverrideConfiguration());
            mMergedOverrideConfiguration.updateFrom(mOverrideConfiguration);
        } else {
            mMergedOverrideConfiguration.setTo(mOverrideConfiguration);
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            child.onMergedOverrideConfigurationChanged();
        }
    }

    /**
     * Notify that the display this container is on has changed.
     * @param dc The new display this container is on.
     */
    void onDisplayChanged(DisplayContent dc) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            child.onDisplayChanged(dc);
        }
    }

    void setWaitingForDrawnIfResizingChanged() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.setWaitingForDrawnIfResizingChanged();
        }
    }

    void onResize() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onResize();
        }
    }

    void onMovedByResize() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onMovedByResize();
        }
    }

    void resetDragResizingChangeReported() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.resetDragResizingChangeReported();
        }
    }

    void forceWindowsScaleableInTransaction(boolean force) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.forceWindowsScaleableInTransaction(force);
        }
    }

    boolean isAnimating() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.isAnimating()) {
                return true;
            }
        }
        return false;
    }

    void sendAppVisibilityToClients() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.sendAppVisibilityToClients();
        }
    }

    void setVisibleBeforeClientHidden() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.setVisibleBeforeClientHidden();
        }
    }

    /**
     * Returns true if the container or one of its children as some content it can display or wants
     * to display (e.g. app views or saved surface).
     *
     * NOTE: While this method will return true if the there is some content to display, it doesn't
     * mean the container is visible. Use {@link #isVisible()} to determine if the container is
     * visible.
     */
    boolean hasContentToDisplay() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            if (wc.hasContentToDisplay()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the container or one of its children is considered visible from the
     * WindowManager perspective which usually means valid surface and some other internal state
     * are true.
     *
     * NOTE: While this method will return true if the surface is visible, it doesn't mean the
     * client has actually displayed any content. Use {@link #hasContentToDisplay()} to determine if
     * the container has any content to display.
     */
    boolean isVisible() {
        // TODO: Will this be more correct if it checks the visibility of its parents?
        // It depends...For example, Tasks and Stacks are only visible if there children are visible
        // but, WindowState are not visible if there parent are not visible. Maybe have the
        // container specify which direction to traverse for visibility?
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            if (wc.isVisible()) {
                return true;
            }
        }
        return false;
    }

    /**
a     * Returns whether this child is on top of the window hierarchy.
     */
    boolean isOnTop() {
        return getParent().getTopChild() == this && getParent().isOnTop();
    }

    /** Returns the top child container. */
    E getTopChild() {
        return mChildren.peekLast();
    }

    /** Returns true if there is still a removal being deferred */
    boolean checkCompleteDeferredRemoval() {
        boolean stillDeferringRemoval = false;

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            stillDeferringRemoval |= wc.checkCompleteDeferredRemoval();
        }

        return stillDeferringRemoval;
    }

    /** Checks if all windows in an app are all drawn and shows them if needed. */
    void checkAppWindowsReadyToShow() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.checkAppWindowsReadyToShow();
        }
    }

    /** Step currently ongoing animation for App window containers. */
    void stepAppWindowsAnimation(long currentTime) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.stepAppWindowsAnimation(currentTime);
        }
    }

    void onAppTransitionDone() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onAppTransitionDone();
        }
    }

    void setOrientation(int orientation) {
        mOrientation = orientation;
    }

    int getOrientation() {
        return getOrientation(mOrientation);
    }

    /**
     * Returns the specified orientation for this window container or one of its children is there
     * is one set, or {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSET} if no
     * specification is set.
     * NOTE: {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} is a
     * specification...
     *
     * @param candidate The current orientation candidate that will be returned if we don't find a
     *                  better match.
     * @return The orientation as specified by this branch or the window hierarchy.
     */
    int getOrientation(int candidate) {
        if (!fillsParent()) {
            // Ignore containers that don't completely fill their parents.
            return SCREEN_ORIENTATION_UNSET;
        }

        // The container fills its parent so we can use it orientation if it has one
        // specified; otherwise we prefer to use the orientation of its topmost child that has one
        // specified and fall back on this container's unset or unspecified value as a candidate
        // if none of the children have a better candidate for the orientation.
        if (mOrientation != SCREEN_ORIENTATION_UNSET
                && mOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            return mOrientation;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);

            // TODO: Maybe mOrientation should default to SCREEN_ORIENTATION_UNSET vs.
            // SCREEN_ORIENTATION_UNSPECIFIED?
            final int orientation = wc.getOrientation(candidate == SCREEN_ORIENTATION_BEHIND
                    ? SCREEN_ORIENTATION_BEHIND : SCREEN_ORIENTATION_UNSET);
            if (orientation == SCREEN_ORIENTATION_BEHIND) {
                // container wants us to use the orientation of the container behind it. See if we
                // can find one. Else return SCREEN_ORIENTATION_BEHIND so the caller can choose to
                // look behind this container.
                candidate = orientation;
                continue;
            }

            if (orientation == SCREEN_ORIENTATION_UNSET) {
                continue;
            }

            if (wc.fillsParent() || orientation != SCREEN_ORIENTATION_UNSPECIFIED) {
                // Use the orientation if the container fills its parent or requested an explicit
                // orientation that isn't SCREEN_ORIENTATION_UNSPECIFIED.
                return orientation;
            }
        }

        return candidate;
    }

    /**
     * Returns true if this container is opaque and fills all the space made available by its parent
     * container.
     *
     * NOTE: It is possible for this container to occupy more space than the parent has (or less),
     * this is just a signal from the client to window manager stating its intent, but not what it
     * actually does.
     */
    boolean fillsParent() {
        return false;
    }

    // TODO: Users would have their own window containers under the display container?
    void switchUser() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).switchUser();
        }
    }

    /**
     * For all windows at or below this container call the callback.
     * @param   callback Calls the {@link ToBooleanFunction#apply} method for each window found and
     *                   stops the search if {@link ToBooleanFunction#apply} returns true.
     * @param   traverseTopToBottom If true traverses the hierarchy from top-to-bottom in terms of
     *                              z-order, else from bottom-to-top.
     * @return  True if the search ended before we reached the end of the hierarchy due to
     *          {@link ToBooleanFunction#apply} returning true.
     */
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                if (mChildren.get(i).forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (mChildren.get(i).forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        }
        return false;
    }

    void forAllWindows(Consumer<WindowState> callback, boolean traverseTopToBottom) {
        ForAllWindowsConsumerWrapper wrapper = obtainConsumerWrapper(callback);
        forAllWindows(wrapper, traverseTopToBottom);
        wrapper.release();
    }

    /**
     * For all tasks at or below this container call the callback.
     *
     * @param callback Callback to be called for every task.
     */
    void forAllTasks(Consumer<Task> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).forAllTasks(callback);
        }
    }

    WindowState getWindow(Predicate<WindowState> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState w = mChildren.get(i).getWindow(callback);
            if (w != null) {
                return w;
            }
        }

        return null;
    }

    /**
     * Returns 1, 0, or -1 depending on if this container is greater than, equal to, or lesser than
     * the input container in terms of z-order.
     */
    @Override
    public int compareTo(WindowContainer other) {
        if (this == other) {
            return 0;
        }

        if (mParent != null && mParent == other.mParent) {
            final WindowList<WindowContainer> list = mParent.mChildren;
            return list.indexOf(this) > list.indexOf(other) ? 1 : -1;
        }

        final LinkedList<WindowContainer> thisParentChain = mTmpChain1;
        final LinkedList<WindowContainer> otherParentChain = mTmpChain2;
        try {
            getParents(thisParentChain);
            other.getParents(otherParentChain);

            // Find the common ancestor of both containers.
            WindowContainer commonAncestor = null;
            WindowContainer thisTop = thisParentChain.peekLast();
            WindowContainer otherTop = otherParentChain.peekLast();
            while (thisTop != null && otherTop != null && thisTop == otherTop) {
                commonAncestor = thisParentChain.removeLast();
                otherParentChain.removeLast();
                thisTop = thisParentChain.peekLast();
                otherTop = otherParentChain.peekLast();
            }

            // Containers don't belong to the same hierarchy???
            if (commonAncestor == null) {
                throw new IllegalArgumentException("No in the same hierarchy this="
                        + thisParentChain + " other=" + otherParentChain);
            }

            // Children are always considered greater than their parents, so if one of the containers
            // we are comparing it the parent of the other then whichever is the child is greater.
            if (commonAncestor == this) {
                return -1;
            } else if (commonAncestor == other) {
                return 1;
            }

            // The position of the first non-common ancestor in the common ancestor list determines
            // which is greater the which.
            final WindowList<WindowContainer> list = commonAncestor.mChildren;
            return list.indexOf(thisParentChain.peekLast()) > list.indexOf(otherParentChain.peekLast())
                    ? 1 : -1;
        } finally {
            mTmpChain1.clear();
            mTmpChain2.clear();
        }
    }

    private void getParents(LinkedList<WindowContainer> parents) {
        parents.clear();
        WindowContainer current = this;
        do {
            parents.addLast(current);
            current = current.mParent;
        } while (current != null);
    }

    WindowContainerController getController() {
        return mController;
    }

    void setController(WindowContainerController controller) {
        if (mController != null && controller != null) {
            throw new IllegalArgumentException("Can't set controller=" + mController
                    + " for container=" + this + " Already set to=" + mController);
        }
        if (controller != null) {
            controller.setContainer(this);
        } else if (mController != null) {
            mController.setContainer(null);
        }
        mController = controller;
    }

    /**
     * Dumps the names of this container children in the input print writer indenting each
     * level with the input prefix.
     */
    void dumpChildrenNames(StringBuilder out, String prefix) {
        final String childPrefix = prefix + " ";
        out.append(getName() + "\n");
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            out.append(childPrefix + "#" + i + " ");
            wc.dumpChildrenNames(out, childPrefix);
        }
    }

    String getName() {
        return toString();
    }

    private ForAllWindowsConsumerWrapper obtainConsumerWrapper(Consumer<WindowState> consumer) {
        ForAllWindowsConsumerWrapper wrapper = mConsumerWrapperPool.acquire();
        if (wrapper == null) {
            wrapper = new ForAllWindowsConsumerWrapper();
        }
        wrapper.setConsumer(consumer);
        return wrapper;
    }

    private final class ForAllWindowsConsumerWrapper implements ToBooleanFunction<WindowState> {

        private Consumer<WindowState> mConsumer;

        void setConsumer(Consumer<WindowState> consumer) {
            mConsumer = consumer;
        }

        @Override
        public boolean apply(WindowState w) {
            mConsumer.accept(w);
            return false;
        }

        void release() {
            mConsumer = null;
            mConsumerWrapperPool.release(this);
        }
    }
}
