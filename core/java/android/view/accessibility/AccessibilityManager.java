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

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityServiceInfo.FeedbackType;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.CallbackExecutor;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.RemoteAction;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.IWindow;
import android.view.View;
import android.view.accessibility.AccessibilityEvent.EventType;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IntPair;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s,
 * and provides facilities for querying the accessibility state of the system.
 * Accessibility events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@link android.accessibilityservice.AccessibilityService}.
 *
 * @see AccessibilityEvent
 * @see AccessibilityNodeInfo
 * @see android.accessibilityservice.AccessibilityService
 * @see Context#getSystemService
 * @see Context#ACCESSIBILITY_SERVICE
 */
@SystemService(Context.ACCESSIBILITY_SERVICE)
public final class AccessibilityManager {
    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManager";

    /** @hide */
    public static final int STATE_FLAG_ACCESSIBILITY_ENABLED = 1 /* << 0 */;

    /** @hide */
    public static final int STATE_FLAG_TOUCH_EXPLORATION_ENABLED = 1 << 1;

    /** @hide */
    public static final int STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED = 1 << 2;

    /** @hide */
    public static final int STATE_FLAG_DISPATCH_DOUBLE_TAP = 1 << 3;

    /** @hide */
    public static final int STATE_FLAG_REQUEST_MULTI_FINGER_GESTURES = 1 << 4;

    /** @hide */
    public static final int STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_ENABLED = 1 << 8;
    /** @hide */
    public static final int STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_CB_ENABLED = 1 << 9;
    /** @hide */
    public static final int STATE_FLAG_TRACE_A11Y_INTERACTION_CLIENT_ENABLED = 1 << 10;
    /** @hide */
    public static final int STATE_FLAG_TRACE_A11Y_SERVICE_ENABLED = 1 << 11;
    /** @hide */
    public static final int STATE_FLAG_AUDIO_DESCRIPTION_BY_DEFAULT_ENABLED = 1 << 12;

    /** @hide */
    public static final int DALTONIZER_DISABLED = -1;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int DALTONIZER_SIMULATE_MONOCHROMACY = 0;

    /** @hide */
    public static final int DALTONIZER_CORRECT_DEUTERANOMALY = 12;

    /** @hide */
    public static final int AUTOCLICK_DELAY_DEFAULT = 600;

    /**
     * Activity action: Launch UI to manage which accessibility service or feature is assigned
     * to the navigation bar Accessibility button.
     * <p>
     * Input: Nothing.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHOOSE_ACCESSIBILITY_BUTTON =
            "com.android.internal.intent.action.CHOOSE_ACCESSIBILITY_BUTTON";

    /**
     * Used as an int value for accessibility chooser activity to represent the accessibility button
     * shortcut type.
     *
     * @hide
     */
    public static final int ACCESSIBILITY_BUTTON = 0;

    /**
     * Used as an int value for accessibility chooser activity to represent hardware key shortcut,
     * such as volume key button.
     *
     * @hide
     */
    public static final int ACCESSIBILITY_SHORTCUT_KEY = 1;

    /** @hide */
    public static final int FLASH_REASON_CALL = 1;

    /** @hide */
    public static final int FLASH_REASON_ALARM = 2;

    /** @hide */
    public static final int FLASH_REASON_NOTIFICATION = 3;

    /** @hide */
    public static final int FLASH_REASON_PREVIEW = 4;

    /**
     * Annotations for the shortcut type.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ACCESSIBILITY_BUTTON,
            ACCESSIBILITY_SHORTCUT_KEY
    })
    public @interface ShortcutType {}

    /**
     * Annotations for content flag of UI.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_CONTENT_" }, value = {
            FLAG_CONTENT_ICONS,
            FLAG_CONTENT_TEXT,
            FLAG_CONTENT_CONTROLS
    })
    public @interface ContentFlag {}

    /**
     * Annotations for reason of Flash notification.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "FLASH_REASON_" }, value = {
            FLASH_REASON_CALL,
            FLASH_REASON_ALARM,
            FLASH_REASON_NOTIFICATION,
            FLASH_REASON_PREVIEW
    })
    public @interface FlashNotificationReason {}

    /**
     * Use this flag to indicate the content of a UI that times out contains icons.
     *
     * @see #getRecommendedTimeoutMillis(int, int)
     */
    public static final int FLAG_CONTENT_ICONS = 1;

    /**
     * Use this flag to indicate the content of a UI that times out contains text.
     *
     * @see #getRecommendedTimeoutMillis(int, int)
     */
    public static final int FLAG_CONTENT_TEXT = 2;

    /**
     * Use this flag to indicate the content of a UI that times out contains interactive controls.
     *
     * @see #getRecommendedTimeoutMillis(int, int)
     */
    public static final int FLAG_CONTENT_CONTROLS = 4;

    @UnsupportedAppUsage
    static final Object sInstanceSync = new Object();

    @UnsupportedAppUsage
    private static AccessibilityManager sInstance;

    @UnsupportedAppUsage
    private final Object mLock = new Object();

    @UnsupportedAppUsage
    private IAccessibilityManager mService;

    @UnsupportedAppUsage
    final int mUserId;

    @UnsupportedAppUsage
    final Handler mHandler;

    final Handler.Callback mCallback;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    boolean mIsEnabled;

    int mRelevantEventTypes = AccessibilityEvent.TYPES_ALL_MASK;

    int mInteractiveUiTimeout;
    int mNonInteractiveUiTimeout;

    boolean mIsTouchExplorationEnabled;

    @UnsupportedAppUsage(trackingBug = 123768939L)
    boolean mIsHighTextContrastEnabled;

    boolean mIsAudioDescriptionByDefaultRequested;

    // accessibility tracing state
    int mAccessibilityTracingState = 0;

    AccessibilityPolicy mAccessibilityPolicy;

    private int mPerformingAction = 0;

    /** The stroke width of the focus rectangle in pixels */
    private int mFocusStrokeWidth;
    /** The color of the focus rectangle */
    private int mFocusColor;

    @UnsupportedAppUsage
    private final ArrayMap<AccessibilityStateChangeListener, Handler>
            mAccessibilityStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<TouchExplorationStateChangeListener, Handler>
            mTouchExplorationStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<HighTextContrastChangeListener, Handler>
            mHighTextContrastStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<AccessibilityServicesStateChangeListener, Executor>
            mServicesStateChangeListeners = new ArrayMap<>();

    private final ArrayMap<AudioDescriptionRequestedChangeListener, Executor>
            mAudioDescriptionRequestedChangeListeners = new ArrayMap<>();

    private boolean mRequestFromAccessibilityTool;

    /**
     * Map from a view's accessibility id to the list of request preparers set for that view
     */
    private SparseArray<List<AccessibilityRequestPreparer>> mRequestPreparerLists;

    /**
     * Binder for flash notification.
     *
     * @see #startFlashNotificationSequence(Context, int)
     */
    private final Binder mBinder = new Binder();

    /**
     * Listener for the system accessibility state. To listen for changes to the
     * accessibility state on the device, implement this interface and register
     * it with the system by calling {@link #addAccessibilityStateChangeListener}.
     */
    public interface AccessibilityStateChangeListener {

        /**
         * Called when the accessibility enabled state changes.
         *
         * @param enabled Whether accessibility is enabled.
         */
        void onAccessibilityStateChanged(boolean enabled);
    }

    /**
     * Listener for the system touch exploration state. To listen for changes to
     * the touch exploration state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addTouchExplorationStateChangeListener}.
     */
    public interface TouchExplorationStateChangeListener {

