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

import static com.android.internal.util.BitUtils.bitAt;
import static com.android.internal.util.BitUtils.isBitSet;

import static java.util.Collections.EMPTY_LIST;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AccessibilityClickableSpan;
import android.text.style.AccessibilityURLSpan;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongArray;
import android.util.Pools.SynchronizedPool;
import android.view.TouchDelegate;
import android.view.View;

import com.android.internal.R;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class represents a node of the window content as well as actions that
 * can be requested from its source. From the point of view of an
 * {@link android.accessibilityservice.AccessibilityService} a window's content is
 * presented as a tree of accessibility node infos, which may or may not map one-to-one
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
 * node info as well as details about the security model.
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

    private static final String TAG = "AccessibilityNodeInfo";

    /** @hide */
    public static final int UNDEFINED_CONNECTION_ID = -1;

    /** @hide */
    public static final int UNDEFINED_SELECTION_INDEX = -1;

    /** @hide */
    public static final int UNDEFINED_ITEM_ID = Integer.MAX_VALUE;

    /** @hide */
    public static final int ROOT_ITEM_ID = Integer.MAX_VALUE - 1;

    /** @hide */
    public static final long UNDEFINED_NODE_ID = makeNodeId(UNDEFINED_ITEM_ID, UNDEFINED_ITEM_ID);

    /** @hide */
    public static final long ROOT_NODE_ID = makeNodeId(ROOT_ITEM_ID,
            AccessibilityNodeProvider.HOST_VIEW_ID);

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
     *
     * See {@link AccessibilityAction#ACTION_CLICK}
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
     * <strong>Arguments:</strong>
     * {@link #ACTION_ARGUMENT_SELECTION_START_INT},
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
     * <strong>Arguments:</strong>
     * {@link #ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE}<br>
     * <strong>Example:</strong>
     * <code><pre><p>
     *   Bundle arguments = new Bundle();
     *   arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
     *       "android");
     *   info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
     * </code></pre></p>
     */
    public static final int ACTION_SET_TEXT = 0x00200000;

    /** @hide */
    public static final int LAST_LEGACY_STANDARD_ACTION = ACTION_SET_TEXT;

    /**
     * Mask to see if the value is larger than the largest ACTION_ constant
     */
    private static final int ACTION_TYPE_MASK = 0xFF000000;

    // Action arguments

    /**
     * Argument for which movement granularity to be used when traversing the node text.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_NEXT_AT_MOVEMENT_GRANULARITY}</li>
     *     <li>{@link AccessibilityAction#ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY}</li>
     * </ul>
     * </p>
     *
     * @see AccessibilityAction#ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     * @see AccessibilityAction#ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
     */
    public static final String ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT =
            "ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT";

    /**
     * Argument for which HTML element to get moving to the next/previous HTML element.
     * <p>
     * <strong>Type:</strong> String<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_NEXT_HTML_ELEMENT}</li>
     *     <li>{@link AccessibilityAction#ACTION_PREVIOUS_HTML_ELEMENT}</li>
     * </ul>
     * </p>
     *
     * @see AccessibilityAction#ACTION_NEXT_HTML_ELEMENT
     * @see AccessibilityAction#ACTION_PREVIOUS_HTML_ELEMENT
     */
    public static final String ACTION_ARGUMENT_HTML_ELEMENT_STRING =
            "ACTION_ARGUMENT_HTML_ELEMENT_STRING";

    /**
     * Argument for whether when moving at granularity to extend the selection
     * or to move it otherwise.
     * <p>
     * <strong>Type:</strong> boolean<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_NEXT_AT_MOVEMENT_GRANULARITY}</li>
     *     <li>{@link AccessibilityAction#ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     * @see AccessibilityAction#ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
     */
    public static final String ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN =
            "ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN";

    /**
     * Argument for specifying the selection start.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SET_SELECTION}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SET_SELECTION
     */
    public static final String ACTION_ARGUMENT_SELECTION_START_INT =
            "ACTION_ARGUMENT_SELECTION_START_INT";

    /**
     * Argument for specifying the selection end.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SET_SELECTION}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SET_SELECTION
     */
    public static final String ACTION_ARGUMENT_SELECTION_END_INT =
            "ACTION_ARGUMENT_SELECTION_END_INT";

    /**
     * Argument for specifying the text content to set.
     * <p>
     * <strong>Type:</strong> CharSequence<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SET_TEXT}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SET_TEXT
     */
    public static final String ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE =
            "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE";

    /**
     * Argument for specifying the collection row to make visible on screen.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SCROLL_TO_POSITION}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SCROLL_TO_POSITION
     */
    public static final String ACTION_ARGUMENT_ROW_INT =
            "android.view.accessibility.action.ARGUMENT_ROW_INT";

    /**
     * Argument for specifying the collection column to make visible on screen.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SCROLL_TO_POSITION}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SCROLL_TO_POSITION
     */
    public static final String ACTION_ARGUMENT_COLUMN_INT =
            "android.view.accessibility.action.ARGUMENT_COLUMN_INT";

    /**
     * Argument for specifying the progress value to set.
     * <p>
     * <strong>Type:</strong> float<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_SET_PROGRESS}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_SET_PROGRESS
     */
    public static final String ACTION_ARGUMENT_PROGRESS_VALUE =
            "android.view.accessibility.action.ARGUMENT_PROGRESS_VALUE";

    /**
     * Argument for specifying the x coordinate to which to move a window.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_MOVE_WINDOW}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_MOVE_WINDOW
     */
    public static final String ACTION_ARGUMENT_MOVE_WINDOW_X =
            "ACTION_ARGUMENT_MOVE_WINDOW_X";

    /**
     * Argument for specifying the y coordinate to which to move a window.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_MOVE_WINDOW}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_MOVE_WINDOW
     */
    public static final String ACTION_ARGUMENT_MOVE_WINDOW_Y =
            "ACTION_ARGUMENT_MOVE_WINDOW_Y";

    /**
     * Argument to pass the {@link AccessibilityClickableSpan}.
     * For use with R.id.accessibilityActionClickOnClickableSpan
     * @hide
     */
    public static final String ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN =
            "android.view.accessibility.action.ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN";

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

    /**
     * Key used to request and locate extra data for text character location. This key requests that
     * an array of {@link android.graphics.RectF}s be added to the extras. This request is made with
     * {@link #refreshWithExtraData(String, Bundle)}. The arguments taken by this request are two
     * integers: {@link #EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX} and
     * {@link #EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH}. The starting index must be valid
     * inside the CharSequence returned by {@link #getText()}, and the length must be positive.
     * <p>
     * The data can be retrieved from the {@code Bundle} returned by {@link #getExtras()} using this
     * string as a key for {@link Bundle#getParcelableArray(String)}. The
     * {@link android.graphics.RectF} will be null for characters that either do not exist or are
     * off the screen.
     *
     * {@see #refreshWithExtraData(String, Bundle)}
     */
    public static final String EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY =
            "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_KEY";

    /**
     * Integer argument specifying the start index of the requested text location data. Must be
     * valid inside the CharSequence returned by {@link #getText()}.
     *
     * @see #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
     */
    public static final String EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX =
            "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX";

    /**
     * Integer argument specifying the end index of the requested text location data. Must be
     * positive.
     *
     * @see #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
     */
    public static final String EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH =
            "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH";

    /** @hide */
    public static final String EXTRA_DATA_REQUESTED_KEY =
            "android.view.accessibility.AccessibilityNodeInfo.extra_data_requested";

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

    private static final int BOOLEAN_PROPERTY_CONTEXT_CLICKABLE = 0x00020000;

    private static final int BOOLEAN_PROPERTY_IMPORTANCE = 0x0040000;

    private static final int BOOLEAN_PROPERTY_SCREEN_READER_FOCUSABLE = 0x0080000;

    private static final int BOOLEAN_PROPERTY_IS_SHOWING_HINT = 0x0100000;

    private static final int BOOLEAN_PROPERTY_IS_HEADING = 0x0200000;

    private static final int BOOLEAN_PROPERTY_IS_TEXT_ENTRY_KEY = 0x0400000;

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

    // TODO(b/129300068): Remove sNumInstancesInUse.
    private static AtomicInteger sNumInstancesInUse;

    /**
     * Gets the accessibility view id which identifies a View in the view three.
     *
     * @param accessibilityNodeId The id of an {@link AccessibilityNodeInfo}.
     * @return The accessibility view id part of the node id.
     *
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
            new SynchronizedPool<>(MAX_POOL_SIZE);

    private static final AccessibilityNodeInfo DEFAULT = new AccessibilityNodeInfo();

    @UnsupportedAppUsage
    private boolean mSealed;

    // Data.
    private int mWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    @UnsupportedAppUsage
    private long mSourceNodeId = UNDEFINED_NODE_ID;
    private long mParentNodeId = UNDEFINED_NODE_ID;
    private long mLabelForId = UNDEFINED_NODE_ID;
    private long mLabeledById = UNDEFINED_NODE_ID;
    private long mTraversalBefore = UNDEFINED_NODE_ID;
    private long mTraversalAfter = UNDEFINED_NODE_ID;

    private int mBooleanProperties;
    private final Rect mBoundsInParent = new Rect();
    private final Rect mBoundsInScreen = new Rect();
    private int mDrawingOrderInParent;

    private CharSequence mPackageName;
    private CharSequence mClassName;
    // Hidden, unparceled value used to hold the original value passed to setText
    private CharSequence mOriginalText;
    private CharSequence mText;
    private CharSequence mHintText;
    private CharSequence mError;
    private CharSequence mPaneTitle;
    private CharSequence mContentDescription;
    private CharSequence mTooltipText;
    private String mViewIdResourceName;
    private ArrayList<String> mExtraDataKeys;

    @UnsupportedAppUsage
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

    private TouchDelegateInfo mTouchDelegateInfo;

    /**
     * Hide constructor from clients.
     */
    private AccessibilityNodeInfo() {
        /* do nothing */
    }

    /** @hide */
    AccessibilityNodeInfo(AccessibilityNodeInfo info) {
        init(info);
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
        setSource(source, AccessibilityNodeProvider.HOST_VIEW_ID);
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
    @UnsupportedAppUsage
    public boolean refresh(Bundle arguments, boolean bypassCache) {
        enforceSealed();
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
            return false;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        AccessibilityNodeInfo refreshedInfo = client.findAccessibilityNodeInfoByAccessibilityId(
                mConnectionId, mWindowId, mSourceNodeId, bypassCache, 0, arguments);
        if (refreshedInfo == null) {
            return false;
        }
        // Hard-to-reproduce bugs seem to be due to some tools recycling a node on another
        // thread. If that happens, the init will re-seal the node, which then is in a bad state
        // when it is obtained. Enforce sealing again before we init to fail when a node has been
        // recycled during a refresh to catch such errors earlier.
        enforceSealed();
        init(refreshedInfo);
        refreshedInfo.recycle();
        return true;
    }

    /**
     * Refreshes this info with the latest state of the view it represents.
     *
     * @return {@code true} if the refresh succeeded. {@code false} if the {@link View} represented
     * by this node is no longer in the view tree (and thus this node is obsolete and should be
     * recycled).
     */
    public boolean refresh() {
        return refresh(null, true);
    }

    /**
     * Refreshes this info with the latest state of the view it represents, and request new
     * data be added by the View.
     *
     * @param extraDataKey The extra data requested. Data that must be requested
     *                     with this mechanism is generally expensive to retrieve, so should only be
     *                     requested when needed. See
     *                     {@link #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY} and
     *                     {@link #getAvailableExtraData()}.
     * @param args A bundle of arguments for the request. These depend on the particular request.
     *
     * @return {@code true} if the refresh succeeded. {@code false} if the {@link View} represented
     * by this node is no longer in the view tree (and thus this node is obsolete and should be
     * recycled).
     */
    public boolean refreshWithExtraData(String extraDataKey, Bundle args) {
        args.putString(EXTRA_DATA_REQUESTED_KEY, extraDataKey);
        return refresh(args, true);
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
            return null;
        }
        final long childId = mChildNodeIds.get(index);
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mWindowId,
                childId, false, FLAG_PREFETCH_DESCENDANTS, null);
    }

    /**
     * Adds a child.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * Note that a view cannot be made its own child.
     * </p>
     *
     * @param child The child.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void addChild(View child) {
        addChildInternal(child, AccessibilityNodeProvider.HOST_VIEW_ID, true);
    }

    /**
     * Unchecked version of {@link #addChild(View)} that does not verify
     * uniqueness. For framework use only.
     *
     * @hide
     */
    public void addChildUnchecked(View child) {
        addChildInternal(child, AccessibilityNodeProvider.HOST_VIEW_ID, false);
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
        return removeChild(child, AccessibilityNodeProvider.HOST_VIEW_ID);
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
     * Note that a view cannot be made its own child.
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
        if (childNodeId == mSourceNodeId) {
            Log.e(TAG, "Rejecting attempt to make a View its own child");
            return;
        }

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
        return CollectionUtils.emptyIfNull(mActions);
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

        addActionUnchecked(action);
    }

    private void addActionUnchecked(AccessibilityAction action) {
        if (action == null) {
            return;
        }

        if (mActions == null) {
            mActions = new ArrayList<>();
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

        addStandardActions(action);
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
     * Removes all actions.
     *
     * @hide
     */
    public void removeAllActions() {
        if (mActions != null) {
            mActions.clear();
        }
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
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mTraversalBefore);
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
        setTraversalBefore(view, AccessibilityNodeProvider.HOST_VIEW_ID);
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
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mTraversalAfter);
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
        setTraversalAfter(view, AccessibilityNodeProvider.HOST_VIEW_ID);
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
     * Get the extra data available for this node.
     * <p>
     * Some data that is useful for some accessibility services is expensive to compute, and would
     * place undue overhead on apps to compute all the time. That data can be requested with
     * {@link #refreshWithExtraData(String, Bundle)}.
     *
     * @return An unmodifiable list of keys corresponding to extra data that can be requested.
     * @see #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
     */
    public List<String> getAvailableExtraData() {
        if (mExtraDataKeys != null) {
            return Collections.unmodifiableList(mExtraDataKeys);
        } else {
            return EMPTY_LIST;
        }
    }

    /**
     * Set the extra data available for this node.
     * <p>
     * <strong>Note:</strong> When a {@code View} passes in a non-empty list, it promises that
     * it will populate the node's extras with corresponding pieces of information in
     * {@link View#addExtraDataToAccessibilityNodeInfo(AccessibilityNodeInfo, String, Bundle)}.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     *
     * @param extraDataKeys A list of types of extra data that are available.
     * @see #getAvailableExtraData()
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setAvailableExtraData(List<String> extraDataKeys) {
        enforceNotSealed();
        mExtraDataKeys = new ArrayList<>(extraDataKeys);
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
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
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mParentNodeId);
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
        setParent(parent, AccessibilityNodeProvider.HOST_VIEW_ID);
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
     * Gets the node bounds in the viewParent's coordinates.
     * {@link #getParent()} does not represent the source's viewParent.
     * Instead it represents the result of {@link View#getParentForAccessibility()},
     * which returns the closest ancestor where {@link View#isImportantForAccessibility()} is true.
     * So this method is not reliable.
     *
     * @param outBounds The output node bounds.
     * @deprecated Use {@link #getBoundsInScreen(Rect)} instead.
     *
     */
    @Deprecated
    public void getBoundsInParent(Rect outBounds) {
        outBounds.set(mBoundsInParent.left, mBoundsInParent.top,
                mBoundsInParent.right, mBoundsInParent.bottom);
    }

    /**
     * Sets the node bounds in the viewParent's coordinates.
     * {@link #getParent()} does not represent the source's viewParent.
     * Instead it represents the result of {@link View#getParentForAccessibility()},
     * which returns the closest ancestor where {@link View#isImportantForAccessibility()} is true.
     * So this method is not reliable.
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param bounds The node bounds.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     * @deprecated Accessibility services should not care about these bounds.
     */
    @Deprecated
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
     * Returns the actual rect containing the node bounds in screen coordinates.
     *
     * @hide Not safe to expose outside the framework.
     */
    public Rect getBoundsInScreen() {
        return mBoundsInScreen;
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
     * Gets whether this node is visible to the user.
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
     * If this node represents a visually distinct region of the screen that may update separately
     * from the rest of the window, it is considered a pane. Set the pane title to indicate that
     * the node is a pane, and to provide a title for it.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     * @param paneTitle The title of the pane represented by this node.
     */
    public void setPaneTitle(@Nullable CharSequence paneTitle) {
        enforceNotSealed();
        mPaneTitle = (paneTitle == null)
                ? null : paneTitle.subSequence(0, paneTitle.length());
    }

    /**
     * Get the title of the pane represented by this node.
     *
     * @return The title of the pane represented by this node, or {@code null} if this node does
     *         not represent a pane.
     */
    public @Nullable CharSequence getPaneTitle() {
        return mPaneTitle;
    }

    /**
     * Get the drawing order of the view corresponding it this node.
     * <p>
     * Drawing order is determined only within the node's parent, so this index is only relative
     * to its siblings.
     * <p>
     * In some cases, the drawing order is essentially simultaneous, so it is possible for two
     * siblings to return the same value. It is also possible that values will be skipped.
     *
     * @return The drawing position of the view corresponding to this node relative to its siblings.
     */
    public int getDrawingOrder() {
        return mDrawingOrderInParent;
    }

    /**
     * Set the drawing order of the view corresponding it this node.
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     * @param drawingOrderInParent
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setDrawingOrder(int drawingOrderInParent) {
        enforceNotSealed();
        mDrawingOrderInParent = drawingOrderInParent;
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
     * Gets whether this node is context clickable.
     *
     * @return True if the node is context clickable.
     */
    public boolean isContextClickable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_CONTEXT_CLICKABLE);
    }

    /**
     * Sets whether this node is context clickable.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}. This class is made immutable
     * before being delivered to an AccessibilityService.
     * </p>
     *
     * @param contextClickable True if the node is context clickable.
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setContextClickable(boolean contextClickable) {
        setBooleanProperty(BOOLEAN_PROPERTY_CONTEXT_CLICKABLE, contextClickable);
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
     * Returns whether the node originates from a view considered important for accessibility.
     *
     * @return {@code true} if the node originates from a view considered important for
     *         accessibility, {@code false} otherwise
     *
     * @see View#isImportantForAccessibility()
     */
    public boolean isImportantForAccessibility() {
        return getBooleanProperty(BOOLEAN_PROPERTY_IMPORTANCE);
    }

    /**
     * Sets whether the node is considered important for accessibility.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param important {@code true} if the node is considered important for accessibility,
     *                  {@code false} otherwise
     */
    public void setImportantForAccessibility(boolean important) {
        setBooleanProperty(BOOLEAN_PROPERTY_IMPORTANCE, important);
    }

    /**
     * Returns whether the node is explicitly marked as a focusable unit by a screen reader. Note
     * that {@code false} indicates that it is not explicitly marked, not that the node is not
     * a focusable unit. Screen readers should generally use other signals, such as
     * {@link #isFocusable()}, or the presence of text in a node, to determine what should receive
     * focus.
     *
     * @return {@code true} if the node is specifically marked as a focusable unit for screen
     *         readers, {@code false} otherwise.
     *
     * @see View#isScreenReaderFocusable()
     */
    public boolean isScreenReaderFocusable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_SCREEN_READER_FOCUSABLE);
    }

    /**
     * Sets whether the node should be considered a focusable unit by a screen reader.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param screenReaderFocusable {@code true} if the node is a focusable unit for screen readers,
     *                              {@code false} otherwise.
     */
    public void setScreenReaderFocusable(boolean screenReaderFocusable) {
        setBooleanProperty(BOOLEAN_PROPERTY_SCREEN_READER_FOCUSABLE, screenReaderFocusable);
    }

    /**
     * Returns whether the node's text represents a hint for the user to enter text. It should only
     * be {@code true} if the node has editable text.
     *
     * @return {@code true} if the text in the node represents a hint to the user, {@code false}
     * otherwise.
     */
    public boolean isShowingHintText() {
        return getBooleanProperty(BOOLEAN_PROPERTY_IS_SHOWING_HINT);
    }

    /**
     * Sets whether the node's text represents a hint for the user to enter text. It should only
     * be {@code true} if the node has editable text.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param showingHintText {@code true} if the text in the node represents a hint to the user,
     * {@code false} otherwise.
     */
    public void setShowingHintText(boolean showingHintText) {
        setBooleanProperty(BOOLEAN_PROPERTY_IS_SHOWING_HINT, showingHintText);
    }

    /**
     * Returns whether node represents a heading.
     * <p><strong>Note:</strong> Returns {@code true} if either {@link #setHeading(boolean)}
     * marks this node as a heading or if the node has a {@link CollectionItemInfo} that marks
     * it as such, to accomodate apps that use the now-deprecated API.</p>
     *
     * @return {@code true} if the node is a heading, {@code false} otherwise.
     */
    public boolean isHeading() {
        if (getBooleanProperty(BOOLEAN_PROPERTY_IS_HEADING)) return true;
        CollectionItemInfo itemInfo = getCollectionItemInfo();
        return ((itemInfo != null) && itemInfo.mHeading);
    }

    /**
     * Sets whether the node represents a heading.
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param isHeading {@code true} if the node is a heading, {@code false} otherwise.
     */
    public void setHeading(boolean isHeading) {
        setBooleanProperty(BOOLEAN_PROPERTY_IS_HEADING, isHeading);
    }

    /**
     * Returns whether node represents a text entry key that is part of a keyboard or keypad.
     *
     * @return {@code true} if the node is a text entry key., {@code false} otherwise.
     */
    public boolean isTextEntryKey() {
        return getBooleanProperty(BOOLEAN_PROPERTY_IS_TEXT_ENTRY_KEY);
    }

    /**
     * Sets whether the node represents a text entry key that is part of a keyboard or keypad.
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param isTextEntryKey {@code true} if the node is a text entry key, {@code false} otherwise.
     */
    public void setTextEntryKey(boolean isTextEntryKey) {
        setBooleanProperty(BOOLEAN_PROPERTY_IS_TEXT_ENTRY_KEY, isTextEntryKey);
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
     * <p>
     *   <strong>Note:</strong> If the text contains {@link ClickableSpan}s or {@link URLSpan}s,
     *   these spans will have been replaced with ones whose {@link ClickableSpan#onClick(View)}
     *   can be called from an {@link AccessibilityService}. When called from a service, the
     *   {@link View} argument is ignored and the corresponding span will be found on the view that
     *   this {@code AccessibilityNodeInfo} represents and called with that view as its argument.
     *   <p>
     *   This treatment of {@link ClickableSpan}s means that the text returned from this method may
     *   different slightly one passed to {@link #setText(CharSequence)}, although they will be
     *   equivalent according to {@link TextUtils#equals(CharSequence, CharSequence)}. The
     *   {@link ClickableSpan#onClick(View)} of any spans, however, will generally not work outside
     *   of an accessibility service.
     * </p>
     *
     * @return The text.
     */
    public CharSequence getText() {
        // Attach this node to any spans that need it
        if (mText instanceof Spanned) {
            Spanned spanned = (Spanned) mText;
            AccessibilityClickableSpan[] clickableSpans =
                    spanned.getSpans(0, mText.length(), AccessibilityClickableSpan.class);
            for (int i = 0; i < clickableSpans.length; i++) {
                clickableSpans[i].copyConnectionDataFrom(this);
            }
            AccessibilityURLSpan[] urlSpans =
                    spanned.getSpans(0, mText.length(), AccessibilityURLSpan.class);
            for (int i = 0; i < urlSpans.length; i++) {
                urlSpans[i].copyConnectionDataFrom(this);
            }
        }
        return mText;
    }

    /**
     * Get the text passed to setText before any changes to the spans.
     * @hide
     */
    public CharSequence getOriginalText() {
        return mOriginalText;
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
        mOriginalText = text;
        // Replace any ClickableSpans in mText with placeholders
        if (text instanceof Spanned) {
            ClickableSpan[] spans =
                    ((Spanned) text).getSpans(0, text.length(), ClickableSpan.class);
            if (spans.length > 0) {
                Spannable spannable = new SpannableStringBuilder(text);
                for (int i = 0; i < spans.length; i++) {
                    ClickableSpan span = spans[i];
                    if ((span instanceof AccessibilityClickableSpan)
                            || (span instanceof AccessibilityURLSpan)) {
                        // We've already done enough
                        break;
                    }
                    int spanToReplaceStart = spannable.getSpanStart(span);
                    int spanToReplaceEnd = spannable.getSpanEnd(span);
                    int spanToReplaceFlags = spannable.getSpanFlags(span);
                    spannable.removeSpan(span);
                    ClickableSpan replacementSpan = (span instanceof URLSpan)
                            ? new AccessibilityURLSpan((URLSpan) span)
                            : new AccessibilityClickableSpan(span.getId());
                    spannable.setSpan(replacementSpan, spanToReplaceStart, spanToReplaceEnd,
                            spanToReplaceFlags);
                }
                mText = spannable;
                return;
            }
        }
        mText = (text == null) ? null : text.subSequence(0, text.length());
    }

    /**
     * Gets the hint text of this node. Only applies to nodes where text can be entered.
     *
     * @return The hint text.
     */
    public CharSequence getHintText() {
        return mHintText;
    }

    /**
     * Sets the hint text of this node. Only applies to nodes where text can be entered.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param hintText The hint text for this mode.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setHintText(CharSequence hintText) {
        enforceNotSealed();
        mHintText = (hintText == null) ? null : hintText.subSequence(0, hintText.length());
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
        mError = (error == null) ? null : error.subSequence(0, error.length());
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
        mContentDescription = (contentDescription == null) ? null
                : contentDescription.subSequence(0, contentDescription.length());
    }

    /**
     * Gets the tooltip text of this node.
     *
     * @return The tooltip text.
     */
    @Nullable
    public CharSequence getTooltipText() {
        return mTooltipText;
    }

    /**
     * Sets the tooltip text of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param tooltipText The tooltip text.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setTooltipText(@Nullable CharSequence tooltipText) {
        enforceNotSealed();
        mTooltipText = (tooltipText == null) ? null
                : tooltipText.subSequence(0, tooltipText.length());
    }

    /**
     * Sets the view for which the view represented by this info serves as a
     * label for accessibility purposes.
     *
     * @param labeled The view for which this info serves as a label.
     */
    public void setLabelFor(View labeled) {
        setLabelFor(labeled, AccessibilityNodeProvider.HOST_VIEW_ID);
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
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mLabelForId);
    }

    /**
     * Sets the view which serves as the label of the view represented by
     * this info for accessibility purposes.
     *
     * @param label The view that labels this node's source.
     */
    public void setLabeledBy(View label) {
        setLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
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
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mLabeledById);
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
     * Gets the text selection start or the cursor position.
     * <p>
     * If no text is selected, both this method and
     * {@link AccessibilityNodeInfo#getTextSelectionEnd()} return the same value:
     * the current location of the cursor.
     * </p>
     *
     * @return The text selection start, the cursor location if there is no selection, or -1 if
     *         there is no text selection and no cursor.
     */
    public int getTextSelectionStart() {
        return mTextSelectionStart;
    }

    /**
     * Gets the text selection end if text is selected.
     * <p>
     * If no text is selected, both this method and
     * {@link AccessibilityNodeInfo#getTextSelectionStart()} return the same value:
     * the current location of the cursor.
     * </p>
     *
     * @return The text selection end, the cursor location if there is no selection, or -1 if
     *         there is no text selection and no cursor.
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
     * Check if a node has an extras bundle
     * @hide
     */
    public boolean hasExtras() {
        return mExtras != null;
    }

    /**
     * Get the {@link TouchDelegateInfo} for touch delegate behavior with the represented view.
     * It is possible for the same node to be pointed to by several regions. Use
     * {@link TouchDelegateInfo#getRegionAt(int)} to get touch delegate target {@link Region}, and
     * {@link TouchDelegateInfo#getTargetForRegion(Region)} for {@link AccessibilityNodeInfo} from
     * the given region.
     *
     * @return {@link TouchDelegateInfo} or {@code null} if there are no touch delegates.
     */
    @Nullable
    public TouchDelegateInfo getTouchDelegateInfo() {
        if (mTouchDelegateInfo != null) {
            mTouchDelegateInfo.setConnectionId(mConnectionId);
            mTouchDelegateInfo.setWindowId(mWindowId);
        }
        return mTouchDelegateInfo;
    }

    /**
     * Set touch delegate info if the represented view has a {@link TouchDelegate}.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an
     *   AccessibilityService.
     * </p>
     *
     * @param delegatedInfo {@link TouchDelegateInfo} returned from
     *         {@link TouchDelegate#getTouchDelegateInfo()}.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setTouchDelegateInfo(@NonNull TouchDelegateInfo delegatedInfo) {
        enforceNotSealed();
        mTouchDelegateInfo = delegatedInfo;
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
     * Get the connection ID.
     *
     * @return The connection id
     *
     * @hide
     */
    public int getConnectionId() {
        return mConnectionId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Sets the id of the source node.
     *
     * @param sourceId The id.
     * @param windowId The window id.
     *
     * @hide
     */
    public void setSourceNodeId(long sourceId, int windowId) {
        enforceNotSealed();
        mSourceNodeId = sourceId;
        mWindowId = windowId;
    }

    /**
     * Gets the id of the source node.
     *
     * @return The id.
     *
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
        if (sNumInstancesInUse != null) {
            sNumInstancesInUse.incrementAndGet();
        }
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
        if (sNumInstancesInUse != null) {
            sNumInstancesInUse.decrementAndGet();
        }
    }

    /**
     * Specify a counter that will be incremented on obtain() and decremented on recycle()
     *
     * @hide
     */
    @TestApi
    public static void setNumInstancesInUseCounter(AtomicInteger counter) {
        sNumInstancesInUse = counter;
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
        writeToParcelNoRecycle(parcel, flags);
        // Since instances of this class are fetched via synchronous i.e. blocking
        // calls in IPCs we always recycle as soon as the instance is marshaled.
        recycle();
    }

    /** @hide */
    @TestApi
    public void writeToParcelNoRecycle(Parcel parcel, int flags) {
        // Write bit set of indices of fields with values differing from default
        long nonDefaultFields = 0;
        int fieldIndex = 0; // index of the current field
        if (isSealed() != DEFAULT.isSealed()) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mSourceNodeId != DEFAULT.mSourceNodeId) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mWindowId != DEFAULT.mWindowId) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mParentNodeId != DEFAULT.mParentNodeId) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mLabelForId != DEFAULT.mLabelForId) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mLabeledById != DEFAULT.mLabeledById) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mTraversalBefore != DEFAULT.mTraversalBefore) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mTraversalAfter != DEFAULT.mTraversalAfter) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mConnectionId != DEFAULT.mConnectionId) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!LongArray.elementsEqual(mChildNodeIds, DEFAULT.mChildNodeIds)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mBoundsInParent, DEFAULT.mBoundsInParent)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mBoundsInScreen, DEFAULT.mBoundsInScreen)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mActions, DEFAULT.mActions)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mMaxTextLength != DEFAULT.mMaxTextLength) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mMovementGranularities != DEFAULT.mMovementGranularities) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mBooleanProperties != DEFAULT.mBooleanProperties) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mPackageName, DEFAULT.mPackageName)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mClassName, DEFAULT.mClassName)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mText, DEFAULT.mText)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mHintText, DEFAULT.mHintText)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mError, DEFAULT.mError)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mContentDescription, DEFAULT.mContentDescription)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mPaneTitle, DEFAULT.mPaneTitle)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mTooltipText, DEFAULT.mTooltipText)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mViewIdResourceName, DEFAULT.mViewIdResourceName)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mTextSelectionStart != DEFAULT.mTextSelectionStart) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mTextSelectionEnd != DEFAULT.mTextSelectionEnd) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mInputType != DEFAULT.mInputType) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mLiveRegion != DEFAULT.mLiveRegion) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mDrawingOrderInParent != DEFAULT.mDrawingOrderInParent) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mExtraDataKeys, DEFAULT.mExtraDataKeys)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mExtras, DEFAULT.mExtras)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mRangeInfo, DEFAULT.mRangeInfo)) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (!Objects.equals(mCollectionInfo, DEFAULT.mCollectionInfo)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mCollectionItemInfo, DEFAULT.mCollectionItemInfo)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mTouchDelegateInfo, DEFAULT.mTouchDelegateInfo)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        int totalFields = fieldIndex;
        parcel.writeLong(nonDefaultFields);

        fieldIndex = 0;
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(isSealed() ? 1 : 0);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mSourceNodeId);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mWindowId);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mParentNodeId);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mLabelForId);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mLabeledById);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mTraversalBefore);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mTraversalAfter);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mConnectionId);

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
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
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeInt(mBoundsInParent.top);
            parcel.writeInt(mBoundsInParent.bottom);
            parcel.writeInt(mBoundsInParent.left);
            parcel.writeInt(mBoundsInParent.right);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeInt(mBoundsInScreen.top);
            parcel.writeInt(mBoundsInScreen.bottom);
            parcel.writeInt(mBoundsInScreen.left);
            parcel.writeInt(mBoundsInScreen.right);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            if (mActions != null && !mActions.isEmpty()) {
                final int actionCount = mActions.size();

                int nonStandardActionCount = 0;
                long defaultStandardActions = 0;
                for (int i = 0; i < actionCount; i++) {
                    AccessibilityAction action = mActions.get(i);
                    if (isDefaultStandardAction(action)) {
                        defaultStandardActions |= action.mSerializationFlag;
                    } else {
                        nonStandardActionCount++;
                    }
                }
                parcel.writeLong(defaultStandardActions);

                parcel.writeInt(nonStandardActionCount);
                for (int i = 0; i < actionCount; i++) {
                    AccessibilityAction action = mActions.get(i);
                    if (!isDefaultStandardAction(action)) {
                        parcel.writeInt(action.getId());
                        parcel.writeCharSequence(action.getLabel());
                    }
                }
            } else {
                parcel.writeLong(0);
                parcel.writeInt(0);
            }
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mMaxTextLength);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mMovementGranularities);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mBooleanProperties);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mPackageName);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mClassName);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mText);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mHintText);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mError);
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeCharSequence(mContentDescription);
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mPaneTitle);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mTooltipText);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeString(mViewIdResourceName);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mTextSelectionStart);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mTextSelectionEnd);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mInputType);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mLiveRegion);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeInt(mDrawingOrderInParent);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeStringList(mExtraDataKeys);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeBundle(mExtras);

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeInt(mRangeInfo.getType());
            parcel.writeFloat(mRangeInfo.getMin());
            parcel.writeFloat(mRangeInfo.getMax());
            parcel.writeFloat(mRangeInfo.getCurrent());
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeInt(mCollectionInfo.getRowCount());
            parcel.writeInt(mCollectionInfo.getColumnCount());
            parcel.writeInt(mCollectionInfo.isHierarchical() ? 1 : 0);
            parcel.writeInt(mCollectionInfo.getSelectionMode());
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeInt(mCollectionItemInfo.getRowIndex());
            parcel.writeInt(mCollectionItemInfo.getRowSpan());
            parcel.writeInt(mCollectionItemInfo.getColumnIndex());
            parcel.writeInt(mCollectionItemInfo.getColumnSpan());
            parcel.writeInt(mCollectionItemInfo.isHeading() ? 1 : 0);
            parcel.writeInt(mCollectionItemInfo.isSelected() ? 1 : 0);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mTouchDelegateInfo.writeToParcel(parcel, flags);
        }

        if (DEBUG) {
            fieldIndex--;
            if (totalFields != fieldIndex) {
                throw new IllegalStateException("Number of fields mismatch: " + totalFields
                        + " vs " + fieldIndex);
            }
        }
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
        mOriginalText = other.mOriginalText;
        mHintText = other.mHintText;
        mError = other.mError;
        mContentDescription = other.mContentDescription;
        mPaneTitle = other.mPaneTitle;
        mTooltipText = other.mTooltipText;
        mViewIdResourceName = other.mViewIdResourceName;

        if (mActions != null) mActions.clear();
        final ArrayList<AccessibilityAction> otherActions = other.mActions;
        if (otherActions != null && otherActions.size() > 0) {
            if (mActions == null) {
                mActions = new ArrayList(otherActions);
            } else {
                mActions.addAll(other.mActions);
            }
        }

        mBooleanProperties = other.mBooleanProperties;
        mMaxTextLength = other.mMaxTextLength;
        mMovementGranularities = other.mMovementGranularities;


        if (mChildNodeIds != null) mChildNodeIds.clear();
        final LongArray otherChildNodeIds = other.mChildNodeIds;
        if (otherChildNodeIds != null && otherChildNodeIds.size() > 0) {
            if (mChildNodeIds == null) {
                mChildNodeIds = otherChildNodeIds.clone();
            } else {
                mChildNodeIds.addAll(otherChildNodeIds);
            }
        }

        mTextSelectionStart = other.mTextSelectionStart;
        mTextSelectionEnd = other.mTextSelectionEnd;
        mInputType = other.mInputType;
        mLiveRegion = other.mLiveRegion;
        mDrawingOrderInParent = other.mDrawingOrderInParent;

        mExtraDataKeys = other.mExtraDataKeys;

        mExtras = other.mExtras != null ? new Bundle(other.mExtras) : null;

        if (mRangeInfo != null) mRangeInfo.recycle();
        mRangeInfo = (other.mRangeInfo != null)
                ? RangeInfo.obtain(other.mRangeInfo) : null;
        if (mCollectionInfo != null) mCollectionInfo.recycle();
        mCollectionInfo = (other.mCollectionInfo != null)
                ? CollectionInfo.obtain(other.mCollectionInfo) : null;
        if (mCollectionItemInfo != null) mCollectionItemInfo.recycle();
        mCollectionItemInfo =  (other.mCollectionItemInfo != null)
                ? CollectionItemInfo.obtain(other.mCollectionItemInfo) : null;

        final TouchDelegateInfo otherInfo = other.mTouchDelegateInfo;
        mTouchDelegateInfo = (otherInfo != null)
                ? new TouchDelegateInfo(otherInfo.mTargetMap, true) : null;
    }

    /**
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityNodeInfo}.
     */
    private void initFromParcel(Parcel parcel) {
        // Bit mask of non-default-valued field indices
        long nonDefaultFields = parcel.readLong();
        int fieldIndex = 0;
        final boolean sealed = isBitSet(nonDefaultFields, fieldIndex++)
                ? (parcel.readInt() == 1)
                : DEFAULT.mSealed;
        if (isBitSet(nonDefaultFields, fieldIndex++)) mSourceNodeId = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mWindowId = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mParentNodeId = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mLabelForId = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mLabeledById = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTraversalBefore = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTraversalAfter = parcel.readLong();

        if (isBitSet(nonDefaultFields, fieldIndex++)) mConnectionId = parcel.readInt();

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
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
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mBoundsInParent.top = parcel.readInt();
            mBoundsInParent.bottom = parcel.readInt();
            mBoundsInParent.left = parcel.readInt();
            mBoundsInParent.right = parcel.readInt();
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mBoundsInScreen.top = parcel.readInt();
            mBoundsInScreen.bottom = parcel.readInt();
            mBoundsInScreen.left = parcel.readInt();
            mBoundsInScreen.right = parcel.readInt();
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            final long standardActions = parcel.readLong();
            addStandardActions(standardActions);
            final int nonStandardActionCount = parcel.readInt();
            for (int i = 0; i < nonStandardActionCount; i++) {
                final AccessibilityAction action = new AccessibilityAction(
                        parcel.readInt(), parcel.readCharSequence());
                addActionUnchecked(action);
            }
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) mMaxTextLength = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mMovementGranularities = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mBooleanProperties = parcel.readInt();

        if (isBitSet(nonDefaultFields, fieldIndex++)) mPackageName = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mClassName = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mText = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mHintText = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mError = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mContentDescription = parcel.readCharSequence();
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) mPaneTitle = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTooltipText = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mViewIdResourceName = parcel.readString();

        if (isBitSet(nonDefaultFields, fieldIndex++)) mTextSelectionStart = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTextSelectionEnd = parcel.readInt();

        if (isBitSet(nonDefaultFields, fieldIndex++)) mInputType = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mLiveRegion = parcel.readInt();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mDrawingOrderInParent = parcel.readInt();

        mExtraDataKeys = isBitSet(nonDefaultFields, fieldIndex++)
                ? parcel.createStringArrayList()
                : null;

        mExtras = isBitSet(nonDefaultFields, fieldIndex++)
                ? parcel.readBundle()
                : null;

        if (mRangeInfo != null) mRangeInfo.recycle();
        mRangeInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? RangeInfo.obtain(
                        parcel.readInt(),
                        parcel.readFloat(),
                        parcel.readFloat(),
                        parcel.readFloat())
                : null;

        if (mCollectionInfo != null) mCollectionInfo.recycle();
        mCollectionInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? CollectionInfo.obtain(
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt() == 1,
                        parcel.readInt())
                : null;

        if (mCollectionItemInfo != null) mCollectionItemInfo.recycle();
        mCollectionItemInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? CollectionItemInfo.obtain(
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt() == 1,
                        parcel.readInt() == 1)
                : null;

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mTouchDelegateInfo = TouchDelegateInfo.CREATOR.createFromParcel(parcel);
        }

        mSealed = sealed;
    }

    /**
     * Clears the state of this instance.
     */
    private void clear() {
        init(DEFAULT);
    }

    private static boolean isDefaultStandardAction(AccessibilityAction action) {
        return (action.mSerializationFlag != -1L) && TextUtils.isEmpty(action.getLabel());
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

    private static AccessibilityAction getActionSingletonBySerializationFlag(long flag) {
        final int actions = AccessibilityAction.sStandardActions.size();
        for (int i = 0; i < actions; i++) {
            AccessibilityAction currentAction = AccessibilityAction.sStandardActions.valueAt(i);
            if (flag == currentAction.mSerializationFlag) {
                return currentAction;
            }
        }

        return null;
    }

    private void addStandardActions(long serializationIdMask) {
        long remainingIds = serializationIdMask;
        while (remainingIds > 0) {
            final long id = 1L << Long.numberOfTrailingZeros(remainingIds);
            remainingIds &= ~id;
            AccessibilityAction action = getActionSingletonBySerializationFlag(id);
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
            case ACTION_EXPAND:
                return "ACTION_EXPAND";
            case ACTION_COLLAPSE:
                return "ACTION_COLLAPSE";
            case ACTION_DISMISS:
                return "ACTION_DISMISS";
            case ACTION_SET_TEXT:
                return "ACTION_SET_TEXT";
            case R.id.accessibilityActionShowOnScreen:
                return "ACTION_SHOW_ON_SCREEN";
            case R.id.accessibilityActionScrollToPosition:
                return "ACTION_SCROLL_TO_POSITION";
            case R.id.accessibilityActionScrollUp:
                return "ACTION_SCROLL_UP";
            case R.id.accessibilityActionScrollLeft:
                return "ACTION_SCROLL_LEFT";
            case R.id.accessibilityActionScrollDown:
                return "ACTION_SCROLL_DOWN";
            case R.id.accessibilityActionScrollRight:
                return "ACTION_SCROLL_RIGHT";
            case R.id.accessibilityActionPageDown:
                return "ACTION_PAGE_DOWN";
            case R.id.accessibilityActionPageUp:
                return "ACTION_PAGE_UP";
            case R.id.accessibilityActionPageLeft:
                return "ACTION_PAGE_LEFT";
            case R.id.accessibilityActionPageRight:
                return "ACTION_PAGE_RIGHT";
            case R.id.accessibilityActionSetProgress:
                return "ACTION_SET_PROGRESS";
            case R.id.accessibilityActionContextClick:
                return "ACTION_CONTEXT_CLICK";
            case R.id.accessibilityActionShowTooltip:
                return "ACTION_SHOW_TOOLTIP";
            case R.id.accessibilityActionHideTooltip:
                return "ACTION_HIDE_TOOLTIP";
            default:
                return "ACTION_UNKNOWN";
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

    private static boolean canPerformRequestOverConnection(int connectionId,
            int windowId, long accessibilityNodeId) {
        return ((windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
                && (getAccessibilityViewId(accessibilityNodeId) != UNDEFINED_ITEM_ID)
                && (connectionId != UNDEFINED_CONNECTION_ID));
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
            builder.append("; windowId: " + mWindowId);
            builder.append("; accessibilityViewId: ").append(getAccessibilityViewId(mSourceNodeId));
            builder.append("; virtualDescendantId: ").append(getVirtualDescendantId(mSourceNodeId));
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

        builder.append("; boundsInParent: ").append(mBoundsInParent);
        builder.append("; boundsInScreen: ").append(mBoundsInScreen);

        builder.append("; packageName: ").append(mPackageName);
        builder.append("; className: ").append(mClassName);
        builder.append("; text: ").append(mText);
        builder.append("; error: ").append(mError);
        builder.append("; maxTextLength: ").append(mMaxTextLength);
        builder.append("; contentDescription: ").append(mContentDescription);
        builder.append("; tooltipText: ").append(mTooltipText);
        builder.append("; viewIdResName: ").append(mViewIdResourceName);

        builder.append("; checkable: ").append(isCheckable());
        builder.append("; checked: ").append(isChecked());
        builder.append("; focusable: ").append(isFocusable());
        builder.append("; focused: ").append(isFocused());
        builder.append("; selected: ").append(isSelected());
        builder.append("; clickable: ").append(isClickable());
        builder.append("; longClickable: ").append(isLongClickable());
        builder.append("; contextClickable: ").append(isContextClickable());
        builder.append("; enabled: ").append(isEnabled());
        builder.append("; password: ").append(isPassword());
        builder.append("; scrollable: ").append(isScrollable());
        builder.append("; importantForAccessibility: ").append(isImportantForAccessibility());
        builder.append("; visible: ").append(isVisibleToUser());
        builder.append("; actions: ").append(mActions);

        return builder.toString();
    }

    private static AccessibilityNodeInfo getNodeForAccessibilityId(int connectionId,
            int windowId, long accessibilityId) {
        if (!canPerformRequestOverConnection(connectionId, windowId, accessibilityId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(connectionId,
                windowId, accessibilityId, false, FLAG_PREFETCH_PREDECESSORS
                        | FLAG_PREFETCH_DESCENDANTS | FLAG_PREFETCH_SIBLINGS, null);
    }

    /** @hide */
    public static String idToString(long accessibilityId) {
        int accessibilityViewId = getAccessibilityViewId(accessibilityId);
        int virtualDescendantId = getVirtualDescendantId(accessibilityId);
        return virtualDescendantId == AccessibilityNodeProvider.HOST_VIEW_ID
                ? idItemToString(accessibilityViewId)
                : idItemToString(accessibilityViewId) + ":" + idItemToString(virtualDescendantId);
    }

    private static String idItemToString(int item) {
        switch (item) {
            case ROOT_ITEM_ID: return "ROOT";
            case UNDEFINED_ITEM_ID: return "UNDEFINED";
            case AccessibilityNodeProvider.HOST_VIEW_ID: return "HOST";
            default: return "" + item;
        }
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
     * These actions will have {@code null} labels.
     * </li>
     * <li><strong>Custom actions action</strong> - These are actions that are reported
     * and handled by custom widgets. i.e. ones that are not part of the UI toolkit. For
     * example, an application may define a custom action for clearing the user history.
     * </li>
     * <li><strong>Overriden standard actions</strong> - These are actions that override
     * standard actions to customize them. For example, an app may add a label to the
     * standard {@link #ACTION_CLICK} action to indicate to the user that this action clears
     * browsing history.
     * </ul>
     * </p>
     * <p>
     * Actions are typically added to an {@link AccessibilityNodeInfo} by using
     * {@link AccessibilityNodeInfo#addAction(AccessibilityAction)} within
     * {@link View#onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo)} and are performed
     * within {@link View#performAccessibilityAction(int, Bundle)}.
     * </p>
     * <p class="note">
     * <strong>Note:</strong> Views which support these actions should invoke
     * {@link View#setImportantForAccessibility(int)} with
     * {@link View#IMPORTANT_FOR_ACCESSIBILITY_YES} to ensure an {@link AccessibilityService}
     * can discover the set of supported actions.
     * </p>
     */
    public static final class AccessibilityAction {

        /** @hide */
        public static final ArraySet<AccessibilityAction> sStandardActions = new ArraySet<>();

        /**
         * Action that gives input focus to the node.
         */
        public static final AccessibilityAction ACTION_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS);

        /**
         * Action that clears input focus of the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);

        /**
         *  Action that selects the node.
         */
        public static final AccessibilityAction ACTION_SELECT =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT);

        /**
         * Action that deselects the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_SELECTION =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);

        /**
         * Action that clicks on the node info.
         */
        public static final AccessibilityAction ACTION_CLICK =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK);

        /**
         * Action that long clicks on the node.
         */
        public static final AccessibilityAction ACTION_LONG_CLICK =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);

        /**
         * Action that gives accessibility focus to the node.
         */
        public static final AccessibilityAction ACTION_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);

        /**
         * Action that clears accessibility focus of the node.
         */
        public static final AccessibilityAction ACTION_CLEAR_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);

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
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);

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
                        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);

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
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT);

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
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT);

        /**
         * Action to scroll the node content forward.
         */
        public static final AccessibilityAction ACTION_SCROLL_FORWARD =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);

        /**
         * Action to scroll the node content backward.
         */
        public static final AccessibilityAction ACTION_SCROLL_BACKWARD =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);

        /**
         * Action to copy the current selection to the clipboard.
         */
        public static final AccessibilityAction ACTION_COPY =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_COPY);

        /**
         * Action to paste the current clipboard content.
         */
        public static final AccessibilityAction ACTION_PASTE =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE);

        /**
         * Action to cut the current selection and place it to the clipboard.
         */
        public static final AccessibilityAction ACTION_CUT =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CUT);

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
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION);

        /**
         * Action to expand an expandable node.
         */
        public static final AccessibilityAction ACTION_EXPAND =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND);

        /**
         * Action to collapse an expandable node.
         */
        public static final AccessibilityAction ACTION_COLLAPSE =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE);

        /**
         * Action to dismiss a dismissable node.
         */
        public static final AccessibilityAction ACTION_DISMISS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS);

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
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT);

        /**
         * Action that requests the node make its bounding rectangle visible
         * on the screen, scrolling if necessary just enough.
         *
         * @see View#requestRectangleOnScreen(Rect)
         */
        public static final AccessibilityAction ACTION_SHOW_ON_SCREEN =
                new AccessibilityAction(R.id.accessibilityActionShowOnScreen);

        /**
         * Action that scrolls the node to make the specified collection
         * position visible on screen.
         * <p>
         * <strong>Arguments:</strong>
         * <ul>
         *     <li>{@link AccessibilityNodeInfo#ACTION_ARGUMENT_ROW_INT}</li>
         *     <li>{@link AccessibilityNodeInfo#ACTION_ARGUMENT_COLUMN_INT}</li>
         * <ul>
         *
         * @see AccessibilityNodeInfo#getCollectionInfo()
         */
        public static final AccessibilityAction ACTION_SCROLL_TO_POSITION =
                new AccessibilityAction(R.id.accessibilityActionScrollToPosition);

        /**
         * Action to scroll the node content up.
         */
        public static final AccessibilityAction ACTION_SCROLL_UP =
                new AccessibilityAction(R.id.accessibilityActionScrollUp);

        /**
         * Action to scroll the node content left.
         */
        public static final AccessibilityAction ACTION_SCROLL_LEFT =
                new AccessibilityAction(R.id.accessibilityActionScrollLeft);

        /**
         * Action to scroll the node content down.
         */
        public static final AccessibilityAction ACTION_SCROLL_DOWN =
                new AccessibilityAction(R.id.accessibilityActionScrollDown);

        /**
         * Action to scroll the node content right.
         */
        public static final AccessibilityAction ACTION_SCROLL_RIGHT =
                new AccessibilityAction(R.id.accessibilityActionScrollRight);

        /**
         * Action to move to the page above.
         */
        public static final AccessibilityAction ACTION_PAGE_UP =
                new AccessibilityAction(R.id.accessibilityActionPageUp);

        /**
         * Action to move to the page below.
         */
        public static final AccessibilityAction ACTION_PAGE_DOWN =
                new AccessibilityAction(R.id.accessibilityActionPageDown);

        /**
         * Action to move to the page left.
         */
        public static final AccessibilityAction ACTION_PAGE_LEFT =
                new AccessibilityAction(R.id.accessibilityActionPageLeft);

        /**
         * Action to move to the page right.
         */
        public static final AccessibilityAction ACTION_PAGE_RIGHT =
                new AccessibilityAction(R.id.accessibilityActionPageRight);

        /**
         * Action that context clicks the node.
         */
        public static final AccessibilityAction ACTION_CONTEXT_CLICK =
                new AccessibilityAction(R.id.accessibilityActionContextClick);

        /**
         * Action that sets progress between {@link  RangeInfo#getMin() RangeInfo.getMin()} and
         * {@link  RangeInfo#getMax() RangeInfo.getMax()}. It should use the same value type as
         * {@link RangeInfo#getType() RangeInfo.getType()}
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_PROGRESS_VALUE}
         *
         * @see RangeInfo
         */
        public static final AccessibilityAction ACTION_SET_PROGRESS =
                new AccessibilityAction(R.id.accessibilityActionSetProgress);

        /**
         * Action to move a window to a new location.
         * <p>
         * <strong>Arguments:</strong>
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_MOVE_WINDOW_X}
         * {@link AccessibilityNodeInfo#ACTION_ARGUMENT_MOVE_WINDOW_Y}
         */
        public static final AccessibilityAction ACTION_MOVE_WINDOW =
                new AccessibilityAction(R.id.accessibilityActionMoveWindow);

        /**
         * Action to show a tooltip. A node should expose this action only for views with tooltip
         * text that but are not currently showing a tooltip.
         */
        public static final AccessibilityAction ACTION_SHOW_TOOLTIP =
                new AccessibilityAction(R.id.accessibilityActionShowTooltip);

        /**
         * Action to hide a tooltip. A node should expose this action only for views that are
         * currently showing a tooltip.
         */
        public static final AccessibilityAction ACTION_HIDE_TOOLTIP =
                new AccessibilityAction(R.id.accessibilityActionHideTooltip);

        private final int mActionId;
        private final CharSequence mLabel;

        /** @hide */
        public long mSerializationFlag = -1L;

        /**
         * Creates a new AccessibilityAction. For adding a standard action without a specific label,
         * use the static constants.
         *
         * You can also override the description for one the standard actions. Below is an example
         * how to override the standard click action by adding a custom label:
         * <pre>
         *   AccessibilityAction action = new AccessibilityAction(
         *           AccessibilityAction.ACTION_CLICK.getId(), getLocalizedLabel());
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
         * Constructor for a {@link #sStandardActions standard} action
         */
        private AccessibilityAction(int standardActionId) {
            this(standardActionId, null);

            mSerializationFlag = bitAt(sStandardActions.size());
            sStandardActions.add(this);
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
     * {@link RangeInfo#obtain(int, float, float, float)} to get an instance. Recycling is
     * handled by the {@link AccessibilityNodeInfo} to which this object is attached.
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
         * @param min The minimum value. Use {@code Float.NEGATIVE_INFINITY} if the range has no
         *            minimum.
         * @param max The maximum value. Use {@code Float.POSITIVE_INFINITY} if the range has no
         *            maximum.
         * @param current The current value.
         */
        public static RangeInfo obtain(int type, float min, float max, float current) {
            RangeInfo info = sPool.acquire();
            if (info == null) {
                return new RangeInfo(type, min, max, current);
            }

            info.mType = type;
            info.mMin = min;
            info.mMax = max;
            info.mCurrent = current;
            return info;
        }

        /**
         * Creates a new range.
         *
         * @param type The type of the range.
         * @param min The minimum value. Use {@code Float.NEGATIVE_INFINITY} if the range has no
         *            minimum.
         * @param max The maximum value. Use {@code Float.POSITIVE_INFINITY} if the range has no
         *            maximum.
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
         * Gets the minimum value.
         *
         * @return The minimum value, or {@code Float.NEGATIVE_INFINITY} if no minimum exists.
         */
        public float getMin() {
            return mMin;
        }

        /**
         * Gets the maximum value.
         *
         * @return The maximum value, or {@code Float.POSITIVE_INFINITY} if no maximum exists.
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
     * {@link CollectionInfo#obtain(int, int, boolean)} to get an instance. Recycling is
     * handled by the {@link AccessibilityNodeInfo} to which this object is attached.
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
                new SynchronizedPool<>(MAX_POOL_SIZE);

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
     * to get an instance. Recycling is handled by the {@link AccessibilityNodeInfo} to which this
     * object is attached.
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
                new SynchronizedPool<>(MAX_POOL_SIZE);

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
         * @param heading Whether the item is a heading. (Prefer
         *                {@link AccessibilityNodeInfo#setHeading(boolean)}).
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
         * @param heading Whether the item is a heading. (Prefer
         *                {@link AccessibilityNodeInfo#setHeading(boolean)})
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
         * @deprecated Use {@link AccessibilityNodeInfo#isHeading()}
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
     * Class with information of touch delegated views and regions from {@link TouchDelegate} for
     * the {@link AccessibilityNodeInfo}.
     *
     * @see AccessibilityNodeInfo#setTouchDelegateInfo(TouchDelegateInfo)
     */
    public static final class TouchDelegateInfo implements Parcelable {
        private ArrayMap<Region, Long> mTargetMap;
        // Two ids are initialized lazily in AccessibilityNodeInfo#getTouchDelegateInfo
        private int mConnectionId;
        private int mWindowId;

        /**
         * Create a new instance of {@link TouchDelegateInfo}.
         *
         * @param targetMap A map from regions (in view coordinates) to delegated views.
         * @throws IllegalArgumentException if targetMap is empty or {@code null} in
         * Regions or Views.
         */
        public TouchDelegateInfo(@NonNull Map<Region, View> targetMap) {
            Preconditions.checkArgument(!targetMap.isEmpty()
                    && !targetMap.containsKey(null) && !targetMap.containsValue(null));
            mTargetMap = new ArrayMap<>(targetMap.size());
            for (final Region region : targetMap.keySet()) {
                final View view = targetMap.get(region);
                mTargetMap.put(region, (long) view.getAccessibilityViewId());
            }
        }

        /**
         * Create a new instance from target map.
         *
         * @param targetMap A map from regions (in view coordinates) to delegated views'
         *                  accessibility id.
         * @param doCopy True if shallow copy targetMap.
         * @throws IllegalArgumentException if targetMap is empty or {@code null} in
         * Regions or Views.
         */
        TouchDelegateInfo(@NonNull ArrayMap<Region, Long> targetMap, boolean doCopy) {
            Preconditions.checkArgument(!targetMap.isEmpty()
                    && !targetMap.containsKey(null) && !targetMap.containsValue(null));
            if (doCopy) {
                mTargetMap = new ArrayMap<>(targetMap.size());
                mTargetMap.putAll(targetMap);
            } else {
                mTargetMap = targetMap;
            }
        }

        /**
         * Set the connection ID.
         *
         * @param connectionId The connection id.
         */
        private void setConnectionId(int connectionId) {
            mConnectionId = connectionId;
        }

        /**
         * Set the window ID.
         *
         * @param windowId The window id.
         */
        private void setWindowId(int windowId) {
            mWindowId = windowId;
        }

        /**
         * Returns the number of touch delegate target region.
         *
         * @return Number of touch delegate target region.
         */
        public int getRegionCount() {
            return mTargetMap.size();
        }

        /**
         * Return the {@link Region} at the given index in the {@link TouchDelegateInfo}.
         *
         * @param index The desired index, must be between 0 and {@link #getRegionCount()}-1.
         * @return Returns the {@link Region} stored at the given index.
         */
        @NonNull
        public Region getRegionAt(int index) {
            return mTargetMap.keyAt(index);
        }

        /**
         * Return the target {@link AccessibilityNodeInfo} for the given {@link Region}.
         * <p>
         *   <strong>Note:</strong> This api can only be called from {@link AccessibilityService}.
         * </p>
         * <p>
         *   <strong>Note:</strong> It is a client responsibility to recycle the
         *     received info by calling {@link AccessibilityNodeInfo#recycle()}
         *     to avoid creating of multiple instances.
         * </p>
         *
         * @param region The region retrieved from {@link #getRegionAt(int)}.
         * @return The target node associates with the given region.
         */
        @Nullable
        public AccessibilityNodeInfo getTargetForRegion(@NonNull Region region) {
            return getNodeForAccessibilityId(mConnectionId, mWindowId, mTargetMap.get(region));
        }

        /**
         * Return the accessibility id of target node.
         *
         * @param region The region retrieved from {@link #getRegionAt(int)}.
         * @return The accessibility id of target node.
         *
         * @hide
         */
        @TestApi
        public long getAccessibilityIdForRegion(@NonNull Region region) {
            return mTargetMap.get(region);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mTargetMap.size());
            for (int i = 0; i < mTargetMap.size(); i++) {
                final Region region = mTargetMap.keyAt(i);
                final Long accessibilityId = mTargetMap.valueAt(i);
                region.writeToParcel(dest, flags);
                dest.writeLong(accessibilityId);
            }
        }

        /**
         * @see android.os.Parcelable.Creator
         */
        public static final @android.annotation.NonNull Parcelable.Creator<TouchDelegateInfo> CREATOR =
                new Parcelable.Creator<TouchDelegateInfo>() {
            @Override
            public TouchDelegateInfo createFromParcel(Parcel parcel) {
                final int size = parcel.readInt();
                if (size == 0) {
                    return null;
                }
                final ArrayMap<Region, Long> targetMap = new ArrayMap<>(size);
                for (int i = 0; i < size; i++) {
                    final Region region = Region.CREATOR.createFromParcel(parcel);
                    final long accessibilityId = parcel.readLong();
                    targetMap.put(region, accessibilityId);
                }
                final TouchDelegateInfo touchDelegateInfo = new TouchDelegateInfo(
                        targetMap, false);
                return touchDelegateInfo;
            }

            @Override
            public TouchDelegateInfo[] newArray(int size) {
                return new TouchDelegateInfo[size];
            }
        };
    }

    /**
     * @see android.os.Parcelable.Creator
     */
    public static final @android.annotation.NonNull Parcelable.Creator<AccessibilityNodeInfo> CREATOR =
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
