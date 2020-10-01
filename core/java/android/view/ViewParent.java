/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;

/**
 * Defines the responsibilities for a class that will be a parent of a View.
 * This is the API that a view sees when it wants to interact with its parent.
 * 
 */
public interface ViewParent {
    /**
     * Called when something has changed which has invalidated the layout of a
     * child of this view parent. This will schedule a layout pass of the view
     * tree.
     */
    public void requestLayout();

    /**
     * Indicates whether layout was requested on this view parent.
     *
     * @return true if layout was requested, false otherwise
     */
    public boolean isLayoutRequested();

    /**
     * Called when a child wants the view hierarchy to gather and report
     * transparent regions to the window compositor. Views that "punch" holes in
     * the view hierarchy, such as SurfaceView can use this API to improve
     * performance of the system. When no such a view is present in the
     * hierarchy, this optimization in unnecessary and might slightly reduce the
     * view hierarchy performance.
     * 
     * @param child the view requesting the transparent region computation
     * 
     */
    public void requestTransparentRegion(View child);


    /**
     * The target View has been invalidated, or has had a drawing property changed that
     * requires the hierarchy to re-render.
     *
     * This method is called by the View hierarchy to signal ancestors that a View either needs to
     * re-record its drawing commands, or drawing properties have changed. This is how Views
     * schedule a drawing traversal.
     *
     * This signal is generally only dispatched for attached Views, since only they need to draw.
     *
     * @param child Direct child of this ViewParent containing target
     * @param target The view that needs to redraw
     */
    default void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
        if (getParent() != null) {
            // Note: should pass 'this' as default, but can't since we may not be a View
            getParent().onDescendantInvalidated(child, target);
        }
    }

    /**
     * All or part of a child is dirty and needs to be redrawn.
     * 
     * @param child The child which is dirty
     * @param r The area within the child that is invalid
     *
     * @deprecated Use {@link #onDescendantInvalidated(View, View)} instead.
     */
    @Deprecated
    public void invalidateChild(View child, Rect r);

    /**
     * All or part of a child is dirty and needs to be redrawn.
     *
     * <p>The location array is an array of two int values which respectively
     * define the left and the top position of the dirty child.</p>
     *
     * <p>This method must return the parent of this ViewParent if the specified
     * rectangle must be invalidated in the parent. If the specified rectangle
     * does not require invalidation in the parent or if the parent does not
     * exist, this method must return null.</p>
     *
     * <p>When this method returns a non-null value, the location array must
     * have been updated with the left and top coordinates of this ViewParent.</p>
     *
     * @param location An array of 2 ints containing the left and top
     *        coordinates of the child to invalidate
     * @param r The area within the child that is invalid
     *
     * @return the parent of this ViewParent or null
     *
     * @deprecated Use {@link #onDescendantInvalidated(View, View)} instead.
     */
    @Deprecated
    public ViewParent invalidateChildInParent(int[] location, Rect r);

    /**
     * Returns the parent if it exists, or null.
     *
     * @return a ViewParent or null if this ViewParent does not have a parent
     */
    public ViewParent getParent();

    /**
     * Called when a child of this parent wants focus
     * 
     * @param child The child of this ViewParent that wants focus. This view
     *        will contain the focused view. It is not necessarily the view that
     *        actually has focus.
     * @param focused The view that is a descendant of child that actually has
     *        focus
     */
    public void requestChildFocus(View child, View focused);

    /**
     * Tell view hierarchy that the global view attributes need to be
     * re-evaluated.
     * 
     * @param child View whose attributes have changed.
     */
    public void recomputeViewAttributes(View child);
    
    /**
     * Called when a child of this parent is giving up focus
     * 
     * @param child The view that is giving up focus
     */
    public void clearChildFocus(View child);

    /**
     * Compute the visible part of a rectangular region defined in terms of a child view's
     * coordinates.
     *
     * <p>Returns the clipped visible part of the rectangle <code>r</code>, defined in the
     * <code>child</code>'s local coordinate system. <code>r</code> is modified by this method to
     * contain the result, expressed in the global (root) coordinate system.</p>
     *
     * <p>The resulting rectangle is always axis aligned. If a rotation is applied to a node in the
     * View hierarchy, the result is the axis-aligned bounding box of the visible rectangle.</p>
     *
     * @param child A child View, whose rectangular visible region we want to compute
     * @param r The input rectangle, defined in the child coordinate system. Will be overwritten to
     * contain the resulting visible rectangle, expressed in global (root) coordinates
     * @param offset The input coordinates of a point, defined in the child coordinate system.
     * As with the <code>r</code> parameter, this will be overwritten to contain the global (root)
     * coordinates of that point.
     * A <code>null</code> value is valid (in case you are not interested in this result)
     * @return true if the resulting rectangle is not empty, false otherwise
     */
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset);

    /**
     * Find the nearest view in the specified direction that wants to take focus
     * 
     * @param v The view that currently has focus
     * @param direction One of FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, and FOCUS_RIGHT
     */
    public View focusSearch(View v, int direction);

    /**
     * Find the nearest keyboard navigation cluster in the specified direction.
     * This does not actually give focus to that cluster.
     *
     * @param currentCluster The starting point of the search. Null means the current cluster is not
     *                       found yet
     * @param direction Direction to look
     *
     * @return The nearest keyboard navigation cluster in the specified direction, or null if none
     *         can be found
     */
    View keyboardNavigationClusterSearch(View currentCluster, int direction);

    /**
     * Change the z order of the child so it's on top of all other children.
     * This ordering change may affect layout, if this container
     * uses an order-dependent layout scheme (e.g., LinearLayout). Prior
     * to {@link android.os.Build.VERSION_CODES#KITKAT} this
     * method should be followed by calls to {@link #requestLayout()} and
     * {@link View#invalidate()} on this parent to force the parent to redraw
     * with the new child ordering.
     * 
     * @param child The child to bring to the top of the z order
     */
    public void bringChildToFront(View child);

    /**
     * Tells the parent that a new focusable view has become available. This is
     * to handle transitions from the case where there are no focusable views to
     * the case where the first focusable view appears.
     * 
     * @param v The view that has become newly focusable
     */
    public void focusableViewAvailable(View v);

    /**
     * Shows the context menu for the specified view or its ancestors.
     * <p>
     * In most cases, a subclass does not need to override this. However, if
     * the subclass is added directly to the window manager (for example,
     * {@link ViewManager#addView(View, android.view.ViewGroup.LayoutParams)})
     * then it should override this and show the context menu.
     *
     * @param originalView the source view where the context menu was first
     *                     invoked
     * @return {@code true} if the context menu was shown, {@code false}
     *         otherwise
     * @see #showContextMenuForChild(View, float, float)
     */
    public boolean showContextMenuForChild(View originalView);

    /**
     * Shows the context menu for the specified view or its ancestors anchored
     * to the specified view-relative coordinate.
     * <p>
     * In most cases, a subclass does not need to override this. However, if
     * the subclass is added directly to the window manager (for example,
     * {@link ViewManager#addView(View, android.view.ViewGroup.LayoutParams)})
     * then it should override this and show the context menu.
     * <p>
     * If a subclass overrides this method it should also override
     * {@link #showContextMenuForChild(View)}.
     *
     * @param originalView the source view where the context menu was first
     *                     invoked
     * @param x the X coordinate in pixels relative to the original view to
     *          which the menu should be anchored, or {@link Float#NaN} to
     *          disable anchoring
     * @param y the Y coordinate in pixels relative to the original view to
     *          which the menu should be anchored, or {@link Float#NaN} to
     *          disable anchoring
     * @return {@code true} if the context menu was shown, {@code false}
     *         otherwise
     */
    boolean showContextMenuForChild(View originalView, float x, float y);

    /**
     * Have the parent populate the specified context menu if it has anything to
     * add (and then recurse on its parent).
     * 
     * @param menu The menu to populate
     */
    public void createContextMenu(ContextMenu menu);

    /**
     * Start an action mode for the specified view with the default type
     * {@link ActionMode#TYPE_PRIMARY}.
     *
     * <p>In most cases, a subclass does not need to override this. However, if the
     * subclass is added directly to the window manager (for example,
     * {@link ViewManager#addView(View, android.view.ViewGroup.LayoutParams)})
     * then it should override this and start the action mode.</p>
     *
     * @param originalView The source view where the action mode was first invoked
     * @param callback The callback that will handle lifecycle events for the action mode
     * @return The new action mode if it was started, null otherwise
     *
     * @see #startActionModeForChild(View, android.view.ActionMode.Callback, int)
     */
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback);

    /**
     * Start an action mode of a specific type for the specified view.
     *
     * <p>In most cases, a subclass does not need to override this. However, if the
     * subclass is added directly to the window manager (for example,
     * {@link ViewManager#addView(View, android.view.ViewGroup.LayoutParams)})
     * then it should override this and start the action mode.</p>
     *
     * @param originalView The source view where the action mode was first invoked
     * @param callback The callback that will handle lifecycle events for the action mode
     * @param type One of {@link ActionMode#TYPE_PRIMARY} or {@link ActionMode#TYPE_FLOATING}.
     * @return The new action mode if it was started, null otherwise
     */
    public ActionMode startActionModeForChild(
            View originalView, ActionMode.Callback callback, int type);

    /**
     * This method is called on the parent when a child's drawable state
     * has changed.
     *
     * @param child The child whose drawable state has changed.
     */
    public void childDrawableStateChanged(View child);

    /**
     * Called when a child does not want this parent and its ancestors to
     * intercept touch events with
     * {@link ViewGroup#onInterceptTouchEvent(MotionEvent)}.
     *
     * <p>This parent should pass this call onto its parents. This parent must obey
     * this request for the duration of the touch (that is, only clear the flag
     * after this parent has received an up or a cancel.</p>
     * 
     * @param disallowIntercept True if the child does not want the parent to
     *            intercept touch events.
     */
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept);

    /**
     * Called when a child of this group wants a particular rectangle to be
     * positioned onto the screen.  {@link ViewGroup}s overriding this can trust
     * that:
     * <ul>
     *   <li>child will be a direct child of this group</li>
     *   <li>rectangle will be in the child's content coordinates</li>
     * </ul>
     *
     * <p>{@link ViewGroup}s overriding this should uphold the contract:</p>
     * <ul>
     *   <li>nothing will change if the rectangle is already visible</li>
     *   <li>the view port will be scrolled only just enough to make the
     *       rectangle visible</li>
     * <ul>
     *
     * @param child The direct child making the request.
     * @param rectangle The rectangle in the child's coordinates the child
     *        wishes to be on the screen.
     * @param immediate True to forbid animated or delayed scrolling,
     *        false otherwise
     * @return Whether the group scrolled to handle the operation
     */
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle,
            boolean immediate);

    /**
     * Called by a child to request from its parent to send an {@link AccessibilityEvent}.
     * The child has already populated a record for itself in the event and is delegating
     * to its parent to send the event. The parent can optionally add a record for itself.
     * <p>
     * Note: An accessibility event is fired by an individual view which populates the
     *       event with a record for its state and requests from its parent to perform
     *       the sending. The parent can optionally add a record for itself before
     *       dispatching the request to its parent. A parent can also choose not to
     *       respect the request for sending the event. The accessibility event is sent
     *       by the topmost view in the view tree.</p>
     *
     * @param child The child which requests sending the event.
     * @param event The event to be sent.
     * @return True if the event was sent.
     */
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event);

    /**
     * Called when a child view now has or no longer is tracking transient state.
     *
     * <p>"Transient state" is any state that a View might hold that is not expected to
     * be reflected in the data model that the View currently presents. This state only
     * affects the presentation to the user within the View itself, such as the current
     * state of animations in progress or the state of a text selection operation.</p>
     *
     * <p>Transient state is useful for hinting to other components of the View system
     * that a particular view is tracking something complex but encapsulated.
     * A <code>ListView</code> for example may acknowledge that list item Views
     * with transient state should be preserved within their position or stable item ID
     * instead of treating that view as trivially replaceable by the backing adapter.
     * This allows adapter implementations to be simpler instead of needing to track
     * the state of item view animations in progress such that they could be restored
     * in the event of an unexpected recycling and rebinding of attached item views.</p>
     *
     * <p>This method is called on a parent view when a child view or a view within
     * its subtree begins or ends tracking of internal transient state.</p>
     *
     * @param child Child view whose state has changed
     * @param hasTransientState true if this child has transient state
     */
    public void childHasTransientStateChanged(View child, boolean hasTransientState);

    /**
     * Ask that a new dispatch of {@link View#fitSystemWindows(Rect)
     * View.fitSystemWindows(Rect)} be performed.
     */
    public void requestFitSystemWindows();

    /**
     * Gets the parent of a given View for accessibility. Since some Views are not
     * exposed to the accessibility layer the parent for accessibility is not
     * necessarily the direct parent of the View, rather it is a predecessor.
     *
     * @return The parent or <code>null</code> if no such is found.
     */
    public ViewParent getParentForAccessibility();

    /**
     * Notifies a view parent that the accessibility state of one of its
     * descendants has changed and that the structure of the subtree is
     * different.
     * @param child The direct child whose subtree has changed.
     * @param source The descendant view that changed. May not be {@code null}.
     * @param changeType A bit mask of the types of changes that occurred. One
     *            or more of:
     *            <ul>
     *            <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION}
     *            <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_STATE_DESCRIPTION}
     *            <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_SUBTREE}
     *            <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_TEXT}
     *            <li>{@link AccessibilityEvent#CONTENT_CHANGE_TYPE_UNDEFINED}
     *            </ul>
     */
    public void notifySubtreeAccessibilityStateChanged(
            View child, @NonNull View source, int changeType);

    /**
     * Tells if this view parent can resolve the layout direction.
     * See {@link View#setLayoutDirection(int)}
     *
     * @return True if this view parent can resolve the layout direction.
     */
    public boolean canResolveLayoutDirection();

    /**
     * Tells if this view parent layout direction is resolved.
     * See {@link View#setLayoutDirection(int)}
     *
     * @return True if this view parent layout direction is resolved.
     */
    public boolean isLayoutDirectionResolved();

    /**
     * Return this view parent layout direction. See {@link View#getLayoutDirection()}
     *
     * @return {@link View#LAYOUT_DIRECTION_RTL} if the layout direction is RTL or returns
     * {@link View#LAYOUT_DIRECTION_LTR} if the layout direction is not RTL.
     */
    public int getLayoutDirection();

    /**
     * Tells if this view parent can resolve the text direction.
     * See {@link View#setTextDirection(int)}
     *
     * @return True if this view parent can resolve the text direction.
     */
    public boolean canResolveTextDirection();

    /**
     * Tells if this view parent text direction is resolved.
     * See {@link View#setTextDirection(int)}
     *
     * @return True if this view parent text direction is resolved.
     */
    public boolean isTextDirectionResolved();

    /**
     * Return this view parent text direction. See {@link View#getTextDirection()}
     *
     * @return the resolved text direction. Returns one of:
     *
     * {@link View#TEXT_DIRECTION_FIRST_STRONG}
     * {@link View#TEXT_DIRECTION_ANY_RTL},
     * {@link View#TEXT_DIRECTION_LTR},
     * {@link View#TEXT_DIRECTION_RTL},
     * {@link View#TEXT_DIRECTION_LOCALE}
     */
    public int getTextDirection();

    /**
     * Tells if this view parent can resolve the text alignment.
     * See {@link View#setTextAlignment(int)}
     *
     * @return True if this view parent can resolve the text alignment.
     */
    public boolean canResolveTextAlignment();

    /**
     * Tells if this view parent text alignment is resolved.
     * See {@link View#setTextAlignment(int)}
     *
     * @return True if this view parent text alignment is resolved.
     */
    public boolean isTextAlignmentResolved();

    /**
     * Return this view parent text alignment. See {@link android.view.View#getTextAlignment()}
     *
     * @return the resolved text alignment. Returns one of:
     *
     * {@link View#TEXT_ALIGNMENT_GRAVITY},
     * {@link View#TEXT_ALIGNMENT_CENTER},
     * {@link View#TEXT_ALIGNMENT_TEXT_START},
     * {@link View#TEXT_ALIGNMENT_TEXT_END},
     * {@link View#TEXT_ALIGNMENT_VIEW_START},
     * {@link View#TEXT_ALIGNMENT_VIEW_END}
     */
    public int getTextAlignment();

    /**
     * React to a descendant view initiating a nestable scroll operation, claiming the
     * nested scroll operation if appropriate.
     *
     * <p>This method will be called in response to a descendant view invoking
     * {@link View#startNestedScroll(int)}. Each parent up the view hierarchy will be
     * given an opportunity to respond and claim the nested scrolling operation by returning
     * <code>true</code>.</p>
     *
     * <p>This method may be overridden by ViewParent implementations to indicate when the view
     * is willing to support a nested scrolling operation that is about to begin. If it returns
     * true, this ViewParent will become the target view's nested scrolling parent for the duration
     * of the scroll operation in progress. When the nested scroll is finished this ViewParent
     * will receive a call to {@link #onStopNestedScroll(View)}.
     * </p>
     *
     * @param child Direct child of this ViewParent containing target
     * @param target View that initiated the nested scroll
     * @param nestedScrollAxes Flags consisting of {@link View#SCROLL_AXIS_HORIZONTAL},
     *                         {@link View#SCROLL_AXIS_VERTICAL} or both
     * @return true if this ViewParent accepts the nested scroll operation
     */
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes);

    /**
     * React to the successful claiming of a nested scroll operation.
     *
     * <p>This method will be called after
     * {@link #onStartNestedScroll(View, View, int) onStartNestedScroll} returns true. It offers
     * an opportunity for the view and its superclasses to perform initial configuration
     * for the nested scroll. Implementations of this method should always call their superclass's
     * implementation of this method if one is present.</p>
     *
     * @param child Direct child of this ViewParent containing target
     * @param target View that initiated the nested scroll
     * @param nestedScrollAxes Flags consisting of {@link View#SCROLL_AXIS_HORIZONTAL},
     *                         {@link View#SCROLL_AXIS_VERTICAL} or both
     * @see #onStartNestedScroll(View, View, int)
     * @see #onStopNestedScroll(View)
     */
    public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes);

    /**
     * React to a nested scroll operation ending.
     *
     * <p>Perform cleanup after a nested scrolling operation.
     * This method will be called when a nested scroll stops, for example when a nested touch
     * scroll ends with a {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL} event.
     * Implementations of this method should always call their superclass's implementation of this
     * method if one is present.</p>
     *
     * @param target View that initiated the nested scroll
     */
    public void onStopNestedScroll(View target);

    /**
     * React to a nested scroll in progress.
     *
     * <p>This method will be called when the ViewParent's current nested scrolling child view
     * dispatches a nested scroll event. To receive calls to this method the ViewParent must have
     * previously returned <code>true</code> for a call to
     * {@link #onStartNestedScroll(View, View, int)}.</p>
     *
     * <p>Both the consumed and unconsumed portions of the scroll distance are reported to the
     * ViewParent. An implementation may choose to use the consumed portion to match or chase scroll
     * position of multiple child elements, for example. The unconsumed portion may be used to
     * allow continuous dragging of multiple scrolling or draggable elements, such as scrolling
     * a list within a vertical drawer where the drawer begins dragging once the edge of inner
     * scrolling content is reached.</p>
     *
     * @param target The descendent view controlling the nested scroll
     * @param dxConsumed Horizontal scroll distance in pixels already consumed by target
     * @param dyConsumed Vertical scroll distance in pixels already consumed by target
     * @param dxUnconsumed Horizontal scroll distance in pixels not consumed by target
     * @param dyUnconsumed Vertical scroll distance in pixels not consumed by target
     */
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed);

    /**
     * React to a nested scroll in progress before the target view consumes a portion of the scroll.
     *
     * <p>When working with nested scrolling often the parent view may want an opportunity
     * to consume the scroll before the nested scrolling child does. An example of this is a
     * drawer that contains a scrollable list. The user will want to be able to scroll the list
     * fully into view before the list itself begins scrolling.</p>
     *
     * <p><code>onNestedPreScroll</code> is called when a nested scrolling child invokes
     * {@link View#dispatchNestedPreScroll(int, int, int[], int[])}. The implementation should
     * report how any pixels of the scroll reported by dx, dy were consumed in the
     * <code>consumed</code> array. Index 0 corresponds to dx and index 1 corresponds to dy.
     * This parameter will never be null. Initial values for consumed[0] and consumed[1]
     * will always be 0.</p>
     *
     * @param target View that initiated the nested scroll
     * @param dx Horizontal scroll distance in pixels
     * @param dy Vertical scroll distance in pixels
     * @param consumed Output. The horizontal and vertical scroll distance consumed by this parent
     */
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed);

    /**
     * Request a fling from a nested scroll.
     *
     * <p>This method signifies that a nested scrolling child has detected suitable conditions
     * for a fling. Generally this means that a touch scroll has ended with a
     * {@link VelocityTracker velocity} in the direction of scrolling that meets or exceeds
     * the {@link ViewConfiguration#getScaledMinimumFlingVelocity() minimum fling velocity}
     * along a scrollable axis.</p>
     *
     * <p>If a nested scrolling child view would normally fling but it is at the edge of
     * its own content, it can use this method to delegate the fling to its nested scrolling
     * parent instead. The parent may optionally consume the fling or observe a child fling.</p>
     *
     * @param target View that initiated the nested scroll
     * @param velocityX Horizontal velocity in pixels per second
     * @param velocityY Vertical velocity in pixels per second
     * @param consumed true if the child consumed the fling, false otherwise
     * @return true if this parent consumed or otherwise reacted to the fling
     */
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed);

    /**
     * React to a nested fling before the target view consumes it.
     *
     * <p>This method siginfies that a nested scrolling child has detected a fling with the given
     * velocity along each axis. Generally this means that a touch scroll has ended with a
     * {@link VelocityTracker velocity} in the direction of scrolling that meets or exceeds
     * the {@link ViewConfiguration#getScaledMinimumFlingVelocity() minimum fling velocity}
     * along a scrollable axis.</p>
     *
     * <p>If a nested scrolling parent is consuming motion as part of a
     * {@link #onNestedPreScroll(View, int, int, int[]) pre-scroll}, it may be appropriate for
     * it to also consume the pre-fling to complete that same motion. By returning
     * <code>true</code> from this method, the parent indicates that the child should not
     * fling its own internal content as well.</p>
     *
     * @param target View that initiated the nested scroll
     * @param velocityX Horizontal velocity in pixels per second
     * @param velocityY Vertical velocity in pixels per second
     * @return true if this parent consumed the fling ahead of the target view
     */
    public boolean onNestedPreFling(View target, float velocityX, float velocityY);

    /**
     * React to an accessibility action delegated by a target descendant view before the target
     * processes it.
     *
     * <p>This method may be called by a target descendant view if the target wishes to give
     * a view in its parent chain a chance to react to the event before normal processing occurs.
     * Most commonly this will be a scroll event such as
     * {@link android.view.accessibility.AccessibilityNodeInfo#ACTION_SCROLL_FORWARD}.
     * A ViewParent that supports acting as a nested scrolling parent should override this
     * method and act accordingly to implement scrolling via accesibility systems.</p>
     *
     * @param target The target view dispatching this action
     * @param action Action being performed; see
     *               {@link android.view.accessibility.AccessibilityNodeInfo}
     * @param arguments Optional action arguments
     * @return true if the action was consumed by this ViewParent
     */
    public boolean onNestedPrePerformAccessibilityAction(View target, int action, Bundle arguments);

    /**
     * Given a touchable region of a child, this method reduces region by the bounds of all views on
     * top of the child for which {@link View#canReceivePointerEvents} returns {@code true}. This
     * applies recursively for all views in the view hierarchy on top of this one.
     *
     * @param touchableRegion The touchable region we want to modify.
     * @param view A child view of this ViewGroup which indicates the z-order of the touchable
     *             region.
     * @hide
     */
    default void subtractObscuredTouchableRegion(Region touchableRegion, View view) {
    }

    /**
     * Unbuffered dispatch has been requested by a child of this view parent.
     * This method is called by the View hierarchy to signal ancestors that a View needs to
     * request unbuffered dispatch.
     *
     * @see View#requestUnbufferedDispatch(int)
     * @hide
     */
    default void onDescendantUnbufferedRequested() {
        if (getParent() != null) {
            getParent().onDescendantUnbufferedRequested();
        }
    }
}
