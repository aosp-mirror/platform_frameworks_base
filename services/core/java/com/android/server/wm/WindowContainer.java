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
import static android.view.SurfaceControl.Transaction;

import static com.android.server.wm.WindowContainerProto.CONFIGURATION_CONTAINER;
import static com.android.server.wm.WindowContainerProto.ORIENTATION;
import static com.android.server.wm.WindowContainerProto.SURFACE_ANIMATOR;
import static com.android.server.wm.WindowContainerProto.VISIBLE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Pools;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceSession;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.wm.SurfaceAnimator.Animatable;

import java.io.PrintWriter;
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
class WindowContainer<E extends WindowContainer> extends ConfigurationContainer<E>
        implements Comparable<WindowContainer>, Animatable {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowContainer" : TAG_WM;

    /** Animation layer that happens above all animating {@link TaskStack}s. */
    static final int ANIMATION_LAYER_STANDARD = 0;

    /** Animation layer that happens above all {@link TaskStack}s. */
    static final int ANIMATION_LAYER_BOOSTED = 1;

    /**
     * Animation layer that is reserved for {@link WindowConfiguration#ACTIVITY_TYPE_HOME}
     * activities and all activities that are being controlled by the recents animation. This
     * layer is generally below all {@link TaskStack}s.
     */
    static final int ANIMATION_LAYER_HOME = 2;

    @IntDef(prefix = { "ANIMATION_LAYER_" }, value = {
            ANIMATION_LAYER_STANDARD,
            ANIMATION_LAYER_BOOSTED,
            ANIMATION_LAYER_HOME,
    })
    @interface AnimationLayer {}

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;

    /**
     * The parent of this window container.
     * For removing or setting new parent {@link #setParent} should be used, because it also
     * performs configuration updates based on new parent's settings.
     */
    private WindowContainer<WindowContainer> mParent = null;

    // List of children for this window container. List is in z-order as the children appear on
    // screen with the top-most window container at the tail of the list.
    protected final WindowList<E> mChildren = new WindowList<E>();

    // The specified orientation for this window container.
    protected int mOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    private final Pools.SynchronizedPool<ForAllWindowsConsumerWrapper> mConsumerWrapperPool =
            new Pools.SynchronizedPool<>(3);

    // The owner/creator for this container. No controller if null.
    WindowContainerController mController;

    protected SurfaceControl mSurfaceControl;
    private int mLastLayer = 0;
    private SurfaceControl mLastRelativeToLayer = null;

    /**
     * Applied as part of the animation pass in "prepareSurfaces".
     */
    protected final Transaction mPendingTransaction;
    protected final SurfaceAnimator mSurfaceAnimator;
    protected final WindowManagerService mWmService;

    private final Point mTmpPos = new Point();
    protected final Point mLastSurfacePosition = new Point();

    /** Total number of elements in this subtree, including our own hierarchy element. */
    private int mTreeWeight = 1;

    /**
     * Indicates whether we are animating and have committed the transaction to reparent our
     * surface to the animation leash
     */
    private boolean mCommittedReparentToAnimationLeash;

    WindowContainer(WindowManagerService wms) {
        mWmService = wms;
        mPendingTransaction = wms.mTransactionFactory.make();
        mSurfaceAnimator = new SurfaceAnimator(this, this::onAnimationFinished, wms);
    }

    @Override
    final protected WindowContainer getParent() {
        return mParent;
    }

    @Override
    protected int getChildCount() {
        return mChildren.size();
    }

    @Override
    protected E getChildAt(int index) {
        return mChildren.get(index);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        updateSurfacePosition();
        scheduleAnimation();
    }

    final protected void setParent(WindowContainer<WindowContainer> parent) {
        mParent = parent;
        onParentChanged();
    }

    /**
     * Callback that is triggered when @link WindowContainer#setParent(WindowContainer)} was called.
     * Supposed to be overridden and contain actions that should be executed after parent was set.
     */
    @Override
    void onParentChanged() {
        super.onParentChanged();
        if (mParent == null) {
            return;
        }

        if (mSurfaceControl == null) {
            // If we don't yet have a surface, but we now have a parent, we should
            // build a surface.
            mSurfaceControl = makeSurface().build();
            getPendingTransaction().show(mSurfaceControl);
            updateSurfacePosition();
        } else {
            // If we have a surface but a new parent, we just need to perform a reparent. Go through
            // surface animator such that hierarchy is preserved when animating, i.e.
            // mSurfaceControl stays attached to the leash and we just reparent the leash to the
            // new parent.
            reparentSurfaceControl(getPendingTransaction(), mParent.mSurfaceControl);
        }

        // Either way we need to ask the parent to assign us a Z-order.
        mParent.assignChildLayers();
        scheduleAnimation();
    }

    // Temp. holders for a chain of containers we are currently processing.
    private final LinkedList<WindowContainer> mTmpChain1 = new LinkedList<>();
    private final LinkedList<WindowContainer> mTmpChain2 = new LinkedList<>();

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
        onChildAdded(child);

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

        if ((index < 0 && index != POSITION_BOTTOM)
                || (index > mChildren.size() && index != POSITION_TOP)) {
            throw new IllegalArgumentException("addChild: invalid position=" + index
                    + ", children number=" + mChildren.size());
        }

        if (index == POSITION_TOP) {
            index = mChildren.size();
        } else if (index == POSITION_BOTTOM) {
            index = 0;
        }

        mChildren.add(index, child);
        onChildAdded(child);

        // Set the parent after we've actually added a child in case a subclass depends on this.
        child.setParent(this);
    }

    private void onChildAdded(WindowContainer child) {
        mTreeWeight += child.mTreeWeight;
        WindowContainer parent = getParent();
        while (parent != null) {
            parent.mTreeWeight += child.mTreeWeight;
            parent = parent.getParent();
        }
        onChildPositionChanged();
    }

    /**
     * Removes the input child container from this container which is its parent.
     *
     * @return True if the container did contain the input child and it was detached.
     */
    @CallSuper
    void removeChild(E child) {
        if (mChildren.remove(child)) {
            onChildRemoved(child);
            child.setParent(null);
        } else {
            throw new IllegalArgumentException("removeChild: container=" + child.getName()
                    + " is not a child of container=" + getName());
        }
    }

    private void onChildRemoved(WindowContainer child) {
        mTreeWeight -= child.mTreeWeight;
        WindowContainer parent = getParent();
        while (parent != null) {
            parent.mTreeWeight -= child.mTreeWeight;
            parent = parent.getParent();
        }
        onChildPositionChanged();
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
            final E child = mChildren.peekLast();
            child.removeImmediately();
            // Need to do this after calling remove on the child because the child might try to
            // remove/detach itself from its parent which will cause an exception if we remove
            // it before calling remove on the child.
            if (mChildren.remove(child)) {
                onChildRemoved(child);
            }
        }

        if (mSurfaceControl != null) {
            mPendingTransaction.remove(mSurfaceControl);

            // Merge to parent transaction to ensure the transactions on this WindowContainer are
            // applied in native even if WindowContainer is removed.
            if (mParent != null) {
                mParent.getPendingTransaction().merge(mPendingTransaction);
            }

            mSurfaceControl = null;
            scheduleAnimation();
        }

        if (mParent != null) {
            mParent.removeChild(this);
        }

        if (mController != null) {
            setController(null);
        }

    }

    /**
     * @return The index of this element in the hierarchy tree in prefix order.
     */
    int getPrefixOrderIndex() {
        if (mParent == null) {
            return 0;
        }
        return mParent.getPrefixOrderIndex(this);
    }

    private int getPrefixOrderIndex(WindowContainer child) {
        int order = 0;
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowContainer childI = mChildren.get(i);
            if (child == childI) {
                break;
            }
            order += childI.mTreeWeight;
        }
        if (mParent != null) {
            order += mParent.getPrefixOrderIndex(this);
        }

        // We also need to count ourselves.
        order++;
        return order;
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
    boolean hasChild(E child) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final E current = mChildren.get(i);
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
                    onChildPositionChanged();
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
                    onChildPositionChanged();
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_BOTTOM, this /* child */,
                            true /* includingParents */);
                }
                break;
            default:
                // TODO: Removing the child before reinserting requires the caller to provide a
                //       position that takes into account the removed child (if the index of the
                //       child < position, then the position should be adjusted). We should consider
                //       doing this adjustment here and remove any adjustments in the callers.
                mChildren.remove(child);
                mChildren.add(position, child);
                onChildPositionChanged();
        }
    }

    /**
     * Notify that a child's position has changed. Possible changes are adding or removing a child.
     */
    void onChildPositionChanged() { }

    /**
     * Update override configuration and recalculate full config.
     * @see #mRequestedOverrideConfiguration
     * @see #mFullConfiguration
     */
    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        // We must diff before the configuration is applied so that we can capture the change
        // against the existing bounds.
        final int diff = diffRequestedOverrideBounds(
                overrideConfiguration.windowConfiguration.getBounds());
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        if (mParent != null) {
            mParent.onDescendantOverrideConfigurationChanged();
        }

        if (diff == BOUNDS_CHANGE_NONE) {
            return;
        }

        if ((diff & BOUNDS_CHANGE_SIZE) == BOUNDS_CHANGE_SIZE) {
            onResize();
        } else {
            onMovedByResize();
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
     * Notify that the display this container is on has changed. This could be either this container
     * is moved to a new display, or some configurations on the display it is on changes.
     *
     * @param dc The display this container is on after changes.
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
            wc.onParentResize();
        }
    }

    void onParentResize() {
        // In the case this container has specified its own bounds, a parent resize will not
        // affect its bounds. Any relevant changes will be propagated through changes to the
        // Configuration override.
        if (hasOverrideBounds()) {
            return;
        }

        // Default implementation is to treat as resize on self.
        onResize();
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

    /**
     * @return Whether our own container is running an animation or any child, no matter how deep in
     *         the hierarchy, is animating.
     */
    boolean isSelfOrChildAnimating() {
        if (isSelfAnimating()) {
            return true;
        }
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.isSelfOrChildAnimating()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether our own container is running an animation or our parent is animating. This
     *         doesn't consider whether children are animating.
     */
    boolean isAnimating() {

        // We are animating if we ourselves are animating or if our parent is animating.
        return isSelfAnimating() || mParent != null && mParent.isAnimating();
    }

    /**
     * @return {@code true} if in this subtree of the hierarchy we have an {@link AppWindowToken}
     *         that is {@link #isSelfAnimating}; {@code false} otherwise.
     */
    boolean isAppAnimating() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.isAppAnimating()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether our own container running an animation at the moment.
     */
    boolean isSelfAnimating() {
        return mSurfaceAnimator.isAnimating();
    }

    void sendAppVisibilityToClients() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.sendAppVisibilityToClients();
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
     * @return Whether this child is on top of the window hierarchy.
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

    void onAppTransitionDone() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onAppTransitionDone();
        }
    }

    /**
     * Called when this container or one of its descendants changed its requested orientation, and
     * wants this container to handle it or pass it to its parent.
     *
     * @param freezeDisplayToken freeze this app window token if display needs to freeze
     * @param requestingContainer the container which orientation request has changed
     * @return {@code true} if handled; {@code false} otherwise.
     */
    boolean onDescendantOrientationChanged(@Nullable IBinder freezeDisplayToken,
            @Nullable ConfigurationContainer requestingContainer) {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return false;
        }
        return parent.onDescendantOrientationChanged(freezeDisplayToken,
                requestingContainer);
    }

    /**
     * Check if this container or its parent will handle orientation changes from descendants. It's
     * different from the return value of {@link #onDescendantOrientationChanged(IBinder,
     * ConfigurationContainer)} in the sense that the return value of this method tells if this
     * container or its parent will handle the request eventually, while the return value of the
     * other method is if it handled the request synchronously.
     *
     * @return {@code true} if it handles or will handle orientation change in the future; {@code
     *         false} if it won't handle the change at anytime.
     */
    boolean handlesOrientationChangeFromDescendant() {
        final WindowContainer parent = getParent();
        return parent != null && parent.handlesOrientationChangeFromDescendant();
    }

    /**
     * Calls {@link #setOrientation(int, IBinder, ActivityRecord)} with {@code null} to the last 2
     * parameters.
     *
     * @param orientation the specified orientation.
     */
    void setOrientation(int orientation) {
        setOrientation(orientation, null /* freezeDisplayToken */,
                null /* ActivityRecord */);
    }

    /**
     * Sets the specified orientation of this container. It percolates this change upward along the
     * hierarchy to let each level of the hierarchy a chance to respond to it.
     *
     * @param orientation the specified orientation. Needs to be one of {@link
     *      android.content.pm.ActivityInfo.ScreenOrientation}.
     * @param freezeDisplayToken uses this token to freeze display if orientation change is not
     *                           done. Display will not be frozen if this is {@code null}, which
     *                           should only happen in tests.
     * @param requestingContainer the container which orientation request has changed. Mostly used
     *                            to ensure it gets correct configuration.
     */
    void setOrientation(int orientation, @Nullable IBinder freezeDisplayToken,
            @Nullable ConfigurationContainer requestingContainer) {
        final boolean changed = mOrientation != orientation;
        mOrientation = orientation;
        if (!changed) {
            return;
        }
        final WindowContainer parent = getParent();
        if (parent != null) {
            onDescendantOrientationChanged(freezeDisplayToken, requestingContainer);
        }
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

    void forAllAppWindows(Consumer<AppWindowToken> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).forAllAppWindows(callback);
        }
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

    SurfaceControl.Builder makeSurface() {
        final WindowContainer p = getParent();
        return p.makeChildSurface(this);
    }

    /**
     * @param child The WindowContainer this child surface is for, or null if the Surface
     *              is not assosciated with a WindowContainer (e.g. a surface used for Dimming).
     */
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        final WindowContainer p = getParent();
        // Give the parent a chance to set properties. In hierarchy v1 we rely
        // on this to set full-screen dimensions on all our Surface-less Layers.
        return p.makeChildSurface(child)
                .setParent(mSurfaceControl);
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }
        return parent.getSurfaceControl();
    }

    /**
     * @return Whether this WindowContainer should be magnified by the accessibility magnifier.
     */
    boolean shouldMagnify() {
        if (mSurfaceControl == null) {
            return false;
        }

        for (int i = 0; i < mChildren.size(); i++) {
            if (!mChildren.get(i).shouldMagnify()) {
                return false;
            }
        }
        return true;
    }

    SurfaceSession getSession() {
        if (getParent() != null) {
            return getParent().getSession();
        }
        return null;
    }

    void assignLayer(Transaction t, int layer) {
        final boolean changed = layer != mLastLayer || mLastRelativeToLayer != null;
        if (mSurfaceControl != null && changed) {
            setLayer(t, layer);
            mLastLayer = layer;
            mLastRelativeToLayer = null;
        }
    }

    void assignRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        final boolean changed = layer != mLastLayer || mLastRelativeToLayer != relativeTo;
        if (mSurfaceControl != null && changed) {
            setRelativeLayer(t, relativeTo, layer);
            mLastLayer = layer;
            mLastRelativeToLayer = relativeTo;
        }
    }

    protected void setLayer(Transaction t, int layer) {

        // Route through surface animator to accommodate that our surface control might be
        // attached to the leash, and leash is attached to parent container.
        mSurfaceAnimator.setLayer(t, layer);
    }

    protected void setRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {

        // Route through surface animator to accommodate that our surface control might be
        // attached to the leash, and leash is attached to parent container.
        mSurfaceAnimator.setRelativeLayer(t, relativeTo, layer);
    }

    protected void reparentSurfaceControl(Transaction t, SurfaceControl newParent) {
        mSurfaceAnimator.reparent(t, newParent);
    }

    void assignChildLayers(Transaction t) {
        int layer = 0;

        // We use two passes as a way to promote children which
        // need Z-boosting to the end of the list.
        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            wc.assignChildLayers(t);
            if (!wc.needsZBoost()) {
                wc.assignLayer(t, layer++);
            }
        }
        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.needsZBoost()) {
                wc.assignLayer(t, layer++);
            }
        }
    }

    void assignChildLayers() {
        assignChildLayers(getPendingTransaction());
        scheduleAnimation();
    }

    boolean needsZBoost() {
        for (int i = 0; i < mChildren.size(); i++) {
            if (mChildren.get(i).needsZBoost()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at
     * {@link com.android.server.wm.WindowContainerProto}.
     *
     * @param proto     Stream to write the WindowContainer object to.
     * @param fieldId   Field Id of the WindowContainer as defined in the parent message.
     * @param logLevel  Determines the amount of data to be written to the Protobuf.
     * @hide
     */
    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        boolean isVisible = isVisible();
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible) {
            return;
        }

        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER, logLevel);
        proto.write(ORIENTATION, mOrientation);
        proto.write(VISIBLE, isVisible);
        if (mSurfaceAnimator.isAnimating()) {
            mSurfaceAnimator.writeToProto(proto, SURFACE_ANIMATOR);
        }
        proto.end(token);
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

    // TODO(b/68336570): Should this really be on WindowContainer since it
    // can only be used on the top-level nodes that aren't animated?
    // (otherwise we would be fighting other callers of setMatrix).
    void applyMagnificationSpec(Transaction t, MagnificationSpec spec) {
        if (shouldMagnify()) {
            t.setMatrix(mSurfaceControl, spec.scale, 0, 0, spec.scale)
                    .setPosition(mSurfaceControl, spec.offsetX, spec.offsetY);
        } else {
            for (int i = 0; i < mChildren.size(); i++) {
                mChildren.get(i).applyMagnificationSpec(t, spec);
            }
        }
    }

    /**
     * TODO: Once we totally eliminate global transaction we will pass transaction in here
     * rather than merging to global.
     */
    void prepareSurfaces() {
        SurfaceControl.mergeToGlobalTransaction(getPendingTransaction());

        // If a leash has been set when the transaction was committed, then the leash reparent has
        // been committed.
        mCommittedReparentToAnimationLeash = mSurfaceAnimator.hasLeash();
        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).prepareSurfaces();
        }
    }

    /**
     * @return true if the reparent to animation leash transaction has been committed, false
     * otherwise.
     */
    boolean hasCommittedReparentToAnimationLeash() {
        return mCommittedReparentToAnimationLeash;
    }

    /**
     * Trigger a call to prepareSurfaces from the animation thread, such that
     * mPendingTransaction will be applied.
     */
    void scheduleAnimation() {
        if (mParent != null) {
            mParent.scheduleAnimation();
        }
    }

    @Override
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    @Override
    public Transaction getPendingTransaction() {
        return mPendingTransaction;
    }

    /**
     * Starts an animation on the container.
     *
     * @param anim The animation to run.
     * @param hidden Whether our container is currently hidden. TODO This should use isVisible at
     *               some point but the meaning is too weird to work for all containers.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden) {
        if (DEBUG_ANIM) Slog.v(TAG, "Starting animation on " + this + ": " + anim);

        // TODO: This should use isVisible() but because isVisible has a really weird meaning at
        // the moment this doesn't work for all animatable window containers.
        mSurfaceAnimator.startAnimation(t, anim, hidden);
    }

    void transferAnimation(WindowContainer from) {
        mSurfaceAnimator.transferAnimation(from.mSurfaceAnimator);
    }

    void cancelAnimation() {
        mSurfaceAnimator.cancelAnimation();
    }

    @Override
    public Builder makeAnimationLeash() {
        return makeSurface();
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return getParentSurfaceControl();
    }

    /**
     * @return The layer on which all app animations are happening.
     */
    SurfaceControl getAppAnimationLayer(@AnimationLayer int animationLayer) {
        final WindowContainer parent = getParent();
        if (parent != null) {
            return parent.getAppAnimationLayer(animationLayer);
        }
        return null;
    }

    @Override
    public void commitPendingTransaction() {
        scheduleAnimation();
    }

    void reassignLayer(Transaction t) {
        final WindowContainer parent = getParent();
        if (parent != null) {
            parent.assignChildLayers(t);
        }
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        mLastLayer = -1;
        reassignLayer(t);
    }

    @Override
    public void onAnimationLeashDestroyed(Transaction t) {
        mLastLayer = -1;
        reassignLayer(t);
    }

    /**
     * Called when an animation has finished running.
     */
    protected void onAnimationFinished() {
    }

    /**
     * @return The currently running animation, if any, or {@code null} otherwise.
     */
    AnimationAdapter getAnimation() {
        return mSurfaceAnimator.getAnimation();
    }

    /**
     * @see SurfaceAnimator#startDelayingAnimationStart
     */
    void startDelayingAnimationStart() {
        mSurfaceAnimator.startDelayingAnimationStart();
    }

    /**
     * @see SurfaceAnimator#endDelayingAnimationStart
     */
    void endDelayingAnimationStart() {
        mSurfaceAnimator.endDelayingAnimationStart();
    }

    @Override
    public int getSurfaceWidth() {
        return mSurfaceControl.getWidth();
    }

    @Override
    public int getSurfaceHeight() {
        return mSurfaceControl.getHeight();
    }

    @CallSuper
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mSurfaceAnimator.isAnimating()) {
            pw.print(prefix); pw.println("ContainerAnimator:");
            mSurfaceAnimator.dump(pw, prefix + "  ");
        }
    }

    void updateSurfacePosition() {
        if (mSurfaceControl == null) {
            return;
        }

        getRelativeDisplayedPosition(mTmpPos);
        if (mTmpPos.equals(mLastSurfacePosition)) {
            return;
        }

        getPendingTransaction().setPosition(mSurfaceControl, mTmpPos.x, mTmpPos.y);
        mLastSurfacePosition.set(mTmpPos.x, mTmpPos.y);
    }

    @VisibleForTesting
    Point getLastSurfacePosition() {
        return mLastSurfacePosition;
    }

    /**
     * Displayed bounds specify where to display this container at. It differs from bounds during
     * certain operations (like animation or interactive dragging).
     *
     * @return the bounds to display this container at.
     */
    Rect getDisplayedBounds() {
        return getBounds();
    }

    void getRelativeDisplayedPosition(Point outPos) {
        final Rect dispBounds = getDisplayedBounds();
        outPos.set(dispBounds.left, dispBounds.top);
        final WindowContainer parent = getParent();
        if (parent != null) {
            final Rect parentBounds = parent.getDisplayedBounds();
            outPos.offset(-parentBounds.left, -parentBounds.top);
        }
    }

    Dimmer getDimmer() {
        if (mParent == null) {
            return null;
        }
        return mParent.getDimmer();
    }
}
