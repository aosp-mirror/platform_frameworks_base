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

import android.app.ActivityThread;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricPromptReceiver;
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
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationDelegate;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.power.ShutdownThread;
import com.android.server.wm.WindowManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A note on locking:  We rely on the fact that calls onto mBar are oneway or
 * if they are local, that they just enqueue messages to not deadlock.
 */
public class StatusBarManagerService extends IStatusBarService.Stub {
    private static final String TAG = "StatusBarManagerService";
    private static final boolean SPEW = false;

    private final Context mContext;

    private final WindowManagerService mWindowManager;
    private Handler mHandler = new Handler();
    private NotificationDelegate mNotificationDelegate;
    private volatile IStatusBar mBar;
    private ArrayMap<String, StatusBarIcon> mIcons = new ArrayMap<>();

    // for disabling the status bar
    private final ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    private GlobalActionsProvider.GlobalActionsListener mGlobalActionListener;
    private IBinder mSysUiVisToken = new Binder();
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    private final Object mLock = new Object();
    // encompasses lights-out mode and other flags defined on View
    private int mSystemUiVisibility = 0;
    private int mFullscreenStackSysUiVisibility;
    private int mDockedStackSysUiVisibility;
    private final Rect mFullscreenStackBounds = new Rect();
    private final Rect mDockedStackBounds = new Rect();
    private boolean mMenuVisible = false;
    private int mImeWindowVis = 0;
    private int mImeBackDisposition;
    private boolean mShowImeSwitcher;
    private IBinder mImeToken = null;
    private int mCurrentUserId;

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
                    return;
                case 2:
                    what2 = what;
                    return;
                default:
                    Slog.w(TAG, "Can't set unsupported disable flag " + which
                            + ": 0x" + Integer.toHexString(what));
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
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarManagerService(Context context, WindowManagerService windowManager) {
        mContext = context;
        mWindowManager = windowManager;

        LocalServices.addService(StatusBarManagerInternal.class, mInternalService);
        LocalServices.addService(GlobalActionsProvider.class, mGlobalActionsProvider);
    }

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
        public void topAppWindowChanged(boolean menuVisible) {
            StatusBarManagerService.this.topAppWindowChanged(menuVisible);
        }

