/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools.SynchronizedPool;
import android.util.SparseLongArray;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * This class represents a node of the window content as well as actions that
 * can be requested from its source. From the point of view of an
 * {@link android.accessibilityservice.AccessibilityService} a window content is
 * presented as tree of accessibility node info which may or may not map one-to-one
 * to the view hierarchy. In other words, a custom view is free to report itself as
 * a tree of accessibility node info.
 * </p>
 * <p>
 * Once an accessibility node info is delivered to an accessibility service it is
 * made immutable and calling a state mutation method generates an error.
 * </p>
 * <p>
 * Please refer to {@link android.accessibilityservice.AccessibilityService} for
 * details about how to obtain a handle to window content as a tree of accessibility
 * node info as well as familiarizing with the security model.
 * </p>
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about making applications accessible, read the
 * <a href="{@docRoot}guide/topics/ui/accessibility/index.html">Accessibility</a>
 * developer guide.</p>
 * </div>
 *
 * @see android.accessibilityservice.AccessibilityService
 * @see AccessibilityEvent
 * @see AccessibilityManager
 */
public class AccessibilityNodeInfo implements Parcelable {

    private static final boolean DEBUG = false;

    /** @hide */
    public static final int UNDEFINED = -1;

    /** @hide */
    public static final long ROOT_NODE_ID = makeNodeId(UNDEFINED, UNDEFINED);

    /** @hide */
    public static final int ACTIVE_WINDOW_ID = UNDEFINED;

    /** @hide */
    public static final int FLAG_PREFETCH_PREDECESSORS = 0x00000001;

    /** @hide */
    public static final int FLAG_PREFETCH_SIBLINGS = 0x00000002;

    /** @hide */
    public static final int FLAG_PREFETCH_DESCENDANTS = 0x00000004;

    /** @hide */
    public static final int FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 0x00000008;

    /** @hide */
    public static final int FLAG_REPORT_VIEW_IDS = 0x00000010;

    // Actions.

    /**
     * Action that gives input focus to the node.
     */
    public static final int ACTION_FOCUS =  0x00000001;

    /**
     * Action that clears input focus of the node.
     */
    public static final int ACTION_CLEAR_FOCUS = 0x00000002;

    /**
     * Action that selects the node.
     */
    public static final int ACTION_SELECT = 0x00000004;

    /**
     * Action that unselects the node.
     */
    public static final int ACTION_CLEAR_SELECTION = 0x00000008;

    /**
     * Action that clicks on the node info.
     */
    public static final int ACTION_CLICK = 0x00000010;

    /**
     * Action that long clicks on the node.
     */
    public static final int ACTION_LONG_CLICK = 0x00000020;

    /**
     * Action that gives accessibility focus to the node.
     */
    public static final int ACTION_ACCESSIBILITY_FOCUS = 0x00000040;

    /**
     * Action that clears accessibility focus of the node.
     */
    public static final int ACTION_CLEAR_ACCESSIBILITY_FOCUS = 0x00000080;

    /**
     * Action that requests to go to the next entity in this node's text
     * at a given movement granularity. For example, move to the next character,
     * word, etc.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT}<,
     * {@link #ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN}<br>
     * <strong>Example:</strong> Move to the previous character and do not extend selection.
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
     *           AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
     *   arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
     *           false);
     *   info.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, arguments);
     * </code></pre></p>
     * </p>
     *
     * @see #ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
     * @see #ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
     *
     * @see #setMovementGranularities(int)
     * @see #getMovementGranularities()
     *
     * @see #MOVEMENT_GRANULARITY_CHARACTER
     * @see #MOVEMENT_GRANULARITY_WORD
     * @see #MOVEMENT_GRANULARITY_LINE
     * @see #MOVEMENT_GRANULARITY_PARAGRAPH
     * @see #MOVEMENT_GRANULARITY_PAGE
     */
    public static final int ACTION_NEXT_AT_MOVEMENT_GRANULARITY = 0x00000100;

    /**
     * Action that requests to go to the previous entity in this node's text
     * at a given movement granularity. For example, move to the next character,
     * word, etc.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT}<,
     * {@link #ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN}<br>
     * <strong>Example:</strong> Move to the next character and do not extend selection.
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
     *           AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
     *   arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
     *           false);
     *   info.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
     *           arguments);
     * </code></pre></p>
     * </p>
     *
     * @see #ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
     * @see #ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
     *
     * @see #setMovementGranularities(int)
     * @see #getMovementGranularities()
     *
     * @see #MOVEMENT_GRANULARITY_CHARACTER
     * @see #MOVEMENT_GRANULARITY_WORD
     * @see #MOVEMENT_GRANULARITY_LINE
     * @see #MOVEMENT_GRANULARITY_PARAGRAPH
     * @see #MOVEMENT_GRANULARITY_PAGE
     */
    public static final int ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY = 0x00000200;

    /**
     * Action to move to the next HTML element of a given type. For example, move
     * to the BUTTON, INPUT, TABLE, etc.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_HTML_ELEMENT_STRING}<br>
     * <strong>Example:</strong>
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "BUTTON");
     *   info.performAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, arguments);
     * </code></pre></p>
     * </p>
     */
    public static final int ACTION_NEXT_HTML_ELEMENT = 0x00000400;

    /**
     * Action to move to the previous HTML element of a given type. For example, move
     * to the BUTTON, INPUT, TABLE, etc.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_HTML_ELEMENT_STRING}<br>
     * <strong>Example:</strong>
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "BUTTON");
     *   info.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, arguments);
     * </code></pre></p>
     * </p>
     */
    public static final int ACTION_PREVIOUS_HTML_ELEMENT = 0x00000800;

    /**
     * Action to scroll the node content forward.
     */
    public static final int ACTION_SCROLL_FORWARD = 0x00001000;

    /**
     * Action to scroll the node content backward.
     */
    public static final int ACTION_SCROLL_BACKWARD = 0x00002000;

    /**
     * Action to copy the current selection to the clipboard.
     */
    public static final int ACTION_COPY = 0x00004000;

    /**
     * Action to paste the current clipboard content.
     */
    public static final int ACTION_PASTE = 0x00008000;

    /**
     * Action to cut the current selection and place it to the clipboard.
     */
    public static final int ACTION_CUT = 0x00010000;

