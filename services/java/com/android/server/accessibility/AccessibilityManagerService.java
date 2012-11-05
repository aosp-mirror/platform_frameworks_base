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
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

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
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
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
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.R;
import com.android.internal.content.PackageMonitor;
import com.android.internal.statusbar.IStatusBarService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * @hide
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED =
            "temporaryEnableAccessibilityStateUntilKeyguardRemoved";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    private static int sIdCounter = 0;

    private static int sNextWindowId;

    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final List<AccessibilityServiceInfo> mEnabledServicesForFeedbackTempList =
            new ArrayList<AccessibilityServiceInfo>();

    private final Rect mTempRect = new Rect();

    private final Point mTempPoint = new Point();

    private final Display mDefaultDisplay;

    private final PackageManager mPackageManager;

    private final IWindowManager mWindowManagerService;

    private final SecurityPolicy mSecurityPolicy;

    private final MainHandler mMainHandler;

    private Service mUiAutomationService;

    private Service mQueryBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<IAccessibilityManagerClient>();

    private final SparseArray<AccessibilityConnectionWrapper> mGlobalInteractionConnections =
            new SparseArray<AccessibilityConnectionWrapper>();

    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<IBinder>();

    private final SparseArray<UserState> mUserStates = new SparseArray<UserState>();

    private final TempUserStateChangeMemento mTempStateChangeForCurrentUserMemento =
            new TempUserStateChangeMemento();

    private int mCurrentUserId = UserHandle.USER_OWNER;

    private UserState getCurrentUserStateLocked() {
        return getUserStateLocked(mCurrentUserId);
    }

    private UserState getUserStateLocked(int userId) {
        UserState state = mUserStates.get(userId);
        if (state == null) {
            state = new UserState(userId);
            mUserStates.put(userId, state);
        }
        return state;
    }

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mWindowManagerService = (IWindowManager) ServiceManager.getService(Context.WINDOW_SERVICE);
        mSecurityPolicy = new SecurityPolicy();
        mMainHandler = new MainHandler(mContext.getMainLooper());
        //TODO: (multi-display) We need to support multiple displays.
        DisplayManager displayManager = (DisplayManager)
                mContext.getSystemService(Context.DISPLAY_SERVICE);
        mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                context.getContentResolver());
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        populateInstalledAccessibilityServiceLocked(userState);
                        manageServicesLocked(userState);
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    UserState state = getUserStateLocked(userId);
                    Iterator<ComponentName> it = state.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        if (compPkg.equals(packageName)) {
                            it.remove();
                            // Update the enabled services setting.
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                    state.mEnabledServices, userId);
                            // Update the touch exploration granted services setting.
                            state.mTouchExplorationGrantedServices.remove(comp);
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.
                                            TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                    state.mEnabledServices, userId);
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
                    if (userId != mCurrentUserId) {
                        return false;
                    }
                    UserState state = getUserStateLocked(userId);
                    Iterator<ComponentName> it = state.mEnabledServices.iterator();
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
                                        state.mEnabledServices, userId);
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
                    restoreStateFromMementoIfNeeded();
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    public int addClient(IAccessibilityManagerClient client, int userId) {
        synchronized (mLock) {
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
                return getClientState(userState);
            } else {
                userState.mClients.register(client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
                return (resolvedUserId == mCurrentUserId) ? getClientState(userState) : 0;
            }
        }
    }

    public boolean sendAccessibilityEvent(AccessibilityEvent event, int userId) {
        synchronized (mLock) {
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return true; // yes, recycle the event
            }
            if (mSecurityPolicy.canDispatchAccessibilityEvent(event)) {
                mSecurityPolicy.updateEventSourceLocked(event);
                mMainHandler.obtainMessage(MainHandler.MSG_UPDATE_ACTIVE_WINDOW,
                        event.getWindowId(), event.getEventType()).sendToTarget();
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

    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId) {
        synchronized (mLock) {
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            return getUserStateLocked(resolvedUserId).mInstalledServices;
        }
    }

    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        List<AccessibilityServiceInfo> result = null;
        synchronized (mLock) {
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            result = mEnabledServicesForFeedbackTempList;
            result.clear();
            List<Service> services = getUserStateLocked(resolvedUserId).mServices;
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

    public void interrupt(int userId) {
        CopyOnWriteArrayList<Service> services;
        synchronized (mLock) {
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return;
            }
            services = getUserStateLocked(resolvedUserId).mServices;
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

    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection, int userId) throws RemoteException {
        synchronized (mLock) {
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
                            + " with windowId: " + windowId);
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
                            + " with windowId: " + windowId + " and userId:" + mCurrentUserId);
                }
            }
            if (DEBUG) {
                Slog.i(LOG_TAG, "Adding interaction connection to windowId: " + windowId);
            }
            return windowId;
        }
    }

    public void removeAccessibilityInteractionConnection(IWindow window) {
        synchronized (mLock) {
            mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                    UserHandle.getCallingUserId());
            IBinder token = window.asBinder();
            final int removedWindowId = removeAccessibilityInteractionConnectionInternalLocked(
                    token, mGlobalWindowTokens, mGlobalInteractionConnections);
            if (removedWindowId >= 0) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + removedWindowId);
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
                                + mUserStates.keyAt(i));
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

    public void registerUiTestAutomationService(IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE);
        ComponentName componentName = new ComponentName("foo.bar",
                "AutomationAccessibilityService");
        synchronized (mLock) {
            // If an automation services is connected to the system all services are stopped
            // so the automation one is the only one running. Settings are not changed so when
            // the automation service goes away the state is restored from the settings.
            UserState userState = getCurrentUserStateLocked();
            unbindAllServicesLocked(userState);

            // If necessary enable accessibility and announce that.
            if (!userState.mIsAccessibilityEnabled) {
                userState.mIsAccessibilityEnabled = true;
            }
            // No touch exploration.
            userState.mIsTouchExplorationEnabled = false;

            // Hook the automation service up.
            mUiAutomationService = new Service(mCurrentUserId, componentName,
                    accessibilityServiceInfo, true);
            mUiAutomationService.onServiceConnected(componentName, serviceClient.asBinder());

            updateInputFilterLocked(userState);
            scheduleSendStateToClientsLocked(userState);
        }
    }

    public void temporaryEnableAccessibilityStateUntilKeyguardRemoved(
            ComponentName service, boolean touchExplorationEnabled) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.TEMPORARY_ENABLE_ACCESSIBILITY,
                TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED);
        try {
            if (!mWindowManagerService.isKeyguardLocked()) {
                return;
            }
        } catch (RemoteException re) {
            return;
        }
        synchronized (mLock) {
            UserState userState = getCurrentUserStateLocked();
            // Stash the old state so we can restore it when the keyguard is gone.
            mTempStateChangeForCurrentUserMemento.initialize(mCurrentUserId, getCurrentUserStateLocked());
            // Set the temporary state.
            userState.mIsAccessibilityEnabled = true;
            userState.mIsTouchExplorationEnabled= touchExplorationEnabled;
            userState.mIsDisplayMagnificationEnabled = false;
            userState.mEnabledServices.clear();
            userState.mEnabledServices.add(service);
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.add(service);
            // Update the internal state.
            performServiceManagementLocked(userState);
            updateInputFilterLocked(userState);
            scheduleSendStateToClientsLocked(userState);
        }
    }

    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        synchronized (mLock) {
            // Automation service is not bound, so pretend it died to perform clean up.
            if (mUiAutomationService != null && mUiAutomationService.mServiceInterface != null
                    && serviceClient != null && mUiAutomationService.mServiceInterface
                            .asBinder() == serviceClient.asBinder()) {
                mUiAutomationService.binderDied();
            }
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

    /**
     * Gets the bounds of the accessibility focus in the active window.
     *
     * @param outBounds The output to which to write the focus bounds.
     * @return Whether accessibility focus was found and the bounds are populated.
     */
    // TODO: (multi-display) Make sure this works for multiple displays. 
    boolean getAccessibilityFocusBoundsInActiveWindow(Rect outBounds) {
        // Instead of keeping track of accessibility focus events per
        // window to be able to find the focus in the active window,
        // we take a stateless approach and look it up. This is fine
        // since we do this only when the user clicks/long presses.
        Service service = getQueryBridge();
        final int connectionId = service.mId;
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        client.addConnection(connectionId, service);
        try {
            AccessibilityNodeInfo root = AccessibilityInteractionClient.getInstance()
                    .getRootInActiveWindow(connectionId);
            if (root == null) {
                return false;
            }
            AccessibilityNodeInfo focus = root.findFocus(
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focus == null) {
                return false;
            }
            focus.getBoundsInScreen(outBounds);
            // Clip to the window rectangle.
            Rect windowBounds = mTempRect;
            getActiveWindowBounds(windowBounds);
            outBounds.intersect(windowBounds);
            // Clip to the screen rectangle.
            mDefaultDisplay.getRealSize(mTempPoint);
            outBounds.intersect(0,  0,  mTempPoint.x, mTempPoint.y);
            return true;
        } finally {
            client.removeConnection(connectionId);
        }
    }

    /**
     * Gets the bounds of the active window.
     *
     * @param outBounds The output to which to write the bounds.
     */
    boolean getActiveWindowBounds(Rect outBounds) {
        IBinder token;
        synchronized (mLock) {
            final int windowId = mSecurityPolicy.mActiveWindowId;
            token = mGlobalWindowTokens.get(windowId);
            if (token == null) {
                token = getCurrentUserStateLocked().mWindowTokens.get(windowId);
            }
        }
        WindowInfo info = null;
        try {
            info = mWindowManagerService.getWindowInfo(token);
            if (info != null) {
                outBounds.set(info.frame);
                return true;
            }
        } catch (RemoteException re) {
            /* ignore */
        } finally {
            if (info != null) {
                info.recycle();
            }
        }
        return false;
    }

    int getActiveWindowId() {
        return mSecurityPolicy.mActiveWindowId;
    }

    void onTouchInteractionStart() {
        mSecurityPolicy.onTouchInteractionStart();
    }

    void onTouchInteractionEnd() {
        mSecurityPolicy.onTouchInteractionEnd();
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            // The user switched so we do not need to restore the current user
            // state since we will fully rebuild it when he becomes current again.
            mTempStateChangeForCurrentUserMemento.clear();

            // Disconnect from services for the old user.
            UserState oldUserState = getUserStateLocked(mCurrentUserId);
            unbindAllServicesLocked(oldUserState);

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

            // Recreate the internal state for the new user.
            mMainHandler.obtainMessage(MainHandler.MSG_SEND_RECREATE_INTERNAL_STATE,
                    mCurrentUserId, 0).sendToTarget();

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

    private void restoreStateFromMementoIfNeeded() {
        synchronized (mLock) {
            if (mTempStateChangeForCurrentUserMemento.mUserId != UserHandle.USER_NULL) {
                UserState userState = getCurrentUserStateLocked();
                // Restore the state from the memento.
                mTempStateChangeForCurrentUserMemento.applyTo(userState);
                mTempStateChangeForCurrentUserMemento.clear();
                // Update the internal state.
                performServiceManagementLocked(userState);
                updateInputFilterLocked(userState);
                scheduleSendStateToClientsLocked(userState);
            }
        }
    }

    private Service getQueryBridge() {
        if (mQueryBridge == null) {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            mQueryBridge = new Service(UserHandle.USER_NULL, null, info, true);
        }
        return mQueryBridge;
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
        for (int i = state.mServices.size() - 1; i >= 0; i--) {
            Service service = state.mServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureId);
                return true;
            }
        }
        return false;
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

    private void populateInstalledAccessibilityServiceLocked(UserState userState) {
        userState.mInstalledServices.clear();

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
                userState.mInstalledServices.add(accessibilityServiceInfo);
            } catch (XmlPullParserException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
            } catch (IOException ioe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", ioe);
            }
        }
    }

    private void populateEnabledAccessibilityServicesLocked(UserState userState) {
        populateComponentNamesFromSettingLocked(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mUserId,
                userState.mEnabledServices);
    }

    private void populateTouchExplorationGrantedAccessibilityServicesLocked(
            UserState userState) {
        populateComponentNamesFromSettingLocked(
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                userState.mUserId,
                userState.mTouchExplorationGrantedServices);
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
            for (int i = 0, count = state.mServices.size(); i < count; i++) {
                Service service = state.mServices.get(i);

                if (service.mIsDefault == isDefault) {
                    if (canDispathEventLocked(service, event, state.mHandledFeedbackTypes)) {
                        state.mHandledFeedbackTypes |= service.mFeedbackType;
                        service.notifyAccessibilityEvent(event);
                    }
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
            return;
        }
    }

    /**
     * Adds a service for a user.
     *
     * @param service The service to add.
     * @param userId The user id.
     */
    private void tryAddServiceLocked(Service service, int userId) {
        try {
            UserState userState = getUserStateLocked(userId);
            if (userState.mServices.contains(service)) {
                return;
            }
            service.linkToOwnDeath();
            userState.mServices.add(service);
            userState.mComponentNameToServiceMap.put(service.mComponentName, service);
            updateInputFilterLocked(userState);
            tryEnableTouchExplorationLocked(service);
        } catch (RemoteException e) {
            /* do nothing */
        }
    }

    /**
     * Removes a service.
     *
     * @param service The service.
     * @return True if the service was removed, false otherwise.
     */
    private boolean tryRemoveServiceLocked(Service service) {
        UserState userState = getUserStateLocked(service.mUserId);
        final boolean removed = userState.mServices.remove(service);
        if (!removed) {
            return false;
        }
        userState.mComponentNameToServiceMap.remove(service.mComponentName);
        service.unlinkToOwnDeath();
        service.dispose();
        updateInputFilterLocked(userState);
        tryDisableTouchExplorationLocked(service);
        return removed;
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
    private boolean canDispathEventLocked(Service service, AccessibilityEvent event,
            int handledFeedbackTypes) {

        if (!service.canReceiveEvents()) {
            return false;
        }

        if (!event.isImportantForAccessibility()
                && !service.mIncludeNotImportantViews) {
            return false;
        }

        int eventType = event.getEventType();
        if ((service.mEventTypes & eventType) != eventType) {
            return false;
        }

        Set<String> packageNames = service.mPackageNames;
        CharSequence packageName = event.getPackageName();

        if (packageNames.isEmpty() || packageNames.contains(packageName)) {
            int feedbackType = service.mFeedbackType;
            if ((handledFeedbackTypes & feedbackType) != feedbackType
                    || feedbackType == AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                return true;
            }
        }

        return false;
    }

    /**
     * Manages services by starting enabled ones and stopping disabled ones.
     */
    private void manageServicesLocked(UserState userState) {
        final int enabledInstalledServicesCount = updateServicesStateLocked(userState);
        // No enabled installed services => disable accessibility to avoid
        // sending accessibility events with no recipient across processes.
        if (userState.mIsAccessibilityEnabled && enabledInstalledServicesCount == 0) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0, userState.mUserId);
        }
    }

    /**
     * Unbinds all bound services for a user.
     *
     * @param userState The user state.
     */
    private void unbindAllServicesLocked(UserState userState) {
        List<Service> services = userState.mServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            Service service = services.get(i);
            if (service.unbind()) {
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
    private void populateComponentNamesFromSettingLocked(String settingName, int userId,
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

    /**
     * Updates the state of each service by starting (or keeping running) enabled ones and
     * stopping the rest.
     *
     * @param userState The user state for which to do that.
     * @return The number of enabled installed services.
     */
    private int updateServicesStateLocked(UserState userState) {
        Map<ComponentName, Service> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isEnabled = userState.mIsAccessibilityEnabled;

        int enabledInstalledServices = 0;
        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());
            Service service = componentNameToServiceMap.get(componentName);

            if (isEnabled) {
                if (userState.mEnabledServices.contains(componentName)) {
                    if (service == null) {
                        service = new Service(userState.mUserId, componentName,
                                installedService, false);
                    }
                    service.bind();
                    enabledInstalledServices++;
                } else {
                    if (service != null) {
                        service.unbind();
                    }
                }
            } else {
                if (service != null) {
                    service.unbind();
                }
            }
        }

        return enabledInstalledServices;
    }

    private void scheduleSendStateToClientsLocked(UserState userState) {
        if (mGlobalClients.getRegisteredCallbackCount() > 0
                || userState.mClients.getRegisteredCallbackCount() > 0) {
            final int clientState = getClientState(userState);
            mMainHandler.obtainMessage(MainHandler.MSG_SEND_STATE_TO_CLIENTS,
                    clientState, userState.mUserId) .sendToTarget();
        }
    }

    private void updateInputFilterLocked(UserState userState) {
        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            if ((userState.mIsAccessibilityEnabled && userState.mIsTouchExplorationEnabled)
                    || userState.mIsDisplayMagnificationEnabled) {
                if (!mHasInputFilter) {
                    mHasInputFilter = true;
                    if (mInputFilter == null) {
                        mInputFilter = new AccessibilityInputFilter(mContext,
                                AccessibilityManagerService.this);
                    }
                    inputFilter = mInputFilter;
                    setInputFilter = true;
                }
                int flags = 0;
                if (userState.mIsDisplayMagnificationEnabled) {
                    flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
                }
                if (userState.mIsTouchExplorationEnabled) {
                    flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
                }
                mInputFilter.setEnabledFeatures(flags);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.setEnabledFeatures(0);
                    inputFilter = null;
                    setInputFilter = true;
                }
            }
        }
        if (setInputFilter) {
            try {
                mWindowManagerService.setInputFilter(inputFilter);
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    private void showEnableTouchExplorationDialog(final Service service) {
        String label = service.mResolveInfo.loadLabel(
                mContext.getPackageManager()).toString();
        synchronized (mLock) {
            final UserState state = getCurrentUserStateLocked();
            if (state.mIsTouchExplorationEnabled) {
                return;
            }
            if (mEnableTouchExplorationDialog != null
                    && mEnableTouchExplorationDialog.isShowing()) {
                return;
            }
            mEnableTouchExplorationDialog = new AlertDialog.Builder(mContext)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // The user allowed the service to toggle touch exploration.
                        state.mTouchExplorationGrantedServices.add(service.mComponentName);
                        persistComponentNamesToSettingLocked(
                                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                       state.mTouchExplorationGrantedServices, state.mUserId);
                        // Enable touch exploration.
                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                service.mUserId);
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

    private int getClientState(UserState userState) {
        int clientState = 0;
        if (userState.mIsAccessibilityEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        // Touch exploration relies on enabled accessibility.
        if (userState.mIsAccessibilityEnabled && userState.mIsTouchExplorationEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
        }
        return clientState;
    }

    private void recreateInternalStateLocked(UserState userState) {
        populateInstalledAccessibilityServiceLocked(userState);
        populateEnabledAccessibilityServicesLocked(userState);
        populateTouchExplorationGrantedAccessibilityServicesLocked(userState);

        handleTouchExplorationEnabledSettingChangedLocked(userState);
        handleDisplayMagnificationEnabledSettingChangedLocked(userState);
        handleAccessibilityEnabledSettingChangedLocked(userState);

        performServiceManagementLocked(userState);
        updateInputFilterLocked(userState);
        scheduleSendStateToClientsLocked(userState);
    }

    private void handleAccessibilityEnabledSettingChangedLocked(UserState userState) {
        userState.mIsAccessibilityEnabled = Settings.Secure.getIntForUser(
               mContext.getContentResolver(),
               Settings.Secure.ACCESSIBILITY_ENABLED, 0, userState.mUserId) == 1;
    }

    private void performServiceManagementLocked(UserState userState) {
        if (userState.mIsAccessibilityEnabled ) {
            manageServicesLocked(userState);
        } else {
            unbindAllServicesLocked(userState);
        }
    }

    private void handleTouchExplorationEnabledSettingChangedLocked(UserState userState) {
        userState.mIsTouchExplorationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId) == 1;
    }

    private void handleDisplayMagnificationEnabledSettingChangedLocked(UserState userState) {
        userState.mIsDisplayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
    }

    private void handleTouchExplorationGrantedAccessibilityServicesChangedLocked(
            UserState userState) {
        final int serviceCount = userState.mServices.size();
        for (int i = 0; i < serviceCount; i++) {
            Service service = userState.mServices.get(i);
            if (service.mRequestTouchExplorationMode
                    && userState.mTouchExplorationGrantedServices.contains(
                            service.mComponentName)) {
                tryEnableTouchExplorationLocked(service);
                return;
            }
        }
        if (userState.mIsTouchExplorationEnabled) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId);
        }
    }

    private void tryEnableTouchExplorationLocked(final Service service) {
        UserState userState = getUserStateLocked(service.mUserId);
        if (!userState.mIsTouchExplorationEnabled && service.mRequestTouchExplorationMode
                && service.canReceiveEvents()) {
            final boolean canToggleTouchExploration =
                    userState.mTouchExplorationGrantedServices.contains(service.mComponentName);
            if (!service.mIsAutomation && !canToggleTouchExploration) {
                showEnableTouchExplorationDialog(service);
            } else {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1, userState.mUserId);
            }
        }
    }

    private void tryDisableTouchExplorationLocked(Service service) {
        UserState userState = getUserStateLocked(service.mUserId);
        if (userState.mIsTouchExplorationEnabled) {
            final int serviceCount = userState.mServices.size();
            for (int i = 0; i < serviceCount; i++) {
                Service other = userState.mServices.get(i);
                if (other != service && other.mRequestTouchExplorationMode) {
                    return;
                }
            }
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId);
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
        public static final int MSG_SEND_RECREATE_INTERNAL_STATE = 4;
        public static final int MSG_UPDATE_ACTIVE_WINDOW = 5;
        public static final int MSG_ANNOUNCE_NEW_USER_IF_NEEDED = 6;

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
                case MSG_SEND_RECREATE_INTERNAL_STATE: {
                    final int userId = msg.arg1;
                    synchronized (mLock) {
                        UserState userState = getUserStateLocked(userId);
                        recreateInternalStateLocked(userState);
                    }
                } break;
                case MSG_UPDATE_ACTIVE_WINDOW: {
                    final int windowId = msg.arg1;
                    final int eventType = msg.arg2;
                    mSecurityPolicy.updateActiveWindow(windowId, eventType);
                } break;
                case MSG_ANNOUNCE_NEW_USER_IF_NEEDED: {
                    announceNewUserIfNeeded();
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
                    event.setWindowId(mSecurityPolicy.getRetrievalAllowingWindowLocked());
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

    /**
     * This class represents an accessibility service. It stores all per service
     * data required for the service management, provides API for starting/stopping the
     * service and is responsible for adding/removing the service in the data structures
     * for service management. The class also exposes configuration interface that is
     * passed to the service it represents as soon it is bound. It also serves as the
     * connection for the service.
     */
    class Service extends IAccessibilityServiceConnection.Stub
            implements ServiceConnection, DeathRecipient {

        // We pick the MSB to avoid collision since accessibility event types are
        // used as message types allowing us to remove messages per event type. 
        private static final int MSG_ON_GESTURE = 0x80000000;

        final int mUserId;

        int mId = 0;

        AccessibilityServiceInfo mAccessibilityServiceInfo;

        IBinder mService;

        IAccessibilityServiceClient mServiceInterface;

        int mEventTypes;

        int mFeedbackType;

        Set<String> mPackageNames = new HashSet<String>();

        boolean mIsDefault;

        boolean mRequestTouchExplorationMode;

        boolean mIncludeNotImportantViews;

        long mNotificationTimeout;

        ComponentName mComponentName;

        Intent mIntent;

        boolean mCanRetrieveScreenContent;

        boolean mIsAutomation;

        final Rect mTempBounds = new Rect();

        final ResolveInfo mResolveInfo;

        // the events pending events to be dispatched to this service
        final SparseArray<AccessibilityEvent> mPendingEvents =
            new SparseArray<AccessibilityEvent>();

        /**
         * Handler for delayed event dispatch.
         */
        public Handler mHandler = new Handler(mMainHandler.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                final int type = message.what;
                switch (type) {
                    case MSG_ON_GESTURE: {
                        final int gestureId = message.arg1;
                        notifyGestureInternal(gestureId);
                    } break;
                    default: {
                        final int eventType = type;
                        notifyAccessibilityEventInternal(eventType);
                    } break;
                }
            }
        };

        public Service(int userId, ComponentName componentName,
                AccessibilityServiceInfo accessibilityServiceInfo, boolean isAutomation) {
            mUserId = userId;
            mResolveInfo = accessibilityServiceInfo.getResolveInfo();
            mId = sIdCounter++;
            mComponentName = componentName;
            mAccessibilityServiceInfo = accessibilityServiceInfo;
            mIsAutomation = isAutomation;
            if (!isAutomation) {
                mCanRetrieveScreenContent = accessibilityServiceInfo.getCanRetrieveWindowContent();
                mRequestTouchExplorationMode =
                    (accessibilityServiceInfo.flags
                            & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
                mIntent = new Intent().setComponent(mComponentName);
                mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                        com.android.internal.R.string.accessibility_binding_label);
                mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                        mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
            } else {
                mCanRetrieveScreenContent = true;
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
                mIncludeNotImportantViews =
                    (info.flags & FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
            }

            mRequestTouchExplorationMode = (info.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;

            // If this service is up and running we may have to enable touch
            // exploration, otherwise this will happen when the service connects.
            synchronized (mLock) {
                if (canReceiveEvents()) {
                    if (mRequestTouchExplorationMode) {
                        tryEnableTouchExplorationLocked(this);
                    } else {
                        tryDisableTouchExplorationLocked(this);
                    }
                }
            }
        }

        /**
         * Binds to the accessibility service.
         *
         * @return True if binding is successful.
         */
        public boolean bind() {
            if (!mIsAutomation && mService == null) {
                return mContext.bindService(mIntent, this, Context.BIND_AUTO_CREATE, mUserId);
            }
            return false;
        }

        /**
         * Unbinds form the accessibility service and removes it from the data
         * structures for service management.
         *
         * @return True if unbinding is successful.
         */
        public boolean unbind() {
            if (mService != null) {
                synchronized (mLock) {
                    tryRemoveServiceLocked(this);
                }
                if (!mIsAutomation) {
                    mContext.unbindService(this);
                }
                return true;
            }
            return false;
        }

        public boolean canReceiveEvents() {
            return (mEventTypes != 0 && mFeedbackType != 0 && mService != null);
        }

        @Override
        public AccessibilityServiceInfo getServiceInfo() {
            synchronized (mLock) {
                return mAccessibilityServiceInfo;
            }
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
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mService = service;
            mServiceInterface = IAccessibilityServiceClient.Stub.asInterface(service);
            try {
                mServiceInterface.setConnection(this, mId);
                synchronized (mLock) {
                    tryAddServiceLocked(this, mUserId);
                }
            } catch (RemoteException re) {
                Slog.w(LOG_TAG, "Error while setting Controller for service: " + service, re);
            }
        }

        @Override
        public float findAccessibilityNodeInfoByViewId(int accessibilityWindowId,
                long accessibilityNodeId, int viewId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                                UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return -1;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                final boolean permissionGranted = mSecurityPolicy.canRetrieveWindowContent(this);
                if (!permissionGranted) {
                    return 0;
                } else {
                    resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return 0;
                    }
                }
            }
            final int flags = (mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0;
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.findAccessibilityNodeInfoByViewId(accessibilityNodeId, viewId,
                        interactionId, callback, flags, interrogatingPid, interrogatingTid);
                return getCompatibilityScale(resolvedWindowId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error findAccessibilityNodeInfoByViewId().");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return 0;
        }

        @Override
        public float findAccessibilityNodeInfosByText(int accessibilityWindowId,
                long accessibilityNodeId, String text, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return -1;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return 0;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return 0;
                    }
                }
            }
            final int flags = (mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0;
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.findAccessibilityNodeInfosByText(accessibilityNodeId, text,
                        interactionId, callback, flags, interrogatingPid,
                        interrogatingTid);
                return getCompatibilityScale(resolvedWindowId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfosByText()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return 0;
        }

        @Override
        public float findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
                long accessibilityNodeId, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                long interrogatingTid) throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return -1;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return 0;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return 0;
                    }
                }
            }
            final int allFlags = flags | ((mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0);
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.findAccessibilityNodeInfoByAccessibilityId(accessibilityNodeId,
                        interactionId, callback, allFlags, interrogatingPid, interrogatingTid);
                return getCompatibilityScale(resolvedWindowId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return 0;
        }

        @Override
        public float findFocus(int accessibilityWindowId, long accessibilityNodeId,
                int focusType, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return -1;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return 0;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return 0;
                    }
                }
            }
            final int flags = (mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0;
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.findFocus(accessibilityNodeId, focusType, interactionId, callback,
                        flags, interrogatingPid, interrogatingTid);
                return getCompatibilityScale(resolvedWindowId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling findAccessibilityFocus()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return 0;
        }

        @Override
        public float focusSearch(int accessibilityWindowId, long accessibilityNodeId,
                int direction, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return -1;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return 0;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return 0;
                    }
                }
            }
            final int flags = (mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0;
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.focusSearch(accessibilityNodeId, direction, interactionId, callback,
                        flags, interrogatingPid, interrogatingTid);
                return getCompatibilityScale(resolvedWindowId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling accessibilityFocusSearch()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return 0;
        }

        @Override
        public boolean performAccessibilityAction(int accessibilityWindowId,
                long accessibilityNodeId, int action, Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
                throws RemoteException {
            final int resolvedWindowId;
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
                if (resolvedUserId != mCurrentUserId) {
                    return false;
                }
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                final boolean permissionGranted = mSecurityPolicy.canPerformActionLocked(this,
                        resolvedWindowId, action, arguments);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return false;
                    }
                }
            }
            final int flags = (mIncludeNotImportantViews) ?
                    AccessibilityNodeInfo.INCLUDE_NOT_IMPORTANT_VIEWS : 0;
            final int interrogatingPid = Binder.getCallingPid();
            final long identityToken = Binder.clearCallingIdentity();
            try {
                connection.performAccessibilityAction(accessibilityNodeId, action, arguments,
                        interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error calling performAccessibilityAction()");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return true;
        }

        public boolean performGlobalAction(int action) {
            synchronized (mLock) {
                final int resolvedUserId = mSecurityPolicy
                        .resolveCallingUserIdEnforcingPermissionsLocked(
                        UserHandle.getCallingUserId());
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
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void onServiceDisconnected(ComponentName componentName) {
            /* do nothing - #binderDied takes care */
        }

        public void linkToOwnDeath() throws RemoteException {
            mService.linkToDeath(this, 0);
        }

        public void unlinkToOwnDeath() {
            mService.unlinkToDeath(this, 0);
        }

        public void dispose() {
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

        public void binderDied() {
            synchronized (mLock) {
                // The death recipient is unregistered in tryRemoveServiceLocked
                tryRemoveServiceLocked(this);
                // We no longer have an automation service, so restore
                // the state based on values in the settings database.
                if (mIsAutomation) {
                    mUiAutomationService = null;
                    recreateInternalStateLocked(getUserStateLocked(mUserId));
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
                    mHandler.removeMessages(what);
                    oldEvent.recycle();
                }

                Message message = mHandler.obtainMessage(what);
                mHandler.sendMessageDelayed(message, mNotificationTimeout);
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
                if (mSecurityPolicy.canRetrieveWindowContent(this)) {
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
            mHandler.obtainMessage(MSG_ON_GESTURE, gestureId, 0).sendToTarget();
        }

        private void notifyGestureInternal(int gestureId) {
            IAccessibilityServiceClient listener = mServiceInterface;
            if (listener != null) {
                try {
                    listener.onGesture(gestureId);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error during sending gesture " + gestureId
                            + " to " + mService, re);
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
                return mSecurityPolicy.mActiveWindowId;
            }
            return accessibilityWindowId;
        }

        private float getCompatibilityScale(int windowId) {
            try {
                IBinder windowToken = mGlobalWindowTokens.get(windowId);
                if (windowToken != null) {
                    return mWindowManagerService.getWindowCompatibilityScale(windowToken);
                }
                windowToken = getCurrentUserStateLocked().mWindowTokens.get(windowId);
                if (windowToken != null) {
                    return mWindowManagerService.getWindowCompatibilityScale(windowToken);
                }
            } catch (RemoteException re) {
                /* ignore */
            }
            return 1.0f;
        }
    }

    final class SecurityPolicy {
        private static final int VALID_ACTIONS =
            AccessibilityNodeInfo.ACTION_CLICK
            | AccessibilityNodeInfo.ACTION_LONG_CLICK
            | AccessibilityNodeInfo.ACTION_FOCUS
            | AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
            | AccessibilityNodeInfo.ACTION_SELECT
            | AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
            | AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
            | AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            | AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            | AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            | AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT
            | AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT
            | AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            | AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;

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
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;

        private int mActiveWindowId;
        private boolean mTouchInteractionInProgress;

        private boolean canDispatchAccessibilityEvent(AccessibilityEvent event) {
            final int eventType = event.getEventType();
            switch (eventType) {
                // All events that are for changes in a global window
                // state should *always* be dispatched.
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                // All events generated by the user touching the
                // screen should *always* be dispatched.
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                // These will change the active window, so dispatch.
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT: {
                    return true;
                }
                // All events for changes in window content should be
                // dispatched *only* if this window is the active one.
                default:
                    return event.getWindowId() == mActiveWindowId;
            }
        }

        public void updateEventSourceLocked(AccessibilityEvent event) {
            if ((event.getEventType() & RETRIEVAL_ALLOWING_EVENT_TYPES) == 0) {
                event.setSource(null);
            }
        }

        public void updateActiveWindow(int windowId, int eventType) {
            // The active window is either the window that has input focus or
            // the window that the user is currently touching. If the user is
            // touching a window that does not have input focus as soon as the
            // the user stops touching that window the focused window becomes
            // the active one.
            switch (eventType) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    if (getFocusedWindowId() == windowId) {
                        mActiveWindowId = windowId;
                    }
                } break;
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: {
                    // Do not allow delayed hover events to confuse us
                    // which the active window is.
                    if (mTouchInteractionInProgress) {
                        mActiveWindowId = windowId;
                    }
                } break;
            }
        }

        public void onTouchInteractionStart() {
            mTouchInteractionInProgress = true;
        }

        public void onTouchInteractionEnd() {
            mTouchInteractionInProgress = false;
            // We want to set the active window to be current immediately
            // after the user has stopped touching the screen since if the
            // user types with the IME he should get a feedback for the
            // letter typed in the text view which is in the input focused
            // window. Note that we always deliver hover accessibility events
            // (they are a result of user touching the screen) so change of
            // the active window before all hover accessibility events from
            // the touched window are delivered is fine.
            mActiveWindowId = getFocusedWindowId();
        }

        public int getRetrievalAllowingWindowLocked() {
            return mActiveWindowId;
        }

        public boolean canGetAccessibilityNodeInfoLocked(Service service, int windowId) {
            return canRetrieveWindowContent(service) && isRetrievalAllowingWindow(windowId);
        }

        public boolean canPerformActionLocked(Service service, int windowId, int action,
                Bundle arguments) {
            return canRetrieveWindowContent(service)
                && isRetrievalAllowingWindow(windowId)
                && isActionPermitted(action);
        }

        public boolean canRetrieveWindowContent(Service service) {
            return service.mCanRetrieveScreenContent;
        }

        public void enforceCanRetrieveWindowContent(Service service) throws RemoteException {
            // This happens due to incorrect registration so make it apparent.
            if (!canRetrieveWindowContent(service)) {
                Slog.e(LOG_TAG, "Accessibility serivce " + service.mComponentName + " does not " +
                        "declare android:canRetrieveWindowContent.");
                throw new RemoteException();
            }
        }

        public int resolveCallingUserIdEnforcingPermissionsLocked(int userId) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID
                    || callingUid == Process.SHELL_UID) {
                return mCurrentUserId;
            }
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (callingUserId == userId) {
                return userId;
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
            return (mActiveWindowId == windowId);
        }

        private boolean isActionPermitted(int action) {
             return (VALID_ACTIONS & action) != 0;
        }

        private void enforceCallingPermission(String permission, String function) {
            if (OWN_PROCESS_ID == Binder.getCallingPid()) {
                return;
            }
            if (!hasPermission(permission)) {
                throw new SecurityException("You do not have " + permission
                        + " required to call " + function);
            }
        }

        private boolean hasPermission(String permission) {
            return mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        private int getFocusedWindowId() {
            try {
                // We call this only on window focus change or after touch
                // exploration gesture end and the shown windows are not that
                // many, so the linear look up is just fine.
                IBinder token = mWindowManagerService.getFocusedWindowToken();
                if (token != null) {
                    synchronized (mLock) {
                        int windowId = getFocusedWindowIdLocked(token, mGlobalWindowTokens);
                        if (windowId < 0) {
                            windowId = getFocusedWindowIdLocked(token,
                                    getCurrentUserStateLocked().mWindowTokens);
                        }
                        return windowId;
                    }
                }
            } catch (RemoteException re) {
                /* ignore */
            }
            return -1;
        }

        private int getFocusedWindowIdLocked(IBinder token, SparseArray<IBinder> windows) {
            final int windowCount = windows.size();
            for (int i = 0; i < windowCount; i++) {
                if (windows.valueAt(i) == token) {
                    return windows.keyAt(i);
                }
            }
            return -1;
        }
    }

    private class UserState {
        public final int mUserId;

        public final CopyOnWriteArrayList<Service> mServices = new CopyOnWriteArrayList<Service>();

        public final RemoteCallbackList<IAccessibilityManagerClient> mClients =
            new RemoteCallbackList<IAccessibilityManagerClient>();

        public final Map<ComponentName, Service> mComponentNameToServiceMap =
                new HashMap<ComponentName, Service>();

        public final List<AccessibilityServiceInfo> mInstalledServices =
                new ArrayList<AccessibilityServiceInfo>();

        public final Set<ComponentName> mEnabledServices = new HashSet<ComponentName>();

        public final Set<ComponentName> mTouchExplorationGrantedServices =
                new HashSet<ComponentName>();

        public final SparseArray<AccessibilityConnectionWrapper>
                mInteractionConnections =
                new SparseArray<AccessibilityConnectionWrapper>();

        public final SparseArray<IBinder> mWindowTokens = new SparseArray<IBinder>();

        public int mHandledFeedbackTypes = 0;

        public boolean mIsAccessibilityEnabled;
        public boolean mIsTouchExplorationEnabled;
        public boolean mIsDisplayMagnificationEnabled;

        public UserState(int userId) {
            mUserId = userId;
        }
    }

    private class TempUserStateChangeMemento {
        public int mUserId = UserHandle.USER_NULL;
        public boolean mIsAccessibilityEnabled;
        public boolean mIsTouchExplorationEnabled;
        public boolean mIsDisplayMagnificationEnabled;
        public final Set<ComponentName> mEnabledServices = new HashSet<ComponentName>();
        public final Set<ComponentName> mTouchExplorationGrantedServices =
                new HashSet<ComponentName>();

        public void initialize(int userId, UserState userState) {
            mUserId = userId;
            mIsAccessibilityEnabled = userState.mIsAccessibilityEnabled;
            mIsTouchExplorationEnabled = userState.mIsTouchExplorationEnabled;
            mIsDisplayMagnificationEnabled = userState.mIsDisplayMagnificationEnabled;
            mEnabledServices.clear();
            mEnabledServices.addAll(userState.mEnabledServices);
            mTouchExplorationGrantedServices.clear();
            mTouchExplorationGrantedServices.addAll(userState.mTouchExplorationGrantedServices);
        }

        public void applyTo(UserState userState) {
            userState.mIsAccessibilityEnabled = mIsAccessibilityEnabled;
            userState.mIsTouchExplorationEnabled = mIsTouchExplorationEnabled;
            userState.mIsDisplayMagnificationEnabled = mIsDisplayMagnificationEnabled;
            userState.mEnabledServices.clear();
            userState.mEnabledServices.addAll(mEnabledServices);
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.addAll(mTouchExplorationGrantedServices);
        }

        public void clear() {
            mUserId = UserHandle.USER_NULL;
            mIsAccessibilityEnabled = false;
            mIsTouchExplorationEnabled = false;
            mIsDisplayMagnificationEnabled = false;
            mEnabledServices.clear();
            mTouchExplorationGrantedServices.clear();
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
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mAccessibilityEnabledUri.equals(uri)) {
                synchronized (mLock) {
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        handleAccessibilityEnabledSettingChangedLocked(userState);
                        performServiceManagementLocked(userState);
                        updateInputFilterLocked(userState);
                        scheduleSendStateToClientsLocked(userState);
                    }
                }
            } else if (mTouchExplorationEnabledUri.equals(uri)) {
                synchronized (mLock) {
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        handleTouchExplorationEnabledSettingChangedLocked(userState);
                        updateInputFilterLocked(userState);
                        scheduleSendStateToClientsLocked(userState);
                    }
                }
            } else if (mDisplayMagnificationEnabledUri.equals(uri)) {
                synchronized (mLock) {
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        handleDisplayMagnificationEnabledSettingChangedLocked(userState);
                        updateInputFilterLocked(userState);
                        scheduleSendStateToClientsLocked(userState);
                    }
                }
            } else if (mEnabledAccessibilityServicesUri.equals(uri)) {
                synchronized (mLock) {
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        populateEnabledAccessibilityServicesLocked(userState);
                        manageServicesLocked(userState);
                    }
                }
            } else if (mTouchExplorationGrantedAccessibilityServicesUri.equals(uri)) {
                synchronized (mLock) {
                    // We will update when the automation service dies.
                    if (mUiAutomationService == null) {
                        UserState userState = getCurrentUserStateLocked();
                        populateTouchExplorationGrantedAccessibilityServicesLocked(userState);
                        handleTouchExplorationGrantedAccessibilityServicesChangedLocked(userState);
                    }
                }
            }
        }
    }
}
