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

import static android.accessibilityservice.util.AccessibilityUtils.getFilteredHtmlText;
import static android.accessibilityservice.util.AccessibilityUtils.loadSafeAnimatedImage;
import static android.content.pm.PackageManager.FEATURE_FINGERPRINT;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.Xml;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;
import com.android.internal.compat.IPlatformCompat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class describes an {@link AccessibilityService}. The system notifies an
 * {@link AccessibilityService} for {@link android.view.accessibility.AccessibilityEvent}s
 * according to the information encapsulated in this class.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating AccessibilityServices, read the
 * <a href="{@docRoot}guide/topics/ui/accessibility/index.html">Accessibility</a>
 * developer guide.</p>
 * </div>
 *
 * @attr ref android.R.styleable#AccessibilityService_accessibilityEventTypes
 * @attr ref android.R.styleable#AccessibilityService_accessibilityFeedbackType
 * @attr ref android.R.styleable#AccessibilityService_accessibilityFlags
 * @attr ref android.R.styleable#AccessibilityService_canRequestEnhancedWebAccessibility
 * @attr ref android.R.styleable#AccessibilityService_canRequestFilterKeyEvents
 * @attr ref android.R.styleable#AccessibilityService_canRequestTouchExplorationMode
 * @attr ref android.R.styleable#AccessibilityService_canRetrieveWindowContent
 * @attr ref android.R.styleable#AccessibilityService_intro
 * @attr ref android.R.styleable#AccessibilityService_description
 * @attr ref android.R.styleable#AccessibilityService_summary
 * @attr ref android.R.styleable#AccessibilityService_notificationTimeout
 * @attr ref android.R.styleable#AccessibilityService_packageNames
 * @attr ref android.R.styleable#AccessibilityService_settingsActivity
 * @attr ref android.R.styleable#AccessibilityService_tileService
 * @attr ref android.R.styleable#AccessibilityService_nonInteractiveUiTimeout
 * @attr ref android.R.styleable#AccessibilityService_interactiveUiTimeout
 * @attr ref android.R.styleable#AccessibilityService_canTakeScreenshot
 * @see AccessibilityService
 * @see android.view.accessibility.AccessibilityEvent
 * @see android.view.accessibility.AccessibilityManager
 */
public class AccessibilityServiceInfo implements Parcelable {

    private static final String TAG_ACCESSIBILITY_SERVICE = "accessibility-service";

    /**
     * Capability: This accessibility service can retrieve the active window content.
     * @see android.R.styleable#AccessibilityService_canRetrieveWindowContent
     */
    public static final int CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT = 0x00000001;

    /**
     * Capability: This accessibility service can request touch exploration mode in which
     * touched items are spoken aloud and the UI can be explored via gestures.
     * @see android.R.styleable#AccessibilityService_canRequestTouchExplorationMode
     */
    public static final int CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION = 0x00000002;

    /**
     * @deprecated No longer used
     */
    public static final int CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY = 0x00000004;

    /**
     * Capability: This accessibility service can request to filter the key event stream.
     * @see android.R.styleable#AccessibilityService_canRequestFilterKeyEvents
     */
    public static final int CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS = 0x00000008;

    /**
     * Capability: This accessibility service can control display magnification.
     * @see android.R.styleable#AccessibilityService_canControlMagnification
     */
    public static final int CAPABILITY_CAN_CONTROL_MAGNIFICATION = 0x00000010;

    /**
     * Capability: This accessibility service can perform gestures.
     * @see android.R.styleable#AccessibilityService_canPerformGestures
     */
    public static final int CAPABILITY_CAN_PERFORM_GESTURES = 0x00000020;

    /**
     * Capability: This accessibility service can capture gestures from the fingerprint sensor
     * @see android.R.styleable#AccessibilityService_canRequestFingerprintGestures
     */
    public static final int CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES = 0x00000040;

    /**
     * Capability: This accessibility service can take screenshot.
     * @see android.R.styleable#AccessibilityService_canTakeScreenshot
     */
    public static final int CAPABILITY_CAN_TAKE_SCREENSHOT = 0x00000080;

    private static SparseArray<CapabilityInfo> sAvailableCapabilityInfos;

    /**
     * Denotes spoken feedback.
     */
    public static final int FEEDBACK_SPOKEN = 0x0000001;

    /**
     * Denotes haptic feedback.
     */
    public static final int FEEDBACK_HAPTIC =  0x0000002;

    /**
     * Denotes audible (not spoken) feedback.
     */
    public static final int FEEDBACK_AUDIBLE = 0x0000004;

    /**
     * Denotes visual feedback.
     */
    public static final int FEEDBACK_VISUAL = 0x0000008;

    /**
     * Denotes generic feedback.
     */
    public static final int FEEDBACK_GENERIC = 0x0000010;

    /**
     * Denotes braille feedback.
     */
    public static final int FEEDBACK_BRAILLE = 0x0000020;

    /**
     * Mask for all feedback types.
     *
     * @see #FEEDBACK_SPOKEN
     * @see #FEEDBACK_HAPTIC
     * @see #FEEDBACK_AUDIBLE
     * @see #FEEDBACK_VISUAL
     * @see #FEEDBACK_GENERIC
     * @see #FEEDBACK_BRAILLE
     */
    public static final int FEEDBACK_ALL_MASK = 0xFFFFFFFF;

    /**
     * If an {@link AccessibilityService} is the default for a given type.
     * Default service is invoked only if no package specific one exists. In case of
     * more than one package specific service only the earlier registered is notified.
     */
    public static final int DEFAULT = 0x0000001;

    /**
     * If this flag is set the system will regard views that are not important
     * for accessibility in addition to the ones that are important for accessibility.
     * That is, views that are marked as not important for accessibility via
     * {@link View#IMPORTANT_FOR_ACCESSIBILITY_NO} or
     * {@link View#IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS} and views that are
     * marked as potentially important for accessibility via
     * {@link View#IMPORTANT_FOR_ACCESSIBILITY_AUTO} for which the system has determined
     * that are not important for accessibility, are reported while querying the window
     * content and also the accessibility service will receive accessibility events from
     * them.
     * <p>
     * <strong>Note:</strong> For accessibility services targeting Android 4.1 (API level 16) or
     * higher, this flag has to be explicitly set for the system to regard views that are not
     * important for accessibility. For accessibility services targeting Android 4.0.4 (API level
     * 15) or lower, this flag is ignored and all views are regarded for accessibility purposes.
     * </p>
     * <p>
     * Usually views not important for accessibility are layout managers that do not
     * react to user actions, do not draw any content, and do not have any special
     * semantics in the context of the screen content. For example, a three by three
     * grid can be implemented as three horizontal linear layouts and one vertical,
     * or three vertical linear layouts and one horizontal, or one grid layout, etc.
     * In this context, the actual layout managers used to achieve the grid configuration
     * are not important; rather it is important that there are nine evenly distributed
     * elements.
     * </p>
     */
    public static final int FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 0x0000002;

