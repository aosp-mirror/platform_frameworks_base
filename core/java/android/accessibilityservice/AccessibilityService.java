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

package android.accessibilityservice;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.os.HandlerCaller;

/**
 * An accessibility service runs in the background and receives callbacks by the system
 * when {@link AccessibilityEvent}s are fired. Such events denote some state transition
 * in the user interface, for example, the focus has changed, a button has been clicked,
 * etc. Such a service can optionally request the capability for querying the content
 * of the active window. Development of an accessibility service requires extending this
 * class and implementing its abstract methods.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating AccessibilityServices, read the
 * <a href="{@docRoot}guide/topics/ui/accessibility/index.html">Accessibility</a>
 * developer guide.</p>
 * </div>
 *
 * <h3>Lifecycle</h3>
 * <p>
 * The lifecycle of an accessibility service is managed exclusively by the system and
 * follows the established service life cycle. Additionally, starting or stopping an
 * accessibility service is triggered exclusively by an explicit user action through
 * enabling or disabling it in the device settings. After the system binds to a service it
 * calls {@link AccessibilityService#onServiceConnected()}. This method can be
 * overriden by clients that want to perform post binding setup.
 * </p>
 * <h3>Declaration</h3>
 * <p>
 * An accessibility is declared as any other service in an AndroidManifest.xml but it
 * must also specify that it handles the "android.accessibilityservice.AccessibilityService"
 * {@link android.content.Intent}. Failure to declare this intent will cause the system to
 * ignore the accessibility service. Additionally an accessibility service must request the
 * {@link android.Manifest.permission#BIND_ACCESSIBILITY_SERVICE} permission to ensure
 * that only the system
 * can bind to it. Failure to declare this intent will cause the system to ignore the
 * accessibility service. Following is an example declaration:
 * </p>
 * <pre> &lt;service android:name=".MyAccessibilityService"
 *         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;
 *     &lt;/intent-filter&gt;
 *     . . .
 * &lt;/service&gt;</pre>
 * <h3>Configuration</h3>
 * <p>
 * An accessibility service can be configured to receive specific types of accessibility events,
 * listen only to specific packages, get events from each type only once in a given time frame,
 * retrieve window content, specify a settings activity, etc.
 * </p>
 * <p>
 * There are two approaches for configuring an accessibility service:
 * </p>
 * <ul>
 * <li>
 * Providing a {@link #SERVICE_META_DATA meta-data} entry in the manifest when declaring
 * the service. A service declaration with a meta-data tag is presented below:
 * <pre> &lt;service android:name=".MyAccessibilityService"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibilityservice" /&gt;
 * &lt;/service&gt;</pre>
 * <p class="note">
 * <strong>Note:</strong> This approach enables setting all properties.
 * </p>
 * <p>
 * For more details refer to {@link #SERVICE_META_DATA} and
 * <code>&lt;{@link android.R.styleable#AccessibilityService accessibility-service}&gt;</code>.
 * </p>
 * </li>
 * <li>
 * Calling {@link AccessibilityService#setServiceInfo(AccessibilityServiceInfo)}. Note
 * that this method can be called any time to dynamically change the service configuration.
 * <p class="note">
 * <strong>Note:</strong> This approach enables setting only dynamically configurable properties:
 * {@link AccessibilityServiceInfo#eventTypes},
 * {@link AccessibilityServiceInfo#feedbackType},
 * {@link AccessibilityServiceInfo#flags},
 * {@link AccessibilityServiceInfo#notificationTimeout},
 * {@link AccessibilityServiceInfo#packageNames}
 * </p>
 * <p>
 * For more details refer to {@link AccessibilityServiceInfo}.
 * </p>
 * </li>
 * </ul>
 * <h3>Retrieving window content</h3>
 * <p>
 * A service can specify in its declaration that it can retrieve the active window
 * content which is represented as a tree of {@link AccessibilityNodeInfo}. Note that
 * declaring this capability requires that the service declares its configuration via
 * an XML resource referenced by {@link #SERVICE_META_DATA}.
 * </p>
 * <p>
 * For security purposes an accessibility service can retrieve only the content of the
 * currently active window. The currently active window is defined as the window from
 * which was fired the last event of the following types:
 * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED},
 * {@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER},
 * {@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT},
 * In other words, the last window that was shown or the last window that the user has touched
 * during touch exploration.
 * </p>
 * <p>
 * The entry point for retrieving window content is through calling
 * {@link AccessibilityEvent#getSource() AccessibilityEvent.getSource()} of the last received
 * event of the above types or a previous event from the same window
 * (see {@link AccessibilityEvent#getWindowId() AccessibilityEvent.getWindowId()}). Invoking
 * this method will return an {@link AccessibilityNodeInfo} that can be used to traverse the
 * window content which represented as a tree of such objects.
 * </p>
 * <p class="note">
 * <strong>Note</strong> An accessibility service may have requested to be notified for
 * a subset of the event types, thus be unaware that the active window has changed. Therefore
 * accessibility service that would like to retrieve window content should:
 * <ul>
 * <li>
 * Register for all event types with no notification timeout and keep track for the active
 * window by calling {@link AccessibilityEvent#getWindowId()} of the last received event and
 * compare this with the {@link AccessibilityNodeInfo#getWindowId()} before calling retrieval
 * methods on the latter.
 * </li>
 * <li>
 * Prepare that a retrieval method on {@link AccessibilityNodeInfo} may fail since the
 * active window has changed and the service did not get the accessibility event yet. Note
 * that it is possible to have a retrieval method failing even adopting the strategy
 * specified in the previous bullet because the accessibility event dispatch is asynchronous
 * and crosses process boundaries.
 * </li>
 * </ul>
 * </p>
 * <h3>Notification strategy</h3>
 * <p>
 * For each feedback type only one accessibility service is notified. Services are notified
 * in the order of registration. Hence, if two services are registered for the same
 * feedback type in the same package the first one wins. It is possible however, to
 * register a service as the default one for a given feedback type. In such a case this
 * service is invoked if no other service was interested in the event. In other words, default
 * services do not compete with other services and are notified last regardless of the
 * registration order. This enables "generic" accessibility services that work reasonably
 * well with most applications to coexist with "polished" ones that are targeted for
 * specific applications.
 * </p>
 * <p class="note">
 * <strong>Note:</strong> The event notification timeout is useful to avoid propagating
 * events to the client too frequently since this is accomplished via an expensive
 * interprocess call. One can think of the timeout as a criteria to determine when
 * event generation has settled down.</p>
 * <h3>Event types</h3>
 * <ul>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_CLICKED}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_FOCUSED}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_SELECTED}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED}
 * <li>{@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}
 * <li>{@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED}
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_START}
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_SCROLLED}
 * <li>{@link AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED}
 * <li>{@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED}
 * </ul>
 * <h3>Feedback types</h3>
 * <ul>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_HAPTIC}
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_VISUAL}
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_GENERIC}
 * </ul>
 * @see AccessibilityEvent
 * @see AccessibilityServiceInfo
 * @see android.view.accessibility.AccessibilityManager
 */
