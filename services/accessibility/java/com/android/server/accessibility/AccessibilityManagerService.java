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

import static android.accessibilityservice.AccessibilityServiceInfo.DEFAULT;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.UiAutomation;
import android.appwidget.AppWidgetManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
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
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.ArraySet;
import android.view.Display;
import android.view.IWindow;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerInternal;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.R;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

import com.android.server.statusbar.StatusBarManagerInternal;
import libcore.util.EmptyArray;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    private static final int WAIT_WINDOWS_TIMEOUT_MILLIS = 5000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS = 1000;

    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED =
            "temporaryEnableAccessibilityStateUntilKeyguardRemoved";

    private static final String GET_WINDOW_TOKEN = "getWindowToken";

    private static final ComponentName sFakeAccessibilityServiceComponentName =
            new ComponentName("foo.bar", "FakeService");

    private static final String FUNCTION_DUMP = "dump";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    private static final int WINDOW_ID_UNKNOWN = -1;

    // Each service has an ID. Also provide one for magnification gesture handling
    public static final int MAGNIFICATION_GESTURE_HANDLER_ID = 0;

    private static int sIdCounter = MAGNIFICATION_GESTURE_HANDLER_ID + 1;

    private static int sNextWindowId;

    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final List<AccessibilityServiceInfo> mEnabledServicesForFeedbackTempList =
            new ArrayList<>();

    private final Rect mTempRect = new Rect();

    private final Rect mTempRect1 = new Rect();

    private final Point mTempPoint = new Point();

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final WindowManagerInternal mWindowManagerService;

    private AppWidgetManagerInternal mAppWidgetService;

    private final SecurityPolicy mSecurityPolicy;

    private final MainHandler mMainHandler;

    private MagnificationController mMagnificationController;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private KeyEventDispatcher mKeyEventDispatcher;

    private MotionEventInjector mMotionEventInjector;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final List<AccessibilityServiceInfo> mTempAccessibilityServiceInfoList =
            new ArrayList<>();

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    private final SparseArray<RemoteAccessibilityConnection> mGlobalInteractionConnections =
            new SparseArray<>();

    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<>();

    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final UserManager mUserManager;

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    //TODO: Remove this hack
    private boolean mInitialized;

    private WindowsForAccessibilityCallback mWindowsForAccessibilityCallback;

    private UserState getCurrentUserStateLocked() {
        return getUserStateLocked(mCurrentUserId);
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
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mSecurityPolicy = new SecurityPolicy();
        mMainHandler = new MainHandler(mContext.getMainLooper());
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                context.getContentResolver());
    }

    private UserState getUserStateLocked(int userId) {
        UserState state = mUserStates.get(userId);
        if (state == null) {
            state = new UserState(userId);
            mUserStates.put(userId, state);
        }
        return state;
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
                    if (!userState.isUiAutomationSuppressingOtherServices()) {
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
                        }
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                // Unbind all services from this package, and then update the user state to
                // re-bind new versions of them.
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    UserState userState = getUserStateLocked(userId);
                    boolean unboundAService = false;
                    for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                        Service boundService = userState.mBoundServices.get(i);
                        String servicePkg = boundService.mComponentName.getPackageName();
                        if (servicePkg.equals(packageName)) {
                            boundService.unbindLocked();
                            unboundAService = true;
                        }
                    }
                    if (unboundAService) {
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
                            // We will update when the automation service dies.
                            if (!userState.isUiAutomationSuppressingOtherServices()) {
                                onUserStateChangedLocked(userState);
                            }
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
                                persistComponentNamesToSettingLocked(
                                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                        userState.mEnabledServices, userId);
                                // We will update when the automation service dies.
                                if (!userState.isUiAutomationSuppressingOtherServices()) {
                                    onUserStateChangedLocked(userState);
                                }
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
                    UserState userState = getCurrentUserStateLocked();
                    if (!userState.isUiAutomationSuppressingOtherServices()) {
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
    public int addClient(IAccessibilityManagerClient client, int userId) {
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
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                mGlobalClients.register(client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
                return userState.getClientState();
            } else {
                userState.mClients.register(client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
                return (resolvedUserId == mCurrentUserId) ? userState.getClientState() : 0;
            }
        }
    }

    @Override
    public boolean sendAccessibilityEvent(AccessibilityEvent event, int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution..
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // Make sure the reported package is one the caller has access to.
            event.setPackageName(mSecurityPolicy.resolveValidReportedPackageLocked(
                    event.getPackageName(), UserHandle.getCallingAppId(), resolvedUserId));

            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return true; // yes, recycle the event
            }
            if (mSecurityPolicy.canDispatchAccessibilityEventLocked(event)) {
                mSecurityPolicy.updateActiveAndAccessibilityFocusedWindowLocked(event.getWindowId(),
                        event.getSourceNodeId(), event.getEventType(), event.getAction());
                mSecurityPolicy.updateEventSourceLocked(event);
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
            }
            if (mHasInputFilter && mInputFilter != null) {
                mMainHandler.obtainMessage(MainHandler.MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER,
                        AccessibilityEvent.obtain(event)).sendToTarget();
            }
            event.recycle();
        }
        return (OWN_PROCESS_ID != Binder.getCallingPid());
    }

    @Override
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // The automation service is a fake one and should not be reported
            // to clients as being installed - it really is not.
            UserState userState = getUserStateLocked(resolvedUserId);
            if (userState.mUiAutomationService != null) {
                List<AccessibilityServiceInfo> installedServices = new ArrayList<>();
                installedServices.addAll(userState.mInstalledServices);
                installedServices.remove(userState.mUiAutomationService.mAccessibilityServiceInfo);
                return installedServices;
            }
            return userState.mInstalledServices;
        }
    }

    @Override
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        List<AccessibilityServiceInfo> result = null;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // The automation service can suppress other services.
            UserState userState = getUserStateLocked(resolvedUserId);
            if (userState.isUiAutomationSuppressingOtherServices()) {
                return Collections.emptyList();
            }

            result = mEnabledServicesForFeedbackTempList;
            result.clear();
            List<Service> services = userState.mBoundServices;
            while (feedbackType != 0) {
                final int feedbackTypeBit = (1 << Integer.numberOfTrailingZeros(feedbackType));
                feedbackType &= ~feedbackTypeBit;
                final int serviceCount = services.size();
                for (int i = 0; i < serviceCount; i++) {
                    Service service = services.get(i);
                    // Don't report the UIAutomation (fake service)
                    if (!sFakeAccessibilityServiceComponentName.equals(service.mComponentName)
                            && (service.mFeedbackType & feedbackTypeBit) != 0) {
                        result.add(service.mAccessibilityServiceInfo);
                    }
                }
            }
        }
        return result;
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
            List<Service> services = getUserStateLocked(resolvedUserId).mBoundServices;
            int numServices = services.size();
            interfacesToInterrupt = new ArrayList<>(numServices);
            for (int i = 0; i < numServices; i++) {
                Service service = services.get(i);
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
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            final int resolvedUid = UserHandle.getUid(resolvedUserId, UserHandle.getCallingAppId());

            // Make sure the reported package is one the caller has access to.
            packageName = mSecurityPolicy.resolveValidReportedPackageLocked(
                    packageName, UserHandle.getCallingAppId(), resolvedUserId);

            final int windowId = sNextWindowId++;
            // If the window is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, UserHandle.USER_ALL);
                wrapper.linkToDeath();
                mGlobalInteractionConnections.put(windowId, wrapper);
                mGlobalWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and  token: "
                            + windowToken.asBinder());
                }
            } else {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, resolvedUserId);
                wrapper.linkToDeath();
                UserState userState = getUserStateLocked(resolvedUserId);
                userState.mInteractionConnections.put(windowId, wrapper);
                userState.mWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and userId:" + mCurrentUserId
                            + " and  token: " + windowToken.asBinder());
                }
            }
            return windowId;
        }
    }

    @Override
    public void removeAccessibilityInteractionConnection(IWindow window) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                    UserHandle.getCallingUserId());
            IBinder token = window.asBinder();
            final int removedWindowId = removeAccessibilityInteractionConnectionInternalLocked(
                    token, mGlobalWindowTokens, mGlobalInteractionConnections);
            if (removedWindowId >= 0) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + removedWindowId + " and token: "
                            + window.asBinder());
                }
                return;
            }
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                UserState userState = mUserStates.valueAt(i);
                final int removedWindowIdForUser =
                        removeAccessibilityInteractionConnectionInternalLocked(
                        token, userState.mWindowTokens, userState.mInteractionConnections);
                if (removedWindowIdForUser >= 0) {
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Removed user connection for pid:" + Binder.getCallingPid()
                                + " with windowId: " + removedWindowIdForUser + " and userId:"
                                + mUserStates.keyAt(i) + " and token: " + window.asBinder());
                    }
                    return;
                }
            }
        }
    }

    private int removeAccessibilityInteractionConnectionInternalLocked(IBinder windowToken,
            SparseArray<IBinder> windowTokens,
            SparseArray<RemoteAccessibilityConnection> interactionConnections) {
        final int count = windowTokens.size();
        for (int i = 0; i < count; i++) {
            if (windowTokens.valueAt(i) == windowToken) {
                final int windowId = windowTokens.keyAt(i);
                windowTokens.removeAt(i);
                RemoteAccessibilityConnection wrapper = interactionConnections.get(windowId);
                wrapper.unlinkToDeath();
                interactionConnections.remove(windowId);
                return windowId;
            }
        }
        return -1;
    }

    @Override
    public void registerUiTestAutomationService(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo,
            int flags) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE);

        accessibilityServiceInfo.setComponentName(sFakeAccessibilityServiceComponentName);

        synchronized (mLock) {
            UserState userState = getCurrentUserStateLocked();

            if (userState.mUiAutomationService != null) {
                throw new IllegalStateException("UiAutomationService " + serviceClient
                        + "already registered!");
            }

            try {
                owner.linkToDeath(userState.mUiAutomationSerivceOnwerDeathRecipient, 0);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Couldn't register for the death of a"
                        + " UiTestAutomationService!", re);
                return;
            }

            userState.mUiAutomationServiceOwner = owner;
            userState.mUiAutomationServiceClient = serviceClient;
            userState.mUiAutomationFlags = flags;
            userState.mInstalledServices.add(accessibilityServiceInfo);
            if ((flags & UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) == 0) {
                // Set the temporary state, and use it instead of settings
                userState.mIsTouchExplorationEnabled = false;
                userState.mIsEnhancedWebAccessibilityEnabled = false;
                userState.mIsDisplayMagnificationEnabled = false;
                userState.mIsAutoclickEnabled = false;
                userState.mEnabledServices.clear();
            }
            userState.mEnabledServices.add(sFakeAccessibilityServiceComponentName);
            userState.mTouchExplorationGrantedServices.add(sFakeAccessibilityServiceComponentName);

            // Use the new state instead of settings.
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        synchronized (mLock) {
            UserState userState = getCurrentUserStateLocked();
            // Automation service is not bound, so pretend it died to perform clean up.
            if (userState.mUiAutomationService != null
                    && serviceClient != null
                    && userState.mUiAutomationService.mServiceInterface != null
                    && userState.mUiAutomationService.mServiceInterface.asBinder()
                    == serviceClient.asBinder()) {
                userState.mUiAutomationService.binderDied();
            } else {
                throw new IllegalStateException("UiAutomationService " + serviceClient
                        + " not registered!");
            }
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

            // This is a nop if UI automation is enabled.
            if (userState.isUiAutomationSuppressingOtherServices()) {
                return;
            }

            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            userState.mIsEnhancedWebAccessibilityEnabled = false;
            userState.mIsDisplayMagnificationEnabled = false;
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
            if (mSecurityPolicy.findWindowById(windowId) == null) {
                return null;
            }
            IBinder token = mGlobalWindowTokens.get(windowId);
            if (token != null) {
                return token;
            }
            return getCurrentUserStateLocked().mWindowTokens.get(windowId);
        }
    }

    boolean onGesture(int gestureId) {
        synchronized (mLock) {
            boolean handled = notifyGestureLocked(gestureId, false);
            if (!handled) {
                handled = notifyGestureLocked(gestureId, true);
            }
            return handled;
        }
    }

    boolean notifyKeyEvent(KeyEvent event, int policyFlags) {
        synchronized (mLock) {
            List<Service> boundServices = getCurrentUserStateLocked().mBoundServices;
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
     * @param region the new magnified region, may be empty if
     *               magnification is not enabled (e.g. scale is 1)
     * @param scale the new scale
     * @param centerX the new screen-relative center X coordinate
     * @param centerY the new screen-relative center Y coordinate
     */
    void notifyMagnificationChanged(@NonNull Region region,
            float scale, float centerX, float centerY) {
        synchronized (mLock) {
            notifyMagnificationChangedLocked(region, scale, centerX, centerY);
        }
    }

    /**
     * Called by AccessibilityInputFilter when it creates or destroys the motionEventInjector.
     * Not using a getter because the AccessibilityInputFilter isn't thread-safe
     *
     * @param motionEventInjector The new value of the motionEventInjector. May be null.
     */
    void setMotionEventInjector(MotionEventInjector motionEventInjector) {
        synchronized (mLock) {
            mMotionEventInjector = motionEventInjector;
            // We may be waiting on this object being set
            mLock.notifyAll();
        }
    }

    /**
     * Gets a point within the accessibility focused node where we can send down
     * and up events to perform a click.
     *
     * @param outPoint The click point to populate.
     * @return Whether accessibility a click point was found and set.
     */
    // TODO: (multi-display) Make sure this works for multiple displays.
    boolean getAccessibilityFocusClickPointInScreen(Point outPoint) {
        return getInteractionBridgeLocked()
                .getAccessibilityFocusClickPointInScreenNotLocked(outPoint);
    }

    /**
     * Gets the bounds of a window.
     *
     * @param outBounds The output to which to write the bounds.
     */
    boolean getWindowBounds(int windowId, Rect outBounds) {
        IBinder token;
        synchronized (mLock) {
            token = mGlobalWindowTokens.get(windowId);
            if (token == null) {
                token = getCurrentUserStateLocked().mWindowTokens.get(windowId);
            }
        }
        mWindowManagerService.getWindowFrame(token, outBounds);
        if (!outBounds.isEmpty()) {
            return true;
        }
        return false;
    }

    boolean accessibilityFocusOnlyInActiveWindow() {
        synchronized (mLock) {
            return mWindowsForAccessibilityCallback == null;
        }
    }

    int getActiveWindowId() {
        return mSecurityPolicy.getActiveWindowId();
    }

    void onTouchInteractionStart() {
        mSecurityPolicy.onTouchInteractionStart();
    }

    void onTouchInteractionEnd() {
        mSecurityPolicy.onTouchInteractionEnd();
    }

    void onMagnificationStateChanged() {
        notifyClearAccessibilityCacheLocked();
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId && mInitialized) {
                return;
            }

            // Disconnect from services for the old user.
            UserState oldUserState = getCurrentUserStateLocked();
            oldUserState.onSwitchToAnotherUser();

            // Disable the local managers for the old user.
            if (oldUserState.mClients.getRegisteredCallbackCount() > 0) {
                mMainHandler.obtainMessage(MainHandler.MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER,
                        oldUserState.mUserId, 0).sendToTarget();
            }

            // Announce user changes only if more that one exist.
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            final boolean announceNewUser = userManager.getUsers().size() > 1;

            // The user changed.
            mCurrentUserId = userId;

            UserState userState = getCurrentUserStateLocked();
            if (userState.mUiAutomationService != null) {
                // Switching users disables the UI automation service.
                userState.mUiAutomationService.binderDied();
            }

            readConfigurationForUserStateLocked(userState);
            // Even if reading did not yield change, we have to update
            // the state since the context in which the current user
            // state was used has changed since it was inactive.
            onUserStateChangedLocked(userState);

            if (announceNewUser) {
                // Schedule announcement of the current user if needed.
                mMainHandler.sendEmptyMessageDelayed(MainHandler.MSG_ANNOUNCE_NEW_USER_IF_NEEDED,
                        WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS);
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

    private InteractionBridge getInteractionBridgeLocked() {
        if (mInteractionBridge == null) {
            mInteractionBridge = new InteractionBridge();
        }
        return mInteractionBridge;
    }

    private boolean notifyGestureLocked(int gestureId, boolean isDefault) {
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
            Service service = state.mBoundServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureId);
                return true;
            }
        }
        return false;
    }

    private void notifyClearAccessibilityCacheLocked() {
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            Service service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
        }
    }

    private void notifyMagnificationChangedLocked(@NonNull Region region,
            float scale, float centerX, float centerY) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final Service service = state.mBoundServices.get(i);
            service.notifyMagnificationChangedLocked(region, scale, centerX, centerY);
        }
    }

    private void notifySoftKeyboardShowModeChangedLocked(int showMode) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final Service service = state.mBoundServices.get(i);
            service.notifySoftKeyboardShowModeChangedLocked(showMode);
        }
    }

    /**
     * Removes an AccessibilityInteractionConnection.
     *
     * @param windowId The id of the window to which the connection is targeted.
     * @param userId The id of the user owning the connection. UserHandle.USER_ALL
     *     if global.
     */
    private void removeAccessibilityInteractionConnectionLocked(int windowId, int userId) {
        if (userId == UserHandle.USER_ALL) {
            mGlobalWindowTokens.remove(windowId);
            mGlobalInteractionConnections.remove(windowId);
        } else {
            UserState userState = getCurrentUserStateLocked();
            userState.mWindowTokens.remove(windowId);
            userState.mInteractionConnections.remove(windowId);
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "Removing interaction connection to windowId: " + windowId);
        }
    }

    private boolean readInstalledAccessibilityServiceLocked(UserState userState) {
        mTempAccessibilityServiceInfoList.clear();

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES
                        | PackageManager.GET_META_DATA
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                mCurrentUserId);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (!android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(
                    serviceInfo.permission)) {
                Slog.w(LOG_TAG, "Skipping accessibilty service " + new ComponentName(
                        serviceInfo.packageName, serviceInfo.name).flattenToShortString()
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE);
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
            if (userState.mUiAutomationService != null) {
                userState.mEnabledServices.add(sFakeAccessibilityServiceComponentName);
            }
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
                Service service = state.mBoundServices.get(i);

                if (service.mIsDefault == isDefault) {
                    if (canDispatchEventToServiceLocked(service, event)) {
                        service.notifyAccessibilityEvent(event);
                    }
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
        }
    }

    private void addServiceLocked(Service service, UserState userState) {
        try {
            if (!userState.mBoundServices.contains(service)) {
                service.onAdded();
                userState.mBoundServices.add(service);
                userState.mComponentNameToServiceMap.put(service.mComponentName, service);
            }
        } catch (RemoteException re) {
            /* do nothing */
        }
    }

    /**
     * Removes a service.
     *
     * @param service The service.
     */
    private void removeServiceLocked(Service service, UserState userState) {
        userState.mBoundServices.remove(service);
        service.onRemoved();
        // It may be possible to bind a service twice, which confuses the map. Rebuild the map
        // to make sure we can still reach a service
        userState.mComponentNameToServiceMap.clear();
        for (int i = 0; i < userState.mBoundServices.size(); i++) {
            Service boundService = userState.mBoundServices.get(i);
            userState.mComponentNameToServiceMap.put(boundService.mComponentName, boundService);
        }
    }

    /**
     * Determines if given event can be dispatched to a service based on the package of the
     * event source. Specifically, a service is notified if it is interested in events from the
     * package.
     *
     * @param service The potential receiver.
     * @param event The event.
     * @return True if the listener should be notified, false otherwise.
     */
    private boolean canDispatchEventToServiceLocked(Service service, AccessibilityEvent event) {

        if (!service.canReceiveEventsLocked()) {
            return false;
        }

        if (event.getWindowId() != WINDOW_ID_UNKNOWN && !event.isImportantForAccessibility()
                && (service.mFetchFlags
                        & AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) == 0) {
            return false;
        }

        int eventType = event.getEventType();
        if ((service.mEventTypes & eventType) != eventType) {
            return false;
        }

        Set<String> packageNames = service.mPackageNames;
        String packageName = (event.getPackageName() != null)
                ? event.getPackageName().toString() : null;

        return (packageNames.isEmpty() || packageNames.contains(packageName));
    }

    private void unbindAllServicesLocked(UserState userState) {
        List<Service> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            Service service = services.get(i);
            if (service.unbindLocked()) {
                i--;
                count--;
            }
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

    /**
     * Persists the component names in the specified setting in a
     * colon separated fashion.
     *
     * @param settingName The setting name.
     * @param componentNames The component names.
     */
    private void persistComponentNamesToSettingLocked(String settingName,
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
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    settingName, builder.toString(), userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void updateServicesLocked(UserState userState) {
        Map<ComponentName, Service> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isUnlockingOrUnlocked = mContext.getSystemService(UserManager.class)
                .isUserUnlockingOrUnlocked(userState.mUserId);

        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());

            Service service = componentNameToServiceMap.get(componentName);

            // Ignore non-encryption-aware services until user is unlocked
            if (!isUnlockingOrUnlocked && !installedService.isDirectBootAware()) {
                Slog.d(LOG_TAG, "Ignoring non-encryption-aware service " + componentName);
                continue;
            }

            // Wait for the binding if it is in process.
            if (userState.mBindingServices.contains(componentName)) {
                continue;
            }
            if (userState.mEnabledServices.contains(componentName)) {
                if (service == null) {
                    service = new Service(userState.mUserId, componentName, installedService);
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

        updateAccessibilityEnabledSetting(userState);
    }

    private void scheduleUpdateClientsIfNeededLocked(UserState userState) {
        final int clientState = userState.getClientState();
        if (userState.mLastSentClientState != clientState
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                        || userState.mClients.getRegisteredCallbackCount() > 0)) {
            userState.mLastSentClientState = clientState;
            mMainHandler.obtainMessage(MainHandler.MSG_SEND_STATE_TO_CLIENTS,
                    clientState, userState.mUserId) .sendToTarget();
        }
    }

    private void scheduleUpdateInputFilter(UserState userState) {
        mMainHandler.obtainMessage(MainHandler.MSG_UPDATE_INPUT_FILTER, userState).sendToTarget();
    }

    private void updateInputFilter(UserState userState) {
        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            int flags = 0;
            if (userState.mIsDisplayMagnificationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
            }
            if (userHasMagnificationServicesLocked(userState)) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER;
            }
            // Touch exploration without accessibility makes no sense.
            if (userState.isHandlingAccessibilityEvents()
                    && userState.mIsTouchExplorationEnabled) {
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

    private void showEnableTouchExplorationDialog(final Service service) {
        synchronized (mLock) {
            String label = service.mResolveInfo.loadLabel(
            mContext.getPackageManager()).toString();

            final UserState state = getCurrentUserStateLocked();
            if (state.mIsTouchExplorationEnabled) {
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
                         state.mTouchExplorationGrantedServices.add(service.mComponentName);
                         persistComponentNamesToSettingLocked(
                                 Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                 state.mTouchExplorationGrantedServices, state.mUserId);
                         // Enable touch exploration.
                         UserState userState = getUserStateLocked(service.mUserId);
                         userState.mIsTouchExplorationEnabled = true;
                         final long identity = Binder.clearCallingIdentity();
                         try {
                             Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                     Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                     service.mUserId);
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
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateAccessibilityFocusBehaviorLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updatePerformGesturesLocked(userState);
        updateEnhancedWebAccessibilityLocked(userState);
        updateDisplayDaltonizerLocked(userState);
        updateDisplayInversionLocked(userState);
        updateMagnificationLocked(userState);
        updateSoftKeyboardShowModeLocked(userState);
        scheduleUpdateInputFilter(userState);
        scheduleUpdateClientsIfNeededLocked(userState);
    }

    private void updateAccessibilityFocusBehaviorLocked(UserState userState) {
        // If there is no service that can operate with interactive windows
        // then we keep the old behavior where a window loses accessibility
        // focus if it is no longer active. This still changes the behavior
        // for services that do not operate with interactive windows and run
        // at the same time as the one(s) which does. In practice however,
        // there is only one service that uses accessibility focus and it
        // is typically the one that operates with interactive windows, So,
        // this is fine. Note that to allow a service to work across windows
        // we have to allow accessibility focus stay in any of them. Sigh...
        List<Service> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; i < boundServiceCount; i++) {
            Service boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                userState.mAccessibilityFocusOnlyInActiveWindow = false;
                return;
            }
        }
        userState.mAccessibilityFocusOnlyInActiveWindow = true;
    }

    private void updateWindowsForAccessibilityCallbackLocked(UserState userState) {
        // We observe windows for accessibility only if there is at least
        // one bound service that can retrieve window content that specified
        // it is interested in accessing such windows. For services that are
        // binding we do an update pass after each bind event, so we run this
        // code and register the callback if needed.

        List<Service> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; i < boundServiceCount; i++) {
            Service boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                if (mWindowsForAccessibilityCallback == null) {
                    mWindowsForAccessibilityCallback = new WindowsForAccessibilityCallback();
                    mWindowManagerService.setWindowsForAccessibilityCallback(
                            mWindowsForAccessibilityCallback);
                }
                return;
            }
        }

        if (mWindowsForAccessibilityCallback != null) {
            mWindowsForAccessibilityCallback = null;
            mWindowManagerService.setWindowsForAccessibilityCallback(null);
            // Drop all windows we know about.
            mSecurityPolicy.clearWindowsLocked();
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
            Service service = userState.mBoundServices.get(i);
            if ((service.mAccessibilityServiceInfo.getCapabilities()
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
            Service service = userState.mBoundServices.get(i);
            if (service.mRequestFilterKeyEvents
                    && (service.mAccessibilityServiceInfo.getCapabilities()
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
        somethingChanged |= readEnhancedWebAccessibilityEnabledChangedLocked(userState);
        somethingChanged |= readDisplayMagnificationEnabledSettingLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);

        return somethingChanged;
    }

    private void updateAccessibilityEnabledSetting(UserState userState) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    userState.isHandlingAccessibilityEvents() ? 1 : 0,
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

    private boolean readDisplayMagnificationEnabledSettingLocked(UserState userState) {
        final boolean displayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        if (displayMagnificationEnabled != userState.mIsDisplayMagnificationEnabled) {
            userState.mIsDisplayMagnificationEnabled = displayMagnificationEnabled;
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

    private boolean readEnhancedWebAccessibilityEnabledChangedLocked(UserState userState) {
         final boolean enhancedWeAccessibilityEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION,
                0, userState.mUserId) == 1;
         if (enhancedWeAccessibilityEnabled != userState.mIsEnhancedWebAccessibilityEnabled) {
             userState.mIsEnhancedWebAccessibilityEnabled = enhancedWeAccessibilityEnabled;
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

    private boolean readSoftKeyboardShowModeChangedLocked(UserState userState) {
        final int softKeyboardShowMode = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0,
                userState.mUserId);
        if (softKeyboardShowMode != userState.mSoftKeyboardShowMode) {
            userState.mSoftKeyboardShowMode = softKeyboardShowMode;
            return true;
        }
        return false;
    }

    private void updateTouchExplorationLocked(UserState userState) {
        boolean enabled = false;
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            Service service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsTouchExplorationLocked(service)) {
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

    private boolean canRequestAndRequestsTouchExplorationLocked(Service service) {
        // Service not ready or cannot request the feature - well nothing to do.
        if (!service.canReceiveEventsLocked() || !service.mRequestTouchExplorationMode) {
            return false;
        }
        // UI test automation service can always enable it.
        if (service.mIsAutomation) {
            return true;
        }
        if (service.mResolveInfo.serviceInfo.applicationInfo.targetSdkVersion
                <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Up to JB-MR1 we had a white list with services that can enable touch
            // exploration. When a service is first started we show a dialog to the
            // use to get a permission to white list the service.
            UserState userState = getUserStateLocked(service.mUserId);
            if (userState.mTouchExplorationGrantedServices.contains(service.mComponentName)) {
                return true;
            } else if (mEnableTouchExplorationDialog == null
                    || !mEnableTouchExplorationDialog.isShowing()) {
                mMainHandler.obtainMessage(
                        MainHandler.MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG,
                        service).sendToTarget();
            }
        } else {
            // Starting in JB-MR2 we request an accessibility service to declare
            // certain capabilities in its meta-data to allow it to enable the
            // corresponding features.
            if ((service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0) {
                return true;
            }
        }
        return false;
    }

    private void updateEnhancedWebAccessibilityLocked(UserState userState) {
        boolean enabled = false;
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            Service service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsEnhancedWebAccessibilityLocked(service)) {
                enabled = true;
                break;
            }
        }
        if (enabled != userState.mIsEnhancedWebAccessibilityEnabled) {
            userState.mIsEnhancedWebAccessibilityEnabled = enabled;
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION, enabled ? 1 : 0,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean canRequestAndRequestsEnhancedWebAccessibilityLocked(Service service) {
        if (!service.canReceiveEventsLocked() || !service.mRequestEnhancedWebAccessibility ) {
            return false;
        }
        if (service.mIsAutomation || (service.mAccessibilityServiceInfo.getCapabilities()
               & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY) != 0) {
            return true;
        }
        return false;
    }

    private void updateDisplayDaltonizerLocked(UserState userState) {
        DisplayAdjustmentUtils.applyDaltonizerSetting(mContext, userState.mUserId);
    }

    private void updateDisplayInversionLocked(UserState userState) {
        DisplayAdjustmentUtils.applyInversionSetting(mContext, userState.mUserId);
    }

    private void updateMagnificationLocked(UserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }

        if (userState.mIsDisplayMagnificationEnabled ||
                userHasListeningMagnificationServicesLocked(userState)) {
            // Initialize the magnification controller if necessary
            getMagnificationController();
            mMagnificationController.register();
        } else if (mMagnificationController != null) {
            mMagnificationController.unregister();
        }
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification.
     */
    private boolean userHasMagnificationServicesLocked(UserState userState) {
        final List<Service> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final Service service = services.get(i);
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
    private boolean userHasListeningMagnificationServicesLocked(UserState userState) {
        final List<Service> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final Service service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)
                    && service.mInvocationHandler.mIsMagnificationCallbackEnabled) {
                return true;
            }
        }
        return false;
    }

    private void updateSoftKeyboardShowModeLocked(UserState userState) {
        final int userId = userState.mUserId;
        // Only check whether we need to reset the soft keyboard mode if it is not set to the
        // default.
        if ((userId == mCurrentUserId) && (userState.mSoftKeyboardShowMode != 0)) {
            // Check whether the last Accessibility Service that changed the soft keyboard mode to
            // something other than the default is still enabled and, if not, remove flag and
            // reset to the default soft keyboard behavior.
            boolean serviceChangingSoftKeyboardModeIsEnabled =
                    userState.mEnabledServices.contains(userState.mServiceChangingSoftKeyboardMode);

            if (!serviceChangingSoftKeyboardModeIsEnabled) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                            0,
                            userState.mUserId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                userState.mSoftKeyboardShowMode = 0;
                userState.mServiceChangingSoftKeyboardMode = null;
                notifySoftKeyboardShowModeChangedLocked(userState.mSoftKeyboardShowMode);
            }
        }
    }

    private MagnificationSpec getCompatibleMagnificationSpecLocked(int windowId) {
        IBinder windowToken = mGlobalWindowTokens.get(windowId);
        if (windowToken == null) {
            windowToken = getCurrentUserStateLocked().mWindowTokens.get(windowId);
        }
        if (windowToken != null) {
            return mWindowManagerService.getCompatibleMagnificationSpecForWindow(
                    windowToken);
        }
        return null;
    }

    private KeyEventDispatcher getKeyEventDispatcher() {
        if (mKeyEventDispatcher == null) {
            mKeyEventDispatcher = new KeyEventDispatcher(
                    mMainHandler, MainHandler.MSG_SEND_KEY_EVENT_TO_INPUT_FILTER, mLock,
                    mPowerManager);
        }
        return mKeyEventDispatcher;
    }

    /**
     * Enables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    public void enableAccessibilityService(ComponentName componentName, int userId) {
        synchronized(mLock) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("only SYSTEM can call enableAccessibilityService.");
            }

            SettingsStringHelper settingsHelper = new SettingsStringHelper(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, userId);
            settingsHelper.addService(componentName);
            settingsHelper.writeToSettings();

            UserState userState = getUserStateLocked(userId);
            if (userState.mEnabledServices.add(componentName)) {
                onUserStateChangedLocked(userState);
            }
        }
    }

    /**
     * Disables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    public void disableAccessibilityService(ComponentName componentName, int userId) {
        synchronized(mLock) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("only SYSTEM can call disableAccessibility");
            }

            SettingsStringHelper settingsHelper = new SettingsStringHelper(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, userId);
            settingsHelper.deleteService(componentName);
            settingsHelper.writeToSettings();

            UserState userState = getUserStateLocked(userId);
            if (userState.mEnabledServices.remove(componentName)) {
                onUserStateChangedLocked(userState);
            }
        }
    }

    private class SettingsStringHelper {
        private static final String SETTINGS_DELIMITER = ":";
        private ContentResolver mContentResolver;
        private final String mSettingsName;
        private Set<String> mServices;
        private final int mUserId;

        public SettingsStringHelper(String name, int userId) {
            mUserId = userId;
            mSettingsName = name;
            mContentResolver = mContext.getContentResolver();
            String servicesString = Settings.Secure.getStringForUser(
                    mContentResolver, mSettingsName, userId);
            mServices = new HashSet();
            if (!TextUtils.isEmpty(servicesString)) {
                final TextUtils.SimpleStringSplitter colonSplitter =
                        new TextUtils.SimpleStringSplitter(SETTINGS_DELIMITER.charAt(0));
                colonSplitter.setString(servicesString);
                while (colonSplitter.hasNext()) {
                    final String serviceName = colonSplitter.next();
                    mServices.add(serviceName);
                }
            }
        }

        public void addService(ComponentName component) {
            mServices.add(component.flattenToString());
        }

        public void deleteService(ComponentName component) {
            mServices.remove(component.flattenToString());
        }

        public void writeToSettings() {
            Settings.Secure.putStringForUser(mContentResolver, mSettingsName,
                    TextUtils.join(SETTINGS_DELIMITER, mServices), mUserId);
        }
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.DUMP, FUNCTION_DUMP);
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
                pw.append(", autoclickEnabled=" + userState.mIsAutoclickEnabled);
                if (userState.mUiAutomationService != null) {
                    pw.append(", ");
                    userState.mUiAutomationService.dump(fd, pw, args);
                    pw.println();
                }
                pw.append("}");
                pw.println();
                pw.append("           services:{");
                final int serviceCount = userState.mBoundServices.size();
                for (int j = 0; j < serviceCount; j++) {
                    if (j > 0) {
                        pw.append(", ");
                        pw.println();
                        pw.append("                     ");
                    }
                    Service service = userState.mBoundServices.get(j);
                    service.dump(fd, pw, args);
                }
                pw.println("}]");
                pw.println();
            }
            if (mSecurityPolicy.mWindows != null) {
                final int windowCount = mSecurityPolicy.mWindows.size();
                for (int j = 0; j < windowCount; j++) {
                    if (j > 0) {
                        pw.append(',');
                        pw.println();
                    }
                    pw.append("Window[");
                    AccessibilityWindowInfo window = mSecurityPolicy.mWindows.get(j);
                    pw.append(window.toString());
                    pw.append(']');
                }
            }
        }
    }

    class RemoteAccessibilityConnection implements DeathRecipient {
        private final int mUid;
        private final String mPackageName;
        private final int mWindowId;
        private final int mUserId;
        private final IAccessibilityInteractionConnection mConnection;

        RemoteAccessibilityConnection(int windowId,
                IAccessibilityInteractionConnection connection,
                String packageName, int uid, int userId) {
            mWindowId = windowId;
            mPackageName = packageName;
            mUid = uid;
            mUserId = userId;
            mConnection = connection;
        }

        public int getUid() {
            return  mUid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public IAccessibilityInteractionConnection getRemote() {
            return mConnection;
        }

        public void linkToDeath() throws RemoteException {
            mConnection.asBinder().linkToDeath(this, 0);
        }

        public void unlinkToDeath() {
            mConnection.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            synchronized (mLock) {
                removeAccessibilityInteractionConnectionLocked(mWindowId, mUserId);
            }
        }
    }

    private final class MainHandler extends Handler {
        public static final int MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER = 1;
        public static final int MSG_SEND_STATE_TO_CLIENTS = 2;
        public static final int MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER = 3;
        public static final int MSG_ANNOUNCE_NEW_USER_IF_NEEDED = 5;
        public static final int MSG_UPDATE_INPUT_FILTER = 6;
        public static final int MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG = 7;
        public static final int MSG_SEND_KEY_EVENT_TO_INPUT_FILTER = 8;
        public static final int MSG_CLEAR_ACCESSIBILITY_FOCUS = 9;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int type = msg.what;
            switch (type) {
                case MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER: {
                    AccessibilityEvent event = (AccessibilityEvent) msg.obj;
                    synchronized (mLock) {
                        if (mHasInputFilter && mInputFilter != null) {
                            mInputFilter.notifyAccessibilityEvent(event);
                        }
                    }
                    event.recycle();
                } break;

                case MSG_SEND_KEY_EVENT_TO_INPUT_FILTER: {
                    KeyEvent event = (KeyEvent) msg.obj;
                    final int policyFlags = msg.arg1;
                    synchronized (mLock) {
                        if (mHasInputFilter && mInputFilter != null) {
                            mInputFilter.sendInputEvent(event, policyFlags);
                        }
                    }
                    event.recycle();
                } break;

                case MSG_SEND_STATE_TO_CLIENTS: {
                    final int clientState = msg.arg1;
                    final int userId = msg.arg2;
                    sendStateToClients(clientState, mGlobalClients);
                    sendStateToClientsForUser(clientState, userId);
                } break;

                case MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER: {
                    final int userId = msg.arg1;
                    sendStateToClientsForUser(0, userId);
                } break;

                case MSG_ANNOUNCE_NEW_USER_IF_NEEDED: {
                    announceNewUserIfNeeded();
                } break;

                case MSG_UPDATE_INPUT_FILTER: {
                    UserState userState = (UserState) msg.obj;
                    updateInputFilter(userState);
                } break;

                case MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG: {
                    Service service = (Service) msg.obj;
                    showEnableTouchExplorationDialog(service);
                } break;

                case MSG_CLEAR_ACCESSIBILITY_FOCUS: {
                    final int windowId = msg.arg1;
                    InteractionBridge bridge;
                    synchronized (mLock) {
                        bridge = getInteractionBridgeLocked();
                    }
                    bridge.clearAccessibilityFocusNotLocked(windowId);
                } break;
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
                    sendAccessibilityEvent(event, mCurrentUserId);
                }
            }
        }

        private void sendStateToClientsForUser(int clientState, int userId) {
            final UserState userState;
            synchronized (mLock) {
                userState = getUserStateLocked(userId);
            }
            sendStateToClients(clientState, userState.mClients);
        }

        private void sendStateToClients(int clientState,
                RemoteCallbackList<IAccessibilityManagerClient> clients) {
            try {
                final int userClientCount = clients.beginBroadcast();
                for (int i = 0; i < userClientCount; i++) {
                    IAccessibilityManagerClient client = clients.getBroadcastItem(i);
                    try {
                        client.setState(clientState);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                }
            } finally {
                clients.finishBroadcast();
            }
        }
    }

    private int findWindowIdLocked(IBinder token) {
        final int globalIndex = mGlobalWindowTokens.indexOfValue(token);
        if (globalIndex >= 0) {
            return mGlobalWindowTokens.keyAt(globalIndex);
        }
        UserState userState = getCurrentUserStateLocked();
        final int userIndex = userState.mWindowTokens.indexOfValue(token);
        if (userIndex >= 0) {
            return userState.mWindowTokens.keyAt(userIndex);
        }
        return -1;
    }

    private void ensureWindowsAvailableTimed() {
        synchronized (mLock) {
            if (mSecurityPolicy.mWindows != null) {
                return;
            }
            // If we have no registered callback, update the state we
            // we may have to register one but it didn't happen yet.
            if (mWindowsForAccessibilityCallback == null) {
                UserState userState = getCurrentUserStateLocked();
                onUserStateChangedLocked(userState);
            }
            // We have no windows but do not care about them, done.
            if (mWindowsForAccessibilityCallback == null) {
                return;
            }

            // Wait for the windows with a timeout.
            final long startMillis = SystemClock.uptimeMillis();
            while (mSecurityPolicy.mWindows == null) {
                final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
                final long remainMillis = WAIT_WINDOWS_TIMEOUT_MILLIS - elapsedMillis;
                if (remainMillis <= 0) {
                    return;
                }
                try {
                    mLock.wait(remainMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
    }

    MagnificationController getMagnificationController() {
        synchronized (mLock) {
            if (mMagnificationController == null) {
                mMagnificationController = new MagnificationController(mContext, this, mLock);
                mMagnificationController.setUserId(mCurrentUserId);
            }
            return mMagnificationController;
        }
    }

    /**
     * This class represents an accessibility service. It stores all per service
     * data required for the service management, provides API for starting/stopping the
     * service and is responsible for adding/removing the service in the data structures
     * for service management. The class also exposes configuration interface that is
     * passed to the service it represents as soon it is bound. It also serves as the
     * connection for the service.
     */
    class Service extends IAccessibilityServiceConnection.Stub
            implements ServiceConnection, DeathRecipient {;

        final int mUserId;

        int mId = 0;

        AccessibilityServiceInfo mAccessibilityServiceInfo;

        // The service that's bound to this instance. Whenever this value is non-null, this
        // object is registered as a death recipient
        IBinder mService;

        IAccessibilityServiceClient mServiceInterface;

        int mEventTypes;

        int mFeedbackType;

        Set<String> mPackageNames = new HashSet<>();

        boolean mIsDefault;

        boolean mRequestTouchExplorationMode;

        boolean mRequestEnhancedWebAccessibility;

        boolean mRequestFilterKeyEvents;

        boolean mRetrieveInteractiveWindows;

        int mFetchFlags;

        long mNotificationTimeout;

        ComponentName mComponentName;

        Intent mIntent;

        boolean mIsAutomation;

        final ResolveInfo mResolveInfo;

        final IBinder mOverlayWindowToken = new Binder();

        // the events pending events to be dispatched to this service
        final SparseArray<AccessibilityEvent> mPendingEvents =
            new SparseArray<>();

        boolean mWasConnectedAndDied;

        // Handler only for dispatching accessibility events since we use event
        // types as message types allowing us to remove messages per event type.
        public Handler mEventDispatchHandler = new Handler(mMainHandler.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                final int eventType =  message.what;
                AccessibilityEvent event = (AccessibilityEvent) message.obj;
                notifyAccessibilityEventInternal(eventType, event);
            }
        };

        // Handler for scheduling method invocations on the main thread.
        public final InvocationHandler mInvocationHandler = new InvocationHandler(
                mMainHandler.getLooper());

        public Service(int userId, ComponentName componentName,
                AccessibilityServiceInfo accessibilityServiceInfo) {
            mUserId = userId;
            mResolveInfo = accessibilityServiceInfo.getResolveInfo();
            mId = sIdCounter++;
            mComponentName = componentName;
            mAccessibilityServiceInfo = accessibilityServiceInfo;
            mIsAutomation = (sFakeAccessibilityServiceComponentName.equals(componentName));
            if (!mIsAutomation) {
                mIntent = new Intent().setComponent(mComponentName);
                mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                        com.android.internal.R.string.accessibility_binding_label);
                final long idendtity = Binder.clearCallingIdentity();
                try {
                    mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                            mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
                } finally {
                    Binder.restoreCallingIdentity(idendtity);
                }
            }
            setDynamicallyConfigurableProperties(accessibilityServiceInfo);
        }

        public void setDynamicallyConfigurableProperties(AccessibilityServiceInfo info) {
            mEventTypes = info.eventTypes;
            mFeedbackType = info.feedbackType;
            String[] packageNames = info.packageNames;
            if (packageNames != null) {
                mPackageNames.addAll(Arrays.asList(packageNames));
            }
            mNotificationTimeout = info.notificationTimeout;
            mIsDefault = (info.flags & DEFAULT) != 0;

            if (mIsAutomation || info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                    >= Build.VERSION_CODES.JELLY_BEAN) {
                if ((info.flags & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0) {
                    mFetchFlags |= AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                } else {
                    mFetchFlags &= ~AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                }
            }

            if ((info.flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0) {
                mFetchFlags |= AccessibilityNodeInfo.FLAG_REPORT_VIEW_IDS;
            } else {
                mFetchFlags &= ~AccessibilityNodeInfo.FLAG_REPORT_VIEW_IDS;
            }

            mRequestTouchExplorationMode = (info.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
            mRequestEnhancedWebAccessibility = (info.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY) != 0;
            mRequestFilterKeyEvents = (info.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
            mRetrieveInteractiveWindows = (info.flags
                    & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
        }

        /**
         * Binds to the accessibility service.
         *
         * @return True if binding is successful.
         */
        public boolean bindLocked() {
            UserState userState = getUserStateLocked(mUserId);
            if (!mIsAutomation) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (mService == null && mContext.bindServiceAsUser(
                            mIntent, this,
                            Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                            new UserHandle(mUserId))) {
                        userState.mBindingServices.add(mComponentName);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                userState.mBindingServices.add(mComponentName);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Simulate asynchronous connection since in onServiceConnected
                        // we may modify the state data in case of an error but bind is
                        // called while iterating over the data and bad things can happen.
                        onServiceConnected(mComponentName,
                                userState.mUiAutomationServiceClient.asBinder());
                    }
                });
                userState.mUiAutomationService = this;
            }
            return false;
        }

        /**
         * Unbinds from the accessibility service and removes it from the data
         * structures for service management.
         *
         * @return True if unbinding is successful.
         */
        public boolean unbindLocked() {
            UserState userState = getUserStateLocked(mUserId);
            getKeyEventDispatcher().flush(this);
            if (!mIsAutomation) {
                mContext.unbindService(this);
            } else {
                userState.destroyUiAutomationService();
            }
            removeServiceLocked(this, userState);
            resetLocked();
            return true;
        }

        @Override
        public void disableSelf() {
            synchronized(mLock) {
                UserState userState = getUserStateLocked(mUserId);
                if (userState.mEnabledServices.remove(mComponentName)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        persistComponentNamesToSettingLocked(
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                userState.mEnabledServices, mUserId);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                    onUserStateChangedLocked(userState);
                }
            }
        }

        public boolean canReceiveEventsLocked() {
            return (mEventTypes != 0 && mFeedbackType != 0 && mService != null);
        }

        @Override
        public void setOnKeyEventResult(boolean handled, int sequence) {
            getKeyEventDispatcher().setOnKeyEventResult(this, handled, sequence);
        }

        @Override
        public AccessibilityServiceInfo getServiceInfo() {
            synchronized (mLock) {
                return mAccessibilityServiceInfo;
            }
        }

        public boolean canRetrieveInteractiveWindowsLocked() {
            return mSecurityPolicy.canRetrieveWindowContentLocked(this)
                    && mRetrieveInteractiveWindows;
        }

        @Override
        public void setServiceInfo(AccessibilityServiceInfo info) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // If the XML manifest had data to configure the service its info
                    // should be already set. In such a case update only the dynamically
                    // configurable properties.
                    AccessibilityServiceInfo oldInfo = mAccessibilityServiceInfo;
                    if (oldInfo != null) {
                        oldInfo.updateDynamicallyConfigurableProperties(info);
                        setDynamicallyConfigurableProperties(oldInfo);
                    } else {
                        setDynamicallyConfigurableProperties(info);
                    }
                    UserState userState = getUserStateLocked(mUserId);
                    onUserStateChangedLocked(userState);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            synchronized (mLock) {
                if (mService != service) {
                    if (mService != null) {
                        mService.unlinkToDeath(this, 0);
                    }
                    mService = service;
                    try {
                        mService.linkToDeath(this, 0);
                    } catch (RemoteException re) {
                        Slog.e(LOG_TAG, "Failed registering death link");
                        binderDied();
                        return;
                    }
                }
                mServiceInterface = IAccessibilityServiceClient.Stub.asInterface(service);
                UserState userState = getUserStateLocked(mUserId);
                addServiceLocked(this, userState);
                if (userState.mBindingServices.contains(mComponentName) || mWasConnectedAndDied) {
                    userState.mBindingServices.remove(mComponentName);
                    mWasConnectedAndDied = false;
                    try {
                       mServiceInterface.init(this, mId, mOverlayWindowToken);
                       onUserStateChangedLocked(userState);
                    } catch (RemoteException re) {
                        Slog.w(LOG_TAG, "Error while setting connection for service: "
                                + service, re);
                        binderDied();
                    }
                } else {
                    binderDied();
                }
            }
        }

        private boolean isCalledForCurrentUserLocked() {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(UserHandle.USER_CURRENT);
            return resolvedUserId == mCurrentUserId;
        }

        @Override
        public List<AccessibilityWindowInfo> getWindows() {
            ensureWindowsAvailableTimed();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                final boolean permissionGranted =
                        mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (!permissionGranted) {
                    return null;
                }
                if (mSecurityPolicy.mWindows == null) {
                    return null;
                }
                List<AccessibilityWindowInfo> windows = new ArrayList<>();
                final int windowCount = mSecurityPolicy.mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mSecurityPolicy.mWindows.get(i);
                    AccessibilityWindowInfo windowClone =
                            AccessibilityWindowInfo.obtain(window);
                    windowClone.setConnectionId(mId);
                    windows.add(windowClone);
                }
                return windows;
            }
        }

        @Override
        public AccessibilityWindowInfo getWindow(int windowId) {
            ensureWindowsAvailableTimed();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                final boolean permissionGranted =
                        mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (!permissionGranted) {
                    return null;
                }
                AccessibilityWindowInfo window = mSecurityPolicy.findWindowById(windowId);
                if (window != null) {
                    AccessibilityWindowInfo windowClone = AccessibilityWindowInfo.obtain(window);
                    windowClone.setConnectionId(mId);
                    return windowClone;
                }
                return null;
            }
        }

        @Override
        public String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
                long accessibilityNodeId, String viewIdResName, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection = null;
            Region partialInteractiveRegion = Region.obtain();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                        mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion.recycle();
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.getRemote().findAccessibilityNodeInfosByViewId(accessibilityNodeId,
                        viewIdResName, partialInteractiveRegion, interactionId, callback,
                        mFetchFlags, interrogatingPid, interrogatingTid, spec);
                return mSecurityPolicy.computeValidReportedPackages(callingUid,
                        connection.getPackageName(), connection.getUid());
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error findAccessibilityNodeInfoByViewId().");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
                // Recycle if passed to another process.
                if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                    partialInteractiveRegion.recycle();
                }
            }
            return null;
        }

        @Override
        public String[] findAccessibilityNodeInfosByText(int accessibilityWindowId,
                long accessibilityNodeId, String text, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection = null;
            Region partialInteractiveRegion = Region.obtain();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion.recycle();
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.getRemote().findAccessibilityNodeInfosByText(accessibilityNodeId, text,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags,
                        interrogatingPid, interrogatingTid, spec);
                return mSecurityPolicy.computeValidReportedPackages(callingUid,
                        connection.getPackageName(), connection.getUid());
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfosByText()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
                // Recycle if passed to another process.
                if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                    partialInteractiveRegion.recycle();
                }
            }
            return null;
        }

        @Override
        public String[] findAccessibilityNodeInfoByAccessibilityId(
                int accessibilityWindowId, long accessibilityNodeId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                long interrogatingTid) throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection = null;
            Region partialInteractiveRegion = Region.obtain();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion.recycle();
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.getRemote().findAccessibilityNodeInfoByAccessibilityId(
                        accessibilityNodeId, partialInteractiveRegion, interactionId, callback,
                        mFetchFlags | flags, interrogatingPid, interrogatingTid, spec);
                return mSecurityPolicy.computeValidReportedPackages(callingUid,
                        connection.getPackageName(), connection.getUid());
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
                // Recycle if passed to another process.
                if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                    partialInteractiveRegion.recycle();
                }
            }
            return null;
        }

        @Override
        public String[] findFocus(int accessibilityWindowId, long accessibilityNodeId,
                int focusType, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection = null;
            Region partialInteractiveRegion = Region.obtain();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(
                        accessibilityWindowId, focusType);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion.recycle();
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.getRemote().findFocus(accessibilityNodeId, focusType,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags,
                        interrogatingPid, interrogatingTid, spec);
                return mSecurityPolicy.computeValidReportedPackages(callingUid,
                        connection.getPackageName(), connection.getUid());
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findFocus()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
                // Recycle if passed to another process.
                if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                    partialInteractiveRegion.recycle();
                }
            }
            return null;
        }

        @Override
        public String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId,
                int direction, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection = null;
            Region partialInteractiveRegion = Region.obtain();
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion.recycle();
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.getRemote().focusSearch(accessibilityNodeId, direction,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags,
                        interrogatingPid, interrogatingTid, spec);
                return mSecurityPolicy.computeValidReportedPackages(callingUid,
                        connection.getPackageName(), connection.getUid());
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling accessibilityFocusSearch()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
                // Recycle if passed to another process.
                if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                    partialInteractiveRegion.recycle();
                }
            }
            return null;
        }

        @Override
        public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
            synchronized (mLock) {
                if (mSecurityPolicy.canPerformGestures(this)) {
                    final long endMillis =
                            SystemClock.uptimeMillis() + WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS;
                    while ((mMotionEventInjector == null)
                            && (SystemClock.uptimeMillis() < endMillis)) {
                        try {
                            mLock.wait(endMillis - SystemClock.uptimeMillis());
                        } catch (InterruptedException ie) {
                            /* ignore */
                        }
                    }
                    if (mMotionEventInjector != null) {
                        List<GestureDescription.GestureStep> steps = gestureSteps.getList();
                        List<MotionEvent> events = GestureDescription.MotionEventGenerator
                                .getMotionEventsFromGestureSteps(steps);
                        // Confirm that the motion events end with an UP event.
                        if (events.get(events.size() - 1).getAction() == MotionEvent.ACTION_UP) {
                            mMotionEventInjector.injectEvents(events, mServiceInterface, sequence);
                            return;
                        } else {
                            Slog.e(LOG_TAG, "Gesture is not well-formed");
                        }
                    } else {
                        Slog.e(LOG_TAG, "MotionEventInjector installation timed out");
                    }
                }
            }
            try {
                mServiceInterface.onPerformGestureResult(sequence, false);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending motion event injection failure to "
                        + mServiceInterface, re);
            }
        }

        @Override
        public boolean performAccessibilityAction(int accessibilityWindowId,
                long accessibilityNodeId, int action, Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            RemoteAccessibilityConnection connection;
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted = mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                        this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                // Regardless of whether or not the action succeeds, it was generated by an
                // accessibility service that is driven by user actions, so note user activity.
                mPowerManager.userActivity(SystemClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY, 0);

                connection.mConnection.performAccessibilityAction(accessibilityNodeId, action,
                        arguments, interactionId, callback, mFetchFlags, interrogatingPid,
                        interrogatingTid);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling performAccessibilityAction()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return true;
        }

        @Override
        public boolean performGlobalAction(int action) {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return false;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                mPowerManager.userActivity(SystemClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY, 0);
                switch (action) {
                    case AccessibilityService.GLOBAL_ACTION_BACK: {
                        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_BACK);
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_HOME: {
                        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HOME);
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_RECENTS: {
                        return openRecents();
                    }
                    case AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS: {
                        expandNotifications();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS: {
                        expandQuickSettings();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_POWER_DIALOG: {
                        showGlobalActions();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN: {
                        toggleSplitScreen();
                    } return true;
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public float getMagnificationScale() {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return 1.0f;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return getMagnificationController().getScale();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Region getMagnificationRegion() {
            synchronized (mLock) {
                final Region region = Region.obtain();
                if (!isCalledForCurrentUserLocked()) {
                    return region;
                }
                MagnificationController magnificationController = getMagnificationController();
                boolean forceRegistration = mSecurityPolicy.canControlMagnification(this);
                boolean initiallyRegistered = magnificationController.isRegisteredLocked();
                if (!initiallyRegistered && forceRegistration) {
                    magnificationController.register();
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    magnificationController.getMagnificationRegion(region);
                    return region;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                    if (!initiallyRegistered && forceRegistration) {
                        magnificationController.unregister();
                    }
                }
            }
        }

        @Override
        public float getMagnificationCenterX() {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return 0.0f;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return getMagnificationController().getCenterX();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public float getMagnificationCenterY() {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return 0.0f;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return getMagnificationController().getCenterY();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean resetMagnification(boolean animate) {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return false;
                }
                final boolean permissionGranted = mSecurityPolicy.canControlMagnification(this);
                if (!permissionGranted) {
                    return false;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return getMagnificationController().reset(animate);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean setMagnificationScaleAndCenter(float scale, float centerX, float centerY,
                boolean animate) {
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return false;
                }
                final boolean permissionGranted = mSecurityPolicy.canControlMagnification(this);
                if (!permissionGranted) {
                    return false;
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    MagnificationController magnificationController = getMagnificationController();
                    if (!magnificationController.isRegisteredLocked()) {
                        magnificationController.register();
                    }
                    return magnificationController
                            .setScaleAndCenter(scale, centerX, centerY, animate, mId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void setMagnificationCallbackEnabled(boolean enabled) {
            mInvocationHandler.setMagnificationCallbackEnabled(enabled);
        }

        @Override
        public boolean setSoftKeyboardShowMode(int showMode) {
            final UserState userState;
            synchronized (mLock) {
                if (!isCalledForCurrentUserLocked()) {
                    return false;
                }

                userState = getCurrentUserStateLocked();
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                // Keep track of the last service to request a non-default show mode. The show mode
                // should be restored to default should this service be disabled.
                if (showMode == Settings.Secure.SHOW_MODE_AUTO) {
                    userState.mServiceChangingSoftKeyboardMode = null;
                } else {
                    userState.mServiceChangingSoftKeyboardMode = mComponentName;
                }

                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, showMode,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return true;
        }

        @Override
        public void setSoftKeyboardCallbackEnabled(boolean enabled) {
            mInvocationHandler.setSoftKeyboardCallbackEnabled(enabled);
        }

        @Override
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            mSecurityPolicy.enforceCallingPermission(Manifest.permission.DUMP, FUNCTION_DUMP);
            synchronized (mLock) {
                pw.append("Service[label=" + mAccessibilityServiceInfo.getResolveInfo()
                        .loadLabel(mContext.getPackageManager()));
                pw.append(", feedbackType"
                        + AccessibilityServiceInfo.feedbackTypeToString(mFeedbackType));
                pw.append(", capabilities=" + mAccessibilityServiceInfo.getCapabilities());
                pw.append(", eventTypes="
                        + AccessibilityEvent.eventTypeToString(mEventTypes));
                pw.append(", notificationTimeout=" + mNotificationTimeout);
                pw.append("]");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            binderDied();
        }

        public void onAdded() throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mWindowManagerService.addWindowToken(mOverlayWindowToken,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void onRemoved() {
            final long identity = Binder.clearCallingIdentity();
            try {
                mWindowManagerService.removeWindowToken(mOverlayWindowToken, true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void resetLocked() {
            try {
                // Clear the proxy in the other process so this
                // IAccessibilityServiceConnection can be garbage collected.
                if (mServiceInterface != null) {
                    mServiceInterface.init(null, mId, null);
                }
            } catch (RemoteException re) {
                /* ignore */
            }
            if (mService != null) {
                mService.unlinkToDeath(this, 0);
                mService = null;
            }
            mServiceInterface = null;
        }

        public boolean isConnectedLocked() {
            return (mService != null);
        }

        public void binderDied() {
            synchronized (mLock) {
                // It is possible that this service's package was force stopped during
                // whose handling the death recipient is unlinked and still get a call
                // on binderDied since the call was made before we unlink but was
                // waiting on the lock we held during the force stop handling.
                if (!isConnectedLocked()) {
                    return;
                }
                mWasConnectedAndDied = true;
                getKeyEventDispatcher().flush(this);
                UserState userState = getUserStateLocked(mUserId);
                resetLocked();
                if (mIsAutomation) {
                    // This is typically done when unbinding, but UiAutomation isn't bound.
                    removeServiceLocked(this, userState);
                    // We no longer have an automation service, so restore
                    // the state based on values in the settings database.
                    userState.mInstalledServices.remove(mAccessibilityServiceInfo);
                    userState.mEnabledServices.remove(mComponentName);
                    userState.destroyUiAutomationService();
                    readConfigurationForUserStateLocked(userState);
                }
                if (mId == getMagnificationController().getIdOfLastServiceToMagnify()) {
                    getMagnificationController().resetIfNeeded(true);
                }
                onUserStateChangedLocked(userState);
            }
        }

        /**
         * Performs a notification for an {@link AccessibilityEvent}.
         *
         * @param event The event.
         */
        public void notifyAccessibilityEvent(AccessibilityEvent event) {
            synchronized (mLock) {
                final int eventType = event.getEventType();
                // Make a copy since during dispatch it is possible the event to
                // be modified to remove its source if the receiving service does
                // not have permission to access the window content.
                AccessibilityEvent newEvent = AccessibilityEvent.obtain(event);
                Message message;
                if ((mNotificationTimeout > 0)
                        && (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                    // Allow at most one pending event
                    final AccessibilityEvent oldEvent = mPendingEvents.get(eventType);
                    mPendingEvents.put(eventType, newEvent);
                    if (oldEvent != null) {
                        mEventDispatchHandler.removeMessages(eventType);
                        oldEvent.recycle();
                    }
                    message = mEventDispatchHandler.obtainMessage(eventType);
                } else {
                    // Send all messages, bypassing mPendingEvents
                    message = mEventDispatchHandler.obtainMessage(eventType, newEvent);
                }

                mEventDispatchHandler.sendMessageDelayed(message, mNotificationTimeout);
            }
        }

        /**
         * Notifies an accessibility service client for a scheduled event given the event type.
         *
         * @param eventType The type of the event to dispatch.
         */
        private void notifyAccessibilityEventInternal(int eventType, AccessibilityEvent event) {
            IAccessibilityServiceClient listener;

            synchronized (mLock) {
                listener = mServiceInterface;

                // If the service died/was disabled while the message for dispatching
                // the accessibility event was propagating the listener may be null.
                if (listener == null) {
                    return;
                }

                // There are two ways we notify for events, throttled and non-throttled. If we
                // are not throttling, then messages come with events, which we handle with
                // minimal fuss.
                if (event == null) {
                    // We are throttling events, so we'll send the event for this type in
                    // mPendingEvents as long as it it's null. It can only null due to a race
                    // condition:
                    //
                    //   1) A binder thread calls notifyAccessibilityServiceDelayedLocked
                    //      which posts a message for dispatching an event and stores the event
                    //      in mPendingEvents.
                    //   2) The message is pulled from the queue by the handler on the service
                    //      thread and this method is just about to acquire the lock.
                    //   3) Another binder thread acquires the lock in notifyAccessibilityEvent
                    //   4) notifyAccessibilityEvent recycles the event that this method was about
                    //      to process, replaces it with a new one, and posts a second message
                    //   5) This method grabs the new event, processes it, and removes it from
                    //      mPendingEvents
                    //   6) The second message dispatched in (4) arrives, but the event has been
                    //      remvoved in (5).
                    event = mPendingEvents.get(eventType);
                    if (event == null) {
                        return;
                    }
                    mPendingEvents.remove(eventType);
                }
                if (mSecurityPolicy.canRetrieveWindowContentLocked(this)) {
                    event.setConnectionId(mId);
                } else {
                    event.setSource(null);
                }
                event.setSealed(true);
            }

            try {
                listener.onAccessibilityEvent(event);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Event " + event + " sent to " + listener);
                }
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during sending " + event + " to " + listener, re);
            } finally {
                event.recycle();
            }
        }

        public void notifyGesture(int gestureId) {
            mInvocationHandler.obtainMessage(InvocationHandler.MSG_ON_GESTURE,
                    gestureId, 0).sendToTarget();
        }

        public void notifyClearAccessibilityNodeInfoCache() {
            mInvocationHandler.sendEmptyMessage(
                    InvocationHandler.MSG_CLEAR_ACCESSIBILITY_CACHE);
        }

        public void notifyMagnificationChangedLocked(@NonNull Region region,
                float scale, float centerX, float centerY) {
            mInvocationHandler
                    .notifyMagnificationChangedLocked(region, scale, centerX, centerY);
        }

        public void notifySoftKeyboardShowModeChangedLocked(int showState) {
            mInvocationHandler.notifySoftKeyboardShowModeChangedLocked(showState);
        }

        /**
         * Called by the invocation handler to notify the service that the
         * state of magnification has changed.
         */
        private void notifyMagnificationChangedInternal(@NonNull Region region,
                float scale, float centerX, float centerY) {
            final IAccessibilityServiceClient listener;
            synchronized (mLock) {
                listener = mServiceInterface;
            }
            if (listener != null) {
                try {
                    listener.onMagnificationChanged(region, scale, centerX, centerY);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error sending magnification changes to " + mService, re);
                }
            }
        }

        /**
         * Called by the invocation handler to notify the service that the state of the soft
         * keyboard show mode has changed.
         */
        private void notifySoftKeyboardShowModeChangedInternal(int showState) {
            final IAccessibilityServiceClient listener;
            synchronized (mLock) {
                listener = mServiceInterface;
            }
            if (listener != null) {
                try {
                    listener.onSoftKeyboardShowModeChanged(showState);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error sending soft keyboard show mode changes to " + mService,
                            re);
                }
            }
        }

        private void notifyGestureInternal(int gestureId) {
            final IAccessibilityServiceClient listener;
            synchronized (mLock) {
                listener = mServiceInterface;
            }
            if (listener != null) {
                try {
                    listener.onGesture(gestureId);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error during sending gesture " + gestureId
                            + " to " + mService, re);
                }
            }
        }

        private void notifyClearAccessibilityCacheInternal() {
            final IAccessibilityServiceClient listener;
            synchronized (mLock) {
                listener = mServiceInterface;
            }
            if (listener != null) {
                try {
                    listener.clearAccessibilityCache();
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error during requesting accessibility info cache"
                            + " to be cleared.", re);
                }
            }
        }

        private void sendDownAndUpKeyEvents(int keyCode) {
            final long token = Binder.clearCallingIdentity();

            // Inject down.
            final long downTime = SystemClock.uptimeMillis();
            KeyEvent down = KeyEvent.obtain(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD, null);
            InputManager.getInstance().injectInputEvent(down,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            down.recycle();

            // Inject up.
            final long upTime = SystemClock.uptimeMillis();
            KeyEvent up = KeyEvent.obtain(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD, null);
            InputManager.getInstance().injectInputEvent(up,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            up.recycle();

            Binder.restoreCallingIdentity(token);
        }

        private void expandNotifications() {
            final long token = Binder.clearCallingIdentity();

            StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                    android.app.Service.STATUS_BAR_SERVICE);
            statusBarManager.expandNotificationsPanel();

            Binder.restoreCallingIdentity(token);
        }

        private void expandQuickSettings() {
            final long token = Binder.clearCallingIdentity();

            StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                    android.app.Service.STATUS_BAR_SERVICE);
            statusBarManager.expandSettingsPanel();

            Binder.restoreCallingIdentity(token);
        }

        private boolean openRecents() {
            final long token = Binder.clearCallingIdentity();
            try {
                StatusBarManagerInternal statusBarService = LocalServices.getService(
                        StatusBarManagerInternal.class);
                if (statusBarService == null) {
                    return false;
                }
                statusBarService.toggleRecentApps();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return true;
        }

        private void showGlobalActions() {
            mWindowManagerService.showGlobalActions();
        }

        private void toggleSplitScreen() {
            LocalServices.getService(StatusBarManagerInternal.class).toggleSplitScreen();
        }

        private RemoteAccessibilityConnection getConnectionLocked(int windowId) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
            }
            RemoteAccessibilityConnection wrapper = mGlobalInteractionConnections.get(windowId);
            if (wrapper == null) {
                wrapper = getCurrentUserStateLocked().mInteractionConnections.get(windowId);
            }
            if (wrapper != null && wrapper.mConnection != null) {
                return wrapper;
            }
            if (DEBUG) {
                Slog.e(LOG_TAG, "No interaction connection to window: " + windowId);
            }
            return null;
        }

        private int resolveAccessibilityWindowIdLocked(int accessibilityWindowId) {
            if (accessibilityWindowId == AccessibilityNodeInfo.ACTIVE_WINDOW_ID) {
                return mSecurityPolicy.getActiveWindowId();
            }
            return accessibilityWindowId;
        }

        private int resolveAccessibilityWindowIdForFindFocusLocked(int windowId, int focusType) {
            if (windowId == AccessibilityNodeInfo.ACTIVE_WINDOW_ID) {
                return mSecurityPolicy.mActiveWindowId;
            }
            if (windowId == AccessibilityNodeInfo.ANY_WINDOW_ID) {
                if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
                    return mSecurityPolicy.mFocusedWindowId;
                } else if (focusType == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
                    return mSecurityPolicy.mAccessibilityFocusedWindowId;
                }
            }
            return windowId;
        }

        private final class InvocationHandler extends Handler {
            public static final int MSG_ON_GESTURE = 1;
            public static final int MSG_CLEAR_ACCESSIBILITY_CACHE = 2;

            private static final int MSG_ON_MAGNIFICATION_CHANGED = 5;
            private static final int MSG_ON_SOFT_KEYBOARD_STATE_CHANGED = 6;

            private boolean mIsMagnificationCallbackEnabled = false;
            private boolean mIsSoftKeyboardCallbackEnabled = false;

            public InvocationHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            public void handleMessage(Message message) {
                final int type = message.what;
                switch (type) {
                    case MSG_ON_GESTURE: {
                        final int gestureId = message.arg1;
                        notifyGestureInternal(gestureId);
                    } break;

                    case MSG_CLEAR_ACCESSIBILITY_CACHE: {
                        notifyClearAccessibilityCacheInternal();
                    } break;

                    case MSG_ON_MAGNIFICATION_CHANGED: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        final Region region = (Region) args.arg1;
                        final float scale = (float) args.arg2;
                        final float centerX = (float) args.arg3;
                        final float centerY = (float) args.arg4;
                        notifyMagnificationChangedInternal(region, scale, centerX, centerY);
                    } break;

                    case MSG_ON_SOFT_KEYBOARD_STATE_CHANGED: {
                        final int showState = (int) message.arg1;
                        notifySoftKeyboardShowModeChangedInternal(showState);
                    } break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: " + type);
                    }
                }
            }

            public void notifyMagnificationChangedLocked(@NonNull Region region, float scale,
                    float centerX, float centerY) {
                if (!mIsMagnificationCallbackEnabled) {
                    // Callback is disabled, don't bother packing args.
                    return;
                }

                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = region;
                args.arg2 = scale;
                args.arg3 = centerX;
                args.arg4 = centerY;

                final Message msg = obtainMessage(MSG_ON_MAGNIFICATION_CHANGED, args);
                msg.sendToTarget();
            }

            public void setMagnificationCallbackEnabled(boolean enabled) {
                mIsMagnificationCallbackEnabled = enabled;
            }

            public void notifySoftKeyboardShowModeChangedLocked(int showState) {
                if (!mIsSoftKeyboardCallbackEnabled) {
                    return;
                }

                final Message msg = obtainMessage(MSG_ON_SOFT_KEYBOARD_STATE_CHANGED, showState, 0);
                msg.sendToTarget();
            }

            public void setSoftKeyboardCallbackEnabled(boolean enabled) {
                mIsSoftKeyboardCallbackEnabled = enabled;
            }
        }
    }

    private AppWidgetManagerInternal getAppWidgetManager() {
        synchronized (mLock) {
            if (mAppWidgetService == null
                    && mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                mAppWidgetService = LocalServices.getService(AppWidgetManagerInternal.class);
            }
            return mAppWidgetService;
        }
    }

    final class WindowsForAccessibilityCallback implements
            WindowManagerInternal.WindowsForAccessibilityCallback {

        @Override
        public void onWindowsForAccessibilityChanged(List<WindowInfo> windows) {
            synchronized (mLock) {
                // Populate the windows to report.
                List<AccessibilityWindowInfo> reportedWindows = new ArrayList<>();
                final int receivedWindowCount = windows.size();
                for (int i = 0; i < receivedWindowCount; i++) {
                    WindowInfo receivedWindow = windows.get(i);
                    AccessibilityWindowInfo reportedWindow = populateReportedWindow(
                            receivedWindow);
                    if (reportedWindow != null) {
                        reportedWindows.add(reportedWindow);
                    }
                }

                if (DEBUG) {
                    Slog.i(LOG_TAG, "Windows changed: " + reportedWindows);
                }

                // Let the policy update the focused and active windows.
                mSecurityPolicy.updateWindowsLocked(reportedWindows);

                // Someone may be waiting for the windows - advertise it.
                mLock.notifyAll();
            }
        }

        private AccessibilityWindowInfo populateReportedWindow(WindowInfo window) {
            final int windowId = findWindowIdLocked(window.token);
            if (windowId < 0) {
                return null;
            }

            AccessibilityWindowInfo reportedWindow = AccessibilityWindowInfo.obtain();

            reportedWindow.setId(windowId);
            reportedWindow.setType(getTypeForWindowManagerWindowType(window.type));
            reportedWindow.setLayer(window.layer);
            reportedWindow.setFocused(window.focused);
            reportedWindow.setBoundsInScreen(window.boundsInScreen);
            reportedWindow.setTitle(window.title);
            reportedWindow.setAnchorId(window.accessibilityIdOfAnchor);

            final int parentId = findWindowIdLocked(window.parentToken);
            if (parentId >= 0) {
                reportedWindow.setParentId(parentId);
            }

            if (window.childTokens != null) {
                final int childCount = window.childTokens.size();
                for (int i = 0; i < childCount; i++) {
                    IBinder childToken = window.childTokens.get(i);
                    final int childId = findWindowIdLocked(childToken);
                    if (childId >= 0) {
                        reportedWindow.addChild(childId);
                    }
                }
            }

            return reportedWindow;
        }

        private int getTypeForWindowManagerWindowType(int windowType) {
            switch (windowType) {
                case WindowManager.LayoutParams.TYPE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_STARTING:
                case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_BASE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION:
                case WindowManager.LayoutParams.TYPE_PHONE:
                case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                case WindowManager.LayoutParams.TYPE_TOAST:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_APPLICATION;
                }

                case WindowManager.LayoutParams.TYPE_INPUT_METHOD:
                case WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                }

                case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SCREENSHOT: {
                    return AccessibilityWindowInfo.TYPE_SYSTEM;
                }

                case WindowManager.LayoutParams.TYPE_DOCK_DIVIDER: {
                    return AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER;
                }

                case WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
                }

                default: {
                    return -1;
                }
            }
        }
    }

    private final class InteractionBridge {
        private final Display mDefaultDisplay;
        private final int mConnectionId;
        private final AccessibilityInteractionClient mClient;

        public InteractionBridge() {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            Service service = new Service(UserHandle.USER_NULL,
                    sFakeAccessibilityServiceComponentName, info);

            mConnectionId = service.mId;

            mClient = AccessibilityInteractionClient.getInstance();
            mClient.addConnection(mConnectionId, service);

            //TODO: (multi-display) We need to support multiple displays.
            DisplayManager displayManager = (DisplayManager)
                    mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }

        public void clearAccessibilityFocusNotLocked(int windowId) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked(windowId);
            if (focus != null) {
                focus.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            }
        }

        public boolean getAccessibilityFocusClickPointInScreenNotLocked(Point outPoint) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if (focus == null) {
                return false;
            }

            synchronized (mLock) {
                Rect boundsInScreen = mTempRect;
                focus.getBoundsInScreen(boundsInScreen);

                // Apply magnification if needed.
                MagnificationSpec spec = getCompatibleMagnificationSpecLocked(focus.getWindowId());
                if (spec != null && !spec.isNop()) {
                    boundsInScreen.offset((int) -spec.offsetX, (int) -spec.offsetY);
                    boundsInScreen.scale(1 / spec.scale);
                }

                // Clip to the window bounds.
                Rect windowBounds = mTempRect1;
                getWindowBounds(focus.getWindowId(), windowBounds);
                if (!boundsInScreen.intersect(windowBounds)) {
                    return false;
                }

                // Clip to the screen bounds.
                Point screenSize = mTempPoint;
                mDefaultDisplay.getRealSize(screenSize);
                if (!boundsInScreen.intersect(0, 0, screenSize.x, screenSize.y)) {
                    return false;
                }

                outPoint.set(boundsInScreen.centerX(), boundsInScreen.centerY());
            }

            return true;
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked() {
            final int focusedWindowId;
            synchronized (mLock) {
                focusedWindowId = mSecurityPolicy.mAccessibilityFocusedWindowId;
                if (focusedWindowId == SecurityPolicy.INVALID_WINDOW_ID) {
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

    final class SecurityPolicy {
        public static final int INVALID_WINDOW_ID = -1;

        private static final int RETRIEVAL_ALLOWING_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED
            | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
            | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SELECTED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
            | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

        public List<AccessibilityWindowInfo> mWindows;

        public int mActiveWindowId = INVALID_WINDOW_ID;
        public int mFocusedWindowId = INVALID_WINDOW_ID;
        public int mAccessibilityFocusedWindowId = INVALID_WINDOW_ID;
        public long mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;

        private boolean mTouchInteractionInProgress;

        private boolean canDispatchAccessibilityEventLocked(AccessibilityEvent event) {
            final int eventType = event.getEventType();
            switch (eventType) {
                // All events that are for changes in a global window
                // state should *always* be dispatched.
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                case AccessibilityEvent.TYPE_ANNOUNCEMENT:
                // All events generated by the user touching the
                // screen should *always* be dispatched.
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT:
                // Also always dispatch the event that assist is reading context.
                case AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT:
                // Also windows changing should always be anounced.
                case AccessibilityEvent.TYPE_WINDOWS_CHANGED: {
                    return true;
                }
                // All events for changes in window content should be
                // dispatched *only* if this window is one of the windows
                // the accessibility layer reports which are windows
                // that a sighted user can touch.
                default: {
                    return isRetrievalAllowingWindow(event.getWindowId());
                }
            }
        }

        private boolean isValidPackageForUid(String packageName, int uid) {
            try {
                return uid == mPackageManager.getPackageUid(
                        packageName, UserHandle.getUserId(uid));
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        String resolveValidReportedPackageLocked(CharSequence packageName, int appId, int userId) {
            // Okay to pass no package
            if (packageName == null) {
                return null;
            }
            // The system gets to pass any package
            if (appId == Process.SYSTEM_UID) {
                return packageName.toString();
            }
            // Passing a package in your UID is fine
            final String packageNameStr = packageName.toString();
            final int resolvedUid = UserHandle.getUid(userId, appId);
            if (isValidPackageForUid(packageNameStr, resolvedUid)) {
                return packageName.toString();
            }
            // Appwidget hosts get to pass packages for widgets they host
            final AppWidgetManagerInternal appWidgetManager = getAppWidgetManager();
            if (appWidgetManager != null && ArrayUtils.contains(appWidgetManager
                            .getHostedWidgetPackages(resolvedUid), packageNameStr)) {
                return packageName.toString();
            }
            // Otherwise, set the package to the first one in the UID
            final String[] packageNames = mPackageManager.getPackagesForUid(resolvedUid);
            if (ArrayUtils.isEmpty(packageNames)) {
                return null;
            }
            // Okay, the caller reported a package it does not have access to.
            // Instead of crashing the caller for better backwards compatibility
            // we report the first package in the UID. Since most of the time apps
            // don't use shared user id, this will yield correct results and for
            // the edge case of using a shared user id we may report the wrong
            // package but this is fine since first, this is a cheating app and
            // second there is no way to get the correct package anyway.
            return packageNames[0];
        }

        String[] computeValidReportedPackages(int callingUid,
                String targetPackage, int targetUid) {
            if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
                // Empty array means any package is Okay
                return EmptyArray.STRING;
            }
            // IMPORTANT: The target package is already vetted to be in the target UID
            String[] uidPackages = new String[]{targetPackage};
            // Appwidget hosts get to pass packages for widgets they host
            final AppWidgetManagerInternal appWidgetManager = getAppWidgetManager();
            if (appWidgetManager != null) {
                final ArraySet<String> widgetPackages = appWidgetManager
                        .getHostedWidgetPackages(targetUid);
                if (widgetPackages != null && !widgetPackages.isEmpty()) {
                    final String[] validPackages = new String[uidPackages.length
                            + widgetPackages.size()];
                    System.arraycopy(uidPackages, 0, validPackages, 0, uidPackages.length);
                    final int widgetPackageCount = widgetPackages.size();
                    for (int i = 0; i < widgetPackageCount; i++) {
                        validPackages[uidPackages.length + i] = widgetPackages.valueAt(i);
                    }
                    return validPackages;
                }
            }
            return uidPackages;
        }

        public void clearWindowsLocked() {
            List<AccessibilityWindowInfo> windows = Collections.emptyList();
            final int activeWindowId = mActiveWindowId;
            updateWindowsLocked(windows);
            mActiveWindowId = activeWindowId;
            mWindows = null;
        }

        public void updateWindowsLocked(List<AccessibilityWindowInfo> windows) {
            if (mWindows == null) {
                mWindows = new ArrayList<>();
            }

            final int oldWindowCount = mWindows.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                mWindows.remove(i).recycle();
            }

            mFocusedWindowId = INVALID_WINDOW_ID;
            if (!mTouchInteractionInProgress) {
                mActiveWindowId = INVALID_WINDOW_ID;
            }

            // If the active window goes away while the user is touch exploring we
            // reset the active window id and wait for the next hover event from
            // under the user's finger to determine which one is the new one. It
            // is possible that the finger is not moving and the input system
            // filters out such events.
            boolean activeWindowGone = true;

            final int windowCount = windows.size();
            if (windowCount > 0) {
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = windows.get(i);
                    final int windowId = window.getId();
                    if (window.isFocused()) {
                        mFocusedWindowId = windowId;
                        if (!mTouchInteractionInProgress) {
                            mActiveWindowId = windowId;
                            window.setActive(true);
                        } else if (windowId == mActiveWindowId) {
                            activeWindowGone = false;
                        }
                    }
                    mWindows.add(window);
                }

                if (mTouchInteractionInProgress && activeWindowGone) {
                    mActiveWindowId = mFocusedWindowId;
                }

                // Focused window may change the active one, so set the
                // active window once we decided which it is.
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == mActiveWindowId) {
                        window.setActive(true);
                    }
                    if (window.getId() == mAccessibilityFocusedWindowId) {
                        window.setAccessibilityFocused(true);
                    }
                }
            }

            notifyWindowsChanged();
        }

        public boolean computePartialInteractiveRegionForWindowLocked(int windowId,
                Region outRegion) {
            if (mWindows == null) {
                return false;
            }

            // Windows are ordered in z order so start from the bottom and find
            // the window of interest. After that all windows that cover it should
            // be subtracted from the resulting region. Note that for accessibility
            // we are returning only interactive windows.
            Region windowInteractiveRegion = null;
            boolean windowInteractiveRegionChanged = false;

            final int windowCount = mWindows.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                AccessibilityWindowInfo currentWindow = mWindows.get(i);
                if (windowInteractiveRegion == null) {
                    if (currentWindow.getId() == windowId) {
                        Rect currentWindowBounds = mTempRect;
                        currentWindow.getBoundsInScreen(currentWindowBounds);
                        outRegion.set(currentWindowBounds);
                        windowInteractiveRegion = outRegion;
                        continue;
                    }
                } else if (currentWindow.getType()
                        != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    Rect currentWindowBounds = mTempRect;
                    currentWindow.getBoundsInScreen(currentWindowBounds);
                    if (windowInteractiveRegion.op(currentWindowBounds, Region.Op.DIFFERENCE)) {
                        windowInteractiveRegionChanged = true;
                    }
                }
            }

            return windowInteractiveRegionChanged;
        }

        public void updateEventSourceLocked(AccessibilityEvent event) {
            if ((event.getEventType() & RETRIEVAL_ALLOWING_EVENT_TYPES) == 0) {
                event.setSource(null);
            }
        }

        public void updateActiveAndAccessibilityFocusedWindowLocked(int windowId, long nodeId,
                int eventType, int eventAction) {
            // The active window is either the window that has input focus or
            // the window that the user is currently touching. If the user is
            // touching a window that does not have input focus as soon as the
            // the user stops touching that window the focused window becomes
            // the active one. Here we detect the touched window and make it
            // active. In updateWindowsLocked() we update the focused window
            // and if the user is not touching the screen, we make the focused
            // window the active one.
            switch (eventType) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    // If no service has the capability to introspect screen,
                    // we do not register callback in the window manager for
                    // window changes, so we have to ask the window manager
                    // what the focused window is to update the active one.
                    // The active window also determined events from which
                    // windows are delivered.
                    synchronized (mLock) {
                        if (mWindowsForAccessibilityCallback == null) {
                            mFocusedWindowId = getFocusedWindowId();
                            if (windowId == mFocusedWindowId) {
                                mActiveWindowId = windowId;
                            }
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: {
                    // Do not allow delayed hover events to confuse us
                    // which the active window is.
                    synchronized (mLock) {
                        if (mTouchInteractionInProgress && mActiveWindowId != windowId) {
                            setActiveWindowLocked(windowId);
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                    synchronized (mLock) {
                        if (mAccessibilityFocusedWindowId != windowId) {
                            mMainHandler.obtainMessage(MainHandler.MSG_CLEAR_ACCESSIBILITY_FOCUS,
                                    mAccessibilityFocusedWindowId, 0).sendToTarget();
                            mSecurityPolicy.setAccessibilityFocusedWindowLocked(windowId);
                            mAccessibilityFocusNodeId = nodeId;
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                    synchronized (mLock) {
                        if (mAccessibilityFocusNodeId == nodeId) {
                            mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                        }
                        // Clear the window with focus if it no longer has focus and we aren't
                        // just moving focus from one view to the other in the same window
                        if ((mAccessibilityFocusNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)
                                && (mAccessibilityFocusedWindowId == windowId)
                                && (eventAction != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                                ) {
                            mAccessibilityFocusedWindowId = INVALID_WINDOW_ID;
                        }
                    }
                } break;
            }
        }

        public void onTouchInteractionStart() {
            synchronized (mLock) {
                mTouchInteractionInProgress = true;
            }
        }

        public void onTouchInteractionEnd() {
            synchronized (mLock) {
                mTouchInteractionInProgress = false;
                // We want to set the active window to be current immediately
                // after the user has stopped touching the screen since if the
                // user types with the IME he should get a feedback for the
                // letter typed in the text view which is in the input focused
                // window. Note that we always deliver hover accessibility events
                // (they are a result of user touching the screen) so change of
                // the active window before all hover accessibility events from
                // the touched window are delivered is fine.
                final int oldActiveWindow = mSecurityPolicy.mActiveWindowId;
                setActiveWindowLocked(mFocusedWindowId);

                // If there is no service that can operate with active windows
                // we keep accessibility focus behavior to constrain it only in
                // the active window. Look at updateAccessibilityFocusBehaviorLocked
                // for details.
                if (oldActiveWindow != mSecurityPolicy.mActiveWindowId
                        && mAccessibilityFocusedWindowId == oldActiveWindow
                        && getCurrentUserStateLocked().mAccessibilityFocusOnlyInActiveWindow) {
                    mMainHandler.obtainMessage(MainHandler.MSG_CLEAR_ACCESSIBILITY_FOCUS,
                            oldActiveWindow, 0).sendToTarget();
                }
            }
        }

        public int getActiveWindowId() {
            if (mActiveWindowId == INVALID_WINDOW_ID && !mTouchInteractionInProgress) {
                mActiveWindowId = getFocusedWindowId();
            }
            return mActiveWindowId;
        }

        private void setActiveWindowLocked(int windowId) {
            if (mActiveWindowId != windowId) {
                mActiveWindowId = windowId;
                if (mWindows != null) {
                    final int windowCount = mWindows.size();
                    for (int i = 0; i < windowCount; i++) {
                        AccessibilityWindowInfo window = mWindows.get(i);
                        window.setActive(window.getId() == windowId);
                    }
                }
                notifyWindowsChanged();
            }
        }

        private void setAccessibilityFocusedWindowLocked(int windowId) {
            if (mAccessibilityFocusedWindowId != windowId) {
                mAccessibilityFocusedWindowId = windowId;
                if (mWindows != null) {
                    final int windowCount = mWindows.size();
                    for (int i = 0; i < windowCount; i++) {
                        AccessibilityWindowInfo window = mWindows.get(i);
                        window.setAccessibilityFocused(window.getId() == windowId);
                    }
                }

                notifyWindowsChanged();
            }
        }

        private void notifyWindowsChanged() {
            if (mWindowsForAccessibilityCallback == null) {
                return;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                // Let the client know the windows changed.
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED);
                event.setEventTime(SystemClock.uptimeMillis());
                sendAccessibilityEvent(event, mCurrentUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public boolean canGetAccessibilityNodeInfoLocked(Service service, int windowId) {
            return canRetrieveWindowContentLocked(service) && isRetrievalAllowingWindow(windowId);
        }

        public boolean canRetrieveWindowsLocked(Service service) {
            return canRetrieveWindowContentLocked(service) && service.mRetrieveInteractiveWindows;
        }

        public boolean canRetrieveWindowContentLocked(Service service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
        }

        public boolean canControlMagnification(Service service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION) != 0;
        }

        public boolean canPerformGestures(Service service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
        }

        private int resolveProfileParentLocked(int userId) {
            if (userId != mCurrentUserId) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    UserInfo parent = mUserManager.getProfileParent(userId);
                    if (parent != null) {
                        return parent.getUserHandle().getIdentifier();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return userId;
        }

        public int resolveCallingUserIdEnforcingPermissionsLocked(int userId) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == 0
                    || callingUid == Process.SYSTEM_UID
                    || callingUid == Process.SHELL_UID) {
                if (userId == UserHandle.USER_CURRENT
                        || userId == UserHandle.USER_CURRENT_OR_SELF) {
                    return mCurrentUserId;
                }
                return resolveProfileParentLocked(userId);
            }
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (callingUserId == userId) {
                return resolveProfileParentLocked(userId);
            }
            final int callingUserParentId = resolveProfileParentLocked(callingUserId);
            if (callingUserParentId == mCurrentUserId &&
                    (userId == UserHandle.USER_CURRENT
                            || userId == UserHandle.USER_CURRENT_OR_SELF)) {
                return mCurrentUserId;
            }
            if (!hasPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                    && !hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)) {
                throw new SecurityException("Call from user " + callingUserId + " as user "
                        + userId + " without permission INTERACT_ACROSS_USERS or "
                        + "INTERACT_ACROSS_USERS_FULL not allowed.");
            }
            if (userId == UserHandle.USER_CURRENT
                    || userId == UserHandle.USER_CURRENT_OR_SELF) {
                return mCurrentUserId;
            }
            throw new IllegalArgumentException("Calling user can be changed to only "
                    + "UserHandle.USER_CURRENT or UserHandle.USER_CURRENT_OR_SELF.");
        }

        public boolean isCallerInteractingAcrossUsers(int userId) {
            final int callingUid = Binder.getCallingUid();
            return (Binder.getCallingPid() == android.os.Process.myPid()
                    || callingUid == Process.SHELL_UID
                    || userId == UserHandle.USER_CURRENT
                    || userId == UserHandle.USER_CURRENT_OR_SELF);
        }

        private boolean isRetrievalAllowingWindow(int windowId) {
            // The system gets to interact with any window it wants.
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                return true;
            }
            if (windowId == mActiveWindowId) {
                return true;
            }
            return findWindowById(windowId) != null;
        }

        private AccessibilityWindowInfo findWindowById(int windowId) {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == windowId) {
                        return window;
                    }
                }
            }
            return null;
        }

        private void enforceCallingPermission(String permission, String function) {
            if (OWN_PROCESS_ID == Binder.getCallingPid()) {
                return;
            }
            if (!hasPermission(permission)) {
                throw new SecurityException("You do not have " + permission
                        + " required to call " + function + " from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            }
        }

        private boolean hasPermission(String permission) {
            return mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        private int getFocusedWindowId() {
            IBinder token = mWindowManagerService.getFocusedWindowToken();
            synchronized (mLock) {
                return findWindowIdLocked(token);
            }
        }
    }

    private class UserState {
        public final int mUserId;

        // Non-transient state.

        public final RemoteCallbackList<IAccessibilityManagerClient> mClients =
            new RemoteCallbackList<>();

        public final SparseArray<RemoteAccessibilityConnection> mInteractionConnections =
                new SparseArray<>();

        public final SparseArray<IBinder> mWindowTokens = new SparseArray<>();

        // Transient state.

        public final CopyOnWriteArrayList<Service> mBoundServices =
                new CopyOnWriteArrayList<>();

        public final Map<ComponentName, Service> mComponentNameToServiceMap =
                new HashMap<>();

        public final List<AccessibilityServiceInfo> mInstalledServices =
                new ArrayList<>();

        public final Set<ComponentName> mBindingServices = new HashSet<>();

        public final Set<ComponentName> mEnabledServices = new HashSet<>();

        public final Set<ComponentName> mTouchExplorationGrantedServices =
                new HashSet<>();

        public ComponentName mServiceChangingSoftKeyboardMode;

        public int mLastSentClientState = -1;

        public int mSoftKeyboardShowMode = 0;

        public boolean mIsTouchExplorationEnabled;
        public boolean mIsTextHighContrastEnabled;
        public boolean mIsEnhancedWebAccessibilityEnabled;
        public boolean mIsDisplayMagnificationEnabled;
        public boolean mIsAutoclickEnabled;
        public boolean mIsPerformGesturesEnabled;
        public boolean mIsFilterKeyEventsEnabled;
        public boolean mAccessibilityFocusOnlyInActiveWindow;

        private Service mUiAutomationService;
        private int mUiAutomationFlags;
        private IAccessibilityServiceClient mUiAutomationServiceClient;

        private IBinder mUiAutomationServiceOwner;
        private final DeathRecipient mUiAutomationSerivceOnwerDeathRecipient =
                new DeathRecipient() {
            @Override
            public void binderDied() {
                mUiAutomationServiceOwner.unlinkToDeath(
                        mUiAutomationSerivceOnwerDeathRecipient, 0);
                mUiAutomationServiceOwner = null;
                if (mUiAutomationService != null) {
                    mUiAutomationService.binderDied();
                }
            }
        };

        public UserState(int userId) {
            mUserId = userId;
        }

        public int getClientState() {
            int clientState = 0;
            if (isHandlingAccessibilityEvents()) {
                clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
            }
            // Touch exploration relies on enabled accessibility.
            if (isHandlingAccessibilityEvents() && mIsTouchExplorationEnabled) {
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

        public void onSwitchToAnotherUser() {
            // Clear UI test automation state.
            if (mUiAutomationService != null) {
                mUiAutomationService.binderDied();
            }

            // Unbind all services.
            unbindAllServicesLocked(this);

            // Clear service management state.
            mBoundServices.clear();
            mBindingServices.clear();

            // Clear event management state.
            mLastSentClientState = -1;

            // Clear state persisted in settings.
            mEnabledServices.clear();
            mTouchExplorationGrantedServices.clear();
            mIsTouchExplorationEnabled = false;
            mIsEnhancedWebAccessibilityEnabled = false;
            mIsDisplayMagnificationEnabled = false;
            mIsAutoclickEnabled = false;
            mSoftKeyboardShowMode = 0;
        }

        public void destroyUiAutomationService() {
            mUiAutomationService = null;
            mUiAutomationFlags = 0;
            mUiAutomationServiceClient = null;
            if (mUiAutomationServiceOwner != null) {
                mUiAutomationServiceOwner.unlinkToDeath(
                        mUiAutomationSerivceOnwerDeathRecipient, 0);
                mUiAutomationServiceOwner = null;
            }
        }

        boolean isUiAutomationSuppressingOtherServices() {
            return ((mUiAutomationService != null) && (mUiAutomationFlags
                    & UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) == 0);
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

        private final Uri mEnhancedWebAccessibilityUri = Settings.Secure
                .getUriFor(Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION);

        private final Uri mDisplayInversionEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);

        private final Uri mDisplayDaltonizerEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);

        private final Uri mDisplayDaltonizerUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER);

        private final Uri mHighTextContrastUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED);

        private final Uri mAccessibilitySoftKeyboardModeUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);

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
            contentResolver.registerContentObserver(mEnhancedWebAccessibilityUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayInversionEnabledUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayDaltonizerEnabledUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayDaltonizerUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mHighTextContrastUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilitySoftKeyboardModeUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // Profiles share the accessibility state of the parent. Therefore,
                // we are checking for changes only the parent settings.
                UserState userState = getCurrentUserStateLocked();

                // If the automation service is suppressing, we will update when it dies.
                if (userState.isUiAutomationSuppressingOtherServices()) {
                    return;
                }

                if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayMagnificationEnabledUri.equals(uri)) {
                    if (readDisplayMagnificationEnabledSettingLocked(userState)) {
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
                } else if (mEnhancedWebAccessibilityUri.equals(uri)) {
                    if (readEnhancedWebAccessibilityEnabledChangedLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayDaltonizerEnabledUri.equals(uri)
                        || mDisplayDaltonizerUri.equals(uri)) {
                    updateDisplayDaltonizerLocked(userState);
                } else if (mDisplayInversionEnabledUri.equals(uri)) {
                    updateDisplayInversionLocked(userState);
                } else if (mHighTextContrastUri.equals(uri)) {
                    if (readHighTextContrastEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilitySoftKeyboardModeUri.equals(uri)) {
                    if (readSoftKeyboardShowModeChangedLocked(userState)) {
                        notifySoftKeyboardShowModeChangedLocked(userState.mSoftKeyboardShowMode);
                        onUserStateChangedLocked(userState);
                    }
                }
            }
        }
    }
}
