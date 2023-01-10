/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.statusbar;

import static android.Manifest.permission.CONTROL_DEVICE_STATE;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;
import static android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE;
import static android.app.StatusBarManager.NAV_BAR_MODE_DEFAULT;
import static android.app.StatusBarManager.NAV_BAR_MODE_KIDS;
import static android.app.StatusBarManager.NavBarMode;
import static android.app.StatusBarManager.SessionFlags;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.ITransientNotificationCallback;
import android.app.Notification;
import android.app.StatusBarManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricManager.BiometricMultiSensorMode;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.media.INearbyMediaDevicesProvider;
import android.media.MediaRoute2Info;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationStats;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InsetsState.InternalInsetsType;
import android.view.InsetsVisibilities;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.logging.InstanceId;
import com.android.internal.os.TransferPipe;
import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.IUndoMediaTransferCallback;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.GcUtils;
import com.android.internal.view.AppearanceRegion;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.notification.NotificationDelegate;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.power.ShutdownCheckPoints;
import com.android.server.power.ShutdownThread;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A note on locking:  We rely on the fact that calls onto mBar are oneway or
 * if they are local, that they just enqueue messages to not deadlock.
 */
public class StatusBarManagerService extends IStatusBarService.Stub implements DisplayListener {
    private static final String TAG = "StatusBarManagerService";
    private static final boolean SPEW = false;

