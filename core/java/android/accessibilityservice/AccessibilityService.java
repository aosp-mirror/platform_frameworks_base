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

import android.accessibilityservice.GestureDescription.MotionEventGenerator;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ParcelableColorSpace;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Accessibility services should only be used to assist users with disabilities in using
 * Android devices and apps. They run in the background and receive callbacks by the system
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
 * follows the established service life cycle. Starting an accessibility service is triggered
 * exclusively by the user explicitly turning the service on in device settings. After the system
 * binds to a service, it calls {@link AccessibilityService#onServiceConnected()}. This method can
 * be overridden by clients that want to perform post binding setup.
 * </p>
 * <p>
 * An accessibility service stops either when the user turns it off in device settings or when
 * it calls {@link AccessibilityService#disableSelf()}.
 * </p>
 * <h3>Declaration</h3>
 * <p>
 * An accessibility is declared as any other service in an AndroidManifest.xml, but it
 * must do two things:
 * <ul>
 *     <ol>
 *         Specify that it handles the "android.accessibilityservice.AccessibilityService"
 *         {@link android.content.Intent}.
 *     </ol>
 *     <ol>
 *         Request the {@link android.Manifest.permission#BIND_ACCESSIBILITY_SERVICE} permission to
 *         ensure that only the system can bind to it.
 *     </ol>
 * </ul>
 * If either of these items is missing, the system will ignore the accessibility service.
 * Following is an example declaration:
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
 * A service can specify in its declaration that it can retrieve window
 * content which is represented as a tree of {@link AccessibilityWindowInfo} and
 * {@link AccessibilityNodeInfo} objects. Note that
 * declaring this capability requires that the service declares its configuration via
 * an XML resource referenced by {@link #SERVICE_META_DATA}.
 * </p>
 * <p>
 * Window content may be retrieved with
 * {@link AccessibilityEvent#getSource() AccessibilityEvent.getSource()},
 * {@link AccessibilityService#findFocus(int)},
 * {@link AccessibilityService#getWindows()}, or
 * {@link AccessibilityService#getRootInActiveWindow()}.
 * </p>
 * <p class="note">
 * <strong>Note</strong> An accessibility service may have requested to be notified for
 * a subset of the event types, and thus be unaware when the node hierarchy has changed. It is also
 * possible for a node to contain outdated information because the window content may change at any
 * time.
 * </p>
 * <h3>Notification strategy</h3>
 * <p>
 * All accessibility services are notified of all events they have requested, regardless of their
 * feedback type.
 * </p>
 * <p class="note">
 * <strong>Note:</strong> The event notification timeout is useful to avoid propagating
 * events to the client too frequently since this is accomplished via an expensive
 * interprocess call. One can think of the timeout as a criteria to determine when
 * event generation has settled down.</p>
 * <h3>Event types</h3>
 * <ul>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_CLICKED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_LONG_CLICKED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_FOCUSED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_SELECTED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_START}</li>
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_SCROLLED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_ANNOUNCEMENT}</li>
 * <li>{@link AccessibilityEvent#TYPE_GESTURE_DETECTION_START}</li>
 * <li>{@link AccessibilityEvent#TYPE_GESTURE_DETECTION_END}</li>
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_INTERACTION_START}</li>
 * <li>{@link AccessibilityEvent#TYPE_TOUCH_INTERACTION_END}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUSED}</li>
 * <li>{@link AccessibilityEvent#TYPE_WINDOWS_CHANGED}</li>
 * <li>{@link AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED}</li>
 * </ul>
 * <h3>Feedback types</h3>
 * <ul>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}</li>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_HAPTIC}</li>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_AUDIBLE}</li>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_VISUAL}</li>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_GENERIC}</li>
 * <li>{@link AccessibilityServiceInfo#FEEDBACK_BRAILLE}</li>
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
     * The user has performed a double tap gesture on the touch screen.
     */
    public static final int GESTURE_DOUBLE_TAP = 17;

    /**
     * The user has performed a double tap and hold gesture on the touch screen.
     */
    public static final int GESTURE_DOUBLE_TAP_AND_HOLD = 18;

    /**
     * The user has performed a two-finger single tap gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_SINGLE_TAP = 19;

    /**
     * The user has performed a two-finger double tap gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_DOUBLE_TAP = 20;

    /**
     * The user has performed a two-finger triple tap gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_TRIPLE_TAP = 21;

    /**
     * The user has performed a three-finger single tap gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_SINGLE_TAP = 22;

    /**
     * The user has performed a three-finger double tap gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_DOUBLE_TAP = 23;

    /**
     * The user has performed a three-finger triple tap gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_TRIPLE_TAP = 24;

    /**
     * The user has performed a two-finger swipe up gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_SWIPE_UP = 25;

    /**
     * The user has performed a two-finger swipe down gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_SWIPE_DOWN = 26;

    /**
     * The user has performed a two-finger swipe left gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_SWIPE_LEFT = 27;

    /**
     * The user has performed a two-finger swipe right gesture on the touch screen.
     */
    public static final int GESTURE_2_FINGER_SWIPE_RIGHT = 28;

    /**
     * The user has performed a three-finger swipe up gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_SWIPE_UP = 29;

    /**
     * The user has performed a three-finger swipe down gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_SWIPE_DOWN = 30;

    /**
     * The user has performed a three-finger swipe left gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_SWIPE_LEFT = 31;

    /**
     * The user has performed a three-finger swipe right gesture on the touch screen.
     */
    public static final int GESTURE_3_FINGER_SWIPE_RIGHT = 32;

    /** The user has performed a four-finger swipe up gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_SWIPE_UP = 33;

    /** The user has performed a four-finger swipe down gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_SWIPE_DOWN = 34;

    /** The user has performed a four-finger swipe left gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_SWIPE_LEFT = 35;

    /** The user has performed a four-finger swipe right gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_SWIPE_RIGHT = 36;

    /** The user has performed a four-finger single tap gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_SINGLE_TAP = 37;

    /** The user has performed a four-finger double tap gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_DOUBLE_TAP = 38;

    /** The user has performed a four-finger triple tap gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_TRIPLE_TAP = 39;

    /** The user has performed a two-finger double tap and hold gesture on the touch screen. */
    public static final int GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD = 40;

    /** The user has performed a three-finger double tap and hold gesture on the touch screen. */
    public static final int GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD = 41;

    /** The user has performed a two-finger double tap and hold gesture on the touch screen. */
    public static final int GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD = 42;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.accessibilityservice.AccessibilityService";

    /**
     * Name under which an AccessibilityService component publishes information
     * about itself. This meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#AccessibilityService accessibility-service}&gt;</code>
     * tag. This is a sample XML file configuring an accessibility service:
     * <pre> &lt;accessibility-service
     *     android:accessibilityEventTypes="typeViewClicked|typeViewFocused"
     *     android:packageNames="foo.bar, foo.baz"
     *     android:accessibilityFeedbackType="feedbackSpoken"
     *     android:notificationTimeout="100"
     *     android:accessibilityFlags="flagDefault"
     *     android:settingsActivity="foo.bar.TestBackActivity"
     *     android:canRetrieveWindowContent="true"
     *     android:canRequestTouchExplorationMode="true"
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
     * Action to toggle showing the overview of recent apps. Will fail on platforms that don't
     * show recent apps.
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

    /**
     * Action to open the power long-press dialog.
     */
    public static final int GLOBAL_ACTION_POWER_DIALOG = 6;

    /**
     * Action to toggle docking the current app's window
     */
    public static final int GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN = 7;

    /**
     * Action to lock the screen
     */
    public static final int GLOBAL_ACTION_LOCK_SCREEN = 8;

    /**
     * Action to take a screenshot
     */
    public static final int GLOBAL_ACTION_TAKE_SCREENSHOT = 9;

    /**
     * Action to send the KEYCODE_HEADSETHOOK KeyEvent, which is used to answer/hang up calls and
     * play/stop media
     * @hide
     */
    public static final int GLOBAL_ACTION_KEYCODE_HEADSETHOOK = 10;

    private static final String LOG_TAG = "AccessibilityService";

    /**
     * Interface used by IAccessibilityServiceClientWrapper to call the service from its main
     * thread.
     * @hide
     */
    public interface Callbacks {
        void onAccessibilityEvent(AccessibilityEvent event);
        void onInterrupt();
        void onServiceConnected();
        void init(int connectionId, IBinder windowToken);
        /** The detected gesture information for different displays */
        boolean onGesture(AccessibilityGestureEvent gestureInfo);
        boolean onKeyEvent(KeyEvent event);
        /** Magnification changed callbacks for different displays */
        void onMagnificationChanged(int displayId, @NonNull Region region,
                float scale, float centerX, float centerY);
        void onSoftKeyboardShowModeChanged(int showMode);
        void onPerformGestureResult(int sequence, boolean completedSuccessfully);
        void onFingerprintCapturingGesturesChanged(boolean active);
        void onFingerprintGesture(int gesture);
        /** Accessbility button clicked callbacks for different displays */
        void onAccessibilityButtonClicked(int displayId);
        void onAccessibilityButtonAvailabilityChanged(boolean available);
        /** This is called when the system action list is changed. */
        void onSystemActionsChanged();
    }

    /**
     * Annotations for Soft Keyboard show modes so tools can catch invalid show modes.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SHOW_MODE_" }, value = {
            SHOW_MODE_AUTO,
            SHOW_MODE_HIDDEN,
            SHOW_MODE_IGNORE_HARD_KEYBOARD
    })
    public @interface SoftKeyboardShowMode {}

    /**
     * Allow the system to control when the soft keyboard is shown.
     * @see SoftKeyboardController
     */
    public static final int SHOW_MODE_AUTO = 0;

    /**
     * Never show the soft keyboard.
     * @see SoftKeyboardController
     */
    public static final int SHOW_MODE_HIDDEN = 1;

    /**
     * Allow the soft keyboard to be shown, even if a hard keyboard is connected
     * @see SoftKeyboardController
     */
    public static final int SHOW_MODE_IGNORE_HARD_KEYBOARD = 2;

    /**
     * Mask used to cover the show modes supported in public API
     * @hide
     */
    public static final int SHOW_MODE_MASK = 0x03;

    /**
     * Bit used to hold the old value of the hard IME setting to restore when a service is shut
     * down.
     * @hide
     */
    public static final int SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE = 0x20000000;

    /**
     * Bit for show mode setting to indicate that the user has overridden the hard keyboard
     * behavior.
     * @hide
     */
    public static final int SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN = 0x40000000;

    /**
     * Annotations for error codes of taking screenshot.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TAKE_SCREENSHOT_" }, value = {
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY
    })
    public @interface ScreenshotErrorCode {}

    /**
     * The status of taking screenshot is success.
     * @hide
     */
    public static final int TAKE_SCREENSHOT_SUCCESS = 0;

    /**
     * The status of taking screenshot is failure and the reason is internal error.
     */
    public static final int ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR = 1;

    /**
     * The status of taking screenshot is failure and the reason is no accessibility access.
     */
    public static final int ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS = 2;

    /**
     * The status of taking screenshot is failure and the reason is that too little time has
     * elapsed since the last screenshot.
     */
    public static final int ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3;

    /**
     * The status of taking screenshot is failure and the reason is invalid display Id.
     */
    public static final int ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY = 4;

    /**
     * The interval time of calling
     * {@link AccessibilityService#takeScreenshot(int, Executor, Consumer)} API.
     * @hide
     */
    @TestApi
    public static final int ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 1000;

    /** @hide */
    public static final String KEY_ACCESSIBILITY_SCREENSHOT_STATUS =
            "screenshot_status";

    /** @hide */
    public static final String KEY_ACCESSIBILITY_SCREENSHOT_HARDWAREBUFFER =
            "screenshot_hardwareBuffer";

    /** @hide */
    public static final String KEY_ACCESSIBILITY_SCREENSHOT_COLORSPACE =
            "screenshot_colorSpace";

    /** @hide */
    public static final String KEY_ACCESSIBILITY_SCREENSHOT_TIMESTAMP =
            "screenshot_timestamp";

    private int mConnectionId = AccessibilityInteractionClient.NO_ID;

    @UnsupportedAppUsage
    private AccessibilityServiceInfo mInfo;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private IBinder mWindowToken;

    private WindowManager mWindowManager;

    /** List of magnification controllers, mapping from displayId -> MagnificationController. */
    private final SparseArray<MagnificationController> mMagnificationControllers =
            new SparseArray<>(0);
    private SoftKeyboardController mSoftKeyboardController;
    private final SparseArray<AccessibilityButtonController> mAccessibilityButtonControllers =
            new SparseArray<>(0);

    private int mGestureStatusCallbackSequence;

    private SparseArray<GestureResultCallbackInfo> mGestureStatusCallbackInfos;

    private final Object mLock = new Object();

    private FingerprintGestureController mFingerprintGestureController;


    /**
     * Callback for {@link android.view.accessibility.AccessibilityEvent}s.
     *
     * @param event The new event. This event is owned by the caller and cannot be used after
     * this method returns. Services wishing to use the event after this method returns should
     * make a copy.
     */
    public abstract void onAccessibilityEvent(AccessibilityEvent event);

    /**
     * Callback for interrupting the accessibility feedback.
     */
    public abstract void onInterrupt();

    /**
     * Dispatches service connection to internal components first, then the
     * client code.
     */
    private void dispatchServiceConnected() {
        synchronized (mLock) {
            for (int i = 0; i < mMagnificationControllers.size(); i++) {
                mMagnificationControllers.valueAt(i).onServiceConnectedLocked();
            }
        }
        if (mSoftKeyboardController != null) {
            mSoftKeyboardController.onServiceConnected();
        }

        // The client gets to handle service connection last, after we've set
        // up any state upon which their code may rely.
        onServiceConnected();
    }

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
     * Called by {@link #onGesture(AccessibilityGestureEvent)} when the user performs a specific
     * gesture on the default display.
     *
     * <strong>Note:</strong> To receive gestures an accessibility service must
     * request that the device is in touch exploration mode by setting the
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE}
     * flag.
     *
     * @param gestureId The unique id of the performed gesture.
     *
     * @return Whether the gesture was handled.
     * @deprecated Override {@link #onGesture(AccessibilityGestureEvent)} instead.
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
    @Deprecated
    protected boolean onGesture(int gestureId) {
        return false;
    }

    /**
     * Called by the system when the user performs a specific gesture on the
     * specific touch screen.
     *<p>
     * <strong>Note:</strong> To receive gestures an accessibility service must
     * request that the device is in touch exploration mode by setting the
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE}
     * flag.
     *<p>
     * <strong>Note:</strong> The default implementation calls {@link #onGesture(int)} when the
     * touch screen is default display.
     *
     * @param gestureEvent The information of gesture.
     *
     * @return Whether the gesture was handled.
     *
     */
    public boolean onGesture(@NonNull AccessibilityGestureEvent gestureEvent) {
        if (gestureEvent.getDisplayId() == Display.DEFAULT_DISPLAY) {
            onGesture(gestureEvent.getGestureId());
        }
        return false;
    }

    /**
     * Callback that allows an accessibility service to observe the key events
     * before they are passed to the rest of the system. This means that the events
     * are first delivered here before they are passed to the device policy, the
     * input method, or applications.
     * <p>
     * <strong>Note:</strong> It is important that key events are handled in such
     * a way that the event stream that would be passed to the rest of the system
     * is well-formed. For example, handling the down event but not the up event
     * and vice versa would generate an inconsistent event stream.
     * </p>
     * <p>
     * <strong>Note:</strong> The key events delivered in this method are copies
     * and modifying them will have no effect on the events that will be passed
     * to the system. This method is intended to perform purely filtering
     * functionality.
     * <p>
     *
     * @param event The event to be processed. This event is owned by the caller and cannot be used
     * after this method returns. Services wishing to use the event after this method returns should
     * make a copy.
     * @return If true then the event will be consumed and not delivered to
     *         applications, otherwise it will be delivered as usual.
     */
    protected boolean onKeyEvent(KeyEvent event) {
        return false;
    }

    /**
     * Gets the windows on the screen of the default display. This method returns only the windows
     * that a sighted user can interact with, as opposed to all windows.
     * For example, if there is a modal dialog shown and the user cannot touch
     * anything behind it, then only the modal window will be reported
     * (assuming it is the top one). For convenience the returned windows
     * are ordered in a descending layer order, which is the windows that
     * are on top are reported first. Since the user can always
     * interact with the window that has input focus by typing, the focused
     * window is always returned (even if covered by a modal window).
     * <p>
     * <strong>Note:</strong> In order to access the windows your service has
     * to declare the capability to retrieve window content by setting the
     * {@link android.R.styleable#AccessibilityService_canRetrieveWindowContent}
     * property in its meta-data. For details refer to {@link #SERVICE_META_DATA}.
     * Also the service has to opt-in to retrieve the interactive windows by
     * setting the {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
     * flag.
     * </p>
     *
     * @return The windows if there are windows and the service is can retrieve
     *         them, otherwise an empty list.
     */
    public List<AccessibilityWindowInfo> getWindows() {
        return AccessibilityInteractionClient.getInstance().getWindows(mConnectionId);
    }

    /**
     * Gets the windows on the screen of all displays. This method returns only the windows
     * that a sighted user can interact with, as opposed to all windows.
     * For example, if there is a modal dialog shown and the user cannot touch
     * anything behind it, then only the modal window will be reported
     * (assuming it is the top one). For convenience the returned windows
     * are ordered in a descending layer order, which is the windows that
     * are on top are reported first. Since the user can always
     * interact with the window that has input focus by typing, the focused
     * window is always returned (even if covered by a modal window).
     * <p>
     * <strong>Note:</strong> In order to access the windows your service has
     * to declare the capability to retrieve window content by setting the
     * {@link android.R.styleable#AccessibilityService_canRetrieveWindowContent}
     * property in its meta-data. For details refer to {@link #SERVICE_META_DATA}.
     * Also the service has to opt-in to retrieve the interactive windows by
     * setting the {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
     * flag.
     * </p>
     *
     * @return The windows of all displays if there are windows and the service is can retrieve
     *         them, otherwise an empty list. The key of SparseArray is display ID.
     */
    @NonNull
    public final SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
        return AccessibilityInteractionClient.getInstance().getWindowsOnAllDisplays(mConnectionId);
    }

    /**
     * Gets the root node in the currently active window if this service
     * can retrieve window content. The active window is the one that the user
     * is currently touching or the window with input focus, if the user is not
     * touching any window. It could be from any logical display.
     * <p>
     * The currently active window is defined as the window that most recently fired one
     * of the following events:
     * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED},
     * {@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER},
     * {@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT}.
     * In other words, the last window shown that also has input focus.
     * </p>
     * <p>
     * <strong>Note:</strong> In order to access the root node your service has
     * to declare the capability to retrieve window content by setting the
     * {@link android.R.styleable#AccessibilityService_canRetrieveWindowContent}
     * property in its meta-data. For details refer to {@link #SERVICE_META_DATA}.
     * </p>
     *
     * @return The root node if this service can retrieve window content.
     */
    public AccessibilityNodeInfo getRootInActiveWindow() {
        return AccessibilityInteractionClient.getInstance().getRootInActiveWindow(mConnectionId);
    }

    /**
     * Disables the service. After calling this method, the service will be disabled and settings
     * will show that it is turned off.
     */
    public final void disableSelf() {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                connection.disableSelf();
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    @Override
    public Context createDisplayContext(Display display) {
        final Context context = super.createDisplayContext(display);
        final int displayId = display.getDisplayId();
        setDefaultTokenInternal(context, displayId);
        return context;
    }

    private void setDefaultTokenInternal(Context context, int displayId) {
        final WindowManagerImpl wm = (WindowManagerImpl) context.getSystemService(WINDOW_SERVICE);
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        IBinder token = null;
        if (connection != null) {
            synchronized (mLock) {
                try {
                    token = connection.getOverlayWindowToken(displayId);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to get window token", re);
                    re.rethrowFromSystemServer();
                }
            }
            wm.setDefaultToken(token);
        }
    }

    /**
     * Returns the magnification controller, which may be used to query and
     * modify the state of display magnification.
     * <p>
     * <strong>Note:</strong> In order to control magnification, your service
     * must declare the capability by setting the
     * {@link android.R.styleable#AccessibilityService_canControlMagnification}
     * property in its meta-data. For more information, see
     * {@link #SERVICE_META_DATA}.
     *
     * @return the magnification controller
     */
    @NonNull
    public final MagnificationController getMagnificationController() {
        return getMagnificationController(Display.DEFAULT_DISPLAY);
    }

    /**
     * Returns the magnification controller of specified logical display, which may be used to
     * query and modify the state of display magnification.
     * <p>
     * <strong>Note:</strong> In order to control magnification, your service
     * must declare the capability by setting the
     * {@link android.R.styleable#AccessibilityService_canControlMagnification}
     * property in its meta-data. For more information, see
     * {@link #SERVICE_META_DATA}.
     *
     * @param displayId The logic display id, use {@link Display#DEFAULT_DISPLAY} for
     *                  default display.
     * @return the magnification controller
     *
     * @hide
     */
    @NonNull
    public final MagnificationController getMagnificationController(int displayId) {
        synchronized (mLock) {
            MagnificationController controller = mMagnificationControllers.get(displayId);
            if (controller == null) {
                controller = new MagnificationController(this, mLock, displayId);
                mMagnificationControllers.put(displayId, controller);
            }
            return controller;
        }
    }

    /**
     * Get the controller for fingerprint gestures. This feature requires {@link
     * AccessibilityServiceInfo#CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES}.
     *
     *<strong>Note: </strong> The service must be connected before this method is called.
     *
     * @return The controller for fingerprint gestures, or {@code null} if gestures are unavailable.
     */
    @RequiresPermission(android.Manifest.permission.USE_FINGERPRINT)
    public final @NonNull FingerprintGestureController getFingerprintGestureController() {
        if (mFingerprintGestureController == null) {
            mFingerprintGestureController = new FingerprintGestureController(
                    AccessibilityInteractionClient.getInstance().getConnection(mConnectionId));
        }
        return mFingerprintGestureController;
    }

    /**
     * Dispatch a gesture to the touch screen. Any gestures currently in progress, whether from
     * the user, this service, or another service, will be cancelled.
     * <p>
     * The gesture will be dispatched as if it were performed directly on the screen by a user, so
     * the events may be affected by features such as magnification and explore by touch.
     * </p>
     * <p>
     * <strong>Note:</strong> In order to dispatch gestures, your service
     * must declare the capability by setting the
     * {@link android.R.styleable#AccessibilityService_canPerformGestures}
     * property in its meta-data. For more information, see
     * {@link #SERVICE_META_DATA}.
     * </p>
     *
     * @param gesture The gesture to dispatch
     * @param callback The object to call back when the status of the gesture is known. If
     * {@code null}, no status is reported.
     * @param handler The handler on which to call back the {@code callback} object. If
     * {@code null}, the object is called back on the service's main thread.
     *
     * @return {@code true} if the gesture is dispatched, {@code false} if not.
     */
    public final boolean dispatchGesture(@NonNull GestureDescription gesture,
            @Nullable GestureResultCallback callback,
            @Nullable Handler handler) {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(
                        mConnectionId);
        if (connection == null) {
            return false;
        }
        List<GestureDescription.GestureStep> steps =
                MotionEventGenerator.getGestureStepsFromGestureDescription(gesture, 16);
        try {
            synchronized (mLock) {
                mGestureStatusCallbackSequence++;
                if (callback != null) {
                    if (mGestureStatusCallbackInfos == null) {
                        mGestureStatusCallbackInfos = new SparseArray<>();
                    }
                    GestureResultCallbackInfo callbackInfo = new GestureResultCallbackInfo(gesture,
                            callback, handler);
                    mGestureStatusCallbackInfos.put(mGestureStatusCallbackSequence, callbackInfo);
                }
                connection.dispatchGesture(mGestureStatusCallbackSequence,
                        new ParceledListSlice<>(steps), gesture.getDisplayId());
            }
        } catch (RemoteException re) {
            throw new RuntimeException(re);
        }
        return true;
    }

    void onPerformGestureResult(int sequence, final boolean completedSuccessfully) {
        if (mGestureStatusCallbackInfos == null) {
            return;
        }
        GestureResultCallbackInfo callbackInfo;
        synchronized (mLock) {
            callbackInfo = mGestureStatusCallbackInfos.get(sequence);
            mGestureStatusCallbackInfos.remove(sequence);
        }
        final GestureResultCallbackInfo finalCallbackInfo = callbackInfo;
        if ((callbackInfo != null) && (callbackInfo.gestureDescription != null)
                && (callbackInfo.callback != null)) {
            if (callbackInfo.handler != null) {
                callbackInfo.handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (completedSuccessfully) {
                            finalCallbackInfo.callback
                                    .onCompleted(finalCallbackInfo.gestureDescription);
                        } else {
                            finalCallbackInfo.callback
                                    .onCancelled(finalCallbackInfo.gestureDescription);
                        }
                    }
                });
                return;
            }
            if (completedSuccessfully) {
                callbackInfo.callback.onCompleted(callbackInfo.gestureDescription);
            } else {
                callbackInfo.callback.onCancelled(callbackInfo.gestureDescription);
            }
        }
    }

    private void onMagnificationChanged(int displayId, @NonNull Region region, float scale,
            float centerX, float centerY) {
        MagnificationController controller;
        synchronized (mLock) {
            controller = mMagnificationControllers.get(displayId);
        }
        if (controller != null) {
            controller.dispatchMagnificationChanged(region, scale, centerX, centerY);
        }
    }

    /**
     * Callback for fingerprint gesture handling
     * @param active If gesture detection is active
     */
    private void onFingerprintCapturingGesturesChanged(boolean active) {
        getFingerprintGestureController().onGestureDetectionActiveChanged(active);
    }

    /**
     * Callback for fingerprint gesture handling
     * @param gesture The identifier for the gesture performed
     */
    private void onFingerprintGesture(int gesture) {
        getFingerprintGestureController().onGesture(gesture);
    }

    /**
     * Used to control and query the state of display magnification.
     */
    public static final class MagnificationController {
        private final AccessibilityService mService;
        private final int mDisplayId;

        /**
         * Map of listeners to their handlers. Lazily created when adding the
         * first magnification listener.
         */
        private ArrayMap<OnMagnificationChangedListener, Handler> mListeners;
        private final Object mLock;

        MagnificationController(@NonNull AccessibilityService service, @NonNull Object lock,
                int displayId) {
            mService = service;
            mLock = lock;
            mDisplayId = displayId;
        }

        /**
         * Called when the service is connected.
         */
        void onServiceConnectedLocked() {
            if (mListeners != null && !mListeners.isEmpty()) {
                setMagnificationCallbackEnabled(true);
            }
        }

        /**
         * Adds the specified change listener to the list of magnification
         * change listeners. The callback will occur on the service's main
         * thread.
         *
         * @param listener the listener to add, must be non-{@code null}
         */
        public void addListener(@NonNull OnMagnificationChangedListener listener) {
            addListener(listener, null);
        }

        /**
         * Adds the specified change listener to the list of magnification
         * change listeners. The callback will occur on the specified
         * {@link Handler}'s thread, or on the service's main thread if the
         * handler is {@code null}.
         *
         * @param listener the listener to add, must be non-null
         * @param handler the handler on which the callback should execute, or
         *        {@code null} to execute on the service's main thread
         */
        public void addListener(@NonNull OnMagnificationChangedListener listener,
                @Nullable Handler handler) {
            synchronized (mLock) {
                if (mListeners == null) {
                    mListeners = new ArrayMap<>();
                }

                final boolean shouldEnableCallback = mListeners.isEmpty();
                mListeners.put(listener, handler);

                if (shouldEnableCallback) {
                    // This may fail if the service is not connected yet, but if we
                    // still have listeners when it connects then we can try again.
                    setMagnificationCallbackEnabled(true);
                }
            }
        }

        /**
         * Removes the specified change listener from the list of magnification change listeners.
         *
         * @param listener the listener to remove, must be non-null
         * @return {@code true} if the listener was removed, {@code false} otherwise
         */
        public boolean removeListener(@NonNull OnMagnificationChangedListener listener) {
            if (mListeners == null) {
                return false;
            }

            synchronized (mLock) {
                final int keyIndex = mListeners.indexOfKey(listener);
                final boolean hasKey = keyIndex >= 0;
                if (hasKey) {
                    mListeners.removeAt(keyIndex);
                }

                if (hasKey && mListeners.isEmpty()) {
                    // We just removed the last listener, so we don't need
                    // callbacks from the service anymore.
                    setMagnificationCallbackEnabled(false);
                }

                return hasKey;
            }
        }

        private void setMagnificationCallbackEnabled(boolean enabled) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    connection.setMagnificationCallbackEnabled(mDisplayId, enabled);
                } catch (RemoteException re) {
                    throw new RuntimeException(re);
                }
            }
        }

        /**
         * Dispatches magnification changes to any registered listeners. This
         * should be called on the service's main thread.
         */
        void dispatchMagnificationChanged(final @NonNull Region region, final float scale,
                final float centerX, final float centerY) {
            final ArrayMap<OnMagnificationChangedListener, Handler> entries;
            synchronized (mLock) {
                if (mListeners == null || mListeners.isEmpty()) {
                    Slog.d(LOG_TAG, "Received magnification changed "
                            + "callback with no listeners registered!");
                    setMagnificationCallbackEnabled(false);
                    return;
                }

                // Listeners may remove themselves. Perform a shallow copy to avoid concurrent
                // modification.
                entries = new ArrayMap<>(mListeners);
            }

            for (int i = 0, count = entries.size(); i < count; i++) {
                final OnMagnificationChangedListener listener = entries.keyAt(i);
                final Handler handler = entries.valueAt(i);
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onMagnificationChanged(MagnificationController.this,
                                    region, scale, centerX, centerY);
                        }
                    });
                } else {
                    // We're already on the main thread, just run the listener.
                    listener.onMagnificationChanged(this, region, scale, centerX, centerY);
                }
            }
        }

        /**
         * Returns the current magnification scale.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will
         * return a default value of {@code 1.0f}.
         *
         * @return the current magnification scale
         */
        public float getScale() {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.getMagnificationScale(mDisplayId);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to obtain scale", re);
                    re.rethrowFromSystemServer();
                }
            }
            return 1.0f;
        }

        /**
         * Returns the unscaled screen-relative X coordinate of the focal
         * center of the magnified region. This is the point around which
         * zooming occurs and is guaranteed to lie within the magnified
         * region.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will
         * return a default value of {@code 0.0f}.
         *
         * @return the unscaled screen-relative X coordinate of the center of
         *         the magnified region
         */
        public float getCenterX() {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.getMagnificationCenterX(mDisplayId);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to obtain center X", re);
                    re.rethrowFromSystemServer();
                }
            }
            return 0.0f;
        }

        /**
         * Returns the unscaled screen-relative Y coordinate of the focal
         * center of the magnified region. This is the point around which
         * zooming occurs and is guaranteed to lie within the magnified
         * region.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will
         * return a default value of {@code 0.0f}.
         *
         * @return the unscaled screen-relative Y coordinate of the center of
         *         the magnified region
         */
        public float getCenterY() {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.getMagnificationCenterY(mDisplayId);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to obtain center Y", re);
                    re.rethrowFromSystemServer();
                }
            }
            return 0.0f;
        }

        /**
         * Returns the region of the screen currently active for magnification. Changes to
         * magnification scale and center only affect this portion of the screen. The rest of the
         * screen, for example input methods, cannot be magnified. This region is relative to the
         * unscaled screen and is independent of the scale and center point.
         * <p>
         * The returned region will be empty if magnification is not active. Magnification is active
         * if magnification gestures are enabled or if a service is running that can control
         * magnification.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will
         * return an empty region.
         *
         * @return the region of the screen currently active for magnification, or an empty region
         * if magnification is not active.
         */
        @NonNull
        public Region getMagnificationRegion() {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.getMagnificationRegion(mDisplayId);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to obtain magnified region", re);
                    re.rethrowFromSystemServer();
                }
            }
            return Region.obtain();
        }

        /**
         * Resets magnification scale and center to their default (e.g. no
         * magnification) values.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will have
         * no effect and return {@code false}.
         *
         * @param animate {@code true} to animate from the current scale and
         *                center or {@code false} to reset the scale and center
         *                immediately
         * @return {@code true} on success, {@code false} on failure
         */
        public boolean reset(boolean animate) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.resetMagnification(mDisplayId, animate);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to reset", re);
                    re.rethrowFromSystemServer();
                }
            }
            return false;
        }

        /**
         * Sets the magnification scale.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will have
         * no effect and return {@code false}.
         *
         * @param scale the magnification scale to set, must be >= 1 and <= 8
         * @param animate {@code true} to animate from the current scale or
         *                {@code false} to set the scale immediately
         * @return {@code true} on success, {@code false} on failure
         */
        public boolean setScale(float scale, boolean animate) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.setMagnificationScaleAndCenter(mDisplayId,
                            scale, Float.NaN, Float.NaN, animate);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to set scale", re);
                    re.rethrowFromSystemServer();
                }
            }
            return false;
        }

        /**
         * Sets the center of the magnified viewport.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been
         * called) or the service has been disconnected, this method will have
         * no effect and return {@code false}.
         *
         * @param centerX the unscaled screen-relative X coordinate on which to
         *                center the viewport
         * @param centerY the unscaled screen-relative Y coordinate on which to
         *                center the viewport
         * @param animate {@code true} to animate from the current viewport
         *                center or {@code false} to set the center immediately
         * @return {@code true} on success, {@code false} on failure
         */
        public boolean setCenter(float centerX, float centerY, boolean animate) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.setMagnificationScaleAndCenter(mDisplayId,
                            Float.NaN, centerX, centerY, animate);
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to set center", re);
                    re.rethrowFromSystemServer();
                }
            }
            return false;
        }

        /**
         * Listener for changes in the state of magnification.
         */
        public interface OnMagnificationChangedListener {
            /**
             * Called when the magnified region, scale, or center changes.
             *
             * @param controller the magnification controller
             * @param region the magnification region
             * @param scale the new scale
             * @param centerX the new X coordinate, in unscaled coordinates, around which
             * magnification is focused
             * @param centerY the new Y coordinate, in unscaled coordinates, around which
             * magnification is focused
             */
            void onMagnificationChanged(@NonNull MagnificationController controller,
                    @NonNull Region region, float scale, float centerX, float centerY);
        }
    }

    /**
     * Returns the soft keyboard controller, which may be used to query and modify the soft keyboard
     * show mode.
     *
     * @return the soft keyboard controller
     */
    @NonNull
    public final SoftKeyboardController getSoftKeyboardController() {
        synchronized (mLock) {
            if (mSoftKeyboardController == null) {
                mSoftKeyboardController = new SoftKeyboardController(this, mLock);
            }
            return mSoftKeyboardController;
        }
    }

    private void onSoftKeyboardShowModeChanged(int showMode) {
        if (mSoftKeyboardController != null) {
            mSoftKeyboardController.dispatchSoftKeyboardShowModeChanged(showMode);
        }
    }

    /**
     * Used to control, query, and listen for changes to the soft keyboard show mode.
     * <p>
     * Accessibility services may request to override the decisions normally made about whether or
     * not the soft keyboard is shown.
     * <p>
     * If multiple services make conflicting requests, the last request is honored. A service may
     * register a listener to find out if the mode has changed under it.
     * <p>
     * If the user takes action to override the behavior behavior requested by an accessibility
     * service, the user's request takes precendence, the show mode will be reset to
     * {@link AccessibilityService#SHOW_MODE_AUTO}, and services will no longer be able to control
     * that aspect of the soft keyboard's behavior.
     * <p>
     * Note: Because soft keyboards are independent apps, the framework does not have total control
     * over their behavior. They may choose to show themselves, or not, without regard to requests
     * made here. So the framework will make a best effort to deliver the behavior requested, but
     * cannot guarantee success.
     *
     * @see AccessibilityService#SHOW_MODE_AUTO
     * @see AccessibilityService#SHOW_MODE_HIDDEN
     * @see AccessibilityService#SHOW_MODE_IGNORE_HARD_KEYBOARD
     */
    public static final class SoftKeyboardController {
        private final AccessibilityService mService;

        /**
         * Map of listeners to their handlers. Lazily created when adding the first
         * soft keyboard change listener.
         */
        private ArrayMap<OnShowModeChangedListener, Handler> mListeners;
        private final Object mLock;

        SoftKeyboardController(@NonNull AccessibilityService service, @NonNull Object lock) {
            mService = service;
            mLock = lock;
        }

        /**
         * Called when the service is connected.
         */
        void onServiceConnected() {
            synchronized(mLock) {
                if (mListeners != null && !mListeners.isEmpty()) {
                    setSoftKeyboardCallbackEnabled(true);
                }
            }
        }

        /**
         * Adds the specified change listener to the list of show mode change listeners. The
         * callback will occur on the service's main thread. Listener is not called on registration.
         */
        public void addOnShowModeChangedListener(@NonNull OnShowModeChangedListener listener) {
            addOnShowModeChangedListener(listener, null);
        }

        /**
         * Adds the specified change listener to the list of soft keyboard show mode change
         * listeners. The callback will occur on the specified {@link Handler}'s thread, or on the
         * services's main thread if the handler is {@code null}.
         *
         * @param listener the listener to add, must be non-null
         * @param handler the handler on which to callback should execute, or {@code null} to
         *        execute on the service's main thread
         */
        public void addOnShowModeChangedListener(@NonNull OnShowModeChangedListener listener,
                @Nullable Handler handler) {
            synchronized (mLock) {
                if (mListeners == null) {
                    mListeners = new ArrayMap<>();
                }

                final boolean shouldEnableCallback = mListeners.isEmpty();
                mListeners.put(listener, handler);

                if (shouldEnableCallback) {
                    // This may fail if the service is not connected yet, but if we still have
                    // listeners when it connects, we can try again.
                    setSoftKeyboardCallbackEnabled(true);
                }
            }
        }

        /**
         * Removes the specified change listener from the list of keyboard show mode change
         * listeners.
         *
         * @param listener the listener to remove, must be non-null
         * @return {@code true} if the listener was removed, {@code false} otherwise
         */
        public boolean removeOnShowModeChangedListener(
                @NonNull OnShowModeChangedListener listener) {
            if (mListeners == null) {
                return false;
            }

            synchronized (mLock) {
                final int keyIndex = mListeners.indexOfKey(listener);
                final boolean hasKey = keyIndex >= 0;
                if (hasKey) {
                    mListeners.removeAt(keyIndex);
                }

                if (hasKey && mListeners.isEmpty()) {
                    // We just removed the last listener, so we don't need callbacks from the
                    // service anymore.
                    setSoftKeyboardCallbackEnabled(false);
                }

                return hasKey;
            }
        }

        private void setSoftKeyboardCallbackEnabled(boolean enabled) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    connection.setSoftKeyboardCallbackEnabled(enabled);
                } catch (RemoteException re) {
                    throw new RuntimeException(re);
                }
            }
        }

        /**
         * Dispatches the soft keyboard show mode change to any registered listeners. This should
         * be called on the service's main thread.
         */
        void dispatchSoftKeyboardShowModeChanged(final int showMode) {
            final ArrayMap<OnShowModeChangedListener, Handler> entries;
            synchronized (mLock) {
                if (mListeners == null || mListeners.isEmpty()) {
                    Slog.w(LOG_TAG, "Received soft keyboard show mode changed callback"
                            + " with no listeners registered!");
                    setSoftKeyboardCallbackEnabled(false);
                    return;
                }

                // Listeners may remove themselves. Perform a shallow copy to avoid concurrent
                // modification.
                entries = new ArrayMap<>(mListeners);
            }

            for (int i = 0, count = entries.size(); i < count; i++) {
                final OnShowModeChangedListener listener = entries.keyAt(i);
                final Handler handler = entries.valueAt(i);
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onShowModeChanged(SoftKeyboardController.this, showMode);
                        }
                    });
                } else {
                    // We're already on the main thread, just run the listener.
                    listener.onShowModeChanged(this, showMode);
                }
            }
        }

        /**
         * Returns the show mode of the soft keyboard.
         *
         * @return the current soft keyboard show mode
         *
         * @see AccessibilityService#SHOW_MODE_AUTO
         * @see AccessibilityService#SHOW_MODE_HIDDEN
         * @see AccessibilityService#SHOW_MODE_IGNORE_HARD_KEYBOARD
         */
        @SoftKeyboardShowMode
        public int getShowMode() {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.getSoftKeyboardShowMode();
                } catch (RemoteException re) {
                    Log.w(LOG_TAG, "Failed to set soft keyboard behavior", re);
                    re.rethrowFromSystemServer();
                }
            }
            return SHOW_MODE_AUTO;
        }

        /**
         * Sets the soft keyboard show mode.
         * <p>
         * <strong>Note:</strong> If the service is not yet connected (e.g.
         * {@link AccessibilityService#onServiceConnected()} has not yet been called) or the
         * service has been disconnected, this method will have no effect and return {@code false}.
         *
         * @param showMode the new show mode for the soft keyboard
         * @return {@code true} on success
         *
         * @see AccessibilityService#SHOW_MODE_AUTO
         * @see AccessibilityService#SHOW_MODE_HIDDEN
         * @see AccessibilityService#SHOW_MODE_IGNORE_HARD_KEYBOARD
         */
        public boolean setShowMode(@SoftKeyboardShowMode int showMode) {
           final IAccessibilityServiceConnection connection =
                   AccessibilityInteractionClient.getInstance().getConnection(
                           mService.mConnectionId);
           if (connection != null) {
               try {
                   return connection.setSoftKeyboardShowMode(showMode);
               } catch (RemoteException re) {
                   Log.w(LOG_TAG, "Failed to set soft keyboard behavior", re);
                   re.rethrowFromSystemServer();
               }
           }
           return false;
        }

        /**
         * Listener for changes in the soft keyboard show mode.
         */
        public interface OnShowModeChangedListener {
           /**
            * Called when the soft keyboard behavior changes. The default show mode is
            * {@code SHOW_MODE_AUTO}, where the soft keyboard is shown when a text input field is
            * focused. An AccessibilityService can also request the show mode
            * {@code SHOW_MODE_HIDDEN}, where the soft keyboard is never shown.
            *
            * @param controller the soft keyboard controller
            * @param showMode the current soft keyboard show mode
            */
            void onShowModeChanged(@NonNull SoftKeyboardController controller,
                    @SoftKeyboardShowMode int showMode);
        }

        /**
         * Switches the current IME for the user for whom the service is enabled. The change will
         * persist until the current IME is explicitly changed again, and may persist beyond the
         * life cycle of the requesting service.
         *
         * @param imeId The ID of the input method to make current. This IME must be installed and
         *              enabled.
         * @return {@code true} if the current input method was successfully switched to the input
         *         method by {@code imeId},
         *         {@code false} if the input method specified is not installed, not enabled, or
         *         otherwise not available to become the current IME
         *
         * @see android.view.inputmethod.InputMethodInfo#getId()
         */
        public boolean switchToInputMethod(@NonNull String imeId) {
            final IAccessibilityServiceConnection connection =
                    AccessibilityInteractionClient.getInstance().getConnection(
                            mService.mConnectionId);
            if (connection != null) {
                try {
                    return connection.switchToInputMethod(imeId);
                } catch (RemoteException re) {
                    throw new RuntimeException(re);
                }
            }
            return false;
        }
    }

    /**
     * Returns the controller for the accessibility button within the system's navigation area.
     * This instance may be used to query the accessibility button's state and register listeners
     * for interactions with and state changes for the accessibility button when
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_ACCESSIBILITY_BUTTON} is set.
     * <p>
     * <strong>Note:</strong> Not all devices are capable of displaying the accessibility button
     * within a navigation area, and as such, use of this class should be considered only as an
     * optional feature or shortcut on supported device implementations.
     * </p>
     *
     * @return the accessibility button controller for this {@link AccessibilityService}
     */
    @NonNull
    public final AccessibilityButtonController getAccessibilityButtonController() {
        return getAccessibilityButtonController(Display.DEFAULT_DISPLAY);
    }

    /**
     * Returns the controller of specified logical display for the accessibility button within the
     * system's navigation area. This instance may be used to query the accessibility button's
     * state and register listeners for interactions with and state changes for the accessibility
     * button when {@link AccessibilityServiceInfo#FLAG_REQUEST_ACCESSIBILITY_BUTTON} is set.
     * <p>
     * <strong>Note:</strong> Not all devices are capable of displaying the accessibility button
     * within a navigation area, and as such, use of this class should be considered only as an
     * optional feature or shortcut on supported device implementations.
     * </p>
     *
     * @param displayId The logic display id, use {@link Display#DEFAULT_DISPLAY} for default
     *                  display.
     * @return the accessibility button controller for this {@link AccessibilityService}
     */
    @NonNull
    public final AccessibilityButtonController getAccessibilityButtonController(int displayId) {
        synchronized (mLock) {
            AccessibilityButtonController controller = mAccessibilityButtonControllers.get(
                    displayId);
            if (controller == null) {
                controller = new AccessibilityButtonController(
                        AccessibilityInteractionClient.getInstance().getConnection(mConnectionId));
                mAccessibilityButtonControllers.put(displayId, controller);
            }
            return controller;
        }
    }

    private void onAccessibilityButtonClicked(int displayId) {
        getAccessibilityButtonController(displayId).dispatchAccessibilityButtonClicked();
    }

    private void onAccessibilityButtonAvailabilityChanged(boolean available) {
        getAccessibilityButtonController().dispatchAccessibilityButtonAvailabilityChanged(
                available);
    }

    /** This is called when the system action list is changed. */
    public void onSystemActionsChanged() {
    }

    /**
     * Returns a list of system actions available in the system right now.
     * <p>
     * System actions that correspond to the global action constants will have matching action IDs.
     * For example, an with id {@link #GLOBAL_ACTION_BACK} will perform the back action.
     * </p>
     * <p>
     * These actions should be called by {@link #performGlobalAction}.
     * </p>
     *
     * @return A list of available system actions.
     */
    public final @NonNull List<AccessibilityAction> getSystemActions() {
        IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                return connection.getSystemActions();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while calling getSystemActions", re);
                re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
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
                re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Find the view that has the specified focus type. The search is performed
     * across all windows.
     * <p>
     * <strong>Note:</strong> In order to access the windows your service has
     * to declare the capability to retrieve window content by setting the
     * {@link android.R.styleable#AccessibilityService_canRetrieveWindowContent}
     * property in its meta-data. For details refer to {@link #SERVICE_META_DATA}.
     * Also the service has to opt-in to retrieve the interactive windows by
     * setting the {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
     * flag. Otherwise, the search will be performed only in the active window.
     * </p>
     * <p>
     * <strong>Note:</strong> If the view with {@link AccessibilityNodeInfo#FOCUS_INPUT}
     * is on an embedded view hierarchy which is embedded in a {@link SurfaceView} via
     * {@link SurfaceView#setChildSurfacePackage}, there is a limitation that this API
     * won't be able to find the node for the view. It's because views don't know about
     * the embedded hierarchies. Instead, you could traverse all the nodes to find the
     * focus.
     * </p>
     *
     * @param focus The focus to find. One of {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     *         {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}.
     * @return The node info of the focused view or null.
     *
     * @see AccessibilityNodeInfo#FOCUS_INPUT
     * @see AccessibilityNodeInfo#FOCUS_ACCESSIBILITY
     */
    public AccessibilityNodeInfo findFocus(int focus) {
        return AccessibilityInteractionClient.getInstance().findFocus(mConnectionId,
                AccessibilityWindowInfo.ANY_WINDOW_ID, AccessibilityNodeInfo.ROOT_NODE_ID, focus);
    }

    /**
     * Gets the an {@link AccessibilityServiceInfo} describing this
     * {@link AccessibilityService}. This method is useful if one wants
     * to change some of the dynamically configurable properties at
     * runtime.
     *
     * @return The accessibility service info.
     *
     * @see AccessibilityServiceInfo
     */
    public final AccessibilityServiceInfo getServiceInfo() {
        IAccessibilityServiceConnection connection =
            AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                return connection.getServiceInfo();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while getting AccessibilityServiceInfo", re);
                re.rethrowFromSystemServer();
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
                re.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public Object getSystemService(@ServiceName @NonNull String name) {
        if (getBaseContext() == null) {
            throw new IllegalStateException(
                    "System services not available to Activities before onCreate()");
        }

        // Guarantee that we always return the same window manager instance.
        if (WINDOW_SERVICE.equals(name)) {
            if (mWindowManager == null) {
                mWindowManager = (WindowManager) getBaseContext().getSystemService(name);
            }
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    /**
     * Takes a screenshot of the specified display and returns it via an
     * {@link AccessibilityService.ScreenshotResult}. You can use {@link Bitmap#wrapHardwareBuffer}
     * to construct the bitmap from the ScreenshotResult's payload.
     * <p>
     * <strong>Note:</strong> In order to take screenshot your service has
     * to declare the capability to take screenshot by setting the
     * {@link android.R.styleable#AccessibilityService_canTakeScreenshot}
     * property in its meta-data. For details refer to {@link #SERVICE_META_DATA}.
     * </p>
     *
     * @param displayId The logic display id, must be {@link Display#DEFAULT_DISPLAY} for
     *                  default display.
     * @param executor Executor on which to run the callback.
     * @param callback The callback invoked when taking screenshot has succeeded or failed.
     *                 See {@link TakeScreenshotCallback} for details.
     */
    public void takeScreenshot(int displayId, @NonNull @CallbackExecutor Executor executor,
            @NonNull TakeScreenshotCallback callback) {
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(
                        mConnectionId);
        if (connection == null) {
            sendScreenshotFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, executor, callback);
            return;
        }
        try {
            connection.takeScreenshot(displayId, new RemoteCallback((result) -> {
                final int status = result.getInt(KEY_ACCESSIBILITY_SCREENSHOT_STATUS);
                if (status != TAKE_SCREENSHOT_SUCCESS) {
                    sendScreenshotFailure(status, executor, callback);
                    return;
                }
                final HardwareBuffer hardwareBuffer =
                        result.getParcelable(KEY_ACCESSIBILITY_SCREENSHOT_HARDWAREBUFFER);
                final ParcelableColorSpace colorSpace =
                        result.getParcelable(KEY_ACCESSIBILITY_SCREENSHOT_COLORSPACE);
                final ScreenshotResult screenshot = new ScreenshotResult(hardwareBuffer,
                        colorSpace.getColorSpace(),
                        result.getLong(KEY_ACCESSIBILITY_SCREENSHOT_TIMESTAMP));
                sendScreenshotSuccess(screenshot, executor, callback);
            }));
        } catch (RemoteException re) {
            throw new RuntimeException(re);
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
                AccessibilityService.this.dispatchServiceConnected();
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
            public void init(int connectionId, IBinder windowToken) {
                mConnectionId = connectionId;
                mWindowToken = windowToken;

                // The client may have already obtained the window manager, so
                // update the default token on whatever manager we gave them.
                final WindowManagerImpl wm = (WindowManagerImpl) getSystemService(WINDOW_SERVICE);
                wm.setDefaultToken(windowToken);
            }

            @Override
            public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
                return AccessibilityService.this.onGesture(gestureEvent);
            }

            @Override
            public boolean onKeyEvent(KeyEvent event) {
                return AccessibilityService.this.onKeyEvent(event);
            }

            @Override
            public void onMagnificationChanged(int displayId, @NonNull Region region,
                    float scale, float centerX, float centerY) {
                AccessibilityService.this.onMagnificationChanged(displayId, region, scale,
                        centerX, centerY);
            }

            @Override
            public void onSoftKeyboardShowModeChanged(int showMode) {
                AccessibilityService.this.onSoftKeyboardShowModeChanged(showMode);
            }

            @Override
            public void onPerformGestureResult(int sequence, boolean completedSuccessfully) {
                AccessibilityService.this.onPerformGestureResult(sequence, completedSuccessfully);
            }

            @Override
            public void onFingerprintCapturingGesturesChanged(boolean active) {
                AccessibilityService.this.onFingerprintCapturingGesturesChanged(active);
            }

            @Override
            public void onFingerprintGesture(int gesture) {
                AccessibilityService.this.onFingerprintGesture(gesture);
            }

            @Override
            public void onAccessibilityButtonClicked(int displayId) {
                AccessibilityService.this.onAccessibilityButtonClicked(displayId);
            }

            @Override
            public void onAccessibilityButtonAvailabilityChanged(boolean available) {
                AccessibilityService.this.onAccessibilityButtonAvailabilityChanged(available);
            }

            @Override
            public void onSystemActionsChanged() {
                AccessibilityService.this.onSystemActionsChanged();
            }
        });
    }

    /**
     * Implements the internal {@link IAccessibilityServiceClient} interface to convert
     * incoming calls to it back to calls on an {@link AccessibilityService}.
     *
     * @hide
     */
    public static class IAccessibilityServiceClientWrapper extends IAccessibilityServiceClient.Stub
            implements HandlerCaller.Callback {
        private static final int DO_INIT = 1;
        private static final int DO_ON_INTERRUPT = 2;
        private static final int DO_ON_ACCESSIBILITY_EVENT = 3;
        private static final int DO_ON_GESTURE = 4;
        private static final int DO_CLEAR_ACCESSIBILITY_CACHE = 5;
        private static final int DO_ON_KEY_EVENT = 6;
        private static final int DO_ON_MAGNIFICATION_CHANGED = 7;
        private static final int DO_ON_SOFT_KEYBOARD_SHOW_MODE_CHANGED = 8;
        private static final int DO_GESTURE_COMPLETE = 9;
        private static final int DO_ON_FINGERPRINT_ACTIVE_CHANGED = 10;
        private static final int DO_ON_FINGERPRINT_GESTURE = 11;
        private static final int DO_ACCESSIBILITY_BUTTON_CLICKED = 12;
        private static final int DO_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED = 13;
        private static final int DO_ON_SYSTEM_ACTIONS_CHANGED = 14;

        private final HandlerCaller mCaller;

        private final Callbacks mCallback;

        private int mConnectionId = AccessibilityInteractionClient.NO_ID;

        public IAccessibilityServiceClientWrapper(Context context, Looper looper,
                Callbacks callback) {
            mCallback = callback;
            mCaller = new HandlerCaller(context, looper, this, true /*asyncHandler*/);
        }

        public void init(IAccessibilityServiceConnection connection, int connectionId,
                IBinder windowToken) {
            Message message = mCaller.obtainMessageIOO(DO_INIT, connectionId,
                    connection, windowToken);
            mCaller.sendMessage(message);
        }

        public void onInterrupt() {
            Message message = mCaller.obtainMessage(DO_ON_INTERRUPT);
            mCaller.sendMessage(message);
        }

        public void onAccessibilityEvent(AccessibilityEvent event, boolean serviceWantsEvent) {
            Message message = mCaller.obtainMessageBO(
                    DO_ON_ACCESSIBILITY_EVENT, serviceWantsEvent, event);
            mCaller.sendMessage(message);
        }

        @Override
        public void onGesture(AccessibilityGestureEvent gestureInfo) {
            Message message = mCaller.obtainMessageO(DO_ON_GESTURE, gestureInfo);
            mCaller.sendMessage(message);
        }

        public void clearAccessibilityCache() {
            Message message = mCaller.obtainMessage(DO_CLEAR_ACCESSIBILITY_CACHE);
            mCaller.sendMessage(message);
        }

        @Override
        public void onKeyEvent(KeyEvent event, int sequence) {
            Message message = mCaller.obtainMessageIO(DO_ON_KEY_EVENT, sequence, event);
            mCaller.sendMessage(message);
        }

        /** Magnification changed callbacks for different displays */
        public void onMagnificationChanged(int displayId, @NonNull Region region,
                float scale, float centerX, float centerY) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = region;
            args.arg2 = scale;
            args.arg3 = centerX;
            args.arg4 = centerY;
            args.argi1 = displayId;

            final Message message = mCaller.obtainMessageO(DO_ON_MAGNIFICATION_CHANGED, args);
            mCaller.sendMessage(message);
        }

        public void onSoftKeyboardShowModeChanged(int showMode) {
          final Message message =
                  mCaller.obtainMessageI(DO_ON_SOFT_KEYBOARD_SHOW_MODE_CHANGED, showMode);
          mCaller.sendMessage(message);
        }

        public void onPerformGestureResult(int sequence, boolean successfully) {
            Message message = mCaller.obtainMessageII(DO_GESTURE_COMPLETE, sequence,
                    successfully ? 1 : 0);
            mCaller.sendMessage(message);
        }

        public void onFingerprintCapturingGesturesChanged(boolean active) {
            mCaller.sendMessage(mCaller.obtainMessageI(
                    DO_ON_FINGERPRINT_ACTIVE_CHANGED, active ? 1 : 0));
        }

        public void onFingerprintGesture(int gesture) {
            mCaller.sendMessage(mCaller.obtainMessageI(DO_ON_FINGERPRINT_GESTURE, gesture));
        }

        /** Accessibility button clicked callbacks for different displays */
        public void onAccessibilityButtonClicked(int displayId) {
            final Message message = mCaller.obtainMessageI(DO_ACCESSIBILITY_BUTTON_CLICKED,
                    displayId);
            mCaller.sendMessage(message);
        }

        public void onAccessibilityButtonAvailabilityChanged(boolean available) {
            final Message message = mCaller.obtainMessageI(
                    DO_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED, (available ? 1 : 0));
            mCaller.sendMessage(message);
        }

        /** This is called when the system action list is changed. */
        public void onSystemActionsChanged() {
            mCaller.sendMessage(mCaller.obtainMessage(DO_ON_SYSTEM_ACTIONS_CHANGED));
        }

        @Override
        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ON_ACCESSIBILITY_EVENT: {
                    AccessibilityEvent event = (AccessibilityEvent) message.obj;
                    boolean serviceWantsEvent = message.arg1 != 0;
                    if (event != null) {
                        // Send the event to AccessibilityCache via AccessibilityInteractionClient
                        AccessibilityInteractionClient.getInstance().onAccessibilityEvent(event);
                        if (serviceWantsEvent
                                && (mConnectionId != AccessibilityInteractionClient.NO_ID)) {
                            // Send the event to AccessibilityService
                            mCallback.onAccessibilityEvent(event);
                        }
                        // Make sure the event is recycled.
                        try {
                            event.recycle();
                        } catch (IllegalStateException ise) {
                            /* ignore - best effort */
                        }
                    }
                    return;
                }
                case DO_ON_INTERRUPT: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onInterrupt();
                    }
                    return;
                }
                case DO_INIT: {
                    mConnectionId = message.arg1;
                    SomeArgs args = (SomeArgs) message.obj;
                    IAccessibilityServiceConnection connection =
                            (IAccessibilityServiceConnection) args.arg1;
                    IBinder windowToken = (IBinder) args.arg2;
                    args.recycle();
                    if (connection != null) {
                        AccessibilityInteractionClient.getInstance().addConnection(mConnectionId,
                                connection);
                        mCallback.init(mConnectionId, windowToken);
                        mCallback.onServiceConnected();
                    } else {
                        AccessibilityInteractionClient.getInstance().removeConnection(
                                mConnectionId);
                        mConnectionId = AccessibilityInteractionClient.NO_ID;
                        AccessibilityInteractionClient.getInstance().clearCache();
                        mCallback.init(AccessibilityInteractionClient.NO_ID, null);
                    }
                    return;
                }
                case DO_ON_GESTURE: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onGesture((AccessibilityGestureEvent) message.obj);
                    }
                    return;
                }
                case DO_CLEAR_ACCESSIBILITY_CACHE: {
                    AccessibilityInteractionClient.getInstance().clearCache();
                    return;
                }
                case DO_ON_KEY_EVENT: {
                    KeyEvent event = (KeyEvent) message.obj;
                    try {
                        IAccessibilityServiceConnection connection = AccessibilityInteractionClient
                                .getInstance().getConnection(mConnectionId);
                        if (connection != null) {
                            final boolean result = mCallback.onKeyEvent(event);
                            final int sequence = message.arg1;
                            try {
                                connection.setOnKeyEventResult(result, sequence);
                            } catch (RemoteException re) {
                                /* ignore */
                            }
                        }
                    } finally {
                        // Make sure the event is recycled.
                        try {
                            event.recycle();
                        } catch (IllegalStateException ise) {
                            /* ignore - best effort */
                        }
                    }
                    return;
                }
                case DO_ON_MAGNIFICATION_CHANGED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        final SomeArgs args = (SomeArgs) message.obj;
                        final Region region = (Region) args.arg1;
                        final float scale = (float) args.arg2;
                        final float centerX = (float) args.arg3;
                        final float centerY = (float) args.arg4;
                        final int displayId = args.argi1;
                        args.recycle();
                        mCallback.onMagnificationChanged(displayId, region, scale,
                                centerX, centerY);
                    }
                    return;
                }
                case DO_ON_SOFT_KEYBOARD_SHOW_MODE_CHANGED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        final int showMode = (int) message.arg1;
                        mCallback.onSoftKeyboardShowModeChanged(showMode);
                    }
                    return;
                }
                case DO_GESTURE_COMPLETE: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        final boolean successfully = message.arg2 == 1;
                        mCallback.onPerformGestureResult(message.arg1, successfully);
                    }
                    return;
                }
                case DO_ON_FINGERPRINT_ACTIVE_CHANGED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onFingerprintCapturingGesturesChanged(message.arg1 == 1);
                    }
                    return;
                }
                case DO_ON_FINGERPRINT_GESTURE: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onFingerprintGesture(message.arg1);
                    }
                    return;
                }
                case DO_ACCESSIBILITY_BUTTON_CLICKED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onAccessibilityButtonClicked(message.arg1);
                    }
                    return;
                }
                case DO_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        final boolean available = (message.arg1 != 0);
                        mCallback.onAccessibilityButtonAvailabilityChanged(available);
                    }
                    return;
                }
                case DO_ON_SYSTEM_ACTIONS_CHANGED: {
                    if (mConnectionId != AccessibilityInteractionClient.NO_ID) {
                        mCallback.onSystemActionsChanged();
                    }
                    return;
                }
                default :
                    Log.w(LOG_TAG, "Unknown message type " + message.what);
            }
        }
    }

    /**
     * Class used to report status of dispatched gestures
     */
    public static abstract class GestureResultCallback {
        /** Called when the gesture has completed successfully
         *
         * @param gestureDescription The description of the gesture that completed.
         */
        public void onCompleted(GestureDescription gestureDescription) {
        }

        /** Called when the gesture was cancelled
         *
         * @param gestureDescription The description of the gesture that was cancelled.
         */
        public void onCancelled(GestureDescription gestureDescription) {
        }
    }

    /* Object to keep track of gesture result callbacks */
    private static class GestureResultCallbackInfo {
        GestureDescription gestureDescription;
        GestureResultCallback callback;
        Handler handler;

        GestureResultCallbackInfo(GestureDescription gestureDescription,
                GestureResultCallback callback, Handler handler) {
            this.gestureDescription = gestureDescription;
            this.callback = callback;
            this.handler = handler;
        }
    }

    private void sendScreenshotSuccess(ScreenshotResult screenshot, Executor executor,
            TakeScreenshotCallback callback) {
        executor.execute(() -> callback.onSuccess(screenshot));
    }

    private void sendScreenshotFailure(@ScreenshotErrorCode int errorCode, Executor executor,
            TakeScreenshotCallback callback) {
        executor.execute(() -> callback.onFailure(errorCode));
    }

    /**
     * Interface used to report status of taking screenshot.
     */
    public interface TakeScreenshotCallback {
        /** Called when taking screenshot has completed successfully.
         *
         * @param screenshot The content of screenshot.
         */
        void onSuccess(@NonNull ScreenshotResult screenshot);

        /** Called when taking screenshot has failed. {@code errorCode} will identify the
         * reason of failure.
         *
         * @param errorCode The error code of this operation.
         */
        void onFailure(@ScreenshotErrorCode int errorCode);
    }

    /**
     * Can be used to construct a bitmap of the screenshot or any other operations for
     * {@link AccessibilityService#takeScreenshot} API.
     */
    public static final class ScreenshotResult {
        private final @NonNull HardwareBuffer mHardwareBuffer;
        private final @NonNull ColorSpace mColorSpace;
        private final long mTimestamp;

        private ScreenshotResult(@NonNull HardwareBuffer hardwareBuffer,
                @NonNull ColorSpace colorSpace, long timestamp) {
            Preconditions.checkNotNull(hardwareBuffer, "hardwareBuffer cannot be null");
            Preconditions.checkNotNull(colorSpace, "colorSpace cannot be null");
            mHardwareBuffer = hardwareBuffer;
            mColorSpace = colorSpace;
            mTimestamp = timestamp;
        }

        /**
         * Gets the {@link ColorSpace} identifying a specific organization of colors of the
         * screenshot.
         *
         * @return the color space
         */
        @NonNull
        public ColorSpace getColorSpace() {
            return mColorSpace;
        }

        /**
         * Gets the {@link HardwareBuffer} representing a memory buffer of the screenshot.
         * <p>
         * <strong>Note:</strong> The application should call {@link HardwareBuffer#close()} when
         * the buffer is no longer needed to free the underlying resources.
         * </p>
         *
         * @return the hardware buffer
         */
        @NonNull
        public HardwareBuffer getHardwareBuffer() {
            return mHardwareBuffer;
        }

        /**
         * Gets the timestamp of taking the screenshot.
         *
         * @return milliseconds of non-sleep uptime before screenshot since boot and it's from
         * {@link SystemClock#uptimeMillis()}
         */
        public long getTimestamp() {
            return mTimestamp;
        };
    }

    /**
     * When {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled, this
     * function requests that touch interactions starting in the specified region of the screen
     * bypass the gesture detector. There can only be one gesture detection passthrough region per
     * display. Requesting a new gesture detection passthrough region clears the existing one. To
     * disable this passthrough and return to the original behavior, pass in an empty region. When
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is disabled this
     * function has no effect.
     *
     * @param displayId The display on which to set this region.
     * @param region the region of the screen.
     */
    public void setGestureDetectionPassthroughRegion(int displayId, @NonNull Region region) {
        Preconditions.checkNotNull(region, "region cannot be null");
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                connection.setGestureDetectionPassthroughRegion(displayId, region);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * When {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled, this
     * function requests that touch interactions starting in the specified region of the screen
     * bypass the touch explorer and go straight to the view hierarchy. There can only be one touch
     * exploration passthrough region per display. Requesting a new touch explorationpassthrough
     * region clears the existing one. To disable this passthrough and return to the original
     * behavior, pass in an empty region. When {@link
     * AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is disabled this function has
     * no effect.
     *
     * @param displayId The display on which to set this region.
     * @param region the region of the screen .
     */
    public void setTouchExplorationPassthroughRegion(int displayId, @NonNull Region region) {
        Preconditions.checkNotNull(region, "region cannot be null");
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                connection.setTouchExplorationPassthroughRegion(displayId, region);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }
}
