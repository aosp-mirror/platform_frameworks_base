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
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AccessibilityClickableSpan;
import android.text.style.AccessibilityReplacementSpan;
import android.text.style.AccessibilityURLSpan;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.text.style.URLSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongArray;
import android.util.Size;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * made immutable and calling a state mutation method generates an error. See
 * {@link #setQueryFromAppProcessEnabled} if you would like to inspect the
 * node tree from the app process for testing or debugging tools.
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
 * <aside class="note">
 * <b>Note:</b> Use a {@link androidx.core.view.accessibility.AccessibilityNodeInfoCompat}
 * wrapper instead of this class for backwards-compatibility. </aside>
 *
 * @see android.accessibilityservice.AccessibilityService
 * @see AccessibilityEvent
 * @see AccessibilityManager
 */
public class AccessibilityNodeInfo implements Parcelable {

    private static final String TAG = "AccessibilityNodeInfo";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG) && Build.IS_DEBUGGABLE;

    /** @hide */
    public static final int UNDEFINED_CONNECTION_ID = -1;

    /** @hide */
    public static final int UNDEFINED_SELECTION_INDEX = -1;

    /** @hide */
    public static final int UNDEFINED_ITEM_ID = Integer.MAX_VALUE;

    /** @hide */
    public static final int ROOT_ITEM_ID = Integer.MAX_VALUE - 1;

    /** @hide */
    public static final int LEASHED_ITEM_ID = Integer.MAX_VALUE - 2;

    /** @hide */
    public static final long UNDEFINED_NODE_ID = makeNodeId(UNDEFINED_ITEM_ID, UNDEFINED_ITEM_ID);

    /** @hide */
    public static final long ROOT_NODE_ID = makeNodeId(ROOT_ITEM_ID,
            AccessibilityNodeProvider.HOST_VIEW_ID);

    /** @hide */
    public static final long LEASHED_NODE_ID = makeNodeId(LEASHED_ITEM_ID,
            AccessibilityNodeProvider.HOST_VIEW_ID);

    // Prefetch flags.

    /**
     * Prefetching strategy that prefetches the ancestors of the requested node.
     * <p> Ancestors will be prefetched before siblings and descendants.
     *
     * @see #getChild(int, int)
     * @see #getParent(int)
     * @see AccessibilityWindowInfo#getRoot(int)
     * @see AccessibilityService#getRootInActiveWindow(int)
     * @see AccessibilityEvent#getSource(int)
     */
    public static final int FLAG_PREFETCH_ANCESTORS = 1 /* << 0 */;

    /**
     * Prefetching strategy that prefetches the siblings of the requested node.
     * <p> To avoid disconnected trees, this flag will also prefetch the parent. Siblings will be
     * prefetched before descendants.
     *
     * <p> See {@link #FLAG_PREFETCH_ANCESTORS} for information on where these flags can be used.
     */
    public static final int FLAG_PREFETCH_SIBLINGS = 1 << 1;

    /**
     * Prefetching strategy that prefetches the descendants in a hybrid depth first and breadth
     * first approach.
     * <p> The children of the root node is prefetched before recursing on the children. This
     * must not be combined with {@link #FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST} or
     * {@link #FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST} or this will trigger an
     * IllegalArgumentException.
     *
     * <p> See {@link #FLAG_PREFETCH_ANCESTORS} for information on where these flags can be used.
     */
    public static final int FLAG_PREFETCH_DESCENDANTS_HYBRID = 1 << 2;

    /**
     * Prefetching strategy that prefetches the descendants of the requested node depth-first.
     * <p> This must not be combined with {@link #FLAG_PREFETCH_DESCENDANTS_HYBRID} or
     * {@link #FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST} or this will trigger an
     * IllegalArgumentException.
     *
     * <p> See {@link #FLAG_PREFETCH_ANCESTORS} for information on where these flags can be used.
     */
    public static final int FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST = 1 << 3;

    /**
     * Prefetching strategy that prefetches the descendants of the requested node breadth-first.
     * <p> This must not be combined with {@link #FLAG_PREFETCH_DESCENDANTS_HYBRID} or
     * {@link #FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST} or this will trigger an
     * IllegalArgumentException.
     *
     * <p> See {@link #FLAG_PREFETCH_ANCESTORS} for information on where these flags can be used.
     */
    public static final int FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST = 1 << 4;

    /**
     * Prefetching flag that specifies prefetching should not be interrupted by a request to
     * retrieve a node or perform an action on a node.
     *
     * <p> See {@link #FLAG_PREFETCH_ANCESTORS} for information on where these flags can be used.
     */
    public static final int FLAG_PREFETCH_UNINTERRUPTIBLE = 1 << 5;

    /**
     * Mask for {@link PrefetchingStrategy} all types.
     *
     * @see #FLAG_PREFETCH_ANCESTORS
     * @see #FLAG_PREFETCH_SIBLINGS
     * @see #FLAG_PREFETCH_DESCENDANTS_HYBRID
     * @see #FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
     * @see #FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST
     * @see #FLAG_PREFETCH_UNINTERRUPTIBLE
     *
     * @hide
     */
    public static final int FLAG_PREFETCH_MASK = 0x0000003F;

    /**
     * Mask for {@link PrefetchingStrategy} that includes only descendants-related strategies.
     *
     * @see #FLAG_PREFETCH_DESCENDANTS_HYBRID
     * @see #FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
     * @see #FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST
     *
     * @hide
     */
    public static final int FLAG_PREFETCH_DESCENDANTS_MASK = 0x0000001C;

    /**
     * Maximum batch size of prefetched nodes for a request.
     */
    @SuppressLint("MinMaxConstant")
    public static final int MAX_NUMBER_OF_PREFETCHED_NODES = 50;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_PREFETCH" }, value = {
            FLAG_PREFETCH_ANCESTORS,
            FLAG_PREFETCH_SIBLINGS,
            FLAG_PREFETCH_DESCENDANTS_HYBRID,
            FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST,
            FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST,
            FLAG_PREFETCH_UNINTERRUPTIBLE
    })
    public @interface PrefetchingStrategy {}

    // Service flags.

    /**
     * @see AccessibilityServiceInfo#FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
     * @hide
     */
    public static final int FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS = 1 << 7;

    /**
     * @see AccessibilityServiceInfo#FLAG_REPORT_VIEW_IDS
     * @hide
     */
    public static final int FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS = 1 << 8;

    /**
     * @see AccessibilityServiceInfo#isAccessibilityTool()
     * @hide
     */
    public static final int FLAG_SERVICE_IS_ACCESSIBILITY_TOOL = 1 << 9;

    /**
     * Mask for all types of additional view data exposed to services.
     *
     * @hide
     */
    public static final int FLAG_REPORT_MASK =
            FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS
                    | FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS
                    | FLAG_SERVICE_IS_ACCESSIBILITY_TOOL;

    // Actions.

    /**
     * Action that gives input focus to the node.
     * See {@link AccessibilityAction#ACTION_FOCUS}
     */
    public static final int ACTION_FOCUS =  1 /* << 0 */;

    /**
     * Action that clears input focus of the node.
     * See {@link AccessibilityAction#ACTION_CLEAR_FOCUS}
     */
    public static final int ACTION_CLEAR_FOCUS = 1 << 1;

    /**
     * Action that selects the node.
     * @see AccessibilityAction#ACTION_SELECT
     */
    public static final int ACTION_SELECT = 1 << 2;

    /**
     * Action that deselects the node.
     */
    public static final int ACTION_CLEAR_SELECTION = 1 << 3;

    /**
     * Action that clicks on the node info.
     *
     * @see AccessibilityAction#ACTION_CLICK
     */
    public static final int ACTION_CLICK = 1 << 4;

    /**
     * Action that long clicks on the node.
     *
     * <p>It does not support coordinate information for anchoring.</p>
     * @see AccessibilityAction#ACTION_LONG_CLICK
     */
    public static final int ACTION_LONG_CLICK = 1 << 5;

    /**
     * Action that gives accessibility focus to the node.
     * See {@link AccessibilityAction#ACTION_ACCESSIBILITY_FOCUS}
     */
    public static final int ACTION_ACCESSIBILITY_FOCUS = 1 << 6;

    /**
     * Action that clears accessibility focus of the node.
     * See {@link AccessibilityAction#ACTION_CLEAR_ACCESSIBILITY_FOCUS}
     */
    public static final int ACTION_CLEAR_ACCESSIBILITY_FOCUS = 1 << 7;

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
    public static final int ACTION_NEXT_AT_MOVEMENT_GRANULARITY = 1 << 8;

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
    public static final int ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY = 1 << 9;

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
    public static final int ACTION_NEXT_HTML_ELEMENT = 1 << 10;

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
    public static final int ACTION_PREVIOUS_HTML_ELEMENT = 1 << 11;

    /**
     * Action to scroll the node content forward.
     * @see AccessibilityAction#ACTION_SCROLL_FORWARD
     */
    public static final int ACTION_SCROLL_FORWARD = 1 << 12;

    /**
     * Action to scroll the node content backward.
     * @see AccessibilityAction#ACTION_SCROLL_BACKWARD
     */
    public static final int ACTION_SCROLL_BACKWARD = 1 << 13;

    /**
     * Action to copy the current selection to the clipboard.
     */
    public static final int ACTION_COPY = 1 << 14;

    /**
     * Action to paste the current clipboard content.
     */
    public static final int ACTION_PASTE = 1 << 15;

    /**
     * Action to cut the current selection and place it to the clipboard.
     */
    public static final int ACTION_CUT = 1 << 16;

    /**
     * Action to set the selection. Performing this action with no arguments
     * clears the selection.
     *
     * @see AccessibilityAction#ACTION_SET_SELECTION
     * @see #ACTION_ARGUMENT_SELECTION_START_INT
     * @see #ACTION_ARGUMENT_SELECTION_END_INT
     */
    public static final int ACTION_SET_SELECTION = 1 << 17;

    /**
     * Action to expand an expandable node.
     */
    public static final int ACTION_EXPAND = 1 << 18;

    /**
     * Action to collapse an expandable node.
     */
    public static final int ACTION_COLLAPSE = 1 << 19;

    /**
     * Action to dismiss a dismissable node.
     */
    public static final int ACTION_DISMISS = 1 << 20;

    /**
     * Action that sets the text of the node. Performing the action without argument, using <code>
     * null</code> or empty {@link CharSequence} will clear the text. This action will also put the
     * cursor at the end of text.
     * @see AccessibilityAction#ACTION_SET_TEXT
     */
    public static final int ACTION_SET_TEXT = 1 << 21;

    /** @hide */
    public static final int LAST_LEGACY_STANDARD_ACTION = ACTION_SET_TEXT;

    /**
     * Mask to verify if a given value is a combination of the existing ACTION_ constants.
     *
     * The smallest possible action is 1, and the largest is 1 << 21, or {@link ACTION_SET_TEXT}. A
     * node can have any combination of actions present, so a node's max action int is:
     *
     *   0000 0000 0011 1111 1111 1111 1111 1111
     *
     * Therefore, if an action has any of the following bits flipped, it will be invalid:
     *
     *   1111 1111 11-- ---- ---- ---- ---- ----
     *
     * This can be represented in hexadecimal as 0xFFC00000.
     *
     * @see AccessibilityNodeInfo#addAction(int)
     */
    private static final int INVALID_ACTIONS_MASK = 0xFFC00000;

    // Action arguments.

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

    /**
     * Argument to represent the duration in milliseconds to press and hold a node.
     * <p>
     * <strong>Type:</strong> int<br>
     * <strong>Actions:</strong>
     * <ul>
     *     <li>{@link AccessibilityAction#ACTION_PRESS_AND_HOLD}</li>
     * </ul>
     *
     * @see AccessibilityAction#ACTION_PRESS_AND_HOLD
     */
    public static final String ACTION_ARGUMENT_PRESS_AND_HOLD_DURATION_MILLIS_INT =
            "android.view.accessibility.action.ARGUMENT_PRESS_AND_HOLD_DURATION_MILLIS_INT";

    /**
     * <p>Argument to represent the direction when using
     * {@link AccessibilityAction#ACTION_SCROLL_IN_DIRECTION}.</p>
     *
     * <p>
     *     The value of this argument can be one of:
     *     <ul>
     *         <li>{@link View#FOCUS_DOWN}</li>
     *         <li>{@link View#FOCUS_UP}</li>
     *         <li>{@link View#FOCUS_LEFT}</li>
     *         <li>{@link View#FOCUS_RIGHT}</li>
     *         <li>{@link View#FOCUS_FORWARD}</li>
     *         <li>{@link View#FOCUS_BACKWARD}</li>
     *     </ul>
     * </p>
     */
    public static final String ACTION_ARGUMENT_DIRECTION_INT =
            "android.view.accessibility.action.ARGUMENT_DIRECTION_INT";

    /**
     * <p>Argument to represent the scroll amount as a percent of the visible area of a node, with
     * 1.0F as the default. Values smaller than 1.0F represent a partial scroll of the node, and
     * values larger than 1.0F represent a scroll that extends beyond the currently visible node
     * Rect. Setting this to {@link Float#POSITIVE_INFINITY} or to another "too large" value should
     * scroll to the end of the node. Negative values should not be used with this argument.
     * </p>
     *
     * <p>
     *     This argument should be used with the following scroll actions:
     *     <ul>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_FORWARD}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_BACKWARD}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_UP}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_DOWN}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_LEFT}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_RIGHT}</li>
     *     </ul>
     * </p>
     * <p>
     *     Example: if a view representing a list of items implements
     *     {@link AccessibilityAction#ACTION_SCROLL_FORWARD} to scroll forward by an entire screen
     *     (one "page"), then passing a value of .25F via this argument should scroll that view
     *     only by 1/4th of a screen. Passing a value of 1.50F via this argument should scroll the
     *     view by 1 1/2 screens or to end of the node if the node doesn't extend to 1 1/2 screens.
     * </p>
     *
     * <p>
     *     This argument should not be used with the following scroll actions, which don't cleanly
     *     conform to granular scroll semantics:
     *     <ul>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_IN_DIRECTION}</li>
     *         <li>{@link AccessibilityAction#ACTION_SCROLL_TO_POSITION}</li>
     *     </ul>
     * </p>
     *
     * <p>
     *     Views that support this argument should set
     *     {@link #setGranularScrollingSupported(boolean)} to true. Clients should use
     *     {@link #isGranularScrollingSupported()} to check if granular scrolling is supported.
     * </p>
     */
    @FlaggedApi(Flags.FLAG_GRANULAR_SCROLLING)
    public static final String ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT =
            "android.view.accessibility.action.ARGUMENT_SCROLL_AMOUNT_FLOAT";

    // Focus types.

    /**
     * The input focus.
     */
    public static final int FOCUS_INPUT = 1;

    /**
     * The accessibility focus.
     */
    public static final int FOCUS_ACCESSIBILITY = 2;

    // Movement granularities.

    /**
     * Movement granularity bit for traversing the text of a node by character.
     */
    public static final int MOVEMENT_GRANULARITY_CHARACTER = 1 /* << 0 */;

    /**
     * Movement granularity bit for traversing the text of a node by word.
     */
    public static final int MOVEMENT_GRANULARITY_WORD = 1 << 1;

    /**
     * Movement granularity bit for traversing the text of a node by line.
     */
    public static final int MOVEMENT_GRANULARITY_LINE = 1 << 2;

    /**
     * Movement granularity bit for traversing the text of a node by paragraph.
     */
    public static final int MOVEMENT_GRANULARITY_PARAGRAPH = 1 << 3;

    /**
     * Movement granularity bit for traversing the text of a node by page.
     */
    public static final int MOVEMENT_GRANULARITY_PAGE = 1 << 4;

    // Extra data arguments.

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
     * positive and no larger than {@link #EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH}.
     *
     * @see #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
     */
    public static final String EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH =
            "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH";

    /**
     * The maximum allowed length of the requested text location data.
     */
    public static final int EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH = 20000;

    /**
     * Key used to request extra data for the rendering information.
     * The key requests that a {@link AccessibilityNodeInfo.ExtraRenderingInfo} be added to this
     * info. This request is made with {@link #refreshWithExtraData(String, Bundle)} without
     * argument.
     * <p>
     * The data can be retrieved from the {@link ExtraRenderingInfo} returned by
     * {@link #getExtraRenderingInfo()} using {@link ExtraRenderingInfo#getLayoutSize},
     * {@link ExtraRenderingInfo#getTextSizeInPx()} and
     * {@link ExtraRenderingInfo#getTextSizeUnit()}. For layout params, it is supported by both
     * {@link TextView} and {@link ViewGroup}. For text size and unit, it is only supported by
     * {@link TextView}.
     *
     * @see #refreshWithExtraData(String, Bundle)
     */
    public static final String EXTRA_DATA_RENDERING_INFO_KEY =
            "android.view.accessibility.extra.DATA_RENDERING_INFO_KEY";

    /** @hide */
    public static final String EXTRA_DATA_REQUESTED_KEY =
            "android.view.accessibility.AccessibilityNodeInfo.extra_data_requested";

    // Boolean attributes.

    private static final int BOOLEAN_PROPERTY_CHECKABLE = 1 /* << 0 */;

    private static final int BOOLEAN_PROPERTY_CHECKED = 1 << 1;

    private static final int BOOLEAN_PROPERTY_FOCUSABLE = 1 << 2;

    private static final int BOOLEAN_PROPERTY_FOCUSED = 1 << 3;

    private static final int BOOLEAN_PROPERTY_SELECTED = 1 << 4;

    private static final int BOOLEAN_PROPERTY_CLICKABLE = 1 << 5;

    private static final int BOOLEAN_PROPERTY_LONG_CLICKABLE = 1 << 6;

    private static final int BOOLEAN_PROPERTY_ENABLED = 1 << 7;

    private static final int BOOLEAN_PROPERTY_PASSWORD = 1 << 8;

    private static final int BOOLEAN_PROPERTY_SCROLLABLE = 1 << 9;

    private static final int BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED = 1 << 10;

    private static final int BOOLEAN_PROPERTY_VISIBLE_TO_USER = 1 << 11;

    private static final int BOOLEAN_PROPERTY_EDITABLE = 1 << 12;

    private static final int BOOLEAN_PROPERTY_OPENS_POPUP = 1 << 13;

    private static final int BOOLEAN_PROPERTY_DISMISSABLE = 1 << 14;

    private static final int BOOLEAN_PROPERTY_MULTI_LINE = 1 << 15;

    private static final int BOOLEAN_PROPERTY_CONTENT_INVALID = 1 << 16;

    private static final int BOOLEAN_PROPERTY_CONTEXT_CLICKABLE = 1 << 17;

    private static final int BOOLEAN_PROPERTY_IMPORTANCE = 1 << 18;

    private static final int BOOLEAN_PROPERTY_SCREEN_READER_FOCUSABLE = 1 << 19;

    private static final int BOOLEAN_PROPERTY_IS_SHOWING_HINT = 1 << 20;

    private static final int BOOLEAN_PROPERTY_IS_HEADING = 1 << 21;

    private static final int BOOLEAN_PROPERTY_IS_TEXT_ENTRY_KEY = 1 << 22;

    private static final int BOOLEAN_PROPERTY_IS_TEXT_SELECTABLE = 1 << 23;

    private static final int BOOLEAN_PROPERTY_REQUEST_INITIAL_ACCESSIBILITY_FOCUS = 1 << 24;

    private static final int BOOLEAN_PROPERTY_ACCESSIBILITY_DATA_SENSITIVE = 1 << 25;

    private static final int BOOLEAN_PROPERTY_SUPPORTS_GRANULAR_SCROLLING = 1 << 26;

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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

    private static final AccessibilityNodeInfo DEFAULT = new AccessibilityNodeInfo();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean mSealed;

    // Data.
    private int mWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    @UnsupportedAppUsage
    private long mSourceNodeId = UNDEFINED_NODE_ID;
    private long mParentNodeId = UNDEFINED_NODE_ID;
    private long mLabelForId = UNDEFINED_NODE_ID;
    private long mLabeledById = UNDEFINED_NODE_ID;
    private LongArray mLabeledByIds;
    private long mTraversalBefore = UNDEFINED_NODE_ID;
    private long mTraversalAfter = UNDEFINED_NODE_ID;

    private long mMinDurationBetweenContentChanges = 0;

    private int mBooleanProperties;
    private final Rect mBoundsInParent = new Rect();
    private final Rect mBoundsInScreen = new Rect();
    private final Rect mBoundsInWindow = new Rect();
    private int mDrawingOrderInParent;

    private CharSequence mPackageName;
    private CharSequence mClassName;
    // Hidden, unparceled value used to hold the original value passed to setText
    private CharSequence mOriginalText;
    private CharSequence mText;
    private CharSequence mHintText;
    private CharSequence mError;
    private CharSequence mPaneTitle;
    private CharSequence mStateDescription;
    private CharSequence mContentDescription;
    private CharSequence mTooltipText;
    private String mViewIdResourceName;
    private String mUniqueId;
    private CharSequence mContainerTitle;
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

    private ExtraRenderingInfo mExtraRenderingInfo;

    private IBinder mLeashedChild;
    private IBinder mLeashedParent;
    private long mLeashedParentNodeId = UNDEFINED_NODE_ID;

    /**
     * Creates a new {@link AccessibilityNodeInfo}.
     */
    public AccessibilityNodeInfo() {
    }

    /**
     * Creates a new {@link AccessibilityNodeInfo} with the given <code>source</code>.
     *
     * @param source The source view.
     */
    public AccessibilityNodeInfo(@NonNull View source) {
        setSource(source);
    }

    /**
     * Creates a new {@link AccessibilityNodeInfo} with the given <code>source</code>.
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public AccessibilityNodeInfo(@NonNull View root, int virtualDescendantId) {
        setSource(root, virtualDescendantId);
    }

    /**
     * Copy constructor. Creates a new {@link AccessibilityNodeInfo}, and this new instance is
     * initialized from the given <code>info</code>.
     *
     * @param info The other info.
     */
    public AccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
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
     * <p>
     * <strong>Note:</strong> If this view hierarchy has a {@link SurfaceView} embedding another
     * view hierarchy via {@link SurfaceView#setChildSurfacePackage}, there is a limitation that
     * this API won't be able to find the node for the view on the embedded view hierarchy. It's
     * because views don't know about the embedded hierarchies. Instead, you could traverse all
     * the children to find the node. Or, use {@link AccessibilityService#findFocus(int)} for
     * {@link #FOCUS_ACCESSIBILITY} only since it has no such limitation.
     * </p>
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
     * <p>
     * <strong>Note:</strong> If this view hierarchy has a {@link SurfaceView} embedding another
     * view hierarchy via {@link SurfaceView#setChildSurfacePackage}, there is a limitation that
     * this API won't be able to find the node for the view in the specified direction on the
     * embedded view hierarchy. It's because views don't know about the embedded hierarchies.
     * Instead, you could traverse all the children to find the node.
     * </p>
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
     *
     * @param bypassCache Whether to bypass the cache.
     * @return Whether the refresh succeeded.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        init(refreshedInfo);
        return true;
    }

    /**
     * Refreshes this info with the latest state of the view it represents.
     *
     * @return {@code true} if the refresh succeeded. {@code false} if the {@link View} represented
     * by this node is no longer in the view tree (and thus this node is obsolete).
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
     *                     {@link #EXTRA_DATA_RENDERING_INFO_KEY},
     *                     {@link #EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY},
     *                     {@link #getAvailableExtraData()} and {@link #getExtraRenderingInfo()}.
     * @param args A bundle of arguments for the request. These depend on the particular request.
     *
     * @return {@code true} if the refresh succeeded. {@code false} if the {@link View} represented
     * by this node is no longer in the view tree (and thus this node is obsolete).
     */
    public boolean refreshWithExtraData(String extraDataKey, Bundle args) {
        // limits the text location length to make sure the rectangle array allocation avoids
        // the binder transaction failure and OOM crash.
        if (args.getInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1)
                > EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH) {
            args.putInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                    EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH);
        }

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
     *
     * @param index The child index.
     * @return The child node.
     *
     * @throws IllegalStateException If called outside of an {@link AccessibilityService} and before
     *                               calling {@link #setQueryFromAppProcessEnabled}.
     */
    public AccessibilityNodeInfo getChild(int index) {
        return getChild(index, FLAG_PREFETCH_DESCENDANTS_HYBRID);
    }


    /**
     * Get the child at given index.
     *
     * <p>
     * See {@link #getParent(int)} for a description of prefetching.
     * @param index The child index.
     * @param prefetchingStrategy the prefetching strategy.
     * @return The child node.
     *
     * @throws IllegalStateException If called outside of an {@link AccessibilityService} and before
     *                               calling {@link #setQueryFromAppProcessEnabled}.
     *
     */
    @Nullable
    public AccessibilityNodeInfo getChild(int index, @PrefetchingStrategy int prefetchingStrategy) {
        enforceSealed();
        if (mChildNodeIds == null) {
            return null;
        }
        if (!canPerformRequestOverConnection(mConnectionId, mWindowId, mSourceNodeId)) {
            return null;
        }
        final long childId = mChildNodeIds.get(index);
        final AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        if (mLeashedChild != null && childId == LEASHED_NODE_ID) {
            return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mLeashedChild,
                    ROOT_NODE_ID, false, prefetchingStrategy, null);
        }

        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mWindowId,
                childId, false, prefetchingStrategy, null);
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
     * Adds a view root from leashed content as a child. This method is used to embedded another
     * view hierarchy.
     * <p>
     * <strong>Note:</strong> Only one leashed child is permitted.
     * </p>
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * Note that a view cannot be made its own child.
     * </p>
     *
     * @param token The token to which a view root is added.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     * @hide
     */
    @TestApi
    public void addChild(@NonNull IBinder token) {
        enforceNotSealed();
        if (token == null) {
            return;
        }
        if (mChildNodeIds == null) {
            mChildNodeIds = new LongArray();
        }

        mLeashedChild = token;
        // Checking uniqueness.
        // Since only one leashed child is permitted, skip adding ID if the ID already exists.
        if (mChildNodeIds.indexOf(LEASHED_NODE_ID) >= 0) {
            return;
        }
        mChildNodeIds.add(LEASHED_NODE_ID);
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
     * Removes a leashed child. If the child was not previously added to the node,
     * calling this method has no effect.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param token The token of the leashed child
     * @return true if the child was present
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     * @hide
     */
    public boolean removeChild(IBinder token) {
        enforceNotSealed();
        if (mChildNodeIds == null || mLeashedChild == null) {
            return false;
        }
        if (!mLeashedChild.equals(token)) {
            return false;
        }
        final int index = mChildNodeIds.indexOf(LEASHED_NODE_ID);
        mLeashedChild = null;
        if (index < 0) {
            return false;
        }
        mChildNodeIds.remove(index);
        return true;
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
     * Use {@link androidx.core.view.ViewCompat#addAccessibilityAction(View, CharSequence,
     * AccessibilityViewCommand)} to register an action directly on the view.
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

        if ((action & INVALID_ACTIONS_MASK) != 0) {
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
     * @see #EXTRA_DATA_RENDERING_INFO_KEY
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
     * Sets the minimum time duration between two content change events, which is used in throttling
     * content change events in accessibility services.
     *
     * <p>
     * Example: An app can set MinMillisBetweenContentChanges as 1 min for a view which sends
     * content change events to accessibility services one event per second.
     * Accessibility service will throttle those content change events and only handle one event
     * per minute for that view.
     * </p>
     * <p>
     * Example UI elements that frequently update and may benefit from a duration are progress bars,
     * timers, and stopwatches.
     * </p>
     *
     * @see AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED
     * @see AccessibilityEvent#getContentChangeTypes
     * @param duration the minimum duration between content change events.
     *                                         Negative duration would be treated as zero.
     */
    public void setMinDurationBetweenContentChanges(@NonNull Duration duration) {
        enforceNotSealed();
        mMinDurationBetweenContentChanges = duration.toMillis();
    }

    /**
     * Gets the minimum time duration between two content change events.
     */
    @NonNull
    public Duration getMinDurationBetweenContentChanges() {
        return Duration.ofMillis(mMinDurationBetweenContentChanges);
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
        Bundle arguments = null;
        if (mExtras != null) {
            arguments = mExtras;
        }
        return client.performAccessibilityAction(mConnectionId, mWindowId, mSourceNodeId,
                action, arguments);
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
     * <strong>Note:</strong> If this view hierarchy has a {@link SurfaceView} embedding another
     * view hierarchy via {@link SurfaceView#setChildSurfacePackage}, there is a limitation that
     * this API won't be able to find the node for the view on the embedded view hierarchy. It's
     * because views don't know about the embedded hierarchies. Instead, you could traverse all
     * the children to find the node.
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
     *   <strong>Note:</strong> The primary usage of this API is for UI test automation
     *   and in order to report the fully qualified view id if an {@link AccessibilityNodeInfo}
     *   the client has to set the {@link AccessibilityServiceInfo#FLAG_REPORT_VIEW_IDS}
     *   flag when configuring the {@link android.accessibilityservice.AccessibilityService}.
     * </p>
     * <p>
     * <strong>Note:</strong> If this view hierarchy has a {@link SurfaceView} embedding another
     * view hierarchy via {@link SurfaceView#setChildSurfacePackage}, there is a limitation that
     * this API won't be able to find the node for the view on the embedded view hierarchy. It's
     * because views don't know about the embedded hierarchies. Instead, you could traverse all
     * the children to find the node.
     * </p>
     *
     * @param viewId The fully qualified resource name of the view id to find.
     * @return A list of node info.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(@NonNull String viewId) {
        enforceSealed();
        if (viewId == null) {
            Log.e(TAG, "returns empty list due to null viewId.");
            return Collections.emptyList();
        }
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
     *
     * @return The parent.
     *
     * @throws IllegalStateException If called outside of an {@link AccessibilityService} and before
     *                               calling {@link #setQueryFromAppProcessEnabled}.
     */
    public AccessibilityNodeInfo getParent() {
        enforceSealed();
        if (mLeashedParent != null && mLeashedParentNodeId != UNDEFINED_NODE_ID) {
            return getNodeForAccessibilityId(mConnectionId, mLeashedParent, mLeashedParentNodeId,
                    FLAG_PREFETCH_ANCESTORS | FLAG_PREFETCH_SIBLINGS);
        }
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mParentNodeId);
    }

    /**
     * Gets the parent.
     *
     * <p>
     * Use {@code prefetchingStrategy} to determine the types of
     * nodes prefetched from the app if the requested node is not in the cache and must be retrieved
     * by the app. The default strategy for {@link #getParent()} is a combination of ancestor and
     * sibling strategies. The app will prefetch until all nodes fulfilling the strategies are
     * fetched, another node request is sent, or the maximum prefetch batch size of
     * {@link #MAX_NUMBER_OF_PREFETCHED_NODES} nodes is reached. To prevent interruption by another
     * request and to force prefetching of the max batch size, use
     * {@link AccessibilityNodeInfo#FLAG_PREFETCH_UNINTERRUPTIBLE}.
     * </p>
     *
     * @param prefetchingStrategy the prefetching strategy.
     * @return The parent.
     *
     * @throws IllegalStateException If called outside of an {@link AccessibilityService} and before
     *                               calling {@link #setQueryFromAppProcessEnabled}.
     *
     * @see #FLAG_PREFETCH_ANCESTORS
     * @see #FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST
     * @see #FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
     * @see #FLAG_PREFETCH_DESCENDANTS_HYBRID
     * @see #FLAG_PREFETCH_SIBLINGS
     * @see #FLAG_PREFETCH_UNINTERRUPTIBLE
     */
    @Nullable
    public AccessibilityNodeInfo getParent(@PrefetchingStrategy int prefetchingStrategy) {
        enforceSealed();
        if (mLeashedParent != null && mLeashedParentNodeId != UNDEFINED_NODE_ID) {
            return getNodeForAccessibilityId(mConnectionId, mLeashedParent, mLeashedParentNodeId,
                    prefetchingStrategy);
        }
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mParentNodeId,
                prefetchingStrategy);
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
     * <p>
     * When magnification is enabled, the bounds in parent are also scaled up by magnification
     * scale. For example, it returns Rect(20, 20, 200, 200) for original bounds
     * Rect(10, 10, 100, 100), when the magnification scale is 2.
     * <p/>
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
     * <p>
     * When magnification is enabled, the bounds in screen are scaled up by magnification scale
     * and the positions are also adjusted according to the offset of magnification viewport.
     * For example, it returns Rect(-180, -180, 0, 0) for original bounds Rect(10, 10, 100, 100),
     * when the magnification scale is 2 and offsets for X and Y are both 200.
     * <p/>
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
     * Gets the node bounds in window coordinates.
     * <p>
     * When magnification is enabled, the bounds in window are scaled up by magnification scale
     * and the positions are also adjusted according to the offset of magnification viewport.
     * For example, it returns Rect(-180, -180, 0, 0) for original bounds Rect(10, 10, 100, 100),
     * when the magnification scale is 2 and offsets for X and Y are both 200.
     * <p/>
     *
     * @param outBounds The output node bounds.
     */
    public void getBoundsInWindow(@NonNull Rect outBounds) {
        outBounds.set(mBoundsInWindow.left, mBoundsInWindow.top,
                mBoundsInWindow.right, mBoundsInWindow.bottom);
    }

    /**
     * Returns the actual rect containing the node bounds in window coordinates.
     *
     * @hide Not safe to expose outside the framework.
     */
    @NonNull
    public Rect getBoundsInWindow() {
        return mBoundsInWindow;
    }

    /**
     * Sets the node bounds in window coordinates.
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
    public void setBoundsInWindow(@NonNull Rect bounds) {
        enforceNotSealed();
        mBoundsInWindow.set(bounds);
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
     * <p>In the View system, this typically maps to {@link View#isFocusable()}.
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
     * <p>To mark a node as explicitly focusable for a screen reader, consider using
     * {@link #setScreenReaderFocusable(boolean)} instead.
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
     * <p>This is distinct from {@link #isAccessibilityFocused()}, which is used by screen readers.
     * See {@link AccessibilityAction#ACTION_ACCESSIBILITY_FOCUS} for details.
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
     * <p>
     * Between {@link Build.VERSION_CODES#JELLY_BEAN API 16} and
     * {@link Build.VERSION_CODES#Q API 29}, this method may incorrectly return false when
     * magnification is enabled. On other versions, a node is considered visible even if it is not
     * on the screen because magnification is active.
     * </p>
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
     * <p>This is distinct from {@link #isFocused()}, which is used to track system focus.
     * See {@link #ACTION_ACCESSIBILITY_FOCUS} for details.
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
     * <p>The UI element updating this property should send an event of
     * {@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUSED}
     * or {@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED} if its
     * accessibility-focused state changes.
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
     * Gets if the node supports granular scrolling.
     *
     * @return True if all scroll actions that could support
     * {@link #ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT} have done so, false otherwise.
     */
    @FlaggedApi(Flags.FLAG_GRANULAR_SCROLLING)
    public boolean isGranularScrollingSupported() {
        return getBooleanProperty(BOOLEAN_PROPERTY_SUPPORTS_GRANULAR_SCROLLING);
    }

    /**
     * Sets if the node supports granular scrolling. This should be set to true if all scroll
     * actions which could support {@link #ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT} have done so.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param granularScrollingSupported True if the node supports granular scrolling, false
     *                                  otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    @FlaggedApi(Flags.FLAG_GRANULAR_SCROLLING)
    public void setGranularScrollingSupported(boolean granularScrollingSupported) {
        setBooleanProperty(BOOLEAN_PROPERTY_SUPPORTS_GRANULAR_SCROLLING,
                granularScrollingSupported);
    }

    /**
     * Gets if the node has selectable text.
     *
     * <p>
     *     Services should use {@link #ACTION_SET_SELECTION} for selection. Editable text nodes must
     *     also be selectable. But not all UIs will populate this field, so services should consider
     *     'isTextSelectable | isEditable' to ensure they don't miss nodes with selectable text.
     * </p>
     *
     * @see #isEditable
     * @return True if the node has selectable text.
     */
    public boolean isTextSelectable() {
        return getBooleanProperty(BOOLEAN_PROPERTY_IS_TEXT_SELECTABLE);
    }

    /**
     * Sets if the node has selectable text.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param selectableText True if the node has selectable text, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setTextSelectable(boolean selectableText) {
        setBooleanProperty(BOOLEAN_PROPERTY_IS_TEXT_SELECTABLE, selectableText);
    }

    /**
     * Gets whether the node has {@link #setRequestInitialAccessibilityFocus}.
     *
     * @return True if the node has requested initial accessibility focus.
     */
    public boolean hasRequestInitialAccessibilityFocus() {
        return getBooleanProperty(BOOLEAN_PROPERTY_REQUEST_INITIAL_ACCESSIBILITY_FOCUS);
    }

    /**
     * Sets whether the node has requested initial accessibility focus.
     *
     * <p>
     * If the node {@link #hasRequestInitialAccessibilityFocus}, this node would be one of
     * candidates to be accessibility focused when the window appears.
     * </p>
     *
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param requestInitialAccessibilityFocus True if the node requests to receive initial
     *                                         accessibility focus.
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setRequestInitialAccessibilityFocus(boolean requestInitialAccessibilityFocus) {
        setBooleanProperty(BOOLEAN_PROPERTY_REQUEST_INITIAL_ACCESSIBILITY_FOCUS,
                requestInitialAccessibilityFocus);
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
     * Gets if the node's accessibility data is considered sensitive.
     *
     * @return True if the node's data is considered sensitive, false otherwise.
     * @see View#isAccessibilityDataSensitive()
     */
    public boolean isAccessibilityDataSensitive() {
        return getBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_DATA_SENSITIVE);
    }

    /**
     * Sets whether this node's accessibility data is considered sensitive.
     *
     * <p>
     * <strong>Note:</strong> Cannot be called from an {@link AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param accessibilityDataSensitive True if the node's accessibility data is considered
     *                                   sensitive.
     * @throws IllegalStateException If called from an AccessibilityService.
     * @see View#setAccessibilityDataSensitive
     */
    public void setAccessibilityDataSensitive(boolean accessibilityDataSensitive) {
        setBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_DATA_SENSITIVE,
                accessibilityDataSensitive);
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
     * Gets the {@link ExtraRenderingInfo extra rendering info} if the node is meant to be
     * refreshed with extra data to examine rendering related accessibility issues.
     *
     * @return The {@link ExtraRenderingInfo extra rendering info}.
     *
     * @see #EXTRA_DATA_RENDERING_INFO_KEY
     * @see #refreshWithExtraData(String, Bundle)
     */
    @Nullable
    public ExtraRenderingInfo getExtraRenderingInfo() {
        return mExtraRenderingInfo;
    }

    /**
     * Sets the extra rendering info, <code>extraRenderingInfo<code/>, if the node is meant to be
     * refreshed with extra data.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param extraRenderingInfo The {@link ExtraRenderingInfo extra rendering info}.
     * @hide
     */
    public void setExtraRenderingInfo(@NonNull ExtraRenderingInfo extraRenderingInfo) {
        enforceNotSealed();
        mExtraRenderingInfo = extraRenderingInfo;
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
     * a Snackbar that displays a confirmation notification should be marked
     * as a live region with mode
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
     * <p>This can be used to
     * <a href="{@docRoot}guide/topics/ui/accessibility/principles#content-groups">group related
     * content.</a>
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
        if (text instanceof Spanned) {
            CharSequence tmpText = text;
            tmpText = replaceClickableSpan(tmpText);
            tmpText = replaceReplacementSpan(tmpText);
            mText = tmpText;
            return;
        }
        mText = (text == null) ? null : text.subSequence(0, text.length());
    }

    /**
     * Replaces any ClickableSpan in the given {@code text} with placeholders.
     *
     * @param text The text.
     *
     * @return The spannable with ClickableSpan replacement.
     */
    private CharSequence replaceClickableSpan(CharSequence text) {
        ClickableSpan[] clickableSpans =
                ((Spanned) text).getSpans(0, text.length(), ClickableSpan.class);
        Spannable spannable = new SpannableStringBuilder(text);
        if (clickableSpans.length == 0) {
            return text;
        }
        for (int i = 0; i < clickableSpans.length; i++) {
            ClickableSpan span = clickableSpans[i];
            if ((span instanceof AccessibilityClickableSpan)
                    || (span instanceof AccessibilityURLSpan)) {
                // We've already done enough
                break;
            }
            int spanToReplaceStart = spannable.getSpanStart(span);
            int spanToReplaceEnd = spannable.getSpanEnd(span);
            int spanToReplaceFlags = spannable.getSpanFlags(span);
            if (spanToReplaceStart < 0) {
                continue;
            }
            spannable.removeSpan(span);
            ClickableSpan replacementSpan = (span instanceof URLSpan)
                    ? new AccessibilityURLSpan((URLSpan) span)
                    : new AccessibilityClickableSpan(span.getId());
            spannable.setSpan(replacementSpan, spanToReplaceStart, spanToReplaceEnd,
                    spanToReplaceFlags);
        }
        return spannable;
    }

    /**
     * Replaces any ReplacementSpan in the given {@code text} if the object has content description.
     *
     * @param text The text.
     *
     * @return The spannable with ReplacementSpan replacement.
     */
    private CharSequence replaceReplacementSpan(CharSequence text) {
        ReplacementSpan[] replacementSpans =
                ((Spanned) text).getSpans(0, text.length(), ReplacementSpan.class);
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        if (replacementSpans.length == 0) {
            return text;
        }
        for (int i = 0; i < replacementSpans.length; i++) {
            ReplacementSpan span = replacementSpans[i];
            CharSequence replacementText = span.getContentDescription();
            if (span instanceof AccessibilityReplacementSpan) {
                // We've already done enough
                break;
            }
            if (replacementText == null) {
                continue;
            }
            int spanToReplaceStart = spannable.getSpanStart(span);
            int spanToReplaceEnd = spannable.getSpanEnd(span);
            int spanToReplaceFlags = spannable.getSpanFlags(span);
            if (spanToReplaceStart < 0) {
                continue;
            }
            spannable.removeSpan(span);
            ReplacementSpan replacementSpan = new AccessibilityReplacementSpan(replacementText);
            spannable.setSpan(replacementSpan, spanToReplaceStart, spanToReplaceEnd,
                    spanToReplaceFlags);
        }
        return spannable;
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
     * Get the state description of this node.
     *
     * @return the state description
     */
    public @Nullable CharSequence getStateDescription() {
        return mStateDescription;
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
     * Sets the state description of this node.
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param stateDescription the state description of this node.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setStateDescription(@Nullable CharSequence stateDescription) {
        enforceNotSealed();
        mStateDescription = (stateDescription == null) ? null
                : stateDescription.subSequence(0, stateDescription.length());
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
     *
     * @return The labeled info.
     */
    public AccessibilityNodeInfo getLabelFor() {
        enforceSealed();
        return getNodeForAccessibilityId(mConnectionId, mWindowId, mLabelForId);
    }

    /**
     * Adds the view which serves as the label of the view represented by
     * this info for accessibility purposes. When multiple labels are
     * added, the content from each label is combined in the order that
     * they are added.
     * <p>
     * If visible text can be used to describe or give meaning to this UI,
     * this method is preferred. For example, a TextView before an EditText
     * in the UI usually specifies what information is contained in the
     * EditText. Hence, the EditText is labeled by the TextView.
     * </p>
     *
     * @param label A view that labels this node's source.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_MULTIPLE_LABELEDBY)
    public void addLabeledBy(@NonNull View label) {
        addLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
    }

    /**
     * Adds the view which serves as the label of the view represented by
     * this info for accessibility purposes. If <code>virtualDescendantId</code>
     * is {@link View#NO_ID} the root is set as the label. When multiple
     * labels are added, the content from each label is combined in the order
     * that they are added.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report themselves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     * <p>
     * If visible text can be used to describe or give meaning to this UI,
     * this method is preferred. For example, a TextView before an EditText
     * in the UI usually specifies what information is contained in the
     * EditText. Hence, the EditText is labeled by the TextView.
     * </p>
     * <p>
     *   <strong>Note:</strong> Cannot be called from an
     *   {@link android.accessibilityservice.AccessibilityService}.
     *   This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param root A root whose virtual descendant labels this node's source.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_MULTIPLE_LABELEDBY)
    public void addLabeledBy(@NonNull View root, int virtualDescendantId) {
        enforceNotSealed();
        Preconditions.checkNotNull(root, "%s must not be null", root);
        if (mLabeledByIds == null) {
            mLabeledByIds = new LongArray();
        }
        mLabeledById = makeNodeId(root.getAccessibilityViewId(), virtualDescendantId);
        mLabeledByIds.add(mLabeledById);
    }

    /**
     * Gets the list of node infos which serve as the labels of the view represented by
     * this info for accessibility purposes.
     *
     * @return The list of labels in the order that they were added.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_MULTIPLE_LABELEDBY)
    public @NonNull List<AccessibilityNodeInfo> getLabeledByList() {
        enforceSealed();
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        if (mLabeledByIds == null) {
            return labels;
        }
        for (int i = 0; i < mLabeledByIds.size(); i++) {
            labels.add(getNodeForAccessibilityId(mConnectionId, mWindowId, mLabeledByIds.get(i)));
        }
        return labels;
    }

    /**
     * Removes a label. If the label was not previously added to the node,
     * calling this method has no effect.
     * <p>
     * <strong>Note:</strong> Cannot be called from an
     * {@link android.accessibilityservice.AccessibilityService}.
     * This class is made immutable before being delivered to an AccessibilityService.
     * </p>
     *
     * @param label The node which serves as this node's label.
     * @return true if the label was present
     * @see #addLabeledBy(View)
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_MULTIPLE_LABELEDBY)
    public boolean removeLabeledBy(@NonNull View label) {
        return removeLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
    }

    /**
     * Removes a label which is a virtual descendant of the given
     * <code>root</code>. If <code>virtualDescendantId</code> is
     * {@link View#NO_ID} the root is set as the label. If the label
     * was not previously added to the node, calling this method has
     * no effect.
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual node which serves as this node's label.
     * @return true if the label was present
     * @see #addLabeledBy(View, int)
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_MULTIPLE_LABELEDBY)
    public boolean removeLabeledBy(@NonNull View root, int virtualDescendantId) {
        enforceNotSealed();
        final LongArray labeledByIds = mLabeledByIds;
        if (labeledByIds == null) {
            return false;
        }
        final int rootAccessibilityViewId =
                (root != null) ? root.getAccessibilityViewId() : UNDEFINED_ITEM_ID;
        final long labeledById = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
        if (mLabeledById == labeledById) {
            mLabeledById = UNDEFINED_NODE_ID;
        }
        final int index = labeledByIds.indexOf(labeledById);
        if (index < 0) {
            return false;
        }
        labeledByIds.remove(index);
        return true;
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
        if (Flags.supportMultipleLabeledby()) {
            if (mLabeledByIds == null) {
                mLabeledByIds = new LongArray();
            } else {
                mLabeledByIds.clear();
            }
        }
        mLabeledById = makeNodeId(rootAccessibilityViewId, virtualDescendantId);
        if (Flags.supportMultipleLabeledby()) {
            mLabeledByIds.add(mLabeledById);
        }
    }

    /**
     * Gets the node info which serves as the label of the view represented by
     * this info for accessibility purposes.
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
     *   flag when configuring the {@link android.accessibilityservice.AccessibilityService}.
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
    @TestApi
    public long getSourceNodeId() {
        return mSourceNodeId;
    }

    /**
     * Sets the unique id to act as a key to identify the node. If the node instance is replaced
     * after refreshing the layout, calling this API to assign the same unique id to the new
     * alike node can help accessibility service to identify it.
     *
     * @param uniqueId The unique id that is associated with a visible node on the screen
     */
    public void setUniqueId(@Nullable String uniqueId) {
        enforceNotSealed();
        mUniqueId = uniqueId;
    }

    /**
     * Gets the unique id of the node.
     *
     * @return The unique id
     */
    @Nullable
    public String getUniqueId() {
        return mUniqueId;
    }

    /**
     * Sets the container title for app-developer-defined container which can be any type of
     * ViewGroup or layout.
     * Container title will be used to group together related controls, similar to HTML fieldset.
     * Or container title may identify a large piece of the UI that is visibly grouped together,
     * such as a toolbar or a card, etc.
     * <p>
     * Container title helps to assist in navigation across containers and other groups.
     * For example, a screen reader may use this to determine where to put accessibility focus.
     * </p>
     * <p>
     * Container title is different from pane title{@link #setPaneTitle} which indicates that the
     * node represents a window or activity.
     * </p>
     *
     * <p>
     *  Example: An app can set container titles on several non-modal menus, containing TextViews
     *  or ImageButtons that have content descriptions, text, etc. Screen readers can quickly
     *  switch accessibility focus among menus instead of child views.  Other accessibility-services
     *  can easily find the menu.
     * </p>
     *
     * @param containerTitle The container title that is associated with a ViewGroup/Layout on the
     *                       screen.
     */
    public void setContainerTitle(@Nullable CharSequence containerTitle) {
        enforceNotSealed();
        mContainerTitle = (containerTitle == null) ? null
                : containerTitle.subSequence(0, containerTitle.length());
    }

    /**
     * Returns the container title.
     *
     * @see #setContainerTitle
     */
    @Nullable
    public CharSequence getContainerTitle() {
        return mContainerTitle;
    }

    /**
     * Sets the token and node id of the leashed parent.
     *
     * @param token The token.
     * @param viewId The accessibility view id.
     * @hide
     */
    @TestApi
    public void setLeashedParent(@Nullable IBinder token, int viewId) {
        enforceNotSealed();
        mLeashedParent = token;
        mLeashedParentNodeId = makeNodeId(viewId, AccessibilityNodeProvider.HOST_VIEW_ID);
    }

    /**
     * Gets the token of the leashed parent.
     *
     * @return The token.
     * @hide
     */
    public @Nullable IBinder getLeashedParent() {
        return mLeashedParent;
    }

    /**
     * Gets the node id of the leashed parent.
     *
     * @return The accessibility node id.
     * @hide
     */
    public long getLeashedParentNodeId() {
        return mLeashedParentNodeId;
    }

    /**
     * Connects this node to the View's root so that operations on this node can query the entire
     * {@link AccessibilityNodeInfo} tree and perform accessibility actions on nodes.
     *
     * <p>
     * Testing or debugging tools should create this {@link AccessibilityNodeInfo} node using
     * {@link View#createAccessibilityNodeInfo()} or {@link AccessibilityNodeProvider} and call this
     * method, then navigate and interact with the node tree by calling methods on the node.
     * Calling this method more than once on the same node is a no-op. After calling this method,
     * all nodes linked to this node (children, ancestors, etc.) are also queryable.
     * </p>
     *
     * <p>
     * Here "query" refers to the following node operations:
     * <li>check properties of this node (example: {@link #isScrollable()})</li>
     * <li>find and query children (example: {@link #getChild(int)})</li>
     * <li>find and query the parent (example: {@link #getParent()})</li>
     * <li>find focus (examples: {@link #findFocus(int)}, {@link #focusSearch(int)})</li>
     * <li>find and query other nodes (example: {@link #findAccessibilityNodeInfosByText(String)},
     * {@link #findAccessibilityNodeInfosByViewId(String)})</li>
     * <li>perform actions (example: {@link #performAction(int)})</li>
     * </p>
     *
     * <p>
     * This is intended for short-lived inspections from testing or debugging tools in the app
     * process, as operations on this node tree will only succeed as long as the associated
     * view hierarchy remains attached to a window. {@link AccessibilityNodeInfo} objects can
     * quickly become out of sync with their corresponding {@link View} objects; if you wish to
     * inspect a changed or different view hierarchy then create a new node from any view in that
     * hierarchy and call this method on that new node, instead of disabling & re-enabling the
     * connection on the previous node.
     * </p>
     *
     * @param view The view that generated this node, or any view in the same view-root hierarchy.
     * @param enabled Whether to enable (true) or disable (false) querying from the app process.
     * @throws IllegalStateException If called from an {@link AccessibilityService}, or if provided
     *                               a {@link View} that is not attached to a window.
     */
    public void setQueryFromAppProcessEnabled(@NonNull View view, boolean enabled) {
        enforceNotSealed();

        if (!enabled) {
            setConnectionId(UNDEFINED_CONNECTION_ID);
            return;
        }

        if (mConnectionId != UNDEFINED_CONNECTION_ID) {
            return;
        }

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        if (viewRootImpl == null) {
            throw new IllegalStateException(
                    "Cannot link a node to a view that is not attached to a window.");
        }
        setConnectionId(viewRootImpl.getDirectAccessibilityConnectionId());
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

    private static boolean usingDirectConnection(int connectionId) {
        return AccessibilityInteractionClient.getConnection(
                connectionId) instanceof DirectAccessibilityConnection;
    }

    /**
     * Enforces that this instance is sealed, unless using a {@link DirectAccessibilityConnection}
     * which allows queries while the node is not sealed.
     *
     * @throws IllegalStateException If this instance is not sealed.
     *
     * @hide
     */
    protected void enforceSealed() {
        if (!usingDirectConnection(mConnectionId) && !isSealed()) {
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
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityNodeInfo(View)} instead.
     * @param source The source view.
     * @return An instance.
     *
     * @see #setSource(View)
     */
    @Deprecated
    public static AccessibilityNodeInfo obtain(View source) {
        return new AccessibilityNodeInfo(source);
    }

    /**
     * Returns a cached instance if such is available otherwise a new one
     * and sets the source.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityNodeInfo(View, int)} instead.
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     * @return An instance.
     *
     * @see #setSource(View, int)
     */
    @Deprecated
    public static AccessibilityNodeInfo obtain(View root, int virtualDescendantId) {
        return new AccessibilityNodeInfo(root, virtualDescendantId);
    }

    /**
     * Instantiates a new AccessibilityNodeInfo.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityNodeInfo()} instead.
     * @return An instance.
     */
    @Deprecated
    public static AccessibilityNodeInfo obtain() {
        return new AccessibilityNodeInfo();
    }

    /**
     * Instantiates a new AccessibilityNodeInfo initialized from the given
     * <code>info</code>.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityNodeInfo(AccessibilityNodeInfo)} instead.
     * @param info The other info.
     * @return An instance.
     */
    @Deprecated
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo info) {
        return new AccessibilityNodeInfo(info);
    }

    /**
     * Would previously return an instance back to be reused.
     *
     * @deprecated Object pooling has been discontinued. Calling this function now will have
     * no effect.
     */
    @Deprecated
    public void recycle() {}

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
        if (!LongArray.elementsEqual(mLabeledByIds, DEFAULT.mLabeledByIds)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mTraversalBefore != DEFAULT.mTraversalBefore) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mTraversalAfter != DEFAULT.mTraversalAfter) nonDefaultFields |= bitAt(fieldIndex);
        fieldIndex++;
        if (mMinDurationBetweenContentChanges
                != DEFAULT.mMinDurationBetweenContentChanges) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
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
        if (!Objects.equals(mBoundsInWindow, DEFAULT.mBoundsInWindow)) {
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
        if (!Objects.equals(mStateDescription, DEFAULT.mStateDescription)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
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
        if (!Objects.equals(mContainerTitle, DEFAULT.mContainerTitle)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mViewIdResourceName, DEFAULT.mViewIdResourceName)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (!Objects.equals(mUniqueId, DEFAULT.mUniqueId)) {
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
        fieldIndex++;
        if (!Objects.equals(mExtraRenderingInfo, DEFAULT.mExtraRenderingInfo)) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mLeashedChild != DEFAULT.mLeashedChild) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mLeashedParent != DEFAULT.mLeashedParent) {
            nonDefaultFields |= bitAt(fieldIndex);
        }
        fieldIndex++;
        if (mLeashedParentNodeId != DEFAULT.mLeashedParentNodeId) {
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
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            final LongArray labeledByIds = mLabeledByIds;
            if (labeledByIds == null) {
                parcel.writeInt(0);
            } else {
                final int labeledByIdsSize = labeledByIds.size();
                parcel.writeInt(labeledByIdsSize);
                for (int i = 0; i < labeledByIdsSize; i++) {
                    parcel.writeLong(labeledByIds.get(i));
                }
            }
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mTraversalBefore);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeLong(mTraversalAfter);
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeLong(mMinDurationBetweenContentChanges);
        }

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
            parcel.writeInt(mBoundsInWindow.top);
            parcel.writeInt(mBoundsInWindow.bottom);
            parcel.writeInt(mBoundsInWindow.left);
            parcel.writeInt(mBoundsInWindow.right);
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
                        action.writeToParcel(parcel, flags);
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
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mStateDescription);
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeCharSequence(mContentDescription);
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mPaneTitle);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mTooltipText);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeCharSequence(mContainerTitle);

        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeString(mViewIdResourceName);
        if (isBitSet(nonDefaultFields, fieldIndex++)) parcel.writeString(mUniqueId);
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
            parcel.writeInt(mCollectionInfo.getItemCount());
            parcel.writeInt(mCollectionInfo.getImportantForAccessibilityItemCount());
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeString(mCollectionItemInfo.getRowTitle());
            parcel.writeInt(mCollectionItemInfo.getRowIndex());
            parcel.writeInt(mCollectionItemInfo.getRowSpan());
            parcel.writeString(mCollectionItemInfo.getColumnTitle());
            parcel.writeInt(mCollectionItemInfo.getColumnIndex());
            parcel.writeInt(mCollectionItemInfo.getColumnSpan());
            parcel.writeInt(mCollectionItemInfo.isHeading() ? 1 : 0);
            parcel.writeInt(mCollectionItemInfo.isSelected() ? 1 : 0);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mTouchDelegateInfo.writeToParcel(parcel, flags);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeValue(mExtraRenderingInfo.getLayoutSize());
            parcel.writeFloat(mExtraRenderingInfo.getTextSizeInPx());
            parcel.writeInt(mExtraRenderingInfo.getTextSizeUnit());
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeStrongBinder(mLeashedChild);
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeStrongBinder(mLeashedParent);
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            parcel.writeLong(mLeashedParentNodeId);
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
        mLabeledByIds = other.mLabeledByIds;
        mTraversalBefore = other.mTraversalBefore;
        mTraversalAfter = other.mTraversalAfter;
        mMinDurationBetweenContentChanges = other.mMinDurationBetweenContentChanges;
        mWindowId = other.mWindowId;
        mConnectionId = other.mConnectionId;
        mUniqueId = other.mUniqueId;
        mBoundsInParent.set(other.mBoundsInParent);
        mBoundsInScreen.set(other.mBoundsInScreen);
        mBoundsInWindow.set(other.mBoundsInWindow);
        mPackageName = other.mPackageName;
        mClassName = other.mClassName;
        mText = other.mText;
        mOriginalText = other.mOriginalText;
        mHintText = other.mHintText;
        mError = other.mError;
        mStateDescription = other.mStateDescription;
        mContentDescription = other.mContentDescription;
        mPaneTitle = other.mPaneTitle;
        mTooltipText = other.mTooltipText;
        mContainerTitle = other.mContainerTitle;
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

        initCopyInfos(other);

        final TouchDelegateInfo otherInfo = other.mTouchDelegateInfo;
        mTouchDelegateInfo = (otherInfo != null)
                ? new TouchDelegateInfo(otherInfo.mTargetMap, true) : null;

        mLeashedChild = other.mLeashedChild;
        mLeashedParent = other.mLeashedParent;
        mLeashedParentNodeId = other.mLeashedParentNodeId;
    }

    private void initCopyInfos(AccessibilityNodeInfo other) {
        RangeInfo ri = other.mRangeInfo;
        mRangeInfo = (ri == null) ? null
                : new RangeInfo(ri.mType, ri.mMin, ri.mMax, ri.mCurrent);
        CollectionInfo ci = other.mCollectionInfo;
        mCollectionInfo = (ci == null) ? null
                : new CollectionInfo(ci.mRowCount, ci.mColumnCount,
                        ci.mHierarchical, ci.mSelectionMode, ci.mItemCount,
                        ci.mImportantForAccessibilityItemCount);
        CollectionItemInfo cii = other.mCollectionItemInfo;
        CollectionItemInfo.Builder builder = new CollectionItemInfo.Builder();
        mCollectionItemInfo = (cii == null)  ? null
                : builder.setRowTitle(cii.mRowTitle).setRowIndex(cii.mRowIndex).setRowSpan(
                        cii.mRowSpan).setColumnTitle(cii.mColumnTitle).setColumnIndex(
                                cii.mColumnIndex).setColumnSpan(cii.mColumnSpan).setHeading(
                                        cii.mHeading).setSelected(cii.mSelected).build();
        ExtraRenderingInfo ti = other.mExtraRenderingInfo;
        mExtraRenderingInfo = (ti == null) ? null
                : new ExtraRenderingInfo(ti);
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
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            final int labeledByIdsSize = parcel.readInt();
            if (labeledByIdsSize <= 0) {
                mLabeledByIds = null;
            } else {
                mLabeledByIds = new LongArray(labeledByIdsSize);
                for (int i = 0; i < labeledByIdsSize; i++) {
                    final long labeledById = parcel.readLong();
                    mLabeledByIds.add(labeledById);
                }
            }
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTraversalBefore = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTraversalAfter = parcel.readLong();
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mMinDurationBetweenContentChanges = parcel.readLong();
        }

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
            mBoundsInWindow.top = parcel.readInt();
            mBoundsInWindow.bottom = parcel.readInt();
            mBoundsInWindow.left = parcel.readInt();
            mBoundsInWindow.right = parcel.readInt();
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            final long standardActions = parcel.readLong();
            addStandardActions(standardActions);
            final int nonStandardActionCount = parcel.readInt();
            for (int i = 0; i < nonStandardActionCount; i++) {
                final AccessibilityAction action =
                        AccessibilityAction.CREATOR.createFromParcel(parcel);
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
        if (isBitSet(nonDefaultFields, fieldIndex++)) mStateDescription = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mContentDescription = parcel.readCharSequence();
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) mPaneTitle = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mTooltipText = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mContainerTitle = parcel.readCharSequence();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mViewIdResourceName = parcel.readString();
        if (isBitSet(nonDefaultFields, fieldIndex++)) mUniqueId = parcel.readString();

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

        mRangeInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? new RangeInfo(
                        parcel.readInt(),
                        parcel.readFloat(),
                        parcel.readFloat(),
                        parcel.readFloat())
                : null;

        mCollectionInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? new CollectionInfo(
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt() == 1,
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt())
                : null;

        mCollectionItemInfo = isBitSet(nonDefaultFields, fieldIndex++)
                ? new CollectionItemInfo(
                        parcel.readString(),
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readString(),
                        parcel.readInt(),
                        parcel.readInt(),
                        parcel.readInt() == 1,
                        parcel.readInt() == 1)
                : null;

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mTouchDelegateInfo = TouchDelegateInfo.CREATOR.createFromParcel(parcel);
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mExtraRenderingInfo = new ExtraRenderingInfo(null);
            mExtraRenderingInfo.mLayoutSize = (Size) parcel.readValue(null);
            mExtraRenderingInfo.mTextSizeInPx = parcel.readFloat();
            mExtraRenderingInfo.mTextSizeUnit = parcel.readInt();
        }

        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mLeashedChild = parcel.readStrongBinder();
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mLeashedParent = parcel.readStrongBinder();
        }
        if (isBitSet(nonDefaultFields, fieldIndex++)) {
            mLeashedParentNodeId = parcel.readLong();
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
            case R.id.accessibilityActionPressAndHold:
                return "ACTION_PRESS_AND_HOLD";
            case R.id.accessibilityActionImeEnter:
                return "ACTION_IME_ENTER";
            case R.id.accessibilityActionDragStart:
                return "ACTION_DRAG";
            case R.id.accessibilityActionDragCancel:
                return "ACTION_CANCEL_DRAG";
            case R.id.accessibilityActionDragDrop:
                return "ACTION_DROP";
            default: {
                if (action == R.id.accessibilityActionShowTextSuggestions) {
                    return "ACTION_SHOW_TEXT_SUGGESTIONS";
                }
                if (action == R.id.accessibilityActionScrollInDirection) {
                    return "ACTION_SCROLL_IN_DIRECTION";
                }
                return "ACTION_UNKNOWN";
            }
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
        final boolean hasWindowId = windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        return ((usingDirectConnection(connectionId) || hasWindowId)
                && (getAccessibilityViewId(accessibilityNodeId) != UNDEFINED_ITEM_ID)
                && (connectionId != UNDEFINED_CONNECTION_ID));
    }

    @Override
    public boolean equals(@Nullable Object object) {
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
            builder.append("; sourceNodeId: 0x").append(Long.toHexString(mSourceNodeId));
            builder.append("; windowId: 0x").append(Long.toHexString(mWindowId));
            builder.append("; accessibilityViewId: 0x")
                    .append(Long.toHexString(getAccessibilityViewId(mSourceNodeId)));
            builder.append("; virtualDescendantId: 0x")
                    .append(Long.toHexString(getVirtualDescendantId(mSourceNodeId)));
            builder.append("; mParentNodeId: 0x").append(Long.toHexString(mParentNodeId));
            builder.append("; traversalBefore: 0x").append(Long.toHexString(mTraversalBefore));
            builder.append("; traversalAfter: 0x").append(Long.toHexString(mTraversalAfter));
            builder.append("; minDurationBetweenContentChanges: ")
                    .append(mMinDurationBetweenContentChanges);

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
                    builder.append("0x").append(Long.toHexString(childIds.get(i)));
                    if (i < count - 1) {
                        builder.append(", ");
                    }
                }
            }
            builder.append("]");
        }

        builder.append("; boundsInParent: ").append(mBoundsInParent);
        builder.append("; boundsInScreen: ").append(mBoundsInScreen);
        builder.append("; boundsInWindow: ").append(mBoundsInScreen);

        builder.append("; packageName: ").append(mPackageName);
        builder.append("; className: ").append(mClassName);
        builder.append("; text: ").append(mText);
        builder.append("; error: ").append(mError);
        builder.append("; maxTextLength: ").append(mMaxTextLength);
        builder.append("; stateDescription: ").append(mStateDescription);
        builder.append("; contentDescription: ").append(mContentDescription);
        builder.append("; tooltipText: ").append(mTooltipText);
        builder.append("; containerTitle: ").append(mContainerTitle);
        builder.append("; viewIdResName: ").append(mViewIdResourceName);
        builder.append("; uniqueId: ").append(mUniqueId);

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
        builder.append("; granularScrollingSupported: ").append(isGranularScrollingSupported());
        builder.append("; importantForAccessibility: ").append(isImportantForAccessibility());
        builder.append("; visible: ").append(isVisibleToUser());
        builder.append("; actions: ").append(mActions);
        builder.append("; isTextSelectable: ").append(isTextSelectable());

        return builder.toString();
    }

    private static AccessibilityNodeInfo getNodeForAccessibilityId(int connectionId,
            int windowId, long accessibilityId) {
        return getNodeForAccessibilityId(connectionId, windowId, accessibilityId,
                FLAG_PREFETCH_ANCESTORS
                        | FLAG_PREFETCH_DESCENDANTS_HYBRID | FLAG_PREFETCH_SIBLINGS);
    }

    private static AccessibilityNodeInfo getNodeForAccessibilityId(int connectionId,
            int windowId, long accessibilityId, @PrefetchingStrategy int prefetchingStrategy) {
        if (!canPerformRequestOverConnection(connectionId, windowId, accessibilityId)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(connectionId,
                windowId, accessibilityId, false, prefetchingStrategy, null);
    }

    private static AccessibilityNodeInfo getNodeForAccessibilityId(int connectionId,
            IBinder leashToken, long accessibilityId) {
        return getNodeForAccessibilityId(connectionId, leashToken, accessibilityId,
                FLAG_PREFETCH_ANCESTORS
                        | FLAG_PREFETCH_DESCENDANTS_HYBRID | FLAG_PREFETCH_SIBLINGS);
    }

    private static AccessibilityNodeInfo getNodeForAccessibilityId(int connectionId,
            IBinder leashToken, long accessibilityId,
            @PrefetchingStrategy int prefetchingStrategy) {
        if (!((leashToken != null)
                && (getAccessibilityViewId(accessibilityId) != UNDEFINED_ITEM_ID)
                && (connectionId != UNDEFINED_CONNECTION_ID))) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(connectionId,
                leashToken, accessibilityId, false, prefetchingStrategy, null);
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
     * <li><strong>Overridden standard actions</strong> - These are actions that override
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
     * <p>
     * <aside class="note">
     * <b>Note:</b> Views which support these actions should invoke
     * {@link View#setImportantForAccessibility(int)} with
     * {@link View#IMPORTANT_FOR_ACCESSIBILITY_YES} to ensure an {@link AccessibilityService}
     * can discover the set of supported actions. </aside>
     * </p>
     * <p>
     * <aside class="note">
     * <b>Note:</b> Use {@link androidx.core.view.ViewCompat#addAccessibilityAction(View,
     * CharSequence, AccessibilityViewCommand)} to register an action directly on the view. </aside>
     * </p>
     */
    public static final class AccessibilityAction implements Parcelable {

        /** @hide */
        public static final ArraySet<AccessibilityAction> sStandardActions = new ArraySet<>();

        /**
         * Action that gives input focus to the node.
         * <p>The focus request send an event of {@link AccessibilityEvent#TYPE_VIEW_FOCUSED}
         * if successful. In the View system, this is handled by {@link View#requestFocus}.
         *
         * <p>The node that is focused should return {@code true} for
         * {@link AccessibilityNodeInfo#isFocused()}.
         *
         * See {@link #ACTION_ACCESSIBILITY_FOCUS} for the difference between system and
         * accessibility focus.
         */
        public static final AccessibilityAction ACTION_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS);

        /**
         * Action that clears input focus of the node.
         * <p>The node that is cleared should return {@code false} for
         * {@link AccessibilityNodeInfo#isFocused)}.
         */
        public static final AccessibilityAction ACTION_CLEAR_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);

        /**
         *  Action that selects the node.
         *  The view the implements this should send a
         *  {@link AccessibilityEvent#TYPE_VIEW_SELECTED} event.
         * @see AccessibilityAction#ACTION_CLEAR_SELECTION
         */
        public static final AccessibilityAction ACTION_SELECT =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT);

        /**
         * Action that deselects the node.
         * @see AccessibilityAction#ACTION_SELECT
         */
        public static final AccessibilityAction ACTION_CLEAR_SELECTION =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);

        /**
         * Action that clicks on the node info.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_CLICKED} event. In the View system,
         * the default handling of this action when performed by a service is to call
         * {@link View#performClick()}, and setting a
         * {@link View#setOnClickListener(View.OnClickListener)} automatically adds this action.
         *
         * <p>{@link #isClickable()} should return true if this action is available.
         */
        public static final AccessibilityAction ACTION_CLICK =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK);

        /**
         * Action that long clicks on the node.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED} event. In the View system,
         * the default handling of this action when performed by a service is to call
         * {@link View#performLongClick()}, and setting a
         * {@link View#setOnLongClickListener(View.OnLongClickListener)} automatically adds this
         * action.
         *
         * <p>{@link #isLongClickable()} should return true if this action is available.
         */
        public static final AccessibilityAction ACTION_LONG_CLICK =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);

        /**
         * Action that gives accessibility focus to the node.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUSED} event
         * if successful. The node that is focused should return {@code true} for
         * {@link AccessibilityNodeInfo#isAccessibilityFocused()}.
         *
         * <p>This is intended to be used by screen readers to assist with user navigation. Apps
         * changing focus can confuse screen readers, so the resulting behavior can vary by device
         * and screen reader version.
         * <p>This is distinct from {@link #ACTION_FOCUS}, which refers to system focus. System
         * focus is typically used to convey targets for keyboard navigation.
         */
        public static final AccessibilityAction ACTION_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);

        /**
         * Action that clears accessibility focus of the node.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED} event if successful. The
         * node that is cleared should return {@code false} for
         * {@link AccessibilityNodeInfo#isAccessibilityFocused()}.
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

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content forward.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event. Depending on the orientation,
         * this element should also add the relevant directional scroll actions of
         * {@link #ACTION_SCROLL_LEFT}, {@link #ACTION_SCROLL_RIGHT},
         * {@link #ACTION_SCROLL_UP}, and {@link #ACTION_SCROLL_DOWN}. If the scrolling brings
         * the next or previous element into view as the center element, such as in a ViewPager2,
         * use {@link #ACTION_PAGE_DOWN} and the other page actions instead of the directional
         * actions.
         * <p>Example: a scrolling UI of vertical orientation with a forward
         * scroll action should also add the scroll down action:
         * <pre class="prettyprint"><code>
         *     onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
         *          super.onInitializeAccessibilityNodeInfo(info);
         *          if (canScrollForward) {
         *              info.addAction(ACTION_SCROLL_FORWARD);
         *              info.addAction(ACTION_SCROLL_DOWN);
         *          }
         *     }
         *     performAccessibilityAction(int action, Bundle bundle) {
         *          if (action == ACTION_SCROLL_FORWARD || action == ACTION_SCROLL_DOWN) {
         *              scrollForward();
         *          }
         *     }
         *     scrollForward() {
         *         ...
         *         if (mAccessibilityManager.isEnabled()) {
         *             event = new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
         *             event.setScrollDeltaX(dx);
         *             event.setScrollDeltaY(dy);
         *             event.setMaxScrollX(maxDx);
         *             event.setMaxScrollY(maxDY);
         *             sendAccessibilityEventUnchecked(event);
         *        }
         *     }
         *      </code>
         * </pre></p>
         */
        public static final AccessibilityAction ACTION_SCROLL_FORWARD =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content backward.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event. Depending on the orientation,
         * this element should also add the relevant directional scroll actions of
         * {@link #ACTION_SCROLL_LEFT}, {@link #ACTION_SCROLL_RIGHT},
         * {@link #ACTION_SCROLL_UP}, and {@link #ACTION_SCROLL_DOWN}. If the scrolling brings
         * the next or previous element into view as the center element, such as in a ViewPager2,
         * use {@link #ACTION_PAGE_DOWN} and the other page actions instead of the directional
         * actions.
         * <p> Example: a scrolling UI of horizontal orientation with a backward
         * scroll action should also add the scroll left/right action (LTR/RTL):
         * <pre class="prettyprint"><code>
         *     onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
         *          super.onInitializeAccessibilityNodeInfo(info);
         *          if (canScrollBackward) {
         *              info.addAction(ACTION_SCROLL_FORWARD);
         *              if (leftToRight) {
         *                  info.addAction(ACTION_SCROLL_LEFT);
         *              } else {
         *                  info.addAction(ACTION_SCROLL_RIGHT);
         *              }
         *          }
         *     }
         *     performAccessibilityAction(int action, Bundle bundle) {
         *          if (action == ACTION_SCROLL_BACKWARD) {
         *              scrollBackward();
         *          } else if (action == ACTION_SCROLL_LEFT) {
         *              if (!isRTL()){
         *                  scrollBackward();
         *              }
         *          } else if (action == ACTION_SCROLL_RIGHT) {
         *              if (isRTL()){
         *                  scrollBackward();
         *              }
         *          }
         *     }
         *     scrollBackward() {
         *         ...
         *         if (mAccessibilityManager.isEnabled()) {
         *             event = new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
         *             event.setScrollDeltaX(dx);
         *             event.setScrollDeltaY(dy);
         *             event.setMaxScrollX(maxDx);
         *             event.setMaxScrollY(maxDY);
         *             sendAccessibilityEventUnchecked(event);
         *        }
         *     }
         *      </code>
         * </pre></p>
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
         * <p> If this is a text selection, the UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED} event if its selection is
         * updated. This element should also return {@code true} for
         * {@link AccessibilityNodeInfo#isTextSelectable()}.
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
         * <p> The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED} event if its text is updated.
         * This element should also return {@code true} for
         * {@link AccessibilityNodeInfo#isEditable()}.
         */
        public static final AccessibilityAction ACTION_SET_TEXT =
                new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT);

        /**
         * Action that requests the node make its bounding rectangle visible
         * on the screen, scrolling if necessary just enough.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
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
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         *
         * @see AccessibilityNodeInfo#getCollectionInfo()
         */
        public static final AccessibilityAction ACTION_SCROLL_TO_POSITION =
                new AccessibilityAction(R.id.accessibilityActionScrollToPosition);

        /**
         * Action that brings fully on screen the next node in the specified direction.
         *
         * <p>
         *     This should include wrapping around to the next/previous row, column, etc. in a
         *     collection if one is available. If there is no node in that direction, the action
         *     should fail and return false.
         * </p>
         * <p>
         *     This action should be used instead of
         *     {@link AccessibilityAction#ACTION_SCROLL_TO_POSITION} when a widget does not have
         *     clear row and column semantics or if a directional search is needed to find a node in
         *     a complex ViewGroup where individual nodes may span multiple rows or columns. The
         *     implementing widget must send a
         *     {@link AccessibilityEvent#TYPE_VIEW_TARGETED_BY_SCROLL} accessibility event with the
         *     scroll target as the source.  An accessibility service can listen for this event,
         *     inspect its source, and use the result when determining where to place accessibility
         *     focus.
         * <p>
         *     <strong>Arguments:</strong> {@link #ACTION_ARGUMENT_DIRECTION_INT}. This is a
         *     required argument.<br>
         * </p>
         */
        @NonNull public static final AccessibilityAction ACTION_SCROLL_IN_DIRECTION =
                new AccessibilityAction(R.id.accessibilityActionScrollInDirection);

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content up.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_SCROLL_UP =
                new AccessibilityAction(R.id.accessibilityActionScrollUp);

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content left.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_SCROLL_LEFT =
                new AccessibilityAction(R.id.accessibilityActionScrollLeft);

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content down.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_SCROLL_DOWN =
                new AccessibilityAction(R.id.accessibilityActionScrollDown);

        // TODO(316638728): restore ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT in javadoc
        /**
         * Action to scroll the node content right.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_SCROLL_RIGHT =
                new AccessibilityAction(R.id.accessibilityActionScrollRight);

        /**
         * Action to move to the page above.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_PAGE_UP =
                new AccessibilityAction(R.id.accessibilityActionPageUp);

        /**
         * Action to move to the page below.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_PAGE_DOWN =
                new AccessibilityAction(R.id.accessibilityActionPageDown);

        /**
         * Action to move to the page left.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_PAGE_LEFT =
                new AccessibilityAction(R.id.accessibilityActionPageLeft);

        /**
         * Action to move to the page right.
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event.
         */
        public static final AccessibilityAction ACTION_PAGE_RIGHT =
                new AccessibilityAction(R.id.accessibilityActionPageRight);

        /**
         * Action that context clicks the node.
         *
         * <p>The UI element that implements this should send a
         * {@link AccessibilityEvent#TYPE_VIEW_CONTEXT_CLICKED} event. In the View system,
         * the default handling of this action when performed by a service is to call
         * {@link View#performContextClick()}, and setting a
         * {@link View#setOnContextClickListener(View.OnContextClickListener)} automatically adds
         * this action.
         *
         * <p>A context click usually occurs from a mouse pointer right-click or a stylus button
         * press.
         *
         * <p>{@link #isContextClickable()} should return true if this action is available.
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

        /**
         * Action that presses and holds a node.
         * <p>
         * This action is for nodes that have distinct behavior that depends on how long a press is
         * held. Nodes having a single action for long press should use {@link #ACTION_LONG_CLICK}
         *  instead of this action, and nodes should not expose both actions.
         * <p>
         * When calling {@code performAction(ACTION_PRESS_AND_HOLD, bundle}, use
         * {@link #ACTION_ARGUMENT_PRESS_AND_HOLD_DURATION_MILLIS_INT} to specify how long the
         * node is pressed. The first time an accessibility service performs ACTION_PRES_AND_HOLD
         * on a node, it must specify 0 as ACTION_ARGUMENT_PRESS_AND_HOLD, so the application is
         * notified that the held state has started. To ensure reasonable behavior, the values
         * must be increased incrementally and may not exceed 10,000. UIs requested
         * to hold for times outside of this range should ignore the action.
         * <p>
         * The total time the element is held could be specified by an accessibility user up-front,
         * or may depend on what happens on the UI as the user continues to request the hold.
         * <p>
         *   <strong>Note:</strong> The time between dispatching the action and it arriving in the
         *     UI process is not guaranteed. It is possible on a busy system for the time to expire
         *     unexpectedly. For the case of holding down a key for a repeating action, a delayed
         *     arrival should be benign. Please do not use this sort of action in cases where such
         *     delays will lead to unexpected UI behavior.
         * <p>
         */
        @NonNull public static final AccessibilityAction ACTION_PRESS_AND_HOLD =
                new AccessibilityAction(R.id.accessibilityActionPressAndHold);

        /**
         * Action to send an ime actionId which is from
         * {@link android.view.inputmethod.EditorInfo#actionId}. This ime actionId sets by
         * {@link TextView#setImeActionLabel(CharSequence, int)}, or it would be
         * {@link android.view.inputmethod.EditorInfo#IME_ACTION_UNSPECIFIED} if no specific
         * actionId has set. A node should expose this action only for views that are currently
         * with input focus and editable.
         */
        @NonNull public static final AccessibilityAction ACTION_IME_ENTER =
                new AccessibilityAction(R.id.accessibilityActionImeEnter);

        /**
         * Action to start a drag.
         * <p>
         * This action initiates a drag & drop within the system. The source's dragged content is
         * prepared before the drag begins. In View, this action should prepare the arguments to
         * {@link View#startDragAndDrop(ClipData, View.DragShadowBuilder, Object, int)} and then
         * call {@link View#startDragAndDrop(ClipData, View.DragShadowBuilder, Object, int)} with
         * {@link View#DRAG_FLAG_ACCESSIBILITY_ACTION}. The equivalent should be performed for other
         * UI toolkits.
         * </p>
         *
         * @see AccessibilityEvent#CONTENT_CHANGE_TYPE_DRAG_STARTED
         */
        @NonNull public static final AccessibilityAction ACTION_DRAG_START =
                new AccessibilityAction(R.id.accessibilityActionDragStart);

        /**
         * Action to trigger a drop of the content being dragged.
         * <p>
         * This action is added to potential drop targets if the source started a drag with
         * {@link #ACTION_DRAG_START}. In View, these targets are Views that accepted
         * {@link android.view.DragEvent#ACTION_DRAG_STARTED} and have an
         * {@link View.OnDragListener}, and the drop occurs at the center location of the View's
         * window bounds.
         * </p>
         *
         * @see AccessibilityEvent#CONTENT_CHANGE_TYPE_DRAG_DROPPED
         */
        @NonNull public static final AccessibilityAction ACTION_DRAG_DROP =
                new AccessibilityAction(R.id.accessibilityActionDragDrop);

        /**
         * Action to cancel a drag.
         * <p>
         * This action is added to the source that started a drag with {@link #ACTION_DRAG_START}.
         * </p>
         *
         * @see AccessibilityEvent#CONTENT_CHANGE_TYPE_DRAG_CANCELLED
         */
        @NonNull public static final AccessibilityAction ACTION_DRAG_CANCEL =
                new AccessibilityAction(R.id.accessibilityActionDragCancel);

        /**
         * Action to show suggestions for editable text.
         */
        @NonNull public static final AccessibilityAction ACTION_SHOW_TEXT_SUGGESTIONS =
                new AccessibilityAction(R.id.accessibilityActionShowTextSuggestions);

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
        public boolean equals(@Nullable Object other) {
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

        /**
         * {@inheritDoc}
         */
        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * Write data into a parcel.
         */
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mActionId);
            out.writeCharSequence(mLabel);
        }

        public static final @NonNull Parcelable.Creator<AccessibilityAction> CREATOR =
                new Parcelable.Creator<AccessibilityAction>() {
                    public AccessibilityAction createFromParcel(Parcel in) {
                        return new AccessibilityAction(in);
                    }

                    public AccessibilityAction[] newArray(int size) {
                        return new AccessibilityAction[size];
                    }
                };

        private AccessibilityAction(Parcel in) {
            mActionId = in.readInt();
            mLabel = in.readCharSequence();
        }
    }

    /**
     * Class with information if a node is a range.
     */
    public static final class RangeInfo {

        /** Range type: integer. */
        public static final int RANGE_TYPE_INT = 0;
        /** Range type: float. */
        public static final int RANGE_TYPE_FLOAT = 1;
        /** Range type: percent with values from zero to one hundred. */
        public static final int RANGE_TYPE_PERCENT = 2;

        private int mType;
        private float mMin;
        private float mMax;
        private float mCurrent;
        /**
         * Instantiates a new RangeInfo.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link AccessibilityNodeInfo.RangeInfo#RangeInfo(int, float, float,
         * float)} instead.
         *
         * @param type The type of the range.
         * @param min The minimum value. Use {@code Float.NEGATIVE_INFINITY} if the range has no
         *            minimum.
         * @param max The maximum value. Use {@code Float.POSITIVE_INFINITY} if the range has no
         *            maximum.
         * @param current The current value.
         */
        @Deprecated
        public static RangeInfo obtain(int type, float min, float max, float current) {
            return new RangeInfo(type, min, max, current);
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
        public RangeInfo(int type, float min, float max, float current) {
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
         *
         * @deprecated Object pooling has been discontinued. Calling this function now will have
         * no effect.
         */
        @Deprecated
        void recycle() {}

        private void clear() {
            mType = 0;
            mMin = 0;
            mMax = 0;
            mCurrent = 0;
        }
    }

    /**
     * Class with information if a node is a collection.
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

        /**
         * Constant to denote a missing collection count.
         *
         * This should be used for {@code mItemCount} and
         * {@code mImportantForAccessibilityItemCount} when values for those fields are not known.
         */
        @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
        public static final int UNDEFINED = -1;

        private int mRowCount;
        private int mColumnCount;
        private boolean mHierarchical;
        private int mSelectionMode;
        private int mItemCount;
        private int mImportantForAccessibilityItemCount;

        /**
         * Instantiates a CollectionInfo that is a clone of another one.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionInfo#CollectionInfo} instead.
         *
         * @param other The instance to clone.
         * @hide
         */
        public static CollectionInfo obtain(CollectionInfo other) {
            return new CollectionInfo(other.mRowCount, other.mColumnCount, other.mHierarchical,
                    other.mSelectionMode, other.mItemCount,
                    other.mImportantForAccessibilityItemCount);
        }

        /**
         * Obtains a pooled instance.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionInfo#CollectionInfo(int, int,
         * boolean)} instead.
         *
         * @param rowCount The number of rows, or -1 if count is unknown.
         * @param columnCount The number of columns, or -1 if count is unknown.
         * @param hierarchical Whether the collection is hierarchical.
         */
        public static CollectionInfo obtain(int rowCount, int columnCount,
                boolean hierarchical) {
            return new CollectionInfo(rowCount, columnCount, hierarchical, SELECTION_MODE_NONE);
        }

        /**
         * Obtains a pooled instance.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionInfo#CollectionInfo(int, int,
         * boolean, int)} instead.
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
            return new CollectionInfo(rowCount, columnCount, hierarchical, selectionMode);
        }

        /**
         * Creates a new instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         */
        public CollectionInfo(int rowCount, int columnCount, boolean hierarchical) {
            this(rowCount, columnCount, hierarchical, SELECTION_MODE_NONE);
        }

        /**
         * Creates a new instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         * @param selectionMode The collection's selection mode.
         */
        public CollectionInfo(int rowCount, int columnCount, boolean hierarchical,
                int selectionMode) {
            mRowCount = rowCount;
            mColumnCount = columnCount;
            mHierarchical = hierarchical;
            mSelectionMode = selectionMode;
            mItemCount = UNDEFINED;
            mImportantForAccessibilityItemCount = UNDEFINED;
        }

        /**
         * Creates a new instance.
         *
         * @param rowCount The number of rows.
         * @param columnCount The number of columns.
         * @param hierarchical Whether the collection is hierarchical.
         * @param selectionMode The collection's selection mode.
         * @param itemCount The collection's item count, which includes items that are unimportant
         *                  for accessibility. When ViewGroups map cleanly to both row and column
         *                  semantics, clients should populate the row and column counts and
         *                  optionally populate this field. In all other cases, clients should
         *                  populate this field so that accessibility services can use it to relay
         *                  the collection size to users. This should be set to {@code UNDEFINED} if
         *                  the item count is not known.
         * @param importantForAccessibilityItemCount The count of the collection's views considered
         *                                           important for accessibility.
         * @hide
         */
        public CollectionInfo(int rowCount, int columnCount, boolean hierarchical,
                int selectionMode, int itemCount, int importantForAccessibilityItemCount) {
            mRowCount = rowCount;
            mColumnCount = columnCount;
            mHierarchical = hierarchical;
            mSelectionMode = selectionMode;
            mItemCount = itemCount;
            mImportantForAccessibilityItemCount = importantForAccessibilityItemCount;
        }

        /**
         * Gets the number of rows.
         *
         * @return The row count, or -1 if count is unknown.
         */
        public int getRowCount() {
            return mRowCount;
        }

        /**
         * Gets the number of columns.
         *
         * @return The column count, or -1 if count is unknown.
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
         * Gets the number of items in the collection.
         *
         * @return The count of items, which may be {@code UNDEFINED} if the count is not known.
         */
        @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
        public int getItemCount() {
            return mItemCount;
        }

        /**
         * Gets the number of items in the collection considered important for accessibility.
         *
         * @return The count of items important for accessibility, which may be {@code UNDEFINED}
         * if the count is not known.
         */
        @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
        public int getImportantForAccessibilityItemCount() {
            return mImportantForAccessibilityItemCount;
        }

        /**
         * Previously would recycle this instance.
         *
         * @deprecated Object pooling has been discontinued. Calling this function now will have
         * no effect.
         */
        @Deprecated
        void recycle() {}

        private void clear() {
            mRowCount = 0;
            mColumnCount = 0;
            mHierarchical = false;
            mSelectionMode = SELECTION_MODE_NONE;
            mItemCount = UNDEFINED;
            mImportantForAccessibilityItemCount = UNDEFINED;
        }

        /**
         * The builder for CollectionInfo.
         */

        @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
        public static final class Builder {
            private int mRowCount = 0;
            private int mColumnCount = 0;
            private boolean mHierarchical = false;
            private int mSelectionMode;
            private int mItemCount = UNDEFINED;
            private int mImportantForAccessibilityItemCount = UNDEFINED;

            /**
             * Creates a new Builder.
             */
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public Builder() {
            }

            /**
             * Sets the row count.
             * @param rowCount The number of rows in the collection.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setRowCount(int rowCount) {
                mRowCount = rowCount;
                return this;
            }

            /**
             * Sets the column count.
             * @param columnCount The number of columns in the collection.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setColumnCount(int columnCount) {
                mColumnCount = columnCount;
                return this;
            }
            /**
             * Sets whether the collection is hierarchical.
             * @param hierarchical Whether the collection is hierarchical.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setHierarchical(boolean hierarchical) {
                mHierarchical = hierarchical;
                return this;
            }

            /**
             * Sets the selection mode.
             * @param selectionMode The selection mode.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setSelectionMode(int selectionMode) {
                mSelectionMode = selectionMode;
                return this;
            }

            /**
             * Sets the number of items in the collection. Can be optionally set for ViewGroups with
             * clear row and column semantics; should be set for all other clients.
             *
             * @param itemCount The number of items in the collection. This should be set to
             *                  {@code UNDEFINED} if the item count is not known.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setItemCount(int itemCount) {
                mItemCount = itemCount;
                return this;
            }

            /**
             * Sets the number of views considered important for accessibility.
             * @param importantForAccessibilityItemCount The number of items important for
             *                                            accessibility.
             * @return This builder.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo.Builder setImportantForAccessibilityItemCount(
                    int importantForAccessibilityItemCount) {
                mImportantForAccessibilityItemCount = importantForAccessibilityItemCount;
                return this;
            }

            /**
             * Creates a new {@link CollectionInfo} instance.
             */
            @NonNull
            @FlaggedApi(Flags.FLAG_COLLECTION_INFO_ITEM_COUNTS)
            public CollectionInfo build() {
                CollectionInfo collectionInfo = new CollectionInfo(mRowCount, mColumnCount,
                        mHierarchical);
                collectionInfo.mSelectionMode = mSelectionMode;
                collectionInfo.mItemCount = mItemCount;
                collectionInfo.mImportantForAccessibilityItemCount =
                        mImportantForAccessibilityItemCount;
                return collectionInfo;
            }
        }
    }

    /**
     * Class with information if a node is a collection item.
     * <p>
     * A collection item is contained in a collection, it starts at
     * a given row and column in the collection, and spans one or
     * more rows and columns. For example, a header of two related
     * table columns starts at the first row and the first column,
     * spans one row and two columns.
     * </p>
     */
    public static final class CollectionItemInfo {
        /**
         * Instantiates a CollectionItemInfo that is a clone of another one.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionItemInfo#CollectionItemInfo}
         * instead.
         *
         * @param other The instance to clone.
         * @hide
         */
        @Deprecated
        public static CollectionItemInfo obtain(CollectionItemInfo other) {
            return new CollectionItemInfo(other.mRowTitle, other.mRowIndex, other.mRowSpan,
                other.mColumnTitle, other.mColumnIndex, other.mColumnSpan, other.mHeading,
                other.mSelected);
        }

        /**
         * Instantiates a new CollectionItemInfo.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionItemInfo#CollectionItemInfo(int,
         * int, int, int, boolean)} instead.
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading. (Prefer
         *                {@link AccessibilityNodeInfo#setHeading(boolean)}).
         */
        @Deprecated
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan,
                int columnIndex, int columnSpan, boolean heading) {
            return new CollectionItemInfo(rowIndex, rowSpan, columnIndex, columnSpan, heading,
                false);
        }

        /**
         * Instantiates a new CollectionItemInfo.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionItemInfo#CollectionItemInfo(int,
         * int, int, int, boolean)} instead.
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading. (Prefer
         *                {@link AccessibilityNodeInfo#setHeading(boolean)}).
         * @param selected Whether the item is selected.
         */
        @Deprecated
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan,
                int columnIndex, int columnSpan, boolean heading, boolean selected) {
            return new CollectionItemInfo(rowIndex, rowSpan, columnIndex, columnSpan, heading,
                selected);
        }

        /**
         * Instantiates a new CollectionItemInfo.
         *
         * @deprecated Object pooling has been discontinued. Creates a new instance using the
         * constructor {@link
         * AccessibilityNodeInfo.CollectionItemInfo#CollectionItemInfo(int,
         * int, int, int, boolean, boolean)} instead.
         *
         * @param rowTitle The row title at which the item is located.
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnTitle The column title at which the item is located.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading. (Prefer
         *                {@link AccessibilityNodeInfo#setHeading(boolean)})
         * @param selected Whether the item is selected.
         * @removed
         */
        @Deprecated
        @NonNull
        public static CollectionItemInfo obtain(@Nullable String rowTitle, int rowIndex,
                int rowSpan, @Nullable String columnTitle, int columnIndex, int columnSpan,
                boolean heading, boolean selected) {
            return new CollectionItemInfo(rowTitle, rowIndex, rowSpan, columnTitle, columnIndex,
                columnSpan, heading, selected);
        }

        private boolean mHeading;
        private int mColumnIndex;
        private int mRowIndex;
        private int mColumnSpan;
        private int mRowSpan;
        private boolean mSelected;
        private String mRowTitle;
        private String mColumnTitle;

        private CollectionItemInfo() {
            /* do nothing */
        }

        /**
         * Creates a new instance.
         *
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         */
        public CollectionItemInfo(int rowIndex, int rowSpan, int columnIndex, int columnSpan,
                boolean heading) {
            this(rowIndex, rowSpan, columnIndex, columnSpan, heading, false);
        }

        /**
         * Creates a new instance.
         *
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         * @param selected Whether the item is selected.
         */
        public CollectionItemInfo(int rowIndex, int rowSpan, int columnIndex, int columnSpan,
                boolean heading, boolean selected) {
            this(null, rowIndex, rowSpan, null, columnIndex, columnSpan,
                    heading, selected);
        }

        /**
         * Creates a new instance.
         *
         * @param rowTitle The row title at which the item is located.
         * @param rowIndex The row index at which the item is located.
         * @param rowSpan The number of rows the item spans.
         * @param columnTitle The column title at which the item is located.
         * @param columnIndex The column index at which the item is located.
         * @param columnSpan The number of columns the item spans.
         * @param heading Whether the item is a heading.
         * @param selected Whether the item is selected.
         * @hide
         */
        public CollectionItemInfo(@Nullable String rowTitle, int rowIndex, int rowSpan,
                @Nullable String columnTitle, int columnIndex, int columnSpan, boolean heading,
                boolean selected) {
            mRowIndex = rowIndex;
            mRowSpan = rowSpan;
            mColumnIndex = columnIndex;
            mColumnSpan = columnSpan;
            mHeading = heading;
            mSelected = selected;
            mRowTitle = rowTitle;
            mColumnTitle = columnTitle;
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
         * Gets the row title at which the item is located.
         *
         * @return The row title.
         */
        @Nullable
        public String getRowTitle() {
            return mRowTitle;
        }

        /**
         * Gets the column title at which the item is located.
         *
         * @return The column title.
         */
        @Nullable
        public String getColumnTitle() {
            return mColumnTitle;
        }

        /**
         * Recycles this instance.
         *
         * @deprecated Object pooling has been discontinued. Calling this function now will have
         * no effect.
         */
        @Deprecated
        void recycle() {}

        private void clear() {
            mColumnIndex = 0;
            mColumnSpan = 0;
            mRowIndex = 0;
            mRowSpan = 0;
            mHeading = false;
            mSelected = false;
            mRowTitle = null;
            mColumnTitle = null;
        }

        /**
         * Builder for creating {@link CollectionItemInfo} objects.
         */
        public static final class Builder {
            private boolean mHeading;
            private int mColumnIndex;
            private int mRowIndex;
            private int mColumnSpan;
            private int mRowSpan;
            private boolean mSelected;
            private String mRowTitle;
            private String mColumnTitle;

            /**
             * Creates a new Builder.
             */
            public Builder() {
            }

            /**
             * Sets the collection item is a heading.
             *
             * @param heading The heading state
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setHeading(boolean heading) {
                mHeading = heading;
                return this;
            }

            /**
             * Sets the column index at which the item is located.
             *
             * @param columnIndex The column index
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setColumnIndex(int columnIndex) {
                mColumnIndex = columnIndex;
                return this;
            }

            /**
             * Sets the row index at which the item is located.
             *
             * @param rowIndex The row index
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setRowIndex(int rowIndex) {
                mRowIndex = rowIndex;
                return this;
            }

            /**
             * Sets the number of columns the item spans.
             *
             * @param columnSpan The number of columns spans
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setColumnSpan(int columnSpan) {
                mColumnSpan = columnSpan;
                return this;
            }

            /**
             * Sets the number of rows the item spans.
             *
             * @param rowSpan The number of rows spans
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setRowSpan(int rowSpan) {
                mRowSpan = rowSpan;
                return this;
            }

            /**
             * Sets the collection item is selected.
             *
             * @param selected The number of rows spans
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setSelected(boolean selected) {
                mSelected = selected;
                return this;
            }

            /**
             * Sets the row title at which the item is located.
             *
             * @param rowTitle The row title
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setRowTitle(@Nullable String rowTitle) {
                mRowTitle = rowTitle;
                return this;
            }

            /**
             * Sets the column title at which the item is located.
             *
             * @param columnTitle The column title
             * @return This builder
             */
            @NonNull
            public CollectionItemInfo.Builder setColumnTitle(@Nullable String columnTitle) {
                mColumnTitle = columnTitle;
                return this;
            }

            /**
             * Builds and returns a {@link CollectionItemInfo}.
             */
            @NonNull
            public CollectionItemInfo build() {
                CollectionItemInfo collectionItemInfo = new CollectionItemInfo();
                collectionItemInfo.mHeading = mHeading;
                collectionItemInfo.mColumnIndex = mColumnIndex;
                collectionItemInfo.mRowIndex = mRowIndex;
                collectionItemInfo.mColumnSpan = mColumnSpan;
                collectionItemInfo.mRowSpan = mRowSpan;
                collectionItemInfo.mSelected = mSelected;
                collectionItemInfo.mRowTitle = mRowTitle;
                collectionItemInfo.mColumnTitle = mColumnTitle;

                return collectionItemInfo;
            }
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
        public static final @NonNull Parcelable.Creator<TouchDelegateInfo> CREATOR =
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
     * Class with information of a view useful to evaluate accessibility needs. Developers can
     * refresh the node with the key {@link #EXTRA_DATA_RENDERING_INFO_KEY} to fetch the text size
     * and unit if it is {@link TextView} and the height and the width of layout params from
     * {@link ViewGroup} or {@link TextView}.
     *
     * @see #EXTRA_DATA_RENDERING_INFO_KEY
     * @see #refreshWithExtraData(String, Bundle)
     */
    public static final class ExtraRenderingInfo {
        private static final int UNDEFINED_VALUE = -1;

        private Size mLayoutSize;
        private float mTextSizeInPx = UNDEFINED_VALUE;
        private int mTextSizeUnit = UNDEFINED_VALUE;

        /**
         * Instantiates an ExtraRenderingInfo, by copying an existing one.
         *
         * @hide
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link #ExtraRenderingInfo(ExtraRenderingInfo)} instead.
         */
        @Deprecated
        @NonNull
        public static ExtraRenderingInfo obtain() {
            return new ExtraRenderingInfo(null);
        }

        /**
         * Instantiates an ExtraRenderingInfo, by copying an existing one.
         *
         * @deprecated Object pooling has been discontinued. Create a new instance using the
         * constructor {@link #ExtraRenderingInfo(ExtraRenderingInfo)} instead.
         * @param other
         */
        @Deprecated
        private static ExtraRenderingInfo obtain(ExtraRenderingInfo other) {
            return new ExtraRenderingInfo(other);
        }

        /**
         * Creates a new rendering info of a view, and this new instance is initialized from
         * the given <code>other</code>.
         *
         * @param other The instance to clone.
         */
        private ExtraRenderingInfo(@Nullable ExtraRenderingInfo other) {
            if (other != null) {
                mLayoutSize = other.mLayoutSize;
                mTextSizeInPx = other.mTextSizeInPx;
                mTextSizeUnit = other.mTextSizeUnit;
            }
        }

        /**
         * Gets the size object containing the height and the width of
         * {@link android.view.ViewGroup.LayoutParams}  if the node is a {@link ViewGroup} or
         * a {@link TextView}, or null otherwise. Useful for some accessibility services to
         * understand whether the text is scalable and fits the view or not.
         *
         * @return a {@link Size} stores layout height and layout width of the view, or null
         * otherwise. And the size value may be in pixels,
         * {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT},
         * or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
         */
        public @Nullable Size getLayoutSize() {
            return mLayoutSize;
        }

        /**
         * Sets layout width and layout height of the view.
         *
         * @param width The layout width.
         * @param height The layout height.
         * @hide
         */
        public void setLayoutSize(int width, int height) {
            mLayoutSize = new Size(width, height);
        }

        /**
         * Gets the text size if the node is a {@link TextView}, or -1 otherwise. Useful for some
         * accessibility services to understand whether the text is scalable and fits the view or
         * not.
         *
         * @return the text size of a {@code TextView}, or -1 otherwise.
         */
        public float getTextSizeInPx() {
            return mTextSizeInPx;
        }

        /**
         * Sets text size of the view.
         *
         * @param textSizeInPx The text size in pixels.
         * @hide
         */
        public void setTextSizeInPx(float textSizeInPx) {
            mTextSizeInPx = textSizeInPx;
        }

        /**
         * Gets the text size unit if the node is a {@link TextView}, or -1 otherwise.
         * Text size returned from {@link #getTextSizeInPx} in raw pixels may scale by factors and
         * convert from other units. Useful for some accessibility services to understand whether
         * the text is scalable and fits the view or not.
         *
         * @return the text size unit which type is {@link TypedValue#TYPE_DIMENSION} of a
         *         {@code TextView}, or -1 otherwise.
         *
         * @see TypedValue#TYPE_DIMENSION
         */
        public int getTextSizeUnit() {
            return mTextSizeUnit;
        }

        /**
         * Sets text size unit of the view.
         *
         * @param textSizeUnit The text size unit.
         * @hide
         */
        public void setTextSizeUnit(int textSizeUnit) {
            mTextSizeUnit = textSizeUnit;
        }

        /**
         * Previously would recycle this instance.
         *
         * @deprecated Object pooling has been discontinued. Calling this function now will have
         * no effect.
         */
        @Deprecated
        void recycle() {}

        private void clear() {
            mLayoutSize = null;
            mTextSizeInPx = UNDEFINED_VALUE;
            mTextSizeUnit = UNDEFINED_VALUE;
        }
    }

    /**
     * @see android.os.Parcelable.Creator
     */
    public static final @NonNull Parcelable.Creator<AccessibilityNodeInfo> CREATOR =
            new Parcelable.Creator<AccessibilityNodeInfo>() {
        @Override
        public AccessibilityNodeInfo createFromParcel(Parcel parcel) {
            AccessibilityNodeInfo info = new AccessibilityNodeInfo();
            info.initFromParcel(parcel);
            return info;
        }

        @Override
        public AccessibilityNodeInfo[] newArray(int size) {
            return new AccessibilityNodeInfo[size];
        }
    };
}