    /**
     * Action to set the selection. Performing this action with no arguments
     * clears the selection.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_SELECTION_START_INT},
     * {@link #ACTION_ARGUMENT_SELECTION_END_INT}<br>
     * <strong>Example:</strong>
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1);
     *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2);
     *   info.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments);
     * </code></pre></p>
     * </p>
     *
     * @see #ACTION_ARGUMENT_SELECTION_START_INT
     * @see #ACTION_ARGUMENT_SELECTION_END_INT
     */
    public static final int ACTION_SET_SELECTION = 0x00020000;

    /**
     * Argument for which movement granularity to be used when traversing the node text.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong> {@link #ACTION_NEXT_AT_MOVEMENT_GRANULARITY},
     * {@link #ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY}
     * </p>
     *
     * @see #ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     * @see #ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
     */
    public static final String ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT =
            "ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT";

    /**
     * Argument for which HTML element to get moving to the next/previous HTML element.
     * <p>
     * <strong>Type:</strong> String<br>
     * <strong>Actions:</strong> {@link #ACTION_NEXT_HTML_ELEMENT},
     *         {@link #ACTION_PREVIOUS_HTML_ELEMENT}
     * </p>
     *
     * @see #ACTION_NEXT_HTML_ELEMENT
     * @see #ACTION_PREVIOUS_HTML_ELEMENT
     */
    public static final String ACTION_ARGUMENT_HTML_ELEMENT_STRING =
            "ACTION_ARGUMENT_HTML_ELEMENT_STRING";

    /**
     * Argument for whether when moving at granularity to extend the selection
     * or to move it otherwise.
     * <p>
     * <strong>Type:</strong> boolean<br>
     * <strong>Actions:</strong> {@link #ACTION_NEXT_AT_MOVEMENT_GRANULARITY},
     * {@link #ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY}
     * </p>
     *
     * @see #ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     * @see #ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
     */
    public static final String ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN =
            "ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN";

    /**
     * Argument for specifying the selection start.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong> {@link #ACTION_SET_SELECTION}
     * </p>
     *
     * @see #ACTION_SET_SELECTION
     */
    public static final String ACTION_ARGUMENT_SELECTION_START_INT =
            "ACTION_ARGUMENT_SELECTION_START_INT";

    /**
     * Argument for specifying the selection end.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong> {@link #ACTION_SET_SELECTION}
     * </p>
     *
     * @see #ACTION_SET_SELECTION
     */
    public static final String ACTION_ARGUMENT_SELECTION_END_INT =
            "ACTION_ARGUMENT_SELECTION_END_INT";

    /**
     * The input focus.
     */
    public static final int FOCUS_INPUT = 1;

    /**
     * The accessibility focus.
     */
    public static final int FOCUS_ACCESSIBILITY = 2;

    // Movement granularities

    /**
     * Movement granularity bit for traversing the text of a node by character.
     */
    public static final int MOVEMENT_GRANULARITY_CHARACTER = 0x00000001;

    /**
     * Movement granularity bit for traversing the text of a node by word.
     */
    public static final int MOVEMENT_GRANULARITY_WORD = 0x00000002;

    /**
     * Movement granularity bit for traversing the text of a node by line.
     */
    public static final int MOVEMENT_GRANULARITY_LINE = 0x00000004;

    /**
     * Movement granularity bit for traversing the text of a node by paragraph.
     */
    public static final int MOVEMENT_GRANULARITY_PARAGRAPH = 0x00000008;

    /**
     * Movement granularity bit for traversing the text of a node by page.
     */
    public static final int MOVEMENT_GRANULARITY_PAGE = 0x00000010;

    // Boolean attributes.

    private static final int BOOLEAN_PROPERTY_CHECKABLE = 0x00000001;

    private static final int BOOLEAN_PROPERTY_CHECKED = 0x00000002;

    private static final int BOOLEAN_PROPERTY_FOCUSABLE = 0x00000004;

    private static final int BOOLEAN_PROPERTY_FOCUSED = 0x00000008;

    private static final int BOOLEAN_PROPERTY_SELECTED = 0x00000010;

    private static final int BOOLEAN_PROPERTY_CLICKABLE = 0x00000020;

    private static final int BOOLEAN_PROPERTY_LONG_CLICKABLE = 0x00000040;

    private static final int BOOLEAN_PROPERTY_ENABLED = 0x00000080;

    private static final int BOOLEAN_PROPERTY_PASSWORD = 0x00000100;

    private static final int BOOLEAN_PROPERTY_SCROLLABLE = 0x00000200;

    private static final int BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED = 0x00000400;

    private static final int BOOLEAN_PROPERTY_VISIBLE_TO_USER = 0x00000800;

    private static final int BOOLEAN_PROPERTY_EDITABLE = 0x00001000;

    /**
     * Bits that provide the id of a virtual descendant of a view.
     */
    private static final long VIRTUAL_DESCENDANT_ID_MASK = 0xffffffff00000000L;

    /**
     * Bit shift of {@link #VIRTUAL_DESCENDANT_ID_MASK} to get to the id for a
     * virtual descendant of a view. Such a descendant does not exist in the view
     * hierarchy and is only reported via the accessibility APIs.
     */
    private static final int VIRTUAL_DESCENDANT_ID_SHIFT = 32;

    /**
     * Gets the accessibility view id which identifies a View in the view three.
     *
     * @param accessibilityNodeId The id of an {@link AccessibilityNodeInfo}.
     * @return The accessibility view id part of the node id.
     *
     * @hide
     */
    public static int getAccessibilityViewId(long accessibilityNodeId) {
        return (int) accessibilityNodeId;
    }

    /**
     * Gets the virtual descendant id which identifies an imaginary view in a
     * containing View.
     *
     * @param accessibilityNodeId The id of an {@link AccessibilityNodeInfo}.
     * @return The virtual view id part of the node id.
     *
     * @hide
     */
    public static int getVirtualDescendantId(long accessibilityNodeId) {
        return (int) ((accessibilityNodeId & VIRTUAL_DESCENDANT_ID_MASK)
                >> VIRTUAL_DESCENDANT_ID_SHIFT);
    }