public abstract class AccessibilityService extends Service {

    /**
     * The user has performed a swipe up gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_UP = 1;

    /**
     * The user has performed a swipe down gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_DOWN = 2;

    /**
     * The user has performed a swipe left gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_LEFT = 3;

    /**
     * The user has performed a swipe right gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_RIGHT = 4;

    /**
     * The user has performed a swipe left and right gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_LEFT_AND_RIGHT = 5;

    /**
     * The user has performed a swipe right and left gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_RIGHT_AND_LEFT = 6;

    /**
     * The user has performed a swipe up and down gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_UP_AND_DOWN = 7;

    /**
     * The user has performed a swipe down and up gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_DOWN_AND_UP = 8;

    /**
     * The user has performed a left and up gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_LEFT_AND_UP = 9;

    /**
     * The user has performed a left and down gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_LEFT_AND_DOWN = 10;

    /**
     * The user has performed a right and up gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_RIGHT_AND_UP = 11;

    /**
     * The user has performed a right and down gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_RIGHT_AND_DOWN = 12;

    /**
     * The user has performed an up and left gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_UP_AND_LEFT = 13;

    /**
     * The user has performed an up and right gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_UP_AND_RIGHT = 14;

    /**
     * The user has performed an down and left gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_DOWN_AND_LEFT = 15;

    /**
     * The user has performed an down and right gesture on the touch screen.
     */
    public static final int GESTURE_SWIPE_DOWN_AND_RIGHT = 16;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.accessibilityservice.AccessibilityService";

    /**
     * Name under which an AccessibilityService component publishes information
     * about itself. This meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#AccessibilityService accessibility-service}&gt;</code>
     * tag. This is a a sample XML file configuring an accessibility service:
     * <pre> &lt;accessibility-service
     *     android:accessibilityEventTypes="typeViewClicked|typeViewFocused"
     *     android:packageNames="foo.bar, foo.baz"
     *     android:accessibilityFeedbackType="feedbackSpoken"
     *     android:notificationTimeout="100"
     *     android:accessibilityFlags="flagDefault"
     *     android:settingsActivity="foo.bar.TestBackActivity"
     *     android:canRetrieveWindowContent="true"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.accessibilityservice";

    /**
     * Action to go back.
     */
    public static final int GLOBAL_ACTION_BACK = 1;