        /**
         * Called when the touch exploration enabled state changes.
         *
         * @param enabled Whether touch exploration is enabled.
         */
        void onTouchExplorationStateChanged(boolean enabled);
    }

    /**
     * Listener for changes to the state of accessibility services.
     *
     * <p>
     * This refers to changes to {@link AccessibilityServiceInfo}, including:
     * <ul>
     *     <li>Whenever a service is enabled or disabled, or its info has been set or removed.</li>
     *     <li>Whenever a metadata attribute of any running service's info changes.</li>
     * </ul>
     *
     * @see #getEnabledAccessibilityServiceList for a list of infos of the enabled accessibility
     * services.
     * @see #addAccessibilityServicesStateChangeListener
     *
     */
    public interface AccessibilityServicesStateChangeListener {

        /**
         * Called when the state of accessibility services changes.
         *
         * @param manager The manager that is calling back
         */
        void onAccessibilityServicesStateChanged(@NonNull  AccessibilityManager manager);
    }

    /**
     * Listener for the system high text contrast state. To listen for changes to
     * the high text contrast state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addHighTextContrastStateChangeListener}.
     *
     * @hide
     */
    public interface HighTextContrastChangeListener {

        /**
         * Called when the high text contrast enabled state changes.
         *
         * @param enabled Whether high text contrast is enabled.
         */
        void onHighTextContrastStateChanged(boolean enabled);
    }

    /**
     * Listener for the audio description by default state. To listen for
     * changes to the audio description by default state on the device,
     * implement this interface and register it with the system by calling
     * {@link #addAudioDescriptionRequestedChangeListener}.
     */
    public interface AudioDescriptionRequestedChangeListener {
        /**
         * Called when the audio description enabled state changes.
         *
         * @param enabled Whether audio description by default is enabled.
         */
        void onAudioDescriptionRequestedChanged(boolean enabled);
    }

    /**
     * Policy to inject behavior into the accessibility manager.
     *
     * @hide
     */
    public interface AccessibilityPolicy {
        /**
         * Checks whether accessibility is enabled.
         *
         * @param accessibilityEnabled Whether the accessibility layer is enabled.
         * @return whether accessibility is enabled.
         */
        boolean isEnabled(boolean accessibilityEnabled);

        /**
         * Notifies the policy for an accessibility event.
         *
         * @param event The event.
         * @param accessibilityEnabled Whether the accessibility layer is enabled.
         * @param relevantEventTypes The events relevant events.
         * @return The event to dispatch or null.
         */
        @Nullable AccessibilityEvent onAccessibilityEvent(@NonNull AccessibilityEvent event,
                boolean accessibilityEnabled, @EventType int relevantEventTypes);

        /**
         * Gets the list of relevant events.
         *
         * @param relevantEventTypes The relevant events.
         * @return The relevant events to report.
         */
        @EventType int getRelevantEventTypes(@EventType int relevantEventTypes);

        /**
         * Gets the list of installed services to report.
         *
         * @param installedService The installed services.
         * @return The services to report.
         */
        @NonNull List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(
                @Nullable List<AccessibilityServiceInfo> installedService);

