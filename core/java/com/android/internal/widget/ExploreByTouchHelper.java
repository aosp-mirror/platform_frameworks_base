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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.IntArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;

/**
 * ExploreByTouchHelper is a utility class for implementing accessibility
 * support in custom {@link android.view.View}s that represent a collection of View-like
 * logical items. It extends {@link android.view.accessibility.AccessibilityNodeProvider} and
 * simplifies many aspects of providing information to accessibility services
 * and managing accessibility focus. This class does not currently support
 * hierarchies of logical items.
 * <p>
 * This should be applied to the parent view using
 * {@link android.view.View#setAccessibilityDelegate}:
 *
 * <pre>
 * mAccessHelper = ExploreByTouchHelper.create(someView, mAccessHelperCallback);
 * ViewCompat.setAccessibilityDelegate(someView, mAccessHelper);
 * </pre>
 */
public abstract class ExploreByTouchHelper extends View.AccessibilityDelegate {
    /** Virtual node identifier value for invalid nodes. */
    public static final int INVALID_ID = Integer.MIN_VALUE;

    /** Virtual node identifier value for the host view's node. */
    public static final int HOST_ID = View.NO_ID;

    /** Default class name used for virtual views. */
    private static final String DEFAULT_CLASS_NAME = View.class.getName();

