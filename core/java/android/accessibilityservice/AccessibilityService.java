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
 * etc.
 * <p>
 * An accessibility service extends this class and implements its abstract methods. Such
 * a service is declared as any other service in an AndroidManifest.xml but it must also
 * specify that it handles the "android.accessibilityservice.AccessibilityService"
 * {@link android.content.Intent}. Following is an example of such a declaration:
 * <p>
 * <code>
 * &lt;service android:name=".MyAccessibilityService"&gt;<br>
 * &nbsp;&nbsp;&lt;intent-filter&gt;<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;<br>
 * &nbsp;&nbsp;&lt;/intent-filter&gt;<br>
 * &lt;/service&gt;<br>
 * </code>
 * </p>
 * <p>
 * The lifecycle of an accessibility service is managed exclusively by the system. Starting
 * or stopping an accessibility service is triggered by an explicit user action through
 * enabling or disabling it in the device settings. After the system binds to a service it
 * calls {@link AccessibilityService#onServiceConnected()}. This method can be
 * overriden by clients that want to perform post binding setup.
 * </p>
 * <p>
 * An accessibility service can be configured to receive specific types of accessibility events,
 * listen only to specific packages, get events from each type only once in a given time frame,
 * retrieve window content, specify a settings activity, etc.
 * </p>
 * There are two approaches for configuring an accessibility service:
 * <ul>
 *   <li>
 *     Providing a {@link #SERVICE_META_DATA meta-data} entry in the manifest when declaring
 *     the service. A service declaration with a meta-data tag is presented below:
 *     <p>
 *       <code>
 *         &lt;service android:name=".MyAccessibilityService"&gt;<br>
 *         &nbsp;&nbsp;&lt;intent-filter&gt;<br>
 *         &nbsp;&nbsp;&nbsp;&nbsp;&lt;action android:name="android.accessibilityservice.AccessibilityService" /&gt;<br>
 *         &nbsp;&nbsp;&lt;/intent-filter&gt;<br>
 *         &nbsp;&nbsp;&lt;meta-data android:name="android.accessibilityservice.as" android:resource="@xml/accessibilityservice" /&gt;<br>
 *         &lt;/service&gt;<br>
 *       </code>
 *     </p>
 *     <p>
 *     <strong>
 *       This approach enables setting all accessibility service properties.
 *     </strong>
 *     </p>
 *     <p>
 *       For more details refer to {@link #SERVICE_META_DATA}.
 *     </p>
 *   </li>
 *   <li>
 *     Calling {@link AccessibilityService#setServiceInfo(AccessibilityServiceInfo)}. Note
 *     that this method can be called any time to change the service configuration.<br>
 *     <p>
 *     <strong>
 *       This approach enables setting only dynamically configurable accessibility
 *       service properties:
 *       {@link AccessibilityServiceInfo#eventTypes},
 *       {@link AccessibilityServiceInfo#feedbackType},
 *       {@link AccessibilityServiceInfo#flags},
 *       {@link AccessibilityServiceInfo#notificationTimeout},
 *       {@link AccessibilityServiceInfo#packageNames}
 *     </strong>
 *     </p>
 *     <p>
 *       For more details refer to {@link AccessibilityServiceInfo}.
 *     </p>
 *   </li>
 * </ul>
 * <p>
 * An accessibility service can be registered for events in specific packages to provide a
 * specific type of feedback and is notified with a certain timeout after the last event
 * of interest has been fired.
 * <p>
 * <b>Notification strategy</b>
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
 * <p>
 * <b>Event types</b>
 * <p>
 * {@link AccessibilityEvent#TYPE_VIEW_CLICKED}
 * {@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED}
 * {@link AccessibilityEvent#TYPE_VIEW_FOCUSED}
 * {@link AccessibilityEvent#TYPE_VIEW_SELECTED}
 * {@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED}
 * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}
 * {@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED}
 *  <p>
 *  <b>Feedback types</b>
 *  <p>
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
 * Note: The event notification timeout is useful to avoid propagating events to the client
 *       too frequently since this is accomplished via an expensive interprocess call.
 *       One can think of the timeout as a criteria to determine when event generation has
 *       settled down.
 */
public abstract class AccessibilityService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.accessibilityservice.AccessibilityService";

    /**
     * Name under which an AccessibilityService component publishes information
     * about itself. This meta-data must reference an XML resource containing
     * an
     * <code>&lt;{@link android.R.styleable#AccessibilityService accessibility-service}&gt;</code>
     * tag. This is a a sample XML file configuring an accessibility service:
     * <p>
     * <code>
     *   &lt;?xml version="1.0" encoding="utf-8"?&gt;<br>
     *   &lt;accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"<br>
     *   &nbsp;&nbsp;android:accessibilityEventTypes="typeViewClicked|typeViewFocused"<br>
     *   &nbsp;&nbsp;android:packageNames="foo.bar, foo.baz"<br>
     *   &nbsp;&nbsp;android:accessibilityFeedbackType="feedbackSpoken"<br>
     *   &nbsp;&nbsp;android:notificationTimeout="100"<br>
     *   &nbsp;&nbsp;android:accessibilityFlags="flagDefault"<br>
     *   &nbsp;&nbsp;android:settingsActivity="foo.bar.TestBackActivity"<br>
     *   &nbsp;&nbsp;. . .<br>
     *   /&gt;
     * </code>
     * </p>
     * <p>
     *  <strong>Note:</strong> A service can retrieve only the content of the active window.
     *          An active window is the source of the most recent event of type
     *          {@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_START},
     *          {@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END},
     *          {@link AccessibilityEvent#TYPE_VIEW_CLICKED},
     *          {@link AccessibilityEvent#TYPE_VIEW_FOCUSED},
     *          {@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER},
     *          {@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT},
     *          {@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED},
     *          {@link AccessibilityEvent#TYPE_VIEW_SELECTED},
     *          {@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED},
     *          {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}.
     *          Therefore the service should:
     *          <ul>
     *            <li>
     *              Register for all event types with no notification timeout and keep track
     *              for the active window by calling
     *              {@link AccessibilityEvent#getWindowId()} of the last received
     *              event and compare this with the
     *              {@link AccessibilityNodeInfo#getWindowId()} before calling
     *              retrieval methods on the latter.
     *            </li>
     *            <li>
     *              Prepare that a retrieval method on {@link AccessibilityNodeInfo} may fail
     *              since the active window has changed and the service did not get the 
     *              accessibility event. Note that it is possible to have a retrieval method
     *              failing event adopting the strategy specified in the previous bullet
     *              because the accessibility event dispatch is asynchronous and crosses
     *              process boundaries. 
     *            </li>
     *          <ul>
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
