/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.accessibilityservice.IAccessibilityServiceConnection;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * This class represents accessibility events that are sent by the system when
 * something notable happens in the user interface. For example, when a
 * {@link android.widget.Button} is clicked, a {@link android.view.View} is focused, etc.
 * </p>
 * <p>
 * An accessibility event is fired by an individual view which populates the event with
 * data for its state and requests from its parent to send the event to interested
 * parties. The parent can optionally add an {@link AccessibilityRecord} for itself before
 * dispatching a similar request to its parent. A parent can also choose not to respect the
 * request for sending an event. The accessibility event is sent by the topmost view in the
 * view tree. Therefore, an {@link android.accessibilityservice.AccessibilityService} can
 * explore all records in an accessibility event to obtain more information about the
 * context in which the event was fired.
 * </p>
 * <p>
 * The main purpose of an accessibility event is to expose enough information for an
 * {@link android.accessibilityservice.AccessibilityService} to provide meaningful feedback
 * to the user. Sometimes however, an accessibility service may need more contextual
 * information then the one in the event pay-load. In such cases the service can obtain
 * the event source which is an {@link AccessibilityNodeInfo} (snapshot of a View state)
 * which can be used for exploring the window content. Note that the privilege for accessing
 * an event's source, thus the window content, has to be explicitly requested. For more
 * details refer to {@link android.accessibilityservice.AccessibilityService}. If an
 * accessibility service has not requested to retrieve the window content the event will
 * not contain reference to its source. Also for events of type
 * {@link #TYPE_NOTIFICATION_STATE_CHANGED} the source is never available.
 * </p>
 * <p>
 * This class represents various semantically different accessibility event
 * types. Each event type has an associated set of related properties. In other
 * words, each event type is characterized via a subset of the properties exposed
 * by this class. For each event type there is a corresponding constant defined
 * in this class. Follows a specification of the event types and their associated properties:
 * </p>
 * <p>
 * <b>VIEW TYPES</b></br>
 * </p>
 * <p>
 * <b>View clicked</b> - represents the event of clicking on a {@link android.view.View}
 * like {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc.</br>
 * <em>Type:</em>{@link #TYPE_VIEW_CLICKED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #isChecked()} - Whether the source is checked.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <p>
 * <b>View long clicked</b> - represents the event of long clicking on a {@link android.view.View}
 * like {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc </br>
 * <em>Type:</em>{@link #TYPE_VIEW_LONG_CLICKED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #isChecked()} - Whether the source is checked.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <p>
 * <b>View selected</b> - represents the event of selecting an item usually in
 * the context of an {@link android.widget.AdapterView}.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_SELECTED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #isChecked()} - Whether the source is checked.</li>
 *   <li>{@link #getItemCount()} - The number of selectable items of the source.</li>
 *   <li>{@link #getCurrentItemIndex()} - The currently selected item index.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <p>
 * <b>View focused</b> - represents the event of focusing a
 * {@link android.view.View}.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_FOCUSED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #isChecked()} - Whether the source is checked.</li>
 *   <li>{@link #getItemCount()} - The number of focusable items on the screen.</li>
 *   <li>{@link #getCurrentItemIndex()} - The currently focused item index.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <p>
 * <b>View text changed</b> - represents the event of changing the text of an
 * {@link android.widget.EditText}.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_TEXT_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #isChecked()} - Whether the source is checked.</li>
 *   <li>{@link #getFromIndex()} - The text change start index.</li>
 *   <li>{@link #getAddedCount()} - The number of added characters.</li>
 *   <li>{@link #getRemovedCount()} - The number of removed characters.</li>
 *   <li>{@link #getBeforeText()} - The text of the source before the change.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 * </ul>
 * </p>
 * <p>
 * <b>View text selection changed</b> - represents the event of changing the text
 * selection of an {@link android.widget.EditText}.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_TEXT_SELECTION_CHANGED} </br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source.</li>
 *   <li>{@link #isPassword()} - Whether the source is password.</li>
 *   <li>{@link #getFromIndex()} - The selection start index.</li>
 *   <li>{@link #getToIndex()} - The selection end index.</li>
 *   <li>{@link #getItemCount()} - The length of the source text.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 * </ul>
 * </p>
 * <p>
 * <b>View scrolled</b> - represents the event of scrolling a view. If
 * the source is a descendant of {@link android.widget.AdapterView} the
 * scroll is reported in terms of visible items - the first visible item,
 * the last visible item, and the total items - because the the source
 * is unaware of its pixel size since its adapter is responsible for
 * creating views. In all other cases the scroll is reported as the current
 * scroll on the X and Y axis respectively plus the height of the source in
 * pixels.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_SCROLLED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * <em>Note:</em> This event type is not dispatched to descendants though
 * {@link android.view.View#dispatchPopulateAccessibilityEvent(AccessibilityEvent)
 * View.dispatchPopulateAccessibilityEvent(AccessibilityEvent)}, hence the event
 * source {@link android.view.View} and the sub-tree rooted at it will not receive
 * calls to {@link android.view.View#onPopulateAccessibilityEvent(AccessibilityEvent)
 * View.onPopulateAccessibilityEvent(AccessibilityEvent)}. The preferred way to add
 * text content to such events is by setting the
 * {@link android.R.styleable#View_contentDescription contentDescription} of the source
 * view.</br>
 * </p>
 * <p>
 * <b>TRANSITION TYPES</b></br>
 * </p>
 * <p>
 * <b>Window state changed</b> - represents the event of opening a
 * {@link android.widget.PopupWindow}, {@link android.view.Menu},
 * {@link android.app.Dialog}, etc.</br>
 * <em>Type:</em> {@link #TYPE_WINDOW_STATE_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Window content changed</b> - represents the event of change in the
 * content of a window. This change can be adding/removing view, changing
 * a view size, etc.</br>
 * </p>
 * <p>
 * <strong>Note:</strong> This event is fired only for the window source of the
 * last accessibility event different from {@link #TYPE_NOTIFICATION_STATE_CHANGED}
 * and its purpose is to notify clients that the content of the user interaction
 * window has changed.</br>
 * <em>Type:</em> {@link #TYPE_WINDOW_CONTENT_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 * </ul>
 * <em>Note:</em> This event type is not dispatched to descendants though
 * {@link android.view.View#dispatchPopulateAccessibilityEvent(AccessibilityEvent)
 * View.dispatchPopulateAccessibilityEvent(AccessibilityEvent)}, hence the event
 * source {@link android.view.View} and the sub-tree rooted at it will not receive
 * calls to {@link android.view.View#onPopulateAccessibilityEvent(AccessibilityEvent)
 * View.onPopulateAccessibilityEvent(AccessibilityEvent)}. The preferred way to add
 * text content to such events is by setting the
 * {@link android.R.styleable#View_contentDescription contentDescription} of the source
 * view.</br>
 * </p>
 * <p>
 * <b>NOTIFICATION TYPES</b></br>
 * </p>
 * <p>
 * <b>Notification state changed</b> - represents the event showing
 * {@link android.app.Notification}.</br>
 * <em>Type:</em> {@link #TYPE_NOTIFICATION_STATE_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #getParcelableData()} - The posted {@link android.app.Notification}.</li>
 *   <li>{@link #getText()} - Text for providing more context.</li>
 * </ul>
 * <em>Note:</em> This event type is not dispatched to descendants though
 * {@link android.view.View#dispatchPopulateAccessibilityEvent(AccessibilityEvent)
 * View.dispatchPopulateAccessibilityEvent(AccessibilityEvent)}, hence the event
 * source {@link android.view.View} and the sub-tree rooted at it will not receive
 * calls to {@link android.view.View#onPopulateAccessibilityEvent(AccessibilityEvent)
 * View.onPopulateAccessibilityEvent(AccessibilityEvent)}. The preferred way to add
 * text content to such events is by setting the
 * {@link android.R.styleable#View_contentDescription contentDescription} of the source
 * view.</br>
 * </p>
 * <p>
 * <b>EXPLORATION TYPES</b></br>
 * </p>
 * <p>
 * <b>View hover enter</b> - represents the event of beginning to hover
 * over a {@link android.view.View}. The hover may be generated via
 * exploring the screen by touch or via a pointing device.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_HOVER_ENTER}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <b>View hover exit</b> - represents the event of stopping to hover
 * over a {@link android.view.View}. The hover may be generated via
 * exploring the screen by touch or via a pointing device.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_HOVER_EXIT}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree.</li>
 *   <li>{@link #isEnabled()} - Whether the source is enabled.</li>
 *   <li>{@link #getContentDescription()} - The content description of the source.</li>
 *   <li>{@link #getScrollX()} - The offset of the source left edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getScrollY()} - The offset of the source top edge in pixels
 *       (without descendants of AdapterView).</li>
 *   <li>{@link #getFromIndex()} - The zero based index of the first visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getToIndex()} - The zero based index of the last visible item of the source,
 *       inclusive (for descendants of AdapterView).</li>
 *   <li>{@link #getItemCount()} - The total items of the source
 *       (for descendants of AdapterView).</li>
 * </ul>
 * </p>
 * <p>
 * <b>Touch exploration gesture start</b> - represents the event of starting a touch
 * exploring gesture.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_EXPLORATION_GESTURE_START}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event type is not dispatched to descendants though
 * {@link android.view.View#dispatchPopulateAccessibilityEvent(AccessibilityEvent)
 * View.dispatchPopulateAccessibilityEvent(AccessibilityEvent)}, hence the event
 * source {@link android.view.View} and the sub-tree rooted at it will not receive
 * calls to {@link android.view.View#onPopulateAccessibilityEvent(AccessibilityEvent)
 * View.onPopulateAccessibilityEvent(AccessibilityEvent)}. The preferred way to add
 * text content to such events is by setting the
 * {@link android.R.styleable#View_contentDescription contentDescription} of the source
 * view.</br>
 * </p>
 * <p>
 * <b>Touch exploration gesture end</b> - represents the event of ending a touch
 * exploring gesture.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_EXPLORATION_GESTURE_END}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event type is not dispatched to descendants though
 * {@link android.view.View#dispatchPopulateAccessibilityEvent(AccessibilityEvent)
 * View.dispatchPopulateAccessibilityEvent(AccessibilityEvent)}, hence the event
 * source {@link android.view.View} and the sub-tree rooted at it will not receive
 * calls to {@link android.view.View#onPopulateAccessibilityEvent(AccessibilityEvent)
 * View.onPopulateAccessibilityEvent(AccessibilityEvent)}. The preferred way to add
 * text content to such events is by setting the
 * {@link android.R.styleable#View_contentDescription contentDescription} of the source
 * view.</br>
 * </p>
 * <p>
 * <b>Security note</b>
 * <p>
 * Since an event contains the text of its source privacy can be compromised by leaking
 * sensitive information such as passwords. To address this issue any event fired in response
 * to manipulation of a PASSWORD field does NOT CONTAIN the text of the password.
 * </p>
 *
 * @see android.view.accessibility.AccessibilityManager
 * @see android.accessibilityservice.AccessibilityService
 * @see AccessibilityNodeInfo
 */
public final class AccessibilityEvent extends AccessibilityRecord implements Parcelable {
    private static final boolean DEBUG = false;

    /**
     * Invalid selection/focus position.
     *
     * @see #getCurrentItemIndex()
     */
    public static final int INVALID_POSITION = -1;

    /**
     * Maximum length of the text fields.
     *
     * @see #getBeforeText()
     * @see #getText()
     * </br>
     * Note: This constant is no longer needed since there
     *       is no limit on the length of text that is contained
     *       in an accessibility event anymore.
     */
    @Deprecated
    public static final int MAX_TEXT_LENGTH = 500;

    /**
     * Represents the event of clicking on a {@link android.view.View} like
     * {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc.
     */
    public static final int TYPE_VIEW_CLICKED = 0x00000001;

    /**
     * Represents the event of long clicking on a {@link android.view.View} like
     * {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc.
     */
    public static final int TYPE_VIEW_LONG_CLICKED = 0x00000002;

    /**
     * Represents the event of selecting an item usually in the context of an
     * {@link android.widget.AdapterView}.
     */
    public static final int TYPE_VIEW_SELECTED = 0x00000004;

    /**
     * Represents the event of focusing a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_FOCUSED = 0x00000008;

    /**
     * Represents the event of changing the text of an {@link android.widget.EditText}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 0x00000010;

    /**
     * Represents the event of opening a {@link android.widget.PopupWindow},
     * {@link android.view.Menu}, {@link android.app.Dialog}, etc.
     */
    public static final int TYPE_WINDOW_STATE_CHANGED = 0x00000020;

    /**
     * Represents the event showing a {@link android.app.Notification}.
     */
    public static final int TYPE_NOTIFICATION_STATE_CHANGED = 0x00000040;

    /**
     * Represents the event of a hover enter over a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_HOVER_ENTER = 0x00000080;

    /**
     * Represents the event of a hover exit over a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_HOVER_EXIT = 0x00000100;

    /**
     * Represents the event of starting a touch exploration gesture.
     */
    public static final int TYPE_TOUCH_EXPLORATION_GESTURE_START = 0x00000200;

    /**
     * Represents the event of ending a touch exploration gesture.
     */
    public static final int TYPE_TOUCH_EXPLORATION_GESTURE_END = 0x00000400;

    /**
     * Represents the event of changing the content of a window.
     */
    public static final int TYPE_WINDOW_CONTENT_CHANGED = 0x00000800;

    /**
     * Represents the event of scrolling a view.
     */
    public static final int TYPE_VIEW_SCROLLED = 0x00001000;

    /**
     * Represents the event of changing the selection in an {@link android.widget.EditText}.
     */
    public static final int TYPE_VIEW_TEXT_SELECTION_CHANGED = 0x00002000;

    /**
     * Mask for {@link AccessibilityEvent} all types.
     *
     * @see #TYPE_VIEW_CLICKED
     * @see #TYPE_VIEW_LONG_CLICKED
     * @see #TYPE_VIEW_SELECTED
     * @see #TYPE_VIEW_FOCUSED
     * @see #TYPE_VIEW_TEXT_CHANGED
     * @see #TYPE_WINDOW_STATE_CHANGED
     * @see #TYPE_NOTIFICATION_STATE_CHANGED
     * @see #TYPE_VIEW_HOVER_ENTER
     * @see #TYPE_VIEW_HOVER_EXIT
     * @see #TYPE_TOUCH_EXPLORATION_GESTURE_START
     * @see #TYPE_TOUCH_EXPLORATION_GESTURE_END
     * @see #TYPE_WINDOW_CONTENT_CHANGED
     * @see #TYPE_VIEW_SCROLLED
     * @see #TYPE_VIEW_TEXT_SELECTION_CHANGED
     */
    public static final int TYPES_ALL_MASK = 0xFFFFFFFF;

    private static final int MAX_POOL_SIZE = 10;
    private static final Object sPoolLock = new Object();
    private static AccessibilityEvent sPool;
    private static int sPoolSize;
    private AccessibilityEvent mNext;
    private boolean mIsInPool;

    private int mEventType;
    private CharSequence mPackageName;
    private long mEventTime;

    private final ArrayList<AccessibilityRecord> mRecords = new ArrayList<AccessibilityRecord>();

    /*
     * Hide constructor from clients.
     */
    private AccessibilityEvent() {
    }

    /**
     * Initialize an event from another one.
     *
     * @param event The event to initialize from.
     */
    void init(AccessibilityEvent event) {
        super.init(event);
        mEventType = event.mEventType;
        mEventTime = event.mEventTime;
        mPackageName = event.mPackageName;
    }

    /**
     * Sets the connection for interacting with the AccessibilityManagerService.
     *
     * @param connection The connection.
     *
     * @hide
     */
    @Override
    public void setConnection(IAccessibilityServiceConnection connection) {
        super.setConnection(connection);
        List<AccessibilityRecord> records = mRecords;
        final int recordCount = records.size();
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = records.get(i);
            record.setConnection(connection);
        }
    }

    /**
     * Sets if this instance is sealed.
     *
     * @param sealed Whether is sealed.
     *
     * @hide
     */
    @Override
    public void setSealed(boolean sealed) {
        super.setSealed(sealed);
        List<AccessibilityRecord> records = mRecords;
        final int recordCount = records.size();
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = records.get(i);
            record.setSealed(sealed);
        }
    }

    /**
     * Gets the number of records contained in the event.
     *
     * @return The number of records.
     */
    public int getRecordCount() {
        return mRecords.size();
    }

    /**
     * Appends an {@link AccessibilityRecord} to the end of event records.
     *
     * @param record The record to append.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void appendRecord(AccessibilityRecord record) {
        enforceNotSealed();
        mRecords.add(record);
    }

    /**
     * Gets the record at a given index.
     *
     * @param index The index.
     * @return The record at the specified index.
     */
    public AccessibilityRecord getRecord(int index) {
        return mRecords.get(index);
    }

    /**
     * Gets the event type.
     *
     * @return The event type.
     */
    public int getEventType() {
        return mEventType;
    }

    /**
     * Sets the event type.
     *
     * @param eventType The event type.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEventType(int eventType) {
        enforceNotSealed();
        mEventType = eventType;
    }

    /**
     * Gets the time in which this event was sent.
     *
     * @return The event time.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Sets the time in which this event was sent.
     *
     * @param eventTime The event time.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEventTime(long eventTime) {
        enforceNotSealed();
        mEventTime = eventTime;
    }

    /**
     * Gets the package name of the source.
     *
     * @return The package name.
     */
    public CharSequence getPackageName() {
        return mPackageName;
    }

    /**
     * Sets the package name of the source.
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
     * Returns a cached instance if such is available or a new one is
     * instantiated with its type property set.
     *
     * @param eventType The event type.
     * @return An instance.
     */
    public static AccessibilityEvent obtain(int eventType) {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setEventType(eventType);
        return event;
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * created. The returned instance is initialized from the given
     * <code>event</code>.
     *
     * @param event The other event.
     * @return An instance.
     */
    public static AccessibilityEvent obtain(AccessibilityEvent event) {
        AccessibilityEvent eventClone = AccessibilityEvent.obtain();
        eventClone.init(event);

        final int recordCount = event.mRecords.size();
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = event.mRecords.get(i);
            AccessibilityRecord recordClone = AccessibilityRecord.obtain(record);
            eventClone.mRecords.add(recordClone);
        }

        return eventClone;
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * instantiated.
     *
     * @return An instance.
     */
    public static AccessibilityEvent obtain() {
        synchronized (sPoolLock) {
            if (sPool != null) {
                AccessibilityEvent event = sPool;
                sPool = sPool.mNext;
                sPoolSize--;
                event.mNext = null;
                event.mIsInPool = false;
                return event;
            }
            return new AccessibilityEvent();
        }
    }

    /**
     * Recycles an instance back to be reused.
     * <p>
     *   <b>Note: You must not touch the object after calling this function.</b>
     * </p>
     *
     * @throws IllegalStateException If the event is already recycled.
     */
    @Override
    public void recycle() {
        if (mIsInPool) {
            throw new IllegalStateException("Event already recycled!");
        }
        clear();
        synchronized (sPoolLock) {
            if (sPoolSize <= MAX_POOL_SIZE) {
                mNext = sPool;
                sPool = this;
                mIsInPool = true;
                sPoolSize++;
            }
        }
    }

    /**
     * Clears the state of this instance.
     *
     * @hide
     */
    @Override
    protected void clear() {
        super.clear();
        mEventType = 0;
        mPackageName = null;
        mEventTime = 0;
        while (!mRecords.isEmpty()) {
            AccessibilityRecord record = mRecords.remove(0);
            record.recycle();
        }
    }

    /**
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityEvent}.
     */
    public void initFromParcel(Parcel parcel) {
        if (parcel.readInt() == 1) {
            mConnection = IAccessibilityServiceConnection.Stub.asInterface(
                    parcel.readStrongBinder());
        }
        setSealed(parcel.readInt() == 1);
        mEventType = parcel.readInt();
        mPackageName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mEventTime = parcel.readLong();
        readAccessibilityRecordFromParcel(this, parcel);

        // Read the records.
        final int recordCount = parcel.readInt();
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = AccessibilityRecord.obtain();
            // Do this to write the connection only once.
            record.setConnection(mConnection);
            readAccessibilityRecordFromParcel(record, parcel);
            mRecords.add(record);
        }
    }

    /**
     * Reads an {@link AccessibilityRecord} from a parcel.
     *
     * @param record The record to initialize.
     * @param parcel The parcel to read from.
     */
    private void readAccessibilityRecordFromParcel(AccessibilityRecord record,
            Parcel parcel) {
        record.mBooleanProperties = parcel.readInt();
        record.mCurrentItemIndex = parcel.readInt();
        record.mItemCount = parcel.readInt();
        record.mFromIndex = parcel.readInt();
        record.mToIndex = parcel.readInt();
        record.mScrollX = parcel.readInt();
        record.mScrollY =  parcel.readInt();
        record.mMaxScrollX = parcel.readInt();
        record.mMaxScrollY =  parcel.readInt();
        record.mAddedCount = parcel.readInt();
        record.mRemovedCount = parcel.readInt();
        record.mClassName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mBeforeText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mParcelableData = parcel.readParcelable(null);
        parcel.readList(record.mText, null);
        record.mSourceWindowId = parcel.readInt();
        record.mSourceNodeId = parcel.readLong();
        record.mSealed = (parcel.readInt() == 1);
    }

    /**
     * {@inheritDoc}
     */
    public void writeToParcel(Parcel parcel, int flags) {
        if (mConnection == null) {
            parcel.writeInt(0);
        } else {
            parcel.writeInt(1);
            parcel.writeStrongBinder(mConnection.asBinder());
        }
        parcel.writeInt(isSealed() ? 1 : 0);
        parcel.writeInt(mEventType);
        TextUtils.writeToParcel(mPackageName, parcel, 0);
        parcel.writeLong(mEventTime);
        writeAccessibilityRecordToParcel(this, parcel, flags);

        // Write the records.
        final int recordCount = getRecordCount();
        parcel.writeInt(recordCount);
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = mRecords.get(i);
            writeAccessibilityRecordToParcel(record, parcel, flags);
        }
    }

    /**
     * Writes an {@link AccessibilityRecord} to a parcel.
     *
     * @param record The record to write.
     * @param parcel The parcel to which to write.
     */
    private void writeAccessibilityRecordToParcel(AccessibilityRecord record, Parcel parcel,
            int flags) {
        parcel.writeInt(record.mBooleanProperties);
        parcel.writeInt(record.mCurrentItemIndex);
        parcel.writeInt(record.mItemCount);
        parcel.writeInt(record.mFromIndex);
        parcel.writeInt(record.mToIndex);
        parcel.writeInt(record.mScrollX);
        parcel.writeInt(record.mScrollY);
        parcel.writeInt(record.mMaxScrollX);
        parcel.writeInt(record.mMaxScrollY);
        parcel.writeInt(record.mAddedCount);
        parcel.writeInt(record.mRemovedCount);
        TextUtils.writeToParcel(record.mClassName, parcel, flags);
        TextUtils.writeToParcel(record.mContentDescription, parcel, flags);
        TextUtils.writeToParcel(record.mBeforeText, parcel, flags);
        parcel.writeParcelable(record.mParcelableData, flags);
        parcel.writeList(record.mText);
        parcel.writeInt(record.mSourceWindowId);
        parcel.writeLong(record.mSourceNodeId);
        parcel.writeInt(record.mSealed ? 1 : 0);
    }

    /**
     * {@inheritDoc}
     */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("EventType: ").append(eventTypeToString(mEventType));
        builder.append("; EventTime: ").append(mEventTime);
        builder.append("; PackageName: ").append(mPackageName);
        builder.append(super.toString());
        if (DEBUG) {
            builder.append("\n");
            builder.append("; sourceWindowId: ").append(mSourceWindowId);
            builder.append("; mSourceNodeId: ").append(mSourceNodeId);
            for (int i = 0; i < mRecords.size(); i++) {
                AccessibilityRecord record = mRecords.get(i);
                builder.append("  Record ");
                builder.append(i);
                builder.append(":");
                builder.append(" [ ClassName: " + record.mClassName);
                builder.append("; Text: " + record.mText);
                builder.append("; ContentDescription: " + record.mContentDescription);
                builder.append("; ItemCount: " + record.mItemCount);
                builder.append("; CurrentItemIndex: " + record.mCurrentItemIndex);
                builder.append("; IsEnabled: " + record.isEnabled());
                builder.append("; IsPassword: " + record.isPassword());
                builder.append("; IsChecked: " + record.isChecked());
                builder.append("; IsFullScreen: " + record.isFullScreen());
                builder.append("; Scrollable: " + record.isScrollable());
                builder.append("; BeforeText: " + record.mBeforeText);
                builder.append("; FromIndex: " + record.mFromIndex);
                builder.append("; ToIndex: " + record.mToIndex);
                builder.append("; ScrollX: " + record.mScrollX);
                builder.append("; ScrollY: " + record.mScrollY);
                builder.append("; AddedCount: " + record.mAddedCount);
                builder.append("; RemovedCount: " + record.mRemovedCount);
                builder.append("; ParcelableData: " + record.mParcelableData);
                builder.append(" ]");
                builder.append("\n");
            }
        } else {
            builder.append("; recordCount: ").append(getRecordCount());
        }
        return builder.toString();
    }

    /**
     * Returns the string representation of an event type. For example,
     * {@link #TYPE_VIEW_CLICKED} is represented by the string TYPE_VIEW_CLICKED.
     *
     * @param eventType The event type
     * @return The string representation.
     */
    public static String eventTypeToString(int eventType) {
        switch (eventType) {
            case TYPE_VIEW_CLICKED:
                return "TYPE_VIEW_CLICKED";
            case TYPE_VIEW_LONG_CLICKED:
                return "TYPE_VIEW_LONG_CLICKED";
            case TYPE_VIEW_SELECTED:
                return "TYPE_VIEW_SELECTED";
            case TYPE_VIEW_FOCUSED:
                return "TYPE_VIEW_FOCUSED";
            case TYPE_VIEW_TEXT_CHANGED:
                return "TYPE_VIEW_TEXT_CHANGED";
            case TYPE_WINDOW_STATE_CHANGED:
                return "TYPE_WINDOW_STATE_CHANGED";
            case TYPE_VIEW_HOVER_ENTER:
                return "TYPE_VIEW_HOVER_ENTER";
            case TYPE_VIEW_HOVER_EXIT:
                return "TYPE_VIEW_HOVER_EXIT";
            case TYPE_NOTIFICATION_STATE_CHANGED:
                return "TYPE_NOTIFICATION_STATE_CHANGED";  
            case TYPE_TOUCH_EXPLORATION_GESTURE_START:
                return "TYPE_TOUCH_EXPLORATION_GESTURE_START";
            case TYPE_TOUCH_EXPLORATION_GESTURE_END:
                return "TYPE_TOUCH_EXPLORATION_GESTURE_END";
            case TYPE_WINDOW_CONTENT_CHANGED:
                return "TYPE_WINDOW_CONTENT_CHANGED";
            case TYPE_VIEW_TEXT_SELECTION_CHANGED:
                return "TYPE_VIEW_TEXT_SELECTION_CHANGED";
            case TYPE_VIEW_SCROLLED:
                return "TYPE_VIEW_SCROLLED";
            default:
                return null;
        }
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<AccessibilityEvent> CREATOR =
            new Parcelable.Creator<AccessibilityEvent>() {
        public AccessibilityEvent createFromParcel(Parcel parcel) {
            AccessibilityEvent event = AccessibilityEvent.obtain();
            event.initFromParcel(parcel);
            return event;
        }

        public AccessibilityEvent[] newArray(int size) {
            return new AccessibilityEvent[size];
        }
    };
}