    /**
     * Action to go home.
     */
    public static final int GLOBAL_ACTION_HOME = 2;

    /**
     * Action to open the recent apps.
     */
    public static final int GLOBAL_ACTION_RECENTS = 3;

    /**
     * Action to open the notifications.
     */
    public static final int GLOBAL_ACTION_NOTIFICATIONS = 4;

    /**
     * Action to open the quick settings.
     */
    public static final int GLOBAL_ACTION_QUICK_SETTINGS = 5;

    private static final String LOG_TAG = "AccessibilityService";

    interface Callbacks {
        public void onAccessibilityEvent(AccessibilityEvent event);
        public void onInterrupt();
        public void onServiceConnected();
        public void onSetConnectionId(int connectionId);
        public boolean onGesture(int gestureId);
    }

    private int mConnectionId;

    private AccessibilityServiceInfo mInfo;

    /**
     * Callback for {@link android.view.accessibility.AccessibilityEvent}s.
     *
     * @param event An event.
     */
    public abstract void onAccessibilityEvent(AccessibilityEvent event);

    /**
     * Callback for interrupting the accessibility feedback.
     */
    public abstract void onInterrupt();

    /**
     * This method is a part of the {@link AccessibilityService} lifecycle and is
     * called after the system has successfully bound to the service. If is
     * convenient to use this method for setting the {@link AccessibilityServiceInfo}.
     *
     * @see AccessibilityServiceInfo
     * @see #setServiceInfo(AccessibilityServiceInfo)
     */
    protected void onServiceConnected() {

    }

    /**
     * Called by the system when the user performs a specific gesture on the
     * touch screen.
     *
     * <strong>Note:</strong> To receive gestures an accessibility service must
     * request that the device is in touch exploration mode by setting the
     * {@link android.accessibilityservice.AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE}
     * flag.
     *
     * @param gestureId The unique id of the performed gesture.
     *
     * @return Whether the gesture was handled.
     *
     * @see #GESTURE_SWIPE_UP
     * @see #GESTURE_SWIPE_UP_AND_LEFT
     * @see #GESTURE_SWIPE_UP_AND_DOWN
     * @see #GESTURE_SWIPE_UP_AND_RIGHT
     * @see #GESTURE_SWIPE_DOWN
     * @see #GESTURE_SWIPE_DOWN_AND_LEFT
     * @see #GESTURE_SWIPE_DOWN_AND_UP
     * @see #GESTURE_SWIPE_DOWN_AND_RIGHT
     * @see #GESTURE_SWIPE_LEFT
     * @see #GESTURE_SWIPE_LEFT_AND_UP
     * @see #GESTURE_SWIPE_LEFT_AND_RIGHT
     * @see #GESTURE_SWIPE_LEFT_AND_DOWN
     * @see #GESTURE_SWIPE_RIGHT
     * @see #GESTURE_SWIPE_RIGHT_AND_UP
     * @see #GESTURE_SWIPE_RIGHT_AND_LEFT
     * @see #GESTURE_SWIPE_RIGHT_AND_DOWN
     */
    protected boolean onGesture(int gestureId) {
        return false;
    }

    /**
     * Gets the root node in the currently active window if this service
     * can retrieve window content.
     *
     * @return The root node if this service can retrieve window content.
     */
    public AccessibilityNodeInfo getRootInActiveWindow() {
        return AccessibilityInteractionClient.getInstance().getRootInActiveWindow(mConnectionId);
    }

