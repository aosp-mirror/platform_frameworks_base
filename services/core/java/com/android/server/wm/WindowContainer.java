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

import android.annotation.CallSuper;
import android.view.animation.Animation;

import java.util.Comparator;
import java.util.LinkedList;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

/**
 * Defines common functionality for classes that can hold windows directly or through their
 * children in a hierarchy form.
 * The test class is {@link WindowContainerTests} which must be kept up-to-date and ran anytime
 * changes are made to this class.
 */
class WindowContainer<E extends WindowContainer> implements Comparable<WindowContainer> {

    // The parent of this window container.
    protected WindowContainer mParent = null;

    // List of children for this window container. List is in z-order as the children appear on
    // screen with the top-most window container at the tail of the list.
    protected final LinkedList<E> mChildren = new LinkedList();

    // The specified orientation for this window container.
    protected int mOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    final protected WindowContainer getParent() {
        return mParent;
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
        if (child.mParent != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.mParent.getName()
                    + " can't add to container=" + getName());
        }
        child.mParent = this;

        if (mChildren.isEmpty() || comparator == null) {
            mChildren.add(child);
            return;
        }

        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            if (comparator.compare(child, mChildren.get(i)) < 0) {
                mChildren.add(i, child);
                return;
            }
        }

        mChildren.add(child);
    }

    /** Adds the input window container has a child of this container at the input index. */
    @CallSuper
    protected void addChild(E child, int index) {
        if (child.mParent != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.mParent.getName()
                    + " can't add to container=" + getName());
        }
        child.mParent = this;
        mChildren.add(index, child);
    }

    /**
     * Removes the input child container from this container which is its parent.
     *
     * @return True if the container did contain the input child and it was detached.
     */
    @CallSuper
    void removeChild(E child) {
        if (mChildren.remove(child)) {
            child.mParent = null;
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
        // container specify which direction to treverse for for visibility?
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            if (wc.isVisible()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the top child container or this container if there are no children. */
    WindowContainer getTop() {
        return mChildren.isEmpty() ? this : mChildren.peekLast();
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
    // TODO: The displayId shouldn't be needed as there shouldn't be a container on more than one
    // display. Remove once we migrate DisplayContent to use WindowContainer.
    void checkAppWindowsReadyToShow(int displayId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.checkAppWindowsReadyToShow(displayId);
        }
    }

    /**
     * Updates the current all drawn status for this container. That is all its children
     * that should draw something have done so.
     */
    // TODO: The displayId shouldn't be needed as there shouldn't be a container on more than one
    // display. Remove once we migrate DisplayContent to use WindowContainer.
    void updateAllDrawn(int displayId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.updateAllDrawn(displayId);
        }
    }

    /** Step currently ongoing animation for App window containers. */
    // TODO: The displayId shouldn't be needed as there shouldn't be a container on more than one
    // display. Remove once we migrate DisplayContent to use WindowContainer.
    void stepAppWindowsAnimation(long currentTime, int displayId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.stepAppWindowsAnimation(currentTime, displayId);
        }
    }

    void onAppTransitionDone() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onAppTransitionDone();
        }
    }

    void overridePlayingAppAnimations(Animation a) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            mChildren.get(i).overridePlayingAppAnimations(a);
        }
    }

    void setOrientation(int orientation) {
        mOrientation = orientation;
    }

    /**
     * Returns the specified orientation for this window container or one of its children is there
     * is one set, or {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSET} if no
     * specification is set.
     * NOTE: {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} is a
     * specification...
     */
    int getOrientation() {

        if (!fillsParent() || !isVisible()) {
            // Ignore invisible containers or containers that don't completely fills their parents.
            return SCREEN_ORIENTATION_UNSET;
        }

        // The container fills its parent so we can use it orientation if it has one specified,
        // otherwise we prefer to use the orientation of its topmost child that has one
        // specified and fall back on this container's unset or unspecified value as a candidate
        // if none of the children have a better candidate for the orientation.
        if (mOrientation != SCREEN_ORIENTATION_UNSET
                && mOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            return mOrientation;
        }
        int candidate = mOrientation;

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);

            final int orientation = wc.getOrientation();
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

    /**
     * Rebuilds the WindowList for the input display content.
     * @param dc The display content to rebuild the window list for.
     * @param addIndex The index in the window list to add the next entry to.
     * @return The next index in the window list to.
     */
    // TODO: Hoping we can get rid of WindowList so this method wouldn't be needed.
    int rebuildWindowList(DisplayContent dc, int addIndex) {
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            final WindowContainer wc = mChildren.get(i);
            addIndex = wc.rebuildWindowList(dc, addIndex);
        }
        return addIndex;
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
            final LinkedList<WindowContainer> list = mParent.mChildren;
            return list.indexOf(this) > list.indexOf(other) ? 1 : -1;
        }

        final LinkedList<WindowContainer> thisParentChain = mTmpChain1;
        final LinkedList<WindowContainer> otherParentChain = mTmpChain2;
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
        final LinkedList<WindowContainer> list = commonAncestor.mChildren;
        return list.indexOf(thisParentChain.peekLast()) > list.indexOf(otherParentChain.peekLast())
                ? 1 : -1;
    }

    private void getParents(LinkedList<WindowContainer> parents) {
        parents.clear();
        WindowContainer current = this;
        do {
            parents.addLast(current);
            current = current.mParent;
        } while (current != null);
    }

    /**
     * Dumps the names of this container children in the input print writer indenting each
     * level with the input prefix.
     */
    void dumpChildrenNames(StringBuilder out, String prefix) {
        final String childPrefix = prefix + " ";
        out.append(getName() + "\n");
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            final WindowContainer wc = mChildren.get(i);
            out.append(childPrefix + "#" + i + " ");
            wc.dumpChildrenNames(out, childPrefix);
        }
    }

    String getName() {
        return toString();
    }

}