    /**
     * Apps targeting {@code Build.VERSION_CODES.S} or higher need {@link
     * android.Manifest.permission#STATUS_BAR} permission to collapse the status bar panels due to
     * security reasons.
     *
     * This was being exploited by malware to prevent the user from accessing critical
     * notifications.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long LOCK_DOWN_COLLAPSE_STATUS_BAR = 173031413L;

    /**
     * In apps targeting {@link android.os.Build.VERSION_CODES#TIRAMISU} or higher, calling
     * {@link android.service.quicksettings.TileService#requestListeningState} will check that the 
     * calling package (uid) and the package of the target {@link android.content.ComponentName} 
     * match. It'll also make sure that the context used can take actions on behalf of the current 
     * user.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    static final long REQUEST_LISTENING_MUST_MATCH_PACKAGE = 172251878L;

    private final Context mContext;

    private final Handler mHandler = new Handler();
    private NotificationDelegate mNotificationDelegate;
    private volatile IStatusBar mBar;
    private final ArrayMap<String, StatusBarIcon> mIcons = new ArrayMap<>();

    // for disabling the status bar
    private final ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    private GlobalActionsProvider.GlobalActionsListener mGlobalActionListener;
    private final IBinder mSysUiVisToken = new Binder();

    private final Object mLock = new Object();
    private final DeathRecipient mDeathRecipient = new DeathRecipient();
    private final ActivityManagerInternal mActivityManagerInternal;
    private final ActivityTaskManagerInternal mActivityTaskManager;
    private final PackageManagerInternal mPackageManagerInternal;
    private final SessionMonitor mSessionMonitor;
    private int mCurrentUserId;
    private boolean mTracingEnabled;

    private final TileRequestTracker mTileRequestTracker;

    private final SparseArray<UiState> mDisplayUiState = new SparseArray<>();
    @GuardedBy("mLock")
    private IUdfpsHbmListener mUdfpsHbmListener;
    @GuardedBy("mLock")
    private IBiometricContextListener mBiometricContextListener;

    @GuardedBy("mCurrentRequestAddTilePackages")
    private final ArrayMap<String, Long> mCurrentRequestAddTilePackages = new ArrayMap<>();
    private static final long REQUEST_TIME_OUT = TimeUnit.MINUTES.toNanos(5);

    private IOverlayManager mOverlayManager;

    private class DeathRecipient implements IBinder.DeathRecipient {
        public void binderDied() {
            mBar.asBinder().unlinkToDeath(this,0);
            mBar = null;
            notifyBarAttachChanged();
        }

        public void linkToDeath() {
            try {
                mBar.asBinder().linkToDeath(mDeathRecipient,0);
            } catch (RemoteException e) {
                Slog.e(TAG,"Unable to register Death Recipient for status bar", e);
            }
        }

    }

    private class DisableRecord implements IBinder.DeathRecipient {
        int userId;
        String pkg;
        int what1;
        int what2;
        IBinder token;

        public DisableRecord(int userId, IBinder token) {
            this.userId = userId;
            this.token = token;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException re) {
                // Give up
            }
        }

        @Override
        public void binderDied() {
            Slog.i(TAG, "binder died for pkg=" + pkg);
            disableForUser(0, token, pkg, userId);
            disable2ForUser(0, token, pkg, userId);
            token.unlinkToDeath(this, 0);
        }

        public void setFlags(int what, int which, String pkg) {
            switch (which) {
                case 1:
                    what1 = what;
                    break;
                case 2:
                    what2 = what;
                    break;
                default:
                    Slog.w(TAG, "Can't set unsupported disable flag " + which
                            + ": 0x" + Integer.toHexString(what));
                    break;
            }
            this.pkg = pkg;
        }

        public int getFlags(int which) {
            switch (which) {
                case 1: return what1;
                case 2: return what2;
                default:
                    Slog.w(TAG, "Can't get unsupported disable flag " + which);
                    return 0;
            }
        }

        public boolean isEmpty() {
            return what1 == 0 && what2 == 0;
        }

        @Override
        public String toString() {
            return String.format("userId=%d what1=0x%08X what2=0x%08X pkg=%s token=%s",
                    userId, what1, what2, pkg, token);
        }
    }

    /**
     * Construct the service
     */
    public StatusBarManagerService(Context context) {
        mContext = context;

        LocalServices.addService(StatusBarManagerInternal.class, mInternalService);
        LocalServices.addService(GlobalActionsProvider.class, mGlobalActionsProvider);

        // We always have a default display.
        final UiState state = new UiState();
        mDisplayUiState.put(DEFAULT_DISPLAY, state);

        final DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, mHandler);
        mActivityTaskManager = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        mTileRequestTracker = new TileRequestTracker(mContext);
        mSessionMonitor = new SessionMonitor(mContext);
    }

    private IOverlayManager getOverlayManager() {
        // No need to synchronize; worst-case scenario it will be fetched twice.
        if (mOverlayManager == null) {
            mOverlayManager = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService(Context.OVERLAY_SERVICE));
            if (mOverlayManager == null) {
                Slog.w("StatusBarManager", "warning: no OVERLAY_SERVICE");
            }
        }
        return mOverlayManager;
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {
        synchronized (mLock) {
            mDisplayUiState.remove(displayId);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {}

    /**
     * Private API used by NotificationManagerService.
     */
    private final StatusBarManagerInternal mInternalService = new StatusBarManagerInternal() {
        private boolean mNotificationLightOn;

        @Override
        public void setNotificationDelegate(NotificationDelegate delegate) {
            mNotificationDelegate = delegate;
        }

        @Override
        public void showScreenPinningRequest(int taskId) {
            if (mBar != null) {
                try {
                    mBar.showScreenPinningRequest(taskId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void showAssistDisclosure() {
            if (mBar != null) {
                try {
                    mBar.showAssistDisclosure();
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void startAssist(Bundle args) {
            if (mBar != null) {
                try {
                    mBar.startAssist(args);
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void onCameraLaunchGestureDetected(int source) {
            if (mBar != null) {
                try {
                    mBar.onCameraLaunchGestureDetected(source);
                } catch (RemoteException e) {
                }
            }
        }

        /**
         * Notifies the status bar that a Emergency Action launch gesture has been detected.
         *
         * TODO (b/169175022) Update method name and docs when feature name is locked.
         */
        @Override
        public void onEmergencyActionLaunchGestureDetected() {
            if (SPEW) Slog.d(TAG, "Launching emergency action");
            if (mBar != null) {
                try {
                    mBar.onEmergencyActionLaunchGestureDetected();
                } catch (RemoteException e) {
                    if (SPEW) Slog.d(TAG, "Failed to launch emergency action");
                }
            }
        }

        @Override
        public void setDisableFlags(int displayId, int flags, String cause) {
            StatusBarManagerService.this.setDisableFlags(displayId, flags, cause);
        }

        @Override
        public void toggleSplitScreen() {
            enforceStatusBarService();
            if (mBar != null) {
                try {
                    mBar.toggleSplitScreen();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionFinished(int displayId) {
            enforceStatusBarService();
            if (mBar != null) {
                try {
                    mBar.appTransitionFinished(displayId);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void toggleRecentApps() {
            if (mBar != null) {
                try {
                    mBar.toggleRecentApps();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void setCurrentUser(int newUserId) {
            if (SPEW) Slog.d(TAG, "Setting current user to user " + newUserId);
            mCurrentUserId = newUserId;
        }


        @Override
        public void preloadRecentApps() {
            if (mBar != null) {
                try {
                    mBar.preloadRecentApps();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void cancelPreloadRecentApps() {
            if (mBar != null) {
                try {
                    mBar.cancelPreloadRecentApps();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void showRecentApps(boolean triggeredFromAltTab) {
            if (mBar != null) {
                try {
                    mBar.showRecentApps(triggeredFromAltTab);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
            if (mBar != null) {
                try {
                    mBar.hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void collapsePanels() {
            if (mBar != null) {
                try {
                    mBar.animateCollapsePanels();
                } catch (RemoteException ex) {
                }
            }
        }

        @Override
        public void dismissKeyboardShortcutsMenu() {
            if (mBar != null) {
                try {
                    mBar.dismissKeyboardShortcutsMenu();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void toggleKeyboardShortcutsMenu(int deviceId) {
            if (mBar != null) {
                try {
                    mBar.toggleKeyboardShortcutsMenu(deviceId);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void showChargingAnimation(int batteryLevel) {
            if (mBar != null) {
                try {
                    mBar.showWirelessChargingAnimation(batteryLevel);
                } catch (RemoteException ex){
                }
            }
        }

        @Override
        public void showPictureInPictureMenu() {
            if (mBar != null) {
                try {
                    mBar.showPictureInPictureMenu();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void setWindowState(int displayId, int window, int state) {
            if (mBar != null) {
                try {
                    mBar.setWindowState(displayId, window, state);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionPending(int displayId) {
            if (mBar != null) {
                try {
                    mBar.appTransitionPending(displayId);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionCancelled(int displayId) {
            if (mBar != null) {
                try {
                    mBar.appTransitionCancelled(displayId);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionStarting(int displayId, long statusBarAnimationsStartTime,
                long statusBarAnimationsDuration) {
            if (mBar != null) {
                try {
                    mBar.appTransitionStarting(
                            displayId, statusBarAnimationsStartTime, statusBarAnimationsDuration);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
            if (mBar != null) {
                try {
                    mBar.setTopAppHidesStatusBar(hidesStatusBar);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public boolean showShutdownUi(boolean isReboot, String reason) {
            if (!mContext.getResources().getBoolean(R.bool.config_showSysuiShutdown)) {
                return false;
            }
            if (mBar != null) {
                try {
                    mBar.showShutdownUi(isReboot, reason);
                    return true;
                } catch (RemoteException ex) {}
            }
            return false;
        }

        // TODO(b/118592525): support it per display if necessary.
        @Override
        public void onProposedRotationChanged(int rotation, boolean isValid) {
            if (mBar != null){
                try {
                    mBar.onProposedRotationChanged(rotation, isValid);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void onDisplayReady(int displayId) {
            if (mBar != null) {
                try {
                    mBar.onDisplayReady(displayId);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void onRecentsAnimationStateChanged(boolean running) {
            if (mBar != null) {
                try {
                    mBar.onRecentsAnimationStateChanged(running);
                } catch (RemoteException ex) {}
            }

        }

        @Override
        public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
                @Behavior int behavior, InsetsVisibilities requestedVisibilities,
                String packageName, LetterboxDetails[] letterboxDetails) {
            getUiState(displayId).setBarAttributes(appearance, appearanceRegions,
                    navbarColorManagedByIme, behavior, requestedVisibilities, packageName,
                    letterboxDetails);
            if (mBar != null) {
                try {
                    mBar.onSystemBarAttributesChanged(displayId, appearance, appearanceRegions,
                            navbarColorManagedByIme, behavior, requestedVisibilities, packageName,
                            letterboxDetails);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void showTransient(int displayId, @InternalInsetsType int[] types,
                boolean isGestureOnSystemBar) {
            getUiState(displayId).showTransient(types);
            if (mBar != null) {
                try {
                    mBar.showTransient(displayId, types, isGestureOnSystemBar);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void abortTransient(int displayId, @InternalInsetsType int[] types) {
            getUiState(displayId).clearTransient(types);
            if (mBar != null) {
                try {
                    mBar.abortTransient(displayId, types);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void showToast(int uid, String packageName, IBinder token, CharSequence text,
                IBinder windowToken, int duration,
                @Nullable ITransientNotificationCallback callback, int displayId) {
            if (mBar != null) {
                try {
                    mBar.showToast(uid, packageName, token, text, windowToken, duration, callback,
                            displayId);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void hideToast(String packageName, IBinder token) {
            if (mBar != null) {
                try {
                    mBar.hideToast(packageName, token);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public boolean requestWindowMagnificationConnection(boolean request) {
            if (mBar != null) {
                try {
                    mBar.requestWindowMagnificationConnection(request);
                    return true;
                } catch (RemoteException ex) { }
            }
            return false;
        }

        @Override
        public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
            if (mBar != null) {
                try {
                    mBar.setNavigationBarLumaSamplingEnabled(displayId, enable);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void setUdfpsHbmListener(IUdfpsHbmListener listener) {
            synchronized (mLock) {
                mUdfpsHbmListener = listener;
            }
            if (mBar != null) {
                try {
                    mBar.setUdfpsHbmListener(listener);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void showRearDisplayDialog(int currentBaseState) {
            if (mBar != null) {
                try {
                    mBar.showRearDisplayDialog(currentBaseState);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void goToFullscreenFromSplit() {
            if (mBar != null) {
                try {
                    mBar.goToFullscreenFromSplit();
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void enterStageSplitFromRunningApp(boolean leftOrTop) {
            if (mBar != null) {
                try {
                    mBar.enterStageSplitFromRunningApp(leftOrTop);
                } catch (RemoteException ex) { }
            }
        }
    };

    private final GlobalActionsProvider mGlobalActionsProvider = new GlobalActionsProvider() {
        @Override
        public boolean isGlobalActionsDisabled() {
            // TODO(b/118592525): support global actions for multi-display.
            final int disabled2 = mDisplayUiState.get(DEFAULT_DISPLAY).getDisabled2();
            return (disabled2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        }

        @Override
        public void setGlobalActionsListener(GlobalActionsProvider.GlobalActionsListener listener) {
            mGlobalActionListener = listener;
            mGlobalActionListener.onGlobalActionsAvailableChanged(mBar != null);
        }

        @Override
        public void showGlobalActions() {
            if (mBar != null) {
                try {
                    mBar.showGlobalActionsMenu();
                } catch (RemoteException ex) {}
            }
        }
    };

    /**
     * Returns true if the target disable flag (target2) is set
     */
    private boolean isDisable2FlagSet(int target2) {
        final int disabled2 = mDisplayUiState.get(DEFAULT_DISPLAY).getDisabled2();
        return ((disabled2 & target2) == target2);
    }

    // ================================================================================
    // From IStatusBarService
    // ================================================================================

    @Override
    public void expandNotificationsPanel() {
        enforceExpandStatusBar();

        if (isDisable2FlagSet(DISABLE2_NOTIFICATION_SHADE)) {
            return;
        }

        if (mBar != null) {
            try {
                mBar.animateExpandNotificationsPanel();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void collapsePanels() {
        if (!checkCanCollapseStatusBar("collapsePanels")) {
            return;
        }

        if (mBar != null) {
            try {
                mBar.animateCollapsePanels();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void togglePanel() {
        if (!checkCanCollapseStatusBar("togglePanel")) {
            return;
        }

        if (isDisable2FlagSet(DISABLE2_NOTIFICATION_SHADE)) {
            return;
        }

        if (mBar != null) {
            try {
                mBar.togglePanel();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void expandSettingsPanel(String subPanel) {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateExpandSettingsPanel(subPanel);
            } catch (RemoteException ex) {
            }
        }
    }

    public void addTile(ComponentName component) {
        enforceStatusBarOrShell();

        if (mBar != null) {
            try {
                mBar.addQsTile(component);
            } catch (RemoteException ex) {
            }
        }
    }

    public void remTile(ComponentName component) {
        enforceStatusBarOrShell();

        if (mBar != null) {
            try {
                mBar.remQsTile(component);
            } catch (RemoteException ex) {
            }
        }
    }

    public void clickTile(ComponentName component) {
        enforceStatusBarOrShell();

        if (mBar != null) {
            try {
                mBar.clickQsTile(component);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void handleSystemKey(int key) throws RemoteException {
        if (!checkCanCollapseStatusBar("handleSystemKey")) {
            return;
        }

        if (mBar != null) {
            try {
                mBar.handleSystemKey(key);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void showPinningEnterExitToast(boolean entering) throws RemoteException {
        if (mBar != null) {
            try {
                mBar.showPinningEnterExitToast(entering);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void showPinningEscapeToast() throws RemoteException {
        if (mBar != null) {
            try {
                mBar.showPinningEscapeToast();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void showAuthenticationDialog(PromptInfo promptInfo, IBiometricSysuiReceiver receiver,
            int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, long operationId, String opPackageName, long requestId,
            @BiometricMultiSensorMode int multiSensorConfig) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.showAuthenticationDialog(promptInfo, receiver, sensorIds, credentialAllowed,
                        requireConfirmation, userId, operationId, opPackageName, requestId,
                        multiSensorConfig);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onBiometricAuthenticated(@Modality int modality) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.onBiometricAuthenticated(modality);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onBiometricHelp(@Modality int modality, String message) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.onBiometricHelp(modality, message);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onBiometricError(int modality, int error, int vendorCode) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.onBiometricError(modality, error, vendorCode);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void hideAuthenticationDialog(long requestId) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.hideAuthenticationDialog(requestId);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void setBiometicContextListener(IBiometricContextListener listener) {
        enforceStatusBarService();
        synchronized (mLock) {
            mBiometricContextListener = listener;
        }
        if (mBar != null) {
            try {
                mBar.setBiometicContextListener(listener);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void setUdfpsHbmListener(IUdfpsHbmListener listener) {
        enforceStatusBarService();
        if (mBar != null) {
            try {
                mBar.setUdfpsHbmListener(listener);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void startTracing() {
        if (mBar != null) {
            try {
                mBar.startTracing();
                mTracingEnabled = true;
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void stopTracing() {
        if (mBar != null) {
            try {
                mTracingEnabled = false;
                mBar.stopTracing();
            } catch (RemoteException ex) {}
        }
    }

    @Override
    public boolean isTracing() {
        return mTracingEnabled;
    }

    // TODO(b/117478341): make it aware of multi-display if needed.
    @Override
    public void disable(int what, IBinder token, String pkg) {
        disableForUser(what, token, pkg, mCurrentUserId);
    }

    // TODO(b/117478341): make it aware of multi-display if needed.
    @Override
    public void disableForUser(int what, IBinder token, String pkg, int userId) {
        enforceStatusBar();

        synchronized (mLock) {
            disableLocked(DEFAULT_DISPLAY, userId, what, token, pkg, 1);
        }
    }

    // TODO(b/117478341): make it aware of multi-display if needed.
    /**
     * Disable additional status bar features. Pass the bitwise-or of the DISABLE2_* flags.
     * To re-enable everything, pass {@link #DISABLE2_NONE}.
     *
     * Warning: Only pass DISABLE2_* flags into this function, do not use DISABLE_* flags.
     */
    @Override
    public void disable2(int what, IBinder token, String pkg) {
        disable2ForUser(what, token, pkg, mCurrentUserId);
    }

    // TODO(b/117478341): make it aware of multi-display if needed.
    /**
     * Disable additional status bar features for a given user. Pass the bitwise-or of the
     * DISABLE2_* flags. To re-enable everything, pass {@link #DISABLE_NONE}.
     *
     * Warning: Only pass DISABLE2_* flags into this function, do not use DISABLE_* flags.
     */
    @Override
    public void disable2ForUser(int what, IBinder token, String pkg, int userId) {
        enforceStatusBar();

        synchronized (mLock) {
            disableLocked(DEFAULT_DISPLAY, userId, what, token, pkg, 2);
        }
    }

    private void disableLocked(int displayId, int userId, int what, IBinder token, String pkg,
            int whichFlag) {
        // It's important that the the callback and the call to mBar get done
        // in the same order when multiple threads are calling this function
        // so they are paired correctly.  The messages on the handler will be
        // handled in the order they were enqueued, but will be outside the lock.
        manageDisableListLocked(userId, what, token, pkg, whichFlag);

        // Ensure state for the current user is applied, even if passed a non-current user.
        final int net1 = gatherDisableActionsLocked(mCurrentUserId, 1);
        final int net2 = gatherDisableActionsLocked(mCurrentUserId, 2);
        final UiState state = getUiState(displayId);
        if (!state.disableEquals(net1, net2)) {
            state.setDisabled(net1, net2);
            mHandler.post(() -> mNotificationDelegate.onSetDisabled(net1));
            if (mBar != null) {
                try {
                    mBar.disable(displayId, net1, net2);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    /**
     * Get the currently applied disable flags, in the form of one Pair<Integer, Integer>.
     *
     * @return pair of disable flags in the form of (disabled1, disabled2), where (0, 0) indicates
     * no flags are set for this token.
     */
    @Override
    public int[] getDisableFlags(IBinder token, int userId) {
        enforceStatusBar();

        int disable1 = 0;
        int disable2 = 0;
        synchronized (mLock) {
            // Find a matching record if it exists
            DisableRecord record = findMatchingRecordLocked(token, userId).second;
            if (record != null) {
                disable1 = record.what1;
                disable2 = record.what2;
            }
        }

        return new int[] {disable1, disable2};
    }

    void runGcForTest() {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("runGcForTest requires a debuggable build");
        }

        // Gc the system along the way
        GcUtils.runGcAndFinalizersSync();

        if (mBar != null) {
            try {
                mBar.runGcForTest();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void setIcon(String slot, String iconPackage, int iconId, int iconLevel,
            String contentDescription) {
        enforceStatusBar();

        synchronized (mIcons) {
            StatusBarIcon icon = new StatusBarIcon(iconPackage, UserHandle.SYSTEM, iconId,
                    iconLevel, 0, contentDescription);
            //Slog.d(TAG, "setIcon slot=" + slot + " index=" + index + " icon=" + icon);
            mIcons.put(slot, icon);

            if (mBar != null) {
                try {
                    mBar.setIcon(slot, icon);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    @Override
    public void setIconVisibility(String slot, boolean visibility) {
        enforceStatusBar();

        synchronized (mIcons) {
            StatusBarIcon icon = mIcons.get(slot);
            if (icon == null) {
                return;
            }
            if (icon.visible != visibility) {
                icon.visible = visibility;

                if (mBar != null) {
                    try {
                        mBar.setIcon(slot, icon);
                    } catch (RemoteException ex) {
                    }
                }
            }
        }
    }

    @Override
    public void removeIcon(String slot) {
        enforceStatusBar();

        synchronized (mIcons) {
            mIcons.remove(slot);

            if (mBar != null) {
                try {
                    mBar.removeIcon(slot);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, final IBinder token, final int vis,
            final int backDisposition, final boolean showImeSwitcher) {
        enforceStatusBar();

        if (SPEW) {
            Slog.d(TAG, "swetImeWindowStatus vis=" + vis + " backDisposition=" + backDisposition);
        }

        synchronized(mLock) {
            // In case of IME change, we need to call up setImeWindowStatus() regardless of
            // mImeWindowVis because mImeWindowVis may not have been set to false when the
            // previous IME was destroyed.
            getUiState(displayId).setImeWindowState(vis, backDisposition, showImeSwitcher, token);

            mHandler.post(() -> {
                if (mBar == null) return;
                try {
                    mBar.setImeWindowStatus(
                            displayId, token, vis, backDisposition, showImeSwitcher);
                } catch (RemoteException ex) { }
            });
        }
    }

    private void setDisableFlags(int displayId, int flags, String cause) {
        // also allows calls from window manager which is in this process.
        enforceStatusBarService();

        final int unknownFlags = flags & ~StatusBarManager.DISABLE_MASK;
        if (unknownFlags != 0) {
            Slog.e(TAG, "Unknown disable flags: 0x" + Integer.toHexString(unknownFlags),
                    new RuntimeException());
        }

        if (SPEW) Slog.d(TAG, "setDisableFlags(0x" + Integer.toHexString(flags) + ")");

        synchronized (mLock) {
            disableLocked(displayId, mCurrentUserId, flags, mSysUiVisToken, cause, 1);
        }
    }

    /**
     * @return {@link UiState} specified by {@code displayId}.
     *
     * <p>
     *   Note: If {@link UiState} specified by {@code displayId} does not exist, {@link UiState}
     *   will be allocated and {@code mDisplayUiState} will be updated accordingly.
     * <p/>
     */
    private UiState getUiState(int displayId) {
        UiState state = mDisplayUiState.get(displayId);
        if (state == null) {
            state = new UiState();
            mDisplayUiState.put(displayId, state);
        }
        return state;
    }

    private static class UiState {
        private @Appearance int mAppearance = 0;
        private AppearanceRegion[] mAppearanceRegions = new AppearanceRegion[0];
        private final ArraySet<Integer> mTransientBarTypes = new ArraySet<>();
        private boolean mNavbarColorManagedByIme = false;
        private @Behavior int mBehavior;
        private InsetsVisibilities mRequestedVisibilities = new InsetsVisibilities();
        private String mPackageName = "none";
        private int mDisabled1 = 0;
        private int mDisabled2 = 0;
        private int mImeWindowVis = 0;
        private int mImeBackDisposition = 0;
        private boolean mShowImeSwitcher = false;
        private IBinder mImeToken = null;
        private LetterboxDetails[] mLetterboxDetails = new LetterboxDetails[0];

        private void setBarAttributes(@Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
                @Behavior int behavior, InsetsVisibilities requestedVisibilities,
                String packageName,
                LetterboxDetails[] letterboxDetails) {
            mAppearance = appearance;
            mAppearanceRegions = appearanceRegions;
            mNavbarColorManagedByIme = navbarColorManagedByIme;
            mBehavior = behavior;
            mRequestedVisibilities = requestedVisibilities;
            mPackageName = packageName;
            mLetterboxDetails = letterboxDetails;
        }

        private void showTransient(@InternalInsetsType int[] types) {
            for (int type : types) {
                mTransientBarTypes.add(type);
            }
        }

        private void clearTransient(@InternalInsetsType int[] types) {
            for (int type : types) {
                mTransientBarTypes.remove(type);
            }
        }

        private int getDisabled1() {
            return mDisabled1;
        }

        private int getDisabled2() {
            return mDisabled2;
        }

        private void setDisabled(int disabled1, int disabled2) {
            mDisabled1 = disabled1;
            mDisabled2 = disabled2;
        }

        private boolean disableEquals(int disabled1, int disabled2) {
            return mDisabled1 == disabled1 && mDisabled2 == disabled2;
        }

        private void setImeWindowState(final int vis, final int backDisposition,
                final boolean showImeSwitcher, final IBinder token) {
            mImeWindowVis = vis;
            mImeBackDisposition = backDisposition;
            mShowImeSwitcher = showImeSwitcher;
            mImeToken = token;
        }
    }

    private void enforceStatusBarOrShell() {
        if (Binder.getCallingUid() == Process.SHELL_UID) {
            return;
        }
        enforceStatusBar();
    }

    private void enforceStatusBar() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR,
                "StatusBarManagerService");
    }

    private void enforceExpandStatusBar() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.EXPAND_STATUS_BAR,
                "StatusBarManagerService");
    }

    private void enforceStatusBarService() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                "StatusBarManagerService");
    }

    private void enforceBiometricDialog() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_BIOMETRIC_DIALOG,
                "StatusBarManagerService");
    }

    private void enforceMediaContentControl() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MEDIA_CONTENT_CONTROL,
                "StatusBarManagerService");
    }

    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    private void enforceControlDeviceStatePermission() {
        mContext.enforceCallingOrSelfPermission(CONTROL_DEVICE_STATE, "StatusBarManagerService");
    }

    private boolean doesCallerHoldInteractAcrossUserPermission() {
        return mContext.checkCallingPermission(INTERACT_ACROSS_USERS_FULL) == PERMISSION_GRANTED
                || mContext.checkCallingPermission(INTERACT_ACROSS_USERS) == PERMISSION_GRANTED;
    }

    /**
     *  For targetSdk S+ we require STATUS_BAR. For targetSdk < S, we only require EXPAND_STATUS_BAR
     *  but also require that it falls into one of the allowed use-cases to lock down abuse vector.
     */
    private boolean checkCanCollapseStatusBar(String method) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (CompatChanges.isChangeEnabled(LOCK_DOWN_COLLAPSE_STATUS_BAR, uid)) {
            enforceStatusBar();
        } else {
            if (mContext.checkPermission(Manifest.permission.STATUS_BAR, pid, uid)
                    != PERMISSION_GRANTED) {
                enforceExpandStatusBar();
                if (!mActivityTaskManager.canCloseSystemDialogs(pid, uid)) {
                    Slog.e(TAG, "Permission Denial: Method " + method + "() requires permission "
                            + Manifest.permission.STATUS_BAR + ", ignoring call.");
                    return false;
                }
            }
        }
        return true;
    }

    // ================================================================================
    // Callbacks from the status bar service.
    // ================================================================================
    // TODO(b/118592525): refactor it as an IStatusBar API.
    @Override
    public RegisterStatusBarResult registerStatusBar(IStatusBar bar) {
        enforceStatusBarService();

        Slog.i(TAG, "registerStatusBar bar=" + bar);
        mBar = bar;
        mDeathRecipient.linkToDeath();
        notifyBarAttachChanged();
        final ArrayMap<String, StatusBarIcon> icons;
        synchronized (mIcons) {
            icons = new ArrayMap<>(mIcons);
        }
        synchronized (mLock) {
            // TODO(b/118592525): Currently, status bar only works on the default display.
            // Make it aware of multi-display if needed.
            final UiState state = mDisplayUiState.get(DEFAULT_DISPLAY);
            final int[] transientBarTypes = new int[state.mTransientBarTypes.size()];
            for (int i = 0; i < transientBarTypes.length; i++) {
                transientBarTypes[i] = state.mTransientBarTypes.valueAt(i);
            }
            return new RegisterStatusBarResult(icons, gatherDisableActionsLocked(mCurrentUserId, 1),
                    state.mAppearance, state.mAppearanceRegions, state.mImeWindowVis,
                    state.mImeBackDisposition, state.mShowImeSwitcher,
                    gatherDisableActionsLocked(mCurrentUserId, 2), state.mImeToken,
                    state.mNavbarColorManagedByIme, state.mBehavior, state.mRequestedVisibilities,
                    state.mPackageName, transientBarTypes, state.mLetterboxDetails);
        }
    }

    private void notifyBarAttachChanged() {
        UiThread.getHandler().post(() -> {
            if (mGlobalActionListener == null) return;
            mGlobalActionListener.onGlobalActionsAvailableChanged(mBar != null);
        });
        // If StatusBarService dies, system_server doesn't get killed with it, so we need to make
        // sure the UDFPS listener is refreshed as well. Deferring to the handler just so to avoid
        // making registerStatusBar re-entrant.
        mHandler.post(() -> {
            synchronized (mLock) {
                setUdfpsHbmListener(mUdfpsHbmListener);
                setBiometicContextListener(mBiometricContextListener);
            }
        });
    }

    @VisibleForTesting
    void registerOverlayManager(IOverlayManager overlayManager) {
        mOverlayManager = overlayManager;
    }

    /**
     * @param clearNotificationEffects whether to consider notifications as "shown" and stop
     *     LED, vibration, and ringing
     */
    @Override
    public void onPanelRevealed(boolean clearNotificationEffects, int numItems) {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onPanelRevealed(clearNotificationEffects, numItems);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearNotificationEffects() throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.clearEffects();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onPanelHidden() throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onPanelHidden();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Allows the status bar to shutdown the device.
     */
    @Override
    public void shutdown() {
        enforceStatusBarService();
        String reason = PowerManager.SHUTDOWN_USER_REQUESTED;
        ShutdownCheckPoints.recordCheckPoint(Binder.getCallingPid(), reason);
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.prepareForPossibleShutdown();
            // ShutdownThread displays UI, so give it a UI context.
            mHandler.post(() ->
                    ShutdownThread.shutdown(getUiContext(), reason, false));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Allows the status bar to reboot the device.
     */
    @Override
    public void reboot(boolean safeMode) {
        enforceStatusBarService();
        String reason = safeMode
                ? PowerManager.REBOOT_SAFE_MODE
                : PowerManager.SHUTDOWN_USER_REQUESTED;
        ShutdownCheckPoints.recordCheckPoint(Binder.getCallingPid(), reason);
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.prepareForPossibleShutdown();
            mHandler.post(() -> {
                // ShutdownThread displays UI, so give it a UI context.
                if (safeMode) {
                    ShutdownThread.rebootSafeMode(getUiContext(), true);
                } else {
                    ShutdownThread.reboot(getUiContext(), reason, false);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Allows the status bar to restart android (vs a full reboot).
     */
    @Override
    public void restart() {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mHandler.post(() -> {
                mActivityManagerInternal.restart();
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onGlobalActionsShown() {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mGlobalActionListener == null) return;
            mGlobalActionListener.onGlobalActionsShown();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onGlobalActionsHidden() {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mGlobalActionListener == null) return;
            mGlobalActionListener.onGlobalActionsDismissed();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationClick(String key, NotificationVisibility nv) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClick(callingUid, callingPid, key, nv);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationActionClick(
            String key, int actionIndex, Notification.Action action, NotificationVisibility nv,
            boolean generatedByAssistant) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationActionClick(callingUid, callingPid, key,
                    actionIndex, action, nv, generatedByAssistant);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationError(String pkg, String tag, int id,
            int uid, int initialPid, String message, int userId) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            // WARNING: this will call back into us to do the remove.  Don't hold any locks.
            mNotificationDelegate.onNotificationError(callingUid, callingPid,
                    pkg, tag, id, uid, initialPid, message, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationClear(String pkg, int userId, String key,
            @NotificationStats.DismissalSurface int dismissalSurface,
            @NotificationStats.DismissalSentiment int dismissalSentiment,
            NotificationVisibility nv) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClear(callingUid, callingPid, pkg, userId,
                    key, dismissalSurface, dismissalSentiment, nv);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationVisibilityChanged(
            NotificationVisibility[] newlyVisibleKeys, NotificationVisibility[] noLongerVisibleKeys)
            throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationVisibilityChanged(
                    newlyVisibleKeys, noLongerVisibleKeys);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationExpansionChanged(String key, boolean userAction, boolean expanded,
            int location) throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationExpansionChanged(
                    key, userAction, expanded, location);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationDirectReplied(String key) throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationDirectReplied(key);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount,
            int smartActionCount, boolean generatedByAssistant, boolean editBeforeSending) {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationSmartSuggestionsAdded(key, smartReplyCount,
                    smartActionCount, generatedByAssistant, editBeforeSending);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationSmartReplySent(
            String key, int replyIndex, CharSequence reply, int notificationLocation,
            boolean modifiedBeforeSending) throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationSmartReplySent(key, replyIndex, reply,
                    notificationLocation, modifiedBeforeSending);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationSettingsViewed(String key) throws RemoteException {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationSettingsViewed(key);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onClearAllNotifications(int userId) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onClearAll(callingUid, callingPid, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationBubbleChanged(String key, boolean isBubble, int flags) {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationBubbleChanged(key, isBubble, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onBubbleMetadataFlagChanged(String key, int flags) {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onBubbleMetadataFlagChanged(key, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void hideCurrentInputMethodForBubbles() {
        enforceStatusBarService();
        final long token = Binder.clearCallingIdentity();
        try {
            InputMethodManagerInternal.get().hideCurrentInputMethod(
                    SoftInputShowHideReason.HIDE_BUBBLES);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void grantInlineReplyUriPermission(String key, Uri uri, UserHandle user,
            String packageName) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.grantInlineReplyUriPermission(key, uri, user, packageName,
                    callingUid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearInlineReplyUriPermissions(String key) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.clearInlineReplyUriPermissions(key, callingUid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationFeedbackReceived(String key, Bundle feedback) {
        enforceStatusBarService();
        final long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationFeedbackReceived(key, feedback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }


    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new StatusBarShellCommand(this, mContext)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    public void showInattentiveSleepWarning() {
        enforceStatusBarService();
        if (mBar != null) {
            try {
                mBar.showInattentiveSleepWarning();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void dismissInattentiveSleepWarning(boolean animated) {
        enforceStatusBarService();
        if (mBar != null) {
            try {
                mBar.dismissInattentiveSleepWarning(animated);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void suppressAmbientDisplay(boolean suppress) {
        enforceStatusBarService();
        if (mBar != null) {
            try {
                mBar.suppressAmbientDisplay(suppress);
            } catch (RemoteException ex) {
            }
        }
    }

    private void checkCallingUidPackage(String packageName, int callingUid, int userId) {
        int packageUid = mPackageManagerInternal.getPackageUid(packageName, 0, userId);
        if (UserHandle.getAppId(callingUid) != UserHandle.getAppId(packageUid)) {
            throw new SecurityException("Package " + packageName
                    + " does not belong to the calling uid " + callingUid);
        }
    }

    private ResolveInfo isComponentValidTileService(ComponentName componentName, int userId) {
        Intent intent = new Intent(TileService.ACTION_QS_TILE);
        intent.setComponent(componentName);
        ResolveInfo r = mPackageManagerInternal.resolveService(intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()), 0, userId,
                Process.myUid());
        int enabled = mPackageManagerInternal.getComponentEnabledSetting(
                componentName, Process.myUid(), userId);
        if (r != null
                && r.serviceInfo != null
                && resolveEnabledComponent(r.serviceInfo.enabled, enabled)
                && Manifest.permission.BIND_QUICK_SETTINGS_TILE.equals(r.serviceInfo.permission)) {
            return r;
        } else {
            return null;
        }
    }

    private boolean resolveEnabledComponent(boolean defaultValue, int pmResult) {
        if (pmResult == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        if (pmResult == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return defaultValue;
        }
        return false;
    }

    @Override
    public void requestTileServiceListeningState(
            @NonNull ComponentName componentName,
            int userId
    ) {
        int callingUid = Binder.getCallingUid();
        String packageName = componentName.getPackageName();

        boolean mustPerformChecks = CompatChanges.isChangeEnabled(
                REQUEST_LISTENING_MUST_MATCH_PACKAGE, callingUid);

        if (mustPerformChecks) {
            // Check calling user can act on behalf of current user
            userId = mActivityManagerInternal.handleIncomingUser(Binder.getCallingPid(), callingUid,
                    userId, false, ActivityManagerInternal.ALLOW_NON_FULL,
                    "requestTileServiceListeningState", packageName);

            // Check calling uid matches package
            checkCallingUidPackage(packageName, callingUid, userId);

            int currentUser = mActivityManagerInternal.getCurrentUserId();

            // Check current user
            if (userId != currentUser) {
                throw new IllegalArgumentException("User " + userId + " is not the current user.");
            }
        }
        if (mBar != null) {
            try {
                mBar.requestTileServiceListeningState(componentName);
            } catch (RemoteException e) {
                Slog.e(TAG, "requestTileServiceListeningState", e);
            }
        }
    }

    @Override
    public void requestAddTile(
            @NonNull ComponentName componentName,
            @NonNull CharSequence label,
            @NonNull Icon icon,
            int userId,
            @NonNull IAddTileResultCallback callback
    ) {
        int callingUid = Binder.getCallingUid();
        String packageName = componentName.getPackageName();

        // Check calling user can act on behalf of current user
        mActivityManagerInternal.handleIncomingUser(Binder.getCallingPid(), callingUid, userId,
                false, ActivityManagerInternal.ALLOW_NON_FULL, "requestAddTile", packageName);

        // Check calling uid matches package
        checkCallingUidPackage(packageName, callingUid, userId);

        int currentUser = mActivityManagerInternal.getCurrentUserId();

        // Check current user
        if (userId != currentUser) {
            try {
                callback.onTileRequest(StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER);
            } catch (RemoteException e) {
                Slog.e(TAG, "requestAddTile", e);
            }
            return;
        }

        // We've checked that the package, component name and uid all match.
        ResolveInfo r = isComponentValidTileService(componentName, userId);
        if (r == null || !r.serviceInfo.exported) {
            try {
                callback.onTileRequest(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT);
            } catch (RemoteException e) {
                Slog.e(TAG, "requestAddTile", e);
            }
            return;
        }

        final int procState = mActivityManagerInternal.getUidProcessState(callingUid);
        if (ActivityManager.RunningAppProcessInfo.procStateToImportance(procState)
                != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            try {
                callback.onTileRequest(
                        StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND);
            } catch (RemoteException e) {
                Slog.e(TAG, "requestAddTile", e);
            }
            return;
        }

        synchronized (mCurrentRequestAddTilePackages) {
            Long lastTime = mCurrentRequestAddTilePackages.get(packageName);
            final long currentTime = System.nanoTime();
            if (lastTime != null && currentTime - lastTime < REQUEST_TIME_OUT) {
                try {
                    callback.onTileRequest(
                            StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS);
                } catch (RemoteException e) {
                    Slog.e(TAG, "requestAddTile", e);
                }
                return;
            } else {
                if (lastTime != null) {
                    cancelRequestAddTileInternal(packageName);
                }
            }

            mCurrentRequestAddTilePackages.put(packageName, currentTime);
        }

        if (mTileRequestTracker.shouldBeDenied(userId, componentName)) {
            if (clearTileAddRequest(packageName)) {
                try {
                    callback.onTileRequest(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED);
                } catch (RemoteException e) {
                    Slog.e(TAG, "requestAddTile - callback", e);
                }
            }
            return;
        }

        IAddTileResultCallback proxyCallback = new IAddTileResultCallback.Stub() {
            @Override
            public void onTileRequest(int i) {
                if (i == StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED) {
                    i = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED;
                } else if (i == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                    mTileRequestTracker.addDenial(userId, componentName);
                } else if (i == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    mTileRequestTracker.resetRequests(userId, componentName);
                }
                if (clearTileAddRequest(packageName)) {
                    try {
                        callback.onTileRequest(i);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "requestAddTile - callback", e);
                    }
                }
            }
        };

        CharSequence appName = r.serviceInfo.applicationInfo
                .loadLabel(mContext.getPackageManager());
        if (mBar != null) {
            try {
                mBar.requestAddTile(componentName, appName, label, icon, proxyCallback);
                return;
            } catch (RemoteException e) {
                Slog.e(TAG, "requestAddTile", e);
            }
        }
        clearTileAddRequest(packageName);
        try {
            callback.onTileRequest(StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE);
        } catch (RemoteException e) {
            Slog.e(TAG, "requestAddTile", e);
        }
    }

    @Override
    public void cancelRequestAddTile(@NonNull String packageName) {
        enforceStatusBar();
        cancelRequestAddTileInternal(packageName);
    }

    private void cancelRequestAddTileInternal(String packageName) {
        clearTileAddRequest(packageName);
        if (mBar != null) {
            try {
                mBar.cancelRequestAddTile(packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "requestAddTile", e);
            }
        }
    }

    private boolean clearTileAddRequest(String packageName) {
        synchronized (mCurrentRequestAddTilePackages) {
            return mCurrentRequestAddTilePackages.remove(packageName) != null;
        }
    }

    @Override
    public void onSessionStarted(@SessionFlags int sessionType, InstanceId instance) {
        mSessionMonitor.onSessionStarted(sessionType, instance);
    }

    @Override
    public void onSessionEnded(@SessionFlags int sessionType, InstanceId instance) {
        mSessionMonitor.onSessionEnded(sessionType, instance);
    }

    @Override
    public void registerSessionListener(@SessionFlags int sessionFlags,
            ISessionListener listener) {
        mSessionMonitor.registerSessionListener(sessionFlags, listener);
    }

    @Override
    public void unregisterSessionListener(@SessionFlags int sessionFlags,
            ISessionListener listener) {
        mSessionMonitor.unregisterSessionListener(sessionFlags, listener);
    }

    public String[] getStatusBarIcons() {
        return mContext.getResources().getStringArray(R.array.config_statusBarIcons);
    }

    /**
     * Sets or removes the navigation bar mode.
     *
     * @param navBarMode the mode of the navigation bar to be set.
     */
    public void setNavBarMode(@NavBarMode int navBarMode) {
        enforceStatusBar();
        if (navBarMode != NAV_BAR_MODE_DEFAULT && navBarMode != NAV_BAR_MODE_KIDS) {
            throw new IllegalArgumentException("Supplied navBarMode not supported: " + navBarMode);
        }

        final int userId = mCurrentUserId;
        final int callingUserId = UserHandle.getUserId(Binder.getCallingUid());
        if (mCurrentUserId != callingUserId && !doesCallerHoldInteractAcrossUserPermission()) {
            throw new SecurityException("Calling user id: " + callingUserId
                    + ", cannot call on behalf of current user id: " + mCurrentUserId + ".");
        }
        final long userIdentity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NAV_BAR_KIDS_MODE, navBarMode, userId);
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NAV_BAR_FORCE_VISIBLE, navBarMode, userId);

            IOverlayManager overlayManager = getOverlayManager();
            if (overlayManager != null && navBarMode == NAV_BAR_MODE_KIDS
                    && isPackageSupported(NAV_BAR_MODE_3BUTTON_OVERLAY)) {
                overlayManager.setEnabledExclusiveInCategory(NAV_BAR_MODE_3BUTTON_OVERLAY, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(userIdentity);
        }
    }

    /**
     * Gets the navigation bar mode. Returns default value if no mode is set.
     *
     * @hide
     */
    public @NavBarMode int getNavBarMode() {
        enforceStatusBar();

        int navBarKidsMode = NAV_BAR_MODE_DEFAULT;
        final int userId = mCurrentUserId;
        final long userIdentity = Binder.clearCallingIdentity();
        try {
            navBarKidsMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NAV_BAR_KIDS_MODE, userId);
        } catch (Settings.SettingNotFoundException ex) {
            return navBarKidsMode;
        } finally {
            Binder.restoreCallingIdentity(userIdentity);
        }
        return navBarKidsMode;
    }

    private boolean isPackageSupported(String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            return mContext.getPackageManager().getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(0)) != null;
        } catch (PackageManager.NameNotFoundException ignored) {
            if (SPEW) {
                Slog.d(TAG, "Package not found: " + packageName);
            }
        }
        return false;
    }

    /**
     * Notifies the system of a new media tap-to-transfer state for the *sender* device. See
     * {@link StatusBarManager.updateMediaTapToTransferSenderDisplay} for more information.
     *
     * @param undoCallback a callback that will be triggered if the user elects to undo a media
     *                     transfer.
     *
     * Requires the caller to have the {@link android.Manifest.permission.MEDIA_CONTENT_CONTROL}
     * permission.
     */
    @Override
    public void updateMediaTapToTransferSenderDisplay(
            @StatusBarManager.MediaTransferSenderState int displayState,
            @NonNull MediaRoute2Info routeInfo,
            @Nullable IUndoMediaTransferCallback undoCallback
    ) {
        enforceMediaContentControl();
        if (mBar != null) {
            try {
                mBar.updateMediaTapToTransferSenderDisplay(displayState, routeInfo, undoCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "updateMediaTapToTransferSenderDisplay", e);
            }
        }
    }

    /**
     * Notifies the system of a new media tap-to-transfer state for the *receiver* device. See
     * {@link StatusBarManager.updateMediaTapToTransferReceiverDisplay} for more information.
     *
     * Requires the caller to have the {@link android.Manifest.permission.MEDIA_CONTENT_CONTROL}
     * permission.
     */
    @Override
    public void updateMediaTapToTransferReceiverDisplay(
            @StatusBarManager.MediaTransferReceiverState int displayState,
            MediaRoute2Info routeInfo,
            @Nullable Icon appIcon,
            @Nullable CharSequence appName) {
        enforceMediaContentControl();
        if (mBar != null) {
            try {
                mBar.updateMediaTapToTransferReceiverDisplay(
                        displayState, routeInfo, appIcon, appName);
            } catch (RemoteException e) {
                Slog.e(TAG, "updateMediaTapToTransferReceiverDisplay", e);
            }
        }
    }

    /**
     * Registers a provider that gives information about nearby devices that are able to play media.
     * See {@link StatusBarmanager.registerNearbyMediaDevicesProvider}.
     *
     * Requires the caller to have the {@link android.Manifest.permission.MEDIA_CONTENT_CONTROL}
     * permission.
     *
     * @param provider the nearby device information provider to register
     *
     * @hide
     */
    @Override
    public void registerNearbyMediaDevicesProvider(
            @NonNull INearbyMediaDevicesProvider provider
    ) {
        enforceMediaContentControl();
        if (mBar != null) {
            try {
                mBar.registerNearbyMediaDevicesProvider(provider);
            } catch (RemoteException e) {
                Slog.e(TAG, "registerNearbyMediaDevicesProvider", e);
            }
        }
    }

    /**
     * Unregisters a provider that gives information about nearby devices that are able to play
     * media. See {@link StatusBarmanager.unregisterNearbyMediaDevicesProvider}.
     *
     * Requires the caller to have the {@link android.Manifest.permission.MEDIA_CONTENT_CONTROL}
     * permission.
     *
     * @param provider the nearby device information provider to unregister
     *
     * @hide
     */
    @Override
    public void unregisterNearbyMediaDevicesProvider(
            @NonNull INearbyMediaDevicesProvider provider
    ) {
        enforceMediaContentControl();
        if (mBar != null) {
            try {
                mBar.unregisterNearbyMediaDevicesProvider(provider);
            } catch (RemoteException e) {
                Slog.e(TAG, "unregisterNearbyMediaDevicesProvider", e);
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    @Override
    public void showRearDisplayDialog(int currentState) {
        enforceControlDeviceStatePermission();
        if (mBar != null) {
            try {
                mBar.showRearDisplayDialog(currentState);
            } catch (RemoteException e) {
                Slog.e(TAG, "showRearDisplayDialog", e);
            }
        }
    }

    /** @hide */
    public void passThroughShellCommand(String[] args, FileDescriptor fd) {
        enforceStatusBarOrShell();
        if (mBar == null)  return;

        try (TransferPipe tp = new TransferPipe()) {
            // Sending the command to the remote, which needs to execute async to avoid blocking
            // See Binder#dumpAsync() for inspiration
            tp.setBufferPrefix("  ");
            mBar.passThroughShellCommand(args, tp.getWriteFd());
            // Times out after 5s
            tp.go(fd);
        } catch (Throwable t) {
            Slog.e(TAG, "Error sending command to IStatusBar", t);
        }
    }

    // ================================================================================
    // Can be called from any thread
    // ================================================================================

    // lock on mDisableRecords
    void manageDisableListLocked(int userId, int what, IBinder token, String pkg, int which) {
        if (SPEW) {
            Slog.d(TAG, "manageDisableList userId=" + userId
                    + " what=0x" + Integer.toHexString(what) + " pkg=" + pkg);
        }

        // Find matching record, if any
        Pair<Integer, DisableRecord> match = findMatchingRecordLocked(token, userId);
        int i = match.first;
        DisableRecord record = match.second;

        // Remove record if binder is already dead
        if (!token.isBinderAlive()) {
            if (record != null) {
                mDisableRecords.remove(i);
                record.token.unlinkToDeath(record, 0);
            }
            return;
        }

        // Update existing record
        if (record != null) {
            record.setFlags(what, which, pkg);
            if (record.isEmpty()) {
                mDisableRecords.remove(i);
                record.token.unlinkToDeath(record, 0);
            }
            return;
        }

        // Record doesn't exist, so we create a new one
        record = new DisableRecord(userId, token);
        record.setFlags(what, which, pkg);
        mDisableRecords.add(record);
    }

    @Nullable
    @GuardedBy("mLock")
    private Pair<Integer, DisableRecord> findMatchingRecordLocked(IBinder token, int userId) {
        final int numRecords = mDisableRecords.size();
        DisableRecord record = null;
        int i;
        for (i = 0; i < numRecords; i++) {
            DisableRecord r = mDisableRecords.get(i);
            if (r.token == token && r.userId == userId) {
                record = r;
                break;
            }
        }

        return new Pair<Integer, DisableRecord>(i, record);
    }

    // lock on mDisableRecords
    int gatherDisableActionsLocked(int userId, int which) {
        final int N = mDisableRecords.size();
        // gather the new net flags
        int net = 0;
        for (int i=0; i<N; i++) {
            final DisableRecord rec = mDisableRecords.get(i);
            if (rec.userId == userId) {
                net |= rec.getFlags(which);
            }
        }
        return net;
    }

    // ================================================================================
    // Always called from UI thread
    // ================================================================================

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        boolean proto = false;
        for (int i = 0; i < args.length; i++) {
            if ("--proto".equals(args[i])) {
                proto = true;
            }
        }
        if (proto) {
            if (mBar == null)  return;
            try (TransferPipe tp = new TransferPipe()) {
                // Sending the command to the remote, which needs to execute async to avoid blocking
                // See Binder#dumpAsync() for inspiration
                mBar.dumpProto(args, tp.getWriteFd());
                // Times out after 5s
                tp.go(fd);
            } catch (Throwable t) {
                Slog.e(TAG, "Error sending command to IStatusBar", t);
            }
            return;
        }

        synchronized (mLock) {
            for (int i = 0; i < mDisplayUiState.size(); i++) {
                final int key = mDisplayUiState.keyAt(i);
                final UiState state = mDisplayUiState.get(key);
                pw.println("  displayId=" + key);
                pw.println("    mDisabled1=0x" + Integer.toHexString(state.getDisabled1()));
                pw.println("    mDisabled2=0x" + Integer.toHexString(state.getDisabled2()));
            }
            final int N = mDisableRecords.size();
            pw.println("  mDisableRecords.size=" + N);
            for (int i=0; i<N; i++) {
                DisableRecord tok = mDisableRecords.get(i);
                pw.println("    [" + i + "] " + tok);
            }
            pw.println("  mCurrentUserId=" + mCurrentUserId);
            pw.println("  mIcons=");
            for (String slot : mIcons.keySet()) {
                pw.println("    ");
                pw.print(slot);
                pw.print(" -> ");
                final StatusBarIcon icon = mIcons.get(slot);
                pw.print(icon);
                if (!TextUtils.isEmpty(icon.contentDescription)) {
                    pw.print(" \"");
                    pw.print(icon.contentDescription);
                    pw.print("\"");
                }
                pw.println();
            }
            ArrayList<String> requests;
            synchronized (mCurrentRequestAddTilePackages) {
                requests = new ArrayList<>(mCurrentRequestAddTilePackages.keySet());
            }
            pw.println("  mCurrentRequestAddTilePackages=[");
            final int reqN = requests.size();
            for (int i = 0; i < reqN; i++) {
                pw.println("    " + requests.get(i) + ",");
            }
            pw.println("  ]");
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
            mTileRequestTracker.dump(fd, ipw.increaseIndent(), args);
        }
    }

    private static final Context getUiContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }
}