    /**
     * This flag requests that the system gets into touch exploration mode.
     * In this mode a single finger moving on the screen behaves as a mouse
     * pointer hovering over the user interface. The system will also detect
     * certain gestures performed on the touch screen and notify this service.
     * The system will enable touch exploration mode if there is at least one
     * accessibility service that has this flag set. Hence, clearing this
     * flag does not guarantee that the device will not be in touch exploration
     * mode since there may be another enabled service that requested it.
     * <p>
     * For accessibility services targeting Android 4.3 (API level 18) or higher
     * that want to set this flag have to declare this capability in their
     * meta-data by setting the attribute
     * {@link android.R.attr#canRequestTouchExplorationMode
     * canRequestTouchExplorationMode} to true. Otherwise, this flag will
     * be ignored. For how to declare the meta-data of a service refer to
     * {@value AccessibilityService#SERVICE_META_DATA}.
     * </p>
     * <p>
     * Services targeting Android 4.2.2 (API level 17) or lower will work
     * normally. In other words, the first time they are run, if this flag is
     * specified, a dialog is shown to the user to confirm enabling explore by
     * touch.
     * </p>
     * @see android.R.styleable#AccessibilityService_canRequestTouchExplorationMode
     */
    public static final int FLAG_REQUEST_TOUCH_EXPLORATION_MODE = 0x0000004;

    /**
     * @deprecated No longer used
     */
    public static final int FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY = 0x00000008;

    /**
     * This flag requests that the {@link AccessibilityNodeInfo}s obtained
     * by an {@link AccessibilityService} contain the id of the source view.
     * The source view id will be a fully qualified resource name of the
     * form "package:id/name", for example "foo.bar:id/my_list", and it is
     * useful for UI test automation. This flag is not set by default.
     */
    public static final int FLAG_REPORT_VIEW_IDS = 0x00000010;

    /**
     * This flag requests from the system to filter key events. If this flag
     * is set the accessibility service will receive the key events before
     * applications allowing it implement global shortcuts.
     * <p>
     * Services that want to set this flag have to declare this capability
     * in their meta-data by setting the attribute {@link android.R.attr
     * #canRequestFilterKeyEvents canRequestFilterKeyEvents} to true,
     * otherwise this flag will be ignored. For how to declare the meta-data
     * of a service refer to {@value AccessibilityService#SERVICE_META_DATA}.
     * </p>
     * @see android.R.styleable#AccessibilityService_canRequestFilterKeyEvents
     */
    public static final int FLAG_REQUEST_FILTER_KEY_EVENTS = 0x00000020;

    /**
     * This flag indicates to the system that the accessibility service wants
     * to access content of all interactive windows. An interactive window is a
     * window that has input focus or can be touched by a sighted user when explore
     * by touch is not enabled. If this flag is not set your service will not receive
     * {@link android.view.accessibility.AccessibilityEvent#TYPE_WINDOWS_CHANGED}
     * events, calling AccessibilityService{@link AccessibilityService#getWindows()
     * AccessibilityService.getWindows()} will return an empty list, and {@link
     * AccessibilityNodeInfo#getWindow() AccessibilityNodeInfo.getWindow()} will
     * return null.
     * <p>
     * Services that want to set this flag have to declare the capability
     * to retrieve window content in their meta-data by setting the attribute
     * {@link android.R.attr#canRetrieveWindowContent canRetrieveWindowContent} to
     * true, otherwise this flag will be ignored. For how to declare the meta-data
     * of a service refer to {@value AccessibilityService#SERVICE_META_DATA}.
     * </p>
     * @see android.R.styleable#AccessibilityService_canRetrieveWindowContent
     */
    public static final int FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 0x00000040;

    /**
     * This flag requests that all audio tracks system-wide with
     * {@link android.media.AudioAttributes#USAGE_ASSISTANCE_ACCESSIBILITY} be controlled by the
     * {@link android.media.AudioManager#STREAM_ACCESSIBILITY} volume.
     */
    public static final int FLAG_ENABLE_ACCESSIBILITY_VOLUME = 0x00000080;

     /**
     * This flag indicates to the system that the accessibility service requests that an
     * accessibility button be shown within the system's navigation area, if available.
      * <p>
      *   <strong>Note:</strong> For accessibility services targeting APIs greater than
      *   {@link Build.VERSION_CODES#Q API 29}, this flag must be specified in the
      *   accessibility service metadata file. Otherwise, it will be ignored.
      * </p>
     */
    public static final int FLAG_REQUEST_ACCESSIBILITY_BUTTON = 0x00000100;

    /**
     * This flag requests that all fingerprint gestures be sent to the accessibility service.
     * <p>
     * Services that want to set this flag have to declare the capability
     * to retrieve window content in their meta-data by setting the attribute
     * {@link android.R.attr#canRequestFingerprintGestures} to
     * true, otherwise this flag will be ignored. For how to declare the meta-data
     * of a service refer to {@value AccessibilityService#SERVICE_META_DATA}.
     * </p>
     *
     * @see android.R.styleable#AccessibilityService_canRequestFingerprintGestures
     * @see AccessibilityService#getFingerprintGestureController()
     */
    public static final int FLAG_REQUEST_FINGERPRINT_GESTURES = 0x00000200;

    /**
     * This flag requests that accessibility shortcut warning dialog has spoken feedback when
     * dialog is shown.
     */
    public static final int FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK = 0x00000400;

    /**
     * This flag requests that when {@link #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled,
     * double tap and double tap and hold gestures are dispatched to the service rather than being
     * handled by the framework. If {@link #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is disabled this
     * flag has no effect.
     *
     * @see #FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     */
    public static final int FLAG_SERVICE_HANDLES_DOUBLE_TAP = 0x0000800;