        @Override
        public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
                int mask,
                Rect fullscreenBounds, Rect dockedBounds, String cause) {
            StatusBarManagerService.this.setSystemUiVisibility(vis, fullscreenStackVis,
                    dockedStackVis, mask, fullscreenBounds, dockedBounds, cause);
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

        public void appTransitionFinished() {
            enforceStatusBarService();
            if (mBar != null) {
                try {
                    mBar.appTransitionFinished();
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
        public void setWindowState(int window, int state) {
            if (mBar != null) {
                try {
                    mBar.setWindowState(window, state);
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionPending() {
            if (mBar != null) {
                try {
                    mBar.appTransitionPending();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionCancelled() {
            if (mBar != null) {
                try {
                    mBar.appTransitionCancelled();
                } catch (RemoteException ex) {}
            }
        }

        @Override
        public void appTransitionStarting(long statusBarAnimationsStartTime,
                long statusBarAnimationsDuration) {
            if (mBar != null) {
                try {
                    mBar.appTransitionStarting(
                            statusBarAnimationsStartTime, statusBarAnimationsDuration);
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

        @Override
        public void onProposedRotationChanged(int rotation, boolean isValid) {
            if (mBar != null){
                try {
                    mBar.onProposedRotationChanged(rotation, isValid);
                } catch (RemoteException ex) {}
            }
        }
    };

    private final GlobalActionsProvider mGlobalActionsProvider = new GlobalActionsProvider() {
        @Override
        public boolean isGlobalActionsDisabled() {
            return (mDisabled2 & DISABLE2_GLOBAL_ACTIONS) != 0;
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
    public void showFingerprintDialog(Bundle bundle, IBiometricPromptReceiver receiver) {
        if (mBar != null) {
            try {
                mBar.showFingerprintDialog(bundle, receiver);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onFingerprintAuthenticated() {
        if (mBar != null) {
            try {
                mBar.onFingerprintAuthenticated();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onFingerprintHelp(String message) {
        if (mBar != null) {
            try {
                mBar.onFingerprintHelp(message);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void onFingerprintError(String error) {
        if (mBar != null) {
            try {
                mBar.onFingerprintError(error);
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void hideFingerprintDialog() {
        if (mBar != null) {
            try {
                mBar.hideFingerprintDialog();
            } catch (RemoteException ex) {
            }
        }
    }

    @Override
    public void disable(int what, IBinder token, String pkg) {
        disableForUser(what, token, pkg, mCurrentUserId);
    }

    @Override
    public void disableForUser(int what, IBinder token, String pkg, int userId) {
        enforceStatusBar();

        synchronized (mLock) {
            disableLocked(userId, what, token, pkg, 1);
        }
    }

    /**
     * Disable additional status bar features. Pass the bitwise-or of the DISABLE2_* flags.
     * To re-enable everything, pass {@link #DISABLE_NONE}.
     *
     * Warning: Only pass DISABLE2_* flags into this function, do not use DISABLE_* flags.
     */
    @Override
    public void disable2(int what, IBinder token, String pkg) {
        disable2ForUser(what, token, pkg, mCurrentUserId);
    }

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
            disableLocked(userId, what, token, pkg, 2);
        }
    }

    private void disableLocked(int userId, int what, IBinder token, String pkg, int whichFlag) {
        // It's important that the the callback and the call to mBar get done
        // in the same order when multiple threads are calling this function
        // so they are paired correctly.  The messages on the handler will be
        // handled in the order they were enqueued, but will be outside the lock.
        manageDisableListLocked(userId, what, token, pkg, whichFlag);

        // Ensure state for the current user is applied, even if passed a non-current user.
        final int net1 = gatherDisableActionsLocked(mCurrentUserId, 1);
        final int net2 = gatherDisableActionsLocked(mCurrentUserId, 2);
        if (net1 != mDisabled1 || net2 != mDisabled2) {
            mDisabled1 = net1;
            mDisabled2 = net2;
            mHandler.post(new Runnable() {
                    public void run() {
                        mNotificationDelegate.onSetDisabled(net1);
                    }
                });
            if (mBar != null) {
                try {
                    mBar.disable(net1, net2);
                } catch (RemoteException ex) {
                }
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

    /**
     * Hide or show the on-screen Menu key. Only call this from the window manager, typically in
     * response to a window with {@link android.view.WindowManager.LayoutParams#needsMenuKey} set
     * to {@link android.view.WindowManager.LayoutParams#NEEDS_MENU_SET_TRUE}.
     */
    private void topAppWindowChanged(final boolean menuVisible) {
        enforceStatusBar();

        if (SPEW) Slog.d(TAG, (menuVisible?"showing":"hiding") + " MENU key");

        synchronized(mLock) {
            mMenuVisible = menuVisible;
            mHandler.post(new Runnable() {
                public void run() {
                    if (mBar != null) {
                        try {
                            mBar.topAppWindowChanged(menuVisible);
                        } catch (RemoteException ex) {
                        }
                    }
                }
            });
        }
    }

    @Override
    public void setImeWindowStatus(final IBinder token, final int vis, final int backDisposition,
            final boolean showImeSwitcher) {
        enforceStatusBar();

        if (SPEW) {
            Slog.d(TAG, "swetImeWindowStatus vis=" + vis + " backDisposition=" + backDisposition);
        }

        synchronized(mLock) {
            // In case of IME change, we need to call up setImeWindowStatus() regardless of
            // mImeWindowVis because mImeWindowVis may not have been set to false when the
            // previous IME was destroyed.
            mImeWindowVis = vis;
            mImeBackDisposition = backDisposition;
            mImeToken = token;
            mShowImeSwitcher = showImeSwitcher;
            mHandler.post(new Runnable() {
                public void run() {
                    if (mBar != null) {
                        try {
                            mBar.setImeWindowStatus(token, vis, backDisposition, showImeSwitcher);
                        } catch (RemoteException ex) {
                        }
                    }
                }
            });
        }
    }

    @Override
    public void setSystemUiVisibility(int vis, int mask, String cause) {
        setSystemUiVisibility(vis, 0, 0, mask, mFullscreenStackBounds, mDockedStackBounds, cause);
    }

    private void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis, int mask,
            Rect fullscreenBounds, Rect dockedBounds, String cause) {
        // also allows calls from window manager which is in this process.
        enforceStatusBarService();

        if (SPEW) Slog.d(TAG, "setSystemUiVisibility(0x" + Integer.toHexString(vis) + ")");

        synchronized (mLock) {
            updateUiVisibilityLocked(vis, fullscreenStackVis, dockedStackVis, mask,
                    fullscreenBounds, dockedBounds);
            disableLocked(
                    mCurrentUserId,
                    vis & StatusBarManager.DISABLE_MASK,
                    mSysUiVisToken,
                    cause, 1);
        }
    }

    private void updateUiVisibilityLocked(final int vis,
            final int fullscreenStackVis, final int dockedStackVis, final int mask,
            final Rect fullscreenBounds, final Rect dockedBounds) {
        if (mSystemUiVisibility != vis
                || mFullscreenStackSysUiVisibility != fullscreenStackVis
                || mDockedStackSysUiVisibility != dockedStackVis
                || !mFullscreenStackBounds.equals(fullscreenBounds)
                || !mDockedStackBounds.equals(dockedBounds)) {
            mSystemUiVisibility = vis;
            mFullscreenStackSysUiVisibility = fullscreenStackVis;
            mDockedStackSysUiVisibility = dockedStackVis;
            mFullscreenStackBounds.set(fullscreenBounds);
            mDockedStackBounds.set(dockedBounds);
            mHandler.post(new Runnable() {
                    public void run() {
                        if (mBar != null) {
                            try {
                                mBar.setSystemUiVisibility(vis, fullscreenStackVis, dockedStackVis,
                                        mask, fullscreenBounds, dockedBounds);
                            } catch (RemoteException ex) {
                            }
                        }
                    }
                });
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

    // ================================================================================
    // Callbacks from the status bar service.
    // ================================================================================
    @Override
    public void registerStatusBar(IStatusBar bar, List<String> iconSlots,
            List<StatusBarIcon> iconList, int switches[], List<IBinder> binders,
            Rect fullscreenStackBounds, Rect dockedStackBounds) {
        enforceStatusBarService();

        Slog.i(TAG, "registerStatusBar bar=" + bar);
        mBar = bar;
        try {
            mBar.asBinder().linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    mBar = null;
                    notifyBarAttachChanged();
                }
            }, 0);
        } catch (RemoteException e) {
        }
        notifyBarAttachChanged();
        synchronized (mIcons) {
            for (String slot : mIcons.keySet()) {
                iconSlots.add(slot);
                iconList.add(mIcons.get(slot));
            }
        }
        synchronized (mLock) {
            switches[0] = gatherDisableActionsLocked(mCurrentUserId, 1);
            switches[1] = mSystemUiVisibility;
            switches[2] = mMenuVisible ? 1 : 0;
            switches[3] = mImeWindowVis;
            switches[4] = mImeBackDisposition;
            switches[5] = mShowImeSwitcher ? 1 : 0;
            switches[6] = gatherDisableActionsLocked(mCurrentUserId, 2);
            switches[7] = mFullscreenStackSysUiVisibility;
            switches[8] = mDockedStackSysUiVisibility;
            binders.add(mImeToken);
            fullscreenStackBounds.set(mFullscreenStackBounds);
            dockedStackBounds.set(mDockedStackBounds);
        }
    }

    private void notifyBarAttachChanged() {
        mHandler.post(() -> {
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
    public void onNotificationActionClick(String key, int actionIndex, NotificationVisibility nv) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationActionClick(callingUid, callingPid, key,
                    actionIndex, nv);
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
            @NotificationStats.DismissalSurface int dismissalSurface, NotificationVisibility nv) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClear(callingUid, callingPid, pkg, tag, id, userId,
                    key, dismissalSurface, nv);
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
    public void onNotificationExpansionChanged(String key, boolean userAction,
            boolean expanded) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationExpansionChanged(
                    key, userAction, expanded);
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
    public void onNotificationSmartRepliesAdded(String key, int replyCount)
            throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationSmartRepliesAdded(key, replyCount);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationSmartReplySent(String key, int replyIndex)
            throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationSmartReplySent(key, replyIndex);
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
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new StatusBarShellCommand(this)).exec(
                this, in, out, err, args, callback, resultReceiver);
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
        final int N = mDisableRecords.size();
        DisableRecord record = null;
        int i;
        for (i = 0; i < N; i++) {
            DisableRecord r = mDisableRecords.get(i);
            if (r.token == token && r.userId == userId) {
                record = r;
                break;
            }
        }

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
            pw.println("  mDisabled1=0x" + Integer.toHexString(mDisabled1));
            pw.println("  mDisabled2=0x" + Integer.toHexString(mDisabled2));
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
