/**
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.vr;

import static android.view.Display.INVALID_DISPLAY;

import android.Manifest;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.app.NotificationManager;
import android.app.Vr2dDisplayProperties;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.vr.IPersistentVrStateCallbacks;
import android.service.vr.IVrListener;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.service.vr.VrListenerService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.utils.ManagedApplicationService;
import com.android.server.utils.ManagedApplicationService.BinderChecker;
import com.android.server.utils.ManagedApplicationService.LogEvent;
import com.android.server.utils.ManagedApplicationService.LogFormattable;
import com.android.server.utils.ManagedApplicationService.PendingEvent;
import com.android.server.vr.EnabledComponentsObserver.EnabledComponentChangeListener;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ScreenObserver;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Service tracking whether VR mode is active, and notifying listening services of state changes.
 * <p/>
 * Services running in system server may modify the state of VrManagerService via the interface in
 * VrManagerInternal, and may register to receive callbacks when the system VR mode changes via the
 * interface given in VrStateListener.
 * <p/>
 * Device vendors may choose to receive VR state changes by implementing the VR mode HAL, e.g.:
 *  hardware/libhardware/modules/vr
 * <p/>
 * In general applications may enable or disable VR mode by calling
 * {@link android.app.Activity#setVrModeEnabled)}.  An application may also implement a service to
 * be run while in VR mode by implementing {@link android.service.vr.VrListenerService}.
 *
 * @see android.service.vr.VrListenerService
 * @see com.android.server.vr.VrManagerInternal
 * @see com.android.server.vr.VrStateListener
 *
 * @hide
 */
