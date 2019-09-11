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

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_MASK;

import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.accessibilityservice.AccessibilityGestureInfo;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManagerInternal;
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
import android.graphics.Point;
import android.graphics.Rect;
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
import android.provider.SettingsStringUtil;
import android.provider.SettingsStringUtil.ComponentNameSet;
import android.provider.SettingsStringUtil.SettingStringHelper;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
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
import com.android.server.wm.WindowManagerInternal;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub
        implements AbstractAccessibilityServiceConnection.SystemSupport,
        AccessibilityWindowManager.AccessibilityEventSender,
        AccessibilitySecurityPolicy.AccessibilityUserManager {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS = 1000;

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

    private final Rect mTempRect = new Rect();

    private final Rect mTempRect1 = new Rect();

    private final Point mTempPoint = new Point();

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final WindowManagerInternal mWindowManagerService;

    private final AccessibilitySecurityPolicy mSecurityPolicy;

    private final AccessibilityWindowManager mA11yWindowManager;

    private final AccessibilityDisplayListener mA11yDisplayListener;

    private final MainHandler mMainHandler;

    private final GlobalActionPerformer mGlobalActionPerformer;

    private MagnificationController mMagnificationController;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

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

    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final UiAutomationManager mUiAutomationManager = new UiAutomationManager(mLock);

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    //TODO: Remove this hack
    private boolean mInitialized;

    private boolean mIsAccessibilityButtonShown;

    private UserState getCurrentUserStateLocked() {
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

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mSecurityPolicy = new AccessibilitySecurityPolicy(mContext, this);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mGlobalActionPerformer = new GlobalActionPerformer(mContext, mWindowManagerService);
        mA11yWindowManager = new AccessibilityWindowManager(mLock, mMainHandler,
                mWindowManagerService, this, mSecurityPolicy, this);
        mA11yDisplayListener = new AccessibilityDisplayListener(mContext, mMainHandler);
        mSecurityPolicy.setAccessibilityWindowManager(mA11yWindowManager);

        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                context.getContentResolver());
    }

    @Override
    public int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @Override
    public boolean isAccessibilityButtonShown() {
        return mIsAccessibilityButtonShown;
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

    private UserState getUserState(int userId) {
        synchronized (mLock) {
            return getUserStateLocked(userId);
        }
    }

    private UserState getUserStateLocked(int userId) {
        UserState state = mUserStates.get(userId);
        if (state == null) {
            state = new UserState(userId);
            mUserStates.put(userId, state);
        }
        return state;
    }

    boolean getBindInstantServiceAllowed(int userId) {
        final UserState userState = getUserState(userId);
        if (userState == null) return false;
        return userState.getBindInstantServiceAllowed();
    }

    void setBindInstantServiceAllowed(int userId, boolean allowed) {
        UserState userState;
        synchronized (mLock) {
            userState = getUserState(userId);
            if (userState == null) {
                if (!allowed) {
                    return;
                }
                userState = new UserState(userId);
                mUserStates.put(userId, userState);
            }
        }
        userState.setBindInstantServiceAllowed(allowed);
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
                    UserState userState = getCurrentUserStateLocked();
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
                    UserState userState = getUserStateLocked(userId);
                    boolean reboundAService = userState.mBindingServices.removeIf(
                            component -> component != null
                                    && component.getPackageName().equals(packageName));
                    if (reboundAService) {
                        onUserStateChangedLocked(userState);
                    }
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
                    UserState userState = getUserStateLocked(userId);
                    Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        if (compPkg.equals(packageName)) {
                            it.remove();
                            userState.mBindingServices.remove(comp);
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
                    UserState userState = getUserStateLocked(userId);
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
                                userState.mBindingServices.remove(comp);
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
                        UserState userState = getCurrentUserStateLocked();
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
            UserState userState = getUserStateLocked(resolvedUserId);
            Client client = new Client(callback, Binder.getCallingUid(), userState);
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                mGlobalClients.register(callback, client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
                return IntPair.of(
                        userState.getClientState(),
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
                        (resolvedUserId == mCurrentUserId) ? userState.getClientState() : 0,
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
            final UserState userState = getUserStateLocked(resolvedUserId);
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
    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection, String packageName,
            int userId) throws RemoteException {
        return mA11yWindowManager.addAccessibilityInteractionConnection(
                windowToken, connection, packageName, userId);
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
                    mSecurityPolicy, this, mWindowManagerService, mGlobalActionPerformer,
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
            UserState userState = getCurrentUserStateLocked();

            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            userState.mIsDisplayMagnificationEnabled = false;
            userState.mIsNavBarMagnificationEnabled = false;
            userState.mIsAutoclickEnabled = false;
            userState.mEnabledServices.clear();
            userState.mEnabledServices.add(service);
            userState.mBindingServices.clear();
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
     */
    @Override
    public void notifyAccessibilityButtonClicked(int displayId) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        synchronized (mLock) {
            notifyAccessibilityButtonClickedLocked(displayId);
        }
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        synchronized (mLock) {
            notifyAccessibilityButtonVisibilityChangedLocked(shown);
        }
    }


    public boolean onGesture(AccessibilityGestureInfo gestureInfo) {
        synchronized (mLock) {
            boolean handled = notifyGestureLocked(gestureInfo, false);
            if (!handled) {
                handled = notifyGestureLocked(gestureInfo, true);
            }
            return handled;
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
            UserState oldUserState = getCurrentUserStateLocked();
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

            UserState userState = getCurrentUserStateLocked();

            readConfigurationForUserStateLocked(userState);
            // Even if reading did not yield change, we have to update
            // the state since the context in which the current user
            // state was used has changed since it was inactive.
            onUserStateChangedLocked(userState);

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
            UserState userState = getCurrentUserStateLocked();
            if (userState.isHandlingAccessibilityEvents()) {
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
                UserState userState = getUserStateLocked(mCurrentUserId);
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

        UserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        userState.mEnabledServices.clear();
        userState.mEnabledServices.addAll(mTempComponentNameSet);
        persistComponentNamesToSettingLocked(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mEnabledServices,
                UserHandle.USER_SYSTEM);
        onUserStateChangedLocked(userState);
    }

    private InteractionBridge getInteractionBridge() {
        synchronized (mLock) {
            if (mInteractionBridge == null) {
                mInteractionBridge = new InteractionBridge();
            }
            return mInteractionBridge;
        }
    }

    private boolean notifyGestureLocked(AccessibilityGestureInfo gestureInfo, boolean isDefault) {
        // TODO: Now we are giving the gestures to the last enabled
        //       service that can handle them which is the last one
        //       in our list since we write the last enabled as the
        //       last record in the enabled services setting. Ideally,
        //       the user should make the call which service handles
        //       gestures. However, only one service should handle
        //       gestures to avoid user frustration when different
        //       behavior is observed from different combinations of
        //       enabled accessibility services.
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureInfo);
                return true;
            }
        }
        return false;
    }

    private void notifyClearAccessibilityCacheLocked() {
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
        }
    }

    private void notifyMagnificationChangedLocked(int displayId, @NonNull Region region,
            float scale, float centerX, float centerY) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyMagnificationChangedLocked(displayId, region, scale, centerX, centerY);
        }
    }

    private void notifySoftKeyboardShowModeChangedLocked(int showMode) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifySoftKeyboardShowModeChangedLocked(showMode);
        }
    }

    private void notifyAccessibilityButtonClickedLocked(int displayId) {
        final UserState state = getCurrentUserStateLocked();

        int potentialTargets = state.mIsNavBarMagnificationEnabled ? 1 : 0;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                potentialTargets++;
            }
        }

        if (potentialTargets == 0) {
            return;
        }
        if (potentialTargets == 1) {
            if (state.mIsNavBarMagnificationEnabled) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::sendAccessibilityButtonToInputFilter, this,
                        displayId));
                return;
            } else {
                for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                    final AccessibilityServiceConnection service = state.mBoundServices.get(i);
                    if (service.mRequestAccessibilityButton) {
                        service.notifyAccessibilityButtonClickedLocked(displayId);
                        return;
                    }
                }
            }
        } else {
            if (state.mServiceAssignedToAccessibilityButton == null
                    && !state.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::showAccessibilityButtonTargetSelection, this,
                        displayId));
            } else if (state.mIsNavBarMagnificationEnabled
                    && state.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::sendAccessibilityButtonToInputFilter, this,
                        displayId));
                return;
            } else {
                for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                    final AccessibilityServiceConnection service = state.mBoundServices.get(i);
                    if (service.mRequestAccessibilityButton && (service.mComponentName.equals(
                            state.mServiceAssignedToAccessibilityButton))) {
                        service.notifyAccessibilityButtonClickedLocked(displayId);
                        return;
                    }
                }
            }
            // The user may have turned off the assigned service or feature
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::showAccessibilityButtonTargetSelection, this,
                    displayId));
        }
    }

    private void sendAccessibilityButtonToInputFilter(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyAccessibilityButtonClicked(displayId);
            }
        }
    }

    private void showAccessibilityButtonTargetSelection(int displayId) {
        Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
    }

    private void notifyAccessibilityButtonVisibilityChangedLocked(boolean available) {
        final UserState state = getCurrentUserStateLocked();
        mIsAccessibilityButtonShown = available;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection clientConnection = state.mBoundServices.get(i);
            if (clientConnection.mRequestAccessibilityButton) {
                clientConnection.notifyAccessibilityButtonAvailabilityChangedLocked(
                        clientConnection.isAccessibilityButtonAvailableLocked(state));
            }
        }
    }

    private boolean readInstalledAccessibilityServiceLocked(UserState userState) {
        mTempAccessibilityServiceInfoList.clear();

        int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        if (userState.getBindInstantServiceAllowed()) {
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

    private boolean readEnabledAccessibilityServicesLocked(UserState userState) {
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
            UserState userState) {
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
            UserState state = getCurrentUserStateLocked();
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

    private void updateRelevantEventsLocked(UserState userState) {
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

    private int computeRelevantEventTypesLocked(UserState userState, Client client) {
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
            UserState userState, Consumer<Client> clientAction) {
        mGlobalClients.broadcastForEachCookie(clientAction);
        userState.mUserClients.broadcastForEachCookie(clientAction);
    }

    private void unbindAllServicesLocked(UserState userState) {
        List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int count = services.size(); count > 0; count--) {
            // When the service is unbound, it disappears from the list, so there's no need to
            // keep track of the index
            services.get(0).unbindLocked();
        }
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
        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                settingName, userId);
        readComponentNamesFromStringLocked(settingValue, outComponentNames, false);
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
        if (!doMerge) {
            outComponentNames.clear();
        }
        if (names != null) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(names);
            while (splitter.hasNext()) {
                String str = splitter.next();
                if (str == null || str.length() <= 0) {
                    continue;
                }
                ComponentName enabledService = ComponentName.unflattenFromString(str);
                if (enabledService != null) {
                    outComponentNames.add(enabledService);
                }
            }
        }
    }

    @Override
    public void persistComponentNamesToSettingLocked(String settingName,
            Set<ComponentName> componentNames, int userId) {
        StringBuilder builder = new StringBuilder();
        for (ComponentName componentName : componentNames) {
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(componentName.flattenToShortString());
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

    private void updateServicesLocked(UserState userState) {
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

            // Wait for the binding if it is in process.
            if (userState.mBindingServices.contains(componentName)) {
                continue;
            }
            if (userState.mEnabledServices.contains(componentName)
                    && !mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                if (service == null) {
                    service = new AccessibilityServiceConnection(userState, mContext, componentName,
                            installedService, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                            this, mWindowManagerService, mGlobalActionPerformer,
                            mA11yWindowManager);
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
        updateAccessibilityEnabledSetting(userState);
    }

    private void scheduleUpdateClientsIfNeededLocked(UserState userState) {
        final int clientState = userState.getClientState();
        if (userState.mLastSentClientState != clientState
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                        || userState.mUserClients.getRegisteredCallbackCount() > 0)) {
            userState.mLastSentClientState = clientState;
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

    private void scheduleNotifyClientsOfServicesStateChangeLocked(UserState userState) {
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

    private void scheduleUpdateInputFilter(UserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateInputFilter, this, userState));
    }

    private void scheduleUpdateFingerprintGestureHandling(UserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateFingerprintGestureHandling,
                this, userState));
    }

    private void updateInputFilter(UserState userState) {
        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) return;

        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            int flags = 0;
            if (userState.mIsDisplayMagnificationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
            }
            if (userState.mIsNavBarMagnificationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TRIGGERED_SCREEN_MAGNIFIER;
            }
            if (userHasMagnificationServicesLocked(userState)) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER;
            }
            // Touch exploration without accessibility makes no sense.
            if (userState.isHandlingAccessibilityEvents() && userState.mIsTouchExplorationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
            }
            if (userState.mIsFilterKeyEventsEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS;
            }
            if (userState.mIsAutoclickEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_AUTOCLICK;
            }
            if (userState.mIsPerformGesturesEnabled) {
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

            final UserState userState = getCurrentUserStateLocked();
            if (userState.mIsTouchExplorationEnabled) {
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
                         userState.mIsTouchExplorationEnabled = true;
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
                     |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
             mEnableTouchExplorationDialog.setCanceledOnTouchOutside(true);
             mEnableTouchExplorationDialog.show();
        }
    }

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     */
    private void onUserStateChangedLocked(UserState userState) {
        // TODO: Remove this hack
        mInitialized = true;
        updateLegacyCapabilitiesLocked(userState);
        updateServicesLocked(userState);
        updateAccessibilityShortcutLocked(userState);
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updatePerformGesturesLocked(userState);
        updateMagnificationLocked(userState);
        scheduleUpdateFingerprintGestureHandling(userState);
        scheduleUpdateInputFilter(userState);
        updateRelevantEventsLocked(userState);
        scheduleUpdateClientsIfNeededLocked(userState);
        updateAccessibilityButtonTargetsLocked(userState);
    }

    private void updateWindowsForAccessibilityCallbackLocked(UserState userState) {
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

    private void updateLegacyCapabilitiesLocked(UserState userState) {
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

    private void updatePerformGesturesLocked(UserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if ((service.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) {
                userState.mIsPerformGesturesEnabled = true;
                return;
            }
        }
        userState.mIsPerformGesturesEnabled = false;
    }

    private void updateFilterKeyEventsLocked(UserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestFilterKeyEvents
                    && (service.getCapabilities()
                            & AccessibilityServiceInfo
                            .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0) {
                userState.mIsFilterKeyEventsEnabled = true;
                return;
            }
        }
        userState.mIsFilterKeyEventsEnabled = false;
    }

    private boolean readConfigurationForUserStateLocked(UserState userState) {
        boolean somethingChanged = readInstalledAccessibilityServiceLocked(userState);
        somethingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somethingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somethingChanged |= readMagnificationEnabledSettingsLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);
        somethingChanged |= readAccessibilityShortcutSettingLocked(userState);
        somethingChanged |= readAccessibilityButtonSettingsLocked(userState);
        somethingChanged |= readUserRecommendedUiTimeoutSettingsLocked(userState);
        return somethingChanged;
    }

    private void updateAccessibilityEnabledSetting(UserState userState) {
        final long identity = Binder.clearCallingIdentity();
        final boolean isA11yEnabled = mUiAutomationManager.isUiAutomationRunningLocked()
                || userState.isHandlingAccessibilityEvents();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    (isA11yEnabled) ? 1 : 0,
                    userState.mUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean readTouchExplorationEnabledSettingLocked(UserState userState) {
        final boolean touchExplorationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId) == 1;
        if (touchExplorationEnabled != userState.mIsTouchExplorationEnabled) {
            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            return true;
        }
        return false;
    }

    private boolean readMagnificationEnabledSettingsLocked(UserState userState) {
        final boolean displayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        final boolean navBarMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                0, userState.mUserId) == 1;
        if ((displayMagnificationEnabled != userState.mIsDisplayMagnificationEnabled)
                || (navBarMagnificationEnabled != userState.mIsNavBarMagnificationEnabled)) {
            userState.mIsDisplayMagnificationEnabled = displayMagnificationEnabled;
            userState.mIsNavBarMagnificationEnabled = navBarMagnificationEnabled;
            return true;
        }
        return false;
    }

    private boolean readAutoclickEnabledSettingLocked(UserState userState) {
        final boolean autoclickEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                0, userState.mUserId) == 1;
        if (autoclickEnabled != userState.mIsAutoclickEnabled) {
            userState.mIsAutoclickEnabled = autoclickEnabled;
            return true;
        }
        return false;
    }

    private boolean readHighTextContrastEnabledSettingLocked(UserState userState) {
        final boolean highTextContrastEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, 0,
                userState.mUserId) == 1;
        if (highTextContrastEnabled != userState.mIsTextHighContrastEnabled) {
            userState.mIsTextHighContrastEnabled = highTextContrastEnabled;
            return true;
        }
        return false;
    }

    private void updateTouchExplorationLocked(UserState userState) {
        boolean enabled = mUiAutomationManager.isTouchExplorationEnabledLocked();
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsTouchExplorationLocked(service, userState)) {
                enabled = true;
                break;
            }
        }
        if (enabled != userState.mIsTouchExplorationEnabled) {
            userState.mIsTouchExplorationEnabled = enabled;
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.TOUCH_EXPLORATION_ENABLED, enabled ? 1 : 0,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean readAccessibilityShortcutSettingLocked(UserState userState) {
        String componentNameToEnableString = AccessibilityShortcutController
                .getTargetServiceComponentNameString(mContext, userState.mUserId);
        if ((componentNameToEnableString == null) || componentNameToEnableString.isEmpty()) {
            if (userState.mServiceToEnableWithShortcut == null) {
                return false;
            }
            userState.mServiceToEnableWithShortcut = null;
            return true;
        }
        ComponentName componentNameToEnable =
            ComponentName.unflattenFromString(componentNameToEnableString);
        if ((componentNameToEnable != null)
                && componentNameToEnable.equals(userState.mServiceToEnableWithShortcut)) {
            return false;
        }

        userState.mServiceToEnableWithShortcut = componentNameToEnable;
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        return true;
    }

    private boolean readAccessibilityButtonSettingsLocked(UserState userState) {
        String componentId = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT, userState.mUserId);
        if (TextUtils.isEmpty(componentId)) {
            if ((userState.mServiceAssignedToAccessibilityButton == null)
                    && !userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                return false;
            }
            userState.mServiceAssignedToAccessibilityButton = null;
            userState.mIsNavBarMagnificationAssignedToAccessibilityButton = false;
            return true;
        }

        if (componentId.equals(MagnificationController.class.getName())) {
            if (userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                return false;
            }
            userState.mServiceAssignedToAccessibilityButton = null;
            userState.mIsNavBarMagnificationAssignedToAccessibilityButton = true;
            return true;
        }

        ComponentName componentName = ComponentName.unflattenFromString(componentId);
        if (Objects.equals(componentName, userState.mServiceAssignedToAccessibilityButton)) {
            return false;
        }
        userState.mServiceAssignedToAccessibilityButton = componentName;
        userState.mIsNavBarMagnificationAssignedToAccessibilityButton = false;
        return true;
    }

    private boolean readUserRecommendedUiTimeoutSettingsLocked(UserState userState) {
        final int nonInteractiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);
        final int interactiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);
        if (nonInteractiveUiTimeout != userState.mUserNonInteractiveUiTimeout
                || interactiveUiTimeout != userState.mUserInteractiveUiTimeout) {
            userState.mUserNonInteractiveUiTimeout = nonInteractiveUiTimeout;
            userState.mUserInteractiveUiTimeout = interactiveUiTimeout;
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            return true;
        }
        return false;
    }

    /**
     * Check if the service that will be enabled by the shortcut is installed. If it isn't,
     * clear the value and the associated setting so a sideloaded service can't spoof the
     * package name of the default service.
     *
     * @param userState
     */
    private void updateAccessibilityShortcutLocked(UserState userState) {
        if (userState.mServiceToEnableWithShortcut == null) {
            return;
        }
        boolean shortcutServiceIsInstalled =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap()
                        .containsKey(userState.mServiceToEnableWithShortcut);
        for (int i = 0; !shortcutServiceIsInstalled && (i < userState.mInstalledServices.size());
                i++) {
            if (userState.mInstalledServices.get(i).getComponentName()
                    .equals(userState.mServiceToEnableWithShortcut)) {
                shortcutServiceIsInstalled = true;
            }
        }
        if (!shortcutServiceIsInstalled) {
            userState.mServiceToEnableWithShortcut = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, null,
                        userState.mUserId);

                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_ENABLED, 0, userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean canRequestAndRequestsTouchExplorationLocked(
            AccessibilityServiceConnection service, UserState userState) {
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

    private void updateMagnificationLocked(UserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
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
        if (userState.mIsDisplayMagnificationEnabled
                || userState.mIsNavBarMagnificationEnabled) {
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
    private boolean userHasMagnificationServicesLocked(UserState userState) {
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
    private boolean userHasListeningMagnificationServicesLocked(UserState userState,
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

    private void updateFingerprintGestureHandling(UserState userState) {
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

    private void updateAccessibilityButtonTargetsLocked(UserState userState) {
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                service.notifyAccessibilityButtonAvailabilityChangedLocked(
                        service.isAccessibilityButtonAvailableLocked(userState));
            }
        }
    }

    private void updateRecommendedUiTimeoutLocked(UserState userState) {
        int newNonInteractiveUiTimeout = userState.mUserNonInteractiveUiTimeout;
        int newInteractiveUiTimeout = userState.mUserInteractiveUiTimeout;
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
        userState.mNonInteractiveUiTimeout = newNonInteractiveUiTimeout;
        userState.mInteractiveUiTimeout = newInteractiveUiTimeout;
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
     * AIDL-exposed method to be called when the accessibility shortcut is enabled. Requires
     * permission to write secure settings, since someone with that permission can enable
     * accessibility services themselves.
     */
    @Override
    public void performAccessibilityShortcut() {
        if ((UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)
                && (mContext.checkCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                    "performAccessibilityShortcut requires the MANAGE_ACCESSIBILITY permission");
        }
        final Map<ComponentName, ToggleableFrameworkFeatureInfo> frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        synchronized(mLock) {
            final UserState userState = getUserStateLocked(mCurrentUserId);
            final ComponentName serviceName = userState.mServiceToEnableWithShortcut;
            if (serviceName == null) {
                return;
            }
            if (frameworkFeatureMap.containsKey(serviceName)) {
                // Toggle the requested framework feature
                ToggleableFrameworkFeatureInfo featureInfo = frameworkFeatureMap.get(serviceName);
                SettingStringHelper setting = new SettingStringHelper(mContext.getContentResolver(),
                        featureInfo.getSettingKey(), mCurrentUserId);
                // Assuming that the default state will be to have the feature off
                if (!TextUtils.equals(featureInfo.getSettingOnValue(), setting.read())) {
                    setting.write(featureInfo.getSettingOnValue());
                } else {
                    setting.write(featureInfo.getSettingOffValue());
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                if (userState.mComponentNameToServiceMap.get(serviceName) == null) {
                    enableAccessibilityServiceLocked(serviceName, mCurrentUserId);
                } else {
                    disableAccessibilityServiceLocked(serviceName, mCurrentUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    };

    @Override
    public String getAccessibilityShortcutService() {
        if (mContext.checkCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "getAccessibilityShortcutService requires the MANAGE_ACCESSIBILITY permission");
        }
        synchronized(mLock) {
            final UserState userState = getUserStateLocked(mCurrentUserId);
            return userState.mServiceToEnableWithShortcut.flattenToString();
        }
    }

    /**
     * Enables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void enableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        final SettingStringHelper setting =
                new SettingStringHelper(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        userId);
        setting.write(ComponentNameSet.add(setting.read(), componentName));

        UserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.add(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * Disables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void disableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        final SettingsStringUtil.SettingStringHelper setting =
                new SettingStringHelper(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        userId);
        setting.write(ComponentNameSet.remove(setting.read(), componentName));

        UserState userState = getUserStateLocked(userId);
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
            final UserState userState = getCurrentUserStateLocked();
            return getRecommendedTimeoutMillisLocked(userState);
        }
    }

    private long getRecommendedTimeoutMillisLocked(UserState userState) {
        return IntPair.of(userState.mInteractiveUiTimeout,
                userState.mNonInteractiveUiTimeout);
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.println("ACCESSIBILITY MANAGER (dumpsys accessibility)");
            pw.println();
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                UserState userState = mUserStates.valueAt(i);
                pw.append("User state[attributes:{id=" + userState.mUserId);
                pw.append(", currentUser=" + (userState.mUserId == mCurrentUserId));
                pw.append(", touchExplorationEnabled=" + userState.mIsTouchExplorationEnabled);
                pw.append(", displayMagnificationEnabled="
                        + userState.mIsDisplayMagnificationEnabled);
                pw.append(", navBarMagnificationEnabled="
                        + userState.mIsNavBarMagnificationEnabled);
                pw.append(", autoclickEnabled=" + userState.mIsAutoclickEnabled);
                pw.append(", nonInteractiveUiTimeout=" + userState.mNonInteractiveUiTimeout);
                pw.append(", interactiveUiTimeout=" + userState.mInteractiveUiTimeout);
                pw.append(", installedServiceCount=" + userState.mInstalledServices.size());
                if (mUiAutomationManager.isUiAutomationRunningLocked()) {
                    pw.append(", ");
                    mUiAutomationManager.dumpUiAutomationService(fd, pw, args);
                    pw.println();
                }
                pw.append("}");
                pw.println();
                pw.append("     Bound services:{");
                final int serviceCount = userState.mBoundServices.size();
                for (int j = 0; j < serviceCount; j++) {
                    if (j > 0) {
                        pw.append(", ");
                        pw.println();
                        pw.append("                     ");
                    }
                    AccessibilityServiceConnection service = userState.mBoundServices.get(j);
                    service.dump(fd, pw, args);
                }
                pw.println("}");
                pw.append("     Enabled services:{");
                Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                if (it.hasNext()) {
                    ComponentName componentName = it.next();
                    pw.append(componentName.toShortString());
                    while (it.hasNext()) {
                        componentName = it.next();
                        pw.append(", ");
                        pw.append(componentName.toShortString());
                    }
                }
                pw.println("}");
                pw.append("     Binding services:{");
                it = userState.mBindingServices.iterator();
                if (it.hasNext()) {
                    ComponentName componentName = it.next();
                    pw.append(componentName.toShortString());
                    while (it.hasNext()) {
                        componentName = it.next();
                        pw.append(", ");
                        pw.append(componentName.toShortString());
                    }
                }
                pw.println("}]");
                pw.println();
            }
            mA11yWindowManager.dump(fd, pw, args);
        }
    }

    private void putSecureIntForUser(String key, int value, int userid) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(), key, value, userid);
        } finally {
            Binder.restoreCallingIdentity(identity);
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
        AccessibilityManagerService.UserState userState = getUserStateLocked(mCurrentUserId);
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
            final UserState userState;
            synchronized (mLock) {
                userState = getCurrentUserStateLocked();
            }
            AccessibilityServiceConnection service = new AccessibilityServiceConnection(
                    userState, mContext,
                    COMPONENT_NAME, info, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                    AccessibilityManagerService.this, mWindowManagerService,
                    mGlobalActionPerformer, mA11yWindowManager) {
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
                UserState userState = getCurrentUserStateLocked();
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
                for (int i = 0; i < mDisplaysList.size(); i++) {
                    if (mDisplaysList.get(i).getDisplayId() == displayId) {
                        mDisplaysList.remove(i);
                        break;
                    }
                }
                if (mInputFilter != null) {
                    mInputFilter.onDisplayChanged();
                }
                UserState userState = getCurrentUserStateLocked();
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
            if ((display.getType() == Display.TYPE_VIRTUAL
                    && (display.getFlags() & Display.FLAG_PRIVATE) != 0)) {
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

        private Client(IAccessibilityManagerClient callback, int clientUid, UserState userState) {
            mCallback = callback;
            mPackageNames = mPackageManager.getPackagesForUid(clientUid);
            synchronized (mLock) {
                mLastSentRelevantEventTypes = computeRelevantEventTypesLocked(userState, this);
            }
        }
    }

    public class UserState {
        public final int mUserId;

        // Non-transient state.

        public final RemoteCallbackList<IAccessibilityManagerClient> mUserClients =
                new RemoteCallbackList<>();

        // Transient state.

        public final ArrayList<AccessibilityServiceConnection> mBoundServices = new ArrayList<>();

        public final Map<ComponentName, AccessibilityServiceConnection> mComponentNameToServiceMap =
                new HashMap<>();

        public final List<AccessibilityServiceInfo> mInstalledServices =
                new ArrayList<>();

        private final Set<ComponentName> mBindingServices = new HashSet<>();

        public final Set<ComponentName> mEnabledServices = new HashSet<>();

        public final Set<ComponentName> mTouchExplorationGrantedServices =
                new HashSet<>();

        public ComponentName mServiceChangingSoftKeyboardMode;

        public ComponentName mServiceToEnableWithShortcut;

        public int mLastSentClientState = -1;
        public int mNonInteractiveUiTimeout = 0;
        public int mInteractiveUiTimeout = 0;

        private int mSoftKeyboardShowMode = 0;

        public boolean mIsNavBarMagnificationAssignedToAccessibilityButton;
        public ComponentName mServiceAssignedToAccessibilityButton;

        public boolean mIsTouchExplorationEnabled;
        public boolean mIsTextHighContrastEnabled;
        public boolean mIsDisplayMagnificationEnabled;
        public boolean mIsNavBarMagnificationEnabled;
        public boolean mIsAutoclickEnabled;
        public boolean mIsPerformGesturesEnabled;
        public boolean mIsFilterKeyEventsEnabled;
        public int mUserNonInteractiveUiTimeout;
        public int mUserInteractiveUiTimeout;

        private boolean mBindInstantServiceAllowed;

        public UserState(int userId) {
            mUserId = userId;
        }

        public int getClientState() {
            int clientState = 0;
            final boolean a11yEnabled = (mUiAutomationManager.isUiAutomationRunningLocked()
                    || isHandlingAccessibilityEvents());
            if (a11yEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
            }
            // Touch exploration relies on enabled accessibility.
            if (a11yEnabled && mIsTouchExplorationEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            }
            if (mIsTextHighContrastEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
            }
            return clientState;
        }

        public boolean isHandlingAccessibilityEvents() {
            return !mBoundServices.isEmpty() || !mBindingServices.isEmpty();
        }

        public void onSwitchToAnotherUserLocked() {
            // Unbind all services.
            unbindAllServicesLocked(this);

            // Clear service management state.
            mBoundServices.clear();
            mBindingServices.clear();

            // Clear event management state.
            mLastSentClientState = -1;

            // clear UI timeout
            mNonInteractiveUiTimeout = 0;
            mInteractiveUiTimeout = 0;

            // Clear state persisted in settings.
            mEnabledServices.clear();
            mTouchExplorationGrantedServices.clear();
            mIsTouchExplorationEnabled = false;
            mIsDisplayMagnificationEnabled = false;
            mIsNavBarMagnificationEnabled = false;
            mServiceAssignedToAccessibilityButton = null;
            mIsNavBarMagnificationAssignedToAccessibilityButton = false;
            mIsAutoclickEnabled = false;
            mUserNonInteractiveUiTimeout = 0;
            mUserInteractiveUiTimeout = 0;
        }

        public void addServiceLocked(AccessibilityServiceConnection serviceConnection) {
            if (!mBoundServices.contains(serviceConnection)) {
                serviceConnection.onAdded();
                mBoundServices.add(serviceConnection);
                mComponentNameToServiceMap.put(serviceConnection.mComponentName, serviceConnection);
                scheduleNotifyClientsOfServicesStateChangeLocked(this);
            }
        }

        /**
         * Removes a service.
         * There are three states to a service here: off, bound, and binding.
         * This stops tracking the service as bound.
         *
         * @param serviceConnection The service.
         */
        public void removeServiceLocked(AccessibilityServiceConnection serviceConnection) {
            mBoundServices.remove(serviceConnection);
            serviceConnection.onRemoved();
            if ((mServiceChangingSoftKeyboardMode != null)
                    && (mServiceChangingSoftKeyboardMode.equals(
                            serviceConnection.getServiceInfo().getComponentName()))) {
                setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
            }
            // It may be possible to bind a service twice, which confuses the map. Rebuild the map
            // to make sure we can still reach a service
            mComponentNameToServiceMap.clear();
            for (int i = 0; i < mBoundServices.size(); i++) {
                AccessibilityServiceConnection boundClient = mBoundServices.get(i);
                mComponentNameToServiceMap.put(boundClient.mComponentName, boundClient);
            }
            scheduleNotifyClientsOfServicesStateChangeLocked(this);
        }

        /**
         * Make sure a services disconnected but still 'on' state is reflected in UserState
         * There are three states to a service here: off, bound, and binding.
         * This drops a service from a bound state, to the binding state.
         * The binding state describes the situation where a service is on, but not bound.
         *
         * @param serviceConnection The service.
         */
        public void serviceDisconnectedLocked(AccessibilityServiceConnection serviceConnection) {
            removeServiceLocked(serviceConnection);
            mBindingServices.add(serviceConnection.getComponentName());
        }

        public Set<ComponentName> getBindingServicesLocked() {
            return mBindingServices;
        }

        /**
         * Returns enabled service list.
         */
        public Set<ComponentName> getEnabledServicesLocked() {
            return mEnabledServices;
        }

        public int getSoftKeyboardShowMode() {
            return mSoftKeyboardShowMode;
        }

        /**
         * Set the soft keyboard mode. This mode is a bit odd, as it spans multiple settings.
         * The ACCESSIBILITY_SOFT_KEYBOARD_MODE setting can be checked by the rest of the system
         * to see if it should suppress showing the IME. The SHOW_IME_WITH_HARD_KEYBOARD setting
         * setting can be changed by the user, and prevents the system from suppressing the soft
         * keyboard when the hard keyboard is connected. The hard keyboard setting needs to defer
         * to the user's preference, if they have supplied one.
         *
         * @param newMode The new mode
         * @param requester The service requesting the change, so we can undo it when the
         *                  service stops. Set to null if something other than a service is forcing
         *                  the change.
         *
         * @return Whether or not the soft keyboard mode equals the new mode after the call
         */
        public boolean setSoftKeyboardModeLocked(int newMode, @Nullable ComponentName requester) {
            if ((newMode != SHOW_MODE_AUTO) && (newMode != SHOW_MODE_HIDDEN)
                    && (newMode != SHOW_MODE_IGNORE_HARD_KEYBOARD))
            {
                Slog.w(LOG_TAG, "Invalid soft keyboard mode");
                return false;
            }
            if (mSoftKeyboardShowMode == newMode) return true;

            if (newMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                if (hasUserOverriddenHardKeyboardSettingLocked()) {
                    // The user has specified a default for this setting
                    return false;
                }
                // Save the original value. But don't do this if the value in settings is already
                // the new mode. That happens when we start up after a reboot, and we don't want
                // to overwrite the value we had from when we first started controlling the setting.
                if (getSoftKeyboardValueFromSettings() != SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                    setOriginalHardKeyboardValue(
                            Settings.Secure.getInt(mContext.getContentResolver(),
                                    Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0);
                }
                putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 1, mUserId);
            } else if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                        getOriginalHardKeyboardValue() ? 1 : 0, mUserId);
            }

            saveSoftKeyboardValueToSettings(newMode);
            mSoftKeyboardShowMode = newMode;
            mServiceChangingSoftKeyboardMode = requester;
            notifySoftKeyboardShowModeChangedLocked(mSoftKeyboardShowMode);
            return true;
        }

        /**
         * If the settings are inconsistent with the internal state, make the internal state
         * match the settings.
         */
        public void reconcileSoftKeyboardModeWithSettingsLocked() {
            final ContentResolver cr = mContext.getContentResolver();
            final boolean showWithHardKeyboardSettings =
                    Settings.Secure.getInt(cr, Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0;
            if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                if (!showWithHardKeyboardSettings) {
                    // The user has overridden the setting. Respect that and prevent further changes
                    // to this behavior.
                    setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
                    setUserOverridesHardKeyboardSettingLocked();
                }
            }

            // If the setting and the internal state are out of sync, set both to default
            if (getSoftKeyboardValueFromSettings() != mSoftKeyboardShowMode)
            {
                Slog.e(LOG_TAG,
                        "Show IME setting inconsistent with internal state. Overwriting");
                setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
                putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                        SHOW_MODE_AUTO, mUserId);
            }
        }

        private void setUserOverridesHardKeyboardSettingLocked() {
            final int softKeyboardSetting = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0);
            putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    softKeyboardSetting | SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN,
                    mUserId);
        }

        private boolean hasUserOverriddenHardKeyboardSettingLocked() {
            final int softKeyboardSetting = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0);
            return (softKeyboardSetting & SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN)
                    != 0;
        }

        private void setOriginalHardKeyboardValue(boolean originalHardKeyboardValue) {
            final int oldSoftKeyboardSetting = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0);
            final int newSoftKeyboardSetting = oldSoftKeyboardSetting
                    & (~SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE)
                    | ((originalHardKeyboardValue) ? SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE : 0);
            putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    newSoftKeyboardSetting, mUserId);
        }

        private void saveSoftKeyboardValueToSettings(int softKeyboardShowMode) {
            final int oldSoftKeyboardSetting = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0);
            final int newSoftKeyboardSetting = oldSoftKeyboardSetting & (~SHOW_MODE_MASK)
                    | softKeyboardShowMode;
            putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    newSoftKeyboardSetting, mUserId);
        }

        private int getSoftKeyboardValueFromSettings() {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    SHOW_MODE_AUTO) & SHOW_MODE_MASK;
        }

        private boolean getOriginalHardKeyboardValue() {
            return (Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0)
                    & SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE) != 0;
        }

        public boolean getBindInstantServiceAllowed() {
            synchronized (mLock) {
                return mBindInstantServiceAllowed;
            }
        }

        public void setBindInstantServiceAllowed(boolean allowed) {
            synchronized (mLock) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.MANAGE_BIND_INSTANT_SERVICE,
                        "setBindInstantServiceAllowed");
                if (allowed) {
                    mBindInstantServiceAllowed = allowed;
                    onUserStateChangedLocked(this);
                }
            }
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mDisplayMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

        private final Uri mNavBarMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);

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
            contentResolver.registerContentObserver(mNavBarMagnificationEnabledUri,
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
                UserState userState = getCurrentUserStateLocked();

                if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayMagnificationEnabledUri.equals(uri)
                        || mNavBarMagnificationEnabledUri.equals(uri)) {
                    if (readMagnificationEnabledSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAutoclickEnabledUri.equals(uri)) {
                    if (readAutoclickEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mEnabledAccessibilityServicesUri.equals(uri)) {
                    if (readEnabledAccessibilityServicesLocked(userState)) {
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
                    if (readAccessibilityShortcutSettingLocked(userState)) {
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
}
