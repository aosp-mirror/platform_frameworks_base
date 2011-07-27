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

import com.android.internal.os.HandlerCaller;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * An accessibility service runs in the background and receives callbacks by the system
 * when {@link AccessibilityEvent}s are fired. Such events denote some state transition
 * in the user interface, for example, the focus has changed, a button has been clicked,
 * etc. Such a service can optionally request the capability for querying the content
 * of the active window. Development of an accessibility service requires extends this
 * class and implements its abstract methods.
 * <p>
 * <strong>Lifecycle</strong>
 * </p>
 * <p>
 * The lifecycle of an accessibility service is managed exclusively by the system and
 * follows the established service life cycle. Additionally, starting or stopping an
 * accessibility service is triggered exclusively by an explicit user action through
 * enabling or disabling it in the device settings. After the system binds to a service it
 * calls {@link AccessibilityService#onServiceConnected()}. This method can be
 * overriden by clients that want to perform post binding setup.
 * </p>
 * <p>
 * <strong>Declaration</strong>
 * </p>
 * <p>
 * An accessibility is declared as any other service in an AndroidManifest.xml but it
 * must also specify that it handles the "android.accessibilityservice.AccessibilityService"
 * {@link android.content.Intent}. Failure to declare this intent will cause the system to
 * ignore the accessibility service. Following is an example declaration:
 * </p>
 * <p>
 * <code>
 * <pre>
 *   &lt;service android:name=".MyAccessibilityService"&gt;
 *     &lt;intent-filter&gt;
 *       &lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;
 *     &lt;/intent-filter&gt;
 *     . . .
 *   &lt;/service&gt;
 * </pre>
 * </code>
 * </p>
 * <p>
 * <strong>Configuration</strong>
 * </p>
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
 * <p>
 * <code>
 * <pre>
 *   &lt;service android:name=".MyAccessibilityService"&gt;
 *     &lt;intent-filter&gt;
 *       &lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibilityservice" /&gt;
 *   &lt;/service&gt;
 * </pre>
 * </code>
 * </p>
 * <p>
 * <strong>Note:</strong>This approach enables setting all properties.
 * </p>
 * <p>
 * For more details refer to {@link #SERVICE_META_DATA} and
 * <code>&lt;{@link android.R.styleable#AccessibilityService accessibility-service}&gt;</code>..
 * </p>
 * </li>
 * <li>
 * Calling {@link AccessibilityService#setServiceInfo(AccessibilityServiceInfo)}. Note
 * that this method can be called any time to dynamically change the service configuration.
 * <p>
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
 * <p>
 * <strong>Retrieving window content</strong>
 * </p>
 * <p>
 * An service can specify in its declaration that it can retrieve the active window
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
 * <p>
 * <strong>Note</strong>An accessibility service may have requested to be notified for
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
 * that it is possible to have a retrieval method failing event adopting the strategy
 * specified in the previous bullet because the accessibility event dispatch is asynchronous
 * and crosses process boundaries.
 * </li>
 * </ul>
 * </p>
 * <p>
 * <b>Notification strategy</b>
 * </p>
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
 * <p>
 * <b>Event types</b>
 * </p>
 * {@link AccessibilityEvent#TYPE_VIEW_CLICKED}
 * {@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED}
 * {@link AccessibilityEvent#TYPE_VIEW_FOCUSED}
 * {@link AccessibilityEvent#TYPE_VIEW_SELECTED}
 * {@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED}
 * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}
 * {@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED}
 * {@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_START}
 * {@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END}
 * {@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER}
 * {@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT}
 * {@link AccessibilityEvent#TYPE_VIEW_SCROLLED}
 * {@link AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED}
 * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED}
 * <p>
 * <b>Feedback types</b>
 * <p>
 * {@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}
 * {@link AccessibilityServiceInfo#FEEDBACK_HAPTIC}
 * {@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}
 * {@link AccessibilityServiceInfo#FEEDBACK_VISUAL}
 * {@link AccessibilityServiceInfo#FEEDBACK_GENERIC}
 *
 * @see AccessibilityEvent
 * @see AccessibilityServiceInfo
 * @see android.view.accessibility.AccessibilityManager
 *
 * <strong>Note:</strong> The event notification timeout is useful to avoid propagating
 * events to the client too frequently since this is accomplished via an expensive
 * interprocess call. One can think of the timeout as a criteria to determine when
 * event generation has settled down.
 */
public abstract class AccessibilityService extends Service {
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
     * <p>
     * <code>
     * <pre>
     *   &lt;accessibility-service
     *     android:accessibilityEventTypes="typeViewClicked|typeViewFocused"
     *     android:packageNames="foo.bar, foo.baz"
     *     android:accessibilityFeedbackType="feedbackSpoken"
     *     android:notificationTimeout="100"
     *     android:accessibilityFlags="flagDefault"
     *     android:settingsActivity="foo.bar.TestBackActivity"
     *     android:canRetrieveWindowContent="true"
     *     . . .
     *   /&gt;
     * </pre>
     * </code>
     * </p>
     */
    public static final String SERVICE_META_DATA = "android.accessibilityservice";

    private static final String LOG_TAG = "AccessibilityService";

    private AccessibilityServiceInfo mInfo;

    IAccessibilityServiceConnection mConnection;

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
        if (mInfo != null && mConnection != null) {
            try {
                mConnection.setServiceInfo(mInfo);
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
        return new IEventListenerWrapper(this);
    }

    /**
     * Implements the internal {@link IEventListener} interface to convert
     * incoming calls to it back to calls on an {@link AccessibilityService}.
     */
    class IEventListenerWrapper extends IEventListener.Stub
            implements HandlerCaller.Callback {

        private static final int DO_SET_SET_CONNECTION = 10;
        private static final int DO_ON_INTERRUPT = 20;
        private static final int DO_ON_ACCESSIBILITY_EVENT = 30;

        private final HandlerCaller mCaller;

        private final AccessibilityService mTarget;

        public IEventListenerWrapper(AccessibilityService context) {
            mTarget = context;
            mCaller = new HandlerCaller(context, this);
        }

        public void setConnection(IAccessibilityServiceConnection connection) {
            Message message = mCaller.obtainMessageO(DO_SET_SET_CONNECTION, connection);
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

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ON_ACCESSIBILITY_EVENT :
                    AccessibilityEvent event = (AccessibilityEvent) message.obj;
                    if (event != null) {
                        mTarget.onAccessibilityEvent(event);
                        event.recycle();
                    }
                    return;
                case DO_ON_INTERRUPT :
                    mTarget.onInterrupt();
                    return;
                case DO_SET_SET_CONNECTION :
                    mConnection = ((IAccessibilityServiceConnection) message.obj);
                    mTarget.onServiceConnected();
                    return;
                default :
                    Log.w(LOG_TAG, "Unknown message type " + message.what);
            }
        }
    }
}
