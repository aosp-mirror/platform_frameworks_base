/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;
import static android.view.accessibility.AccessibilityManager.ShortcutType;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.appwidget.AppWidgetManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.IFingerprintService;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.provider.SettingsStringUtil.SettingStringHelper;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.accessibility.IWindowMagnificationConnection;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.AccessibilityShortcutController.ToggleableFrameworkFeatureInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.accessibility.magnification.WindowMagnificationManager;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub
        implements AbstractAccessibilityServiceConnection.SystemSupport,
        AccessibilityUserState.ServiceInfoChangeListener,
        AccessibilityWindowManager.AccessibilityEventSender,
        AccessibilitySecurityPolicy.AccessibilityUserManager,
        SystemActionPerformer.SystemActionsChangedListener {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS = 1000;

    static final String FUNCTION_REGISTER_SYSTEM_ACTION = "registerSystemAction";
    static final String FUNCTION_UNREGISTER_SYSTEM_ACTION = "unregisterSystemAction";
    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED =
            "temporaryEnableAccessibilityStateUntilKeyguardRemoved";

    private static final String GET_WINDOW_TOKEN = "getWindowToken";

    private static final String SET_PIP_ACTION_REPLACEMENT =
            "setPictureInPictureActionReplacingConnection";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    // Each service has an ID. Also provide one for magnification gesture handling
    public static final int MAGNIFICATION_GESTURE_HANDLER_ID = 0;

    private static int sIdCounter = MAGNIFICATION_GESTURE_HANDLER_ID + 1;

    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final WindowManagerInternal mWindowManagerService;

    private final AccessibilitySecurityPolicy mSecurityPolicy;

    private final AccessibilityWindowManager mA11yWindowManager;

    private final AccessibilityDisplayListener mA11yDisplayListener;

    private final ActivityTaskManagerInternal mActivityTaskManagerService;

    private final MainHandler mMainHandler;

    private final SystemActionPerformer mSystemActionPerformer;

    private MagnificationController mMagnificationController;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private WindowMagnificationManager mWindowMagnificationMgr;

    private boolean mHasInputFilter;

    private KeyEventDispatcher mKeyEventDispatcher;

    private SparseArray<MotionEventInjector> mMotionEventInjectors;

    private FingerprintGestureDispatcher mFingerprintGestureDispatcher;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final List<AccessibilityServiceInfo> mTempAccessibilityServiceInfoList =
            new ArrayList<>();

    private final IntArray mTempIntArray = new IntArray(0);

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    private final SparseArray<AccessibilityUserState> mUserStates = new SparseArray<>();

    private final UiAutomationManager mUiAutomationManager = new UiAutomationManager(mLock);

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    //TODO: Remove this hack
    private boolean mInitialized;

    private boolean mIsAccessibilityButtonShown;

    private AccessibilityUserState getCurrentUserStateLocked() {
        return getUserStateLocked(mCurrentUserId);
    }

    public static final class Lifecycle extends SystemService {
        private final AccessibilityManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new AccessibilityManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.ACCESSIBILITY_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.onBootPhase(phase);
        }
    }

    @VisibleForTesting
    AccessibilityManagerService(
            Context context,
            PackageManager packageManager,
            AccessibilitySecurityPolicy securityPolicy,
            SystemActionPerformer systemActionPerformer,
            AccessibilityWindowManager a11yWindowManager,
            AccessibilityDisplayListener a11yDisplayListener) {
        mContext = context;
        mPowerManager =  (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = packageManager;
        mSecurityPolicy = securityPolicy;
        mSystemActionPerformer = systemActionPerformer;
        mA11yWindowManager = a11yWindowManager;
        mA11yDisplayListener = a11yDisplayListener;
        init();
    }

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPowerManager =  (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = mContext.getPackageManager();
        mSecurityPolicy = new AccessibilitySecurityPolicy(mContext, this);
        mSystemActionPerformer =
                new SystemActionPerformer(mContext, mWindowManagerService, null, this);
        mA11yWindowManager = new AccessibilityWindowManager(mLock, mMainHandler,
                mWindowManagerService, this, mSecurityPolicy, this);
        mA11yDisplayListener = new AccessibilityDisplayListener(mContext, mMainHandler);
        init();
    }

    private void init() {
        mSecurityPolicy.setAccessibilityWindowManager(mA11yWindowManager);
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                mContext.getContentResolver());
    }

    @Override
    public int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @Override
    public boolean isAccessibilityButtonShown() {
        return mIsAccessibilityButtonShown;
    }

    @Override
    public void onServiceInfoChangedLocked(AccessibilityUserState userState) {
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    @Nullable
    public FingerprintGestureDispatcher getFingerprintGestureDispatcher() {
        return mFingerprintGestureDispatcher;
    }

    private void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                mSecurityPolicy.setAppWidgetManager(
                        LocalServices.getService(AppWidgetManagerInternal.class));
            }
        }
    }

    private AccessibilityUserState getUserState(int userId) {
        synchronized (mLock) {
            return getUserStateLocked(userId);
        }
    }

    @NonNull
    private AccessibilityUserState getUserStateLocked(int userId) {
        AccessibilityUserState state = mUserStates.get(userId);
        if (state == null) {
            state = new AccessibilityUserState(userId, mContext, this);
            mUserStates.put(userId, state);
        }
        return state;
    }

    boolean getBindInstantServiceAllowed(int userId) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(userId);
            return userState.getBindInstantServiceAllowedLocked();
        }
    }

    void setBindInstantServiceAllowed(int userId, boolean allowed) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_BIND_INSTANT_SERVICE,
                "setBindInstantServiceAllowed");
        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(userId);
            if (allowed != userState.getBindInstantServiceAllowedLocked()) {
                userState.setBindInstantServiceAllowedLocked(allowed);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    // We will update when the automation service dies.
                    AccessibilityUserState userState = getCurrentUserStateLocked();
                    // We have to reload the installed services since some services may
                    // have different attributes, resolve info (does not support equals),
                    // etc. Remove them then to force reload.
                    userState.mInstalledServices.clear();
                    if (readConfigurationForUserStateLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                // The package should already be removed from mBoundServices, and added into
                // mBindingServices in binderDied() during updating. Remove services from  this
                // package from mBindingServices, and then update the user state to re-bind new
                // versions of them.
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    final AccessibilityUserState userState = getUserStateLocked(userId);
                    final boolean reboundAService = userState.getBindingServicesLocked().removeIf(
                            component -> component != null
                                    && component.getPackageName().equals(packageName))
                            || userState.mCrashedServices.removeIf(component -> component != null
                                    && component.getPackageName().equals(packageName));
                    // Reloads the installed services info to make sure the rebound service could
                    // get a new one.
                    userState.mInstalledServices.clear();
                    final boolean configurationChanged =
                            readConfigurationForUserStateLocked(userState);
                    if (reboundAService || configurationChanged) {
                        onUserStateChangedLocked(userState);
                    }
                    migrateAccessibilityButtonSettingsIfNecessaryLocked(userState, packageName);
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    AccessibilityUserState userState = getUserStateLocked(userId);
                    Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        if (compPkg.equals(packageName)) {
                            it.remove();
                            userState.getBindingServicesLocked().remove(comp);
                            userState.getCrashedServicesLocked().remove(comp);
                            // Update the enabled services setting.
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                    userState.mEnabledServices, userId);
                            // Update the touch exploration granted services setting.
                            userState.mTouchExplorationGrantedServices.remove(comp);
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.
                                    TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                    userState.mTouchExplorationGrantedServices, userId);
                            onUserStateChangedLocked(userState);
                            return;
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return false;
                    }
                    AccessibilityUserState userState = getUserStateLocked(userId);
                    Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        for (String pkg : packages) {
                            if (compPkg.equals(pkg)) {
                                if (!doit) {
                                    return true;
                                }
                                it.remove();
                                userState.getBindingServicesLocked().remove(comp);
                                persistComponentNamesToSettingLocked(
                                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                        userState.mEnabledServices, userId);
                                onUserStateChangedLocked(userState);
                            }
                        }
                    }
                    return false;
                }
            }
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);

        // user change and unlock
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_SETTING_RESTORED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    unlockUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // We will update when the automation service dies.
                    synchronized (mLock) {
                        AccessibilityUserState userState = getCurrentUserStateLocked();
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
                        }
                    }
                } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                    final String which = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(which)) {
                        synchronized (mLock) {
                            restoreEnabledAccessibilityServicesLocked(
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE),
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE));
                        }
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public long addClient(IAccessibilityManagerClient callback, int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // If the client is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            Client client = new Client(callback, Binder.getCallingUid(), userState);
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                mGlobalClients.register(callback, client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
                return IntPair.of(
                        getClientStateLocked(userState),
                        client.mLastSentRelevantEventTypes);
            } else {
                userState.mUserClients.register(callback, client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
                return IntPair.of(
                        (resolvedUserId == mCurrentUserId) ? getClientStateLocked(userState) : 0,
                        client.mLastSentRelevantEventTypes);
            }
        }
    }

    @Override
    public void sendAccessibilityEvent(AccessibilityEvent event, int userId) {
        boolean dispatchEvent = false;

        synchronized (mLock) {
            if (event.getWindowId() ==
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID) {
                // The replacer window isn't shown to services. Move its events into the pip.
                AccessibilityWindowInfo pip = mA11yWindowManager.getPictureInPictureWindowLocked();
                if (pip != null) {
                    int pipId = pip.getId();
                    event.setWindowId(pipId);
                }
            }

            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // Make sure the reported package is one the caller has access to.
            event.setPackageName(mSecurityPolicy.resolveValidReportedPackageLocked(
                    event.getPackageName(), UserHandle.getCallingAppId(), resolvedUserId));

            // This method does nothing for a background user.
            if (resolvedUserId == mCurrentUserId) {
                if (mSecurityPolicy.canDispatchAccessibilityEventLocked(mCurrentUserId, event)) {
                    mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(
                            mCurrentUserId, event.getWindowId(), event.getSourceNodeId(),
                            event.getEventType(), event.getAction());
                    mSecurityPolicy.updateEventSourceLocked(event);
                    dispatchEvent = true;
                }
                if (mHasInputFilter && mInputFilter != null) {
                    mMainHandler.sendMessage(obtainMessage(
                            AccessibilityManagerService::sendAccessibilityEventToInputFilter,
                            this, AccessibilityEvent.obtain(event)));
                }
            }
        }

        if (dispatchEvent) {
            // Make sure clients receiving this event will be able to get the
            // current state of the windows as the window manager may be delaying
            // the computation for performance reasons.
            boolean shouldComputeWindows = false;
            int displayId = Display.INVALID_DISPLAY;
            synchronized (mLock) {
                final int windowId = event.getWindowId();
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        && windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                    displayId = mA11yWindowManager.getDisplayIdByUserIdAndWindowIdLocked(
                            mCurrentUserId, windowId);
                }
                if (displayId != Display.INVALID_DISPLAY
                        && mA11yWindowManager.isTrackingWindowsLocked(displayId)) {
                    shouldComputeWindows = true;
                }
            }
            if (shouldComputeWindows) {
                final WindowManagerInternal wm = LocalServices.getService(
                        WindowManagerInternal.class);
                wm.computeWindowsForAccessibility(displayId);
            }
            synchronized (mLock) {
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
                mUiAutomationManager.sendAccessibilityEventLocked(event);
            }
        }

        if (OWN_PROCESS_ID != Binder.getCallingPid()) {
            event.recycle();
        }
    }

    private void sendAccessibilityEventToInputFilter(AccessibilityEvent event) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyAccessibilityEvent(event);
            }
        }
        event.recycle();
    }

    /**
     * This is the implementation of AccessibilityManager system API.
     * System UI calls into this method through AccessibilityManager system API to register a
     * system action.
     */
    @Override
    public void registerSystemAction(RemoteAction action, int actionId) {
        mSecurityPolicy.enforceCallerIsRecentsOrHasPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY,
                FUNCTION_REGISTER_SYSTEM_ACTION);
        mSystemActionPerformer.registerSystemAction(actionId, action);
    }

    /**
     * This is the implementation of AccessibilityManager system API.
     * System UI calls into this method through AccessibilityManager system API to unregister a
     * system action.
     */
    @Override
    public void unregisterSystemAction(int actionId) {
        mSecurityPolicy.enforceCallerIsRecentsOrHasPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY,
                FUNCTION_UNREGISTER_SYSTEM_ACTION);
        mSystemActionPerformer.unregisterSystemAction(actionId);
    }

    @Override
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            return getUserStateLocked(resolvedUserId).mInstalledServices;
        }
    }

    @Override
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // The automation service can suppress other services.
            final AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                return Collections.emptyList();
            }

            final List<AccessibilityServiceConnection> services = userState.mBoundServices;
            final int serviceCount = services.size();
            final List<AccessibilityServiceInfo> result = new ArrayList<>(serviceCount);
            for (int i = 0; i < serviceCount; ++i) {
                final AccessibilityServiceConnection service = services.get(i);
                if ((service.mFeedbackType & feedbackType) != 0) {
                    result.add(service.getServiceInfo());
                }
            }
            return result;
        }
    }

    @Override
    public void interrupt(int userId) {
        List<IAccessibilityServiceClient> interfacesToInterrupt;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return;
            }
            List<AccessibilityServiceConnection> services =
                    getUserStateLocked(resolvedUserId).mBoundServices;
            int numServices = services.size();
            interfacesToInterrupt = new ArrayList<>(numServices);
            for (int i = 0; i < numServices; i++) {
                AccessibilityServiceConnection service = services.get(i);
                IBinder a11yServiceBinder = service.mService;
                IAccessibilityServiceClient a11yServiceInterface = service.mServiceInterface;
                if ((a11yServiceBinder != null) && (a11yServiceInterface != null)) {
                    interfacesToInterrupt.add(a11yServiceInterface);
                }
            }
        }
        for (int i = 0, count = interfacesToInterrupt.size(); i < count; i++) {
            try {
                interfacesToInterrupt.get(i).onInterrupt();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending interrupt request to "
                        + interfacesToInterrupt.get(i), re);
            }
        }
    }

    @Override
    public int addAccessibilityInteractionConnection(IWindow windowToken, IBinder leashToken,
            IAccessibilityInteractionConnection connection, String packageName,
            int userId) throws RemoteException {
        return mA11yWindowManager.addAccessibilityInteractionConnection(
                windowToken, leashToken, connection, packageName, userId);
    }

    @Override
    public void removeAccessibilityInteractionConnection(IWindow window) {
        mA11yWindowManager.removeAccessibilityInteractionConnection(window);
    }

    @Override
    public void setPictureInPictureActionReplacingConnection(
            IAccessibilityInteractionConnection connection) throws RemoteException {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.MODIFY_ACCESSIBILITY_DATA,
                SET_PIP_ACTION_REPLACEMENT);
        mA11yWindowManager.setPictureInPictureActionReplacingConnection(connection);
    }

    @Override
    public void registerUiTestAutomationService(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo,
            int flags) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE);

        synchronized (mLock) {
            mUiAutomationManager.registerUiTestAutomationServiceLocked(owner, serviceClient,
                    mContext, accessibilityServiceInfo, sIdCounter++, mMainHandler,
                    mSecurityPolicy, this, mWindowManagerService, mSystemActionPerformer,
                    mA11yWindowManager, flags);
            onUserStateChangedLocked(getCurrentUserStateLocked());
        }
    }

    @Override
    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        synchronized (mLock) {
            mUiAutomationManager.unregisterUiTestAutomationServiceLocked(serviceClient);
        }
    }

    @Override
    public void temporaryEnableAccessibilityStateUntilKeyguardRemoved(
            ComponentName service, boolean touchExplorationEnabled) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.TEMPORARY_ENABLE_ACCESSIBILITY,
                TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED);
        if (!mWindowManagerService.isKeyguardLocked()) {
            return;
        }
        synchronized (mLock) {
            // Set the temporary state.
            AccessibilityUserState userState = getCurrentUserStateLocked();

            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            userState.setDisplayMagnificationEnabledLocked(false);
            userState.disableShortcutMagnificationLocked();
            userState.setAutoclickEnabledLocked(false);
            userState.mEnabledServices.clear();
            userState.mEnabledServices.add(service);
            userState.getBindingServicesLocked().clear();
            userState.getCrashedServicesLocked().clear();
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.add(service);

            // User the current state instead settings.
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public IBinder getWindowToken(int windowId, int userId) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.RETRIEVE_WINDOW_TOKEN,
                GET_WINDOW_TOKEN);
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            if (resolvedUserId != mCurrentUserId) {
                return null;
            }
            if (mA11yWindowManager.findA11yWindowInfoByIdLocked(windowId) == null) {
                return null;
            }
            return mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(userId, windowId);
        }
    }

    /**
     * Invoked remotely over AIDL by SysUi when the accessibility button within the system's
     * navigation area has been clicked.
     *
     * @param displayId The logical display id.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    @Override
    public void notifyAccessibilityButtonClicked(int displayId, String targetName) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                displayId, ACCESSIBILITY_BUTTON, targetName));
    }

    /**
     * Invoked remotely over AIDL by SysUi when the visibility of the accessibility
     * button within the system's navigation area has changed.
     *
     * @param shown {@code true} if the accessibility button is shown to the
     *                  user, {@code false} otherwise
     */
    @Override
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        mSecurityPolicy.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR_SERVICE);
        synchronized (mLock) {
            notifyAccessibilityButtonVisibilityChangedLocked(shown);
        }
    }

    /**
     * Called when a gesture is detected on a display.
     *
     * @param gestureEvent the detail of the gesture.
     * @return true if the event is handled.
     */
    public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
        synchronized (mLock) {
            boolean handled = notifyGestureLocked(gestureEvent, false);
            if (!handled) {
                handled = notifyGestureLocked(gestureEvent, true);
            }
            return handled;
        }
    }

    /**
     * Called when the system action list is changed.
     */
    @Override
    public void onSystemActionsChanged() {
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            notifySystemActionsChangedLocked(state);
        }
    }

    @VisibleForTesting
    void notifySystemActionsChangedLocked(AccessibilityUserState userState) {
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            service.notifySystemActionsChangedLocked();
        }
    }

    @VisibleForTesting
    public boolean notifyKeyEvent(KeyEvent event, int policyFlags) {
        synchronized (mLock) {
            List<AccessibilityServiceConnection> boundServices =
                    getCurrentUserStateLocked().mBoundServices;
            if (boundServices.isEmpty()) {
                return false;
            }
            return getKeyEventDispatcher().notifyKeyEventLocked(event, policyFlags, boundServices);
        }
    }

    /**
     * Called by the MagnificationController when the state of display
     * magnification changes.
     *
     * @param displayId The logical display id.
     * @param region the new magnified region, may be empty if
     *               magnification is not enabled (e.g. scale is 1)
     * @param scale the new scale
     * @param centerX the new screen-relative center X coordinate
     * @param centerY the new screen-relative center Y coordinate
     */
    public void notifyMagnificationChanged(int displayId, @NonNull Region region,
            float scale, float centerX, float centerY) {
        synchronized (mLock) {
            notifyClearAccessibilityCacheLocked();
            notifyMagnificationChangedLocked(displayId, region, scale, centerX, centerY);
        }
    }

    /**
     * Called by AccessibilityInputFilter when it creates or destroys the motionEventInjector.
     * Not using a getter because the AccessibilityInputFilter isn't thread-safe
     *
     * @param motionEventInjectors The array of motionEventInjectors. May be null.
     *
     */
    void setMotionEventInjectors(SparseArray<MotionEventInjector> motionEventInjectors) {
        synchronized (mLock) {
            mMotionEventInjectors = motionEventInjectors;
            // We may be waiting on this object being set
            mLock.notifyAll();
        }
    }

    @Override
    public @Nullable MotionEventInjector getMotionEventInjectorForDisplayLocked(int displayId) {
        final long endMillis = SystemClock.uptimeMillis() + WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS;
        MotionEventInjector motionEventInjector = null;
        while ((mMotionEventInjectors == null) && (SystemClock.uptimeMillis() < endMillis)) {
            try {
                mLock.wait(endMillis - SystemClock.uptimeMillis());
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        if (mMotionEventInjectors == null) {
            Slog.e(LOG_TAG, "MotionEventInjector installation timed out");
        } else {
            motionEventInjector = mMotionEventInjectors.get(displayId);
        }
        return motionEventInjector;
    }

    /**
     * Perform an accessibility action on the view that currently has accessibility focus.
     * Has no effect if no item has accessibility focus, if the item with accessibility
     * focus does not expose the specified action, or if the action fails.
     *
     * @param action The action to perform.
     *
     * @return {@code true} if the action was performed. {@code false} if it was not.
     */
    public boolean performActionOnAccessibilityFocusedItem(
            AccessibilityNodeInfo.AccessibilityAction action) {
        return getInteractionBridge().performActionOnAccessibilityFocusedItemNotLocked(action);
    }

    public int getActiveWindowId() {
        return mA11yWindowManager.getActiveWindowId(mCurrentUserId);
    }

    public void onTouchInteractionStart() {
        mA11yWindowManager.onTouchInteractionStart();
    }

    public void onTouchInteractionEnd() {
        mA11yWindowManager.onTouchInteractionEnd();
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId && mInitialized) {
                return;
            }

            // Disconnect from services for the old user.
            AccessibilityUserState oldUserState = getCurrentUserStateLocked();
            oldUserState.onSwitchToAnotherUserLocked();

            // Disable the local managers for the old user.
            if (oldUserState.mUserClients.getRegisteredCallbackCount() > 0) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::sendStateToClients,
                        this, 0, oldUserState.mUserId));
            }

            // Announce user changes only if more that one exist.
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            final boolean announceNewUser = userManager.getUsers().size() > 1;

            // The user changed.
            mCurrentUserId = userId;

            AccessibilityUserState userState = getCurrentUserStateLocked();

            readConfigurationForUserStateLocked(userState);
            // Even if reading did not yield change, we have to update
            // the state since the context in which the current user
            // state was used has changed since it was inactive.
            onUserStateChangedLocked(userState);
            migrateAccessibilityButtonSettingsIfNecessaryLocked(userState, null);

            if (announceNewUser) {
                // Schedule announcement of the current user if needed.
                mMainHandler.sendMessageDelayed(
                        obtainMessage(AccessibilityManagerService::announceNewUserIfNeeded, this),
                        WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS);
            }
        }
    }

    private void announceNewUserIfNeeded() {
        synchronized (mLock) {
            AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.isHandlingAccessibilityEventsLocked()) {
                UserManager userManager = (UserManager) mContext.getSystemService(
                        Context.USER_SERVICE);
                String message = mContext.getString(R.string.user_switched,
                        userManager.getUserInfo(mCurrentUserId).name);
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_ANNOUNCEMENT);
                event.getText().add(message);
                sendAccessibilityEventLocked(event, mCurrentUserId);
            }
        }
    }

    private void unlockUser(int userId) {
        synchronized (mLock) {
            int parentUserId = mSecurityPolicy.resolveProfileParentLocked(userId);
            if (parentUserId == mCurrentUserId) {
                AccessibilityUserState userState = getUserStateLocked(mCurrentUserId);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            mUserStates.remove(userId);
        }
    }

    // Called only during settings restore; currently supports only the owner user
    // TODO: http://b/22388012
    void restoreEnabledAccessibilityServicesLocked(String oldSetting, String newSetting) {
        readComponentNamesFromStringLocked(oldSetting, mTempComponentNameSet, false);
        readComponentNamesFromStringLocked(newSetting, mTempComponentNameSet, true);

        AccessibilityUserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        userState.mEnabledServices.clear();
        userState.mEnabledServices.addAll(mTempComponentNameSet);
        persistComponentNamesToSettingLocked(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mEnabledServices,
                UserHandle.USER_SYSTEM);
        onUserStateChangedLocked(userState);
    }

    private int getClientStateLocked(AccessibilityUserState userState) {
        return userState.getClientStateLocked(mUiAutomationManager.isUiAutomationRunningLocked());
    }

    private InteractionBridge getInteractionBridge() {
        synchronized (mLock) {
            if (mInteractionBridge == null) {
                mInteractionBridge = new InteractionBridge();
            }
            return mInteractionBridge;
        }
    }

    private boolean notifyGestureLocked(AccessibilityGestureEvent gestureEvent, boolean isDefault) {
        // TODO: Now we are giving the gestures to the last enabled
        //       service that can handle them which is the last one
        //       in our list since we write the last enabled as the
        //       last record in the enabled services setting. Ideally,
        //       the user should make the call which service handles
        //       gestures. However, only one service should handle
        //       gestures to avoid user frustration when different
        //       behavior is observed from different combinations of
        //       enabled accessibility services.
        AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureEvent);
                return true;
            }
        }
        return false;
    }

    private void notifyClearAccessibilityCacheLocked() {
        AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
        }
    }

    private void notifyMagnificationChangedLocked(int displayId, @NonNull Region region,
            float scale, float centerX, float centerY) {
        final AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyMagnificationChangedLocked(displayId, region, scale, centerX, centerY);
        }
    }

    private void sendAccessibilityButtonToInputFilter(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyAccessibilityButtonClicked(displayId);
            }
        }
    }

    private void showAccessibilityTargetsSelection(int displayId,
            @ShortcutType int shortcutType) {
        Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(AccessibilityManager.EXTRA_SHORTCUT_TYPE, shortcutType);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
    }

    private void launchShortcutTargetActivity(int displayId, ComponentName name) {
        final Intent intent = new Intent();
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        intent.setComponent(name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
        } catch (ActivityNotFoundException ignore) {
            // ignore the exception
        }
    }

    private void notifyAccessibilityButtonVisibilityChangedLocked(boolean available) {
        final AccessibilityUserState state = getCurrentUserStateLocked();
        mIsAccessibilityButtonShown = available;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection clientConnection = state.mBoundServices.get(i);
            if (clientConnection.mRequestAccessibilityButton) {
                clientConnection.notifyAccessibilityButtonAvailabilityChangedLocked(
                        clientConnection.isAccessibilityButtonAvailableLocked(state));
            }
        }
    }

    private boolean readInstalledAccessibilityServiceLocked(AccessibilityUserState userState) {
        mTempAccessibilityServiceInfoList.clear();

        int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        if (userState.getBindInstantServiceAllowedLocked()) {
            flags |= PackageManager.MATCH_INSTANT;
        }

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE), flags, mCurrentUserId);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;

            if (!mSecurityPolicy.canRegisterService(serviceInfo)) {
                continue;
            }

            AccessibilityServiceInfo accessibilityServiceInfo;
            try {
                accessibilityServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
                if (userState.mCrashedServices.contains(serviceInfo.getComponentName())) {
                    // Restore the crashed attribute.
                    accessibilityServiceInfo.crashed = true;
                }
                mTempAccessibilityServiceInfoList.add(accessibilityServiceInfo);
            } catch (XmlPullParserException | IOException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
            }
        }

        if (!mTempAccessibilityServiceInfoList.equals(userState.mInstalledServices)) {
            userState.mInstalledServices.clear();
            userState.mInstalledServices.addAll(mTempAccessibilityServiceInfoList);
            mTempAccessibilityServiceInfoList.clear();
            return true;
        }

        mTempAccessibilityServiceInfoList.clear();
        return false;
    }

    private boolean readInstalledAccessibilityShortcutLocked(AccessibilityUserState userState) {
        final List<AccessibilityShortcutInfo> shortcutInfos = AccessibilityManager
                .getInstance(mContext).getInstalledAccessibilityShortcutListAsUser(
                        mContext, mCurrentUserId);
        if (!shortcutInfos.equals(userState.mInstalledShortcuts)) {
            userState.mInstalledShortcuts.clear();
            userState.mInstalledShortcuts.addAll(shortcutInfos);
            return true;
        }
        return false;
    }

    private boolean readEnabledAccessibilityServicesLocked(AccessibilityUserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mEnabledServices)) {
            userState.mEnabledServices.clear();
            userState.mEnabledServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    private boolean readTouchExplorationGrantedAccessibilityServicesLocked(
            AccessibilityUserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mTouchExplorationGrantedServices)) {
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    /**
     * Performs {@link AccessibilityService}s delayed notification. The delay is configurable
     * and denotes the period after the last event before notifying the service.
     *
     * @param event The event.
     * @param isDefault True to notify default listeners, not default services.
     */
    private void notifyAccessibilityServicesDelayedLocked(AccessibilityEvent event,
            boolean isDefault) {
        try {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = 0, count = state.mBoundServices.size(); i < count; i++) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);

                if (service.mIsDefault == isDefault) {
                    service.notifyAccessibilityEvent(event);
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
        }
    }

    private void updateRelevantEventsLocked(AccessibilityUserState userState) {
        mMainHandler.post(() -> {
            broadcastToClients(userState, ignoreRemoteException(client -> {
                int relevantEventTypes;
                boolean changed = false;
                synchronized (mLock) {
                    relevantEventTypes = computeRelevantEventTypesLocked(userState, client);

                    if (client.mLastSentRelevantEventTypes != relevantEventTypes) {
                        client.mLastSentRelevantEventTypes = relevantEventTypes;
                        changed = true;
                    }
                }
                if (changed) {
                    client.mCallback.setRelevantEventTypes(relevantEventTypes);
                }
            }));
        });
    }

    private int computeRelevantEventTypesLocked(AccessibilityUserState userState, Client client) {
        int relevantEventTypes = 0;

        int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            relevantEventTypes |= isClientInPackageWhitelist(service.getServiceInfo(), client)
                    ? service.getRelevantEventTypes()
                    : 0;
        }

        relevantEventTypes |= isClientInPackageWhitelist(
                mUiAutomationManager.getServiceInfo(), client)
                ? mUiAutomationManager.getRelevantEventTypes()
                : 0;
        return relevantEventTypes;
    }

    private static boolean isClientInPackageWhitelist(
            @Nullable AccessibilityServiceInfo serviceInfo, Client client) {
        if (serviceInfo == null) return false;

        String[] clientPackages = client.mPackageNames;
        boolean result = ArrayUtils.isEmpty(serviceInfo.packageNames);
        if (!result && clientPackages != null) {
            for (String packageName : clientPackages) {
                if (ArrayUtils.contains(serviceInfo.packageNames, packageName)) {
                    result = true;
                    break;
                }
            }
        }
        if (!result) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Dropping events: "
                        + Arrays.toString(clientPackages) + " -> "
                        + serviceInfo.getComponentName().flattenToShortString()
                        + " due to not being in package whitelist "
                        + Arrays.toString(serviceInfo.packageNames));
            }
        }

        return result;
    }

    private void broadcastToClients(
            AccessibilityUserState userState, Consumer<Client> clientAction) {
        mGlobalClients.broadcastForEachCookie(clientAction);
        userState.mUserClients.broadcastForEachCookie(clientAction);
    }

    /**
     * Populates a set with the {@link ComponentName}s stored in a colon
     * separated value setting for a given user.
     *
     * @param settingName The setting to parse.
     * @param userId The user id.
     * @param outComponentNames The output component names.
     */
    private void readComponentNamesFromSettingLocked(String settingName, int userId,
            Set<ComponentName> outComponentNames) {
        readColonDelimitedSettingToSet(settingName, userId, outComponentNames,
                str -> ComponentName.unflattenFromString(str));
    }

    /**
     * Populates a set with the {@link ComponentName}s contained in a colon-delimited string.
     *
     * @param names The colon-delimited string to parse.
     * @param outComponentNames The set of component names to be populated based on
     *    the contents of the <code>names</code> string.
     * @param doMerge If true, the parsed component names will be merged into the output
     *    set, rather than replacing the set's existing contents entirely.
     */
    private void readComponentNamesFromStringLocked(String names,
            Set<ComponentName> outComponentNames,
            boolean doMerge) {
        readColonDelimitedStringToSet(names, outComponentNames, doMerge,
                str -> ComponentName.unflattenFromString(str));
    }

    @Override
    public void persistComponentNamesToSettingLocked(String settingName,
            Set<ComponentName> componentNames, int userId) {
        persistColonDelimitedSetToSettingLocked(settingName, userId, componentNames,
                componentName -> componentName.flattenToShortString());
    }

    private <T> void readColonDelimitedSettingToSet(String settingName, int userId, Set<T> outSet,
            Function<String, T> toItem) {
        final String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                settingName, userId);
        readColonDelimitedStringToSet(settingValue, outSet, false, toItem);
    }

    private <T> void readColonDelimitedStringToSet(String names, Set<T> outSet, boolean doMerge,
            Function<String, T> toItem) {
        if (!doMerge) {
            outSet.clear();
        }
        if (!TextUtils.isEmpty(names)) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(names);
            while (splitter.hasNext()) {
                final String str = splitter.next();
                if (TextUtils.isEmpty(str)) {
                    continue;
                }
                final T item = toItem.apply(str);
                if (item != null) {
                    outSet.add(item);
                }
            }
        }
    }

    private <T> void persistColonDelimitedSetToSettingLocked(String settingName, int userId,
            Set<T> set, Function<T, String> toString) {
        final StringBuilder builder = new StringBuilder();
        for (T item : set) {
            final String str = (item != null ? toString.apply(item) : null);
            if (TextUtils.isEmpty(str)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(str);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final String settingValue = builder.toString();
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    settingName, TextUtils.isEmpty(settingValue) ? null : settingValue, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void updateServicesLocked(AccessibilityUserState userState) {
        Map<ComponentName, AccessibilityServiceConnection> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isUnlockingOrUnlocked = LocalServices.getService(UserManagerInternal.class)
                    .isUserUnlockingOrUnlocked(userState.mUserId);

        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());

            AccessibilityServiceConnection service = componentNameToServiceMap.get(componentName);

            // Ignore non-encryption-aware services until user is unlocked
            if (!isUnlockingOrUnlocked && !installedService.isDirectBootAware()) {
                Slog.d(LOG_TAG, "Ignoring non-encryption-aware service " + componentName);
                continue;
            }

            // Skip the component since it may be in process or crashed.
            if (userState.getBindingServicesLocked().contains(componentName)
                    || userState.getCrashedServicesLocked().contains(componentName)) {
                continue;
            }
            if (userState.mEnabledServices.contains(componentName)
                    && !mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                if (service == null) {
                    service = new AccessibilityServiceConnection(userState, mContext, componentName,
                            installedService, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                            this, mWindowManagerService, mSystemActionPerformer,
                            mA11yWindowManager, mActivityTaskManagerService);
                } else if (userState.mBoundServices.contains(service)) {
                    continue;
                }
                service.bindLocked();
            } else {
                if (service != null) {
                    service.unbindLocked();
                }
            }
        }

        final int count = userState.mBoundServices.size();
        mTempIntArray.clear();
        for (int i = 0; i < count; i++) {
            final ResolveInfo resolveInfo =
                    userState.mBoundServices.get(i).mAccessibilityServiceInfo.getResolveInfo();
            if (resolveInfo != null) {
                mTempIntArray.add(resolveInfo.serviceInfo.applicationInfo.uid);
            }
        }
        // Calling out with lock held, but to a lower-level service
        final AudioManagerInternal audioManager =
                LocalServices.getService(AudioManagerInternal.class);
        if (audioManager != null) {
            audioManager.setAccessibilityServiceUids(mTempIntArray);
        }
        updateAccessibilityEnabledSettingLocked(userState);
    }

    private void scheduleUpdateClientsIfNeededLocked(AccessibilityUserState userState) {
        final int clientState = getClientStateLocked(userState);
        if (userState.getLastSentClientStateLocked() != clientState
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                        || userState.mUserClients.getRegisteredCallbackCount() > 0)) {
            userState.setLastSentClientStateLocked(clientState);
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::sendStateToAllClients,
                    this, clientState, userState.mUserId));
        }
    }

    private void sendStateToAllClients(int clientState, int userId) {
        sendStateToClients(clientState, mGlobalClients);
        sendStateToClients(clientState, userId);
    }

    private void sendStateToClients(int clientState, int userId) {
        sendStateToClients(clientState, getUserState(userId).mUserClients);
    }

    private void sendStateToClients(int clientState,
            RemoteCallbackList<IAccessibilityManagerClient> clients) {
        clients.broadcast(ignoreRemoteException(
                client -> client.setState(clientState)));
    }

    private void scheduleNotifyClientsOfServicesStateChangeLocked(
            AccessibilityUserState userState) {
        updateRecommendedUiTimeoutLocked(userState);
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::sendServicesStateChanged,
                this, userState.mUserClients, getRecommendedTimeoutMillisLocked(userState)));
    }

    private void sendServicesStateChanged(
            RemoteCallbackList<IAccessibilityManagerClient> userClients, long uiTimeout) {
        notifyClientsOfServicesStateChange(mGlobalClients, uiTimeout);
        notifyClientsOfServicesStateChange(userClients, uiTimeout);
    }

    private void notifyClientsOfServicesStateChange(
            RemoteCallbackList<IAccessibilityManagerClient> clients, long uiTimeout) {
        clients.broadcast(ignoreRemoteException(
                client -> client.notifyServicesStateChanged(uiTimeout)));
    }

    private void scheduleUpdateInputFilter(AccessibilityUserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateInputFilter, this, userState));
    }

    private void scheduleUpdateFingerprintGestureHandling(AccessibilityUserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateFingerprintGestureHandling,
                this, userState));
    }

    private void updateInputFilter(AccessibilityUserState userState) {
        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) return;

        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            int flags = 0;
            if (userState.isDisplayMagnificationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
            }
            if (userState.isShortcutMagnificationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TRIGGERED_SCREEN_MAGNIFIER;
            }
            if (userHasMagnificationServicesLocked(userState)) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER;
            }
            // Touch exploration without accessibility makes no sense.
            if (userState.isHandlingAccessibilityEventsLocked()
                    && userState.isTouchExplorationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
                if (userState.isServiceHandlesDoubleTapEnabledLocked()) {
                    flags |= AccessibilityInputFilter.FLAG_SERVICE_HANDLES_DOUBLE_TAP;
                }
                if (userState.isMultiFingerGesturesEnabledLocked()) {
                    flags |= AccessibilityInputFilter.FLAG_REQUEST_MULTI_FINGER_GESTURES;
                }
            }
            if (userState.isFilterKeyEventsEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS;
            }
            if (userState.isAutoclickEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_AUTOCLICK;
            }
            if (userState.isPerformGesturesEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_INJECT_MOTION_EVENTS;
            }
            if (flags != 0) {
                if (!mHasInputFilter) {
                    mHasInputFilter = true;
                    if (mInputFilter == null) {
                        mInputFilter = new AccessibilityInputFilter(mContext,
                                AccessibilityManagerService.this);
                    }
                    inputFilter = mInputFilter;
                    setInputFilter = true;
                }
                mInputFilter.setUserAndEnabledFeatures(userState.mUserId, flags);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.setUserAndEnabledFeatures(userState.mUserId, 0);
                    inputFilter = null;
                    setInputFilter = true;
                }
            }
        }
        if (setInputFilter) {
            mWindowManagerService.setInputFilter(inputFilter);
        }
    }

    private void showEnableTouchExplorationDialog(final AccessibilityServiceConnection service) {
        synchronized (mLock) {
            String label = service.getServiceInfo().getResolveInfo()
                    .loadLabel(mContext.getPackageManager()).toString();

            final AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.isTouchExplorationEnabledLocked()) {
                return;
            }
            if (mEnableTouchExplorationDialog != null
                    && mEnableTouchExplorationDialog.isShowing()) {
                return;
            }
            mEnableTouchExplorationDialog = new AlertDialog.Builder(mContext)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // The user allowed the service to toggle touch exploration.
                        userState.mTouchExplorationGrantedServices.add(service.mComponentName);
                        persistComponentNamesToSettingLocked(
                                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                userState.mTouchExplorationGrantedServices, userState.mUserId);
                        // Enable touch exploration.
                        userState.setTouchExplorationEnabledLocked(true);
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                    userState.mUserId);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        onUserStateChangedLocked(userState);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setTitle(R.string.enable_explore_by_touch_warning_title)
                .setMessage(mContext.getString(
                        R.string.enable_explore_by_touch_warning_message, label))
                .create();
             mEnableTouchExplorationDialog.getWindow().setType(
                     WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
             mEnableTouchExplorationDialog.getWindow().getAttributes().privateFlags
                     |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
             mEnableTouchExplorationDialog.setCanceledOnTouchOutside(true);
             mEnableTouchExplorationDialog.show();
        }
    }

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     */
    private void onUserStateChangedLocked(AccessibilityUserState userState) {
        // TODO: Remove this hack
        mInitialized = true;
        updateLegacyCapabilitiesLocked(userState);
        updateServicesLocked(userState);
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updatePerformGesturesLocked(userState);
        updateMagnificationLocked(userState);
        scheduleUpdateFingerprintGestureHandling(userState);
        scheduleUpdateInputFilter(userState);
        updateRelevantEventsLocked(userState);
        scheduleUpdateClientsIfNeededLocked(userState);
        updateAccessibilityShortcutKeyTargetsLocked(userState);
        updateAccessibilityButtonTargetsLocked(userState);
    }

    private void updateWindowsForAccessibilityCallbackLocked(AccessibilityUserState userState) {
        // We observe windows for accessibility only if there is at least
        // one bound service that can retrieve window content that specified
        // it is interested in accessing such windows. For services that are
        // binding we do an update pass after each bind event, so we run this
        // code and register the callback if needed.

        boolean observingWindows = mUiAutomationManager.canRetrieveInteractiveWindowsLocked();
        List<AccessibilityServiceConnection> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; !observingWindows && (i < boundServiceCount); i++) {
            AccessibilityServiceConnection boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                observingWindows = true;
            }
        }

        // Gets all valid displays and start tracking windows of each display if there is at least
        // one bound service that can retrieve window content.
        final ArrayList<Display> displays = getValidDisplayList();
        for (int i = 0; i < displays.size(); i++) {
            final Display display = displays.get(i);
            if (display != null) {
                if (observingWindows) {
                    mA11yWindowManager.startTrackingWindows(display.getDisplayId());
                } else {
                    mA11yWindowManager.stopTrackingWindows(display.getDisplayId());
                }
            }
        }
    }

    private void updateLegacyCapabilitiesLocked(AccessibilityUserState userState) {
        // Up to JB-MR1 we had a white list with services that can enable touch
        // exploration. When a service is first started we show a dialog to the
        // use to get a permission to white list the service.
        final int installedServiceCount = userState.mInstalledServices.size();
        for (int i = 0; i < installedServiceCount; i++) {
            AccessibilityServiceInfo serviceInfo = userState.mInstalledServices.get(i);
            ResolveInfo resolveInfo = serviceInfo.getResolveInfo();
            if ((serviceInfo.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) == 0
                    && resolveInfo.serviceInfo.applicationInfo.targetSdkVersion
                        <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                ComponentName componentName = new ComponentName(
                        resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
                if (userState.mTouchExplorationGrantedServices.contains(componentName)) {
                    serviceInfo.setCapabilities(serviceInfo.getCapabilities()
                            | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION);
                }
            }
        }
    }

    private void updatePerformGesturesLocked(AccessibilityUserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if ((service.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) {
                userState.setPerformGesturesEnabledLocked(true);
                return;
            }
        }
        userState.setPerformGesturesEnabledLocked(false);
    }

    private void updateFilterKeyEventsLocked(AccessibilityUserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestFilterKeyEvents
                    && (service.getCapabilities()
                            & AccessibilityServiceInfo
                            .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0) {
                userState.setFilterKeyEventsEnabledLocked(true);
                return;
            }
        }
        userState.setFilterKeyEventsEnabledLocked(false);
    }

    private boolean readConfigurationForUserStateLocked(AccessibilityUserState userState) {
        boolean somethingChanged = readInstalledAccessibilityServiceLocked(userState);
        somethingChanged |= readInstalledAccessibilityShortcutLocked(userState);
        somethingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somethingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somethingChanged |= readMagnificationEnabledSettingsLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);
        somethingChanged |= readAccessibilityShortcutKeySettingLocked(userState);
        somethingChanged |= readAccessibilityButtonSettingsLocked(userState);
        somethingChanged |= readUserRecommendedUiTimeoutSettingsLocked(userState);
        return somethingChanged;
    }

    private void updateAccessibilityEnabledSettingLocked(AccessibilityUserState userState) {
        final long identity = Binder.clearCallingIdentity();
        final boolean isA11yEnabled = mUiAutomationManager.isUiAutomationRunningLocked()
                || userState.isHandlingAccessibilityEventsLocked();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    (isA11yEnabled) ? 1 : 0,
                    userState.mUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean readTouchExplorationEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean touchExplorationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId) == 1;
        if (touchExplorationEnabled != userState.isTouchExplorationEnabledLocked()) {
            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            return true;
        }
        return false;
    }

    private boolean readMagnificationEnabledSettingsLocked(AccessibilityUserState userState) {
        final boolean displayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        if ((displayMagnificationEnabled != userState.isDisplayMagnificationEnabledLocked())) {
            userState.setDisplayMagnificationEnabledLocked(displayMagnificationEnabled);
            return true;
        }
        return false;
    }

    private boolean readAutoclickEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean autoclickEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                0, userState.mUserId) == 1;
        if (autoclickEnabled != userState.isAutoclickEnabledLocked()) {
            userState.setAutoclickEnabledLocked(autoclickEnabled);
            return true;
        }
        return false;
    }

    private boolean readHighTextContrastEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean highTextContrastEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, 0,
                userState.mUserId) == 1;
        if (highTextContrastEnabled != userState.isTextHighContrastEnabledLocked()) {
            userState.setTextHighContrastEnabledLocked(highTextContrastEnabled);
            return true;
        }
        return false;
    }

    private void updateTouchExplorationLocked(AccessibilityUserState userState) {
        boolean touchExplorationEnabled = mUiAutomationManager.isTouchExplorationEnabledLocked();
        boolean serviceHandlesDoubleTapEnabled = false;
        boolean requestMultiFingerGestures = false;
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsTouchExplorationLocked(service, userState)) {
                touchExplorationEnabled = true;
                serviceHandlesDoubleTapEnabled = service.isServiceHandlesDoubleTapEnabled();
                requestMultiFingerGestures = service.isMultiFingerGesturesEnabled();
                break;
            }
        }
        if (touchExplorationEnabled != userState.isTouchExplorationEnabledLocked()) {
            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.TOUCH_EXPLORATION_ENABLED, touchExplorationEnabled ? 1 : 0,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        userState.setServiceHandlesDoubleTapLocked(serviceHandlesDoubleTapEnabled);
        userState.setMultiFingerGesturesLocked(requestMultiFingerGestures);
    }

    private boolean readAccessibilityShortcutKeySettingLocked(AccessibilityUserState userState) {
        final String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, userState.mUserId);
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedStringToSet(settingValue, targetsFromSetting, false, str -> str);
        // Fall back to device's default a11y service, only when setting is never updated.
        if (settingValue == null) {
            final String defaultService = mContext.getString(
                    R.string.config_defaultAccessibilityService);
            if (!TextUtils.isEmpty(defaultService)) {
                targetsFromSetting.add(defaultService);
            }
        }

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
        if (targetsFromSetting.equals(currentTargets)) {
            return false;
        }
        currentTargets.clear();
        currentTargets.addAll(targetsFromSetting);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        return true;
    }

    private boolean readAccessibilityButtonSettingsLocked(AccessibilityUserState userState) {
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedSettingToSet(Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
                userState.mUserId, targetsFromSetting, str -> str);

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        if (targetsFromSetting.equals(currentTargets)) {
            return false;
        }
        currentTargets.clear();
        currentTargets.addAll(targetsFromSetting);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        return true;
    }

    private boolean readUserRecommendedUiTimeoutSettingsLocked(AccessibilityUserState userState) {
        final int nonInteractiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);
        final int interactiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);
        if (nonInteractiveUiTimeout != userState.getUserNonInteractiveUiTimeoutLocked()
                || interactiveUiTimeout != userState.getUserInteractiveUiTimeoutLocked()) {
            userState.setUserNonInteractiveUiTimeoutLocked(nonInteractiveUiTimeout);
            userState.setUserInteractiveUiTimeoutLocked(interactiveUiTimeout);
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            return true;
        }
        return false;
    }

    /**
     * Check if the targets that will be enabled by the accessibility shortcut key is installed.
     * If it isn't, remove it from the list and associated setting so a side loaded service can't
     * spoof the package name of the default service.
     */
    private void updateAccessibilityShortcutKeyTargetsLocked(AccessibilityUserState userState) {
        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
        final int lastSize = currentTargets.size();
        if (lastSize == 0) {
            return;
        }
        currentTargets.removeIf(
                name -> !userState.isShortcutTargetInstalledLocked(name));
        if (lastSize == currentTargets.size()) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                userState.mUserId, currentTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    private boolean canRequestAndRequestsTouchExplorationLocked(
            AccessibilityServiceConnection service, AccessibilityUserState userState) {
        // Service not ready or cannot request the feature - well nothing to do.
        if (!service.canReceiveEventsLocked() || !service.mRequestTouchExplorationMode) {
            return false;
        }
        if (service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Up to JB-MR1 we had a white list with services that can enable touch
            // exploration. When a service is first started we show a dialog to the
            // use to get a permission to white list the service.
            if (userState.mTouchExplorationGrantedServices.contains(service.mComponentName)) {
                return true;
            } else if (mEnableTouchExplorationDialog == null
                    || !mEnableTouchExplorationDialog.isShowing()) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::showEnableTouchExplorationDialog,
                        this, service));
            }
        } else {
            // Starting in JB-MR2 we request an accessibility service to declare
            // certain capabilities in its meta-data to allow it to enable the
            // corresponding features.
            if ((service.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0) {
                return true;
            }
        }
        return false;
    }

    private void updateMagnificationLocked(AccessibilityUserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }

        if (mMagnificationController != null) {
            mMagnificationController.setUserId(userState.mUserId);
        }

        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()
                && mMagnificationController != null) {
            mMagnificationController.unregisterAll();
            return;
        }

        // Get all valid displays and register them if global magnification is enabled.
        // We would skip overlay display because it uses overlay window to simulate secondary
        // displays in one display. It's not a real display and there's no input events for it.
        final ArrayList<Display> displays = getValidDisplayList();
        if (userState.isDisplayMagnificationEnabledLocked()
                || userState.isShortcutMagnificationEnabledLocked()) {
            for (int i = 0; i < displays.size(); i++) {
                final Display display = displays.get(i);
                getMagnificationController().register(display.getDisplayId());
            }
            return;
        }

        // Register if display has listening magnification services.
        for (int i = 0; i < displays.size(); i++) {
            final Display display = displays.get(i);
            final int displayId = display.getDisplayId();
            if (userHasListeningMagnificationServicesLocked(userState, displayId)) {
                getMagnificationController().register(displayId);
            } else if (mMagnificationController != null) {
                mMagnificationController.unregister(displayId);
            }
        }
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification.
     */
    private boolean userHasMagnificationServicesLocked(AccessibilityUserState userState) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification and are actively listening for magnification updates.
     */
    private boolean userHasListeningMagnificationServicesLocked(AccessibilityUserState userState,
            int displayId) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)
                    && service.isMagnificationCallbackEnabled(displayId)) {
                return true;
            }
        }
        return false;
    }

    private void updateFingerprintGestureHandling(AccessibilityUserState userState) {
        final List<AccessibilityServiceConnection> services;
        synchronized (mLock) {
            services = userState.mBoundServices;
            if ((mFingerprintGestureDispatcher == null)
                    &&  mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                // Only create the controller when a service wants to use the feature
                int numServices = services.size();
                for (int i = 0; i < numServices; i++) {
                    if (services.get(i).isCapturingFingerprintGestures()) {
                        final long identity = Binder.clearCallingIdentity();
                        IFingerprintService service = null;
                        try {
                            service = IFingerprintService.Stub.asInterface(
                                    ServiceManager.getService(Context.FINGERPRINT_SERVICE));
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        if (service != null) {
                            mFingerprintGestureDispatcher = new FingerprintGestureDispatcher(
                                    service, mContext.getResources(), mLock);
                            break;
                        }
                    }
                }
            }
        }
        if (mFingerprintGestureDispatcher != null) {
            mFingerprintGestureDispatcher.updateClientList(services);
        }
    }

    /**
     * 1) Update accessibility button availability to accessibility services.
     * 2) Check if the targets that will be enabled by the accessibility button is installed.
     *    If it isn't, remove it from the list and associated setting so a side loaded service can't
     *    spoof the package name of the default service.
     */
    private void updateAccessibilityButtonTargetsLocked(AccessibilityUserState userState) {
        // Update accessibility button availability.
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                service.notifyAccessibilityButtonAvailabilityChangedLocked(
                        service.isAccessibilityButtonAvailableLocked(userState));
            }
        }

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        final int lastSize = currentTargets.size();
        if (lastSize == 0) {
            return;
        }
        currentTargets.removeIf(
                name -> !userState.isShortcutTargetInstalledLocked(name));
        if (lastSize == currentTargets.size()) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
                userState.mUserId, currentTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    /**
     * 1) Check if the service assigned to accessibility button target sdk version > Q.
     *    If it isn't, remove it from the list and associated setting.
     *    (It happens when an accessibility service package is downgraded.)
     * 2) Check if an enabled service targeting sdk version > Q and requesting a11y button is
     *    assigned to a shortcut. If it isn't, assigns it to the accessibility button.
     *    (It happens when an enabled accessibility service package is upgraded.)
     *
     * @param packageName The package name to check, or {@code null} to check all services.
     */
    private void migrateAccessibilityButtonSettingsIfNecessaryLocked(
            AccessibilityUserState userState, @Nullable String packageName) {
        final Set<String> buttonTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        int lastSize = buttonTargets.size();
        buttonTargets.removeIf(name -> {
            if (packageName != null && name != null && !name.contains(packageName)) {
                return false;
            }
            final ComponentName componentName = ComponentName.unflattenFromString(name);
            if (componentName == null) {
                return false;
            }
            final AccessibilityServiceInfo serviceInfo =
                    userState.getInstalledServiceInfoLocked(componentName);
            if (serviceInfo == null) {
                return false;
            }
            if (serviceInfo.getResolveInfo().serviceInfo.applicationInfo
                    .targetSdkVersion > Build.VERSION_CODES.Q) {
                return false;
            }
            // A11y services targeting sdk version <= Q should not be in the list.
            return true;
        });
        boolean changed = (lastSize != buttonTargets.size());
        lastSize = buttonTargets.size();

        final Set<String> shortcutKeyTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
        userState.mEnabledServices.forEach(componentName -> {
            if (packageName != null && componentName != null
                    && !packageName.equals(componentName.getPackageName())) {
                return;
            }
            final AccessibilityServiceInfo serviceInfo =
                    userState.getInstalledServiceInfoLocked(componentName);
            if (serviceInfo == null) {
                return;
            }
            final boolean requestA11yButton = (serviceInfo.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if (!(serviceInfo.getResolveInfo().serviceInfo.applicationInfo
                    .targetSdkVersion > Build.VERSION_CODES.Q && requestA11yButton)) {
                return;
            }
            final String serviceName = serviceInfo.getComponentName().flattenToString();
            if (TextUtils.isEmpty(serviceName)) {
                return;
            }
            if (shortcutKeyTargets.contains(serviceName) || buttonTargets.contains(serviceName)) {
                return;
            }
            // For enabled a11y services targeting sdk version > Q and requesting a11y button should
            // be assigned to a shortcut.
            buttonTargets.add(serviceName);
        });
        changed |= (lastSize != buttonTargets.size());
        if (!changed) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
                userState.mUserId, buttonTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    private void updateRecommendedUiTimeoutLocked(AccessibilityUserState userState) {
        int newNonInteractiveUiTimeout = userState.getUserNonInteractiveUiTimeoutLocked();
        int newInteractiveUiTimeout = userState.getUserInteractiveUiTimeoutLocked();
        // read from a11y services if user does not specify value
        if (newNonInteractiveUiTimeout == 0 || newInteractiveUiTimeout == 0) {
            int serviceNonInteractiveUiTimeout = 0;
            int serviceInteractiveUiTimeout = 0;
            final List<AccessibilityServiceConnection> services = userState.mBoundServices;
            for (int i = 0; i < services.size(); i++) {
                int timeout = services.get(i).getServiceInfo().getInteractiveUiTimeoutMillis();
                if (serviceInteractiveUiTimeout < timeout) {
                    serviceInteractiveUiTimeout = timeout;
                }
                timeout = services.get(i).getServiceInfo().getNonInteractiveUiTimeoutMillis();
                if (serviceNonInteractiveUiTimeout < timeout) {
                    serviceNonInteractiveUiTimeout = timeout;
                }
            }
            if (newNonInteractiveUiTimeout == 0) {
                newNonInteractiveUiTimeout = serviceNonInteractiveUiTimeout;
            }
            if (newInteractiveUiTimeout == 0) {
                newInteractiveUiTimeout = serviceInteractiveUiTimeout;
            }
        }
        userState.setNonInteractiveUiTimeoutLocked(newNonInteractiveUiTimeout);
        userState.setInteractiveUiTimeoutLocked(newInteractiveUiTimeout);
    }

    @GuardedBy("mLock")
    @Override
    public MagnificationSpec getCompatibleMagnificationSpecLocked(int windowId) {
        IBinder windowToken = mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                mCurrentUserId, windowId);
        if (windowToken != null) {
            return mWindowManagerService.getCompatibleMagnificationSpecForWindow(
                    windowToken);
        }
        return null;
    }

    @Override
    public KeyEventDispatcher getKeyEventDispatcher() {
        if (mKeyEventDispatcher == null) {
            mKeyEventDispatcher = new KeyEventDispatcher(
                    mMainHandler, MainHandler.MSG_SEND_KEY_EVENT_TO_INPUT_FILTER, mLock,
                    mPowerManager);
        }
        return mKeyEventDispatcher;
    }

    @Override
    public PendingIntent getPendingIntentActivity(Context context, int requestCode, Intent intent,
            int flags) {
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    /**
     * AIDL-exposed method to be called when the accessibility shortcut key is enabled. Requires
     * permission to write secure settings, since someone with that permission can enable
     * accessibility services themselves.
     *
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    @Override
    public void performAccessibilityShortcut(String targetName) {
        if ((UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)
                && (mContext.checkCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                    "performAccessibilityShortcut requires the MANAGE_ACCESSIBILITY permission");
        }
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                Display.DEFAULT_DISPLAY, ACCESSIBILITY_SHORTCUT_KEY, targetName));
    }

    /**
     * Perform the accessibility shortcut action.
     *
     * @param shortcutType The shortcut type.
     * @param displayId The display id of the accessibility button.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    private void performAccessibilityShortcutInternal(int displayId,
            @ShortcutType int shortcutType, @Nullable String targetName) {
        final List<String> shortcutTargets = getAccessibilityShortcutTargetsInternal(shortcutType);
        if (shortcutTargets.isEmpty()) {
            Slog.d(LOG_TAG, "No target to perform shortcut, shortcutType=" + shortcutType);
            return;
        }
        // In case the caller specified a target name
        if (targetName != null) {
            if (!shortcutTargets.contains(targetName)) {
                Slog.d(LOG_TAG, "Perform shortcut failed, invalid target name:" + targetName);
                return;
            }
        } else {
            // In case there are many targets assigned to the given shortcut.
            if (shortcutTargets.size() > 1) {
                showAccessibilityTargetsSelection(displayId, shortcutType);
                return;
            }
            targetName = shortcutTargets.get(0);
        }
        // In case user assigned magnification to the given shortcut.
        if (targetName.equals(MAGNIFICATION_CONTROLLER_NAME)) {
            sendAccessibilityButtonToInputFilter(displayId);
            return;
        }
        final ComponentName targetComponentName = ComponentName.unflattenFromString(targetName);
        if (targetComponentName == null) {
            Slog.d(LOG_TAG, "Perform shortcut failed, invalid target name:" + targetName);
            return;
        }
        // In case user assigned an accessibility framework feature to the given shortcut.
        if (performAccessibilityFrameworkFeature(targetComponentName)) {
            return;
        }
        // In case user assigned an accessibility shortcut target to the given shortcut.
        if (performAccessibilityShortcutTargetActivity(displayId, targetComponentName)) {
            return;
        }
        // in case user assigned an accessibility service to the given shortcut.
        if (performAccessibilityShortcutTargetService(
                displayId, shortcutType, targetComponentName)) {
            return;
        }
    }

    private boolean performAccessibilityFrameworkFeature(ComponentName assignedTarget) {
        final Map<ComponentName, ToggleableFrameworkFeatureInfo> frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        if (!frameworkFeatureMap.containsKey(assignedTarget)) {
            return false;
        }
        // Toggle the requested framework feature
        final ToggleableFrameworkFeatureInfo featureInfo = frameworkFeatureMap.get(assignedTarget);
        final SettingStringHelper setting = new SettingStringHelper(mContext.getContentResolver(),
                featureInfo.getSettingKey(), mCurrentUserId);
        // Assuming that the default state will be to have the feature off
        if (!TextUtils.equals(featureInfo.getSettingOnValue(), setting.read())) {
            setting.write(featureInfo.getSettingOnValue());
        } else {
            setting.write(featureInfo.getSettingOffValue());
        }
        return true;
    }

    private boolean performAccessibilityShortcutTargetActivity(int displayId,
            ComponentName assignedTarget) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = 0; i < userState.mInstalledShortcuts.size(); i++) {
                final AccessibilityShortcutInfo shortcutInfo = userState.mInstalledShortcuts.get(i);
                if (!shortcutInfo.getComponentName().equals(assignedTarget)) {
                    continue;
                }
                launchShortcutTargetActivity(displayId, assignedTarget);
                return true;
            }
        }
        return false;
    }

    /**
     * Perform accessibility service shortcut action.
     *
     * 1) For {@link AccessibilityManager#ACCESSIBILITY_BUTTON} type and services targeting sdk
     *    version <= Q: callbacks to accessibility service if service is bounded and requests
     *    accessibility button.
     * 2) For {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY} type and service targeting sdk
     *    version <= Q: turns on / off the accessibility service.
     * 3) For {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY} type and service targeting sdk
     *    version > Q and request accessibility button: turn on the accessibility service if it's
     *    not in the enabled state.
     *    (It'll happen when a service is disabled and assigned to shortcut then upgraded.)
     * 4) For services targeting sdk version > Q:
     *    a) Turns on / off the accessibility service, if service does not request accessibility
     *       button.
     *    b) Callbacks to accessibility service if service is bounded and requests accessibility
     *       button.
     */
    private boolean performAccessibilityShortcutTargetService(int displayId,
            @ShortcutType int shortcutType, ComponentName assignedTarget) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            final AccessibilityServiceInfo installedServiceInfo =
                    userState.getInstalledServiceInfoLocked(assignedTarget);
            if (installedServiceInfo == null) {
                Slog.d(LOG_TAG, "Perform shortcut failed, invalid component name:"
                        + assignedTarget);
                return false;
            }

            final AccessibilityServiceConnection serviceConnection =
                    userState.getServiceConnectionLocked(assignedTarget);
            final int targetSdk = installedServiceInfo.getResolveInfo()
                    .serviceInfo.applicationInfo.targetSdkVersion;
            final boolean requestA11yButton = (installedServiceInfo.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            // Turns on / off the accessibility service
            if ((targetSdk <= Build.VERSION_CODES.Q && shortcutType == ACCESSIBILITY_SHORTCUT_KEY)
                    || (targetSdk > Build.VERSION_CODES.Q && !requestA11yButton)) {
                if (serviceConnection == null) {
                    enableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);
                } else {
                    disableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);
                }
                return true;
            }
            if (shortcutType == ACCESSIBILITY_SHORTCUT_KEY && targetSdk > Build.VERSION_CODES.Q
                    && requestA11yButton) {
                if (!userState.getEnabledServicesLocked().contains(assignedTarget)) {
                    enableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);
                    return true;
                }
            }
            // Callbacks to a11y service if it's bounded and requests a11y button.
            if (serviceConnection == null
                    || !userState.mBoundServices.contains(serviceConnection)
                    || !serviceConnection.mRequestAccessibilityButton) {
                Slog.d(LOG_TAG, "Perform shortcut failed, service is not ready:"
                        + assignedTarget);
                return false;
            }
            serviceConnection.notifyAccessibilityButtonClickedLocked(displayId);
            return true;
        }
    }

    @Override
    public List<String> getAccessibilityShortcutTargets(@ShortcutType int shortcutType) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "getAccessibilityShortcutService requires the MANAGE_ACCESSIBILITY permission");
        }
        return getAccessibilityShortcutTargetsInternal(shortcutType);
    }

    private List<String> getAccessibilityShortcutTargetsInternal(@ShortcutType int shortcutType) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            final ArrayList<String> shortcutTargets = new ArrayList<>(
                    userState.getShortcutTargetsLocked(shortcutType));
            if (shortcutType != ACCESSIBILITY_BUTTON) {
                return shortcutTargets;
            }
            // Adds legacy a11y services requesting a11y button into the list.
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (!service.mRequestAccessibilityButton
                        || service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo
                        .targetSdkVersion > Build.VERSION_CODES.Q) {
                    continue;
                }
                final String serviceName = service.getComponentName().flattenToString();
                if (!TextUtils.isEmpty(serviceName)) {
                    shortcutTargets.add(serviceName);
                }
            }
            return shortcutTargets;
        }
    }

    /**
     * Enables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void enableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId, mTempComponentNameSet);
        mTempComponentNameSet.add(componentName);
        persistComponentNamesToSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mTempComponentNameSet, userId);

        AccessibilityUserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.add(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * Disables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void disableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId, mTempComponentNameSet);
        mTempComponentNameSet.remove(componentName);
        persistComponentNamesToSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mTempComponentNameSet, userId);

        AccessibilityUserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.remove(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public void sendAccessibilityEventForCurrentUserLocked(AccessibilityEvent event) {
        sendAccessibilityEventLocked(event, mCurrentUserId);
    }

    private void sendAccessibilityEventLocked(AccessibilityEvent event, int userId) {
        // Resync to avoid calling out with the lock held
        event.setEventTime(SystemClock.uptimeMillis());
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::sendAccessibilityEvent,
                this, event, userId));
    }

    /**
     * AIDL-exposed method. System only.
     * Inform accessibility that a fingerprint gesture was performed
     *
     * @param gestureKeyCode The key code corresponding to the fingerprint gesture.
     * @return {@code true} if accessibility consumes the fingerprint gesture, {@code false} if it
     * doesn't.
     */
    @Override
    public boolean sendFingerprintGesture(int gestureKeyCode) {
        synchronized(mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
                throw new SecurityException("Only SYSTEM can call sendFingerprintGesture");
            }
        }
        if (mFingerprintGestureDispatcher == null) {
            return false;
        }
        return mFingerprintGestureDispatcher.onFingerprintGesture(gestureKeyCode);
    }

    /**
     * AIDL-exposed method. System only.
     * Gets accessibility window id from window token.
     *
     * @param windowToken Window token to get accessibility window id.
     * @return Accessibility window id for the window token. Returns -1 if no such token is
     *   registered.
     */
    @Override
    public int getAccessibilityWindowId(@Nullable IBinder windowToken) {
        synchronized (mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
                throw new SecurityException("Only SYSTEM can call getAccessibilityWindowId");
            }

            return mA11yWindowManager.findWindowIdLocked(mCurrentUserId, windowToken);
        }
    }

    /**
     * Get the recommended timeout of interactive controls and non-interactive controls.
     *
     * @return A long for pair of {@code int}s. First integer for interactive one, and second
     * integer for non-interactive one.
     */
    @Override
    public long getRecommendedTimeoutMillis() {
        synchronized(mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            return getRecommendedTimeoutMillisLocked(userState);
        }
    }

    private long getRecommendedTimeoutMillisLocked(AccessibilityUserState userState) {
        return IntPair.of(userState.getInteractiveUiTimeoutLocked(),
                userState.getNonInteractiveUiTimeoutLocked());
    }

    @Override
    public void setWindowMagnificationConnection(
            IWindowMagnificationConnection connection) throws RemoteException {
        mSecurityPolicy.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR_SERVICE);

        getWindowMagnificationMgr().setConnection(connection);
    }

    WindowMagnificationManager getWindowMagnificationMgr() {
        synchronized (mLock) {
            if (mWindowMagnificationMgr == null) {
                mWindowMagnificationMgr = new WindowMagnificationManager();
            }
            return mWindowMagnificationMgr;
        }
    }

    @Override
    public void associateEmbeddedHierarchy(@NonNull IBinder host, @NonNull IBinder embedded) {
        synchronized (mLock) {
            mA11yWindowManager.associateEmbeddedHierarchyLocked(host, embedded);
        }
    }

    @Override
    public void disassociateEmbeddedHierarchy(@NonNull IBinder token) {
        synchronized (mLock) {
            mA11yWindowManager.disassociateEmbeddedHierarchyLocked(token);
        }
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.println("ACCESSIBILITY MANAGER (dumpsys accessibility)");
            pw.println();
            pw.append("currentUserId=").append(String.valueOf(mCurrentUserId));
            pw.println();
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                mUserStates.valueAt(i).dump(fd, pw, args);
            }
            if (mUiAutomationManager.isUiAutomationRunningLocked()) {
                mUiAutomationManager.dumpUiAutomationService(fd, pw, args);
                pw.println();
            }
            mA11yWindowManager.dump(fd, pw, args);
        }
    }

    //TODO remove after refactoring KeyEventDispatcherTest
    final class MainHandler extends Handler {
        public static final int MSG_SEND_KEY_EVENT_TO_INPUT_FILTER = 8;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SEND_KEY_EVENT_TO_INPUT_FILTER) {
                KeyEvent event = (KeyEvent) msg.obj;
                final int policyFlags = msg.arg1;
                synchronized (mLock) {
                    if (mHasInputFilter && mInputFilter != null) {
                        mInputFilter.sendInputEvent(event, policyFlags);
                    }
                }
                event.recycle();
            }
        }
    }

    @Override
    public MagnificationController getMagnificationController() {
        synchronized (mLock) {
            if (mMagnificationController == null) {
                mMagnificationController = new MagnificationController(mContext, this, mLock);
                mMagnificationController.setUserId(mCurrentUserId);
            }
            return mMagnificationController;
        }
    }

    @Override
    public void onClientChangeLocked(boolean serviceInfoChanged) {
        AccessibilityUserState userState = getUserStateLocked(mCurrentUserId);
        onUserStateChangedLocked(userState);
        if (serviceInfoChanged) {
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        new AccessibilityShellCommand(this).exec(this, in, out, err, args,
                callback, resultReceiver);
    }

    private final class InteractionBridge {
        private final ComponentName COMPONENT_NAME =
                new ComponentName("com.android.server.accessibility", "InteractionBridge");

        private final Display mDefaultDisplay;
        private final int mConnectionId;
        private final AccessibilityInteractionClient mClient;

        public InteractionBridge() {
            final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            final AccessibilityUserState userState;
            synchronized (mLock) {
                userState = getCurrentUserStateLocked();
            }
            AccessibilityServiceConnection service = new AccessibilityServiceConnection(
                    userState, mContext,
                    COMPONENT_NAME, info, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                    AccessibilityManagerService.this, mWindowManagerService,
                    mSystemActionPerformer, mA11yWindowManager, mActivityTaskManagerService) {
                @Override
                public boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
                    return true;
                }
            };

            mConnectionId = service.mId;

            mClient = AccessibilityInteractionClient.getInstance();
            mClient.addConnection(mConnectionId, service);

            //TODO: (multi-display) We need to support multiple displays.
            DisplayManager displayManager = (DisplayManager)
                    mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }

        /**
         * Perform an accessibility action on the view that currently has accessibility focus.
         * Has no effect if no item has accessibility focus, if the item with accessibility
         * focus does not expose the specified action, or if the action fails.
         *
         * @param action The action to perform.
         *
         * @return {@code true} if the action was performed. {@code false} if it was not.
         */
        public boolean performActionOnAccessibilityFocusedItemNotLocked(
                AccessibilityNodeInfo.AccessibilityAction action) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if ((focus == null) || !focus.getActionList().contains(action)) {
                return false;
            }
            return focus.performAction(action.getId());
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked() {
            final int focusedWindowId;
            synchronized (mLock) {
                focusedWindowId = mA11yWindowManager.getFocusedWindowId(
                        AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                if (focusedWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                    return null;
                }
            }
            return getAccessibilityFocusNotLocked(focusedWindowId);
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked(int windowId) {
            return mClient.findFocus(mConnectionId,
                    windowId, AccessibilityNodeInfo.ROOT_NODE_ID,
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
    }

    /**
     * Gets all currently valid logical displays.
     *
     * @return An array list containing all valid logical displays.
     */
    public ArrayList<Display> getValidDisplayList() {
        return mA11yDisplayListener.getValidDisplayList();
    }

    /**
     * A Utility class to handle display state.
     */
    public class AccessibilityDisplayListener implements DisplayManager.DisplayListener {
        private final DisplayManager mDisplayManager;
        private final ArrayList<Display> mDisplaysList = new ArrayList<>();

        AccessibilityDisplayListener(Context context, MainHandler handler) {
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayManager.registerDisplayListener(this, handler);
            initializeDisplayList();
        }

        ArrayList<Display> getValidDisplayList() {
            synchronized (mLock) {
                return mDisplaysList;
            }
        }

        private void initializeDisplayList() {
            final Display[] displays = mDisplayManager.getDisplays();
            synchronized (mLock) {
                mDisplaysList.clear();
                for (int i = 0; i < displays.length; i++) {
                    // Exclude overlay virtual displays. The display list is for A11yInputFilter
                    // to create event handler per display. The events should be handled by the
                    // display which is overlaid by it.
                    final Display display = displays[i];
                    if (isValidDisplay(display)) {
                        mDisplaysList.add(display);
                    }
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            final Display display = mDisplayManager.getDisplay(displayId);
            if (!isValidDisplay(display)) {
                return;
            }

            synchronized (mLock) {
                mDisplaysList.add(display);
                if (mInputFilter != null) {
                    mInputFilter.onDisplayChanged();
                }
                AccessibilityUserState userState = getCurrentUserStateLocked();
                if (displayId != Display.DEFAULT_DISPLAY) {
                    final List<AccessibilityServiceConnection> services = userState.mBoundServices;
                    for (int i = 0; i < services.size(); i++) {
                        AccessibilityServiceConnection boundClient = services.get(i);
                        boundClient.onDisplayAdded(displayId);
                    }
                }
                updateMagnificationLocked(userState);
                updateWindowsForAccessibilityCallbackLocked(userState);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                if (!removeDisplayFromList(displayId)) {
                    return;
                }
                if (mInputFilter != null) {
                    mInputFilter.onDisplayChanged();
                }
                AccessibilityUserState userState = getCurrentUserStateLocked();
                if (displayId != Display.DEFAULT_DISPLAY) {
                    final List<AccessibilityServiceConnection> services = userState.mBoundServices;
                    for (int i = 0; i < services.size(); i++) {
                        AccessibilityServiceConnection boundClient = services.get(i);
                        boundClient.onDisplayRemoved(displayId);
                    }
                }
            }
            if (mMagnificationController != null) {
                mMagnificationController.onDisplayRemoved(displayId);
            }
            mA11yWindowManager.stopTrackingWindows(displayId);
        }

        @GuardedBy("mLock")
        private boolean removeDisplayFromList(int displayId) {
            for (int i = 0; i < mDisplaysList.size(); i++) {
                if (mDisplaysList.get(i).getDisplayId() == displayId) {
                    mDisplaysList.remove(i);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onDisplayChanged(int displayId) {
            /* do nothing */
        }

        private boolean isValidDisplay(@Nullable Display display) {
            if (display == null || display.getType() == Display.TYPE_OVERLAY) {
                return false;
            }
            // Private virtual displays are created by the ap and is not allowed to access by other
            // aps. We assume we could ignore them.
            if (display.getType() == Display.TYPE_VIRTUAL
                    && (display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                return false;
            }
            return true;
        }
    }

    /** Represents an {@link AccessibilityManager} */
    class Client {
        final IAccessibilityManagerClient mCallback;
        final String[] mPackageNames;
        int mLastSentRelevantEventTypes;

        private Client(IAccessibilityManagerClient callback, int clientUid,
                AccessibilityUserState userState) {
            mCallback = callback;
            mPackageNames = mPackageManager.getPackagesForUid(clientUid);
            synchronized (mLock) {
                mLastSentRelevantEventTypes = computeRelevantEventTypesLocked(userState, this);
            }
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mDisplayMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

        private final Uri mAutoclickEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED);

        private final Uri mEnabledAccessibilityServicesUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        private final Uri mTouchExplorationGrantedAccessibilityServicesUri = Settings.Secure
                .getUriFor(Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES);

        private final Uri mHighTextContrastUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED);

        private final Uri mAccessibilitySoftKeyboardModeUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);

        private final Uri mShowImeWithHardKeyboardUri = Settings.Secure.getUriFor(
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);

        private final Uri mAccessibilityShortcutServiceIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        private final Uri mAccessibilityButtonComponentIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT);

        private final Uri mUserNonInteractiveUiTimeoutUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS);

        private final Uri mUserInteractiveUiTimeoutUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS);

        public AccessibilityContentObserver(Handler handler) {
            super(handler);
        }

        public void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mTouchExplorationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mDisplayMagnificationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mAutoclickEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mEnabledAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mTouchExplorationGrantedAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mHighTextContrastUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilitySoftKeyboardModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mShowImeWithHardKeyboardUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityShortcutServiceIdUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityButtonComponentIdUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserNonInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // Profiles share the accessibility state of the parent. Therefore,
                // we are checking for changes only the parent settings.
                AccessibilityUserState userState = getCurrentUserStateLocked();

                if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayMagnificationEnabledUri.equals(uri)) {
                    if (readMagnificationEnabledSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAutoclickEnabledUri.equals(uri)) {
                    if (readAutoclickEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mEnabledAccessibilityServicesUri.equals(uri)) {
                    if (readEnabledAccessibilityServicesLocked(userState)) {
                        userState.updateCrashedServicesIfNeededLocked();
                        onUserStateChangedLocked(userState);
                    }
                } else if (mTouchExplorationGrantedAccessibilityServicesUri.equals(uri)) {
                    if (readTouchExplorationGrantedAccessibilityServicesLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mHighTextContrastUri.equals(uri)) {
                    if (readHighTextContrastEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilitySoftKeyboardModeUri.equals(uri)
                        || mShowImeWithHardKeyboardUri.equals(uri)) {
                    userState.reconcileSoftKeyboardModeWithSettingsLocked();
                } else if (mAccessibilityShortcutServiceIdUri.equals(uri)) {
                    if (readAccessibilityShortcutKeySettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonComponentIdUri.equals(uri)) {
                    if (readAccessibilityButtonSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mUserNonInteractiveUiTimeoutUri.equals(uri)
                        || mUserInteractiveUiTimeoutUri.equals(uri)) {
                    readUserRecommendedUiTimeoutSettingsLocked(userState);
                }
            }
        }
    }

    @Override
    public void setGestureDetectionPassthroughRegion(int displayId, Region region) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::setGestureDetectionPassthroughRegionInternal,
                        this,
                        displayId,
                        region));
    }

    @Override
    public void setTouchExplorationPassthroughRegion(int displayId, Region region) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::setTouchExplorationPassthroughRegionInternal,
                        this,
                        displayId,
                        region));
    }

    private void setTouchExplorationPassthroughRegionInternal(int displayId, Region region) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.setTouchExplorationPassthroughRegion(displayId, region);
            }
        }
    }

    private void setGestureDetectionPassthroughRegionInternal(int displayId, Region region) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.setGestureDetectionPassthroughRegion(displayId, region);
            }
        }
    }
}
