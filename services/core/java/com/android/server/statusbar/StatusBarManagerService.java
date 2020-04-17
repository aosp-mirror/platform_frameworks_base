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

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.ITransientNotificationCallback;
import android.app.Notification;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.service.notification.NotificationStats;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InsetsState.InternalInsetsType;
import android.view.WindowInsetsController.Appearance;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.DumpUtils;
import com.android.internal.view.AppearanceRegion;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.notification.NotificationDelegate;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.power.ShutdownThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A note on locking:  We rely on the fact that calls onto mBar are oneway or
 * if they are local, that they just enqueue messages to not deadlock.
 */
public class StatusBarManagerService extends IStatusBarService.Stub implements DisplayListener {
    private static final String TAG = "StatusBarManagerService";
    private static final boolean SPEW = false;

    private final Context mContext;

    private Handler mHandler = new Handler();
    private NotificationDelegate mNotificationDelegate;
    private volatile IStatusBar mBar;
    private ArrayMap<String, StatusBarIcon> mIcons = new ArrayMap<>();

    // for disabling the status bar
    private final ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    private GlobalActionsProvider.GlobalActionsListener mGlobalActionListener;
    private IBinder mSysUiVisToken = new Binder();

    private final Object mLock = new Object();
    private final DeathRecipient mDeathRecipient = new DeathRecipient();
    private int mCurrentUserId;
    private boolean mTracingEnabled;

    private SparseArray<UiState> mDisplayUiState = new SparseArray<>();

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