    /**
     * Makes a node id by shifting the <code>virtualDescendantId</code>
     * by {@link #VIRTUAL_DESCENDANT_ID_SHIFT} and taking
     * the bitwise or with the <code>accessibilityViewId</code>.
     *
     * @param accessibilityViewId A View accessibility id.
     * @param virtualDescendantId A virtual descendant id.
     * @return The node id.
     *
     * @hide
     */
    public static long makeNodeId(int accessibilityViewId, int virtualDescendantId) {
        return (((long) virtualDescendantId) << VIRTUAL_DESCENDANT_ID_SHIFT) | accessibilityViewId;
    }

    // Housekeeping.
    private static final int MAX_POOL_SIZE = 50;
    private static final SynchronizedPool<AccessibilityNodeInfo> sPool =
            new SynchronizedPool<AccessibilityNodeInfo>(MAX_POOL_SIZE);

    private boolean mSealed;

    // Data.
    private int mWindowId = UNDEFINED;
    private long mSourceNodeId = ROOT_NODE_ID;
    private long mParentNodeId = ROOT_NODE_ID;
    private long mLabelForId = ROOT_NODE_ID;
    private long mLabeledById = ROOT_NODE_ID;

    private int mBooleanProperties;
    private final Rect mBoundsInParent = new Rect();
    private final Rect mBoundsInScreen = new Rect();

    private CharSequence mPackageName;
    private CharSequence mClassName;
    private CharSequence mText;
    private CharSequence mContentDescription;
    private String mViewIdResourceName;

    private final SparseLongArray mChildNodeIds = new SparseLongArray();
    private int mActions;

    private int mMovementGranularities;

    private int mTextSelectionStart = UNDEFINED;
    private int mTextSelectionEnd = UNDEFINED;

    private int mConnectionId = UNDEFINED;

    /**
     * Hide constructor from clients.
     */
    private AccessibilityNodeInfo() {
        /* do nothing */
    }

    /**
     * Sets the source.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param source The info source.
     */
    public void setSource(View source) {
        setSource(source, UNDEFINED);
    }

