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
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.StatusBarManager;
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
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;
import android.util.Slog;
import android.util.SparseArray;
import android.view.AccessibilityManagerInternal;
import android.view.Display;
import android.view.IWindow;
import android.view.InputDevice;
import android.view.InputEventConsistencyVerifier;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerInternal;
import android.view.WindowManagerPolicy;
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
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;

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

    private static final int MAX_POOL_SIZE = 10;

    private static int sIdCounter = 0;

    private static int sNextWindowId;

    private final Context mContext;

    private final Object mLock = new Object();

    private final Pool<PendingEvent> mPendingEventPool =
            new SimplePool<>(MAX_POOL_SIZE);

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final List<AccessibilityServiceInfo> mEnabledServicesForFeedbackTempList =
            new ArrayList<>();

    private final Region mTempRegion = new Region();

    private final Rect mTempRect = new Rect();

    private final Point mTempPoint = new Point();

    private final PackageManager mPackageManager;

    private final WindowManagerInternal mWindowManagerService;

    private final SecurityPolicy mSecurityPolicy;

    private final MainHandler mMainHandler;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final List<AccessibilityServiceInfo> mTempAccessibilityServiceInfoList =
            new ArrayList<>();

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    private final SparseArray<AccessibilityConnectionWrapper> mGlobalInteractionConnections =
            new SparseArray<>();

    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<>();

    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final UserManager mUserManager;

    private final LockPatternUtils mLockPatternUtils;

    private int mCurrentUserId = UserHandle.USER_OWNER;

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
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mSecurityPolicy = new SecurityPolicy();
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mLockPatternUtils = new LockPatternUtils(context);
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                context.getContentResolver());
        LocalServices.addService(AccessibilityManagerInternal.class, new LocalService());
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
                    // etc. Remove them then to force reload. Do it even if automation is
                    // running since when it goes away, we will have to reload as well.
                    userState.mInstalledServices.clear();
                    if (userState.mUiAutomationService == null) {
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
                        }
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
                            if (userState.mUiAutomationService == null) {
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
                                if (userState.mUiAutomationService == null) {
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
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // We will update when the automation service dies.
                    UserState userState = getCurrentUserStateLocked();
                    if (userState.mUiAutomationService == null) {
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
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
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return true; // yes, recycle the event
            }
            if (mSecurityPolicy.canDispatchAccessibilityEventLocked(event)) {
                mSecurityPolicy.updateActiveAndAccessibilityFocusedWindowLocked(event.getWindowId(),
                        event.getSourceNodeId(), event.getEventType());
                mSecurityPolicy.updateEventSourceLocked(event);
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
            }
            if (mHasInputFilter && mInputFilter != null) {
                mMainHandler.obtainMessage(MainHandler.MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER,
                        AccessibilityEvent.obtain(event)).sendToTarget();
            }
            event.recycle();
            getUserStateLocked(resolvedUserId).mHandledFeedbackTypes = 0;
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

            // The automation service is a fake one and should not be reported
            // to clients as being enabled. The automation service is always the
            // only active one, if it exists.
            UserState userState = getUserStateLocked(resolvedUserId);
            if (userState.mUiAutomationService != null) {
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
                    if ((service.mFeedbackType & feedbackTypeBit) != 0) {
                        result.add(service.mAccessibilityServiceInfo);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void interrupt(int userId) {
        CopyOnWriteArrayList<Service> services;
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
            services = getUserStateLocked(resolvedUserId).mBoundServices;
        }
        for (int i = 0, count = services.size(); i < count; i++) {
            Service service = services.get(i);
            try {
                service.mServiceInterface.onInterrupt();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during sending interrupt request to "
                    + service.mService, re);
            }
        }
    }

    @Override
    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection, int userId) throws RemoteException {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            final int windowId = sNextWindowId++;
            // If the window is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                AccessibilityConnectionWrapper wrapper = new AccessibilityConnectionWrapper(
                        windowId, connection, UserHandle.USER_ALL);
                wrapper.linkToDeath();
                mGlobalInteractionConnections.put(windowId, wrapper);
                mGlobalWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and  token: " + windowToken.asBinder());
                }
            } else {
                AccessibilityConnectionWrapper wrapper = new AccessibilityConnectionWrapper(
                        windowId, connection, resolvedUserId);
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
                            + " with windowId: " + removedWindowId + " and token: " + window.asBinder());
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
            SparseArray<AccessibilityConnectionWrapper> interactionConnections) {
        final int count = windowTokens.size();
        for (int i = 0; i < count; i++) {
            if (windowTokens.valueAt(i) == windowToken) {
                final int windowId = windowTokens.keyAt(i);
                windowTokens.removeAt(i);
                AccessibilityConnectionWrapper wrapper = interactionConnections.get(windowId);
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
            AccessibilityServiceInfo accessibilityServiceInfo) {
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

            // Set the temporary state.
            userState.mIsAccessibilityEnabled = true;
            userState.mIsTouchExplorationEnabled = false;
            userState.mIsEnhancedWebAccessibilityEnabled = false;
            userState.mIsDisplayMagnificationEnabled = false;
            userState.mInstalledServices.add(accessibilityServiceInfo);
            userState.mEnabledServices.clear();
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
            if (userState.mUiAutomationService != null) {
                return;
            }

            userState.mIsAccessibilityEnabled = true;
            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            userState.mIsEnhancedWebAccessibilityEnabled = false;
            userState.mIsDisplayMagnificationEnabled = false;
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
    public IBinder getWindowToken(int windowId) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.RETRIEVE_WINDOW_TOKEN,
                GET_WINDOW_TOKEN);
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(
                            UserHandle.getCallingUserId());
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
            KeyEvent localClone = KeyEvent.obtain(event);
            boolean handled = notifyKeyEventLocked(localClone, policyFlags, false);
            if (!handled) {
                handled = notifyKeyEventLocked(localClone, policyFlags, true);
            }
            return handled;
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
     * Gets the bounds of the active window.
     *
     * @param outBounds The output to which to write the bounds.
     */
    boolean getActiveWindowBounds(Rect outBounds) {
        // TODO: This should be refactored to work with accessibility
        // focus in multiple windows.
        IBinder token;
        synchronized (mLock) {
            final int windowId = mSecurityPolicy.mActiveWindowId;
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

    private void removeUser(int userId) {
        synchronized (mLock) {
            mUserStates.remove(userId);
        }
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

    private boolean notifyKeyEventLocked(KeyEvent event, int policyFlags, boolean isDefault) {
        // TODO: Now we are giving the key events to the last enabled
        //       service that can handle them Ideally, the user should
        //       make the call which service handles key events. However,
        //       only one service should handle key events to avoid user
        //       frustration when different behavior is observed from
        //       different combinations of enabled accessibility services.
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            Service service = state.mBoundServices.get(i);
            // Key events are handled only by services that declared
            // this capability and requested to filter key events.
            if (!service.mRequestFilterKeyEvents ||
                    (service.mAccessibilityServiceInfo.getCapabilities() & AccessibilityServiceInfo
                            .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) {
                continue;
            }
            if (service.mIsDefault == isDefault) {
                service.notifyKeyEvent(event, policyFlags);
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
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
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
                    if (canDispatchEventToServiceLocked(service, event,
                            state.mHandledFeedbackTypes)) {
                        state.mHandledFeedbackTypes |= service.mFeedbackType;
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
            service.linkToOwnDeathLocked();
            userState.mBoundServices.add(service);
            userState.mComponentNameToServiceMap.put(service.mComponentName, service);
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
        userState.mComponentNameToServiceMap.remove(service.mComponentName);
        service.unlinkToOwnDeathLocked();
    }

    /**
     * Determines if given event can be dispatched to a service based on the package of the
     * event source and already notified services for that event type. Specifically, a
     * service is notified if it is interested in events from the package and no other service
     * providing the same feedback type has been notified. Exception are services the
     * provide generic feedback (feedback type left as a safety net for unforeseen feedback
     * types) which are always notified.
     *
     * @param service The potential receiver.
     * @param event The event.
     * @param handledFeedbackTypes The feedback types for which services have been notified.
     * @return True if the listener should be notified, false otherwise.
     */
    private boolean canDispatchEventToServiceLocked(Service service, AccessibilityEvent event,
            int handledFeedbackTypes) {

        if (!service.canReceiveEventsLocked()) {
            return false;
        }

        if (!event.isImportantForAccessibility()
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

        if (packageNames.isEmpty() || packageNames.contains(packageName)) {
            int feedbackType = service.mFeedbackType;
            if ((handledFeedbackTypes & feedbackType) != feedbackType
                    || feedbackType == AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                return true;
            }
        }

        return false;
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
        outComponentNames.clear();
        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(settingValue);
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
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                settingName, builder.toString(), userId);
    }

    private void manageServicesLocked(UserState userState) {
        Map<ComponentName, Service> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isEnabled = userState.mIsAccessibilityEnabled;

        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());
            Service service = componentNameToServiceMap.get(componentName);

            if (isEnabled) {
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
            } else {
                if (service != null) {
                    service.unbindLocked();
                } else {
                    userState.mBindingServices.remove(componentName);
                }
            }
        }

        // No enabled installed services => disable accessibility to avoid
        // sending accessibility events with no recipient across processes.
        if (isEnabled && userState.mBoundServices.isEmpty()
                && userState.mBindingServices.isEmpty()) {
            userState.mIsAccessibilityEnabled = false;
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0, userState.mUserId);
        }
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
            // Touch exploration without accessibility makes no sense.
            if (userState.mIsAccessibilityEnabled && userState.mIsTouchExplorationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
            }
            if (userState.mIsFilterKeyEventsEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS;
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
                mInputFilter.setEnabledFeatures(flags);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.disableFeatures();
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
                         Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                 Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                 service.mUserId);
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

    private void onUserStateChangedLocked(UserState userState) {
        // TODO: Remove this hack
        mInitialized = true;
        updateLegacyCapabilitiesLocked(userState);
        updateServicesLocked(userState);
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateAccessibilityFocusBehaviorLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updateEnhancedWebAccessibilityLocked(userState);
        updateDisplayColorAdjustmentSettingsLocked(userState);
        updateEncryptionState(userState);
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
        if (userState.mIsAccessibilityEnabled) {
            // We observe windows for accessibility only if there is at least
            // one bound service that can retrieve window content that specified
            // it is interested in accessing such windows. For services that are
            // binding we do an update pass after each bind event, so we run this
            // code and register the callback if needed.
            boolean boundServiceCanRetrieveInteractiveWindows = false;

            List<Service> boundServices = userState.mBoundServices;
            final int boundServiceCount = boundServices.size();
            for (int i = 0; i < boundServiceCount; i++) {
                Service boundService = boundServices.get(i);
                if (boundService.canRetrieveInteractiveWindowsLocked()) {
                    boundServiceCanRetrieveInteractiveWindows = true;
                    break;
                }
            }

            if (boundServiceCanRetrieveInteractiveWindows) {
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

    private void updateServicesLocked(UserState userState) {
        if (userState.mIsAccessibilityEnabled) {
            manageServicesLocked(userState);
        } else {
            unbindAllServicesLocked(userState);
        }
    }

    private boolean readConfigurationForUserStateLocked(UserState userState) {
        boolean somthingChanged = readAccessibilityEnabledSettingLocked(userState);
        somthingChanged |= readInstalledAccessibilityServiceLocked(userState);
        somthingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somthingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somthingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somthingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somthingChanged |= readEnhancedWebAccessibilityEnabledChangedLocked(userState);
        somthingChanged |= readDisplayMagnificationEnabledSettingLocked(userState);
        somthingChanged |= readDisplayColorAdjustmentSettingsLocked(userState);
        return somthingChanged;
    }

    private boolean readAccessibilityEnabledSettingLocked(UserState userState) {
        final boolean accessibilityEnabled = Settings.Secure.getIntForUser(
               mContext.getContentResolver(),
               Settings.Secure.ACCESSIBILITY_ENABLED, 0, userState.mUserId) == 1;
        if (accessibilityEnabled != userState.mIsAccessibilityEnabled) {
            userState.mIsAccessibilityEnabled = accessibilityEnabled;
            return true;
        }
        return false;
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

    private boolean readDisplayColorAdjustmentSettingsLocked(UserState userState) {
        final boolean displayAdjustmentsEnabled = DisplayAdjustmentUtils.hasAdjustments(mContext,
                userState.mUserId);
        if (displayAdjustmentsEnabled != userState.mHasDisplayColorAdjustment) {
            userState.mHasDisplayColorAdjustment = displayAdjustmentsEnabled;
            return true;
        }
        // If display adjustment is enabled, always assume there was a change in
        // the adjustment settings.
        return displayAdjustmentsEnabled;
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
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, enabled ? 1 : 0,
                    userState.mUserId);
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
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION, enabled ? 1 : 0,
                    userState.mUserId);
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

    private void updateDisplayColorAdjustmentSettingsLocked(UserState userState) {
        DisplayAdjustmentUtils.applyAdjustments(mContext, userState.mUserId);
    }

    private void updateEncryptionState(UserState userState) {
        if (userState.mUserId != UserHandle.USER_OWNER) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            if (hasRunningServicesLocked(userState) && LockPatternUtils.isDeviceEncrypted()) {
                // If there are running accessibility services we do not have encryption as
                // the user needs the accessibility layer to be running to authenticate.
                mLockPatternUtils.clearEncryptionPassword();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean hasRunningServicesLocked(UserState userState) {
        return !userState.mBoundServices.isEmpty() || !userState.mBindingServices.isEmpty();
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
                pw.append(", accessibilityEnabled=" + userState.mIsAccessibilityEnabled);
                pw.append(", touchExplorationEnabled=" + userState.mIsTouchExplorationEnabled);
                pw.append(", displayMagnificationEnabled="
                        + userState.mIsDisplayMagnificationEnabled);
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

    private class AccessibilityConnectionWrapper implements DeathRecipient {
        private final int mWindowId;
        private final int mUserId;
        private final IAccessibilityInteractionConnection mConnection;

        public AccessibilityConnectionWrapper(int windowId,
                IAccessibilityInteractionConnection connection, int userId) {
            mWindowId = windowId;
            mUserId = userId;
            mConnection = connection;
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
                if (userState.mIsAccessibilityEnabled) {
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

    private PendingEvent obtainPendingEventLocked(KeyEvent event, int policyFlags, int sequence) {
        PendingEvent pendingEvent = mPendingEventPool.acquire();
        if (pendingEvent == null) {
            pendingEvent = new PendingEvent();
        }
        pendingEvent.event = event;
        pendingEvent.policyFlags = policyFlags;
        pendingEvent.sequence = sequence;
        return pendingEvent;
    }

    private void recyclePendingEventLocked(PendingEvent pendingEvent) {
        pendingEvent.clear();
        mPendingEventPool.release(pendingEvent);
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

        // the events pending events to be dispatched to this service
        final SparseArray<AccessibilityEvent> mPendingEvents =
            new SparseArray<>();

        final KeyEventDispatcher mKeyEventDispatcher = new KeyEventDispatcher();

        boolean mWasConnectedAndDied;

        // Handler only for dispatching accessibility events since we use event
        // types as message types allowing us to remove messages per event type.
        public Handler mEventDispatchHandler = new Handler(mMainHandler.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                final int eventType =  message.what;
                notifyAccessibilityEventInternal(eventType);
            }
        };

        // Handler for scheduling method invocations on the main thread.
        public InvocationHandler mInvocationHandler = new InvocationHandler(
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
                mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                        mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
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
                if (mService == null && mContext.bindServiceAsUser(
                        mIntent, this, Context.BIND_AUTO_CREATE, new UserHandle(mUserId))) {
                    userState.mBindingServices.add(mComponentName);
                }
            } else {
                userState.mBindingServices.add(mComponentName);
                mService = userState.mUiAutomationServiceClient.asBinder();
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Simulate asynchronous connection since in onServiceConnected
                        // we may modify the state data in case of an error but bind is
                        // called while iterating over the data and bad things can happen.
                        onServiceConnected(mComponentName, mService);
                    }
                });
                userState.mUiAutomationService = this;
            }
            return false;
        }

        /**
         * Unbinds form the accessibility service and removes it from the data
         * structures for service management.
         *
         * @return True if unbinding is successful.
         */
        public boolean unbindLocked() {
            if (mService == null) {
                return false;
            }
            UserState userState = getUserStateLocked(mUserId);
            mKeyEventDispatcher.flush();
            if (!mIsAutomation) {
                mContext.unbindService(this);
            } else {
                userState.destroyUiAutomationService();
            }
            removeServiceLocked(this, userState);
            resetLocked();
            return true;
        }

        public boolean canReceiveEventsLocked() {
            return (mEventTypes != 0 && mFeedbackType != 0 && mService != null);
        }

        @Override
        public void setOnKeyEventResult(boolean handled, int sequence) {
            mKeyEventDispatcher.setOnKeyEventResult(handled, sequence);
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
                mService = service;
                mServiceInterface = IAccessibilityServiceClient.Stub.asInterface(service);
                UserState userState = getUserStateLocked(mUserId);
                addServiceLocked(this, userState);
                if (userState.mBindingServices.contains(mComponentName) || mWasConnectedAndDied) {
                    userState.mBindingServices.remove(mComponentName);
                    mWasConnectedAndDied = false;
                    try {
                       mServiceInterface.setConnection(this, mId);
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

        @Override
        public List<AccessibilityWindowInfo> getWindows() {
            ensureWindowsAvailableTimed();
            synchronized (mLock) {
                // We treat calls from a profile as if made by its perent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return null;
                }
                final boolean permissionGranted =
                        mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (!permissionGranted) {
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
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
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
        public boolean findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
                long accessibilityNodeId, String viewIdResName, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                        mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.findAccessibilityNodeInfosByViewId(accessibilityNodeId, viewIdResName,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags,
                        interrogatingPid, interrogatingTid, spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error findAccessibilityNodeInfoByViewId().");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        @Override
        public boolean findAccessibilityNodeInfosByText(int accessibilityWindowId,
                long accessibilityNodeId, String text, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.findAccessibilityNodeInfosByText(accessibilityNodeId, text,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags,
                        interrogatingPid, interrogatingTid, spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfosByText()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        @Override
        public boolean findAccessibilityNodeInfoByAccessibilityId(
                int accessibilityWindowId, long accessibilityNodeId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                long interrogatingTid) throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.findAccessibilityNodeInfoByAccessibilityId(accessibilityNodeId,
                        partialInteractiveRegion, interactionId, callback, mFetchFlags | flags,
                        interrogatingPid, interrogatingTid, spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        @Override
        public boolean findFocus(int accessibilityWindowId, long accessibilityNodeId,
                int focusType, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(
                        accessibilityWindowId, focusType);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.findFocus(accessibilityNodeId, focusType, partialInteractiveRegion,
                        interactionId, callback, mFetchFlags, interrogatingPid, interrogatingTid,
                        spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findFocus()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        @Override
        public boolean focusSearch(int accessibilityWindowId, long accessibilityNodeId,
                int direction, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.focusSearch(accessibilityNodeId, direction, partialInteractiveRegion,
                        interactionId, callback, mFetchFlags, interrogatingPid, interrogatingTid,
                        spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling accessibilityFocusSearch()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        @Override
        public boolean performAccessibilityAction(int accessibilityWindowId,
                long accessibilityNodeId, int action, Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
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
                connection.performAccessibilityAction(accessibilityNodeId, action, arguments,
                        interactionId, callback, mFetchFlags, interrogatingPid, interrogatingTid);
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
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                switch (action) {
                    case AccessibilityService.GLOBAL_ACTION_BACK: {
                        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_BACK);
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_HOME: {
                        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HOME);
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_RECENTS: {
                        openRecents();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS: {
                        expandNotifications();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS: {
                        expandQuickSettings();
                    } return true;
                    case AccessibilityService.GLOBAL_ACTION_POWER_DIALOG: {
                        showGlobalActions();
                    } return true;
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public  boolean computeClickPointInScreen(int accessibilityWindowId,
                long accessibilityNodeId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            Region partialInteractiveRegion = mTempRegion;
            synchronized (mLock) {
                // We treat calls from a profile as if made by its parent as profiles
                // share the accessibility state of the parent. The call below
                // performs the current profile parent resolution.
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.USER_CURRENT);
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                        mSecurityPolicy.canRetrieveWindowContentLocked(this);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
                if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                        resolvedWindowId, partialInteractiveRegion)) {
                    partialInteractiveRegion = null;
                }
            }
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            MagnificationSpec spec = getCompatibleMagnificationSpecLocked(resolvedWindowId);
            try {
                connection.computeClickPointInScreen(accessibilityNodeId, partialInteractiveRegion,
                        interactionId, callback, interrogatingPid, interrogatingTid, spec);
                return true;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error computeClickPointInScreen().");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
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
            /* do nothing - #binderDied takes care */
        }

        public void linkToOwnDeathLocked() throws RemoteException {
            mService.linkToDeath(this, 0);
        }

        public void unlinkToOwnDeathLocked() {
            mService.unlinkToDeath(this, 0);
        }

        public void resetLocked() {
            try {
                // Clear the proxy in the other process so this
                // IAccessibilityServiceConnection can be garbage collected.
                mServiceInterface.setConnection(null, mId);
            } catch (RemoteException re) {
                /* ignore */
            }
            mService = null;
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
                mKeyEventDispatcher.flush();
                UserState userState = getUserStateLocked(mUserId);
                // The death recipient is unregistered in removeServiceLocked
                removeServiceLocked(this, userState);
                resetLocked();
                if (mIsAutomation) {
                    // We no longer have an automation service, so restore
                    // the state based on values in the settings database.
                    userState.mInstalledServices.remove(mAccessibilityServiceInfo);
                    userState.mEnabledServices.remove(mComponentName);
                    userState.destroyUiAutomationService();
                    if (readConfigurationForUserStateLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
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
                AccessibilityEvent oldEvent = mPendingEvents.get(eventType);
                mPendingEvents.put(eventType, newEvent);

                final int what = eventType;
                if (oldEvent != null) {
                    mEventDispatchHandler.removeMessages(what);
                    oldEvent.recycle();
                }

                Message message = mEventDispatchHandler.obtainMessage(what);
                mEventDispatchHandler.sendMessageDelayed(message, mNotificationTimeout);
            }
        }

        /**
         * Notifies an accessibility service client for a scheduled event given the event type.
         *
         * @param eventType The type of the event to dispatch.
         */
        private void notifyAccessibilityEventInternal(int eventType) {
            IAccessibilityServiceClient listener;
            AccessibilityEvent event;

            synchronized (mLock) {
                listener = mServiceInterface;

                // If the service died/was disabled while the message for dispatching
                // the accessibility event was propagating the listener may be null.
                if (listener == null) {
                    return;
                }

                event = mPendingEvents.get(eventType);

                // Check for null here because there is a concurrent scenario in which this
                // happens: 1) A binder thread calls notifyAccessibilityServiceDelayedLocked
                // which posts a message for dispatching an event. 2) The message is pulled
                // from the queue by the handler on the service thread and the latter is
                // just about to acquire the lock and call this method. 3) Now another binder
                // thread acquires the lock calling notifyAccessibilityServiceDelayedLocked
                // so the service thread waits for the lock; 4) The binder thread replaces
                // the event with a more recent one (assume the same event type) and posts a
                // dispatch request releasing the lock. 5) Now the main thread is unblocked and
                // dispatches the event which is removed from the pending ones. 6) And ... now
                // the service thread handles the last message posted by the last binder call
                // but the event is already dispatched and hence looking it up in the pending
                // ones yields null. This check is much simpler that keeping count for each
                // event type of each service to catch such a scenario since only one message
                // is processed at a time.
                if (event == null) {
                    return;
                }

                mPendingEvents.remove(eventType);
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

        public void notifyKeyEvent(KeyEvent event, int policyFlags) {
            mInvocationHandler.obtainMessage(InvocationHandler.MSG_ON_KEY_EVENT,
                    policyFlags, 0, event).sendToTarget();
        }

        public void notifyClearAccessibilityNodeInfoCache() {
            mInvocationHandler.sendEmptyMessage(
                    InvocationHandler.MSG_CLEAR_ACCESSIBILITY_CACHE);
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

        private void notifyKeyEventInternal(KeyEvent event, int policyFlags) {
            mKeyEventDispatcher.notifyKeyEvent(event, policyFlags);
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

        private void openRecents() {
            final long token = Binder.clearCallingIdentity();

            IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService("statusbar"));
            try {
                statusBarService.toggleRecentApps();
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Error toggling recent apps.");
            }

            Binder.restoreCallingIdentity(token);
        }

        private void showGlobalActions() {
            mWindowManagerService.showGlobalActions();
        }

        private IAccessibilityInteractionConnection getConnectionLocked(int windowId) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
            }
            AccessibilityConnectionWrapper wrapper = mGlobalInteractionConnections.get(windowId);
            if (wrapper == null) {
                wrapper = getCurrentUserStateLocked().mInteractionConnections.get(windowId);
            }
            if (wrapper != null && wrapper.mConnection != null) {
                return wrapper.mConnection;
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
            public static final int MSG_ON_KEY_EVENT = 2;
            public static final int MSG_CLEAR_ACCESSIBILITY_CACHE = 3;
            public static final int MSG_ON_KEY_EVENT_TIMEOUT = 4;

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

                    case MSG_ON_KEY_EVENT: {
                        KeyEvent event = (KeyEvent) message.obj;
                        final int policyFlags = message.arg1;
                        notifyKeyEventInternal(event, policyFlags);
                    } break;

                    case MSG_CLEAR_ACCESSIBILITY_CACHE: {
                        notifyClearAccessibilityCacheInternal();
                    } break;

                    case MSG_ON_KEY_EVENT_TIMEOUT: {
                        PendingEvent eventState = (PendingEvent) message.obj;
                        setOnKeyEventResult(false, eventState.sequence);
                    } break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: " + type);
                    }
                }
            }
        }

        private final class KeyEventDispatcher {

            private static final long ON_KEY_EVENT_TIMEOUT_MILLIS = 500;

            private PendingEvent mPendingEvents;

            private final InputEventConsistencyVerifier mSentEventsVerifier =
                    InputEventConsistencyVerifier.isInstrumentationEnabled()
                            ? new InputEventConsistencyVerifier(
                                    this, 0, KeyEventDispatcher.class.getSimpleName()) : null;

            public void notifyKeyEvent(KeyEvent event, int policyFlags) {
                final PendingEvent pendingEvent;

                synchronized (mLock) {
                    pendingEvent = addPendingEventLocked(event, policyFlags);
                }

                Message message = mInvocationHandler.obtainMessage(
                        InvocationHandler.MSG_ON_KEY_EVENT_TIMEOUT, pendingEvent);
                mInvocationHandler.sendMessageDelayed(message, ON_KEY_EVENT_TIMEOUT_MILLIS);

                try {
                    // Accessibility services are exclusively not in the system
                    // process, therefore no need to clone the motion event to
                    // prevent tampering. It will be cloned in the IPC call.
                    mServiceInterface.onKeyEvent(pendingEvent.event, pendingEvent.sequence);
                } catch (RemoteException re) {
                    setOnKeyEventResult(false, pendingEvent.sequence);
                }
            }

            public void setOnKeyEventResult(boolean handled, int sequence) {
                synchronized (mLock) {
                    PendingEvent pendingEvent = removePendingEventLocked(sequence);
                    if (pendingEvent != null) {
                        mInvocationHandler.removeMessages(
                                InvocationHandler.MSG_ON_KEY_EVENT_TIMEOUT,
                                pendingEvent);
                        pendingEvent.handled = handled;
                        finishPendingEventLocked(pendingEvent);
                    }
                }
            }

            public void flush() {
                synchronized (mLock) {
                    cancelAllPendingEventsLocked();
                    if (mSentEventsVerifier != null) {
                        mSentEventsVerifier.reset();
                    }
                }
            }

            private PendingEvent addPendingEventLocked(KeyEvent event, int policyFlags) {
                final int sequence = event.getSequenceNumber();
                PendingEvent pendingEvent = obtainPendingEventLocked(event, policyFlags, sequence);
                pendingEvent.next = mPendingEvents;
                mPendingEvents = pendingEvent;
                return pendingEvent;
            }

            private PendingEvent removePendingEventLocked(int sequence) {
                PendingEvent previous = null;
                PendingEvent current = mPendingEvents;

                while (current != null) {
                    if (current.sequence == sequence) {
                        if (previous != null) {
                            previous.next = current.next;
                        } else {
                            mPendingEvents = current.next;
                        }
                        current.next = null;
                        return current;
                    }
                    previous = current;
                    current = current.next;
                }
                return null;
            }

            private void finishPendingEventLocked(PendingEvent pendingEvent) {
                if (!pendingEvent.handled) {
                    sendKeyEventToInputFilter(pendingEvent.event, pendingEvent.policyFlags);
                }
                // Nullify the event since we do not want it to be
                // recycled yet. It will be sent to the input filter.
                pendingEvent.event = null;
                recyclePendingEventLocked(pendingEvent);
            }

            private void sendKeyEventToInputFilter(KeyEvent event, int policyFlags) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Injecting event: " + event);
                }
                if (mSentEventsVerifier != null) {
                    mSentEventsVerifier.onKeyEvent(event, 0);
                }
                policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
                mMainHandler.obtainMessage(MainHandler.MSG_SEND_KEY_EVENT_TO_INPUT_FILTER,
                        policyFlags, 0, event).sendToTarget();
            }

            private void cancelAllPendingEventsLocked() {
                while (mPendingEvents != null) {
                    PendingEvent pendingEvent = removePendingEventLocked(mPendingEvents.sequence);
                    pendingEvent.handled = false;
                    mInvocationHandler.removeMessages(InvocationHandler.MSG_ON_KEY_EVENT_TIMEOUT,
                            pendingEvent);
                    finishPendingEventLocked(pendingEvent);
                }
            }
        }
    }

    private static final class PendingEvent {
        PendingEvent next;

        KeyEvent event;
        int policyFlags;
        int sequence;
        boolean handled;

        public void clear() {
            if (event != null) {
                event.recycle();
                event = null;
            }
            next = null;
            policyFlags = 0;
            sequence = 0;
            handled = false;
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
                case WindowManager.LayoutParams.TYPE_BASE_APPLICATION:
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
                case WindowManager.LayoutParams.TYPE_RECENTS_OVERLAY:
                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_SYSTEM;
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
                Point point = mClient.computeClickPointInScreen(mConnectionId,
                        focus.getWindowId(), focus.getSourceNodeId());

                if (point == null) {
                    return false;
                }

                MagnificationSpec spec = getCompatibleMagnificationSpecLocked(focus.getWindowId());
                if (spec != null && !spec.isNop()) {
                    point.offset((int) -spec.offsetX, (int) -spec.offsetY);
                    point.x = (int) (point.x * (1 / spec.scale));
                    point.y = (int) (point.y * (1 / spec.scale));
                }

                // Make sure the point is within the window.
                Rect windowBounds = mTempRect;
                getActiveWindowBounds(windowBounds);
                if (!windowBounds.contains(point.x, point.y)) {
                    return false;
                }

                // Make sure the point is within the screen.
                Point screenSize = mTempPoint;
                mDefaultDisplay.getRealSize(screenSize);
                if (point.x < 0 || point.x > screenSize.x
                        || point.y < 0 || point.y > screenSize.y) {
                    return false;
                }

                outPoint.set(point.x, point.y);
                return true;
            }
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

        public AccessibilityEvent mShowingFocusedWindowEvent;

        private boolean mTouchInteractionInProgress;

        private boolean canDispatchAccessibilityEventLocked(AccessibilityEvent event) {
            final int eventType = event.getEventType();
            switch (eventType) {
                // All events that are for changes in a global window
                // state should *always* be dispatched.
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    if (mWindowsForAccessibilityCallback != null) {
                        // OK, this is fun. Sometimes the focused window is notified
                        // it has focus before being shown. Historically this event
                        // means that the window is focused and can be introspected.
                        // But we still have not gotten the window state from the
                        // window manager, so delay the notification until then.
                        AccessibilityWindowInfo window = findWindowById(event.getWindowId());
                        if (window == null) {
                            mShowingFocusedWindowEvent = AccessibilityEvent.obtain(event);
                            return false;
                        }
                    }
                // $fall-through$
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
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

            // If we are delaying a window state change event as the window
            // source was showing when it was fired, now is the time to send.
            if (mShowingFocusedWindowEvent != null) {
                final int windowId = mShowingFocusedWindowEvent.getWindowId();
                AccessibilityWindowInfo window = findWindowById(windowId);
                if (window != null) {
                    // Sending does the recycle.
                    sendAccessibilityEvent(mShowingFocusedWindowEvent, mCurrentUserId);
                }
                mShowingFocusedWindowEvent = null;
            }
        }

        public boolean computePartialInteractiveRegionForWindowLocked(int windowId,
                Region outRegion) {
            if (mWindows == null) {
                return false;
            }

            // Windows are ordered in z order so start from the botton and find
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
                } else {
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
                int eventType) {
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
                        if (mAccessibilityFocusNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID
                                && mAccessibilityFocusedWindowId == windowId) {
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

        public final SparseArray<AccessibilityConnectionWrapper> mInteractionConnections =
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

        public int mHandledFeedbackTypes = 0;

        public int mLastSentClientState = -1;

        public boolean mIsAccessibilityEnabled;
        public boolean mIsTouchExplorationEnabled;
        public boolean mIsTextHighContrastEnabled;
        public boolean mIsEnhancedWebAccessibilityEnabled;
        public boolean mIsDisplayMagnificationEnabled;
        public boolean mIsFilterKeyEventsEnabled;
        public boolean mHasDisplayColorAdjustment;
        public boolean mAccessibilityFocusOnlyInActiveWindow;

        private Service mUiAutomationService;
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
            if (mIsAccessibilityEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
            }
            // Touch exploration relies on enabled accessibility.
            if (mIsAccessibilityEnabled && mIsTouchExplorationEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            }
            if (mIsTextHighContrastEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
            }
            return clientState;
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
            mHandledFeedbackTypes = 0;
            mLastSentClientState = -1;

            // Clear state persisted in settings.
            mEnabledServices.clear();
            mTouchExplorationGrantedServices.clear();
            mIsAccessibilityEnabled = false;
            mIsTouchExplorationEnabled = false;
            mIsEnhancedWebAccessibilityEnabled = false;
            mIsDisplayMagnificationEnabled = false;
        }

        public void destroyUiAutomationService() {
            mUiAutomationService = null;
            mUiAutomationServiceClient = null;
            if (mUiAutomationServiceOwner != null) {
                mUiAutomationServiceOwner.unlinkToDeath(
                        mUiAutomationSerivceOnwerDeathRecipient, 0);
                mUiAutomationServiceOwner = null;
            }
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mAccessibilityEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_ENABLED);

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mDisplayMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

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

        public AccessibilityContentObserver(Handler handler) {
            super(handler);
        }

        public void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mAccessibilityEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mTouchExplorationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mDisplayMagnificationEnabledUri,
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
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // Profiles share the accessibility state of the parent. Therefore,
                // we are checking for changes only the parent settings.
                UserState userState = getCurrentUserStateLocked();

                // We will update when the automation service dies.
                if (userState.mUiAutomationService != null) {
                    return;
                }

                if (mAccessibilityEnabledUri.equals(uri)) {
                    if (readAccessibilityEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayMagnificationEnabledUri.equals(uri)) {
                    if (readDisplayMagnificationEnabledSettingLocked(userState)) {
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
                } else if (mDisplayInversionEnabledUri.equals(uri)
                        || mDisplayDaltonizerEnabledUri.equals(uri)
                        || mDisplayDaltonizerUri.equals(uri)) {
                    if (readDisplayColorAdjustmentSettingsLocked(userState)) {
                        updateDisplayColorAdjustmentSettingsLocked(userState);
                    }
                } else if (mHighTextContrastUri.equals(uri)) {
                    if (readHighTextContrastEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }
        }
    }

    private final class LocalService extends AccessibilityManagerInternal {
        @Override
        public boolean isNonDefaultEncryptionPasswordAllowed() {
            synchronized (mLock) {
                UserState userState = getCurrentUserStateLocked();
                return !hasRunningServicesLocked(userState);
            }
        }
    }
}