        @Override
        public void topAppWindowChanged(int displayId, boolean isFullscreen, boolean isImmersive) {
            StatusBarManagerService.this.topAppWindowChanged(displayId, isFullscreen, isImmersive);
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
        public void onSystemBarAppearanceChanged(int displayId, @Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme) {
            final UiState state = getUiState(displayId);
            if (!state.appearanceEquals(appearance, appearanceRegions, navbarColorManagedByIme)) {
                state.setAppearance(appearance, appearanceRegions, navbarColorManagedByIme);
            }
            if (mBar != null) {
                try {
                    mBar.onSystemBarAppearanceChanged(displayId, appearance, appearanceRegions,
                            navbarColorManagedByIme);
                } catch (RemoteException ex) { }
            }
        }

        @Override
        public void showTransient(int displayId, @InternalInsetsType int[] types) {
            getUiState(displayId).showTransient(types);
            if (mBar != null) {
                try {
                    mBar.showTransient(displayId, types);
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
                @Nullable ITransientNotificationCallback callback) {
            if (mBar != null) {
                try {
                    mBar.showToast(uid, packageName, token, text, windowToken, duration, callback);
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
        public void requestWindowMagnificationConnection(boolean request) {
            if (mBar != null) {
                try {
                    mBar.requestWindowMagnificationConnection(request);
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

    // ================================================================================
    // From IStatusBarService
    // ================================================================================
    @Override
    public void expandNotificationsPanel() {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateExpandNotificationsPanel();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void collapsePanels() {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateCollapsePanels();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void togglePanel() {
        enforceExpandStatusBar();

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
        enforceExpandStatusBar();

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
    public void showAuthenticationDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver,
            @BiometricAuthenticator.Modality int biometricModality, boolean requireConfirmation,
            int userId, String opPackageName, long operationId) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.showAuthenticationDialog(bundle, receiver, biometricModality,
                        requireConfirmation, userId, opPackageName, operationId);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onBiometricAuthenticated() {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.onBiometricAuthenticated();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onBiometricHelp(String message) {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.onBiometricHelp(message);
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
    public void hideAuthenticationDialog() {
        enforceBiometricDialog();
        if (mBar != null) {
            try {
                mBar.hideAuthenticationDialog();
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
            } catch (RemoteException ex) {}
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

    /**
     * Enables System UI to know whether the top app is fullscreen or not, and whether this app is
     * in immersive mode or not.
     */
    private void topAppWindowChanged(int displayId, boolean isFullscreen, boolean isImmersive) {
        enforceStatusBar();

        synchronized(mLock) {
            getUiState(displayId).setFullscreen(isFullscreen);
            getUiState(displayId).setImmersive(isImmersive);
            mHandler.post(() -> {
                if (mBar != null) {
                    try {
                        mBar.topAppWindowChanged(displayId, isFullscreen, isImmersive);
                    } catch (RemoteException ex) {
                    }
                }
            });
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, final IBinder token, final int vis,
            final int backDisposition, final boolean showImeSwitcher,
            boolean isMultiClientImeEnabled) {
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
                            displayId, token, vis, backDisposition, showImeSwitcher,
                            isMultiClientImeEnabled);
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

    private class UiState {
        private @Appearance int mAppearance = 0;
        private AppearanceRegion[] mAppearanceRegions = new AppearanceRegion[0];
        private ArraySet<Integer> mTransientBarTypes = new ArraySet<>();
        private boolean mNavbarColorManagedByIme = false;
        private boolean mFullscreen = false;
        private boolean mImmersive = false;
        private int mDisabled1 = 0;
        private int mDisabled2 = 0;
        private int mImeWindowVis = 0;
        private int mImeBackDisposition = 0;
        private boolean mShowImeSwitcher = false;
        private IBinder mImeToken = null;

        private void setAppearance(@Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme) {
            mAppearance = appearance;
            mAppearanceRegions = appearanceRegions;
            mNavbarColorManagedByIme = navbarColorManagedByIme;
        }

        private boolean appearanceEquals(@Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme) {
            if (mAppearance != appearance || mAppearanceRegions.length != appearanceRegions.length
                    || mNavbarColorManagedByIme != navbarColorManagedByIme) {
                return false;
            }
            for (int i = appearanceRegions.length - 1; i >= 0; i--) {
                if (!mAppearanceRegions[i].equals(appearanceRegions[i])) {
                    return false;
                }
            }
            return true;
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

        private void setFullscreen(boolean isFullscreen) {
            mFullscreen = isFullscreen;
        }

        private void setImmersive(boolean isImmersive) {
            mImmersive = isImmersive;
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
                    state.mNavbarColorManagedByIme, state.mFullscreen, state.mImmersive,
                    transientBarTypes);
        }
    }

    private void notifyBarAttachChanged() {
        UiThread.getHandler().post(() -> {
            if (mGlobalActionListener == null) return;
            mGlobalActionListener.onGlobalActionsAvailableChanged(mBar != null);
        });
    }

    /**
     * @param clearNotificationEffects whether to consider notifications as "shown" and stop
     *     LED, vibration, and ringing
     */
    @Override
    public void onPanelRevealed(boolean clearNotificationEffects, int numItems) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onPanelRevealed(clearNotificationEffects, numItems);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearNotificationEffects() throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.clearEffects();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onPanelHidden() throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.prepareForPossibleShutdown();
            // ShutdownThread displays UI, so give it a UI context.
            mHandler.post(() ->
                    ShutdownThread.shutdown(getUiContext(),
                        PowerManager.SHUTDOWN_USER_REQUESTED, false));
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
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.prepareForPossibleShutdown();
            mHandler.post(() -> {
                // ShutdownThread displays UI, so give it a UI context.
                if (safeMode) {
                    ShutdownThread.rebootSafeMode(getUiContext(), true);
                } else {
                    ShutdownThread.reboot(getUiContext(),
                            PowerManager.SHUTDOWN_USER_REQUESTED, false);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onGlobalActionsShown() {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
        try {
            // WARNING: this will call back into us to do the remove.  Don't hold any locks.
            mNotificationDelegate.onNotificationError(callingUid, callingPid,
                    pkg, tag, id, uid, initialPid, message, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationClear(String pkg, String tag, int id, int userId, String key,
            @NotificationStats.DismissalSurface int dismissalSurface,
            @NotificationStats.DismissalSentiment int dismissalSentiment,
            NotificationVisibility nv) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClear(callingUid, callingPid, pkg, tag, id, userId,
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onClearAll(callingUid, callingPid, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationBubbleChanged(String key, boolean isBubble, int flags) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationBubbleChanged(key, isBubble, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onBubbleNotificationSuppressionChanged(String key, boolean isNotifSuppressed) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onBubbleNotificationSuppressionChanged(key, isNotifSuppressed);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void grantInlineReplyUriPermission(String key, Uri uri, UserHandle user,
            String packageName) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
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
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.clearInlineReplyUriPermissions(key, callingUid);
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

    public String[] getStatusBarIcons() {
        return mContext.getResources().getStringArray(R.array.config_statusBarIcons);
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
        }
    }

    private static final Context getUiContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }
}