public class VrManagerService extends SystemService
        implements EnabledComponentChangeListener, ScreenObserver {

    public static final String TAG = "VrManagerService";
    static final boolean DBG = false;

    private static final int PENDING_STATE_DELAY_MS = 300;
    private static final int EVENT_LOG_SIZE = 64;
    private static final int INVALID_APPOPS_MODE = -1;
    /** Null set of sleep sleep flags. */
    private static final int FLAG_NONE = 0;
    /** Flag set when the device is not sleeping. */
    private static final int FLAG_AWAKE = 1 << 0;
    /** Flag set when the screen has been turned on. */
    private static final int FLAG_SCREEN_ON = 1 << 1;
    /** Flag set when the keyguard is not active. */
    private static final int FLAG_KEYGUARD_UNLOCKED = 1 << 2;
    /** Flag indicating that all system sleep flags have been set.*/
    private static final int FLAG_ALL = FLAG_AWAKE | FLAG_SCREEN_ON | FLAG_KEYGUARD_UNLOCKED;

    private static native void initializeNative();
    private static native void setVrModeNative(boolean enabled);

    private final Object mLock = new Object();

    private final IBinder mOverlayToken = new Binder();

    // State protected by mLock
    private boolean mVrModeAllowed;
    private boolean mVrModeEnabled;
    private boolean mPersistentVrModeEnabled;
    private boolean mRunning2dInVr;
    private int mVrAppProcessId;
    private EnabledComponentsObserver mComponentObserver;
    private ManagedApplicationService mCurrentVrService;
    private ManagedApplicationService mCurrentVrCompositorService;
    private ComponentName mDefaultVrService;
    private Context mContext;
    private ComponentName mCurrentVrModeComponent;
    private int mCurrentVrModeUser;
    private boolean mWasDefaultGranted;
    private boolean mGuard;
    private final RemoteCallbackList<IVrStateCallbacks> mVrStateRemoteCallbacks =
            new RemoteCallbackList<>();
    private final RemoteCallbackList<IPersistentVrStateCallbacks>
            mPersistentVrStateRemoteCallbacks = new RemoteCallbackList<>();
    private int mPreviousCoarseLocationMode = INVALID_APPOPS_MODE;
    private int mPreviousManageOverlayMode = INVALID_APPOPS_MODE;
    private VrState mPendingState;
    private boolean mLogLimitHit;
    private final ArrayDeque<LogFormattable> mLoggingDeque = new ArrayDeque<>(EVENT_LOG_SIZE);
    private final NotificationAccessManager mNotifAccessManager = new NotificationAccessManager();
    private INotificationManager mNotificationManager;
    /** Tracks the state of the screen and keyguard UI.*/
    private int mSystemSleepFlags = FLAG_AWAKE | FLAG_KEYGUARD_UNLOCKED;
    /**
     * Set when ACTION_USER_UNLOCKED is fired. We shouldn't try to bind to the
     * vr service before then. This gets set only once the first time the user unlocks the device
     * and stays true thereafter.
     */
    private boolean mUserUnlocked;
    private Vr2dDisplay mVr2dDisplay;
    private boolean mBootsToVr;
    private boolean mStandby;
    private boolean mUseStandbyToExitVrMode;

    // Handles events from the managed services (e.g. VrListenerService and any bound VR compositor
    // service).
    private final ManagedApplicationService.EventCallback mEventCallback
                = new ManagedApplicationService.EventCallback() {
        @Override
        public void onServiceEvent(LogEvent event) {
            logEvent(event);

            ComponentName component = null;
            synchronized (mLock) {
                component = ((mCurrentVrService == null) ? null : mCurrentVrService.getComponent());

                // If the VrCore main service was disconnected or the binding died we'll rebind
                // automatically. Call focusedActivityChanged() once we rebind.
                if (component != null && component.equals(event.component) &&
                        (event.event == LogEvent.EVENT_DISCONNECTED ||
                         event.event == LogEvent.EVENT_BINDING_DIED)) {
                    callFocusedActivityChangedLocked();
                }
            }

            // If not on an AIO device and we permanently stopped trying to connect to the
            // VrListenerService (or don't have one bound), leave persistent VR mode and VR mode.
            if (!mBootsToVr && event.event == LogEvent.EVENT_STOPPED_PERMANENTLY &&
                    (component == null || component.equals(event.component))) {
                Slog.e(TAG, "VrListenerSevice has died permanently, leaving system VR mode.");
                // We're not a native VR device.  Leave VR + persistent mode.
                setPersistentVrModeEnabled(false);
            }
        }
    };

    private static final int MSG_VR_STATE_CHANGE = 0;
    private static final int MSG_PENDING_VR_STATE_CHANGE = 1;
    private static final int MSG_PERSISTENT_VR_MODE_STATE_CHANGE = 2;

    /**
     * Set whether VR mode may be enabled.
     * <p/>
     * If VR mode is not allowed to be enabled, calls to set VR mode will be cached.  When VR mode
     * is again allowed to be enabled, the most recent cached state will be applied.
     *
     */
    private void updateVrModeAllowedLocked() {
        boolean ignoreSleepFlags = mBootsToVr && mUseStandbyToExitVrMode;
        boolean disallowedByStandby = mStandby && mUseStandbyToExitVrMode;
        boolean allowed = (mSystemSleepFlags == FLAG_ALL || ignoreSleepFlags) && mUserUnlocked
                && !disallowedByStandby;
        if (mVrModeAllowed != allowed) {
            mVrModeAllowed = allowed;
            if (DBG) Slog.d(TAG, "VR mode is " + ((allowed) ? "allowed" : "disallowed"));
            if (mVrModeAllowed) {
                if (mBootsToVr) {
                    setPersistentVrModeEnabled(true);
                }
                if (mBootsToVr && !mVrModeEnabled) {
                  setVrMode(true, mDefaultVrService, 0, -1, null);
                }
            } else {
                // Disable persistent mode when VR mode isn't allowed, allows an escape hatch to
                // exit persistent VR mode when screen is turned off.
                setPersistentModeAndNotifyListenersLocked(false);

                // Set pending state to current state.
                mPendingState = (mVrModeEnabled && mCurrentVrService != null)
                    ? new VrState(mVrModeEnabled, mRunning2dInVr, mCurrentVrService.getComponent(),
                        mCurrentVrService.getUserId(), mVrAppProcessId, mCurrentVrModeComponent)
                    : null;

                // Unbind current VR service and do necessary callbacks.
                updateCurrentVrServiceLocked(false, false, null, 0, -1, null);
            }
        }
    }

    private void setScreenOn(boolean isScreenOn) {
        setSystemState(FLAG_SCREEN_ON, isScreenOn);
    }

    @Override
    public void onAwakeStateChanged(boolean isAwake) {
        setSystemState(FLAG_AWAKE, isAwake);
    }

    @Override
    public void onKeyguardStateChanged(boolean isShowing) {
        setSystemState(FLAG_KEYGUARD_UNLOCKED, !isShowing);
    }

    private void setSystemState(int flags, boolean isOn) {
        synchronized(mLock) {
            int oldState = mSystemSleepFlags;
            if (isOn) {
                mSystemSleepFlags |= flags;
            } else {
                mSystemSleepFlags &= ~flags;
            }
            if (oldState != mSystemSleepFlags) {
                if (DBG) Slog.d(TAG, "System state: " + getStateAsString());
                updateVrModeAllowedLocked();
            }
        }
    }

    private String getStateAsString() {
        return new StringBuilder()
                .append((mSystemSleepFlags & FLAG_AWAKE) != 0 ? "awake, " : "")
                .append((mSystemSleepFlags & FLAG_SCREEN_ON) != 0 ? "screen_on, " : "")
                .append((mSystemSleepFlags & FLAG_KEYGUARD_UNLOCKED) != 0 ? "keyguard_off" : "")
                .toString();
    }

    private void setUserUnlocked() {
        synchronized(mLock) {
            mUserUnlocked = true;
            updateVrModeAllowedLocked();
        }
    }

    private void setStandbyEnabled(boolean standby) {
        synchronized(mLock) {
            if (!mBootsToVr) {
                Slog.e(TAG, "Attempting to set standby mode on a non-standalone device");
                return;
            }
            mStandby = standby;
            updateVrModeAllowedLocked();
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_VR_STATE_CHANGE : {
                    boolean state = (msg.arg1 == 1);
                    int i = mVrStateRemoteCallbacks.beginBroadcast();
                    while (i > 0) {
                        i--;
                        try {
                            mVrStateRemoteCallbacks.getBroadcastItem(i).onVrStateChanged(state);
                        } catch (RemoteException e) {
                            // Noop
                        }
                    }
                    mVrStateRemoteCallbacks.finishBroadcast();
                } break;
                case MSG_PENDING_VR_STATE_CHANGE : {
                    synchronized(mLock) {
                        if (mVrModeAllowed) {
                           VrManagerService.this.consumeAndApplyPendingStateLocked();
                        }
                    }
                } break;
                case MSG_PERSISTENT_VR_MODE_STATE_CHANGE : {
                    boolean state = (msg.arg1 == 1);
                    int i = mPersistentVrStateRemoteCallbacks.beginBroadcast();
                    while (i > 0) {
                        i--;
                        try {
                            mPersistentVrStateRemoteCallbacks.getBroadcastItem(i)
                                    .onPersistentVrStateChanged(state);
                        } catch (RemoteException e) {
                            // Noop
                        }
                    }
                    mPersistentVrStateRemoteCallbacks.finishBroadcast();
                } break;
                default :
                    throw new IllegalStateException("Unknown message type: " + msg.what);
            }
        }
    };

    // Event used to log when settings are changed for dumpsys logs.
    private static class SettingEvent implements LogFormattable {
        public final long timestamp;
        public final String what;

        SettingEvent(String what) {
            this.timestamp = System.currentTimeMillis();
            this.what = what;
        }

        @Override
        public String toLogString(SimpleDateFormat dateFormat) {
            return dateFormat.format(new Date(timestamp)) + "   " + what;
        }
    }

    // Event used to track changes of the primary on-screen VR activity.
    private static class VrState implements LogFormattable {
        final boolean enabled;
        final boolean running2dInVr;
        final int userId;
        final int processId;
        final ComponentName targetPackageName;
        final ComponentName callingPackage;
        final long timestamp;
        final boolean defaultPermissionsGranted;

        VrState(boolean enabled, boolean running2dInVr, ComponentName targetPackageName, int userId,
                int processId, ComponentName callingPackage) {
            this.enabled = enabled;
            this.running2dInVr = running2dInVr;
            this.userId = userId;
            this.processId = processId;
            this.targetPackageName = targetPackageName;
            this.callingPackage = callingPackage;
            this.defaultPermissionsGranted = false;
            this.timestamp = System.currentTimeMillis();
        }

        VrState(boolean enabled, boolean running2dInVr, ComponentName targetPackageName, int userId,
            int processId, ComponentName callingPackage, boolean defaultPermissionsGranted) {
            this.enabled = enabled;
            this.running2dInVr = running2dInVr;
            this.userId = userId;
            this.processId = processId;
            this.targetPackageName = targetPackageName;
            this.callingPackage = callingPackage;
            this.defaultPermissionsGranted = defaultPermissionsGranted;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toLogString(SimpleDateFormat dateFormat) {
            String tab = "  ";
            String newLine = "\n";
            StringBuilder sb = new StringBuilder(dateFormat.format(new Date(timestamp)));
            sb.append(tab);
            sb.append("State changed to:");
            sb.append(tab);
            sb.append((enabled) ? "ENABLED" : "DISABLED");
            sb.append(newLine);
            if (enabled) {
                sb.append(tab);
                sb.append("User=");
                sb.append(userId);
                sb.append(newLine);
                sb.append(tab);
                sb.append("Current VR Activity=");
                sb.append((callingPackage == null) ? "None" : callingPackage.flattenToString());
                sb.append(newLine);
                sb.append(tab);
                sb.append("Bound VrListenerService=");
                sb.append((targetPackageName == null) ? "None"
                        : targetPackageName.flattenToString());
                sb.append(newLine);
                if (defaultPermissionsGranted) {
                    sb.append(tab);
                    sb.append("Default permissions granted to the bound VrListenerService.");
                    sb.append(newLine);
                }
            }
            return sb.toString();
        }
    }

    private static final BinderChecker sBinderChecker = new BinderChecker() {
        @Override
        public IInterface asInterface(IBinder binder) {
            return IVrListener.Stub.asInterface(binder);
        }

        @Override
        public boolean checkType(IInterface service) {
            return service instanceof IVrListener;
        }
    };

    private final class NotificationAccessManager {
        private final SparseArray<ArraySet<String>> mAllowedPackages = new SparseArray<>();
        private final ArrayMap<String, Integer> mNotificationAccessPackageToUserId =
                new ArrayMap<>();

        public void update(Collection<String> packageNames) {
            int currentUserId = ActivityManager.getCurrentUser();

            ArraySet<String> allowed = mAllowedPackages.get(currentUserId);
            if (allowed == null) {
                allowed = new ArraySet<>();
            }

            // Make sure we revoke notification access for listeners in other users
            final int listenerCount = mNotificationAccessPackageToUserId.size();
            for (int i = listenerCount - 1; i >= 0; i--) {
                final int grantUserId = mNotificationAccessPackageToUserId.valueAt(i);
                if (grantUserId != currentUserId) {
                    String packageName = mNotificationAccessPackageToUserId.keyAt(i);
                    revokeNotificationListenerAccess(packageName, grantUserId);
                    revokeNotificationPolicyAccess(packageName);
                    revokeCoarseLocationPermissionIfNeeded(packageName, grantUserId);
                    mNotificationAccessPackageToUserId.removeAt(i);
                }
            }

            for (String pkg : allowed) {
                if (!packageNames.contains(pkg)) {
                    revokeNotificationListenerAccess(pkg, currentUserId);
                    revokeNotificationPolicyAccess(pkg);
                    revokeCoarseLocationPermissionIfNeeded(pkg, currentUserId);
                    mNotificationAccessPackageToUserId.remove(pkg);
                }
            }
            for (String pkg : packageNames) {
                if (!allowed.contains(pkg)) {
                    grantNotificationPolicyAccess(pkg);
                    grantNotificationListenerAccess(pkg, currentUserId);
                    grantCoarseLocationPermissionIfNeeded(pkg, currentUserId);
                    mNotificationAccessPackageToUserId.put(pkg, currentUserId);
                }
            }

            allowed.clear();
            allowed.addAll(packageNames);
            mAllowedPackages.put(currentUserId, allowed);
        }
    }

    /**
     * Called when a user, package, or setting changes that could affect whether or not the
     * currently bound VrListenerService is changed.
     */
    @Override
    public void onEnabledComponentChanged() {
        synchronized (mLock) {
            int currentUser = ActivityManager.getCurrentUser();
            // Update listeners
            ArraySet<ComponentName> enabledListeners = mComponentObserver.getEnabled(currentUser);

            ArraySet<String> enabledPackages = new ArraySet<>();
            for (ComponentName n : enabledListeners) {
                String pkg = n.getPackageName();
                if (isDefaultAllowed(pkg)) {
                    enabledPackages.add(n.getPackageName());
                }
            }
            mNotifAccessManager.update(enabledPackages);

            if (!mVrModeAllowed) {
                return; // Don't do anything, we shouldn't be in VR mode.
            }

            // If there is a pending state change, we'd better deal with that first
            consumeAndApplyPendingStateLocked(false);

            if (mCurrentVrService == null) {
                return; // No active services
            }

            // There is an active service, update it if needed
            updateCurrentVrServiceLocked(mVrModeEnabled, mRunning2dInVr,
                    mCurrentVrService.getComponent(), mCurrentVrService.getUserId(),
                    mVrAppProcessId, mCurrentVrModeComponent);
        }
    }

    private final IVrManager mVrManager = new IVrManager.Stub() {

        @Override
        public void registerListener(IVrStateCallbacks cb) {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.addStateCallback(cb);
        }

        @Override
        public void unregisterListener(IVrStateCallbacks cb) {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.removeStateCallback(cb);
        }

        @Override
        public void registerPersistentVrStateListener(IPersistentVrStateCallbacks cb) {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.addPersistentStateCallback(cb);
        }

        @Override
        public void unregisterPersistentVrStateListener(IPersistentVrStateCallbacks cb) {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            if (cb == null) {
                throw new IllegalArgumentException("Callback binder object is null.");
            }

            VrManagerService.this.removePersistentStateCallback(cb);
        }

        @Override
        public boolean getVrModeState() {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            return VrManagerService.this.getVrMode();
        }

        @Override
        public boolean getPersistentVrModeEnabled() {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER,
                    Manifest.permission.ACCESS_VR_STATE);
            return VrManagerService.this.getPersistentVrMode();
        }

        @Override
        public void setPersistentVrModeEnabled(boolean enabled) {
            enforceCallerPermissionAnyOf(Manifest.permission.RESTRICTED_VR_ACCESS);
            VrManagerService.this.setPersistentVrModeEnabled(enabled);
        }

        @Override
        public void setVr2dDisplayProperties(
                Vr2dDisplayProperties vr2dDisplayProp) {
            enforceCallerPermissionAnyOf(Manifest.permission.RESTRICTED_VR_ACCESS);
            VrManagerService.this.setVr2dDisplayProperties(vr2dDisplayProp);
        }

        @Override
        public int getVr2dDisplayId() {
            return VrManagerService.this.getVr2dDisplayId();
        }

        @Override
        public void setAndBindCompositor(String componentName) {
            enforceCallerPermissionAnyOf(Manifest.permission.RESTRICTED_VR_ACCESS);
            VrManagerService.this.setAndBindCompositor(
                (componentName == null) ? null : ComponentName.unflattenFromString(componentName));
        }

        @Override
        public void setStandbyEnabled(boolean standby) {
            enforceCallerPermissionAnyOf(Manifest.permission.ACCESS_VR_MANAGER);
            VrManagerService.this.setStandbyEnabled(standby);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            pw.println("********* Dump of VrManagerService *********");
            pw.println("VR mode is currently: " + ((mVrModeAllowed) ? "allowed" : "disallowed"));
            pw.println("Persistent VR mode is currently: " +
                    ((mPersistentVrModeEnabled) ? "enabled" : "disabled"));
            pw.println("Currently bound VR listener service: "
                    + ((mCurrentVrService == null)
                    ? "None" : mCurrentVrService.getComponent().flattenToString()));
            pw.println("Currently bound VR compositor service: "
                    + ((mCurrentVrCompositorService == null)
                    ? "None" : mCurrentVrCompositorService.getComponent().flattenToString()));
            pw.println("Previous state transitions:\n");
            String tab = "  ";
            dumpStateTransitions(pw);
            pw.println("\n\nRemote Callbacks:");
            int i=mVrStateRemoteCallbacks.beginBroadcast(); // create the broadcast item array
            while(i-->0) {
                pw.print(tab);
                pw.print(mVrStateRemoteCallbacks.getBroadcastItem(i));
                if (i>0) pw.println(",");
            }
            mVrStateRemoteCallbacks.finishBroadcast();
            pw.println("\n\nPersistent Vr State Remote Callbacks:");
            i=mPersistentVrStateRemoteCallbacks.beginBroadcast();
            while(i-->0) {
                pw.print(tab);
                pw.print(mPersistentVrStateRemoteCallbacks.getBroadcastItem(i));
                if (i>0) pw.println(",");
            }
            mPersistentVrStateRemoteCallbacks.finishBroadcast();
            pw.println("\n");
            pw.println("Installed VrListenerService components:");
            int userId = mCurrentVrModeUser;
            ArraySet<ComponentName> installed = mComponentObserver.getInstalled(userId);
            if (installed == null || installed.size() == 0) {
                pw.println("None");
            } else {
                for (ComponentName n : installed) {
                    pw.print(tab);
                    pw.println(n.flattenToString());
                }
            }
            pw.println("Enabled VrListenerService components:");
            ArraySet<ComponentName> enabled = mComponentObserver.getEnabled(userId);
            if (enabled == null || enabled.size() == 0) {
                pw.println("None");
            } else {
                for (ComponentName n : enabled) {
                    pw.print(tab);
                    pw.println(n.flattenToString());
                }
            }
            pw.println("\n");
            pw.println("********* End of VrManagerService Dump *********");
        }

    };

    /**
     * Enforces that at lease one of the specified permissions is held by the caller.
     * Throws SecurityException if none of the specified permissions are held.
     *
     * @param permissions One or more permissions to check against.
     */
    private void enforceCallerPermissionAnyOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException("Caller does not hold at least one of the permissions: "
                + Arrays.toString(permissions));
    }

    /**
     * Implementation of VrManagerInternal.  Callable only from system services.
     */
    private final class LocalService extends VrManagerInternal {
        @Override
        public void setVrMode(boolean enabled, ComponentName packageName, int userId, int processId,
                ComponentName callingPackage) {
            VrManagerService.this.setVrMode(enabled, packageName, userId, processId, callingPackage);
        }

        @Override
        public void onScreenStateChanged(boolean isScreenOn) {
            VrManagerService.this.setScreenOn(isScreenOn);
        }

        @Override
        public boolean isCurrentVrListener(String packageName, int userId) {
            return VrManagerService.this.isCurrentVrListener(packageName, userId);
        }

        @Override
        public int hasVrPackage(ComponentName packageName, int userId) {
            return VrManagerService.this.hasVrPackage(packageName, userId);
        }

        @Override
        public void setPersistentVrModeEnabled(boolean enabled) {
            VrManagerService.this.setPersistentVrModeEnabled(enabled);
        }

        @Override
        public void setVr2dDisplayProperties(
            Vr2dDisplayProperties compatDisplayProp) {
            VrManagerService.this.setVr2dDisplayProperties(compatDisplayProp);
        }

        @Override
        public int getVr2dDisplayId() {
            return VrManagerService.this.getVr2dDisplayId();
        }

        @Override
        public void addPersistentVrModeStateListener(IPersistentVrStateCallbacks listener) {
            VrManagerService.this.addPersistentStateCallback(listener);
        }
    }

    public VrManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        synchronized(mLock) {
            initializeNative();
            mContext = getContext();
        }

        mBootsToVr = SystemProperties.getBoolean("ro.boot.vr", false);
        mUseStandbyToExitVrMode = mBootsToVr
                && SystemProperties.getBoolean("persist.vr.use_standby_to_exit_vr_mode", true);
        publishLocalService(VrManagerInternal.class, new LocalService());
        publishBinderService(Context.VR_SERVICE, mVrManager.asBinder());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            LocalServices.getService(ActivityTaskManagerInternal.class)
                    .registerScreenObserver(this);

            mNotificationManager = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            synchronized (mLock) {
                Looper looper = Looper.getMainLooper();
                Handler handler = new Handler(looper);
                ArrayList<EnabledComponentChangeListener> listeners = new ArrayList<>();
                listeners.add(this);
                mComponentObserver = EnabledComponentsObserver.build(mContext, handler,
                        Settings.Secure.ENABLED_VR_LISTENERS, looper,
                        android.Manifest.permission.BIND_VR_LISTENER_SERVICE,
                        VrListenerService.SERVICE_INTERFACE, mLock, listeners);

                mComponentObserver.rebuildAll();
            }

            //TODO: something more robust than picking the first one
            ArraySet<ComponentName> defaultVrComponents =
                    SystemConfig.getInstance().getDefaultVrComponents();
            if (defaultVrComponents.size() > 0) {
                mDefaultVrService = defaultVrComponents.valueAt(0);
            } else {
                Slog.i(TAG, "No default vr listener service found.");
            }

            DisplayManager dm =
                    (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            mVr2dDisplay = new Vr2dDisplay(
                    dm,
                    LocalServices.getService(ActivityManagerInternal.class),
                    LocalServices.getService(WindowManagerInternal.class),
                    mVrManager);
            mVr2dDisplay.init(getContext(), mBootsToVr);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            getContext().registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                            VrManagerService.this.setUserUnlocked();
                        }
                    }
                }, intentFilter);
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        FgThread.getHandler().post(() -> {
            synchronized (mLock) {
                mComponentObserver.onUsersChanged();
            }
        });

    }

    @Override
    public void onStopUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }

    }

    @Override
    public void onCleanupUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    private void updateOverlayStateLocked(String exemptedPackage, int newUserId, int oldUserId) {
        AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);

        // If user changed drop restrictions for the old user.
        if (oldUserId != newUserId) {
            appOpsManager.setUserRestrictionForUser(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                    false, mOverlayToken, null, oldUserId);
        }

        // Apply the restrictions for the current user based on vr state
        String[] exemptions = (exemptedPackage == null) ? new String[0] :
                new String[] { exemptedPackage };

        appOpsManager.setUserRestrictionForUser(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                mVrModeEnabled, mOverlayToken, exemptions, newUserId);
    }

    private void updateDependentAppOpsLocked(String newVrServicePackage, int newUserId,
            String oldVrServicePackage, int oldUserId) {
        // If VR state changed and we also have a VR service change.
        if (Objects.equals(newVrServicePackage, oldVrServicePackage)) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            // Set overlay exception state based on VR enabled and current service
            updateOverlayStateLocked(newVrServicePackage, newUserId, oldUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Send VR mode changes (if the mode state has changed), and update the bound/unbound state of
     * the currently selected VR listener service.  If the component selected for the VR listener
     * service has changed, unbind the previous listener and bind the new listener (if enabled).
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state for VR mode.
     * @param running2dInVr true if we have a top-level 2D intent.
     * @param component new component to be bound as a VR listener.
     * @param userId user owning the component to be bound.
     * @param processId the process hosting the activity specified by calling.
     * @param calling the component currently using VR mode or a 2D intent.
     *
     * @return {@code true} if the component/user combination specified is valid.
     */
    private boolean updateCurrentVrServiceLocked(boolean enabled, boolean running2dInVr,
            @NonNull ComponentName component, int userId, int processId, ComponentName calling) {

        boolean sendUpdatedCaller = false;
        final long identity = Binder.clearCallingIdentity();
        try {

            boolean validUserComponent = (mComponentObserver.isValid(component, userId) ==
                    EnabledComponentsObserver.NO_ERROR);
            boolean goingIntoVrMode = validUserComponent && enabled;
            if (!mVrModeEnabled && !goingIntoVrMode) {
                return validUserComponent; // Disabled -> Disabled transition does nothing.
            }

            String oldVrServicePackage = mCurrentVrService != null
                    ? mCurrentVrService.getComponent().getPackageName() : null;
            final int oldUserId = mCurrentVrModeUser;

            // Notify system services and VR HAL of mode change.
            changeVrModeLocked(goingIntoVrMode);

            boolean nothingChanged = false;
            if (!goingIntoVrMode) {
                // Not going into VR mode, unbind whatever is running
                if (mCurrentVrService != null) {
                    Slog.i(TAG, "Leaving VR mode, disconnecting "
                        + mCurrentVrService.getComponent() + " for user "
                        + mCurrentVrService.getUserId());
                    mCurrentVrService.disconnect();
                    updateCompositorServiceLocked(UserHandle.USER_NULL, null);
                    mCurrentVrService = null;
                } else {
                    nothingChanged = true;
                }
            } else {
                // Going into VR mode
                if (mCurrentVrService != null) {
                    // Unbind any running service that doesn't match the latest component/user
                    // selection.
                    if (mCurrentVrService.disconnectIfNotMatching(component, userId)) {
                        Slog.i(TAG, "VR mode component changed to " + component
                            + ", disconnecting " + mCurrentVrService.getComponent()
                            + " for user " + mCurrentVrService.getUserId());
                        updateCompositorServiceLocked(UserHandle.USER_NULL, null);
                        createAndConnectService(component, userId);
                        sendUpdatedCaller = true;
                    } else {
                        nothingChanged = true;
                    }
                    // The service with the correct component/user is already bound, do nothing.
                } else {
                    // Nothing was previously running, bind a new service for the latest
                    // component/user selection.
                    createAndConnectService(component, userId);
                    sendUpdatedCaller = true;
                }
            }

            if ((calling != null || mPersistentVrModeEnabled)
                    && !Objects.equals(calling, mCurrentVrModeComponent)
                    || mRunning2dInVr != running2dInVr) {
                sendUpdatedCaller = true;
            }
            mCurrentVrModeComponent = calling;
            mRunning2dInVr = running2dInVr;
            mVrAppProcessId = processId;

            if (mCurrentVrModeUser != userId) {
                mCurrentVrModeUser = userId;
                sendUpdatedCaller = true;
            }

            String newVrServicePackage = mCurrentVrService != null
                    ? mCurrentVrService.getComponent().getPackageName() : null;
            final int newUserId = mCurrentVrModeUser;

            // Update AppOps settings that change state when entering/exiting VR mode, or changing
            // the current VrListenerService.
            updateDependentAppOpsLocked(newVrServicePackage, newUserId,
                    oldVrServicePackage, oldUserId);

            if (mCurrentVrService != null && sendUpdatedCaller) {
                callFocusedActivityChangedLocked();
            }

            if (!nothingChanged) {
                logStateLocked();
            }

            return validUserComponent;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void callFocusedActivityChangedLocked() {
        final ComponentName c = mCurrentVrModeComponent;
        final boolean b = mRunning2dInVr;
        final int pid = mVrAppProcessId;
        mCurrentVrService.sendEvent(new PendingEvent() {
            @Override
            public void runEvent(IInterface service) throws RemoteException {
                // Under specific (and unlikely) timing scenarios, when VrCore
                // crashes and is rebound, focusedActivityChanged() may be
                // called a 2nd time with the same arguments. IVrListeners
                // should make sure to handle that scenario gracefully.
                IVrListener l = (IVrListener) service;
                l.focusedActivityChanged(c, b, pid);
            }
        });
    }

    private boolean isDefaultAllowed(String packageName) {
        PackageManager pm = mContext.getPackageManager();

        ApplicationInfo info = null;
        try {
            info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
        }

        if (info == null || !(info.isSystemApp() || info.isUpdatedSystemApp())) {
            return false;
        }
        return true;
    }

    private void grantNotificationPolicyAccess(String pkg) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        nm.setNotificationPolicyAccessGranted(pkg, true);
    }

    private void revokeNotificationPolicyAccess(String pkg) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        // Remove any DND zen rules possibly created by the package.
        nm.removeAutomaticZenRules(pkg);
        // Remove Notification Policy Access.
        nm.setNotificationPolicyAccessGranted(pkg, false);
    }

    private void grantNotificationListenerAccess(String pkg, int userId) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        PackageManager pm = mContext.getPackageManager();
        ArraySet<ComponentName> possibleServices = EnabledComponentsObserver.loadComponentNames(pm,
                userId, NotificationListenerService.SERVICE_INTERFACE,
                android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE);

        for (ComponentName c : possibleServices) {
            if (Objects.equals(c.getPackageName(), pkg)) {
                nm.setNotificationListenerAccessGrantedForUser(c, userId, true);
            }
        }
    }

    private void revokeNotificationListenerAccess(String pkg, int userId) {
        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        List<ComponentName> current = nm.getEnabledNotificationListeners(userId);

        for (ComponentName component : current) {
            if (component != null && component.getPackageName().equals(pkg)) {
                nm.setNotificationListenerAccessGrantedForUser(component, userId, false);
            }
        }
    }

    private void grantCoarseLocationPermissionIfNeeded(String pkg, int userId) {
        // Don't clobber the user if permission set in current state explicitly
        if (!isPermissionUserUpdated(Manifest.permission.ACCESS_COARSE_LOCATION, pkg, userId)) {
            try {
                mContext.getPackageManager().grantRuntimePermission(pkg,
                        Manifest.permission.ACCESS_COARSE_LOCATION, new UserHandle(userId));
            } catch (IllegalArgumentException e) {
                // Package was removed during update.
                Slog.w(TAG, "Could not grant coarse location permission, package " + pkg
                    + " was removed.");
            }
        }
    }

    private void revokeCoarseLocationPermissionIfNeeded(String pkg, int userId) {
        // Don't clobber the user if permission set in current state explicitly
        if (!isPermissionUserUpdated(Manifest.permission.ACCESS_COARSE_LOCATION, pkg, userId)) {
            try {
                mContext.getPackageManager().revokeRuntimePermission(pkg,
                        Manifest.permission.ACCESS_COARSE_LOCATION, new UserHandle(userId));
            } catch (IllegalArgumentException e) {
                // Package was removed during update.
                Slog.w(TAG, "Could not revoke coarse location permission, package " + pkg
                    + " was removed.");
            }
        }
    }

    private boolean isPermissionUserUpdated(String permission, String pkg, int userId) {
        final int flags = mContext.getPackageManager().getPermissionFlags(
                permission, pkg, new UserHandle(userId));
        return (flags & (PackageManager.FLAG_PERMISSION_USER_SET
                | PackageManager.FLAG_PERMISSION_USER_FIXED)) != 0;
    }

    private ArraySet<String> getNotificationListeners(ContentResolver resolver, int userId) {
        String flat = Settings.Secure.getStringForUser(resolver,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS, userId);

        ArraySet<String> current = new ArraySet<>();
        if (flat != null) {
            String[] allowed = flat.split(":");
            for (String s : allowed) {
                if (!TextUtils.isEmpty(s)) {
                    current.add(s);
                }
            }
        }
        return current;
    }

    private static String formatSettings(Collection<String> c) {
        if (c == null || c.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (String s : c) {
            if ("".equals(s)) {
                continue;
            }
            if (!start) {
                b.append(':');
            }
            b.append(s);
            start = false;
        }
        return b.toString();
    }



    private void createAndConnectService(@NonNull ComponentName component, int userId) {
        mCurrentVrService = createVrListenerService(component, userId);
        mCurrentVrService.connect();
        Slog.i(TAG, "Connecting " + component + " for user " + userId);
    }

    /**
     * Send VR mode change callbacks to HAL and system services if mode has actually changed.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state of the VR mode.
     */
    private void changeVrModeLocked(boolean enabled) {
        if (mVrModeEnabled != enabled) {
            mVrModeEnabled = enabled;

            // Log mode change event.
            Slog.i(TAG, "VR mode " + ((mVrModeEnabled) ? "enabled" : "disabled"));
            setVrModeNative(mVrModeEnabled);

            onVrModeChangedLocked();
        }
    }

    /**
     * Notify system services of VR mode change.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     */
    private void onVrModeChangedLocked() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_VR_STATE_CHANGE,
                (mVrModeEnabled) ? 1 : 0, 0));
    }

    /**
     * Helper function for making ManagedApplicationService for VrListenerService instances.
     */
    private ManagedApplicationService createVrListenerService(@NonNull ComponentName component,
            int userId) {
        int retryType = (mBootsToVr) ? ManagedApplicationService.RETRY_FOREVER
                : ManagedApplicationService.RETRY_NEVER;
        return ManagedApplicationService.build(mContext, component, userId,
                R.string.vr_listener_binding_label, Settings.ACTION_VR_LISTENER_SETTINGS,
                sBinderChecker, /*isImportant*/true, retryType, mHandler, mEventCallback);
    }

    /**
     * Helper function for making ManagedApplicationService for VR Compositor instances.
     */
    private ManagedApplicationService createVrCompositorService(@NonNull ComponentName component,
            int userId) {
        int retryType = (mBootsToVr) ? ManagedApplicationService.RETRY_FOREVER
                : ManagedApplicationService.RETRY_BEST_EFFORT;
        return ManagedApplicationService.build(mContext, component, userId, /*clientLabel*/0,
                /*settingsAction*/null, /*binderChecker*/null, /*isImportant*/true, retryType,
                mHandler, /*disconnectCallback*/mEventCallback);
    }

    /**
     * Apply the pending VR state. If no state is pending, disconnect any currently bound
     * VR listener service.
     */
    private void consumeAndApplyPendingStateLocked() {
        consumeAndApplyPendingStateLocked(true);
    }

    /**
     * Apply the pending VR state.
     *
     * @param disconnectIfNoPendingState if {@code true}, then any currently bound VR listener
     *     service will be disconnected if no state is pending. If this is {@code false} then the
     *     nothing will be changed when there is no pending state.
     */
    private void consumeAndApplyPendingStateLocked(boolean disconnectIfNoPendingState) {
        if (mPendingState != null) {
            updateCurrentVrServiceLocked(mPendingState.enabled, mPendingState.running2dInVr,
                    mPendingState.targetPackageName, mPendingState.userId, mPendingState.processId,
                    mPendingState.callingPackage);
            mPendingState = null;
        } else if (disconnectIfNoPendingState) {
            updateCurrentVrServiceLocked(false, false, null, 0, -1, null);
        }
    }

    private void logStateLocked() {
        ComponentName currentBoundService = (mCurrentVrService == null) ? null :
                mCurrentVrService.getComponent();
        logEvent(new VrState(mVrModeEnabled, mRunning2dInVr, currentBoundService,
                mCurrentVrModeUser, mVrAppProcessId, mCurrentVrModeComponent, mWasDefaultGranted));
    }

    private void logEvent(LogFormattable event) {
        synchronized (mLoggingDeque) {
            if (mLoggingDeque.size() == EVENT_LOG_SIZE) {
                mLoggingDeque.removeFirst();
                mLogLimitHit = true;
            }
            mLoggingDeque.add(event);
        }
    }

    private void dumpStateTransitions(PrintWriter pw) {
        SimpleDateFormat d = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        synchronized (mLoggingDeque) {
            if (mLoggingDeque.size() == 0) {
                pw.print("  ");
                pw.println("None");
            }

            if (mLogLimitHit) {
                pw.println("..."); // Indicates log overflow
            }

            for (LogFormattable event : mLoggingDeque) {
                pw.println(event.toLogString(d));
            }
        }
    }

    /*
     * Implementation of VrManagerInternal calls.  These are callable from system services.
     */
    private void setVrMode(boolean enabled, @NonNull ComponentName targetPackageName,
            int userId, int processId, @NonNull ComponentName callingPackage) {

        synchronized (mLock) {
            VrState pending;
            ComponentName targetListener;

            // If the device is in persistent VR mode, then calls to disable VR mode are ignored,
            // and the system default VR listener is used.
            boolean targetEnabledState = enabled || mPersistentVrModeEnabled;
            boolean running2dInVr = !enabled && mPersistentVrModeEnabled;
            if (running2dInVr) {
                targetListener = mDefaultVrService;
            } else {
                targetListener = targetPackageName;
            }

            pending = new VrState(targetEnabledState, running2dInVr, targetListener,
                    userId, processId, callingPackage);

            if (!mVrModeAllowed) {
                // We're not allowed to be in VR mode.  Make this state pending.  This will be
                // applied the next time we are allowed to enter VR mode unless it is superseded by
                // another call.
                mPendingState = pending;
                return;
            }

            if (!targetEnabledState && mCurrentVrService != null) {
                // If we're transitioning out of VR mode, delay briefly to avoid expensive HAL calls
                // and service bind/unbind in case we are immediately switching to another VR app.
                if (mPendingState == null) {
                    mHandler.sendEmptyMessageDelayed(MSG_PENDING_VR_STATE_CHANGE,
                            PENDING_STATE_DELAY_MS);
                }

                mPendingState = pending;
                return;
            } else {
                mHandler.removeMessages(MSG_PENDING_VR_STATE_CHANGE);
                mPendingState = null;
            }

            updateCurrentVrServiceLocked(targetEnabledState, running2dInVr, targetListener,
                    userId, processId, callingPackage);
        }
    }

    private void setPersistentVrModeEnabled(boolean enabled) {
        synchronized(mLock) {
            setPersistentModeAndNotifyListenersLocked(enabled);
            // Disabling persistent mode should disable the overall vr mode.
            if (!enabled) {
                setVrMode(false, null, 0, -1, null);
            }
        }
    }

    public void setVr2dDisplayProperties(
        Vr2dDisplayProperties compatDisplayProp) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (mVr2dDisplay != null) {
                mVr2dDisplay.setVirtualDisplayProperties(compatDisplayProp);
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Slog.w(TAG, "Vr2dDisplay is null!");
    }

    private int getVr2dDisplayId() {
        if (mVr2dDisplay != null) {
            return mVr2dDisplay.getVirtualDisplayId();
        }
        Slog.w(TAG, "Vr2dDisplay is null!");
        return INVALID_DISPLAY;
    }

    private void setAndBindCompositor(ComponentName componentName) {
        final int userId = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                updateCompositorServiceLocked(userId, componentName);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void updateCompositorServiceLocked(int userId, ComponentName componentName) {
        if (mCurrentVrCompositorService != null
                && mCurrentVrCompositorService.disconnectIfNotMatching(componentName, userId)) {
            Slog.i(TAG, "Disconnecting compositor service: "
                    + mCurrentVrCompositorService.getComponent());
            // Check if existing service matches the requested one, if not (or if the requested
            // component is null) disconnect it.
            mCurrentVrCompositorService = null;
        }

        if (componentName != null && mCurrentVrCompositorService == null) {
            // We don't have an existing service matching the requested component, so attempt to
            // connect one.
            Slog.i(TAG, "Connecting compositor service: " + componentName);
            mCurrentVrCompositorService = createVrCompositorService(componentName, userId);
            mCurrentVrCompositorService.connect();
        }
    }

    private void setPersistentModeAndNotifyListenersLocked(boolean enabled) {
        if (mPersistentVrModeEnabled == enabled) {
            return;
        }
        String eventName = "Persistent VR mode " + ((enabled) ? "enabled" : "disabled");
        Slog.i(TAG, eventName);
        logEvent(new SettingEvent(eventName));
        mPersistentVrModeEnabled = enabled;

        mHandler.sendMessage(mHandler.obtainMessage(MSG_PERSISTENT_VR_MODE_STATE_CHANGE,
                (mPersistentVrModeEnabled) ? 1 : 0, 0));
    }

    private int hasVrPackage(@NonNull ComponentName targetPackageName, int userId) {
        synchronized (mLock) {
            return mComponentObserver.isValid(targetPackageName, userId);
        }
    }

    private boolean isCurrentVrListener(String packageName, int userId) {
        synchronized (mLock) {
            if (mCurrentVrService == null) {
                return false;
            }
            return mCurrentVrService.getComponent().getPackageName().equals(packageName) &&
                    userId == mCurrentVrService.getUserId();
        }
    }

    /*
     * Implementation of IVrManager calls.
     */

    private void addStateCallback(IVrStateCallbacks cb) {
        mVrStateRemoteCallbacks.register(cb);
    }

    private void removeStateCallback(IVrStateCallbacks cb) {
        mVrStateRemoteCallbacks.unregister(cb);
    }

    private void addPersistentStateCallback(IPersistentVrStateCallbacks cb) {
        mPersistentVrStateRemoteCallbacks.register(cb);
    }

    private void removePersistentStateCallback(IPersistentVrStateCallbacks cb) {
        mPersistentVrStateRemoteCallbacks.unregister(cb);
    }

    private boolean getVrMode() {
        synchronized (mLock) {
            return mVrModeEnabled;
        }
    }

    private boolean getPersistentVrMode() {
        synchronized (mLock) {
            return mPersistentVrModeEnabled;
        }
    }
}