    /** Default bounds used to determine if the client didn't set any. */
    private static final Rect INVALID_PARENT_BOUNDS = new Rect(
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    // Lazily-created temporary data structures used when creating nodes.
    private Rect mTempScreenRect;
    private Rect mTempParentRect;
    private int[] mTempGlobalRect;

    /** Lazily-created temporary data structure used to compute visibility. */
    private Rect mTempVisibleRect;

    /** Lazily-created temporary data structure used to obtain child IDs. */
    private IntArray mTempArray;

    /** System accessibility manager, used to check state and send events. */
    private final AccessibilityManager mManager;

    /** View whose internal structure is exposed through this helper. */
    private final View mView;

    /** Context of the host view. **/
    private final Context mContext;

    /** Node provider that handles creating nodes and performing actions. */
    private ExploreByTouchNodeProvider mNodeProvider;

    /** Virtual view id for the currently focused logical item. */
    private int mFocusedVirtualViewId = INVALID_ID;

    /** Virtual view id for the currently hovered logical item. */
    private int mHoveredVirtualViewId = INVALID_ID;

    /**
     * Factory method to create a new {@link ExploreByTouchHelper}.
     *
     * @param forView View whose logical children are exposed by this helper.
     */
    public ExploreByTouchHelper(View forView) {
        if (forView == null) {
            throw new IllegalArgumentException("View may not be null");
        }

        mView = forView;
        mContext = forView.getContext();
        mManager = (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    /**
     * Returns the {@link android.view.accessibility.AccessibilityNodeProvider} for this helper.
     *
     * @param host View whose logical children are exposed by this helper.
     * @return The accessibility node provider for this helper.
     */
    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
        if (mNodeProvider == null) {
            mNodeProvider = new ExploreByTouchNodeProvider();
        }
        return mNodeProvider;
    }

    /**
     * Dispatches hover {@link android.view.MotionEvent}s to the virtual view hierarchy when
     * the Explore by Touch feature is enabled.
     * <p>
     * This method should be called by overriding
     * {@link View#dispatchHoverEvent}:
     *
     * <pre>&#64;Override
     * public boolean dispatchHoverEvent(MotionEvent event) {
     *   if (mHelper.dispatchHoverEvent(this, event) {
     *     return true;
     *   }
     *   return super.dispatchHoverEvent(event);
     * }
     * </pre>
     *
     * @param event The hover event to dispatch to the virtual view hierarchy.
     * @return Whether the hover event was handled.
     */
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (!mManager.isEnabled() || !mManager.isTouchExplorationEnabled()) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_ENTER:
                final int virtualViewId = getVirtualViewAt(event.getX(), event.getY());
                updateHoveredVirtualView(virtualViewId);
                return (virtualViewId != INVALID_ID);
            case MotionEvent.ACTION_HOVER_EXIT:
                if (mFocusedVirtualViewId != INVALID_ID) {
                    updateHoveredVirtualView(INVALID_ID);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Populates an event of the specified type with information about an item
     * and attempts to send it up through the view hierarchy.
     * <p>
     * You should call this method after performing a user action that normally
     * fires an accessibility event, such as clicking on an item.
     *
     * <pre>public void performItemClick(T item) {
     *   ...
     *   sendEventForVirtualViewId(item.id, AccessibilityEvent.TYPE_VIEW_CLICKED);
     * }
     * </pre>
     *
     * @param virtualViewId The virtual view id for which to send an event.
     * @param eventType The type of event to send.
     * @return true if the event was sent successfully.
     */
    public boolean sendEventForVirtualView(int virtualViewId, int eventType) {
        if ((virtualViewId == INVALID_ID) || !mManager.isEnabled()) {
            return false;
        }

        final ViewParent parent = mView.getParent();
        if (parent == null) {
            return false;
        }

        final AccessibilityEvent event = createEvent(virtualViewId, eventType);
        return parent.requestSendAccessibilityEvent(mView, event);
    }

    /**
     * Notifies the accessibility framework that the properties of the parent
     * view have changed.
     * <p>
     * You <b>must</b> call this method after adding or removing items from the
     * parent view.
     */
    public void invalidateRoot() {
        invalidateVirtualView(HOST_ID, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
    }

    /**
     * Notifies the accessibility framework that the properties of a particular
     * item have changed.
     * <p>
     * You <b>must</b> call this method after changing any of the properties set
     * in {@link #onPopulateNodeForVirtualView}.
     *
     * @param virtualViewId The virtual view id to invalidate, or
     *                      {@link #HOST_ID} to invalidate the root view.
     * @see #invalidateVirtualView(int, int)
     */
    public void invalidateVirtualView(int virtualViewId) {
        invalidateVirtualView(virtualViewId,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
    }

    /**
     * Notifies the accessibility framework that the properties of a particular
     * item have changed.
     * <p>
     * You <b>must</b> call this method after changing any of the properties set
     * in {@link #onPopulateNodeForVirtualView}.
     *
     * @param virtualViewId The virtual view id to invalidate, or
     *                      {@link #HOST_ID} to invalidate the root view.
     * @param changeTypes The bit mask of change types. May be {@code 0} for the
     *                    default (undefined) change type or one or more of:
     *         <ul>
     *         <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION}
     *         <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_SUBTREE}
     *         <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_TEXT}
     *         <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_UNDEFINED}
     *         </ul>
     */
    public void invalidateVirtualView(int virtualViewId, int changeTypes) {
        if (virtualViewId != INVALID_ID && mManager.isEnabled()) {
            final ViewParent parent = mView.getParent();
            if (parent != null) {
                final AccessibilityEvent event = createEvent(virtualViewId,
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                event.setContentChangeTypes(changeTypes);
                parent.requestSendAccessibilityEvent(mView, event);
            }
        }
    }

    /**
     * Returns the virtual view id for the currently focused item,
     *
     * @return A virtual view id, or {@link #INVALID_ID} if no item is
     *         currently focused.
     */
    public int getFocusedVirtualView() {
        return mFocusedVirtualViewId;
    }

    /**
     * Sets the currently hovered item, sending hover accessibility events as
     * necessary to maintain the correct state.
     *
     * @param virtualViewId The virtual view id for the item currently being
     *            hovered, or {@link #INVALID_ID} if no item is hovered within
     *            the parent view.
     */
    private void updateHoveredVirtualView(int virtualViewId) {
        if (mHoveredVirtualViewId == virtualViewId) {
            return;
        }

        final int previousVirtualViewId = mHoveredVirtualViewId;
        mHoveredVirtualViewId = virtualViewId;

        // Stay consistent with framework behavior by sending ENTER/EXIT pairs
        // in reverse order. This is accurate as of API 18.
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        sendEventForVirtualView(previousVirtualViewId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    /**
     * Constructs and returns an {@link AccessibilityEvent} for the specified
     * virtual view id, which includes the host view ({@link #HOST_ID}).
     *
     * @param virtualViewId The virtual view id for the item for which to
     *            construct an event.
     * @param eventType The type of event to construct.
     * @return An {@link AccessibilityEvent} populated with information about
     *         the specified item.
     */
    private AccessibilityEvent createEvent(int virtualViewId, int eventType) {
        switch (virtualViewId) {
            case HOST_ID:
                return createEventForHost(eventType);
            default:
                return createEventForChild(virtualViewId, eventType);
        }
    }

    /**
     * Constructs and returns an {@link AccessibilityEvent} for the host node.
     *
     * @param eventType The type of event to construct.
     * @return An {@link AccessibilityEvent} populated with information about
     *         the specified item.
     */
    private AccessibilityEvent createEventForHost(int eventType) {
        final AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        mView.onInitializeAccessibilityEvent(event);

        // Allow the client to populate the event.
        onPopulateEventForHost(event);

        return event;
    }

    /**
     * Constructs and returns an {@link AccessibilityEvent} populated with
     * information about the specified item.
     *
     * @param virtualViewId The virtual view id for the item for which to
     *            construct an event.
     * @param eventType The type of event to construct.
     * @return An {@link AccessibilityEvent} populated with information about
     *         the specified item.
     */
    private AccessibilityEvent createEventForChild(int virtualViewId, int eventType) {
        final AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setEnabled(true);
        event.setClassName(DEFAULT_CLASS_NAME);

        // Allow the client to populate the event.
        onPopulateEventForVirtualView(virtualViewId, event);

        // Make sure the developer is following the rules.
        if (event.getText().isEmpty() && (event.getContentDescription() == null)) {
            throw new RuntimeException("Callbacks must add text or a content description in "
                    + "populateEventForVirtualViewId()");
        }

        // Don't allow the client to override these properties.
        event.setPackageName(mView.getContext().getPackageName());
        event.setSource(mView, virtualViewId);

        return event;
    }

    /**
     * Constructs and returns an {@link android.view.accessibility.AccessibilityNodeInfo} for the
     * specified virtual view id, which includes the host view
     * ({@link #HOST_ID}).
     *
     * @param virtualViewId The virtual view id for the item for which to
     *            construct a node.
     * @return An {@link android.view.accessibility.AccessibilityNodeInfo} populated with information
     *         about the specified item.
     */
    private AccessibilityNodeInfo createNode(int virtualViewId) {
        switch (virtualViewId) {
            case HOST_ID:
                return createNodeForHost();
            default:
                return createNodeForChild(virtualViewId);
        }
    }

    /**
     * Constructs and returns an {@link AccessibilityNodeInfo} for the
     * host view populated with its virtual descendants.
     *
     * @return An {@link AccessibilityNodeInfo} for the parent node.
     */
    private AccessibilityNodeInfo createNodeForHost() {
        final AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(mView);
        mView.onInitializeAccessibilityNodeInfo(node);
        final int realNodeCount = node.getChildCount();

        // Allow the client to populate the host node.
        onPopulateNodeForHost(node);

        // Add the virtual descendants.
        if (mTempArray == null) {
            mTempArray = new IntArray();
        } else {
            mTempArray.clear();
        }
        final IntArray virtualViewIds = mTempArray;
        getVisibleVirtualViews(virtualViewIds);
        if (realNodeCount > 0 && virtualViewIds.size() > 0) {
            throw new RuntimeException("Views cannot have both real and virtual children");
        }

        final int N = virtualViewIds.size();
        for (int i = 0; i < N; i++) {
            node.addChild(mView, virtualViewIds.get(i));
        }

        return node;
    }

    /**
     * Constructs and returns an {@link AccessibilityNodeInfo} for the
     * specified item. Automatically manages accessibility focus actions.
     * <p>
     * Allows the implementing class to specify most node properties, but
     * overrides the following:
     * <ul>
     * <li>{@link AccessibilityNodeInfo#setPackageName}
     * <li>{@link AccessibilityNodeInfo#setClassName}
     * <li>{@link AccessibilityNodeInfo#setParent(View)}
     * <li>{@link AccessibilityNodeInfo#setSource(View, int)}
     * <li>{@link AccessibilityNodeInfo#setVisibleToUser}
     * <li>{@link AccessibilityNodeInfo#setBoundsInScreen(Rect)}
     * </ul>
     * <p>
     * Uses the bounds of the parent view and the parent-relative bounding
     * rectangle specified by
     * {@link AccessibilityNodeInfo#getBoundsInParent} to automatically
     * update the following properties:
     * <ul>
     * <li>{@link AccessibilityNodeInfo#setVisibleToUser}
     * <li>{@link AccessibilityNodeInfo#setBoundsInParent}
     * </ul>
     *
     * @param virtualViewId The virtual view id for item for which to construct
     *            a node.
     * @return An {@link AccessibilityNodeInfo} for the specified item.
     */
    private AccessibilityNodeInfo createNodeForChild(int virtualViewId) {
        ensureTempRects();
        final Rect tempParentRect = mTempParentRect;
        final int[] tempGlobalRect = mTempGlobalRect;
        final Rect tempScreenRect = mTempScreenRect;

        final AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();

        // Ensure the client has good defaults.
        node.setEnabled(true);
        node.setClassName(DEFAULT_CLASS_NAME);
        node.setBoundsInParent(INVALID_PARENT_BOUNDS);

        // Allow the client to populate the node.
        onPopulateNodeForVirtualView(virtualViewId, node);

        // Make sure the developer is following the rules.
        if ((node.getText() == null) && (node.getContentDescription() == null)) {
            throw new RuntimeException("Callbacks must add text or a content description in "
                    + "populateNodeForVirtualViewId()");
        }

        node.getBoundsInParent(tempParentRect);
        if (tempParentRect.equals(INVALID_PARENT_BOUNDS)) {
            throw new RuntimeException("Callbacks must set parent bounds in "
                    + "populateNodeForVirtualViewId()");
        }

        final int actions = node.getActions();
        if ((actions & AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) != 0) {
            throw new RuntimeException("Callbacks must not add ACTION_ACCESSIBILITY_FOCUS in "
                    + "populateNodeForVirtualViewId()");
        }
        if ((actions & AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) != 0) {
            throw new RuntimeException("Callbacks must not add ACTION_CLEAR_ACCESSIBILITY_FOCUS in "
                    + "populateNodeForVirtualViewId()");
        }

        // Don't allow the client to override these properties.
        node.setPackageName(mView.getContext().getPackageName());
        node.setSource(mView, virtualViewId);
        node.setParent(mView);

        // Manage internal accessibility focus state.
        if (mFocusedVirtualViewId == virtualViewId) {
            node.setAccessibilityFocused(true);
            node.addAction(AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        } else {
            node.setAccessibilityFocused(false);
            node.addAction(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        }

        // Set the visibility based on the parent bound.
        if (intersectVisibleToUser(tempParentRect)) {
            node.setVisibleToUser(true);
            node.setBoundsInParent(tempParentRect);
        }

        // Calculate screen-relative bound.
        mView.getLocationOnScreen(tempGlobalRect);
        final int offsetX = tempGlobalRect[0];
        final int offsetY = tempGlobalRect[1];
        tempScreenRect.set(tempParentRect);
        tempScreenRect.offset(offsetX, offsetY);
        node.setBoundsInScreen(tempScreenRect);

        return node;
    }

    private void ensureTempRects() {
        mTempGlobalRect = new int[2];
        mTempParentRect = new Rect();
        mTempScreenRect = new Rect();
    }

    private boolean performAction(int virtualViewId, int action, Bundle arguments) {
        switch (virtualViewId) {
            case HOST_ID:
                return performActionForHost(action, arguments);
            default:
                return performActionForChild(virtualViewId, action, arguments);
        }
    }

    private boolean performActionForHost(int action, Bundle arguments) {
        return mView.performAccessibilityAction(action, arguments);
    }

    private boolean performActionForChild(int virtualViewId, int action, Bundle arguments) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                return manageFocusForChild(virtualViewId, action);
            default:
                return onPerformActionForVirtualView(virtualViewId, action, arguments);
        }
    }

    private boolean manageFocusForChild(int virtualViewId, int action) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                return requestAccessibilityFocus(virtualViewId);
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                return clearAccessibilityFocus(virtualViewId);
            default:
                return false;
        }
    }

    /**
     * Computes whether the specified {@link Rect} intersects with the visible
     * portion of its parent {@link View}. Modifies {@code localRect} to contain
     * only the visible portion.
     *
     * @param localRect A rectangle in local (parent) coordinates.
     * @return Whether the specified {@link Rect} is visible on the screen.
     */
    private boolean intersectVisibleToUser(Rect localRect) {
        // Missing or empty bounds mean this view is not visible.
        if ((localRect == null) || localRect.isEmpty()) {
            return false;
        }

        // Attached to invisible window means this view is not visible.
        if (mView.getWindowVisibility() != View.VISIBLE) {
            return false;
        }

        // An invisible predecessor means that this view is not visible.
        ViewParent viewParent = mView.getParent();
        while (viewParent instanceof View) {
            final View view = (View) viewParent;
            if ((view.getAlpha() <= 0) || (view.getVisibility() != View.VISIBLE)) {
                return false;
            }
            viewParent = view.getParent();
        }

        // A null parent implies the view is not visible.
        if (viewParent == null) {
            return false;
        }

        // If no portion of the parent is visible, this view is not visible.
        if (mTempVisibleRect == null) {
            mTempVisibleRect = new Rect();
        }
        final Rect tempVisibleRect = mTempVisibleRect;
        if (!mView.getLocalVisibleRect(tempVisibleRect)) {
            return false;
        }

        // Check if the view intersects the visible portion of the parent.
        return localRect.intersect(tempVisibleRect);
    }

    /**
     * Returns whether this virtual view is accessibility focused.
     *
     * @return True if the view is accessibility focused.
     */
    private boolean isAccessibilityFocused(int virtualViewId) {
        return (mFocusedVirtualViewId == virtualViewId);
    }

    /**
     * Attempts to give accessibility focus to a virtual view.
     * <p>
     * A virtual view will not actually take focus if
     * {@link AccessibilityManager#isEnabled()} returns false,
     * {@link AccessibilityManager#isTouchExplorationEnabled()} returns false,
     * or the view already has accessibility focus.
     *
     * @param virtualViewId The id of the virtual view on which to place
     *            accessibility focus.
     * @return Whether this virtual view actually took accessibility focus.
     */
    private boolean requestAccessibilityFocus(int virtualViewId) {
        final AccessibilityManager accessibilityManager =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        if (!mManager.isEnabled()
                || !accessibilityManager.isTouchExplorationEnabled()) {
            return false;
        }
        // TODO: Check virtual view visibility.
        if (!isAccessibilityFocused(virtualViewId)) {
            // Clear focus from the previously focused view, if applicable.
            if (mFocusedVirtualViewId != INVALID_ID) {
                sendEventForVirtualView(mFocusedVirtualViewId,
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            }

            // Set focus on the new view.
            mFocusedVirtualViewId = virtualViewId;

            // TODO: Only invalidate virtual view bounds.
            mView.invalidate();
            sendEventForVirtualView(virtualViewId,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            return true;
        }
        return false;
    }

    /**
     * Attempts to clear accessibility focus from a virtual view.
     *
     * @param virtualViewId The id of the virtual view from which to clear
     *            accessibility focus.
     * @return Whether this virtual view actually cleared accessibility focus.
     */
    private boolean clearAccessibilityFocus(int virtualViewId) {
        if (isAccessibilityFocused(virtualViewId)) {
            mFocusedVirtualViewId = INVALID_ID;
            mView.invalidate();
            sendEventForVirtualView(virtualViewId,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            return true;
        }
        return false;
    }

    /**
     * Provides a mapping between view-relative coordinates and logical
     * items.
     *
     * @param x The view-relative x coordinate
     * @param y The view-relative y coordinate
     * @return virtual view identifier for the logical item under
     *         coordinates (x,y)
     */
    protected abstract int getVirtualViewAt(float x, float y);

    /**
     * Populates a list with the view's visible items. The ordering of items
     * within {@code virtualViewIds} specifies order of accessibility focus
     * traversal.
     *
     * @param virtualViewIds The list to populate with visible items
     */
    protected abstract void getVisibleVirtualViews(IntArray virtualViewIds);

    /**
     * Populates an {@link AccessibilityEvent} with information about the
     * specified item.
     * <p>
     * Implementations <b>must</b> populate the following required fields:
     * <ul>
     * <li>event text, see {@link AccessibilityEvent#getText} or
     * {@link AccessibilityEvent#setContentDescription}
     * </ul>
     * <p>
     * The helper class automatically populates the following fields with
     * default values, but implementations may optionally override them:
     * <ul>
     * <li>item class name, set to android.view.View, see
     * {@link AccessibilityEvent#setClassName}
     * </ul>
     * <p>
     * The following required fields are automatically populated by the
     * helper class and may not be overridden:
     * <ul>
     * <li>package name, set to the package of the host view's
     * {@link Context}, see {@link AccessibilityEvent#setPackageName}
     * <li>event source, set to the host view and virtual view identifier,
     * see {@link android.view.accessibility.AccessibilityRecord#setSource(View, int)}
     * </ul>
     *
     * @param virtualViewId The virtual view id for the item for which to
     *            populate the event
     * @param event The event to populate
     */
    protected abstract void onPopulateEventForVirtualView(
            int virtualViewId, AccessibilityEvent event);

    /**
     * Populates an {@link AccessibilityEvent} with information about the host
     * view.
     * <p>
     * The default implementation is a no-op.
     *
     * @param event the event to populate with information about the host view
     */
    protected void onPopulateEventForHost(AccessibilityEvent event) {
        // Default implementation is no-op.
    }

    /**
     * Populates an {@link AccessibilityNodeInfo} with information
     * about the specified item.
     * <p>
     * Implementations <b>must</b> populate the following required fields:
     * <ul>
     * <li>event text, see {@link AccessibilityNodeInfo#setText} or
     * {@link AccessibilityNodeInfo#setContentDescription}
     * <li>bounds in parent coordinates, see
     * {@link AccessibilityNodeInfo#setBoundsInParent}
     * </ul>
     * <p>
     * The helper class automatically populates the following fields with
     * default values, but implementations may optionally override them:
     * <ul>
     * <li>enabled state, set to true, see
     * {@link AccessibilityNodeInfo#setEnabled}
     * <li>item class name, identical to the class name set by
     * {@link #onPopulateEventForVirtualView}, see
     * {@link AccessibilityNodeInfo#setClassName}
     * </ul>
     * <p>
     * The following required fields are automatically populated by the
     * helper class and may not be overridden:
     * <ul>
     * <li>package name, identical to the package name set by
     * {@link #onPopulateEventForVirtualView}, see
     * {@link AccessibilityNodeInfo#setPackageName}
     * <li>node source, identical to the event source set in
     * {@link #onPopulateEventForVirtualView}, see
     * {@link AccessibilityNodeInfo#setSource(View, int)}
     * <li>parent view, set to the host view, see
     * {@link AccessibilityNodeInfo#setParent(View)}
     * <li>visibility, computed based on parent-relative bounds, see
     * {@link AccessibilityNodeInfo#setVisibleToUser}
     * <li>accessibility focus, computed based on internal helper state, see
     * {@link AccessibilityNodeInfo#setAccessibilityFocused}
     * <li>bounds in screen coordinates, computed based on host view bounds,
     * see {@link AccessibilityNodeInfo#setBoundsInScreen}
     * </ul>
     * <p>
     * Additionally, the helper class automatically handles accessibility
     * focus management by adding the appropriate
     * {@link AccessibilityNodeInfo#ACTION_ACCESSIBILITY_FOCUS} or
     * {@link AccessibilityNodeInfo#ACTION_CLEAR_ACCESSIBILITY_FOCUS}
     * action. Implementations must <b>never</b> manually add these actions.
     * <p>
     * The helper class also automatically modifies parent- and
     * screen-relative bounds to reflect the portion of the item visible
     * within its parent.
     *
     * @param virtualViewId The virtual view identifier of the item for
     *            which to populate the node
     * @param node The node to populate
     */
    protected abstract void onPopulateNodeForVirtualView(
            int virtualViewId, AccessibilityNodeInfo node);

    /**
     * Populates an {@link AccessibilityNodeInfo} with information about the
     * host view.
     * <p>
     * The default implementation is a no-op.
     *
     * @param node the node to populate with information about the host view
     */
    protected void onPopulateNodeForHost(AccessibilityNodeInfo node) {
        // Default implementation is no-op.
    }

    /**
     * Performs the specified accessibility action on the item associated
     * with the virtual view identifier. See
     * {@link AccessibilityNodeInfo#performAction(int, Bundle)} for
     * more information.
     * <p>
     * Implementations <b>must</b> handle any actions added manually in
     * {@link #onPopulateNodeForVirtualView}.
     * <p>
     * The helper class automatically handles focus management resulting
     * from {@link AccessibilityNodeInfo#ACTION_ACCESSIBILITY_FOCUS}
     * and
     * {@link AccessibilityNodeInfo#ACTION_CLEAR_ACCESSIBILITY_FOCUS}
     * actions.
     *
     * @param virtualViewId The virtual view identifier of the item on which
     *            to perform the action
     * @param action The accessibility action to perform
     * @param arguments (Optional) A bundle with additional arguments, or
     *            null
     * @return true if the action was performed
     */
    protected abstract boolean onPerformActionForVirtualView(
            int virtualViewId, int action, Bundle arguments);

    /**
     * Exposes a virtual view hierarchy to the accessibility framework. Only
     * used in API 16+.
     */
    private class ExploreByTouchNodeProvider extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            return ExploreByTouchHelper.this.createNode(virtualViewId);
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            return ExploreByTouchHelper.this.performAction(virtualViewId, action, arguments);
        }
    }
}