    /**
     * This flag requests that when when {@link #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled,
     * multi-finger gestures are also enabled. As a consequence, two-finger bypass gestures will be
     * disabled. If {@link #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is disabled this flag has no
     * effect.
     *
     * @see #FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     */
    public static final int FLAG_REQUEST_MULTI_FINGER_GESTURES = 0x0001000;

    /**
     * This flag requests that when when {@link #FLAG_REQUEST_MULTI_FINGER_GESTURES} is enabled,
     * two-finger passthrough gestures are re-enabled. Two-finger swipe gestures are not detected,
     * but instead passed through as one-finger gestures. In addition, three-finger swipes from the
     * bottom of the screen are not detected, and instead are passed through unchanged. If {@link
     * #FLAG_REQUEST_MULTI_FINGER_GESTURES} is disabled this flag has no effect.
     *
     * @see #FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     */
    public static final int FLAG_REQUEST_2_FINGER_PASSTHROUGH = 0x0002000;

    /**
     * This flag requests that when when {@link #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled, a
     * service will receive the motion events for each successfully-detected gesture. The service
     * will also receive an AccessibilityGestureEvent of type GESTURE_INVALID for each cancelled
     * gesture along with its motion events. A service will receive a gesture of type
     * GESTURE_PASSTHROUGH and accompanying motion events for every passthrough gesture that does
     * not start gesture detection. This information can be used to collect instances of improper
     * gesture detection behavior and relay that information to framework developers. If {@link
     * #FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is disabled this flag has no effect.
     *
     * @see #FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     */
    public static final int FLAG_SEND_MOTION_EVENTS = 0x0004000;

    /**
     * This flag makes the AccessibilityService an input method editor with a subset of input
     * method editor capabilities: get the {@link android.view.inputmethod.InputConnection} and get
     * text selection change notifications.
     *
     * @see AccessibilityService#getInputMethod()
     */
    public static final int FLAG_INPUT_METHOD_EDITOR = 0x0008000;

    /** {@hide} */
    public static final int FLAG_FORCE_DIRECT_BOOT_AWARE = 0x00010000;

    /**
     * The event types an {@link AccessibilityService} is interested in.
     * <p>
     *   <strong>Can be dynamically set at runtime.</strong>
     * </p>
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_CLICKED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_LONG_CLICKED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_FOCUSED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_SELECTED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_START
     * @see android.view.accessibility.AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_HOVER_ENTER
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_HOVER_EXIT
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_SCROLLED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_TOUCH_INTERACTION_START
     * @see android.view.accessibility.AccessibilityEvent#TYPE_TOUCH_INTERACTION_END
     * @see android.view.accessibility.AccessibilityEvent#TYPE_ANNOUNCEMENT
     * @see android.view.accessibility.AccessibilityEvent#TYPE_GESTURE_DETECTION_START
     * @see android.view.accessibility.AccessibilityEvent#TYPE_GESTURE_DETECTION_END
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUSED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
     * @see android.view.accessibility.AccessibilityEvent#TYPE_WINDOWS_CHANGED
     */
    public int eventTypes;

    /**
     * The package names an {@link AccessibilityService} is interested in. Setting
     * to <code>null</code> is equivalent to all packages.
     * <p>
     *   <strong>Can be dynamically set at runtime.</strong>
     * </p>
     */
    public String[] packageNames;