    /**
     * Performs a global action. Such an action can be performed
     * at any moment regardless of the current application or user
     * location in that application. For example going back, going
     * home, opening recents, etc.
     *
     * @param action The action to perform.
     * @return Whether the action was successfully performed.
     *
     * @see #GLOBAL_ACTION_BACK
     * @see #GLOBAL_ACTION_HOME
     * @see #GLOBAL_ACTION_NOTIFICATIONS
     * @see #GLOBAL_ACTION_RECENTS
     */
    public final boolean performGlobalAction(int action) {
        IAccessibilityServiceConnection connection =
            AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                return connection.performGlobalAction(action);
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while calling performGlobalAction", re);
            }
        }
        return false;
    }

    /**
     * Gets the an {@link AccessibilityServiceInfo} describing this
     * {@link AccessibilityService}. This method is useful if one wants
     * to change some of the dynamically configurable properties at
     * runtime.
     *
     * @return The accessibility service info.
     *
     * @see AccessibilityNodeInfo
     */
    public final AccessibilityServiceInfo getServiceInfo() {
        IAccessibilityServiceConnection connection =
            AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                return connection.getServiceInfo();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while getting AccessibilityServiceInfo", re);
            }
        }
        return null;
    }

    /**
     * Sets the {@link AccessibilityServiceInfo} that describes this service.
     * <p>
     * Note: You can call this method any time but the info will be picked up after
     *       the system has bound to this service and when this method is called thereafter.
     *
     * @param info The info.
     */
    public final void setServiceInfo(AccessibilityServiceInfo info) {
        mInfo = info;
        sendServiceInfo();
    }

    /**
     * Sets the {@link AccessibilityServiceInfo} for this service if the latter is
     * properly set and there is an {@link IAccessibilityServiceConnection} to the
     * AccessibilityManagerService.
     */
    private void sendServiceInfo() {
        IAccessibilityServiceConnection connection =
            AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (mInfo != null && connection != null) {
            try {
                connection.setServiceInfo(mInfo);
                mInfo = null;
                AccessibilityInteractionClient.getInstance().clearCache();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while setting AccessibilityServiceInfo", re);
            }
        }
    }

    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IAccessibilityServiceClientWrapper(this, getMainLooper(), new Callbacks() {
            @Override
            public void onServiceConnected() {
                AccessibilityService.this.onServiceConnected();
            }

            @Override
            public void onInterrupt() {
                AccessibilityService.this.onInterrupt();
            }

            @Override
            public void onAccessibilityEvent(AccessibilityEvent event) {
                AccessibilityService.this.onAccessibilityEvent(event);
            }

            @Override
            public void onSetConnectionId( int connectionId) {
                mConnectionId = connectionId;
            }

            @Override
            public boolean onGesture(int gestureId) {
                return AccessibilityService.this.onGesture(gestureId);
            }
        });
    }

    /**
     * Implements the internal {@link IAccessibilityServiceClient} interface to convert
     * incoming calls to it back to calls on an {@link AccessibilityService}.
     */
    static class IAccessibilityServiceClientWrapper extends IAccessibilityServiceClient.Stub
            implements HandlerCaller.Callback {

        static final int NO_ID = -1;

        private static final int DO_SET_SET_CONNECTION = 10;
        private static final int DO_ON_INTERRUPT = 20;
        private static final int DO_ON_ACCESSIBILITY_EVENT = 30;
        private static final int DO_ON_GESTURE = 40;

        private final HandlerCaller mCaller;

        private final Callbacks mCallback;

        public IAccessibilityServiceClientWrapper(Context context, Looper looper,
                Callbacks callback) {
            mCallback = callback;
            mCaller = new HandlerCaller(context, looper, this);
        }

        public void setConnection(IAccessibilityServiceConnection connection, int connectionId) {
            Message message = mCaller.obtainMessageIO(DO_SET_SET_CONNECTION, connectionId,
                    connection);
            mCaller.sendMessage(message);
        }

        public void onInterrupt() {
            Message message = mCaller.obtainMessage(DO_ON_INTERRUPT);
            mCaller.sendMessage(message);
        }

        public void onAccessibilityEvent(AccessibilityEvent event) {
            Message message = mCaller.obtainMessageO(DO_ON_ACCESSIBILITY_EVENT, event);
            mCaller.sendMessage(message);
        }

        public void onGesture(int gestureId) {
            Message message = mCaller.obtainMessageI(DO_ON_GESTURE, gestureId);
            mCaller.sendMessage(message);
        }

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ON_ACCESSIBILITY_EVENT :
                    AccessibilityEvent event = (AccessibilityEvent) message.obj;
                    if (event != null) {
                        AccessibilityInteractionClient.getInstance().onAccessibilityEvent(event);
                        mCallback.onAccessibilityEvent(event);
                        event.recycle();
                    }
                    return;
                case DO_ON_INTERRUPT :
                    mCallback.onInterrupt();
                    return;
                case DO_SET_SET_CONNECTION :
                    final int connectionId = message.arg1;
                    IAccessibilityServiceConnection connection =
                        (IAccessibilityServiceConnection) message.obj;
                    if (connection != null) {
                        AccessibilityInteractionClient.getInstance().addConnection(connectionId,
                                connection);
                        mCallback.onSetConnectionId(connectionId);
                        mCallback.onServiceConnected();
                    } else {
                        AccessibilityInteractionClient.getInstance().removeConnection(connectionId);
                        mCallback.onSetConnectionId(AccessibilityInteractionClient.NO_ID);
                    }
                    return;
                case DO_ON_GESTURE :
                    final int gestureId = message.arg1;
                    mCallback.onGesture(gestureId);
                    return;
                default :
                    Log.w(LOG_TAG, "Unknown message type " + message.what);
            }
        }
    }
}
