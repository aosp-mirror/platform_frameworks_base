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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.BitUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 * parties. The parent can optionally modify or even block the event based on its broader
 * understanding of the user interface's context.
 * </p>
 * <p>
 * The main purpose of an accessibility event is to communicate changes in the UI to an
 * {@link android.accessibilityservice.AccessibilityService}. The service may then inspect,
 * if needed the user interface by examining the View hierarchy, as represented by a tree of
 * {@link AccessibilityNodeInfo}s (snapshot of a View state)
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
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating and processing AccessibilityEvents, read the
 * <a href="{@docRoot}guide/topics/ui/accessibility/index.html">Accessibility</a>
 * developer guide.</p>
 * </div>
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
 *   <li>{@link #getText()} - The new text of the source.</li>
 *   <li>{@link #getBeforeText()} - The text of the source before the change.</li>
 *   <li>{@link #getFromIndex()} - The text change start index.</li>
 *   <li>{@link #getAddedCount()} - The number of added characters.</li>
 *   <li>{@link #getRemovedCount()} - The number of removed characters.</li>
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
 * </ul>
 * </p>
 * <b>View text traversed at movement granularity</b> - represents the event of traversing the
 * text of a view at a given granularity. For example, moving to the next word.</br>
 * <em>Type:</em> {@link #TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY} </br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getMovementGranularity()} - Sets the granularity at which a view's text
 *       was traversed.</li>
 *   <li>{@link #getText()} -  The text of the source's sub-tree.</li>
 *   <li>{@link #getFromIndex()} - The start the text that was skipped over in this movement.
 *       This is the starting point when moving forward through the text, but not when moving
 *       back.</li>
 *   <li>{@link #getToIndex()} - The end of the text that was skipped over in this movement.
 *       This is the ending point when moving forward through the text, but not when moving
 *       back.</li>
 *   <li>{@link #getAction()} - Gets traversal action which specifies the direction.</li>
 * </ul>
 * </p>
 * <p>
 * <b>View scrolled</b> - represents the event of scrolling a view. </br>
 * <em>Type:</em> {@link #TYPE_VIEW_SCROLLED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getScrollDeltaX()} - The difference in the horizontal position.</li>
 *   <li>{@link #getScrollDeltaY()} - The difference in the vertical position.</li>
 * </ul>
 * </p>
 * <p>
 * <b>TRANSITION TYPES</b></br>
 * </p>
 * <p>
 * <b>Window state changed</b> - represents the event of a change to a section of
 * the user interface that is visually distinct. Should be sent from either the
 * root view of a window or from a view that is marked as a pane
 * {@link android.view.View#setAccessibilityPaneTitle(CharSequence)}. Note that changes
 * to true windows are represented by {@link #TYPE_WINDOWS_CHANGED}.</br>
 * <em>Type:</em> {@link #TYPE_WINDOW_STATE_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getContentChangeTypes()} - The type of state changes.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the source's sub-tree, including the pane titles.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Window content changed</b> - represents the event of change in the
 * content of a window. This change can be adding/removing view, changing
 * a view size, etc.</br>
 * </p>
 * <p>
 * <em>Type:</em> {@link #TYPE_WINDOW_CONTENT_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getContentChangeTypes()} - The type of content changes.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Windows changed</b> - represents a change in the windows shown on
 * the screen such as a window appeared, a window disappeared, a window size changed,
 * a window layer changed, etc. These events should only come from the system, which is responsible
 * for managing windows. The list of windows is available from
 * {@link android.accessibilityservice.AccessibilityService#getWindows()}.
 * For regions of the user interface that are presented as windows but are
 * controlled by an app's process, use {@link #TYPE_WINDOW_STATE_CHANGED}.</br>
 * <em>Type:</em> {@link #TYPE_WINDOWS_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getEventTime()} - The event time.</li>
 *   <li>{@link #getWindowChanges()}</li> - The specific change to the source window
 * </ul>
 * <em>Note:</em> You can retrieve the {@link AccessibilityWindowInfo} for the window
 * source of the event by looking through the list returned by
 * {@link android.accessibilityservice.AccessibilityService#getWindows()} for the window whose ID
 * matches {@link #getWindowId()}.
 * </p>
 * <p>
 * <b>NOTIFICATION TYPES</b></br>
 * </p>
 * <p>
 * <b>Notification state changed</b> - represents the event showing a transient piece of information
 * to the user. This information may be a {@link android.app.Notification} or
 * {@link android.widget.Toast}.</br>
 * <em>Type:</em> {@link #TYPE_NOTIFICATION_STATE_CHANGED}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getParcelableData()} - The posted {@link android.app.Notification}, if
 *   applicable.</li>
 *   <li>{@link #getText()} - Displayed text of the {@link android.widget.Toast}, if applicable,
 *   or may contain text from the {@link android.app.Notification}, although
 *   {@link #getParcelableData()} is a richer set of data for {@link android.app.Notification}.</li>
 * </ul>
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
 * </ul>
 * </p>
 * <p>
 * <b>Touch interaction start</b> - represents the event of starting a touch
 * interaction, which is the user starts touching the screen.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_INTERACTION_START}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>Touch interaction end</b> - represents the event of ending a touch
 * interaction, which is the user stops touching the screen.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_INTERACTION_END}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>Touch exploration gesture start</b> - represents the event of starting a touch
 * exploring gesture.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_EXPLORATION_GESTURE_START}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>Touch exploration gesture end</b> - represents the event of ending a touch
 * exploring gesture.</br>
 * <em>Type:</em> {@link #TYPE_TOUCH_EXPLORATION_GESTURE_END}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>Touch gesture detection start</b> - represents the event of starting a user
 * gesture detection.</br>
 * <em>Type:</em> {@link #TYPE_GESTURE_DETECTION_START}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>Touch gesture detection end</b> - represents the event of ending a user
 * gesture detection.</br>
 * <em>Type:</em> {@link #TYPE_GESTURE_DETECTION_END}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 * </ul>
 * <em>Note:</em> This event is fired only by the system and is not passed to the
 * view tree to be populated.</br>
 * </p>
 * <p>
 * <b>MISCELLANEOUS TYPES</b></br>
 * </p>
 * <p>
 * <b>Announcement</b> - represents the event of an application requesting a screen reader to make
 * an announcement. Because the event carries no semantic meaning, this event is appropriate only
 * in exceptional situations where additional screen reader output is needed but other types of
 * accessibility services do not need to be aware of the change.</br>
 * <em>Type:</em> {@link #TYPE_ANNOUNCEMENT}</br>
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getEventType()} - The type of the event.</li>
 *   <li>{@link #getSource()} - The source info (for registered clients).</li>
 *   <li>{@link #getClassName()} - The class name of the source.</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 *   <li>{@link #getText()} - The text of the announcement.</li>
 * </ul>
 * </p>
  * <p>
 * <b>speechStateChanged</b>
 * <em>Type:</em> {@link #TYPE_SPEECH_STATE_CHANGE}</br>
 * Represents a change in the speech state defined by the
 * bit mask of the speech state change types.
 * A change in the speech state occurs when an application wants to signal that
 * it is either speaking or listening for human speech.
 * This event helps avoid conflicts where two applications want to speak or one listens
 * when another speaks.
 * When sending this event, the sender should ensure that  the accompanying state change types
 * make sense. For example, the sender should not send
 * {@link #SPEECH_STATE_SPEAKING_START} and {@link #SPEECH_STATE_SPEAKING_END} together.
 * <em>Properties:</em></br>
 * <ul>
 *   <li>{@link #getSpeechStateChangeTypes()} - The type of state changes</li>
 *   <li>{@link #getPackageName()} - The package name of the source.</li>
 *   <li>{@link #getEventTime()}  - The event time.</li>
 * </ul>
 * </p>
 *
 * @see android.view.accessibility.AccessibilityManager
 * @see android.accessibilityservice.AccessibilityService
 * @see AccessibilityNodeInfo
 */
public final class AccessibilityEvent extends AccessibilityRecord implements Parcelable {
    private static final String LOG_TAG = "AccessibilityEvent";

    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG) && Build.IS_DEBUGGABLE;

    /** @hide */
    public static final boolean DEBUG_ORIGIN = false;

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
     * Represents the event of setting input focus of a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_FOCUSED = 0x00000008;

    /**
     * Represents the event of changing the text of an {@link android.widget.EditText}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 0x00000010;

    /**
     * Represents the event of a change to a visually distinct section of the user interface.
     * These events should only be dispatched from {@link android.view.View}s that have
     * accessibility pane titles, and replaces {@link #TYPE_WINDOW_CONTENT_CHANGED} for those
     * sources. Details about the change are available from {@link #getContentChangeTypes()}.
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
     * Represents the event of changing the content of a window and more
     * specifically the sub-tree rooted at the event's source.
     */
    public static final int TYPE_WINDOW_CONTENT_CHANGED = 0x00000800;

    /**
     * Represents the event of scrolling a view. This event type is generally not sent directly.
     * @see android.view.View#onScrollChanged(int, int, int, int)
     */
    public static final int TYPE_VIEW_SCROLLED = 0x00001000;

    /**
     * Represents the event of changing the selection in an {@link android.widget.EditText}.
     */
    public static final int TYPE_VIEW_TEXT_SELECTION_CHANGED = 0x00002000;

    /**
     * Represents the event of an application making an announcement.
     */
    public static final int TYPE_ANNOUNCEMENT = 0x00004000;

    /**
     * Represents the event of gaining accessibility focus.
     */
    public static final int TYPE_VIEW_ACCESSIBILITY_FOCUSED = 0x00008000;

    /**
     * Represents the event of clearing accessibility focus.
     */
    public static final int TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED = 0x00010000;

    /**
     * Represents the event of traversing the text of a view at a given movement granularity.
     */
    public static final int TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY = 0x00020000;

    /**
     * Represents the event of beginning gesture detection.
     */
    public static final int TYPE_GESTURE_DETECTION_START = 0x00040000;

    /**
     * Represents the event of ending gesture detection.
     */
    public static final int TYPE_GESTURE_DETECTION_END = 0x00080000;

    /**
     * Represents the event of the user starting to touch the screen.
     */
    public static final int TYPE_TOUCH_INTERACTION_START = 0x00100000;

    /**
     * Represents the event of the user ending to touch the screen.
     */
    public static final int TYPE_TOUCH_INTERACTION_END = 0x00200000;

    /**
     * Represents the event change in the system windows shown on the screen. This event type should
     * only be dispatched by the system.
     */
    public static final int TYPE_WINDOWS_CHANGED = 0x00400000;

    /**
     * Represents the event of a context click on a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_CONTEXT_CLICKED = 0x00800000;

    /**
     * Represents the event of the assistant currently reading the users screen context.
     */
    public static final int TYPE_ASSIST_READING_CONTEXT = 0x01000000;

    /**
     * Represents a change in the speech state defined by the speech state change types.
     * A change in the speech state occurs when an application wants to signal that it is either
     * speaking or listening for human speech.
     * This event helps avoid conflicts where two applications want to speak or one listens
     * when another speaks.
     * When sending this event, the sender should ensure that  the accompanying state change types
     * make sense. For example, the sender should not send
     * {@link #SPEECH_STATE_SPEAKING_START} and {@link #SPEECH_STATE_SPEAKING_END} together.
     * @see #SPEECH_STATE_SPEAKING_START
     * @see #SPEECH_STATE_SPEAKING_END
     * @see #SPEECH_STATE_LISTENING_START
     * @see #SPEECH_STATE_LISTENING_END
     * @see #getSpeechStateChangeTypes
     * @see #setSpeechStateChangeTypes
     */
    public static final int TYPE_SPEECH_STATE_CHANGE = 0x02000000;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event: The type of change is not
     * defined.
     */
    public static final int CONTENT_CHANGE_TYPE_UNDEFINED = 0x00000000;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * One or more content changes occurred in the the subtree rooted at the source node,
     * or the subtree's structure changed when a node was added or removed.
     */
    public static final int CONTENT_CHANGE_TYPE_SUBTREE = 0x00000001;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * The node's text changed.
     */
    public static final int CONTENT_CHANGE_TYPE_TEXT = 0x00000002;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * The node's content description changed.
     */
    public static final int CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION = 0x00000004;

    /**
     * Change type for {@link #TYPE_WINDOW_STATE_CHANGED} event:
     * The node's pane title changed.
     * <p>
     * If this makes the pane appear, {@link #CONTENT_CHANGE_TYPE_PANE_APPEARED} is sent
     * instead. If this makes the pane disappear, {@link #CONTENT_CHANGE_TYPE_PANE_DISAPPEARED}
     * is sent.
     *
     */
    public static final int CONTENT_CHANGE_TYPE_PANE_TITLE = 0x00000008;

    /**
     * Change type for {@link #TYPE_WINDOW_STATE_CHANGED} event:
     * The node has a pane title, and either just appeared or just was assigned a title when it
     * had none before.
     */
    public static final int CONTENT_CHANGE_TYPE_PANE_APPEARED = 0x00000010;

    /**
     * Change type for {@link #TYPE_WINDOW_STATE_CHANGED} event:
     * Can mean one of two slightly different things. The primary meaning is that the node has
     * a pane title, and was removed from the node hierarchy. It will also be sent if the pane
     * title is set to {@code null} after it contained a title.
     * No source will be returned if the node is no longer on the screen. To make the change more
     * clear for the user, the first entry in {@link #getText()} will return the value that would
     * have been returned by {@code getSource().getPaneTitle()}.
     */
    public static final int CONTENT_CHANGE_TYPE_PANE_DISAPPEARED = 0x00000020;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * state description of the node as returned by
     * {@link AccessibilityNodeInfo#getStateDescription} changed. If part of the state description
     * changes, the changed part can be put into event text. For example, if state description
     * changed from "on, wifi signal full" to "on, wifi three bars", "wifi three bars" can be put
     * into the event text.
     */
    public static final int CONTENT_CHANGE_TYPE_STATE_DESCRIPTION = 0x00000040;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * A drag has started while accessibility is enabled. This is either via an
     * AccessibilityAction, or via touch events. This is sent from the source that initiated the
     * drag.
     *
     * @see AccessibilityNodeInfo.AccessibilityAction#ACTION_DRAG_START
     */
    public static final int CONTENT_CHANGE_TYPE_DRAG_STARTED = 0x00000080;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * A drag in with accessibility enabled has ended. This means the content has been
     * successfully dropped. This is sent from the target that accepted the dragged content.
     *
     * @see AccessibilityNodeInfo.AccessibilityAction#ACTION_DRAG_DROP
     */
    public static final int CONTENT_CHANGE_TYPE_DRAG_DROPPED = 0x00000100;

    /**
     * Change type for {@link #TYPE_WINDOW_CONTENT_CHANGED} event:
     * A drag in with accessibility enabled has ended. This means the content has been
     * unsuccessfully dropped, the user has canceled the action via an AccessibilityAction, or
     * no drop has been detected within a timeout and the drag was automatically cancelled. This is
     * sent from the source that initiated the drag.
     *
     * @see AccessibilityNodeInfo.AccessibilityAction#ACTION_DRAG_CANCEL
     */
    public static final int CONTENT_CHANGE_TYPE_DRAG_CANCELLED = 0x0000200;

    /** Change type for {@link #TYPE_SPEECH_STATE_CHANGE} event: another service is speaking. */
    public static final int SPEECH_STATE_SPEAKING_START = 0x00000001;

    /**
     * Change type for {@link #TYPE_SPEECH_STATE_CHANGE} event: another service is no longer
     * speaking.
     */
    public static final int SPEECH_STATE_SPEAKING_END = 0x00000002;

    /**
     * Change type for {@link #TYPE_SPEECH_STATE_CHANGE} event: another service is listening to the
     * microphone.
     */
    public static final int SPEECH_STATE_LISTENING_START = 0x00000004;

    /**
     * Change type for {@link #TYPE_SPEECH_STATE_CHANGE} event: another service is no longer
     * listening to the microphone.
     */
    public static final int SPEECH_STATE_LISTENING_END = 0x00000008;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window was added.
     */
    public static final int WINDOWS_CHANGE_ADDED = 0x00000001;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * A window was removed.
     */
    public static final int WINDOWS_CHANGE_REMOVED = 0x00000002;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's title changed.
     */
    public static final int WINDOWS_CHANGE_TITLE = 0x00000004;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's bounds changed.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#R R}, this event implies the window's
     * region changed. It's also possible that region changed but bounds doesn't.
     * </p>
     */
    public static final int WINDOWS_CHANGE_BOUNDS = 0x00000008;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's layer changed.
     */
    public static final int WINDOWS_CHANGE_LAYER = 0x00000010;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's {@link AccessibilityWindowInfo#isActive()} changed.
     */
    public static final int WINDOWS_CHANGE_ACTIVE = 0x00000020;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's {@link AccessibilityWindowInfo#isFocused()} changed.
     */
    public static final int WINDOWS_CHANGE_FOCUSED = 0x00000040;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's {@link AccessibilityWindowInfo#isAccessibilityFocused()} changed.
     */
    public static final int WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED = 0x00000080;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's parent changed.
     */
    public static final int WINDOWS_CHANGE_PARENT = 0x00000100;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window's children changed.
     */
    public static final int WINDOWS_CHANGE_CHILDREN = 0x00000200;

    /**
     * Change type for {@link #TYPE_WINDOWS_CHANGED} event:
     * The window either entered or exited picture-in-picture mode.
     */
    public static final int WINDOWS_CHANGE_PIP = 0x00000400;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "WINDOWS_CHANGE_" }, value = {
            WINDOWS_CHANGE_ADDED,
            WINDOWS_CHANGE_REMOVED,
            WINDOWS_CHANGE_TITLE,
            WINDOWS_CHANGE_BOUNDS,
            WINDOWS_CHANGE_LAYER,
            WINDOWS_CHANGE_ACTIVE,
            WINDOWS_CHANGE_FOCUSED,
            WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED,
            WINDOWS_CHANGE_PARENT,
            WINDOWS_CHANGE_CHILDREN,
            WINDOWS_CHANGE_PIP
    })
    public @interface WindowsChangeTypes {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            flag = true,
            prefix = {"CONTENT_CHANGE_TYPE_"},
            value = {
                CONTENT_CHANGE_TYPE_UNDEFINED,
                CONTENT_CHANGE_TYPE_SUBTREE,
                CONTENT_CHANGE_TYPE_TEXT,
                CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
                CONTENT_CHANGE_TYPE_STATE_DESCRIPTION,
                CONTENT_CHANGE_TYPE_PANE_TITLE,
                CONTENT_CHANGE_TYPE_PANE_APPEARED,
                CONTENT_CHANGE_TYPE_PANE_DISAPPEARED,
                CONTENT_CHANGE_TYPE_DRAG_STARTED,
                CONTENT_CHANGE_TYPE_DRAG_DROPPED,
                CONTENT_CHANGE_TYPE_DRAG_CANCELLED,
            })
    public @interface ContentChangeTypes {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            flag = true,
            prefix = {"SPEECH_STATE_"},
            value = {
                SPEECH_STATE_SPEAKING_START,
                SPEECH_STATE_SPEAKING_END,
                SPEECH_STATE_LISTENING_START,
                SPEECH_STATE_LISTENING_END
            })
    public @interface SpeechStateChangeTypes {}

    /** @hide */
    @IntDef(
            flag = true,
            prefix = {"TYPE_"},
            value = {
                TYPE_VIEW_CLICKED,
                TYPE_VIEW_LONG_CLICKED,
                TYPE_VIEW_SELECTED,
                TYPE_VIEW_FOCUSED,
                TYPE_VIEW_TEXT_CHANGED,
                TYPE_WINDOW_STATE_CHANGED,
                TYPE_NOTIFICATION_STATE_CHANGED,
                TYPE_VIEW_HOVER_ENTER,
                TYPE_VIEW_HOVER_EXIT,
                TYPE_TOUCH_EXPLORATION_GESTURE_START,
                TYPE_TOUCH_EXPLORATION_GESTURE_END,
                TYPE_WINDOW_CONTENT_CHANGED,
                TYPE_VIEW_SCROLLED,
                TYPE_VIEW_TEXT_SELECTION_CHANGED,
                TYPE_ANNOUNCEMENT,
                TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
                TYPE_GESTURE_DETECTION_START,
                TYPE_GESTURE_DETECTION_END,
                TYPE_TOUCH_INTERACTION_START,
                TYPE_TOUCH_INTERACTION_END,
                TYPE_WINDOWS_CHANGED,
                TYPE_VIEW_CONTEXT_CLICKED,
                TYPE_ASSIST_READING_CONTEXT,
                TYPE_SPEECH_STATE_CHANGE
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

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
     * @see #TYPE_ANNOUNCEMENT
     * @see #TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
     * @see #TYPE_GESTURE_DETECTION_START
     * @see #TYPE_GESTURE_DETECTION_END
     * @see #TYPE_TOUCH_INTERACTION_START
     * @see #TYPE_TOUCH_INTERACTION_END
     * @see #TYPE_WINDOWS_CHANGED
     * @see #TYPE_VIEW_CONTEXT_CLICKED
     */
    public static final int TYPES_ALL_MASK = 0xFFFFFFFF;

    @UnsupportedAppUsage
    private @EventType int mEventType;
    private CharSequence mPackageName;
    private long mEventTime;
    int mMovementGranularity;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    int mAction;
    int mContentChangeTypes;
    int mWindowChangeTypes;
    int mSpeechStateChangeTypes;

    /**
     * The stack trace describing where this event originated from on the app side.
     * Only populated if {@link #DEBUG_ORIGIN} is enabled
     * Can be inspected(e.g. printed) from an
     * {@link android.accessibilityservice.AccessibilityService} to trace where particular events
     * are being dispatched from.
     *
     * @hide
     */
    public StackTraceElement[] originStackTrace = null;

    private ArrayList<AccessibilityRecord> mRecords;

    /**
     * Creates a new {@link AccessibilityEvent}.
     */
    public AccessibilityEvent() {
        if (DEBUG_ORIGIN) originStackTrace = Thread.currentThread().getStackTrace();
    }


    /**
     * Creates a new {@link AccessibilityEvent} with the given <code>eventType</code>.
     *
     * @param eventType The event type.
     */
    public AccessibilityEvent(int eventType) {
        mEventType = eventType;
        if (DEBUG_ORIGIN) originStackTrace = Thread.currentThread().getStackTrace();
    }

    /**
     * Copy constructor. Creates a new {@link AccessibilityEvent}, and this instance is initialized
     * from the given <code>event</code>.
     *
     * @param event The other event.
     */
    public AccessibilityEvent(@NonNull AccessibilityEvent event) {
        init(event);
    }

    /**
     * Initialize an event from another one.
     *
     * @param event The event to initialize from.
     */
    void init(AccessibilityEvent event) {
        super.init(event);
        mEventType = event.mEventType;
        mMovementGranularity = event.mMovementGranularity;
        mAction = event.mAction;
        mContentChangeTypes = event.mContentChangeTypes;
        mSpeechStateChangeTypes = event.mSpeechStateChangeTypes;
        mWindowChangeTypes = event.mWindowChangeTypes;
        mEventTime = event.mEventTime;
        mPackageName = event.mPackageName;
        if (event.mRecords != null) {
            final int recordCount = event.mRecords.size();
            mRecords = new ArrayList<>(recordCount);
            for (int i = 0; i < recordCount; i++) {
                final AccessibilityRecord record = event.mRecords.get(i);
                final AccessibilityRecord recordClone = new AccessibilityRecord(record);
                mRecords.add(recordClone);
            }
        }
        if (DEBUG_ORIGIN) originStackTrace = event.originStackTrace;
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
        final List<AccessibilityRecord> records = mRecords;
        if (records != null) {
            final int recordCount = records.size();
            for (int i = 0; i < recordCount; i++) {
                AccessibilityRecord record = records.get(i);
                record.setSealed(sealed);
            }
        }
    }

    /**
     * Gets the number of records contained in the event.
     *
     * @return The number of records.
     */
    public int getRecordCount() {
        return mRecords == null ? 0 : mRecords.size();
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
        if (mRecords == null) {
            mRecords = new ArrayList<AccessibilityRecord>();
        }
        mRecords.add(record);
    }

    /**
     * Gets the record at a given index.
     *
     * @param index The index.
     * @return The record at the specified index.
     */
    public AccessibilityRecord getRecord(int index) {
        if (mRecords == null) {
            throw new IndexOutOfBoundsException("Invalid index " + index + ", size is 0");
        }
        return mRecords.get(index);
    }

    /**
     * Gets the event type.
     *
     * @return The event type.
     */
    public @EventType int getEventType() {
        return mEventType;
    }

    /**
     * Gets the bit mask of change types signaled by a
     * {@link #TYPE_WINDOW_CONTENT_CHANGED} event or {@link #TYPE_WINDOW_STATE_CHANGED}. A single
     * event may represent multiple change types.
     *
     * @return The bit mask of change types. One or more of:
     *         <ul>
     *         <li>{@link #CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION}
     *         <li>{@link #CONTENT_CHANGE_TYPE_STATE_DESCRIPTION}
     *         <li>{@link #CONTENT_CHANGE_TYPE_SUBTREE}
     *         <li>{@link #CONTENT_CHANGE_TYPE_TEXT}
     *         <li>{@link #CONTENT_CHANGE_TYPE_PANE_TITLE}
     *         <li>{@link #CONTENT_CHANGE_TYPE_UNDEFINED}
     *         <li>{@link #CONTENT_CHANGE_TYPE_PANE_APPEARED}
     *         <li>{@link #CONTENT_CHANGE_TYPE_PANE_DISAPPEARED}
     *         </ul>
     */
    @ContentChangeTypes
    public int getContentChangeTypes() {
        return mContentChangeTypes;
    }

    private static String contentChangeTypesToString(int types) {
        return BitUtils.flagsToString(types, AccessibilityEvent::singleContentChangeTypeToString);
    }

    private static String singleContentChangeTypeToString(int type) {
        switch (type) {
            case CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION:
                return "CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION";
            case CONTENT_CHANGE_TYPE_STATE_DESCRIPTION:
                return "CONTENT_CHANGE_TYPE_STATE_DESCRIPTION";
            case CONTENT_CHANGE_TYPE_SUBTREE: return "CONTENT_CHANGE_TYPE_SUBTREE";
            case CONTENT_CHANGE_TYPE_TEXT: return "CONTENT_CHANGE_TYPE_TEXT";
            case CONTENT_CHANGE_TYPE_PANE_TITLE: return "CONTENT_CHANGE_TYPE_PANE_TITLE";
            case CONTENT_CHANGE_TYPE_UNDEFINED: return "CONTENT_CHANGE_TYPE_UNDEFINED";
            case CONTENT_CHANGE_TYPE_PANE_APPEARED: return "CONTENT_CHANGE_TYPE_PANE_APPEARED";
            case CONTENT_CHANGE_TYPE_PANE_DISAPPEARED:
                return "CONTENT_CHANGE_TYPE_PANE_DISAPPEARED";
            case CONTENT_CHANGE_TYPE_DRAG_STARTED: return "CONTENT_CHANGE_TYPE_DRAG_STARTED";
            case CONTENT_CHANGE_TYPE_DRAG_DROPPED: return "CONTENT_CHANGE_TYPE_DRAG_DROPPED";
            case CONTENT_CHANGE_TYPE_DRAG_CANCELLED: return "CONTENT_CHANGE_TYPE_DRAG_CANCELLED";
            default: return Integer.toHexString(type);
        }
    }

    /**
     * Sets the bit mask of node tree changes signaled by an
     * {@link #TYPE_WINDOW_CONTENT_CHANGED} event.
     *
     * @param changeTypes The bit mask of change types.
     * @throws IllegalStateException If called from an AccessibilityService.
     * @see #getContentChangeTypes()
     */
    public void setContentChangeTypes(@ContentChangeTypes int changeTypes) {
        enforceNotSealed();
        mContentChangeTypes = changeTypes;
    }

    /**
     * Gets the bit mask of the speech state signaled by a {@link #TYPE_SPEECH_STATE_CHANGE} event
     *
     * @see #SPEECH_STATE_SPEAKING_START
     * @see #SPEECH_STATE_SPEAKING_END
     * @see #SPEECH_STATE_LISTENING_START
     * @see #SPEECH_STATE_LISTENING_END
     */
    public int getSpeechStateChangeTypes() {
        return mSpeechStateChangeTypes;
    }

    private static String speechStateChangeTypesToString(int types) {
        return BitUtils.flagsToString(
                types, AccessibilityEvent::singleSpeechStateChangeTypeToString);
    }

    private static String singleSpeechStateChangeTypeToString(int type) {
        switch (type) {
            case SPEECH_STATE_SPEAKING_START:
                return "SPEECH_STATE_SPEAKING_START";
            case SPEECH_STATE_LISTENING_START:
                return "SPEECH_STATE_LISTENING_START";
            case SPEECH_STATE_SPEAKING_END:
                return "SPEECH_STATE_SPEAKING_END";
            case SPEECH_STATE_LISTENING_END:
                return "SPEECH_STATE_LISTENING_END";
            default:
                return Integer.toHexString(type);
        }
    }

    /**
     * Sets the bit mask of the speech state change types
     * signaled by a {@link #TYPE_SPEECH_STATE_CHANGE} event.
     * The sender is responsible for ensuring that  the state change types  make sense. For example,
     * the sender should not send
     * {@link #SPEECH_STATE_SPEAKING_START} and {@link #SPEECH_STATE_SPEAKING_END} together.
     *
     * @see #SPEECH_STATE_SPEAKING_START
     * @see #SPEECH_STATE_SPEAKING_END
     * @see #SPEECH_STATE_LISTENING_START
     * @see #SPEECH_STATE_LISTENING_END
     */
    public void setSpeechStateChangeTypes(@SpeechStateChangeTypes int state) {
        enforceNotSealed();
        mSpeechStateChangeTypes = state;
    }

    /**
     * Get the bit mask of change types signaled by a {@link #TYPE_WINDOWS_CHANGED} event. A
     * single event may represent multiple change types.
     *
     * @return The bit mask of change types.
     */
    @WindowsChangeTypes
    public int getWindowChanges() {
        return mWindowChangeTypes;
    }

    /** @hide  */
    public void setWindowChanges(@WindowsChangeTypes int changes) {
        mWindowChangeTypes = changes;
    }

    private static String windowChangeTypesToString(@WindowsChangeTypes int types) {
        return BitUtils.flagsToString(types, AccessibilityEvent::singleWindowChangeTypeToString);
    }

    private static String singleWindowChangeTypeToString(int type) {
        switch (type) {
            case WINDOWS_CHANGE_ADDED: return "WINDOWS_CHANGE_ADDED";
            case WINDOWS_CHANGE_REMOVED: return "WINDOWS_CHANGE_REMOVED";
            case WINDOWS_CHANGE_TITLE: return "WINDOWS_CHANGE_TITLE";
            case WINDOWS_CHANGE_BOUNDS: return "WINDOWS_CHANGE_BOUNDS";
            case WINDOWS_CHANGE_LAYER: return "WINDOWS_CHANGE_LAYER";
            case WINDOWS_CHANGE_ACTIVE: return "WINDOWS_CHANGE_ACTIVE";
            case WINDOWS_CHANGE_FOCUSED: return "WINDOWS_CHANGE_FOCUSED";
            case WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED:
                return "WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED";
            case WINDOWS_CHANGE_PARENT: return "WINDOWS_CHANGE_PARENT";
            case WINDOWS_CHANGE_CHILDREN: return "WINDOWS_CHANGE_CHILDREN";
            case WINDOWS_CHANGE_PIP: return "WINDOWS_CHANGE_PIP";
            default: return Integer.toHexString(type);
        }
    }

    /**
     * Sets the event type.
     *
     * <b>Note: An event must represent a single event type.</b>
     * @param eventType The event type.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEventType(@EventType int eventType) {
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
     * Sets the movement granularity that was traversed.
     *
     * @param granularity The granularity.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setMovementGranularity(int granularity) {
        enforceNotSealed();
        mMovementGranularity = granularity;
    }

    /**
     * Gets the movement granularity that was traversed.
     *
     * @return The granularity.
     */
    public int getMovementGranularity() {
        return mMovementGranularity;
    }

    /**
     * Sets the performed action that triggered this event.
     * <p>
     * Valid actions are defined in {@link AccessibilityNodeInfo}:
     * <ul>
     * <li>{@link AccessibilityNodeInfo#ACTION_ACCESSIBILITY_FOCUS}
     * <li>{@link AccessibilityNodeInfo#ACTION_CLEAR_ACCESSIBILITY_FOCUS}
     * <li>{@link AccessibilityNodeInfo#ACTION_CLEAR_FOCUS}
     * <li>{@link AccessibilityNodeInfo#ACTION_CLEAR_SELECTION}
     * <li>{@link AccessibilityNodeInfo#ACTION_CLICK}
     * <li>etc.
     * </ul>
     *
     * @param action The action.
     * @throws IllegalStateException If called from an AccessibilityService.
     * @see AccessibilityNodeInfo#performAction(int)
     */
    public void setAction(int action) {
        enforceNotSealed();
        mAction = action;
    }

    /**
     * Gets the performed action that triggered this event.
     *
     * @return The action.
     */
    public int getAction() {
        return mAction;
    }

    /**
     * Convenience method to obtain a {@link #TYPE_WINDOWS_CHANGED} event for a specific window and
     * change set.
     *
     * @param windowId The ID of the window that changed
     * @param windowChangeTypes The changes to populate
     * @return An instance of a TYPE_WINDOWS_CHANGED, populated with the requested fields and with
     *         importantForAccessibility set to {@code true}.
     *
     * @hide
     */
    public static AccessibilityEvent obtainWindowsChangedEvent(
            int windowId, int windowChangeTypes) {
        final AccessibilityEvent event = new AccessibilityEvent(TYPE_WINDOWS_CHANGED);
        event.setWindowId(windowId);
        event.setWindowChanges(windowChangeTypes);
        event.setImportantForAccessibility(true);
        return event;
    }

    /**
     * Instantiates a new AccessibilityEvent instance with its type property set.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityEvent()} instead.
     * @param eventType The event type.
     * @return An instance.
     */
    @Deprecated
    public static AccessibilityEvent obtain(int eventType) {
        AccessibilityEvent event = new AccessibilityEvent();
        event.setEventType(eventType);
        return event;
    }

    /**
     * Instantiates a new AccessibilityEvent instance.
     * The returned instance is initialized from the given
     * <code>event</code>.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityEvent()} instead.
     * @param event The other event.
     * @return An instance.
     */
    @Deprecated
    public static AccessibilityEvent obtain(AccessibilityEvent event) {
        AccessibilityEvent eventClone = new AccessibilityEvent();
        eventClone.init(event);
        return eventClone;
    }

    /**
     * Instantiates a new AccessibilityEvent instance.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityEvent()} instead.
     * @return An instance.
     */
    @Deprecated
    public static AccessibilityEvent obtain() {
        return new AccessibilityEvent();
    }

    /**
     * Previously would recycle an instance back to be reused.
     *
     * @deprecated Object pooling has been discontinued. Calling this function now will have
     * no effect.
     */
    @Override
    @Deprecated
    public void recycle() {}

    /**
     * Clears the state of this instance.
     *
     * @hide
     */
    @Override
    protected void clear() {
        super.clear();
        mEventType = 0;
        mMovementGranularity = 0;
        mAction = 0;
        mContentChangeTypes = 0;
        mWindowChangeTypes = 0;
        mSpeechStateChangeTypes = 0;
        mPackageName = null;
        mEventTime = 0;
        if (mRecords != null) {
            while (!mRecords.isEmpty()) {
                AccessibilityRecord record = mRecords.remove(0);
            }
        }
        if (DEBUG_ORIGIN) originStackTrace = null;
    }

    /**
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityEvent}.
     */
    public void initFromParcel(Parcel parcel) {
        mSealed = (parcel.readInt() == 1);
        mEventType = parcel.readInt();
        mMovementGranularity = parcel.readInt();
        mAction = parcel.readInt();
        mContentChangeTypes = parcel.readInt();
        mWindowChangeTypes = parcel.readInt();
        mSpeechStateChangeTypes = parcel.readInt();
        mPackageName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mEventTime = parcel.readLong();
        mConnectionId = parcel.readInt();
        readAccessibilityRecordFromParcel(this, parcel);

        // Read the records.
        final int recordCount = parcel.readInt();
        if (recordCount > 0) {
            mRecords = new ArrayList<>(recordCount);
            for (int i = 0; i < recordCount; i++) {
                AccessibilityRecord record = new AccessibilityRecord();
                readAccessibilityRecordFromParcel(record, parcel);
                record.mConnectionId = mConnectionId;
                mRecords.add(record);
            }
        }

        if (DEBUG_ORIGIN) {
            originStackTrace = new StackTraceElement[parcel.readInt()];
            for (int i = 0; i < originStackTrace.length; i++) {
                originStackTrace[i] = new StackTraceElement(
                        parcel.readString(),
                        parcel.readString(),
                        parcel.readString(),
                        parcel.readInt());
            }
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
        record.mScrollDeltaX =  parcel.readInt();
        record.mScrollDeltaY =  parcel.readInt();
        record.mMaxScrollX = parcel.readInt();
        record.mMaxScrollY =  parcel.readInt();
        record.mAddedCount = parcel.readInt();
        record.mRemovedCount = parcel.readInt();
        record.mClassName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mBeforeText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mParcelableData = parcel.readParcelable(null);
        parcel.readList(record.mText, null, java.lang.CharSequence.class);
        record.mSourceWindowId = parcel.readInt();
        record.mSourceNodeId = parcel.readLong();
        record.mSourceDisplayId = parcel.readInt();
        record.mSealed = (parcel.readInt() == 1);
    }

    /**
     * {@inheritDoc}
     */
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(isSealed() ? 1 : 0);
        parcel.writeInt(mEventType);
        parcel.writeInt(mMovementGranularity);
        parcel.writeInt(mAction);
        parcel.writeInt(mContentChangeTypes);
        parcel.writeInt(mWindowChangeTypes);
        parcel.writeInt(mSpeechStateChangeTypes);
        TextUtils.writeToParcel(mPackageName, parcel, 0);
        parcel.writeLong(mEventTime);
        parcel.writeInt(mConnectionId);
        writeAccessibilityRecordToParcel(this, parcel, flags);

        // Write the records.
        final int recordCount = getRecordCount();
        parcel.writeInt(recordCount);
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = mRecords.get(i);
            writeAccessibilityRecordToParcel(record, parcel, flags);
        }

        if (DEBUG_ORIGIN) {
            if (originStackTrace == null) originStackTrace = Thread.currentThread().getStackTrace();
            parcel.writeInt(originStackTrace.length);
            for (StackTraceElement element : originStackTrace) {
                parcel.writeString(element.getClassName());
                parcel.writeString(element.getMethodName());
                parcel.writeString(element.getFileName());
                parcel.writeInt(element.getLineNumber());
            }
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
        parcel.writeInt(record.mScrollDeltaX);
        parcel.writeInt(record.mScrollDeltaY);
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
        parcel.writeInt(record.mSourceDisplayId);
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
        if (!DEBUG_CONCISE_TOSTRING || mMovementGranularity != 0) {
            builder.append("; MovementGranularity: ").append(mMovementGranularity);
        }
        if (!DEBUG_CONCISE_TOSTRING || mAction != 0) {
            builder.append("; Action: ").append(mAction);
        }
        if (!DEBUG_CONCISE_TOSTRING || mContentChangeTypes != 0) {
            builder.append("; ContentChangeTypes: ").append(
                    contentChangeTypesToString(mContentChangeTypes));
        }
        if (!DEBUG_CONCISE_TOSTRING || mWindowChangeTypes != 0) {
            builder.append("; WindowChangeTypes: ").append(
                    windowChangeTypesToString(mWindowChangeTypes));
        }
        super.appendTo(builder);
        if (DEBUG || DEBUG_CONCISE_TOSTRING) {
            if (!DEBUG_CONCISE_TOSTRING) {
                builder.append("\n");
            }
            if (DEBUG) {
                builder.append("; SourceWindowId: 0x").append(Long.toHexString(mSourceWindowId));
                builder.append("; SourceNodeId: 0x").append(Long.toHexString(mSourceNodeId));
                builder.append("; SourceDisplayId: ").append(mSourceDisplayId);
            }
            for (int i = 0; i < getRecordCount(); i++) {
                builder.append("  Record ").append(i).append(":");
                getRecord(i).appendTo(builder).append("\n");
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
        if (eventType == TYPES_ALL_MASK) {
            return "TYPES_ALL_MASK";
        }
        StringBuilder builder = new StringBuilder();
        int eventTypeCount = 0;
        while (eventType != 0) {
            final int eventTypeFlag = 1 << Integer.numberOfTrailingZeros(eventType);
            eventType &= ~eventTypeFlag;

            if (eventTypeCount > 0) {
                builder.append(", ");
            }
            builder.append(singleEventTypeToString(eventTypeFlag));

            eventTypeCount++;
        }
        if (eventTypeCount > 1) {
            builder.insert(0, '[');
            builder.append(']');
        }
        return builder.toString();
    }

    private static String singleEventTypeToString(int eventType) {
        switch (eventType) {
            case TYPE_VIEW_CLICKED: return "TYPE_VIEW_CLICKED";
            case TYPE_VIEW_LONG_CLICKED: return "TYPE_VIEW_LONG_CLICKED";
            case TYPE_VIEW_SELECTED: return "TYPE_VIEW_SELECTED";
            case TYPE_VIEW_FOCUSED: return "TYPE_VIEW_FOCUSED";
            case TYPE_VIEW_TEXT_CHANGED: return "TYPE_VIEW_TEXT_CHANGED";
            case TYPE_WINDOW_STATE_CHANGED: return "TYPE_WINDOW_STATE_CHANGED";
            case TYPE_VIEW_HOVER_ENTER: return "TYPE_VIEW_HOVER_ENTER";
            case TYPE_VIEW_HOVER_EXIT: return "TYPE_VIEW_HOVER_EXIT";
            case TYPE_NOTIFICATION_STATE_CHANGED: return "TYPE_NOTIFICATION_STATE_CHANGED";
            case TYPE_TOUCH_EXPLORATION_GESTURE_START: {
                return "TYPE_TOUCH_EXPLORATION_GESTURE_START";
            }
            case TYPE_TOUCH_EXPLORATION_GESTURE_END: return "TYPE_TOUCH_EXPLORATION_GESTURE_END";
            case TYPE_WINDOW_CONTENT_CHANGED: return "TYPE_WINDOW_CONTENT_CHANGED";
            case TYPE_VIEW_TEXT_SELECTION_CHANGED: return "TYPE_VIEW_TEXT_SELECTION_CHANGED";
            case TYPE_VIEW_SCROLLED: return "TYPE_VIEW_SCROLLED";
            case TYPE_ANNOUNCEMENT: return "TYPE_ANNOUNCEMENT";
            case TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "TYPE_VIEW_ACCESSIBILITY_FOCUSED";
            case TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                return "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED";
            }
            case TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY: {
                return "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY";
            }
            case TYPE_GESTURE_DETECTION_START: return "TYPE_GESTURE_DETECTION_START";
            case TYPE_GESTURE_DETECTION_END: return "TYPE_GESTURE_DETECTION_END";
            case TYPE_TOUCH_INTERACTION_START: return "TYPE_TOUCH_INTERACTION_START";
            case TYPE_TOUCH_INTERACTION_END: return "TYPE_TOUCH_INTERACTION_END";
            case TYPE_WINDOWS_CHANGED: return "TYPE_WINDOWS_CHANGED";
            case TYPE_VIEW_CONTEXT_CLICKED: return "TYPE_VIEW_CONTEXT_CLICKED";
            case TYPE_ASSIST_READING_CONTEXT: return "TYPE_ASSIST_READING_CONTEXT";
            case TYPE_SPEECH_STATE_CHANGE: return "TYPE_SPEECH_STATE_CHANGE";
            default: return Integer.toHexString(eventType);
        }
    }

    /**
     * @see Parcelable.Creator
     */
    public static final @android.annotation.NonNull Parcelable.Creator<AccessibilityEvent> CREATOR =
            new Parcelable.Creator<AccessibilityEvent>() {
        public AccessibilityEvent createFromParcel(Parcel parcel) {
            AccessibilityEvent event = new AccessibilityEvent();
            event.initFromParcel(parcel);
            return event;
        }

        public AccessibilityEvent[] newArray(int size) {
            return new AccessibilityEvent[size];
        }
    };
}