    /** @hide */
    @IntDef(flag = true, prefix = { "FEEDBACK_" }, value = {
            FEEDBACK_AUDIBLE,
            FEEDBACK_GENERIC,
            FEEDBACK_HAPTIC,
            FEEDBACK_SPOKEN,
            FEEDBACK_VISUAL,
            FEEDBACK_BRAILLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FeedbackType {}

    /**
     * The feedback type an {@link AccessibilityService} provides.
     * <p>
     *   <strong>Can be dynamically set at runtime.</strong>
     * </p>
     * @see #FEEDBACK_AUDIBLE
     * @see #FEEDBACK_GENERIC
     * @see #FEEDBACK_HAPTIC
     * @see #FEEDBACK_SPOKEN
     * @see #FEEDBACK_VISUAL
     * @see #FEEDBACK_BRAILLE
     */
    @FeedbackType
    public int feedbackType;

    /**
     * The timeout, in milliseconds, after the most recent event of a given type before an
     * {@link AccessibilityService} is notified.
     * <p>
     *   <strong>Can be dynamically set at runtime.</strong>
     * </p>
     * <p>
     * <strong>Note:</strong> The event notification timeout is useful to avoid propagating
     *       events to the client too frequently since this is accomplished via an expensive
     *       interprocess call. One can think of the timeout as a criteria to determine when
     *       event generation has settled down.
     */
    public long notificationTimeout;

    /**
     * This field represents a set of flags used for configuring an
     * {@link AccessibilityService}.
     * <p>
     *   <strong>Can be dynamically set at runtime.</strong>
     * </p>
     * <p>
     *   <strong>Note:</strong> Accessibility services with targetSdkVersion greater than
     *   {@link Build.VERSION_CODES#Q API 29} cannot dynamically set the
     *   {@link #FLAG_REQUEST_ACCESSIBILITY_BUTTON} at runtime. It must be specified in the
     *   accessibility service metadata file.
     * </p>
     * @see #DEFAULT
     * @see #FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
     * @see #FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     * @see #FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
     * @see #FLAG_REQUEST_FILTER_KEY_EVENTS
     * @see #FLAG_REPORT_VIEW_IDS
     * @see #FLAG_RETRIEVE_INTERACTIVE_WINDOWS
     * @see #FLAG_ENABLE_ACCESSIBILITY_VOLUME
     * @see #FLAG_REQUEST_ACCESSIBILITY_BUTTON
     * @see #FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK
     * @see #FLAG_INPUT_METHOD_EDITOR
     */
    public int flags;

    /**
     * Whether or not the service has crashed and is awaiting restart. Only valid from {@link
     * android.view.accessibility.AccessibilityManager#getInstalledAccessibilityServiceList()},
     * because that is populated from the internal list of running services.
     *
     * @hide
     */
    public boolean crashed;

    /**
     * A recommended timeout in milliseconds for non-interactive controls.
     */
    private int mNonInteractiveUiTimeout;

    /**
     * A recommended timeout in milliseconds for interactive controls.
     */
    private int mInteractiveUiTimeout;

    /**
     * The component name the accessibility service.
     */
    @NonNull
    private ComponentName mComponentName;

    /**
     * The Service that implements this accessibility service component.
     */
    private ResolveInfo mResolveInfo;

    /**
     * The accessibility service setting activity's name, used by the system
     * settings to launch the setting activity of this accessibility service.
     */
    private String mSettingsActivityName;

    /**
     * The name of {@link android.service.quicksettings.TileService} is associated with this
     * accessibility service for one to one mapping. It is used by system settings to remind users
     * this accessibility service has a {@link android.service.quicksettings.TileService}.
     */
    private String mTileServiceName;

    /**
     * Bit mask with capabilities of this service.
     */
    private int mCapabilities;

    /**
     * Resource id of the summary of the accessibility service.
     */
    private int mSummaryResId;

    /**
     * Non-localized summary of the accessibility service.
     */
    private String mNonLocalizedSummary;

    /**
     * Resource id of the intro of the accessibility service.
     */
    private int mIntroResId;

    /**
     * Resource id of the description of the accessibility service.
     */
    private int mDescriptionResId;

    /**
     * Non localized description of the accessibility service.
     */
    private String mNonLocalizedDescription;

    /**
     * For accessibility services targeting APIs greater than {@link Build.VERSION_CODES#Q API 29},
     * {@link #FLAG_REQUEST_ACCESSIBILITY_BUTTON} must be specified in the accessibility service
     * metadata file. Otherwise, it will be ignored.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.Q)
    private static final long REQUEST_ACCESSIBILITY_BUTTON_CHANGE = 136293963L;

    /**
     * Resource id of the animated image of the accessibility service.
     */
    private int mAnimatedImageRes;

    /**
     * Resource id of the html description of the accessibility service.
     */
    private int mHtmlDescriptionRes;

    /**
     * Whether the service is for accessibility.
     *
     * @hide
     */
    private boolean mIsAccessibilityTool = false;

    /**
     * Creates a new instance.
     */
    public AccessibilityServiceInfo() {
        /* do nothing */
    }

    /**
     * Creates a new instance.
     *
     * @param resolveInfo The service resolve info.
     * @param context Context for accessing resources.
     * @throws XmlPullParserException If a XML parsing error occurs.
     * @throws IOException If a XML parsing error occurs.
     *
     * @hide
     */
    public AccessibilityServiceInfo(ResolveInfo resolveInfo, Context context)
            throws XmlPullParserException, IOException {
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        mComponentName = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        mResolveInfo = resolveInfo;

        XmlResourceParser parser = null;

        try {
            PackageManager packageManager = context.getPackageManager();
            parser = serviceInfo.loadXmlMetaData(packageManager,
                    AccessibilityService.SERVICE_META_DATA);
            if (parser == null) {
                return;
            }

            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            String nodeName = parser.getName();
            if (!TAG_ACCESSIBILITY_SERVICE.equals(nodeName)) {
                throw new XmlPullParserException( "Meta-data does not start with"
                        + TAG_ACCESSIBILITY_SERVICE + " tag");
            }

            AttributeSet allAttributes = Xml.asAttributeSet(parser);
            Resources resources = packageManager.getResourcesForApplication(
                    serviceInfo.applicationInfo);
            TypedArray asAttributes = resources.obtainAttributes(allAttributes,
                    com.android.internal.R.styleable.AccessibilityService);
            eventTypes = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_accessibilityEventTypes,
                    0);
            String packageNamez = asAttributes.getString(
                    com.android.internal.R.styleable.AccessibilityService_packageNames);
            if (packageNamez != null) {
                packageNames = packageNamez.split("(\\s)*,(\\s)*");
            }
            feedbackType = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_accessibilityFeedbackType,
                    0);
            notificationTimeout = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_notificationTimeout,
                    0);
            mNonInteractiveUiTimeout = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_nonInteractiveUiTimeout,
                    0);
            mInteractiveUiTimeout = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_interactiveUiTimeout,
                    0);
            flags = asAttributes.getInt(
                    com.android.internal.R.styleable.AccessibilityService_accessibilityFlags, 0);
            mSettingsActivityName = asAttributes.getString(
                    com.android.internal.R.styleable.AccessibilityService_settingsActivity);
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canRetrieveWindowContent, false)) {
                mCapabilities |= CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canRequestTouchExplorationMode, false)) {
                mCapabilities |= CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canRequestFilterKeyEvents, false)) {
                mCapabilities |= CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canControlMagnification, false)) {
                mCapabilities |= CAPABILITY_CAN_CONTROL_MAGNIFICATION;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canPerformGestures, false)) {
                mCapabilities |= CAPABILITY_CAN_PERFORM_GESTURES;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canRequestFingerprintGestures, false)) {
                mCapabilities |= CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES;
            }
            if (asAttributes.getBoolean(com.android.internal.R.styleable
                    .AccessibilityService_canTakeScreenshot, false)) {
                mCapabilities |= CAPABILITY_CAN_TAKE_SCREENSHOT;
            }
            TypedValue peekedValue = asAttributes.peekValue(
                    com.android.internal.R.styleable.AccessibilityService_description);
            if (peekedValue != null) {
                mDescriptionResId = peekedValue.resourceId;
                CharSequence nonLocalizedDescription = peekedValue.coerceToString();
                if (nonLocalizedDescription != null) {
                    mNonLocalizedDescription = nonLocalizedDescription.toString().trim();
                }
            }
            peekedValue = asAttributes.peekValue(
                com.android.internal.R.styleable.AccessibilityService_summary);
            if (peekedValue != null) {
                mSummaryResId = peekedValue.resourceId;
                CharSequence nonLocalizedSummary = peekedValue.coerceToString();
                if (nonLocalizedSummary != null) {
                    mNonLocalizedSummary = nonLocalizedSummary.toString().trim();
                }
            }
            peekedValue = asAttributes.peekValue(
                    com.android.internal.R.styleable.AccessibilityService_animatedImageDrawable);
            if (peekedValue != null) {
                mAnimatedImageRes = peekedValue.resourceId;
            }
            peekedValue = asAttributes.peekValue(
                    com.android.internal.R.styleable.AccessibilityService_htmlDescription);
            if (peekedValue != null) {
                mHtmlDescriptionRes = peekedValue.resourceId;
            }
            mIsAccessibilityTool = asAttributes.getBoolean(
                    R.styleable.AccessibilityService_isAccessibilityTool, false);
            mTileServiceName = asAttributes.getString(
                    com.android.internal.R.styleable.AccessibilityService_tileService);
            peekedValue = asAttributes.peekValue(
                    com.android.internal.R.styleable.AccessibilityService_intro);
            if (peekedValue != null) {
                mIntroResId = peekedValue.resourceId;
            }
            asAttributes.recycle();
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException( "Unable to create context for: "
                    + serviceInfo.packageName);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /**
     * Updates the properties that an AccessibilityService can change dynamically.
     * <p>
     * Note: A11y services targeting APIs > Q, it cannot update flagRequestAccessibilityButton
     * dynamically.
     * </p>
     *
     * @param platformCompat The platform compat service to check the compatibility change.
     * @param other The info from which to update the properties.
     *
     * @hide
     */
    public void updateDynamicallyConfigurableProperties(IPlatformCompat platformCompat,
            AccessibilityServiceInfo other) {
        if (isRequestAccessibilityButtonChangeEnabled(platformCompat)) {
            other.flags &= ~FLAG_REQUEST_ACCESSIBILITY_BUTTON;
            other.flags |= (flags & FLAG_REQUEST_ACCESSIBILITY_BUTTON);
        }
        eventTypes = other.eventTypes;
        packageNames = other.packageNames;
        feedbackType = other.feedbackType;
        notificationTimeout = other.notificationTimeout;
        mNonInteractiveUiTimeout = other.mNonInteractiveUiTimeout;
        mInteractiveUiTimeout = other.mInteractiveUiTimeout;
        flags = other.flags;
    }

    private boolean isRequestAccessibilityButtonChangeEnabled(IPlatformCompat platformCompat) {
        if (mResolveInfo == null) {
            return true;
        }
        try {
            if (platformCompat != null) {
                return platformCompat.isChangeEnabled(REQUEST_ACCESSIBILITY_BUTTON_CHANGE,
                        mResolveInfo.serviceInfo.applicationInfo);
            }
        } catch (RemoteException ignore) {
        }
        return mResolveInfo.serviceInfo.applicationInfo.targetSdkVersion > Build.VERSION_CODES.Q;
    }

    /**
     * @hide
     */
    public void setComponentName(@NonNull ComponentName component) {
        mComponentName = component;
    }

    /**
     * @hide
     */
    @TestApi
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * The accessibility service id.
     * <p>
     *   <strong>Generated by the system.</strong>
     * </p>
     * @return The id.
     */
    public String getId() {
        return mComponentName.flattenToShortString();
    }

    /**
     * The service {@link ResolveInfo}.
     * <p>
     *   <strong>Generated by the system.</strong>
     * </p>
     * @return The info.
     */
    public ResolveInfo getResolveInfo() {
        return mResolveInfo;
    }

    /**
     * The settings activity name.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The settings activity name.
     */
    public String getSettingsActivityName() {
        return mSettingsActivityName;
    }

    /**
     * Gets the name of {@link android.service.quicksettings.TileService} is associated with
     * this accessibility service.
     *
     * @return The name of {@link android.service.quicksettings.TileService}.
     */
    @Nullable
    public String getTileServiceName() {
        return mTileServiceName;
    }

    /**
     * Gets the animated image resource id.
     *
     * @return The animated image resource id.
     *
     * @hide
     */
    public int getAnimatedImageRes() {
        return mAnimatedImageRes;
    }

    /**
     * The animated image drawable.
     * <p>
     *    Image can not exceed the screen size.
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The animated image drawable, or null if the resource is invalid or the image
     * exceed the screen size.
     *
     * @hide
     */
    @Nullable
    public Drawable loadAnimatedImage(@NonNull Context context)  {
        if (mAnimatedImageRes == /* invalid */ 0) {
            return null;
        }

        return loadSafeAnimatedImage(context, mResolveInfo.serviceInfo.applicationInfo,
                mAnimatedImageRes);
    }

    /**
     * Whether this service can retrieve the current window's content.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return True if window content can be retrieved.
     *
     * @deprecated Use {@link #getCapabilities()}.
     */
    public boolean getCanRetrieveWindowContent() {
        return (mCapabilities & CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
    }

    /**
     * Returns the bit mask of capabilities this accessibility service has such as
     * being able to retrieve the active window content, etc.
     *
     * @return The capability bit mask.
     *
     * @see #CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
     * @see #CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
     * @see #CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS
     * @see #CAPABILITY_CAN_CONTROL_MAGNIFICATION
     * @see #CAPABILITY_CAN_PERFORM_GESTURES
     * @see #CAPABILITY_CAN_TAKE_SCREENSHOT
     */
    public int getCapabilities() {
        return mCapabilities;
    }

    /**
     * Sets the bit mask of capabilities this accessibility service has such as
     * being able to retrieve the active window content, etc.
     *
     * @param capabilities The capability bit mask.
     *
     * @see #CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
     * @see #CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
     * @see #CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS
     * @see #CAPABILITY_CAN_CONTROL_MAGNIFICATION
     * @see #CAPABILITY_CAN_PERFORM_GESTURES
     * @see #CAPABILITY_CAN_TAKE_SCREENSHOT
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setCapabilities(int capabilities) {
        mCapabilities = capabilities;
    }

    /**
     * The localized summary of the accessibility service.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The localized summary if available, and {@code null} if a summary
     * has not been provided.
     */
    public CharSequence loadSummary(PackageManager packageManager) {
        if (mSummaryResId == 0) {
            return mNonLocalizedSummary;
        }
        ServiceInfo serviceInfo = mResolveInfo.serviceInfo;
        CharSequence summary = packageManager.getText(serviceInfo.packageName,
                mSummaryResId, serviceInfo.applicationInfo);
        if (summary != null) {
            return summary.toString().trim();
        }
        return null;
    }

    /**
     * The localized intro of the accessibility service.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The localized intro if available, and {@code null} if a intro
     * has not been provided.
     */
    @Nullable
    public CharSequence loadIntro(@NonNull PackageManager packageManager) {
        if (mIntroResId == /* invalid */ 0) {
            return null;
        }
        ServiceInfo serviceInfo = mResolveInfo.serviceInfo;
        CharSequence intro = packageManager.getText(serviceInfo.packageName,
                mIntroResId, serviceInfo.applicationInfo);
        if (intro != null) {
            return intro.toString().trim();
        }
        return null;
    }

    /**
     * Gets the non-localized description of the accessibility service.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The description.
     *
     * @deprecated Use {@link #loadDescription(PackageManager)}.
     */
    public String getDescription() {
        return mNonLocalizedDescription;
    }

    /**
     * The localized description of the accessibility service.
     * <p>
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The localized description.
     */
    public String loadDescription(PackageManager packageManager) {
        if (mDescriptionResId == 0) {
            return mNonLocalizedDescription;
        }
        ServiceInfo serviceInfo = mResolveInfo.serviceInfo;
        CharSequence description = packageManager.getText(serviceInfo.packageName,
                mDescriptionResId, serviceInfo.applicationInfo);
        if (description != null) {
            return description.toString().trim();
        }
        return null;
    }

    /**
     * The localized and restricted html description of the accessibility service.
     * <p>
     *    Filters the <img> tag which do not meet the custom specification and the <a> tag.
     *    <strong>Statically set from
     *    {@link AccessibilityService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     * @return The localized and restricted html description.
     *
     * @hide
     */
    @Nullable
    public String loadHtmlDescription(@NonNull PackageManager packageManager) {
        if (mHtmlDescriptionRes == /* invalid */ 0) {
            return null;
        }

        final ServiceInfo serviceInfo = mResolveInfo.serviceInfo;
        final CharSequence htmlDescription = packageManager.getText(serviceInfo.packageName,
                mHtmlDescriptionRes, serviceInfo.applicationInfo);
        if (htmlDescription != null) {
            return getFilteredHtmlText(htmlDescription.toString().trim());
        }
        return null;
    }

    /**
     * Set the recommended time that non-interactive controls need to remain on the screen to
     * support the user.
     * <p>
     *     <strong>This value can be dynamically set at runtime by
     *     {@link AccessibilityService#setServiceInfo(AccessibilityServiceInfo)}.</strong>
     * </p>
     *
     * @param timeout The timeout in milliseconds.
     *
     * @see android.R.styleable#AccessibilityService_nonInteractiveUiTimeout
     */
    public void setNonInteractiveUiTimeoutMillis(@IntRange(from = 0) int timeout) {
        mNonInteractiveUiTimeout = timeout;
    }

    /**
     * Get the recommended timeout for non-interactive controls.
     *
     * @return The timeout in milliseconds.
     *
     * @see #setNonInteractiveUiTimeoutMillis(int)
     */
    public int getNonInteractiveUiTimeoutMillis() {
        return mNonInteractiveUiTimeout;
    }

    /**
     * Set the recommended time that interactive controls need to remain on the screen to
     * support the user.
     * <p>
     *     <strong>This value can be dynamically set at runtime by
     *     {@link AccessibilityService#setServiceInfo(AccessibilityServiceInfo)}.</strong>
     * </p>
     *
     * @param timeout The timeout in milliseconds.
     *
     * @see android.R.styleable#AccessibilityService_interactiveUiTimeout
     */
    public void setInteractiveUiTimeoutMillis(@IntRange(from = 0) int timeout) {
        mInteractiveUiTimeout = timeout;
    }

    /**
     * Get the recommended timeout for interactive controls.
     *
     * @return The timeout in milliseconds.
     *
     * @see #setInteractiveUiTimeoutMillis(int)
     */
    public int getInteractiveUiTimeoutMillis() {
        return mInteractiveUiTimeout;
    }

    /** {@hide} */
    public boolean isDirectBootAware() {
        return ((flags & FLAG_FORCE_DIRECT_BOOT_AWARE) != 0)
                || mResolveInfo.serviceInfo.directBootAware;
    }

    /**
     * Indicates if the service is used to assist users with disabilities.
     *
     * @return {@code true} if the property is set to true.
     */
    public boolean isAccessibilityTool() {
        return mIsAccessibilityTool;
    }

    /**
     * {@inheritDoc}
     */
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flagz) {
        parcel.writeInt(eventTypes);
        parcel.writeStringArray(packageNames);
        parcel.writeInt(feedbackType);
        parcel.writeLong(notificationTimeout);
        parcel.writeInt(mNonInteractiveUiTimeout);
        parcel.writeInt(mInteractiveUiTimeout);
        parcel.writeInt(flags);
        parcel.writeInt(crashed ? 1 : 0);
        parcel.writeParcelable(mComponentName, flagz);
        parcel.writeParcelable(mResolveInfo, 0);
        parcel.writeString(mSettingsActivityName);
        parcel.writeInt(mCapabilities);
        parcel.writeInt(mSummaryResId);
        parcel.writeString(mNonLocalizedSummary);
        parcel.writeInt(mDescriptionResId);
        parcel.writeInt(mAnimatedImageRes);
        parcel.writeInt(mHtmlDescriptionRes);
        parcel.writeString(mNonLocalizedDescription);
        parcel.writeBoolean(mIsAccessibilityTool);
        parcel.writeString(mTileServiceName);
        parcel.writeInt(mIntroResId);
    }

    private void initFromParcel(Parcel parcel) {
        eventTypes = parcel.readInt();
        packageNames = parcel.readStringArray();
        feedbackType = parcel.readInt();
        notificationTimeout = parcel.readLong();
        mNonInteractiveUiTimeout = parcel.readInt();
        mInteractiveUiTimeout = parcel.readInt();
        flags = parcel.readInt();
        crashed = parcel.readInt() != 0;
        mComponentName = parcel.readParcelable(this.getClass().getClassLoader(), android.content.ComponentName.class);
        mResolveInfo = parcel.readParcelable(null, android.content.pm.ResolveInfo.class);
        mSettingsActivityName = parcel.readString();
        mCapabilities = parcel.readInt();
        mSummaryResId = parcel.readInt();
        mNonLocalizedSummary = parcel.readString();
        mDescriptionResId = parcel.readInt();
        mAnimatedImageRes = parcel.readInt();
        mHtmlDescriptionRes = parcel.readInt();
        mNonLocalizedDescription = parcel.readString();
        mIsAccessibilityTool = parcel.readBoolean();
        mTileServiceName = parcel.readString();
        mIntroResId = parcel.readInt();
    }

    @Override
    public int hashCode() {
        return 31 * 1 + ((mComponentName == null) ? 0 : mComponentName.hashCode());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AccessibilityServiceInfo other = (AccessibilityServiceInfo) obj;
        if (mComponentName == null) {
            if (other.mComponentName != null) {
                return false;
            }
        } else if (!mComponentName.equals(other.mComponentName)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        appendEventTypes(stringBuilder, eventTypes);
        stringBuilder.append(", ");
        appendPackageNames(stringBuilder, packageNames);
        stringBuilder.append(", ");
        appendFeedbackTypes(stringBuilder, feedbackType);
        stringBuilder.append(", ");
        stringBuilder.append("notificationTimeout: ").append(notificationTimeout);
        stringBuilder.append(", ");
        stringBuilder.append("nonInteractiveUiTimeout: ").append(mNonInteractiveUiTimeout);
        stringBuilder.append(", ");
        stringBuilder.append("interactiveUiTimeout: ").append(mInteractiveUiTimeout);
        stringBuilder.append(", ");
        appendFlags(stringBuilder, flags);
        stringBuilder.append(", ");
        stringBuilder.append("id: ").append(getId());
        stringBuilder.append(", ");
        stringBuilder.append("resolveInfo: ").append(mResolveInfo);
        stringBuilder.append(", ");
        stringBuilder.append("settingsActivityName: ").append(mSettingsActivityName);
        stringBuilder.append(", ");
        stringBuilder.append("tileServiceName: ").append(mTileServiceName);
        stringBuilder.append(", ");
        stringBuilder.append("summary: ").append(mNonLocalizedSummary);
        stringBuilder.append(", ");
        stringBuilder.append("isAccessibilityTool: ").append(mIsAccessibilityTool);
        stringBuilder.append(", ");
        appendCapabilities(stringBuilder, mCapabilities);
        return stringBuilder.toString();
    }

    private static void appendFeedbackTypes(StringBuilder stringBuilder,
            @FeedbackType int feedbackTypes) {
        stringBuilder.append("feedbackTypes:");
        stringBuilder.append("[");
        while (feedbackTypes != 0) {
            final int feedbackTypeBit = (1 << Integer.numberOfTrailingZeros(feedbackTypes));
            stringBuilder.append(feedbackTypeToString(feedbackTypeBit));
            feedbackTypes &= ~feedbackTypeBit;
            if (feedbackTypes != 0) {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("]");
    }

    private static void appendPackageNames(StringBuilder stringBuilder, String[] packageNames) {
        stringBuilder.append("packageNames:");
        stringBuilder.append("[");
        if (packageNames != null) {
            final int packageNameCount = packageNames.length;
            for (int i = 0; i < packageNameCount; i++) {
                stringBuilder.append(packageNames[i]);
                if (i < packageNameCount - 1) {
                    stringBuilder.append(", ");
                }
            }
        }
        stringBuilder.append("]");
    }

    private static void appendEventTypes(StringBuilder stringBuilder, int eventTypes) {
        stringBuilder.append("eventTypes:");
        stringBuilder.append("[");
        while (eventTypes != 0) {
            final int eventTypeBit = (1 << Integer.numberOfTrailingZeros(eventTypes));
            stringBuilder.append(AccessibilityEvent.eventTypeToString(eventTypeBit));
            eventTypes &= ~eventTypeBit;
            if (eventTypes != 0) {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("]");
    }

    private static void appendFlags(StringBuilder stringBuilder, int flags) {
        stringBuilder.append("flags:");
        stringBuilder.append("[");
        while (flags != 0) {
            final int flagBit = (1 << Integer.numberOfTrailingZeros(flags));
            stringBuilder.append(flagToString(flagBit));
            flags &= ~flagBit;
            if (flags != 0) {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("]");
    }

    private static void appendCapabilities(StringBuilder stringBuilder, int capabilities) {
        stringBuilder.append("capabilities:");
        stringBuilder.append("[");
        while (capabilities != 0) {
            final int capabilityBit = (1 << Integer.numberOfTrailingZeros(capabilities));
            stringBuilder.append(capabilityToString(capabilityBit));
            capabilities &= ~capabilityBit;
            if (capabilities != 0) {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("]");
    }

    /**
     * Returns the string representation of a feedback type. For example,
     * {@link #FEEDBACK_SPOKEN} is represented by the string FEEDBACK_SPOKEN.
     *
     * @param feedbackType The feedback type.
     * @return The string representation.
     */
    public static String feedbackTypeToString(int feedbackType) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        while (feedbackType != 0) {
            final int feedbackTypeFlag = 1 << Integer.numberOfTrailingZeros(feedbackType);
            feedbackType &= ~feedbackTypeFlag;
            switch (feedbackTypeFlag) {
                case FEEDBACK_AUDIBLE:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_AUDIBLE");
                    break;
                case FEEDBACK_HAPTIC:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_HAPTIC");
                    break;
                case FEEDBACK_GENERIC:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_GENERIC");
                    break;
                case FEEDBACK_SPOKEN:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_SPOKEN");
                    break;
                case FEEDBACK_VISUAL:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_VISUAL");
                    break;
                case FEEDBACK_BRAILLE:
                    if (builder.length() > 1) {
                        builder.append(", ");
                    }
                    builder.append("FEEDBACK_BRAILLE");
                    break;
            }
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Returns the string representation of a flag. For example,
     * {@link #DEFAULT} is represented by the string DEFAULT.
     *
     * @param flag The flag.
     * @return The string representation.
     */
    public static String flagToString(int flag) {
        switch (flag) {
            case DEFAULT:
                return "DEFAULT";
            case FLAG_INCLUDE_NOT_IMPORTANT_VIEWS:
                return "FLAG_INCLUDE_NOT_IMPORTANT_VIEWS";
            case FLAG_REQUEST_TOUCH_EXPLORATION_MODE:
                return "FLAG_REQUEST_TOUCH_EXPLORATION_MODE";
            case FLAG_SERVICE_HANDLES_DOUBLE_TAP:
                return "FLAG_SERVICE_HANDLES_DOUBLE_TAP";
            case FLAG_REQUEST_MULTI_FINGER_GESTURES:
                return "FLAG_REQUEST_MULTI_FINGER_GESTURES";
            case FLAG_REQUEST_2_FINGER_PASSTHROUGH:
                return "FLAG_REQUEST_2_FINGER_PASSTHROUGH";
            case FLAG_SEND_MOTION_EVENTS:
                return "FLAG_SEND_MOTION_EVENTS";
            case FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY:
                return "FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY";
            case FLAG_REPORT_VIEW_IDS:
                return "FLAG_REPORT_VIEW_IDS";
            case FLAG_REQUEST_FILTER_KEY_EVENTS:
                return "FLAG_REQUEST_FILTER_KEY_EVENTS";
            case FLAG_RETRIEVE_INTERACTIVE_WINDOWS:
                return "FLAG_RETRIEVE_INTERACTIVE_WINDOWS";
            case FLAG_ENABLE_ACCESSIBILITY_VOLUME:
                return "FLAG_ENABLE_ACCESSIBILITY_VOLUME";
            case FLAG_REQUEST_ACCESSIBILITY_BUTTON:
                return "FLAG_REQUEST_ACCESSIBILITY_BUTTON";
            case FLAG_REQUEST_FINGERPRINT_GESTURES:
                return "FLAG_REQUEST_FINGERPRINT_GESTURES";
            case FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK:
                return "FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK";
            case FLAG_INPUT_METHOD_EDITOR:
                return "FLAG_INPUT_METHOD_EDITOR";
            default:
                return null;
        }
    }

    /**
     * Returns the string representation of a capability. For example,
     * {@link #CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT} is represented
     * by the string CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT.
     *
     * @param capability The capability.
     * @return The string representation.
     */
    public static String capabilityToString(int capability) {
        switch (capability) {
            case CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT:
                return "CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT";
            case CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION:
                return "CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION";
            case CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY:
                return "CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY";
            case CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS:
                return "CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS";
            case CAPABILITY_CAN_CONTROL_MAGNIFICATION:
                return "CAPABILITY_CAN_CONTROL_MAGNIFICATION";
            case CAPABILITY_CAN_PERFORM_GESTURES:
                return "CAPABILITY_CAN_PERFORM_GESTURES";
            case CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES:
                return "CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES";
            case CAPABILITY_CAN_TAKE_SCREENSHOT:
                return "CAPABILITY_CAN_TAKE_SCREENSHOT";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * @hide
     * @return The list of {@link CapabilityInfo} objects.
     * @deprecated The version that takes a context works better.
     */
    public List<CapabilityInfo> getCapabilityInfos() {
        return getCapabilityInfos(null);
    }

    /**
     * @hide
     * @param context A valid context
     * @return The list of {@link CapabilityInfo} objects.
     */
    public List<CapabilityInfo> getCapabilityInfos(Context context) {
        if (mCapabilities == 0) {
            return Collections.emptyList();
        }
        int capabilities = mCapabilities;
        List<CapabilityInfo> capabilityInfos = new ArrayList<CapabilityInfo>();
        SparseArray<CapabilityInfo> capabilityInfoSparseArray =
                getCapabilityInfoSparseArray(context);
        while (capabilities != 0) {
            final int capabilityBit = 1 << Integer.numberOfTrailingZeros(capabilities);
            capabilities &= ~capabilityBit;
            CapabilityInfo capabilityInfo = capabilityInfoSparseArray.get(capabilityBit);
            if (capabilityInfo != null) {
                capabilityInfos.add(capabilityInfo);
            }
        }
        return capabilityInfos;
    }

    private static SparseArray<CapabilityInfo> getCapabilityInfoSparseArray(Context context) {
        if (sAvailableCapabilityInfos == null) {
            sAvailableCapabilityInfos = new SparseArray<CapabilityInfo>();
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT,
                    new CapabilityInfo(CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT,
                            R.string.capability_title_canRetrieveWindowContent,
                            R.string.capability_desc_canRetrieveWindowContent));
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION,
                    new CapabilityInfo(CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION,
                            R.string.capability_title_canRequestTouchExploration,
                            R.string.capability_desc_canRequestTouchExploration));
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS,
                    new CapabilityInfo(CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS,
                            R.string.capability_title_canRequestFilterKeyEvents,
                            R.string.capability_desc_canRequestFilterKeyEvents));
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_CONTROL_MAGNIFICATION,
                    new CapabilityInfo(CAPABILITY_CAN_CONTROL_MAGNIFICATION,
                            R.string.capability_title_canControlMagnification,
                            R.string.capability_desc_canControlMagnification));
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_PERFORM_GESTURES,
                    new CapabilityInfo(CAPABILITY_CAN_PERFORM_GESTURES,
                            R.string.capability_title_canPerformGestures,
                            R.string.capability_desc_canPerformGestures));
            sAvailableCapabilityInfos.put(CAPABILITY_CAN_TAKE_SCREENSHOT,
                    new CapabilityInfo(CAPABILITY_CAN_TAKE_SCREENSHOT,
                            R.string.capability_title_canTakeScreenshot,
                            R.string.capability_desc_canTakeScreenshot));
            if ((context == null) || fingerprintAvailable(context)) {
                sAvailableCapabilityInfos.put(CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES,
                        new CapabilityInfo(CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES,
                                R.string.capability_title_canCaptureFingerprintGestures,
                                R.string.capability_desc_canCaptureFingerprintGestures));
            }
        }
        return sAvailableCapabilityInfos;
    }

    private static boolean fingerprintAvailable(Context context) {
        return context.getPackageManager().hasSystemFeature(FEATURE_FINGERPRINT)
                && context.getSystemService(FingerprintManager.class).isHardwareDetected();
    }
    /**
     * @hide
     */
    public static final class CapabilityInfo {
        public final int capability;
        public final int titleResId;
        public final int descResId;

        public CapabilityInfo(int capability, int titleResId, int descResId) {
            this.capability = capability;
            this.titleResId = titleResId;
            this.descResId = descResId;
        }
    }

    /**
     * @see Parcelable.Creator
     */
    public static final @android.annotation.NonNull Parcelable.Creator<AccessibilityServiceInfo> CREATOR =
            new Parcelable.Creator<AccessibilityServiceInfo>() {
        public AccessibilityServiceInfo createFromParcel(Parcel parcel) {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.initFromParcel(parcel);
            return info;
        }

        public AccessibilityServiceInfo[] newArray(int size) {
            return new AccessibilityServiceInfo[size];
        }
    };
}