    /**
     * Sets the source to be a virtual descendant of the given <code>root</code>.
     * If <code>virtualDescendantId</code> is {@link View#NO_ID} the root
     * is set as the source.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report themselves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public void setSource(View root, int virtualDescendantId) {
        enforceNotSealed();
        mWindowId = (root != null) ? root.getAccessibilityWindowId() : UNDEFINED;
        final int rootAccessibilityViewId =
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED;
        mSourceNodeId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Find the view that has the specified focus type. The search starts from
     * the view represented by this node info.
     *
     * @param focus The focus to find. One of {@link #FOCUS_INPUT} or
     *         {@link #FOCUS_ACCESSIBILITY}.
     * @return The node info of the focused view or null.
     *
     * @see #FOCUS_INPUT
     * @see #FOCUS_ACCESSIBILITY
     */
    public AccessibilityNodeInfo findFocus(int focus) {
        enforceSealed();
        enforceValidFocusType(focus);
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return null;
        }
        return AccessibilityInteractionClient.getInstance().findFocus(mConnectionId, mWindowId,
                mSourceNodeId, focus);
    }

    /**
     * Searches for the nearest view in the specified direction that can take
     * the input focus.
     *
     * @param direction The direction. Can be one of:
     *     {@link View#FOCUS_DOWN},
     *     {@link View#FOCUS_UP},
     *     {@link View#FOCUS_LEFT},
     *     {@link View#FOCUS_RIGHT},
     *     {@link View#FOCUS_FORWARD},
     *     {@link View#FOCUS_BACKWARD}.
     *
     * @return The node info for the view that can take accessibility focus.
     */
    public AccessibilityNodeInfo focusSearch(int direction) {
        enforceSealed();
        enforceValidFocusDirection(direction);
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return null;
        }
        return AccessibilityInteractionClient.getInstance().focusSearch(mConnectionId, mWindowId,
                mSourceNodeId, direction);
    }

    /**
     * Gets the id of the window from which the info comes from.
     *
     * @return The window id.
     */
    public int getWindowId() {
        return mWindowId;
    }

    /**
     * Refreshes this info with the latest state of the view it represents.
     * <p>
     * <strong>Note:</strong> If this method returns false this info is obsolete
     * since it represents a view that is no longer in the view tree and should
     * be recycled.
     * </p>
     * @return Whether the refresh succeeded.
     */
    public boolean refresh() {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return false;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        AccessibilityNodeInfo refreshedInfo = client.findAccessibilityNodeInfoByAccessibilityId(
                mConnectionId, mWindowId, mSourceNodeId, 0);
        if (refreshedInfo == null) {
            return false;
        }
        init(refreshedInfo);
        refreshedInfo.recycle();
        return true;
    }

    /**
     * @return The ids of the children.
     *
     * @hide
     */
    public SparseLongArray getChildNodeIds() {
        return mChildNodeIds;
    }

    /**
     * Gets the number of children.
     *
     * @return The child count.
     */
    public int getChildCount() {
        return mChildNodeIds.size();
    }

    /**
     * Get the child at given index.
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     *
     * @param index The child index.
     * @return The child node.
     *
     * @throws IllegalStateException If called outside of an AccessibilityService.
     *
     */
    public AccessibilityNodeInfo getChild(int index) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return null;
        }
        final long childId = mChildNodeIds.get(index);
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mWindowId,
                childId, FLAG_PREFETCH_DESCENDANTS);
    }

    /**
     * Adds a child.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param child The child.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void addChild(View child) {
        addChild(child, UNDEFINED);
    }

    /**
     * Adds a virtual child which is a descendant of the given <code>root</code>.
     * If <code>virtualDescendantId</code> is {@link View#NO_ID} the root
     * is added as a child.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report them selves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual child.
     */
    public void addChild(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int index = mChildNodeIds.size();
        final int rootAccessibilityViewId =
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED;
        final long childNodeId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
        mChildNodeIds.put(index, childNodeId);
    }

    /**
     * Gets the actions that can be performed on the node.
     *
     * @return The bit mask of with actions.
     *
     * @see AccessibilityNodeInfo#ACTION_FOCUS
     * @see AccessibilityNodeInfo#ACTION_CLEAR_FOCUS
     * @see AccessibilityNodeInfo#ACTION_SELECT
     * @see AccessibilityNodeInfo#ACTION_CLEAR_SELECTION
     * @see AccessibilityNodeInfo#ACTION_ACCESSIBILITY_FOCUS
     * @see AccessibilityNodeInfo#ACTION_CLEAR_ACCESSIBILITY_FOCUS
     * @see AccessibilityNodeInfo#ACTION_CLICK
     * @see AccessibilityNodeInfo#ACTION_LONG_CLICK
     * @see AccessibilityNodeInfo#ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     * @see AccessibilityNodeInfo#ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
     * @see AccessibilityNodeInfo#ACTION_NEXT_HTML_ELEMENT
     * @see AccessibilityNodeInfo#ACTION_PREVIOUS_HTML_ELEMENT
     * @see AccessibilityNodeInfo#ACTION_SCROLL_FORWARD
     * @see AccessibilityNodeInfo#ACTION_SCROLL_BACKWARD
     */
    public int getActions() {
        return mActions;
    }

    /**
     * Adds an action that can be performed on the node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param action The action.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void addAction(int action) {
        enforceNotSealed();
        mActions |= action;
    }

    /**
     * Sets the movement granularities for traversing the text of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param granularities The bit mask with granularities.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setMovementGranularities(int granularities) {
        enforceNotSealed();
        mMovementGranularities = granularities;
    }

    /**
     * Gets the movement granularities for traversing the text of this node.
     *
     * @return The bit mask with granularities.
     */
    public int getMovementGranularities() {
        return mMovementGranularities;
    }

    /**
     * Performs an action on the node.
     * <p>
     *   <strong>Note:</strong> An action can be performed only if the request is made
     *   from an {@link android.accessibilityservice.AccessibilityService}.
     * </p>
     *
     * @param action The action to perform.
     * @return True if the action was performed.
     *
     * @throws IllegalStateException If called outside of an AccessibilityService.
     */
    public boolean performAction(int action) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return false;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.performAccessibilityAction(mConnectionId, mWindowId, mSourceNodeId,
                action, null);
    }

    /**
     * Performs an action on the node.
     * <p>
     *   <strong>Note:</strong> An action can be performed only if the request is made
     *   from an {@link android.accessibilityservice.AccessibilityService}.
     * </p>
     *
     * @param action The action to perform.
     * @param arguments A bundle with additional arguments.
     * @return True if the action was performed.
     *
     * @throws IllegalStateException If called outside of an AccessibilityService.
     */
    public boolean performAction(int action, Bundle arguments) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return false;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.performAccessibilityAction(mConnectionId, mWindowId, mSourceNodeId,
                action, arguments);
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by text. The match is case
     * insensitive containment. The search is relative to this info i.e.
     * this info is the root of the traversed tree.
     *
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     *
     * @param text The searched text.
     * @return A list of node info.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return Collections.emptyList();
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfosByText(mConnectionId, mWindowId, mSourceNodeId,
                text);
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by the fully qualified view id's resource
     * name where a fully qualified id is of the from "package:id/id_resource_name".
     * For example, if the target application's package is "foo.bar" and the id
     * resource name is "baz", the fully qualified resource id is "foo.bar:id/baz".
     *
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     * <p>
     *   <strong>Note:</strong> The primary usage of this API is for UI test automation
     *   and in order to report the fully qualified view id if an {@link AccessibilityNodeInfo}
     *   the client has to set the {@link AccessibilityServiceInfo#FLAG_REPORT_VIEW_IDS}
     *   flag when configuring his {@link android.accessibilityservice.AccessibilityService}.
     * </p>
     *
     * @param viewId The fully qualified resource name of the view id to find.
     * @return A list of node info.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String viewId) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return Collections.emptyList();
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfosByViewId(mConnectionId, mWindowId, mSourceNodeId,
                viewId);
    }

    /**
     * Gets the parent.
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     *
     * @return The parent.
     */
    public AccessibilityNodeInfo getParent() {
        enforceSealed();
        if (!canPerformRequestOverConnection(mParentNodeId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mWindowId, mParentNodeId, FLAG_PREFETCH_DESCENDANTS | FLAG_PREFETCH_SIBLINGS);
    }

    /**
     * @return The parent node id.
     *
     * @hide
     */
    public long getParentNodeId() {
        return mParentNodeId;
    }

    /**
     * Sets the parent.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param parent The parent.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setParent(View parent) {
        setParent(parent, UNDEFINED);
    }

    /**
     * Sets the parent to be a virtual descendant of the given <code>root</code>.
     * If <code>virtualDescendantId</code> equals to {@link View#NO_ID} the root
     * is set as the parent.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report them selves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public void setParent(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int rootAccessibilityViewId =
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED;
        mParentNodeId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Gets the node bounds in parent coordinates.
     *
     * @param outBounds The output node bounds.
     */
    public void getBoundsInParent(Rect outBounds) {
        outBounds.set(mBoundsInParent.left, mBoundsInParent.top,
                mBoundsInParent.right, mBoundsInParent.bottom);
    }

    /**
     * Sets the node bounds in parent coordinates.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param bounds The node bounds.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setBoundsInParent(Rect bounds) {
        enforceNotSealed();
        mBoundsInParent.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    /**
     * Gets the node bounds in screen coordinates.
     *
     * @param outBounds The output node bounds.
     */
    public void getBoundsInScreen(Rect outBounds) {
        outBounds.set(mBoundsInScreen.left, mBoundsInScreen.top,
                mBoundsInScreen.right, mBoundsInScreen.bottom);
    }

    /**
     * Sets the node bounds in screen coordinates.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param bounds The node bounds.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setBoundsInScreen(Rect bounds) {
        enforceNotSealed();
        mBoundsInScreen.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    /**
     * Gets whether this node is checkable.
     *
     * @return True if the node is checkable.
     */
    public boolean isCheckable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_CHECKABLE);
    }

    /**
     * Sets whether this node is checkable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param checkable True if the node is checkable.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setCheckable(boolean checkable) {
        setBooleanProperty(BOOLEAN_PROPERTY_CHECKABLE, checkable);
    }

    /**
     * Gets whether this node is checked.
     *
     * @return True if the node is checked.
     */
    public boolean isChecked() {
        return getBooleanProperty(BOOLEAN_PROPERTY_CHECKED);
    }

    /**
     * Sets whether this node is checked.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param checked True if the node is checked.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setChecked(boolean checked) {
        setBooleanProperty(BOOLEAN_PROPERTY_CHECKED, checked);
    }

    /**
     * Gets whether this node is focusable.
     *
     * @return True if the node is focusable.
     */
    public boolean isFocusable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_FOCUSABLE);
    }

    /**
     * Sets whether this node is focusable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param focusable True if the node is focusable.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setFocusable(boolean focusable) {
        setBooleanProperty(BOOLEAN_PROPERTY_FOCUSABLE, focusable);
    }

    /**
     * Gets whether this node is focused.
     *
     * @return True if the node is focused.
     */
    public boolean isFocused() {
        return getBooleanProperty(BOOLEAN_PROPERTY_FOCUSED);
    }

    /**
     * Sets whether this node is focused.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param focused True if the node is focused.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setFocused(boolean focused) {
        setBooleanProperty(BOOLEAN_PROPERTY_FOCUSED, focused);
    }

    /**
     * Sets whether this node is visible to the user.
     *
     * @return Whether the node is visible to the user.
     */
    public boolean isVisibleToUser() {
        return getBooleanProperty(BOOLEAN_PROPERTY_VISIBLE_TO_USER);
    }

    /**
     * Sets whether this node is visible to the user.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param visibleToUser Whether the node is visible to the user.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setVisibleToUser(boolean visibleToUser) {
        setBooleanProperty(BOOLEAN_PROPERTY_VISIBLE_TO_USER, visibleToUser);
    }

    /**
     * Gets whether this node is accessibility focused.
     *
     * @return True if the node is accessibility focused.
     */
    public boolean isAccessibilityFocused() {
        return getBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED);
    }

    /**
     * Sets whether this node is accessibility focused.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param focused True if the node is accessibility focused.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setAccessibilityFocused(boolean focused) {
        setBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED, focused);
    }

    /**
     * Gets whether this node is selected.
     *
     * @return True if the node is selected.
     */
    public boolean isSelected() {
        return getBooleanProperty(BOOLEAN_PROPERTY_SELECTED);
    }

    /**
     * Sets whether this node is selected.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param selected True if the node is selected.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setSelected(boolean selected) {
        setBooleanProperty(BOOLEAN_PROPERTY_SELECTED, selected);
    }

    /**
     * Gets whether this node is clickable.
     *
     * @return True if the node is clickable.
     */
    public boolean isClickable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_CLICKABLE);
    }

    /**
     * Sets whether this node is clickable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param clickable True if the node is clickable.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setClickable(boolean clickable) {
        setBooleanProperty(BOOLEAN_PROPERTY_CLICKABLE, clickable);
    }

    /**
     * Gets whether this node is long clickable.
     *
     * @return True if the node is long clickable.
     */
    public boolean isLongClickable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_LONG_CLICKABLE);
    }

    /**
     * Sets whether this node is long clickable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param longClickable True if the node is long clickable.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setLongClickable(boolean longClickable) {
        setBooleanProperty(BOOLEAN_PROPERTY_LONG_CLICKABLE, longClickable);
    }

    /**
     * Gets whether this node is enabled.
     *
     * @return True if the node is enabled.
     */
    public boolean isEnabled() {
        return getBooleanProperty(BOOLEAN_PROPERTY_ENABLED);
    }

    /**
     * Sets whether this node is enabled.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param enabled True if the node is enabled.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEnabled(boolean enabled) {
        setBooleanProperty(BOOLEAN_PROPERTY_ENABLED, enabled);
    }

    /**
     * Gets whether this node is a password.
     *
     * @return True if the node is a password.
     */
    public boolean isPassword() {
        return getBooleanProperty(BOOLEAN_PROPERTY_PASSWORD);
    }

    /**
     * Sets whether this node is a password.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param password True if the node is a password.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setPassword(boolean password) {
        setBooleanProperty(BOOLEAN_PROPERTY_PASSWORD, password);
    }

    /**
     * Gets if the node is scrollable.
     *
     * @return True if the node is scrollable, false otherwise.
     */
    public boolean isScrollable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_SCROLLABLE);
    }

    /**
     * Sets if the node is scrollable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param scrollable True if the node is scrollable, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setScrollable(boolean scrollable) {
        enforceNotSealed();
        setBooleanProperty(BOOLEAN_PROPERTY_SCROLLABLE, scrollable);
    }

    /**
     * Gets if the node is editable.
     *
     * @return True if the node is editable, false otherwise.
     */
    public boolean isEditable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_EDITABLE);
    }

    /**
     * Sets whether this node is editable.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param editable True if the node is editable.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEditable(boolean editable) {
        setBooleanProperty(BOOLEAN_PROPERTY_EDITABLE, editable);
    }

    /**
     * Gets the package this node comes from.
     *
     * @return The package name.
     */
    public CharSequence getPackageName() {
        return mPackageName;
    }

    /**
     * Sets the package this node comes from.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param packageName The package name.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setPackageName(CharSequence packageName) {
        enforceNotSealed();
        mPackageName = packageName;
    }

    /**
     * Gets the class this node comes from.
     *
     * @return The class name.
     */
    public CharSequence getClassName() {
        return mClassName;
    }

    /**
     * Sets the class this node comes from.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param className The class name.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setClassName(CharSequence className) {
        enforceNotSealed();
        mClassName = className;
    }

    /**
     * Gets the text of this node.
     *
     * @return The text.
     */
    public CharSequence getText() {
        return mText;
    }

    /**
     * Sets the text of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param text The text.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setText(CharSequence text) {
        enforceNotSealed();
        mText = text;
    }

    /**
     * Gets the content description of this node.
     *
     * @return The content description.
     */
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the content description of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param contentDescription The content description.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setContentDescription(CharSequence contentDescription) {
        enforceNotSealed();
        mContentDescription = contentDescription;
    }

    /**
     * Sets the view for which the view represented by this info serves as a
     * label for accessibility purposes.
     *
     * @param labeled The view for which this info serves as a label.
     */
    public void setLabelFor(View labeled) {
        setLabelFor(labeled, UNDEFINED);
    }

    /**
     * Sets the view for which the view represented by this info serves as a
     * label for accessibility purposes. If <code>virtualDescendantId</code>
     * is {@link View#NO_ID} the root is set as the labeled.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report themselves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param root The root whose virtual descendant serves as a label.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public void setLabelFor(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int rootAccessibilityViewId = (root != null)
                ? root.getAccessibilityViewId() : UNDEFINED;
        mLabelForId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Gets the node info for which the view represented by this info serves as
     * a label for accessibility purposes.
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     *
     * @return The labeled info.
     */
    public AccessibilityNodeInfo getLabelFor() {
        enforceSealed();
        if (!canPerformRequestOverConnection(mLabelForId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mWindowId, mLabelForId, FLAG_PREFETCH_DESCENDANTS | FLAG_PREFETCH_SIBLINGS);
    }

    /**
     * Sets the view which serves as the label of the view represented by
     * this info for accessibility purposes.
     *
     * @param label The view that labels this node's source.
     */
    public void setLabeledBy(View label) {
        setLabeledBy(label, UNDEFINED);
    }

    /**
     * Sets the view which serves as the label of the view represented by
     * this info for accessibility purposes. If <code>virtualDescendantId</code>
     * is {@link View#NO_ID} the root is set as the label.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report themselves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param root The root whose virtual descendant labels this node's source.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public void setLabeledBy(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int rootAccessibilityViewId = (root != null)
                ? root.getAccessibilityViewId() : UNDEFINED;
        mLabeledById = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Gets the node info which serves as the label of the view represented by
     * this info for accessibility purposes.
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the
     *     received info by calling {@link AccessibilityNodeInfo#recycle()}
     *     to avoid creating of multiple instances.
     * </p>
     *
     * @return The label.
     */
    public AccessibilityNodeInfo getLabeledBy() {
        enforceSealed();
        if (!canPerformRequestOverConnection(mLabeledById)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mWindowId, mLabeledById, FLAG_PREFETCH_DESCENDANTS | FLAG_PREFETCH_SIBLINGS);
    }

    /**
     * Sets the fully qualified resource name of the source view's id.
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param viewIdResName The id resource name.
     */
    public void setViewIdResourceName(String viewIdResName) {
        enforceNotSealed();
        mViewIdResourceName = viewIdResName;
    }

    /**
     * Gets the fully qualified resource name of the source view's id.
     *
     * <p>
     *   <strong>Note:</strong> The primary usage of this API is for UI test automation
     *   and in order to report the source view id of an {@link AccessibilityNodeInfo} the
     *   client has to set the {@link AccessibilityServiceInfo#FLAG_REPORT_VIEW_IDS}
     *   flag when configuring his {@link android.accessibilityservice.AccessibilityService}.
     * </p>

     * @return The id resource name.
     */
    public String getViewIdResourceName() {
        return mViewIdResourceName;
    }

    /**
     * Gets the text selection start.
     *
     * @return The text selection start if there is selection or -1.
     */
    public int getTextSelectionStart() {
        return mTextSelectionStart;
    }

    /**
     * Gets the text selection end.
     *
     * @return The text selection end if there is selection or -1.
     */
    public int getTextSelectionEnd() {
        return mTextSelectionEnd;
    }

    /**
     * Sets the text selection start and end.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param start The text selection start.
     * @param end The text selection end.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setTextSelection(int start, int end) {
        enforceNotSealed();
        mTextSelectionStart = start;
        mTextSelectionEnd = end;
    }

    /**
     * Gets the value of a boolean property.
     *
     * @param property The property.
     * @return The value.
     */
    private boolean getBooleanProperty(int property) {
        return (mBooleanProperties & property) != 0;
    }

    /**
     * Sets a boolean property.
     *
     * @param property The property.
     * @param value The value.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    private void setBooleanProperty(int property, boolean value) {
        enforceNotSealed();
        if (value) {
            mBooleanProperties |= property;
        } else {
            mBooleanProperties &= ~property;
        }
    }

    /**
     * Sets the unique id of the IAccessibilityServiceConnection over which
     * this instance can send requests to the system.
     *
     * @param connectionId The connection id.
     *
     * @hide
     */
    public void setConnectionId(int connectionId) {
        enforceNotSealed();
        mConnectionId = connectionId;
    }

    /**
     * {@inheritDoc}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Gets the id of the source node.
     *
     * @return The id.
     *
     * @hide
     */
    public long getSourceNodeId() {
        return mSourceNodeId;
    }

    /**
     * Sets if this instance is sealed.
     *
     * @param sealed Whether is sealed.
     *
     * @hide
     */
    public void setSealed(boolean sealed) {
        mSealed = sealed;
    }

    /**
     * Gets if this instance is sealed.
     *
     * @return Whether is sealed.
     *
     * @hide
     */
    public boolean isSealed() {
        return mSealed;
    }

    /**
     * Enforces that this instance is sealed.
     *
     * @throws IllegalStateException If this instance is not sealed.
     *
     * @hide
     */
    protected void enforceSealed() {
        if (!isSealed()) {
            throw new IllegalStateException("Cannot perform this "
                    + "action on a not sealed instance.");
        }
    }

    private void enforceValidFocusDirection(int direction) {
        switch (direction) {
            case View.FOCUS_DOWN:
            case View.FOCUS_UP:
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
            case View.FOCUS_FORWARD:
            case View.FOCUS_BACKWARD:
                return;
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    private void enforceValidFocusType(int focusType) {
        switch (focusType) {
            case FOCUS_INPUT:
            case FOCUS_ACCESSIBILITY:
                return;
            default:
                throw new IllegalArgumentException("Unknown focus type: " + focusType);
        }
    }

    /**
     * Enforces that this instance is not sealed.
     *
     * @throws IllegalStateException If this instance is sealed.
     *
     * @hide
     */
    protected void enforceNotSealed() {
        if (isSealed()) {
            throw new IllegalStateException("Cannot perform this "
                    + "action on a sealed instance.");
        }
    }

    /**
     * Returns a cached instance if such is available otherwise a new one
     * and sets the source.
     *
     * @param source The source view.
     * @return An instance.
     *
     * @see #setSource(View)
     */
    public static AccessibilityNodeInfo obtain(View source) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(source);
        return info;
    }

    /**
     * Returns a cached instance if such is available otherwise a new one
     * and sets the source.
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     * @return An instance.
     *
     * @see #setSource(View, int)
     */
    public static AccessibilityNodeInfo obtain(View root, int virtualDescendantId) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(root, virtualDescendantId);
        return info;
    }

    /**
     * Returns a cached instance if such is available otherwise a new one.
     *
     * @return An instance.
     */
    public static AccessibilityNodeInfo obtain() {
        AccessibilityNodeInfo info = sPool.acquire();
        return (info != null) ? info : new AccessibilityNodeInfo();
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * create. The returned instance is initialized from the given
     * <code>info</code>.
     *
     * @param info The other info.
     * @return An instance.
     */
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo info) {
        AccessibilityNodeInfo infoClone = AccessibilityNodeInfo.obtain();
        infoClone.init(info);
        return infoClone;
    }

    /**
     * Return an instance back to be reused.
     * <p>
     * <strong>Note:</strong> You must not touch the object after calling this function.
     *
     * @throws IllegalStateException If the info is already recycled.
     */
    public void recycle() {
        clear();
        sPool.release(this);
    }

    /**
     * {@inheritDoc}
     * <p>
     *   <strong>Note:</strong> After the instance is written to a parcel it
     *      is recycled. You must not touch the object after calling this function.
     * </p>
     */
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(isSealed() ? 1 : 0);
        parcel.writeLong(mSourceNodeId);
        parcel.writeInt(mWindowId);
        parcel.writeLong(mParentNodeId);
        parcel.writeLong(mLabelForId);
        parcel.writeLong(mLabeledById);
        parcel.writeInt(mConnectionId);

        SparseLongArray childIds = mChildNodeIds;
        final int childIdsSize = childIds.size();
        parcel.writeInt(childIdsSize);
        for (int i = 0; i < childIdsSize; i++) {
            parcel.writeLong(childIds.valueAt(i));
        }

        parcel.writeInt(mBoundsInParent.top);
        parcel.writeInt(mBoundsInParent.bottom);
        parcel.writeInt(mBoundsInParent.left);
        parcel.writeInt(mBoundsInParent.right);

        parcel.writeInt(mBoundsInScreen.top);
        parcel.writeInt(mBoundsInScreen.bottom);
        parcel.writeInt(mBoundsInScreen.left);
        parcel.writeInt(mBoundsInScreen.right);

        parcel.writeInt(mActions);

        parcel.writeInt(mMovementGranularities);

        parcel.writeInt(mBooleanProperties);

        parcel.writeCharSequence(mPackageName);
        parcel.writeCharSequence(mClassName);
        parcel.writeCharSequence(mText);
        parcel.writeCharSequence(mContentDescription);
        parcel.writeString(mViewIdResourceName);

        parcel.writeInt(mTextSelectionStart);
        parcel.writeInt(mTextSelectionEnd);

        // Since instances of this class are fetched via synchronous i.e. blocking
        // calls in IPCs we always recycle as soon as the instance is marshaled.
        recycle();
    }

    /**
     * Initializes this instance from another one.
     *
     * @param other The other instance.
     */
    private void init(AccessibilityNodeInfo other) {
        mSealed = other.mSealed;
        mSourceNodeId = other.mSourceNodeId;
        mParentNodeId = other.mParentNodeId;
        mLabelForId = other.mLabelForId;
        mLabeledById = other.mLabeledById;
        mWindowId = other.mWindowId;
        mConnectionId = other.mConnectionId;
        mBoundsInParent.set(other.mBoundsInParent);
        mBoundsInScreen.set(other.mBoundsInScreen);
        mPackageName = other.mPackageName;
        mClassName = other.mClassName;
        mText = other.mText;
        mContentDescription = other.mContentDescription;
        mViewIdResourceName = other.mViewIdResourceName;
        mActions= other.mActions;
        mBooleanProperties = other.mBooleanProperties;
        mMovementGranularities = other.mMovementGranularities;
        final int otherChildIdCount = other.mChildNodeIds.size();
        for (int i = 0; i < otherChildIdCount; i++) {
            mChildNodeIds.put(i, other.mChildNodeIds.valueAt(i));    
        }
        mTextSelectionStart = other.mTextSelectionStart;
        mTextSelectionEnd = other.mTextSelectionEnd;
    }

    /**
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityNodeInfo}.
     */
    private void initFromParcel(Parcel parcel) {
        mSealed = (parcel.readInt()  == 1);
        mSourceNodeId = parcel.readLong();
        mWindowId = parcel.readInt();
        mParentNodeId = parcel.readLong();
        mLabelForId = parcel.readLong();
        mLabeledById = parcel.readLong();
        mConnectionId = parcel.readInt();

        SparseLongArray childIds = mChildNodeIds;
        final int childrenSize = parcel.readInt();
        for (int i = 0; i < childrenSize; i++) {
            final long childId = parcel.readLong();
            childIds.put(i, childId);
        }

        mBoundsInParent.top = parcel.readInt();
        mBoundsInParent.bottom = parcel.readInt();
        mBoundsInParent.left = parcel.readInt();
        mBoundsInParent.right = parcel.readInt();

        mBoundsInScreen.top = parcel.readInt();
        mBoundsInScreen.bottom = parcel.readInt();
        mBoundsInScreen.left = parcel.readInt();
        mBoundsInScreen.right = parcel.readInt();

        mActions = parcel.readInt();

        mMovementGranularities = parcel.readInt();

        mBooleanProperties = parcel.readInt();

        mPackageName = parcel.readCharSequence();
        mClassName = parcel.readCharSequence();
        mText = parcel.readCharSequence();
        mContentDescription = parcel.readCharSequence();
        mViewIdResourceName = parcel.readString();

        mTextSelectionStart = parcel.readInt();
        mTextSelectionEnd = parcel.readInt();
    }

    /**
     * Clears the state of this instance.
     */
    private void clear() {
        mSealed = false;
        mSourceNodeId = ROOT_NODE_ID;
        mParentNodeId = ROOT_NODE_ID;
        mLabelForId = ROOT_NODE_ID;
        mLabeledById = ROOT_NODE_ID;
        mWindowId = UNDEFINED;
        mConnectionId = UNDEFINED;
        mMovementGranularities = 0;
        mChildNodeIds.clear();
        mBoundsInParent.set(0, 0, 0, 0);
        mBoundsInScreen.set(0, 0, 0, 0);
        mBooleanProperties = 0;
        mPackageName = null;
        mClassName = null;
        mText = null;
        mContentDescription = null;
        mViewIdResourceName = null;
        mActions = 0;
        mTextSelectionStart = UNDEFINED;
        mTextSelectionEnd = UNDEFINED;
    }

    /**
     * Gets the human readable action symbolic name.
     *
     * @param action The action.
     * @return The symbolic name.
     */
    private static String getActionSymbolicName(int action) {
        switch (action) {
            case ACTION_FOCUS:
                return "ACTION_FOCUS";
            case ACTION_CLEAR_FOCUS:
                return "ACTION_CLEAR_FOCUS";
            case ACTION_SELECT:
                return "ACTION_SELECT";
            case ACTION_CLEAR_SELECTION:
                return "ACTION_CLEAR_SELECTION";
            case ACTION_CLICK:
                return "ACTION_CLICK";
            case ACTION_LONG_CLICK:
                return "ACTION_LONG_CLICK";
            case ACTION_ACCESSIBILITY_FOCUS:
                return "ACTION_ACCESSIBILITY_FOCUS";
            case ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                return "ACTION_CLEAR_ACCESSIBILITY_FOCUS";
            case ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
                return "ACTION_NEXT_AT_MOVEMENT_GRANULARITY";
            case ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
                return "ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY";
            case ACTION_NEXT_HTML_ELEMENT:
                return "ACTION_NEXT_HTML_ELEMENT";
            case ACTION_PREVIOUS_HTML_ELEMENT:
                return "ACTION_PREVIOUS_HTML_ELEMENT";
            case ACTION_SCROLL_FORWARD:
                return "ACTION_SCROLL_FORWARD";
            case ACTION_SCROLL_BACKWARD:
                return "ACTION_SCROLL_BACKWARD";
            case ACTION_CUT:
                return "ACTION_CUT";
            case ACTION_COPY:
                return "ACTION_COPY";
            case ACTION_PASTE:
                return "ACTION_PASTE";
            case ACTION_SET_SELECTION:
                return "ACTION_SET_SELECTION";
            default:
                return"ACTION_UNKNOWN";
        }
    }

    /**
     * Gets the human readable movement granularity symbolic name.
     *
     * @param granularity The granularity.
     * @return The symbolic name.
     */
    private static String getMovementGranularitySymbolicName(int granularity) {
        switch (granularity) {
            case MOVEMENT_GRANULARITY_CHARACTER:
                return "MOVEMENT_GRANULARITY_CHARACTER";
            case MOVEMENT_GRANULARITY_WORD:
                return "MOVEMENT_GRANULARITY_WORD";
            case MOVEMENT_GRANULARITY_LINE:
                return "MOVEMENT_GRANULARITY_LINE";
            case MOVEMENT_GRANULARITY_PARAGRAPH:
                return "MOVEMENT_GRANULARITY_PARAGRAPH";
            case MOVEMENT_GRANULARITY_PAGE:
                return "MOVEMENT_GRANULARITY_PAGE";
            default:
                throw new IllegalArgumentException("Unknown movement granularity: " + granularity);
        }
    }

    private boolean canPerformRequestOverConnection(long accessibilityNodeId) {
        return (mWindowId != UNDEFINED
                && getAccessibilityViewId(accessibilityNodeId) != UNDEFINED
                && mConnectionId != UNDEFINED);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null) {
            return false;
        }
        if (getClass() != object.getClass()) {
            return false;
        }
        AccessibilityNodeInfo other = (AccessibilityNodeInfo) object;
        if (mSourceNodeId != other.mSourceNodeId) {
            return false;
        }
        if (mWindowId != other.mWindowId) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + getAccessibilityViewId(mSourceNodeId);
        result = prime * result + getVirtualDescendantId(mSourceNodeId);
        result = prime * result + mWindowId;
        return result;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.toString());

        if (DEBUG) {
            builder.append("; accessibilityViewId: " + getAccessibilityViewId(mSourceNodeId));
            builder.append("; virtualDescendantId: " + getVirtualDescendantId(mSourceNodeId));
            builder.append("; mParentNodeId: " + mParentNodeId);

            int granularities = mMovementGranularities;
            builder.append("; MovementGranularities: [");
            while (granularities != 0) {
                final int granularity = 1 << Integer.numberOfTrailingZeros(granularities);
                granularities &= ~granularity;
                builder.append(getMovementGranularitySymbolicName(granularity));
                if (granularities != 0) {
                    builder.append(", ");
                }
            }
            builder.append("]");

            SparseLongArray childIds = mChildNodeIds;
            builder.append("; childAccessibilityIds: [");
            for (int i = 0, count = childIds.size(); i < count; i++) {
                builder.append(childIds.valueAt(i));
                if (i < count - 1) {
                    builder.append(", ");
                }
            }
            builder.append("]");
        }

        builder.append("; boundsInParent: " + mBoundsInParent);
        builder.append("; boundsInScreen: " + mBoundsInScreen);

        builder.append("; packageName: ").append(mPackageName);
        builder.append("; className: ").append(mClassName);
        builder.append("; text: ").append(mText);
        builder.append("; contentDescription: ").append(mContentDescription);
        builder.append("; viewIdResName: ").append(mViewIdResourceName);

        builder.append("; checkable: ").append(isCheckable());
        builder.append("; checked: ").append(isChecked());
        builder.append("; focusable: ").append(isFocusable());
        builder.append("; focused: ").append(isFocused());
        builder.append("; selected: ").append(isSelected());
        builder.append("; clickable: ").append(isClickable());
        builder.append("; longClickable: ").append(isLongClickable());
        builder.append("; enabled: ").append(isEnabled());
        builder.append("; password: ").append(isPassword());
        builder.append("; scrollable: " + isScrollable());

        builder.append("; [");
        for (int actionBits = mActions; actionBits != 0;) {
            final int action = 1 << Integer.numberOfTrailingZeros(actionBits);
            actionBits &= ~action;
            builder.append(getActionSymbolicName(action));
            if (actionBits != 0) {
                builder.append(", ");
            }
        }
        builder.append("]");

        return builder.toString();
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<AccessibilityNodeInfo> CREATOR =
            new Parcelable.Creator<AccessibilityNodeInfo>() {
        public AccessibilityNodeInfo createFromParcel(Parcel parcel) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.initFromParcel(parcel);
            return info;
        }

        public AccessibilityNodeInfo[] newArray(int size) {
            return new AccessibilityNodeInfo[size];
        }
    };
}