        /**
         * Gets the list of enabled accessibility services.
         *
         * @param feedbackTypeFlags The feedback type to query for.
         * @param enabledService The enabled services.
         * @return The services to report.
         */
        @Nullable List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
                @FeedbackType int feedbackTypeFlags,
                @Nullable List<AccessibilityServiceInfo> enabledService);
    }

    private final IAccessibilityManagerClient.Stub mClient =
            new IAccessibilityManagerClient.Stub() {
        @Override
        public void setState(int state) {
            // We do not want to change this immediately as the application may
            // have already checked that accessibility is on and fired an event,
            // that is now propagating up the view tree, Hence, if accessibility
            // is now off an exception will be thrown. We want to have the exception
            // enforcement to guard against apps that fire unnecessary accessibility
            // events when accessibility is off.
            mHandler.obtainMessage(MyCallback.MSG_SET_STATE, state, 0).sendToTarget();
        }

        @Override
        public void notifyServicesStateChanged(long updatedUiTimeout) {
            updateUiTimeout(updatedUiTimeout);

            final ArrayMap<AccessibilityServicesStateChangeListener, Executor> listeners;
            synchronized (mLock) {
                if (mServicesStateChangeListeners.isEmpty()) {
                    return;
                }
                listeners = new ArrayMap<>(mServicesStateChangeListeners);
            }

            int numListeners = listeners.size();
            for (int i = 0; i < numListeners; i++) {
                final AccessibilityServicesStateChangeListener listener =
                        mServicesStateChangeListeners.keyAt(i);
                mServicesStateChangeListeners.valueAt(i).execute(() -> listener
                        .onAccessibilityServicesStateChanged(AccessibilityManager.this));
            }
        }

        @Override
        public void setRelevantEventTypes(int eventTypes) {
            mRelevantEventTypes = eventTypes;
        }

        @Override
        public void setFocusAppearance(int strokeWidth, int color) {
            synchronized (mLock) {
                updateFocusAppearanceLocked(strokeWidth, color);
            }
        }
    };

    /**
     * Get an AccessibilityManager instance (create one if necessary).
     *
     * @param context Context in which this manager operates.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static AccessibilityManager getInstance(Context context) {
        synchronized (sInstanceSync) {
            if (sInstance == null) {
                final int userId;
                if (Binder.getCallingUid() == Process.SYSTEM_UID
                        || context.checkCallingOrSelfPermission(
                                Manifest.permission.INTERACT_ACROSS_USERS)
                                        == PackageManager.PERMISSION_GRANTED
                        || context.checkCallingOrSelfPermission(
                                Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                                        == PackageManager.PERMISSION_GRANTED) {
                    userId = UserHandle.USER_CURRENT;
                } else {
                    userId = context.getUserId();
                }
                sInstance = new AccessibilityManager(context, null, userId);
            }
        }
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     * @param service An interface to the backing service.
     * @param userId User id under which to run.
     *
     * @hide
     */
    public AccessibilityManager(Context context, IAccessibilityManager service, int userId) {
        // Constructor can't be chained because we can't create an instance of an inner class
        // before calling another constructor.
        mCallback = new MyCallback();
        mHandler = new Handler(context.getMainLooper(), mCallback);
        mUserId = userId;
        synchronized (mLock) {
            initialFocusAppearanceLocked(context.getResources());
            tryConnectToServiceLocked(service);
        }
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     * @param handler The handler to use
     * @param service An interface to the backing service.
     * @param userId User id under which to run.
     * @param serviceConnect {@code true} to connect the service or
     *                       {@code false} not to connect the service.
     *
     * @hide
     */
    @VisibleForTesting
    public AccessibilityManager(Context context, Handler handler, IAccessibilityManager service,
            int userId, boolean serviceConnect) {
        mCallback = new MyCallback();
        mHandler = handler;
        mUserId = userId;
        synchronized (mLock) {
            initialFocusAppearanceLocked(context.getResources());
            if (serviceConnect) {
                tryConnectToServiceLocked(service);
            }
        }
    }

    /**
     * @hide
     */
    public IAccessibilityManagerClient getClient() {
        return mClient;
    }

    /**
     * Unregisters the IAccessibilityManagerClient from the backing service
     * @hide
     */
    public boolean removeClient() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            try {
                return service.removeClient(mClient, mUserId);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "AccessibilityManagerService is dead", re);
            }
        }
        return false;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public Handler.Callback getCallback() {
        return mCallback;
    }

    /**
     * Returns if the accessibility in the system is enabled.
     * <p>
     * <b>Note:</b> This query is used for sending {@link AccessibilityEvent}s, since events are
     * only needed if accessibility is on. Avoid changing UI or app behavior based on the state of
     * accessibility. While well-intentioned, doing this creates brittle, less
     * well-maintained code that works for some users but not others. Shared code leads to more
     * equitable experiences and less technical debt.
     *
     *<p>
     * For example, if you want to expose a unique interaction with your app, use
     * ViewCompat#addAccessibilityAction in AndroidX to make this interaction - ideally
     * with the same code path used for non-accessibility users - available to accessibility
     * services. Services can then expose this action in the way best fit for their users.
     *
     * @return True if accessibility is enabled, false otherwise.
     */
    public boolean isEnabled() {
        synchronized (mLock) {
            return mIsEnabled || hasAnyDirectConnection()
                    || (mAccessibilityPolicy != null && mAccessibilityPolicy.isEnabled(mIsEnabled));
        }
    }

    /**
     * @see AccessibilityInteractionClient#hasAnyDirectConnection
     * @hide
     */
    @TestApi
    public boolean hasAnyDirectConnection() {
        return AccessibilityInteractionClient.hasAnyDirectConnection();
    }

    /**
     * Returns if the touch exploration in the system is enabled.
     * <p>
     * <b>Note:</b> This query is used for dispatching hover events, such as
     * {@link android.view.MotionEvent#ACTION_HOVER_ENTER}, to accessibility services to manage
     * touch exploration. Avoid changing UI or app behavior based on the state of accessibility.
     * While well-intentioned, doing this creates brittle, less well-maintained code that works for
     * som users but not others. Shared code leads to more equitable experiences and less technical
     * debt.
     *
     * @return True if touch exploration is enabled, false otherwise.
     */
    public boolean isTouchExplorationEnabled() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsTouchExplorationEnabled;
        }
    }

    /**
     * Returns if the high text contrast in the system is enabled.
     * <p>
     * <strong>Note:</strong> You need to query this only if you application is
     * doing its own rendering and does not rely on the platform rendering pipeline.
     * </p>
     *
     * @return True if high text contrast is enabled, false otherwise.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isHighTextContrastEnabled() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsHighTextContrastEnabled;
        }
    }

    /**
     * Sends an {@link AccessibilityEvent}.
     *
     * @param event The event to send.
     *
     * @throws IllegalStateException if accessibility is not enabled.
     *
     * <strong>Note:</strong> The preferred mechanism for sending custom accessibility
     * events is through calling
     * {@link android.view.ViewParent#requestSendAccessibilityEvent(View, AccessibilityEvent)}
     * instead of this method to allow predecessors to augment/filter events sent by
     * their descendants.
     */
    public void sendAccessibilityEvent(AccessibilityEvent event) {
        final IAccessibilityManager service;
        final int userId;
        final AccessibilityEvent dispatchedEvent;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
            event.setEventTime(SystemClock.uptimeMillis());
            if (event.getAction() == 0) {
                event.setAction(mPerformingAction);
            }
            if (mAccessibilityPolicy != null) {
                dispatchedEvent = mAccessibilityPolicy.onAccessibilityEvent(event,
                        mIsEnabled, mRelevantEventTypes);
                if (dispatchedEvent == null) {
                    return;
                }
            } else {
                dispatchedEvent = event;
            }
            if (!isEnabled()) {
                Looper myLooper = Looper.myLooper();
                if (myLooper == Looper.getMainLooper()) {
                    throw new IllegalStateException(
                            "Accessibility off. Did you forget to check that?");
                } else {
                    // If we're not running on the thread with the main looper, it's possible for
                    // the state of accessibility to change between checking isEnabled and
                    // calling this method. So just log the error rather than throwing the
                    // exception.
                    Log.e(LOG_TAG, "AccessibilityEvent sent with accessibility disabled");
                    return;
                }
            }
            if ((dispatchedEvent.getEventType() & mRelevantEventTypes) == 0) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Not dispatching irrelevant event: " + dispatchedEvent
                            + " that is not among "
                            + AccessibilityEvent.eventTypeToString(mRelevantEventTypes));
                }
                return;
            }
            userId = mUserId;
        }
        try {
            // it is possible that this manager is in the same process as the service but
            // client using it is called through Binder from another process. Example: MMS
            // app adds a SMS notification and the NotificationManagerService calls this method
            final long identityToken = Binder.clearCallingIdentity();
            try {
                service.sendAccessibilityEvent(dispatchedEvent, userId);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            if (DEBUG) {
                Log.i(LOG_TAG, dispatchedEvent + " sent");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error during sending " + dispatchedEvent + " ", re);
        } finally {
            if (event != dispatchedEvent) {
                event.recycle();
            }
            dispatchedEvent.recycle();
        }
    }

    /**
     * Requests feedback interruption from all accessibility services.
     */
    public void interrupt() {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
            if (!isEnabled()) {
                Looper myLooper = Looper.myLooper();
                if (myLooper == Looper.getMainLooper()) {
                    throw new IllegalStateException(
                            "Accessibility off. Did you forget to check that?");
                } else {
                    // If we're not running on the thread with the main looper, it's possible for
                    // the state of accessibility to change between checking isEnabled and
                    // calling this method. So just log the error rather than throwing the
                    // exception.
                    Log.e(LOG_TAG, "Interrupt called with accessibility disabled");
                    return;
                }
            }
            userId = mUserId;
        }
        try {
            service.interrupt(userId);
            if (DEBUG) {
                Log.i(LOG_TAG, "Requested interrupt from all services");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while requesting interrupt from all services. ", re);
        }
    }

    /**
     * Returns the {@link ServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link ServiceInfo}s.
     *
     * @deprecated Use {@link #getInstalledAccessibilityServiceList()}
     */
    @Deprecated
    public List<ServiceInfo> getAccessibilityServiceList() {
        List<AccessibilityServiceInfo> infos = getInstalledAccessibilityServiceList();
        List<ServiceInfo> services = new ArrayList<>();
        final int infoCount = infos.size();
        for (int i = 0; i < infoCount; i++) {
            AccessibilityServiceInfo info = infos.get(i);
            services.add(info.getResolveInfo().serviceInfo);
        }
        return Collections.unmodifiableList(services);
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     */
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return Collections.emptyList();
            }
            userId = mUserId;
        }

        List<AccessibilityServiceInfo> services = null;
        try {
            services = service.getInstalledAccessibilityServiceList(userId).getList();
            if (DEBUG) {
                Log.i(LOG_TAG, "Installed AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the installed AccessibilityServices. ", re);
        }
        if (mAccessibilityPolicy != null) {
            services = mAccessibilityPolicy.getInstalledAccessibilityServiceList(services);
        }
        if (services != null) {
            return Collections.unmodifiableList(services);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the enabled accessibility services
     * for a given feedback type.
     *
     * @param feedbackTypeFlags The feedback type flags.
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     *
     * @see AccessibilityServiceInfo#FEEDBACK_AUDIBLE
     * @see AccessibilityServiceInfo#FEEDBACK_GENERIC
     * @see AccessibilityServiceInfo#FEEDBACK_HAPTIC
     * @see AccessibilityServiceInfo#FEEDBACK_SPOKEN
     * @see AccessibilityServiceInfo#FEEDBACK_VISUAL
     * @see AccessibilityServiceInfo#FEEDBACK_BRAILLE
     */
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
            int feedbackTypeFlags) {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return Collections.emptyList();
            }
            userId = mUserId;
        }

        List<AccessibilityServiceInfo> services = null;
        try {
            services = service.getEnabledAccessibilityServiceList(feedbackTypeFlags, userId);
            if (DEBUG) {
                Log.i(LOG_TAG, "Enabled AccessibilityServices " + services);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while obtaining the enabled AccessibilityServices. ", re);
        }
        if (mAccessibilityPolicy != null) {
            services = mAccessibilityPolicy.getEnabledAccessibilityServiceList(
                    feedbackTypeFlags, services);
        }
        if (services != null) {
            return Collections.unmodifiableList(services);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system. Equivalent to calling
     * {@link #addAccessibilityStateChangeListener(AccessibilityStateChangeListener, Handler)}
     * with a null handler.
     *
     * @param listener The listener.
     * @return Always returns {@code true}.
     */
    public boolean addAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener) {
        addAccessibilityStateChangeListener(listener, null);
        return true;
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system. If the listener has already been registered,
     * the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mAccessibilityStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters an {@link AccessibilityStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if the listener was previously registered.
     */
    public boolean removeAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener) {
        synchronized (mLock) {
            int index = mAccessibilityStateChangeListeners.indexOfKey(listener);
            mAccessibilityStateChangeListeners.remove(listener);
            return (index >= 0);
        }
    }

    /**
     * Registers a {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system. Equivalent to calling
     * {@link #addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener, Handler)}
     * with a null handler.
     *
     * @param listener The listener.
     * @return Always returns {@code true}.
     */
    public boolean addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        addTouchExplorationStateChangeListener(listener, null);
        return true;
    }

    /**
     * Registers an {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system. If the listener has already been
     * registered, the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mTouchExplorationStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters a {@link TouchExplorationStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if listener was previously registered.
     */
    public boolean removeTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        synchronized (mLock) {
            int index = mTouchExplorationStateChangeListeners.indexOfKey(listener);
            mTouchExplorationStateChangeListeners.remove(listener);
            return (index >= 0);
        }
    }

    /**
     * Registers a {@link AccessibilityServicesStateChangeListener}.
     *
     * @param executor The executor.
     * @param listener The listener.
     */
    public void addAccessibilityServicesStateChangeListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AccessibilityServicesStateChangeListener listener) {
        synchronized (mLock) {
            mServicesStateChangeListeners.put(listener, executor);
        }
    }

    /**
     * Registers a {@link AccessibilityServicesStateChangeListener}. This will execute a callback on
     * the process's main handler.
     *
     * @param listener The listener.
     *
     */
    public void addAccessibilityServicesStateChangeListener(
            @NonNull AccessibilityServicesStateChangeListener listener) {
        addAccessibilityServicesStateChangeListener(new HandlerExecutor(mHandler), listener);
    }

    /**
     * Unregisters a {@link AccessibilityServicesStateChangeListener}.
     *
     * @param listener The listener.
     * @return {@code true} if the listener was previously registered.
     */
    public boolean removeAccessibilityServicesStateChangeListener(
            @NonNull AccessibilityServicesStateChangeListener listener) {
        synchronized (mLock) {
            return mServicesStateChangeListeners.remove(listener) != null;
        }
    }

    /**
     * Whether the current accessibility request comes from an
     * {@link AccessibilityService} with the {@link AccessibilityServiceInfo#isAccessibilityTool}
     * property set to true.
     *
     * <p>
     * You can use this method inside {@link AccessibilityNodeProvider} to decide how to populate
     * your nodes.
     * </p>
     *
     * <p>
     * <strong>Note:</strong> The return value is valid only when an {@link AccessibilityNodeInfo}
     * request is in progress, can change from one request to another, and has no meaning when a
     * request is not in progress.
     * </p>
     *
     * @return True if the current request is from a tool that sets isAccessibilityTool.
     */
    public boolean isRequestFromAccessibilityTool() {
        return mRequestFromAccessibilityTool;
    }

    /**
     * Specifies whether the current accessibility request comes from an
     * {@link AccessibilityService} with the {@link AccessibilityServiceInfo#isAccessibilityTool}
     * property set to true.
     *
     * @hide
     */
    public void setRequestFromAccessibilityTool(boolean requestFromAccessibilityTool) {
        mRequestFromAccessibilityTool = requestFromAccessibilityTool;
    }

    /**
     * Registers a {@link AccessibilityRequestPreparer}.
     */
    public void addAccessibilityRequestPreparer(AccessibilityRequestPreparer preparer) {
        if (mRequestPreparerLists == null) {
            mRequestPreparerLists = new SparseArray<>(1);
        }
        int id = preparer.getAccessibilityViewId();
        List<AccessibilityRequestPreparer> requestPreparerList = mRequestPreparerLists.get(id);
        if (requestPreparerList == null) {
            requestPreparerList = new ArrayList<>(1);
            mRequestPreparerLists.put(id, requestPreparerList);
        }
        requestPreparerList.add(preparer);
    }

    /**
     * Unregisters a {@link AccessibilityRequestPreparer}.
     */
    public void removeAccessibilityRequestPreparer(AccessibilityRequestPreparer preparer) {
        if (mRequestPreparerLists == null) {
            return;
        }
        int viewId = preparer.getAccessibilityViewId();
        List<AccessibilityRequestPreparer> requestPreparerList = mRequestPreparerLists.get(viewId);
        if (requestPreparerList != null) {
            requestPreparerList.remove(preparer);
            if (requestPreparerList.isEmpty()) {
                mRequestPreparerLists.remove(viewId);
            }
        }
    }

    /**
     * Get the recommended timeout for changes to the UI needed by this user. Controls should remain
     * on the screen for at least this long to give users time to react. Some users may need
     * extra time to review the controls, or to reach them, or to activate assistive technology
     * to activate the controls automatically.
     * <p>
     * Use the combination of content flags to indicate contents of UI. For example, use
     * {@code FLAG_CONTENT_ICONS | FLAG_CONTENT_TEXT} for message notification which contains
     * icons and text, or use {@code FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS} for button dialog
     * which contains text and button controls.
     * <p/>
     *
     * @param originalTimeout The timeout appropriate for users with no accessibility needs.
     * @param uiContentFlags The combination of flags {@link #FLAG_CONTENT_ICONS},
     *                       {@link #FLAG_CONTENT_TEXT} or {@link #FLAG_CONTENT_CONTROLS} to
     *                       indicate the contents of UI.
     * @return The recommended UI timeout for the current user in milliseconds.
     */
    public int getRecommendedTimeoutMillis(int originalTimeout, @ContentFlag int uiContentFlags) {
        boolean hasControls = (uiContentFlags & FLAG_CONTENT_CONTROLS) != 0;
        boolean hasIconsOrText = (uiContentFlags & FLAG_CONTENT_ICONS) != 0
                || (uiContentFlags & FLAG_CONTENT_TEXT) != 0;
        int recommendedTimeout = originalTimeout;
        if (hasControls) {
            recommendedTimeout = Math.max(recommendedTimeout, mInteractiveUiTimeout);
        }
        if (hasIconsOrText) {
            recommendedTimeout = Math.max(recommendedTimeout, mNonInteractiveUiTimeout);
        }
        return recommendedTimeout;
    }

    /**
     * Gets the strokeWidth of the focus rectangle. This value can be set by
     * {@link AccessibilityService}.
     *
     * @return The strokeWidth of the focus rectangle in pixels.
     *
     */
    public int getAccessibilityFocusStrokeWidth() {
        synchronized (mLock) {
            return mFocusStrokeWidth;
        }
    }

    /**
     * Gets the color of the focus rectangle. This value can be set by
     * {@link AccessibilityService}.
     *
     * @return The color of the focus rectangle.
     *
     */
    public @ColorInt int getAccessibilityFocusColor() {
        synchronized (mLock) {
            return mFocusColor;
        }
    }

    /**
     * Gets accessibility interaction connection tracing enabled state.
     *
     * @hide
     */
    public boolean isA11yInteractionConnectionTraceEnabled() {
        synchronized (mLock) {
            return ((mAccessibilityTracingState
                    & STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_ENABLED) != 0);
        }
    }

    /**
     * Gets accessibility interaction connection callback tracing enabled state.
     *
     * @hide
     */
    public boolean isA11yInteractionConnectionCBTraceEnabled() {
        synchronized (mLock) {
            return ((mAccessibilityTracingState
                    & STATE_FLAG_TRACE_A11Y_INTERACTION_CONNECTION_CB_ENABLED) != 0);
        }
    }

    /**
     * Gets accessibility interaction client tracing enabled state.
     *
     * @hide
     */
    public boolean isA11yInteractionClientTraceEnabled() {
        synchronized (mLock) {
            return ((mAccessibilityTracingState
                    & STATE_FLAG_TRACE_A11Y_INTERACTION_CLIENT_ENABLED) != 0);
        }
    }

    /**
     * Gets accessibility service tracing enabled state.
     *
     * @hide
     */
    public boolean isA11yServiceTraceEnabled() {
        synchronized (mLock) {
            return ((mAccessibilityTracingState
                    & STATE_FLAG_TRACE_A11Y_SERVICE_ENABLED) != 0);
        }
    }

    /**
     * Get the preparers that are registered for an accessibility ID
     *
     * @param id The ID of interest
     * @return The list of preparers, or {@code null} if there are none.
     *
     * @hide
     */
    public List<AccessibilityRequestPreparer> getRequestPreparersForAccessibilityId(int id) {
        if (mRequestPreparerLists == null) {
            return null;
        }
        return mRequestPreparerLists.get(id);
    }

    /**
     * Set the currently performing accessibility action in views.
     *
     * @param actionId the action id of {@link AccessibilityNodeInfo.AccessibilityAction}.
     * @hide
     */
    public void notifyPerformingAction(int actionId) {
        mPerformingAction = actionId;
    }

    /**
     * Get the id of {@link AccessibilityNodeInfo.AccessibilityAction} currently being performed.
     *
     * @hide
     */
    public int getPerformingAction() {
        return mPerformingAction;
    }

    /**
     * Registers a {@link HighTextContrastChangeListener} for changes in
     * the global high text contrast state of the system.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void addHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mHighTextContrastStateChangeListeners
                    .put(listener, (handler == null) ? mHandler : handler);
        }
    }

    /**
     * Unregisters a {@link HighTextContrastChangeListener}.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void removeHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener) {
        synchronized (mLock) {
            mHighTextContrastStateChangeListeners.remove(listener);
        }
    }

    /**
     * Registers a {@link AudioDescriptionRequestedChangeListener}
     * for changes in the audio description by default state of the system.
     * The value could be read via {@link #isAudioDescriptionRequested}.
     *
     * @param executor The executor on which the listener should be called back.
     * @param listener The listener.
     */
    public void addAudioDescriptionRequestedChangeListener(
            @NonNull Executor executor,
            @NonNull AudioDescriptionRequestedChangeListener listener) {
        synchronized (mLock) {
            mAudioDescriptionRequestedChangeListeners.put(listener, executor);
        }
    }

    /**
     * Unregisters a {@link AudioDescriptionRequestedChangeListener}.
     *
     * @param listener The listener.
     * @return True if listener was previously registered.
     */
    public boolean removeAudioDescriptionRequestedChangeListener(
            @NonNull AudioDescriptionRequestedChangeListener listener) {
        synchronized (mLock) {
            return (mAudioDescriptionRequestedChangeListeners.remove(listener) != null);
        }
    }

    /**
     * Sets the {@link AccessibilityPolicy} controlling this manager.
     *
     * @param policy The policy.
     *
     * @hide
     */
    public void setAccessibilityPolicy(@Nullable AccessibilityPolicy policy) {
        synchronized (mLock) {
            mAccessibilityPolicy = policy;
        }
    }

    /**
     * Check if the accessibility volume stream is active.
     *
     * @return True if accessibility volume is active (i.e. some service has requested it). False
     * otherwise.
     * @hide
     */
    public boolean isAccessibilityVolumeStreamActive() {
        List<AccessibilityServiceInfo> serviceInfos =
                getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (int i = 0; i < serviceInfos.size(); i++) {
            if ((serviceInfos.get(i).flags & FLAG_ENABLE_ACCESSIBILITY_VOLUME) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report a fingerprint gesture to accessibility. Only available for the system process.
     *
     * @param keyCode The key code of the gesture
     * @return {@code true} if accessibility consumes the event. {@code false} if not.
     * @hide
     */
    public boolean sendFingerprintGesture(int keyCode) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }
        try {
            return service.sendFingerprintGesture(keyCode);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns accessibility window id from window token. Accessibility window id is the one
     * returned from AccessibilityWindowInfo.getId(). Only available for the system process.
     *
     * @param windowToken Window token to find accessibility window id.
     * @return Accessibility window id for the window token.
     *   AccessibilityWindowInfo.UNDEFINED_WINDOW_ID if accessibility window id not available for
     *   the token.
     * @hide
     */
    @SystemApi
    public int getAccessibilityWindowId(@Nullable IBinder windowToken) {
        if (windowToken == null) {
            return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }

        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }
        }
        try {
            return service.getAccessibilityWindowId(windowToken);
        } catch (RemoteException e) {
            return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }
    }

    /**
     * Associate the connection between the host View and the embedded SurfaceControlViewHost.
     *
     * @hide
     */
    public void associateEmbeddedHierarchy(@NonNull IBinder host, @NonNull IBinder embedded) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.associateEmbeddedHierarchy(host, embedded);
        } catch (RemoteException e) {
            return;
        }
    }

    /**
     * Disassociate the connection between the host View and the embedded SurfaceControlViewHost.
     * The given token could be either from host side or embedded side.
     *
     * @hide
     */
    public void disassociateEmbeddedHierarchy(@NonNull IBinder token) {
        if (token == null) {
            return;
        }
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.disassociateEmbeddedHierarchy(token);
        } catch (RemoteException e) {
            return;
        }
    }

    /**
     * Sets the current state and notifies listeners, if necessary.
     *
     * @param stateFlags The state flags.
     */
    @UnsupportedAppUsage
    private void setStateLocked(int stateFlags) {
        final boolean enabled = (stateFlags & STATE_FLAG_ACCESSIBILITY_ENABLED) != 0;
        final boolean touchExplorationEnabled =
                (stateFlags & STATE_FLAG_TOUCH_EXPLORATION_ENABLED) != 0;
        final boolean highTextContrastEnabled =
                (stateFlags & STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED) != 0;
        final boolean audioDescriptionEnabled =
                (stateFlags & STATE_FLAG_AUDIO_DESCRIPTION_BY_DEFAULT_ENABLED) != 0;

        final boolean wasEnabled = isEnabled();
        final boolean wasTouchExplorationEnabled = mIsTouchExplorationEnabled;
        final boolean wasHighTextContrastEnabled = mIsHighTextContrastEnabled;
        final boolean wasAudioDescriptionByDefaultRequested = mIsAudioDescriptionByDefaultRequested;

        // Ensure listeners get current state from isZzzEnabled() calls.
        mIsEnabled = enabled;
        mIsTouchExplorationEnabled = touchExplorationEnabled;
        mIsHighTextContrastEnabled = highTextContrastEnabled;
        mIsAudioDescriptionByDefaultRequested = audioDescriptionEnabled;

        if (wasEnabled != isEnabled()) {
            notifyAccessibilityStateChanged();
        }

        if (wasTouchExplorationEnabled != touchExplorationEnabled) {
            notifyTouchExplorationStateChanged();
        }

        if (wasHighTextContrastEnabled != highTextContrastEnabled) {
            notifyHighTextContrastStateChanged();
        }

        if (wasAudioDescriptionByDefaultRequested
                != audioDescriptionEnabled) {
            notifyAudioDescriptionbyDefaultStateChanged();
        }

        updateAccessibilityTracingState(stateFlags);
    }

    /**
     * Find an installed service with the specified {@link ComponentName}.
     *
     * @param componentName The name to match to the service.
     *
     * @return The info corresponding to the installed service, or {@code null} if no such service
     * is installed.
     * @hide
     */
    public AccessibilityServiceInfo getInstalledServiceInfoWithComponentName(
            ComponentName componentName) {
        final List<AccessibilityServiceInfo> installedServiceInfos =
                getInstalledAccessibilityServiceList();
        if ((installedServiceInfos == null) || (componentName == null)) {
            return null;
        }
        for (int i = 0; i < installedServiceInfos.size(); i++) {
            if (componentName.equals(installedServiceInfos.get(i).getComponentName())) {
                return installedServiceInfos.get(i);
            }
        }
        return null;
    }

    /**
     * Adds an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is added.
     * @param leashToken The leash token to which a connection is added.
     * @param connection The connection.
     *
     * @hide
     */
    public int addAccessibilityInteractionConnection(IWindow windowToken, IBinder leashToken,
            String packageName, IAccessibilityInteractionConnection connection) {
        final IAccessibilityManager service;
        final int userId;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return View.NO_ID;
            }
            userId = mUserId;
        }
        try {
            return service.addAccessibilityInteractionConnection(windowToken, leashToken,
                    connection, packageName, userId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while adding an accessibility interaction connection. ", re);
        }
        return View.NO_ID;
    }

    /**
     * Removed an accessibility interaction connection interface for a given window.
     * @param windowToken The window token to which a connection is removed.
     *
     * @hide
     */
    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.removeAccessibilityInteractionConnection(windowToken);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while removing an accessibility interaction connection. ", re);
        }
    }

    /**
     * Perform the accessibility shortcut if the caller has permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    public void performAccessibilityShortcut() {
        performAccessibilityShortcut(null);
    }

    /**
     * Perform the accessibility shortcut for the given target which is assigned to the shortcut.
     *
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    public void performAccessibilityShortcut(@Nullable String targetName) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.performAccessibilityShortcut(targetName);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error performing accessibility shortcut. ", re);
        }
    }

    /**
     * Register the provided {@link RemoteAction} with the given actionId
     * <p>
     * To perform established system actions, an accessibility service uses the GLOBAL_ACTION
     * constants in {@link android.accessibilityservice.AccessibilityService}. To provide a
     * customized implementation for one of these actions, the id of the registered system action
     * must match that of the corresponding GLOBAL_ACTION constant. For example, to register a
     * Back action, {@code actionId} must be
     * {@link android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_BACK}
     * </p>
     * @param action The remote action to be registered with the given actionId as system action.
     * @param actionId The id uniquely identify the system action.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    public void registerSystemAction(@NonNull RemoteAction action, int actionId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.registerSystemAction(action, actionId);

            if (DEBUG) {
                Log.i(LOG_TAG, "System action " + action.getTitle() + " is registered.");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error registering system action " + action.getTitle() + " ", re);
        }
    }

   /**
     * Unregister a system action with the given actionId
     *
     * @param actionId The id uniquely identify the system action to be unregistered.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    public void unregisterSystemAction(int actionId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.unregisterSystemAction(actionId);

            if (DEBUG) {
                Log.i(LOG_TAG, "System action with actionId " + actionId + " is unregistered.");
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error unregistering system action with actionId " + actionId + " ", re);
        }
    }

    /**
     * Notifies that the accessibility button in the system's navigation area has been clicked
     *
     * @param displayId The logical display id.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
    public void notifyAccessibilityButtonClicked(int displayId) {
        notifyAccessibilityButtonClicked(displayId, null);
    }

    /**
     * Perform the accessibility button for the given target which is assigned to the button.
     *
     * @param displayId displayId The logical display id.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
    public void notifyAccessibilityButtonClicked(int displayId, @Nullable String targetName) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.notifyAccessibilityButtonClicked(displayId, targetName);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while dispatching accessibility button click", re);
        }
    }

    /**
     * Notifies that the visibility of the accessibility button in the system's navigation area
     * has changed.
     *
     * @param shown {@code true} if the accessibility button is visible within the system
     *                  navigation area, {@code false} otherwise
     * @hide
     */
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.notifyAccessibilityButtonVisibilityChanged(shown);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while dispatching accessibility button visibility change", re);
        }
    }

    /**
     * Set an IAccessibilityInteractionConnection to replace the actions of a picture-in-picture
     * window. Intended for use by the System UI only.
     *
     * @param connection The connection to handle the actions. Set to {@code null} to avoid
     * affecting the actions.
     *
     * @hide
     */
    public void setPictureInPictureActionReplacingConnection(
            @Nullable IAccessibilityInteractionConnection connection) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setPictureInPictureActionReplacingConnection(connection);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting picture in picture action replacement", re);
        }
    }

    /**
     * Returns the list of shortcut target names currently assigned to the given shortcut.
     *
     * @param shortcutType The shortcut type.
     * @return The list of shortcut target names.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    @NonNull
    public List<String> getAccessibilityShortcutTargets(@ShortcutType int shortcutType) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
        }
        if (service != null) {
            try {
                return service.getAccessibilityShortcutTargets(shortcutType);
            } catch (RemoteException re) {
                re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Returns the {@link AccessibilityShortcutInfo}s of the installed accessibility shortcut
     * targets, for specific user.
     *
     * @param context The context of the application.
     * @param userId The user id.
     * @return A list with {@link AccessibilityShortcutInfo}s.
     * @hide
     */
    @NonNull
    public List<AccessibilityShortcutInfo> getInstalledAccessibilityShortcutListAsUser(
            @NonNull Context context, @UserIdInt int userId) {
        final List<AccessibilityShortcutInfo> shortcutInfos = new ArrayList<>();
        final int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        final Intent actionMain = new Intent(Intent.ACTION_MAIN);
        actionMain.addCategory(Intent.CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET);

        final PackageManager packageManager = context.getPackageManager();
        final List<ResolveInfo> installedShortcutList =
                packageManager.queryIntentActivitiesAsUser(actionMain, flags, userId);
        for (int i = 0; i < installedShortcutList.size(); i++) {
            final AccessibilityShortcutInfo shortcutInfo =
                    getShortcutInfo(context, installedShortcutList.get(i));
            if (shortcutInfo != null) {
                shortcutInfos.add(shortcutInfo);
            }
        }
        return shortcutInfos;
    }

    /**
     * Returns an {@link AccessibilityShortcutInfo} according to the given {@link ResolveInfo} of
     * an activity.
     *
     * @param context The context of the application.
     * @param resolveInfo The resolve info of an activity.
     * @return The AccessibilityShortcutInfo.
     */
    @Nullable
    private AccessibilityShortcutInfo getShortcutInfo(@NonNull Context context,
            @NonNull ResolveInfo resolveInfo) {
        final ActivityInfo activityInfo = resolveInfo.activityInfo;
        if (activityInfo == null || activityInfo.metaData == null
                || activityInfo.metaData.getInt(AccessibilityShortcutInfo.META_DATA) == 0) {
            return null;
        }
        try {
            return new AccessibilityShortcutInfo(context, activityInfo);
        } catch (XmlPullParserException | IOException exp) {
            Log.e(LOG_TAG, "Error while initializing AccessibilityShortcutInfo", exp);
        }
        return null;
    }

    /**
     *
     * Sets an {@link IWindowMagnificationConnection} that manipulates window magnification.
     *
     * @param connection The connection that manipulates window magnification.
     * @hide
     */
    public void setWindowMagnificationConnection(@Nullable
            IWindowMagnificationConnection connection) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setWindowMagnificationConnection(connection);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error setting window magnfication connection", re);
        }
    }

    /**
     * Determines if users want to select sound track with audio description by default.
     * <p>
     * Audio description, also referred to as a video description, described video, or
     * more precisely called a visual description, is a form of narration used to provide
     * information surrounding key visual elements in a media work for the benefit of
     * blind and visually impaired consumers.
     * </p>
     * <p>
     * The method provides the preference value to content provider apps to select the
     * default sound track during playing a video or movie.
     * </p>
     * <p>
     * Add listener to detect the state change via
     * {@link #addAudioDescriptionRequestedChangeListener}
     * </p>
     * @return {@code true} if the audio description is enabled, {@code false} otherwise.
     */
    public boolean isAudioDescriptionRequested() {
        synchronized (mLock) {
            IAccessibilityManager service = getServiceLocked();
            if (service == null) {
                return false;
            }
            return mIsAudioDescriptionByDefaultRequested;
        }
    }

    /**
     * Sets the system audio caption enabled state.
     *
     * @param isEnabled The system audio captioning enabled state.
     * @param userId The user Id.
     * @hide
     */
    public void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setSystemAudioCaptioningEnabled(isEnabled, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the system audio caption UI enabled state.
     *
     * @param userId The user Id.
     * @return the system audio caption UI enabled state.
     * @hide
     */
    public boolean isSystemAudioCaptioningUiEnabled(int userId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }
        try {
            return service.isSystemAudioCaptioningUiEnabled(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the system audio caption UI enabled state.
     *
     * @param isEnabled The system audio captioning UI enabled state.
     * @param userId The user Id.
     * @hide
     */
    public void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setSystemAudioCaptioningUiEnabled(isEnabled, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }


    /**
     * Sets the {@link AccessibilityWindowAttributes} to the window associated with the given
     * window id.
     *
     * @param displayId The display id of the window.
     * @param windowId  The id of the window.
     * @param attributes The accessibility window attributes.
     * @hide
     */
    public void setAccessibilityWindowAttributes(int displayId, int windowId,
            AccessibilityWindowAttributes attributes) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return;
            }
        }
        try {
            service.setAccessibilityWindowAttributes(displayId, windowId, mUserId, attributes);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Registers an {@link AccessibilityDisplayProxy}, so this proxy can access UI content specific
     * to its display.
     *
     * @param proxy the {@link AccessibilityDisplayProxy} to register.
     * @return {@code true} if the proxy is successfully registered.
     *
     * @throws IllegalArgumentException if the proxy's display is not currently tracked by a11y, is
     * {@link android.view.Display#DEFAULT_DISPLAY}, is or lower than
     * {@link android.view.Display#INVALID_DISPLAY}, or is already being proxy-ed.
     *
     * @throws SecurityException if the app does not hold the
     * {@link Manifest.permission#MANAGE_ACCESSIBILITY} permission or the
     * {@link Manifest.permission#CREATE_VIRTUAL_DEVICE} permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {Manifest.permission.MANAGE_ACCESSIBILITY,
            Manifest.permission.CREATE_VIRTUAL_DEVICE})
    public boolean registerDisplayProxy(@NonNull AccessibilityDisplayProxy proxy) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.registerProxyForDisplay(proxy.mServiceClient, proxy.getDisplayId());
        }  catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters an {@link AccessibilityDisplayProxy}.
     *
     * @return {@code true} if the proxy is successfully unregistered.
     *
     * @throws SecurityException if the app does not hold the
     * {@link Manifest.permission#MANAGE_ACCESSIBILITY} permission or the
     * {@link Manifest.permission#CREATE_VIRTUAL_DEVICE} permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {Manifest.permission.MANAGE_ACCESSIBILITY,
            Manifest.permission.CREATE_VIRTUAL_DEVICE})
    public boolean unregisterDisplayProxy(@NonNull AccessibilityDisplayProxy proxy)  {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }
        try {
            return service.unregisterProxyForDisplay(proxy.getDisplayId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Start sequence (infinite) type of flash notification. Use
     * {@code Context.getOpPackageName()} as the identifier of this flash notification.
     * The notification can be cancelled later by calling {@link #stopFlashNotificationSequence}
     * with same {@code Context.getOpPackageName()}.
     * If the binder associated with this {@link AccessibilityManager} instance dies then the
     * sequence will stop automatically. It is strongly recommended to call
     * {@link #stopFlashNotificationSequence} within a reasonable amount of time after calling
     * this method.
     *
     * @param context The context in which this manager operates.
     * @param reason The triggering reason of flash notification.
     * @return {@code true} if flash notification works properly.
     * @hide
     */
    public boolean startFlashNotificationSequence(@NonNull Context context,
            @FlashNotificationReason int reason) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.startFlashNotificationSequence(context.getOpPackageName(),
                    reason, mBinder);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while start flash notification sequence", re);
            return false;
        }
    }

    /**
     * Stop sequence (infinite) type of flash notification. The flash notification with
     * {@code Context.getOpPackageName()} as identifier will be stopped if exist.
     * It is strongly recommended to call this method within a reasonable amount of time after
     * calling {@link #startFlashNotificationSequence} method.
     *
     * @param context The context in which this manager operates.
     * @return {@code true} if flash notification stops properly.
     * @hide
     */
    public boolean stopFlashNotificationSequence(@NonNull Context context) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.stopFlashNotificationSequence(context.getOpPackageName());
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while stop flash notification sequence", re);
            return false;
        }
    }

    /**
     * Start event (finite) type of flash notification.
     *
     * @param context The context in which this manager operates.
     * @param reason The triggering reason of flash notification.
     * @param reasonPkg The package that trigger the flash notification.
     * @return {@code true} if flash notification works properly.
     * @hide
     */
    public boolean startFlashNotificationEvent(@NonNull Context context,
            @FlashNotificationReason int reason, @Nullable String reasonPkg) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.startFlashNotificationEvent(context.getOpPackageName(),
                    reason, reasonPkg);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while start flash notification event", re);
            return false;
        }
    }

    /**
     * Determines if the accessibility target is allowed.
     *
     * @param packageName The name of the application attempting to perform the operation.
     * @param uid The user id of the application attempting to perform the operation.
     * @param userId The id of the user for whom to perform the operation.
     * @return {@code true} the accessibility target is allowed.
     * @hide
     */
    public boolean isAccessibilityTargetAllowed(String packageName, int uid, int userId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.isAccessibilityTargetAllowed(packageName, uid, userId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while check accessibility target status", re);
            return false;
        }
    }

    /**
     * Sends restricted dialog intent if the accessibility target is disallowed.
     *
     * @param packageName The name of the application attempting to perform the operation.
     * @param uid The user id of the application attempting to perform the operation.
     * @param userId The id of the user for whom to perform the operation.
     * @return {@code true} if the restricted dialog is shown.
     * @hide
     */
    public boolean sendRestrictedDialogIntent(String packageName, int uid, int userId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return false;
            }
        }

        try {
            return service.sendRestrictedDialogIntent(packageName, uid, userId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while show restricted dialog", re);
            return false;
        }
    }

    private IAccessibilityManager getServiceLocked() {
        if (mService == null) {
            tryConnectToServiceLocked(null);
        }
        return mService;
    }

    private void tryConnectToServiceLocked(IAccessibilityManager service) {
        if (service == null) {
            IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
            if (iBinder == null) {
                return;
            }
            service = IAccessibilityManager.Stub.asInterface(iBinder);
        }

        try {
            final long userStateAndRelevantEvents = service.addClient(mClient, mUserId);
            setStateLocked(IntPair.first(userStateAndRelevantEvents));
            mRelevantEventTypes = IntPair.second(userStateAndRelevantEvents);
            updateUiTimeout(service.getRecommendedTimeoutMillis());
            updateFocusAppearanceLocked(service.getFocusStrokeWidth(), service.getFocusColor());
            mService = service;
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "AccessibilityManagerService is dead", re);
        }
    }

    /**
     * Notifies the registered {@link AccessibilityStateChangeListener}s.
     *
     * Note: this method notifies only the listeners of this single instance.
     * AccessibilityManagerService is responsible for calling this method on all of
     * its AccessibilityManager clients in order to notify all listeners.
     * @hide
     */
    public void notifyAccessibilityStateChanged() {
        final boolean isEnabled;
        final ArrayMap<AccessibilityStateChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mAccessibilityStateChangeListeners.isEmpty()) {
                return;
            }
            isEnabled = isEnabled();
            listeners = new ArrayMap<>(mAccessibilityStateChangeListeners);
        }

        final int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final AccessibilityStateChangeListener listener = listeners.keyAt(i);
            listeners.valueAt(i).post(() ->
                    listener.onAccessibilityStateChanged(isEnabled));
        }
    }

    /**
     * Notifies the registered {@link TouchExplorationStateChangeListener}s.
     */
    private void notifyTouchExplorationStateChanged() {
        final boolean isTouchExplorationEnabled;
        final ArrayMap<TouchExplorationStateChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mTouchExplorationStateChangeListeners.isEmpty()) {
                return;
            }
            isTouchExplorationEnabled = mIsTouchExplorationEnabled;
            listeners = new ArrayMap<>(mTouchExplorationStateChangeListeners);
        }

        final int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final TouchExplorationStateChangeListener listener = listeners.keyAt(i);
            listeners.valueAt(i).post(() ->
                    listener.onTouchExplorationStateChanged(isTouchExplorationEnabled));
        }
    }

    /**
     * Notifies the registered {@link HighTextContrastChangeListener}s.
     */
    private void notifyHighTextContrastStateChanged() {
        final boolean isHighTextContrastEnabled;
        final ArrayMap<HighTextContrastChangeListener, Handler> listeners;
        synchronized (mLock) {
            if (mHighTextContrastStateChangeListeners.isEmpty()) {
                return;
            }
            isHighTextContrastEnabled = mIsHighTextContrastEnabled;
            listeners = new ArrayMap<>(mHighTextContrastStateChangeListeners);
        }

        final int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final HighTextContrastChangeListener listener = listeners.keyAt(i);
            listeners.valueAt(i).post(() ->
                    listener.onHighTextContrastStateChanged(isHighTextContrastEnabled));
        }
    }

    /**
     * Notifies the registered {@link AudioDescriptionStateChangeListener}s.
     */
    private void notifyAudioDescriptionbyDefaultStateChanged() {
        final boolean isAudioDescriptionByDefaultRequested;
        final ArrayMap<AudioDescriptionRequestedChangeListener, Executor> listeners;
        synchronized (mLock) {
            if (mAudioDescriptionRequestedChangeListeners.isEmpty()) {
                return;
            }
            isAudioDescriptionByDefaultRequested = mIsAudioDescriptionByDefaultRequested;
            listeners = new ArrayMap<>(mAudioDescriptionRequestedChangeListeners);
        }

        final int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            final AudioDescriptionRequestedChangeListener listener = listeners.keyAt(i);
            listeners.valueAt(i).execute(() ->
                    listener.onAudioDescriptionRequestedChanged(
                        isAudioDescriptionByDefaultRequested));
        }
    }

    /**
     * Update mAccessibilityTracingState.
     */
    private void updateAccessibilityTracingState(int stateFlag) {
        synchronized (mLock) {
            mAccessibilityTracingState = stateFlag;
        }
    }

    /**
     * Update interactive and non-interactive UI timeout.
     *
     * @param uiTimeout A pair of {@code int}s. First integer for interactive one, and second
     *                  integer for non-interactive one.
     */
    private void updateUiTimeout(long uiTimeout) {
        mInteractiveUiTimeout = IntPair.first(uiTimeout);
        mNonInteractiveUiTimeout = IntPair.second(uiTimeout);
    }

    /**
     * Updates the stroke width and color of the focus rectangle.
     *
     * @param strokeWidth The strokeWidth of the focus rectangle.
     * @param color The color of the focus rectangle.
     */
    private void updateFocusAppearanceLocked(int strokeWidth, int color) {
        if (mFocusStrokeWidth == strokeWidth && mFocusColor == color) {
            return;
        }
        mFocusStrokeWidth = strokeWidth;
        mFocusColor = color;
    }

    /**
     * Sets the stroke width and color of the focus rectangle to default value.
     *
     * @param resource The resources.
     */
    private void initialFocusAppearanceLocked(Resources resource) {
        try {
            mFocusStrokeWidth = resource.getDimensionPixelSize(
                    R.dimen.accessibility_focus_highlight_stroke_width);
            mFocusColor = resource.getColor(R.color.accessibility_focus_highlight_color);
        } catch (Resources.NotFoundException re) {
            // Sets the stroke width and color to default value by hardcoded for making
            // the Talkback can work normally.
            mFocusStrokeWidth = (int) (4 * resource.getDisplayMetrics().density);
            mFocusColor = 0xbf39b500;
            Log.e(LOG_TAG, "Error while initialing the focus appearance data then setting to"
                    + " default value by hardcoded", re);
        }
    }

    /**
     * Determines if the accessibility button within the system navigation area is supported.
     *
     * @return {@code true} if the accessibility button is supported on this device,
     * {@code false} otherwise
     */
    public static boolean isAccessibilityButtonSupported() {
        final Resources res = Resources.getSystem();
        return res.getBoolean(com.android.internal.R.bool.config_showNavigationBar);
    }

    private final class MyCallback implements Handler.Callback {
        public static final int MSG_SET_STATE = 1;

        @Override
        public boolean handleMessage(Message message) {
            switch (message.what) {
                case MSG_SET_STATE: {
                    // See comment at mClient
                    final int state = message.arg1;
                    synchronized (mLock) {
                        setStateLocked(state);
                    }
                } break;
            }
            return true;
        }
    }

    /**
     * Retrieves the window's transformation matrix and magnification spec.
     *
     * <p>
     * Used by callers outside of the AccessibilityManagerService process which need
     * this information, like {@link android.view.accessibility.DirectAccessibilityConnection}.
     * </p>
     *
     * @return The transformation spec
     * @hide
     */
    public IAccessibilityManager.WindowTransformationSpec getWindowTransformationSpec(
            int windowId) {
        final IAccessibilityManager service;
        synchronized (mLock) {
            service = getServiceLocked();
            if (service == null) {
                return null;
            }
        }
        try {
            return service.getWindowTransformationSpec(windowId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
}
