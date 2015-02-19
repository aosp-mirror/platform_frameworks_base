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
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LongArray;
import android.util.Pools.SynchronizedPool;
import android.view.View;

import java.util.ArrayList;
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
    public static final int UNDEFINED_CONNECTION_ID = -1;

    /** @hide */
    public static final int UNDEFINED_SELECTION_INDEX = -1;

    /** @hide */
    public static final int UNDEFINED_ITEM_ID = Integer.MAX_VALUE;

    /** @hide */
    public static final long ROOT_NODE_ID = makeNodeId(UNDEFINED_ITEM_ID, UNDEFINED_ITEM_ID);

    /** @hide */
    public static final int ACTIVE_WINDOW_ID = UNDEFINED_ITEM_ID;

    /** @hide */
    public static final int ANY_WINDOW_ID = -2;

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
     * Action that deselects the node.
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
     * Action to expand an expandable node.
     */
    public static final int ACTION_EXPAND = 0x00040000;

    /**
     * Action to collapse an expandable node.
     */
    public static final int ACTION_COLLAPSE = 0x00080000;

    /**
     * Action to dismiss a dismissable node.
     */
    public static final int ACTION_DISMISS = 0x00100000;

    /**
     * Action that sets the text of the node. Performing the action without argument, using <code>
     * null</code> or empty {@link CharSequence} will clear the text. This action will also put the
     * cursor at the end of text.
     * <p>
     * <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE}<br>
     * <strong>Example:</strong>
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
     *       "android");
     *   info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
     * </code></pre></p>
     */
    public static final int ACTION_SET_TEXT = 0x00200000;

    private static final int LAST_LEGACY_STANDARD_ACTION = ACTION_SET_TEXT;

    /**
     * Mask to see if the value is larger than the largest ACTION_ constant
     */
    private static final int ACTION_TYPE_MASK = 0xFF000000;

    // Action arguments

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
     * Argument for specifying the text content to set
     * <p>
     * <strong>Type:</strong> CharSequence<br>
     * <strong>Actions:</strong> {@link #ACTION_SET_TEXT}
     * </p>
     *
     * @see #ACTION_SET_TEXT
     */
    public static final String ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE =
            "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE";

    // Focus types

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

    private static final int BOOLEAN_PROPERTY_OPENS_POPUP = 0x00002000;

    private static final int BOOLEAN_PROPERTY_DISMISSABLE = 0x00004000;

    private static final int BOOLEAN_PROPERTY_MULTI_LINE = 0x00008000;

    private static final int BOOLEAN_PROPERTY_CONTENT_INVALID = 0x00010000;

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
        // We changed the value for undefined node to positive due to wrong
        // global id composition (two 32-bin ints into one 64-bit long) but
        // the value used for the host node provider view has id -1 so we
        // remap it here.
        if (virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            virtualDescendantId = UNDEFINED_ITEM_ID;
        }
        return (((long) virtualDescendantId) << VIRTUAL_DESCENDANT_ID_SHIFT) | accessibilityViewId;
    }

    // Housekeeping.
    private static final int MAX_POOL_SIZE = 50;
    private static final SynchronizedPool<AccessibilityNodeInfo> sPool =
            new SynchronizedPool<AccessibilityNodeInfo>(MAX_POOL_SIZE);

    private boolean mSealed;

    // Data.
    private int mWindowId = UNDEFINED_ITEM_ID;
    private long mSourceNodeId = ROOT_NODE_ID;
    private long mParentNodeId = ROOT_NODE_ID;
    private long mLabelForId = ROOT_NODE_ID;
    private long mLabeledById = ROOT_NODE_ID;
    private long mTraversalBefore = ROOT_NODE_ID;
    private long mTraversalAfter = ROOT_NODE_ID;

    private int mBooleanProperties;
    private final Rect mBoundsInParent = new Rect();
    private final Rect mBoundsInScreen = new Rect();

    private CharSequence mPackageName;
    private CharSequence mClassName;
    private CharSequence mText;
    private CharSequence mError;
    private CharSequence mContentDescription;
    private String mViewIdResourceName;

    private LongArray mChildNodeIds;
    private ArrayList<AccessibilityAction> mActions;

    private int mMaxTextLength = -1;
    private int mMovementGranularities;

    private int mTextSelectionStart = UNDEFINED_SELECTION_INDEX;
    private int mTextSelectionEnd = UNDEFINED_SELECTION_INDEX;
    private int mInputType = InputType.TYPE_NULL;
    private int mLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE;

    private Bundle mExtras;

    private int mConnectionId = UNDEFINED_CONNECTION_ID;

    private RangeInfo mRangeInfo;
    private CollectionInfo mCollectionInfo;
    private CollectionItemInfo mCollectionItemInfo;

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
        setSource(source, UNDEFINED_ITEM_ID);
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
        mWindowId = (root != null) ? root.getAccessibilityWindowId() : UNDEFINED_ITEM_ID;
        final int rootAccessibilityViewId =
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
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
     *
     * @param bypassCache Whether to bypass the cache.
     * @return Whether the refresh succeeded.
     *
     * @hide
     */
    public boolean refresh(boolean bypassCache) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return false;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        AccessibilityNodeInfo refreshedInfo = client.findAccessibilityNodeInfoByAccessibilityId(
                mConnectionId, mWindowId, mSourceNodeId, bypassCache, 0);
        if (refreshedInfo == null) {
            return false;
        }
        init(refreshedInfo);
        refreshedInfo.recycle();
        return true;
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
        return refresh(false);
    }

    /**
     * Returns the array containing the IDs of this node's children.
     *
     * @hide
     */
    public LongArray getChildNodeIds() {
        return mChildNodeIds;
    }

    /**
     * Returns the id of the child at the specified index.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt;=
     *             getChildCount()
     * @hide
     */
    public long getChildId(int index) {
        if (mChildNodeIds == null) {
            throw new IndexOutOfBoundsException();
        }
        return mChildNodeIds.get(index);
    }

    /**
     * Gets the number of children.
     *
     * @return The child count.
     */
    public int getChildCount() {
        return mChildNodeIds == null ? 0 : mChildNodeIds.size();
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
        if (mChildNodeIds == null) {
            return null;
        }
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return null;
        }
        final long childId = mChildNodeIds.get(index);
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mWindowId,
                childId, false, FLAG_PREFETCH_DESCENDANTS);
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
        addChildInternal(child, UNDEFINED_ITEM_ID, true);
    }

    /**
     * Unchecked version of {@link #addChild(View)} that does not verify
     * uniqueness. For framework use only.
     *
     * @hide
     */
    public void addChildUnchecked(View child) {
        addChildInternal(child, UNDEFINED_ITEM_ID, false);
    }

    /**
     * Removes a child. If the child was not previously added to the node,
     * calling this method has no effect.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param child The child.
     * @return true if the child was present
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public boolean removeChild(View child) {
        return removeChild(child, UNDEFINED_ITEM_ID);
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
        addChildInternal(root, virtualDescendantId, true);
    }

    private void addChildInternal(View root, int virtualDescendantId, boolean checked) {
        enforceNotSealed();
        if (mChildNodeIds == null) {
            mChildNodeIds = new LongArray();
        }
        final int rootAccessibilityViewId =
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
        final long childNodeId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
        // If we're checking uniqueness and the ID already exists, abort.
        if (checked && mChildNodeIds.indexOf(childNodeId) >= 0) {
            return;
        }
        mChildNodeIds.add(childNodeId);
    }

    /**
     * Removes a virtual child which is a descendant of the given
     * <code>root</code>. If the child was not previously added to the node,
     * calling this method has no effect.
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual child.
     * @return true if the child was present
     * @see #addChild(View, int)
     */
    public boolean removeChild(View root, int virtualDescendantId) {
        enforceNotSealed();
        final LongArray childIds = mChildNodeIds;
        if (childIds == null) {
            return false;
        }
        final int rootAccessibilityViewId =
                (root != null) ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
        final long childNodeId = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
        final int index = childIds.indexOf(childNodeId);
        if (index < 0) {
            return false;
        }
        childIds.remove(index);
        return true;
    }

    /**
     * Gets the actions that can be performed on the node.
     */
    public List<AccessibilityAction> getActionList() {
        if (mActions == null) {
            return Collections.emptyList();
        }

        return mActions;
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
     *
     * @deprecated Use {@link #getActionList()}.
     */
    @Deprecated
    public int getActions() {
        int returnValue = 0;

        if (mActions == null) {
            return returnValue;
        }

        final int actionSize = mActions.size();
        for (int i = 0; i < actionSize; i++) {
            int actionId = mActions.get(i).getId();
            if (actionId <= LAST_LEGACY_STANDARD_ACTION) {
                returnValue |= actionId;
            }
        }

        return returnValue;
    }

    /**
     * Adds an action that can be performed on the node.
     * <p>
     * To add a standard action use the static constants on {@link AccessibilityAction}.
     * To add a custom action create a new {@link AccessibilityAction} by passing in a
     * resource id from your application as the action id and an optional label that
     * describes the action. To override one of the standard actions use as the action
     * id of a standard action id such as {@link #ACTION_CLICK} and an optional label that
     * describes the action.
     * </p>
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
    public void addAction(AccessibilityAction action) {
        enforceNotSealed();

        if (action == null) {
            return;
        }

        if (mActions == null) {
            mActions = new ArrayList<AccessibilityAction>();
        }

        mActions.remove(action);
        mActions.add(action);
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
     * @throws IllegalArgumentException If the argument is not one of the standard actions.
     *
     * @deprecated This has been deprecated for {@link #addAction(AccessibilityAction)}
     */
    @Deprecated
    public void addAction(int action) {
        enforceNotSealed();

        if ((action & ACTION_TYPE_MASK) != 0) {
            throw new IllegalArgumentException("Action is not a combination of the standard " +
                    "actions: " + action);
        }

        addLegacyStandardActions(action);
    }

    /**
     * Removes an action that can be performed on the node. If the action was
     * not already added to the node, calling this method has no effect.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param action The action to be removed.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     * @deprecated Use {@link #removeAction(AccessibilityAction)}
     */
    @Deprecated
    public void removeAction(int action) {
        enforceNotSealed();

        removeAction(getActionSingleton(action));
    }

    /**
     * Removes an action that can be performed on the node. If the action was
     * not already added to the node, calling this method has no effect.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param action The action to be removed.
     * @return The action removed from the list of actions.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public boolean removeAction(AccessibilityAction action) {
        enforceNotSealed();

        if (mActions == null || action == null) {
            return false;
        }

        return mActions.remove(action);
    }

    /**
     * Gets the node before which this one is visited during traversal. A screen-reader
     * must visit the content of this node before the content of the one it precedes.
     *
     * @return The succeeding node if such or <code>null</code>.
     *
     * @see #setTraversalBefore(android.view.View)
     * @see #setTraversalBefore(android.view.View, int)
     */
    public AccessibilityNodeInfo getTraversalBefore() {
        enforceSealed();
        return getNodeForAccessibilityId(mTraversalBefore);
    }

    /**
     * Sets the view before whose node this one should be visited during traversal. A
     * screen-reader must visit the content of this node before the content of the one
     * it precedes.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param view The view providing the preceding node.
     *
     * @see #getTraversalBefore()
     */
    public void setTraversalBefore(View view) {
        setTraversalBefore(view, UNDEFINED_ITEM_ID);
    }

    /**
     * Sets the node before which this one is visited during traversal. A screen-reader
     * must visit the content of this node before the content of the one it precedes.
     * The successor is a virtual descendant of the given <code>root</code>. If
     * <code>virtualDescendantId</code> equals to {@link View#NO_ID} the root is set
     * as the successor.
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
    public void setTraversalBefore(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int rootAccessibilityViewId = (root != null)
                ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
        mTraversalBefore = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Gets the node after which this one is visited in accessibility traversal.
     * A screen-reader must visit the content of the other node before the content
     * of this one.
     *
     * @return The succeeding node if such or <code>null</code>.
     *
     * @see #setTraversalAfter(android.view.View)
     * @see #setTraversalAfter(android.view.View, int)
     */
    public AccessibilityNodeInfo getTraversalAfter() {
        enforceSealed();
        return getNodeForAccessibilityId(mTraversalAfter);
    }

    /**
     * Sets the view whose node is visited after this one in accessibility traversal.
     * A screen-reader must visit the content of the other node before the content
     * of this one.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param view The previous view.
     *
     * @see #getTraversalAfter()
     */
    public void setTraversalAfter(View view) {
        setTraversalAfter(view, UNDEFINED_ITEM_ID);
    }

    /**
     * Sets the node after which this one is visited in accessibility traversal.
     * A screen-reader must visit the content of the other node before the content
     * of this one. If <code>virtualDescendantId</code> equals to {@link View#NO_ID}
     * the root is set as the predecessor.
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
    public void setTraversalAfter(View root, int virtualDescendantId) {
        enforceNotSealed();
        final int rootAccessibilityViewId = (root != null)
                ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
        mTraversalAfter = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
    }

    /**
     * Sets the maximum text length, or -1 for no limit.
     * <p>
     * Typically used to indicate that an editable text field has a limit on
     * the number of characters entered.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     *
     * @param max The maximum text length.
     * @see #getMaxTextLength()
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setMaxTextLength(int max) {
        enforceNotSealed();
        mMaxTextLength = max;
    }

    /**
     * Returns the maximum text length for this node.
     *
     * @return The maximum text length, or -1 for no limit.
     * @see #setMaxTextLength(int)
     */
    public int getMaxTextLength() {
        return mMaxTextLength;
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
     * Gets the window to which this node belongs.
     *
     * @return The window.
     *
     * @see android.accessibilityservice.AccessibilityService#getWindows()
     */
    public AccessibilityWindowInfo getWindow() {
        enforceSealed();
        if (!canPerformRequestOverConnection(mSourceNodeId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.getWindow(mConnectionId, mWindowId);
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
        return getNodeForAccessibilityId(mParentNodeId);
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
        setParent(parent, UNDEFINED_ITEM_ID);
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
            (root != null) ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
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
     * Gets the collection info if the node is a collection. A collection
     * child is always a collection item.
     *
     * @return The collection info.
     */
    public CollectionInfo getCollectionInfo() {
        return mCollectionInfo;
    }

    /**
     * Sets the collection info if the node is a collection. A collection
     * child is always a collection item.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param collectionInfo The collection info.
     */
    public void setCollectionInfo(CollectionInfo collectionInfo) {
        enforceNotSealed();
        mCollectionInfo = collectionInfo;
    }

    /**
     * Gets the collection item info if the node is a collection item. A collection
     * item is always a child of a collection.
     *
     * @return The collection item info.
     */
    public CollectionItemInfo getCollectionItemInfo() {
        return mCollectionItemInfo;
    }

    /**
     * Sets the collection item info if the node is a collection item. A collection
     * item is always a child of a collection.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     */
    public void setCollectionItemInfo(CollectionItemInfo collectionItemInfo) {
        enforceNotSealed();
        mCollectionItemInfo = collectionItemInfo;
    }

    /**
     * Gets the range info if this node is a range.
     *
     * @return The range.
     */
    public RangeInfo getRangeInfo() {
        return mRangeInfo;
    }

    /**
     * Sets the range info if this node is a range.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param rangeInfo The range info.
     */
    public void setRangeInfo(RangeInfo rangeInfo) {
        enforceNotSealed();
        mRangeInfo = rangeInfo;
    }

    /**
     * Gets if the content of this node is invalid. For example,
     * a date is not well-formed.
     *
     * @return If the node content is invalid.
     */
    public boolean isContentInvalid() {
        return getBooleanProperty(BOOLEAN_PROPERTY_CONTENT_INVALID);
    }

    /**
     * Sets if the content of this node is invalid. For example,
     * a date is not well-formed.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param contentInvalid If the node content is invalid.
     */
    public void setContentInvalid(boolean contentInvalid) {
        setBooleanProperty(BOOLEAN_PROPERTY_CONTENT_INVALID, contentInvalid);
    }

    /**
     * Gets the node's live region mode.
     * <p>
     * A live region is a node that contains information that is important for
     * the user and when it changes the user should be notified. For example,
     * in a login screen with a TextView that displays an "incorrect password"
     * notification, that view should be marked as a live region with mode
     * {@link View#ACCESSIBILITY_LIVE_REGION_POLITE}.
     * <p>
     * It is the responsibility of the accessibility service to monitor
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} events indicating
     * changes to live region nodes and their children.
     *
     * @return The live region mode, or
     *         {@link View#ACCESSIBILITY_LIVE_REGION_NONE} if the view is not a
     *         live region.
     * @see android.view.View#getAccessibilityLiveRegion()
     */
    public int getLiveRegion() {
        return mLiveRegion;
    }

    /**
     * Sets the node's live region mode.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}. This class is
     * made immutable before being delivered to an AccessibilityService.
     *
     * @param mode The live region mode, or
     *        {@link View#ACCESSIBILITY_LIVE_REGION_NONE} if the view is not a
     *        live region.
     * @see android.view.View#setAccessibilityLiveRegion(int)
     */
    public void setLiveRegion(int mode) {
        enforceNotSealed();
        mLiveRegion = mode;
    }

    /**
     * Gets if the node is a multi line editable text.
     *
     * @return True if the node is multi line.
     */
    public boolean isMultiLine() {
        return getBooleanProperty(BOOLEAN_PROPERTY_MULTI_LINE);
    }

    /**
     * Sets if the node is a multi line editable text.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param multiLine True if the node is multi line.
     */
    public void setMultiLine(boolean multiLine) {
        setBooleanProperty(BOOLEAN_PROPERTY_MULTI_LINE, multiLine);
    }

    /**
     * Gets if this node opens a popup or a dialog.
     *
     * @return If the the node opens a popup.
     */
    public boolean canOpenPopup() {
        return getBooleanProperty(BOOLEAN_PROPERTY_OPENS_POPUP);
    }

    /**
     * Sets if this node opens a popup or a dialog.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param opensPopup If the the node opens a popup.
     */
    public void setCanOpenPopup(boolean opensPopup) {
        enforceNotSealed();
        setBooleanProperty(BOOLEAN_PROPERTY_OPENS_POPUP, opensPopup);
    }

    /**
     * Gets if the node can be dismissed.
     *
     * @return If the node can be dismissed.
     */
    public boolean isDismissable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_DISMISSABLE);
    }

    /**
     * Sets if the node can be dismissed.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param dismissable If the node can be dismissed.
     */
    public void setDismissable(boolean dismissable) {
        setBooleanProperty(BOOLEAN_PROPERTY_DISMISSABLE, dismissable);
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
     * Sets the error text of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param error The error text.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setError(CharSequence error) {
        enforceNotSealed();
        mError = error;
    }

    /**
     * Gets the error text of this node.
     *
     * @return The error text.
     */
    public CharSequence getError() {
        return mError;
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
        setLabelFor(labeled, UNDEFINED_ITEM_ID);
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
                ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
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
        return getNodeForAccessibilityId(mLabelForId);
    }

    /**
     * Sets the view which serves as the label of the view represented by
     * this info for accessibility purposes.
     *
     * @param label The view that labels this node's source.
     */
    public void setLabeledBy(View label) {
        setLabeledBy(label, UNDEFINED_ITEM_ID);
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
                ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
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
        return getNodeForAccessibilityId(mLabeledById);
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
     * Gets the input type of the source as defined by {@link InputType}.
     *
     * @return The input type.
     */
    public int getInputType() {
        return mInputType;
    }

    /**
     * Sets the input type of the source as defined by {@link InputType}.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an
     *   AccessibilityService.
     * </p>
     *
     * @param inputType The input type.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setInputType(int inputType) {
        enforceNotSealed();
        mInputType = inputType;
    }

    /**
     * Gets an optional bundle with extra data. The bundle
     * is lazily created and never <code>null</code>.
     * <p>
     * <strong>Note:</strong> It is recommended to use the package
     * name of your application as a prefix for the keys to avoid
     * collisions which may confuse an accessibility service if the
     * same key has different meaning when emitted from different
     * applications.
     * </p>
     *
     * @return The bundle.
     */
    public Bundle getExtras() {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        return mExtras;
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
    @Override
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
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(isSealed() ? 1 : 0);
        parcel.writeLong(mSourceNodeId);
        parcel.writeInt(mWindowId);
        parcel.writeLong(mParentNodeId);
        parcel.writeLong(mLabelForId);
        parcel.writeLong(mLabeledById);
        parcel.writeLong(mTraversalBefore);
        parcel.writeLong(mTraversalAfter);

        parcel.writeInt(mConnectionId);

        final LongArray childIds = mChildNodeIds;
        if (childIds == null) {
            parcel.writeInt(0);
        } else {
            final int childIdsSize = childIds.size();
            parcel.writeInt(childIdsSize);
            for (int i = 0; i < childIdsSize; i++) {
                parcel.writeLong(childIds.get(i));
            }
        }

        parcel.writeInt(mBoundsInParent.top);
        parcel.writeInt(mBoundsInParent.bottom);
        parcel.writeInt(mBoundsInParent.left);
        parcel.writeInt(mBoundsInParent.right);

        parcel.writeInt(mBoundsInScreen.top);
        parcel.writeInt(mBoundsInScreen.bottom);
        parcel.writeInt(mBoundsInScreen.left);
        parcel.writeInt(mBoundsInScreen.right);

        if (mActions != null && !mActions.isEmpty()) {
            final int actionCount = mActions.size();
            parcel.writeInt(actionCount);

            int defaultLegacyStandardActions = 0;
            for (int i = 0; i < actionCount; i++) {
                AccessibilityAction action = mActions.get(i);
                if (isDefaultLegacyStandardAction(action)) {
                    defaultLegacyStandardActions |= action.getId();
                }
            }
            parcel.writeInt(defaultLegacyStandardActions);

            for (int i = 0; i < actionCount; i++) {
                AccessibilityAction action = mActions.get(i);
                if (!isDefaultLegacyStandardAction(action)) {
                    parcel.writeInt(action.getId());
                    parcel.writeCharSequence(action.getLabel());
                }
            }
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(mMaxTextLength);
        parcel.writeInt(mMovementGranularities);
        parcel.writeInt(mBooleanProperties);

        parcel.writeCharSequence(mPackageName);
        parcel.writeCharSequence(mClassName);
        parcel.writeCharSequence(mText);
        parcel.writeCharSequence(mError);
        parcel.writeCharSequence(mContentDescription);
        parcel.writeString(mViewIdResourceName);

        parcel.writeInt(mTextSelectionStart);
        parcel.writeInt(mTextSelectionEnd);
        parcel.writeInt(mInputType);
        parcel.writeInt(mLiveRegion);

        if (mExtras != null) {
            parcel.writeInt(1);
            parcel.writeBundle(mExtras);
        } else {
            parcel.writeInt(0);
        }

        if (mRangeInfo != null) {
            parcel.writeInt(1);
            parcel.writeInt(mRangeInfo.getType());
            parcel.writeFloat(mRangeInfo.getMin());
            parcel.writeFloat(mRangeInfo.getMax());
            parcel.writeFloat(mRangeInfo.getCurrent());
        } else {
            parcel.writeInt(0);
        }

        if (mCollectionInfo != null) {
            parcel.writeInt(1);
            parcel.writeInt(mCollectionInfo.getRowCount());
            parcel.writeInt(mCollectionInfo.getColumnCount());
            parcel.writeInt(mCollectionInfo.isHierarchical() ? 1 : 0);
            parcel.writeInt(mCollectionInfo.getSelectionMode());
        } else {
            parcel.writeInt(0);
        }

        if (mCollectionItemInfo != null) {
            parcel.writeInt(1);
            parcel.writeInt(mCollectionItemInfo.getColumnIndex());
            parcel.writeInt(mCollectionItemInfo.getColumnSpan());
            parcel.writeInt(mCollectionItemInfo.getRowIndex());
            parcel.writeInt(mCollectionItemInfo.getRowSpan());
            parcel.writeInt(mCollectionItemInfo.isHeading() ? 1 : 0);
            parcel.writeInt(mCollectionItemInfo.isSelected() ? 1 : 0);
        } else {
            parcel.writeInt(0);
        }

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
        mTraversalBefore = other.mTraversalBefore;
        mTraversalAfter = other.mTraversalAfter;
        mWindowId = other.mWindowId;
        mConnectionId = other.mConnectionId;
        mBoundsInParent.set(other.mBoundsInParent);
        mBoundsInScreen.set(other.mBoundsInScreen);
        mPackageName = other.mPackageName;
        mClassName = other.mClassName;
        mText = other.mText;
        mError = other.mError;
        mContentDescription = other.mContentDescription;
        mViewIdResourceName = other.mViewIdResourceName;

        final ArrayList<AccessibilityAction> otherActions = other.mActions;
        if (otherActions != null && otherActions.size() > 0) {
            if (mActions == null) {
                mActions = new ArrayList(otherActions);
            } else {
                mActions.clear();
                mActions.addAll(other.mActions);
            }
        }

        mBooleanProperties = other.mBooleanProperties;
        mMaxTextLength = other.mMaxTextLength;
        mMovementGranularities = other.mMovementGranularities;

        final LongArray otherChildNodeIds = other.mChildNodeIds;
        if (otherChildNodeIds != null && otherChildNodeIds.size() > 0) {
            if (mChildNodeIds == null) {
                mChildNodeIds = otherChildNodeIds.clone();
            } else {
                mChildNodeIds.clear();
                mChildNodeIds.addAll(otherChildNodeIds);
            }
        }

        mTextSelectionStart = other.mTextSelectionStart;
        mTextSelectionEnd = other.mTextSelectionEnd;
        mInputType = other.mInputType;
        mLiveRegion = other.mLiveRegion;
        if (other.mExtras != null && !other.mExtras.isEmpty()) {
            getExtras().putAll(other.mExtras);
        }
        mRangeInfo = (other.mRangeInfo != null)
                ? RangeInfo.obtain(other.mRangeInfo) : null;
        mCollectionInfo = (other.mCollectionInfo != null)
                ? CollectionInfo.obtain(other.mCollectionInfo) : null;
        mCollectionItemInfo =  (other.mCollectionItemInfo != null)
                ? CollectionItemInfo.obtain(other.mCollectionItemInfo) : null;
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
        mTraversalBefore = parcel.readLong();
        mTraversalAfter = parcel.readLong();

        mConnectionId = parcel.readInt();

        final int childrenSize = parcel.readInt();
        if (childrenSize <= 0) {
            mChildNodeIds = null;
        } else {
            mChildNodeIds = new LongArray(childrenSize);
            for (int i = 0; i < childrenSize; i++) {
                final long childId = parcel.readLong();
                mChildNodeIds.add(childId);
            }
        }

        mBoundsInParent.top = parcel.readInt();
        mBoundsInParent.bottom = parcel.readInt();
        mBoundsInParent.left = parcel.readInt();
        mBoundsInParent.right = parcel.readInt();

        mBoundsInScreen.top = parcel.readInt();
        mBoundsInScreen.bottom = parcel.readInt();
        mBoundsInScreen.left = parcel.readInt();
        mBoundsInScreen.right = parcel.readInt();

        final int actionCount = parcel.readInt();
        if (actionCount > 0) {
            final int legacyStandardActions = parcel.readInt();
            addLegacyStandardActions(legacyStandardActions);
            final int nonLegacyActionCount = actionCount - Integer.bitCount(legacyStandardActions);
            for (int i = 0; i < nonLegacyActionCount; i++) {
                AccessibilityAction action = new AccessibilityAction(
                        parcel.readInt(), parcel.readCharSequence());
                addAction(action);
            }
        }

        mMaxTextLength = parcel.readInt();
        mMovementGranularities = parcel.readInt();
        mBooleanProperties = parcel.readInt();

        mPackageName = parcel.readCharSequence();
        mClassName = parcel.readCharSequence();
        mText = parcel.readCharSequence();
        mError = parcel.readCharSequence();
        mContentDescription = parcel.readCharSequence();
        mViewIdResourceName = parcel.readString();

        mTextSelectionStart = parcel.readInt();
        mTextSelectionEnd = parcel.readInt();

        mInputType = parcel.readInt();
        mLiveRegion = parcel.readInt();

        if (parcel.readInt() == 1) {
            getExtras().putAll(parcel.readBundle());
        }

        if (parcel.readInt() == 1) {
            mRangeInfo = RangeInfo.obtain(
                    parcel.readInt(),
                    parcel.readFloat(),
                    parcel.readFloat(),
                    parcel.readFloat());
        }

        if (parcel.readInt() == 1) {
            mCollectionInfo = CollectionInfo.obtain(
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt() == 1,
                    parcel.readInt());
        }

        if (parcel.readInt() == 1) {
            mCollectionItemInfo = CollectionItemInfo.obtain(
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt() == 1,
                    parcel.readInt() == 1);
        }
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
        mTraversalBefore = ROOT_NODE_ID;
        mTraversalAfter = ROOT_NODE_ID;
        mWindowId = UNDEFINED_ITEM_ID;
        mConnectionId = UNDEFINED_CONNECTION_ID;
        mMaxTextLength = -1;
        mMovementGranularities = 0;
        if (mChildNodeIds != null) {
            mChildNodeIds.clear();
        }
        mBoundsInParent.set(0, 0, 0, 0);
        mBoundsInScreen.set(0, 0, 0, 0);
        mBooleanProperties = 0;
        mPackageName = null;
        mClassName = null;
        mText = null;
        mError = null;
        mContentDescription = null;
        mViewIdResourceName = null;
        if (mActions != null) {
            mActions.clear();
        }
        mTextSelectionStart = UNDEFINED_SELECTION_INDEX;
        mTextSelectionEnd = UNDEFINED_SELECTION_INDEX;
        mInputType = InputType.TYPE_NULL;
        mLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE;
        if (mExtras != null) {
            mExtras.clear();
        }
        if (mRangeInfo != null) {
            mRangeInfo.recycle();
            mRangeInfo = null;
        }
        if (mCollectionInfo != null) {
            mCollectionInfo.recycle();
            mCollectionInfo = null;
        }
        if (mCollectionItemInfo != null) {
            mCollectionItemInfo.recycle();
            mCollectionItemInfo = null;
        }
    }

    private static boolean isDefaultLegacyStandardAction(AccessibilityAction action) {
        return (action.getId() <= LAST_LEGACY_STANDARD_ACTION
                && TextUtils.isEmpty(action.getLabel()));
    }

    private static AccessibilityAction getActionSingleton(int actionId) {
        final int actions = AccessibilityAction.sStandardActions.size();
        for (int i = 0; i < actions; i++) {
            AccessibilityAction currentAction = AccessibilityAction.sStandardActions.valueAt(i);
            if (actionId == currentAction.getId()) {
                return currentAction;
            }
        }

        return null;
    }

    private void addLegacyStandardActions(int actionMask) {
        int remainingIds = actionMask;
        while (remainingIds > 0) {
            final int id = 1 << Integer.numberOfTrailingZeros(remainingIds);
            remainingIds &= ~id;
            AccessibilityAction action = getActionSingleton(id);
            addAction(action);
        }
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
        return (mWindowId != UNDEFINED_ITEM_ID
                && getAccessibilityViewId(accessibilityNodeId) != UNDEFINED_ITEM_ID
                && mConnectionId != UNDEFINED_CONNECTION_ID);
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
            builder.append("; sourceNodeId: " + mSourceNodeId);
            builder.append("; accessibilityViewId: " + getAccessibilityViewId(mSourceNodeId));
            builder.append("; virtualDescendantId: " + getVirtualDescendantId(mSourceNodeId));
            builder.append("; mParentNodeId: " + mParentNodeId);
            builder.append("; traversalBefore: ").append(mTraversalBefore);
            builder.append("; traversalAfter: ").append(mTraversalAfter);

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

            builder.append("; childAccessibilityIds: [");
            final LongArray childIds = mChildNodeIds;
            if (childIds != null) {
                for (int i = 0, count = childIds.size(); i < count; i++) {
                    builder.append(childIds.get(i));
                    if (i < count - 1) {
                        builder.append(", ");
                    }
                }
            }
            builder.append("]");
        }

        builder.append("; boundsInParent: " + mBoundsInParent);
        builder.append("; boundsInScreen: " + mBoundsInScreen);

        builder.append("; packageName: ").append(mPackageName);
        builder.append("; className: ").append(mClassName);
        builder.append("; text: ").append(mText);
        builder.append("; error: ").append(mError);
        builder.append("; maxTextLength: ").append(mMaxTextLength);
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
        builder.append("; scrollable: ").append(isScrollable());
        builder.append("; actions: ").append(mActions);

        return builder.toString();
    }

    private AccessibilityNodeInfo getNodeForAccessibilityId(long accessibilityId) {
        if (!canPerformRequestOverConnection(accessibilityId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mWindowId, accessibilityId, false, FLAG_PREFETCH_PREDECESSORS
                        | FLAG_PREFETCH_DESCENDANTS | FLAG_PREFETCH_SIBLINGS);
    }

    /**
     * A class defining an action that can be performed on an {@link AccessibilityNodeInfo}.
     * Each action has a unique id that is mandatory and optional data.
     * <p>
     * There are three categories of actions:
     * <ul>
     * <li><strong>Standard actions</strong> - These are actions that are reported and
     * handled by the standard UI widgets in the platform. For each standard action
     * there is a static constant defined in this class, e.g. {@link #ACTION_FOCUS}.
     * </li>
     * <li><strong>Custom actions action</strong> - These are actions that are reported
     * and handled by custom widgets. i.e. ones that are not part of the UI toolkit. For
     * example, an application may define a custom action for clearing the user history.
     * </li>
     * <li><strong>Overriden standard actions</strong> - These are actions that override
     * standard actions to customize them. For example, an app may add a label to the
     * standard click action to announce that this action clears browsing history.
     * </ul>
     * </p>
     */
    public static final class AccessibilityAction {

        /**
         * Action that gives input focus to the node.
         */
        public static final AccessibilityAction ACTION_FOCUS =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_FOCUS, null);

        /**
         * Action that clears input focus of the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_FOCUS =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null);

        /**
         *  Action that selects the node.
         */
        public static final AccessibilityAction ACTION_SELECT =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SELECT, null);

        /**
         * Action that deselects the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_SELECTION =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, null);

        /**
         * Action that clicks on the node info.
         */
        public static final AccessibilityAction ACTION_CLICK =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, null);

        /**
         * Action that long clicks on the node.
         */
        public static final AccessibilityAction ACTION_LONG_CLICK =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, null);

        /**
         * Action that gives accessibility focus to the node.
         */
        public static final AccessibilityAction ACTION_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);

        /**
         * Action that clears accessibility focus of the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);

        /**
         * Action that requests to go to the next entity in this node's text
         * at a given movement granularity. For example, move to the next character,
         * word, etc.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT},
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN}<br>
         * <strong>Example:</strong> Move to the previous character and do not extend selection.
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
         *           AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
         *   arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
         *           false);
         *   info.performAction(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY.getId(),
         *           arguments);
         * </code></pre></p>
         * </p>
         *
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *
         * @see AccessibilityNodeInfo#setMovementGranularities(int)
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         * @see AccessibilityNodeInfo#getMovementGranularities()
         *  AccessibilityNodeInfo.getMovementGranularities()
         *
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_CHARACTER
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_WORD
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_LINE
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_PARAGRAPH
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_PAGE
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
         */
        public static final AccessibilityAction ACTION_NEXT_AT_MOVEMENT_GRANULARITY =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, null);

        /**
         * Action that requests to go to the previous entity in this node's text
         * at a given movement granularity. For example, move to the next character,
         * word, etc.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT},
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN}<br>
         * <strong>Example:</strong> Move to the next character and do not extend selection.
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
         *           AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
         *   arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
         *           false);
         *   info.performAction(AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY.getId(),
         *           arguments);
         * </code></pre></p>
         * </p>
         *
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN
         *
         * @see AccessibilityNodeInfo#setMovementGranularities(int)
         *   AccessibilityNodeInfo.setMovementGranularities(int)
         * @see AccessibilityNodeInfo#getMovementGranularities()
         *  AccessibilityNodeInfo.getMovementGranularities()
         *
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_CHARACTER
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_WORD
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_LINE
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_PARAGRAPH
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
         * @see AccessibilityNodeInfo#MOVEMENT_GRANULARITY_PAGE
         *  AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
         */
        public static final AccessibilityAction ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, null);

        /**
         * Action to move to the next HTML element of a given type. For example, move
         * to the BUTTON, INPUT, TABLE, etc.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_HTML_ELEMENT_STRING
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING}<br>
         * <strong>Example:</strong>
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "BUTTON");
         *   info.performAction(AccessibilityAction.ACTION_NEXT_HTML_ELEMENT.getId(), arguments);
         * </code></pre></p>
         * </p>
         */
        public static final AccessibilityAction ACTION_NEXT_HTML_ELEMENT =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, null);

        /**
         * Action to move to the previous HTML element of a given type. For example, move
         * to the BUTTON, INPUT, TABLE, etc.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_HTML_ELEMENT_STRING
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING}<br>
         * <strong>Example:</strong>
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "BUTTON");
         *   info.performAction(AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT.getId(), arguments);
         * </code></pre></p>
         * </p>
         */
        public static final AccessibilityAction ACTION_PREVIOUS_HTML_ELEMENT =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, null);

        /**
         * Action to scroll the node content forward.
         */
        public static final AccessibilityAction ACTION_SCROLL_FORWARD =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);

        /**
         * Action to scroll the node content backward.
         */
        public static final AccessibilityAction ACTION_SCROLL_BACKWARD =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null);

        /**
         * Action to copy the current selection to the clipboard.
         */
        public static final AccessibilityAction ACTION_COPY =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_COPY, null);

        /**
         * Action to paste the current clipboard content.
         */
        public static final AccessibilityAction ACTION_PASTE =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_PASTE, null);

        /**
         * Action to cut the current selection and place it to the clipboard.
         */
        public static final AccessibilityAction ACTION_CUT =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CUT, null);

        /**
         * Action to set the selection. Performing this action with no arguments
         * clears the selection.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_SELECTION_START_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT},
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_SELECTION_END_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT}<br>
         * <strong>Example:</strong>
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1);
         *   arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2);
         *   info.performAction(AccessibilityAction.ACTION_SET_SELECTION.getId(), arguments);
         * </code></pre></p>
         * </p>
         *
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_SELECTION_START_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT
         * @see AccessibilityNodeInfo#ACTION_ARGUMENT_SELECTION_END_INT
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT
         */
        public static final AccessibilityAction ACTION_SET_SELECTION =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION, null);

        /**
         * Action to expand an expandable node.
         */
        public static final AccessibilityAction ACTION_EXPAND =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_EXPAND, null);

        /**
         * Action to collapse an expandable node.
         */
        public static final AccessibilityAction ACTION_COLLAPSE =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_COLLAPSE, null);

        /**
         * Action to dismiss a dismissable node.
         */
        public static final AccessibilityAction ACTION_DISMISS =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_DISMISS, null);

        /**
         * Action that sets the text of the node. Performing the action without argument,
         * using <code> null</code> or empty {@link CharSequence} will clear the text. This
         * action will also put the cursor at the end of text.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
         *  AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE}<br>
         * <strong>Example:</strong>
         * <code><pre><p>
         *   Bundle arguments = new Bundle();
         *   arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
         *       "android");
         *   info.performAction(AccessibilityAction.ACTION_SET_TEXT.getId(), arguments);
         * </code></pre></p>
         */
        public static final AccessibilityAction ACTION_SET_TEXT =
                new AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT, null);

        private static final ArraySet<AccessibilityAction> sStandardActions = new ArraySet<AccessibilityAction>();
        static {
            sStandardActions.add(ACTION_FOCUS);
            sStandardActions.add(ACTION_CLEAR_FOCUS);
            sStandardActions.add(ACTION_SELECT);
            sStandardActions.add(ACTION_CLEAR_SELECTION);
            sStandardActions.add(ACTION_CLICK);
            sStandardActions.add(ACTION_LONG_CLICK);
            sStandardActions.add(ACTION_ACCESSIBILITY_FOCUS);
            sStandardActions.add(ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            sStandardActions.add(ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
            sStandardActions.add(ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
            sStandardActions.add(ACTION_NEXT_HTML_ELEMENT);
            sStandardActions.add(ACTION_PREVIOUS_HTML_ELEMENT);
            sStandardActions.add(ACTION_SCROLL_FORWARD);
            sStandardActions.add(ACTION_SCROLL_BACKWARD);
            sStandardActions.add(ACTION_COPY);
            sStandardActions.add(ACTION_PASTE);
            sStandardActions.add(ACTION_CUT);
            sStandardActions.add(ACTION_SET_SELECTION);
            sStandardActions.add(ACTION_EXPAND);
            sStandardActions.add(ACTION_COLLAPSE);
            sStandardActions.add(ACTION_DISMISS);
            sStandardActions.add(ACTION_SET_TEXT);
        }

        private final int mActionId;
        private final CharSequence mLabel;

        /**
         * Creates a new AccessibilityAction. For adding a standard action without a specific label,
         * use the static constants.
         *
         * You can also override the description for one the standard actions. Below is an example
         * how to override the standard click action by adding a custom label:
         * <pre>
         *   AccessibilityAction action = new AccessibilityAction(
         *           AccessibilityAction.ACTION_ACTION_CLICK, getLocalizedLabel());
         *   node.addAction(action);
         * </pre>
         *
         * @param actionId The id for this action. This should either be one of the
         *                 standard actions or a specific action for your app. In that case it is
         *                 required to use a resource identifier.
         * @param label The label for the new AccessibilityAction.
         */
        public AccessibilityAction(int actionId, @Nullable CharSequence label) {
            if ((actionId & ACTION_TYPE_MASK) == 0 && Integer.bitCount(actionId) != 1) {
                throw new IllegalArgumentException("Invalid standard action id");
            }

            mActionId = actionId;
            mLabel = label;
        }

        /**
         * Gets the id for this action.
         *
         * @return The action id.
         */
        public int getId() {
            return mActionId;
        }

        /**
         * Gets the label for this action. Its purpose is to describe the
         * action to user.
         *
         * @return The label.
         */
        public CharSequence getLabel() {
            return mLabel;
        }

        @Override
        public int hashCode() {
            return mActionId;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }

            if (other == this) {
                return true;
            }

            if (getClass() != other.getClass()) {
                return false;
            }

            return mActionId == ((AccessibilityAction)other).mActionId;
        }

        @Override
        public String toString() {
            return "AccessibilityAction: " + getActionSymbolicName(mActionId) + " - " + mLabel;
        }
    }

    /**
     * Class with information if a node is a range. Use
     * {@link RangeInfo#obtain(int, float, float, float)} to get an instance.
     */
    public static final class RangeInfo {
        private static final int MAX_POOL_SIZE = 10;

        /** Range type: integer. */
        public static final int RANGE_TYPE_INT = 0;
        /** Range type: float. */
        public static final int RANGE_TYPE_FLOAT = 1;
        /** Range type: percent with values from zero to one.*/
        public static final int RANGE_TYPE_PERCENT = 2;

        private static final SynchronizedPool<RangeInfo> sPool =
                new SynchronizedPool<AccessibilityNodeInfo.RangeInfo>(MAX_POOL_SIZE);

        private int mType;
        private float mMin;
        private float mMax;
        private float mCurrent;

        /**
         * Obtains a pooled instance that is a clone of another one.
         *
         * @param other The instance to clone.
         *
         * @hide
         */
        public static RangeInfo obtain(RangeInfo other) {
            return obtain(other.mType, other.mMin, other.mMax, other.mCurrent);
        }

        /**
         * Obtains a pooled instance.
         *
         * @param type The type of the range.
         * @param min The min value.
         * @param max The max value.
         * @param current The current value.
         */
        public static RangeInfo obtain(int type, float min, float max, float current) {
            RangeInfo info = sPool.acquire();
            return (info != null) ? info : new RangeInfo(type, min, max, current);
        }

        /**
         * Creates a new range.
         *
         * @param type The type of the range.
         * @param min The min value.
         * @param max The max value.
         * @param current The current value.
         */
        private RangeInfo(int type, float min, float max, float current) {
            mType = type;
            mMin = min;
            mMax = max;
            mCurrent = current;
        }

        /**
         * Gets the range type.
         *
         * @return The range type.
         *
         * @see #RANGE_TYPE_INT
         * @see #RANGE_TYPE_FLOAT
         * @see #RANGE_TYPE_PERCENT
         */
        public int getType() {
            return mType;
        }

        /**
         * Gets the min value.
         *
         * @return The min value.
         */
        public float getMin() {
            return mMin;
        }

        /**
         * Gets the max value.
         *
         * @return The max value.
         */
        public float getMax() {
            return mMax;
        }

        /**
         * Gets the current value.
         *
         * @return The current value.
         */
        public float getCurrent() {
            return mCurrent;
        }

        /**
         * Recycles this instance.
         */
        void recycle() {
            clear();
            sPool.release(this);
        }

        private void clear() {
            mType = 0;
            mMin = 0;
            mMax = 0;
            mCurrent = 0;
        }
    }

    /**
     * Class with information if a node is a collection. Use
     * {@link CollectionInfo#obtain(int, int, boolean)} to get an instance.
     * <p>
     * A collection of items has rows and columns and may be hierarchical.
     * For example, a horizontal list is a collection with one column, as
     * many rows as the list items, and is not hierarchical; A table is a
     * collection with several rows, several columns, and is not hierarchical;
     * A vertical tree is a hierarchical collection with one column and
     * as many rows as the first level children.
     * </p>
     */
    public static final class CollectionInfo {
        /** Selection mode where items are not selectable. */
        public static final int SELECTION_MODE_NONE = 0;

        /** Selection mode where a single item may be selected. */
        public static final int SELECTION_MODE_SINGLE = 1;

        /** Selection mode where multiple items may be selected. */
        public static final int SELECTION_MODE_MULTIPLE = 2;

        private static final int MAX_POOL_SIZE = 20;

        private static final SynchronizedPool<CollectionInfo> sPool =
                new SynchronizedPool<CollectionInfo>(MAX_POOL_SIZE);

        private int mRowCount;
        private int mColumnCount;
        private boolean mHierarchical;
        private int mSelectionMode;

        /**
         * Obtains a pooled instance that is a clone of another one.
         *
         * @param other The instance to clone.
         * @hide
         */
        public static CollectionInfo obtain(CollectionInfo other) {
            return CollectionInfo.obtain(other.mRowCount, other.mColumnCount, other.mHierarchical,
                    other.mSelectionMode);
        }

        /**
         * Obtains a pooled instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         */
        public static CollectionInfo obtain(int rowCount, int columnCount,
                boolean hierarchical) {
            return obtain(rowCount, columnCount, hierarchical, SELECTION_MODE_NONE);
        }

        /**
         * Obtains a pooled instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         * @param selectionMode The collection's selection mode, one of:
         *            <ul>
         *            <li>{@link #SELECTION_MODE_NONE}
         *            <li>{@link #SELECTION_MODE_SINGLE}
         *            <li>{@link #SELECTION_MODE_MULTIPLE}
         *            </ul>
         */
        public static CollectionInfo obtain(int rowCount, int columnCount,
                boolean hierarchical, int selectionMode) {
           final CollectionInfo info = sPool.acquire();
            if (info == null) {
                return new CollectionInfo(rowCount, columnCount, hierarchical, selectionMode);
            }

            info.mRowCount = rowCount;
            info.mColumnCount = columnCount;
            info.mHierarchical = hierarchical;
            info.mSelectionMode = selectionMode;
            return info;
        }

        /**
         * Creates a new instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         * @param selectionMode The collection's selection mode.
         */
        private CollectionInfo(int rowCount, int columnCount, boolean hierarchical,
                int selectionMode) {
            mRowCount = rowCount;
            mColumnCount = columnCount;
            mHierarchical = hierarchical;
            mSelectionMode = selectionMode;
        }

        /**
         * Gets the number of rows.
         *
         * @return The row count.
         */
        public int getRowCount() {
            return mRowCount;
        }

        /**
         * Gets the number of columns.
         *
         * @return The column count.
         */
        public int getColumnCount() {
            return mColumnCount;
        }

        /**
         * Gets if the collection is a hierarchically ordered.
         *
         * @return Whether the collection is hierarchical.
         */
        public boolean isHierarchical() {
            return mHierarchical;
        }

        /**
         * Gets the collection's selection mode.
         *
         * @return The collection's selection mode, one of:
         *         <ul>
         *         <li>{@link #SELECTION_MODE_NONE}
         *         <li>{@link #SELECTION_MODE_SINGLE}
         *         <li>{@link #SELECTION_MODE_MULTIPLE}
         *         </ul>
         */
        public int getSelectionMode() {
            return mSelectionMode;
        }

        /**
         * Recycles this instance.
         */
        void recycle() {
            clear();
            sPool.release(this);
        }

        private void clear() {
            mRowCount = 0;
            mColumnCount = 0;
            mHierarchical = false;
            mSelectionMode = SELECTION_MODE_NONE;
        }
    }

    /**
     * Class with information if a node is a collection item. Use
     * {@link CollectionItemInfo#obtain(int, int, int, int, boolean)}
     * to get an instance.
     * <p>
     * A collection item is contained in a collection, it starts at
     * a given row and column in the collection, and spans one or
     * more rows and columns. For example, a header of two related
     * table columns starts at the first row and the first column,
     * spans one row and two columns.
     * </p>
     */
    public static final class CollectionItemInfo {
        private static final int MAX_POOL_SIZE = 20;

        private static final SynchronizedPool<CollectionItemInfo> sPool =
                new SynchronizedPool<CollectionItemInfo>(MAX_POOL_SIZE);

        /**
         * Obtains a pooled instance that is a clone of another one.
         *
         * @param other The instance to clone.
         * @hide
         */
        public static CollectionItemInfo obtain(CollectionItemInfo other) {
            return CollectionItemInfo.obtain(other.mRowIndex, other.mRowSpan, other.mColumnIndex,
                    other.mColumnSpan, other.mHeading, other.mSelected);
        }

        /**
         * Obtains a pooled instance.
         *
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         */
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan,
                int columnIndex, int columnSpan, boolean heading) {
            return obtain(rowIndex, rowSpan, columnIndex, columnSpan, heading, false);
        }

        /**
         * Obtains a pooled instance.
         *
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         * @param selected Whether the item is selected.
         */
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan,
                int columnIndex, int columnSpan, boolean heading, boolean selected) {
            final CollectionItemInfo info = sPool.acquire();
            if (info == null) {
                return new CollectionItemInfo(
                        rowIndex, rowSpan, columnIndex, columnSpan, heading, selected);
            }

            info.mRowIndex = rowIndex;
            info.mRowSpan = rowSpan;
            info.mColumnIndex = columnIndex;
            info.mColumnSpan = columnSpan;
            info.mHeading = heading;
            info.mSelected = selected;
            return info;
        }

        private boolean mHeading;
        private int mColumnIndex;
        private int mRowIndex;
        private int mColumnSpan;
        private int mRowSpan;
        private boolean mSelected;

        /**
         * Creates a new instance.
         *
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         */
        private CollectionItemInfo(int rowIndex, int rowSpan, int columnIndex, int columnSpan,
                boolean heading, boolean selected) {
            mRowIndex = rowIndex;
            mRowSpan = rowSpan;
            mColumnIndex = columnIndex;
            mColumnSpan = columnSpan;
            mHeading = heading;
            mSelected = selected;
        }

        /**
         * Gets the column index at which the item is located.
         *
         * @return The column index.
         */
        public int getColumnIndex() {
            return mColumnIndex;
        }

        /**
         * Gets the row index at which the item is located.
         *
         * @return The row index.
         */
        public int getRowIndex() {
            return mRowIndex;
        }

        /**
         * Gets the number of columns the item spans.
         *
         * @return The column span.
         */
        public int getColumnSpan() {
            return mColumnSpan;
        }

        /**
         * Gets the number of rows the item spans.
         *
         * @return The row span.
         */
        public int getRowSpan() {
            return mRowSpan;
        }

        /**
         * Gets if the collection item is a heading. For example, section
         * heading, table header, etc.
         *
         * @return If the item is a heading.
         */
        public boolean isHeading() {
            return mHeading;
        }

        /**
         * Gets if the collection item is selected.
         *
         * @return If the item is selected.
         */
        public boolean isSelected() {
            return mSelected;
        }

        /**
         * Recycles this instance.
         */
        void recycle() {
            clear();
            sPool.release(this);
        }

        private void clear() {
            mColumnIndex = 0;
            mColumnSpan = 0;
            mRowIndex = 0;
            mRowSpan = 0;
            mHeading = false;
            mSelected = false;
        }
    }

    /**
     * @see android.os.Parcelable.Creator
     */
    public static final Parcelable.Creator<AccessibilityNodeInfo> CREATOR =
            new Parcelable.Creator<AccessibilityNodeInfo>() {
        @Override
        public AccessibilityNodeInfo createFromParcel(Parcel parcel) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.initFromParcel(parcel);
            return info;
        }

        @Override
        public AccessibilityNodeInfo[] newArray(int size) {
            return new AccessibilityNodeInfo[size];
        }
    };
}
