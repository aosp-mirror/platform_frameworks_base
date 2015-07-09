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

import android.app.StatusBarManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationDelegate;
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
    private StatusBarIconList mIcons = new StatusBarIconList();

    // for disabling the status bar
    private final ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    private IBinder mSysUiVisToken = new Binder();
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    private Object mLock = new Object();
    // encompasses lights-out mode and other flags defined on View
    private int mSystemUiVisibility = 0;
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

        public void binderDied() {
            Slog.i(TAG, "binder died for pkg=" + pkg);
            disableForUser(0, token, pkg, userId);
            disable2ForUser(0, token, pkg, userId);
            token.unlinkToDeath(this, 0);
        }
    }

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarManagerService(Context context, WindowManagerService windowManager) {
        mContext = context;
        mWindowManager = windowManager;

        final Resources res = context.getResources();
        mIcons.defineSlots(res.getStringArray(com.android.internal.R.array.config_statusBarIcons));

        LocalServices.addService(StatusBarManagerInternal.class, mInternalService);
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
        public void buzzBeepBlinked() {
            if (mBar != null) {
                try {
                    mBar.buzzBeepBlinked();
                } catch (RemoteException ex) {
                }
            }
        }

        @Override
        public void notificationLightPulse(int argb, int onMillis, int offMillis) {
            mNotificationLightOn = true;
            if (mBar != null) {
                try {
                    mBar.notificationLightPulse(argb, onMillis, offMillis);
                } catch (RemoteException ex) {
                }
            }
        }

        @Override
        public void notificationLightOff() {
            if (mNotificationLightOn) {
                mNotificationLightOn = false;
                if (mBar != null) {
                    try {
                        mBar.notificationLightOff();
                    } catch (RemoteException ex) {
                    }
                }
            }
        }

        @Override
        public void showScreenPinningRequest() {
            if (mBar != null) {
                try {
                    mBar.showScreenPinningRequest();
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
    public void expandSettingsPanel() {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateExpandSettingsPanel();
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
        disableForUser(what, token, pkg, mCurrentUserId);
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
            int index = mIcons.getSlotIndex(slot);
            if (index < 0) {
                throw new SecurityException("invalid status bar icon slot: " + slot);
            }

            StatusBarIcon icon = new StatusBarIcon(iconPackage, UserHandle.OWNER, iconId,
                    iconLevel, 0,
                    contentDescription);
            //Slog.d(TAG, "setIcon slot=" + slot + " index=" + index + " icon=" + icon);
            mIcons.setIcon(index, icon);

            if (mBar != null) {
                try {
                    mBar.setIcon(index, icon);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    @Override
    public void setIconVisibility(String slot, boolean visible) {
        enforceStatusBar();

        synchronized (mIcons) {
            int index = mIcons.getSlotIndex(slot);
            if (index < 0) {
                throw new SecurityException("invalid status bar icon slot: " + slot);
            }

            StatusBarIcon icon = mIcons.getIcon(index);
            if (icon == null) {
                return;
            }

            if (icon.visible != visible) {
                icon.visible = visible;

                if (mBar != null) {
                    try {
                        mBar.setIcon(index, icon);
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
            int index = mIcons.getSlotIndex(slot);
            if (index < 0) {
                throw new SecurityException("invalid status bar icon slot: " + slot);
            }

            mIcons.removeIcon(index);

            if (mBar != null) {
                try {
                    mBar.removeIcon(index);
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
    @Override
    public void topAppWindowChanged(final boolean menuVisible) {
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
        // also allows calls from window manager which is in this process.
        enforceStatusBarService();

        if (SPEW) Slog.d(TAG, "setSystemUiVisibility(0x" + Integer.toHexString(vis) + ")");

        synchronized (mLock) {
            updateUiVisibilityLocked(vis, mask);
            disableLocked(
                    mCurrentUserId,
                    vis & StatusBarManager.DISABLE_MASK,
                    mSysUiVisToken,
                    cause, 1);
        }
    }

    private void updateUiVisibilityLocked(final int vis, final int mask) {
        if (mSystemUiVisibility != vis) {
            mSystemUiVisibility = vis;
            mHandler.post(new Runnable() {
                    public void run() {
                        if (mBar != null) {
                            try {
                                mBar.setSystemUiVisibility(vis, mask);
                            } catch (RemoteException ex) {
                            }
                        }
                    }
                });
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
    public void setCurrentUser(int newUserId) {
        if (SPEW) Slog.d(TAG, "Setting current user to user " + newUserId);
        mCurrentUserId = newUserId;
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
    public void startAssist(Bundle args) {
        if (mBar != null) {
            try {
                mBar.startAssist(args);
            } catch (RemoteException ex) {}
        }
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
    public void registerStatusBar(IStatusBar bar, StatusBarIconList iconList,
            int switches[], List<IBinder> binders) {
        enforceStatusBarService();

        Slog.i(TAG, "registerStatusBar bar=" + bar);
        mBar = bar;
        synchronized (mIcons) {
            iconList.copyFrom(mIcons);
        }
        synchronized (mLock) {
            switches[0] = gatherDisableActionsLocked(mCurrentUserId, 1);
            switches[1] = mSystemUiVisibility;
            switches[2] = mMenuVisible ? 1 : 0;
            switches[3] = mImeWindowVis;
            switches[4] = mImeBackDisposition;
            switches[5] = mShowImeSwitcher ? 1 : 0;
            switches[6] = gatherDisableActionsLocked(mCurrentUserId, 2);
            binders.add(mImeToken);
        }
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

    @Override
    public void onNotificationClick(String key) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClick(callingUid, callingPid, key);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void onNotificationActionClick(String key, int actionIndex) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationActionClick(callingUid, callingPid, key,
                    actionIndex);
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
    public void onNotificationClear(String pkg, String tag, int id, int userId) {
        enforceStatusBarService();
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            mNotificationDelegate.onNotificationClear(callingUid, callingPid, pkg, tag, id, userId);
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

    // ================================================================================
    // Can be called from any thread
    // ================================================================================

    // lock on mDisableRecords
    void manageDisableListLocked(int userId, int what, IBinder token, String pkg, int which) {
        if (SPEW) {
            Slog.d(TAG, "manageDisableList userId=" + userId
                    + " what=0x" + Integer.toHexString(what) + " pkg=" + pkg);
        }
        // update the list
        final int N = mDisableRecords.size();
        DisableRecord tok = null;
        int i;
        for (i=0; i<N; i++) {
            DisableRecord t = mDisableRecords.get(i);
            if (t.token == token && t.userId == userId) {
                tok = t;
                break;
            }
        }
        if (what == 0 || !token.isBinderAlive()) {
            if (tok != null) {
                mDisableRecords.remove(i);
                tok.token.unlinkToDeath(tok, 0);
            }
        } else {
            if (tok == null) {
                tok = new DisableRecord();
                tok.userId = userId;
                try {
                    token.linkToDeath(tok, 0);
                }
                catch (RemoteException ex) {
                    return; // give up
                }
                mDisableRecords.add(tok);
            }
            if (which == 1) {
                tok.what1 = what;
            } else {
                tok.what2 = what;
            }
            tok.token = token;
            tok.pkg = pkg;
        }
    }

    // lock on mDisableRecords
    int gatherDisableActionsLocked(int userId, int which) {
        final int N = mDisableRecords.size();
        // gather the new net flags
        int net = 0;
        for (int i=0; i<N; i++) {
            final DisableRecord rec = mDisableRecords.get(i);
            if (rec.userId == userId) {
                net |= (which == 1) ? rec.what1 : rec.what2;
            }
        }
        return net;
    }

    // ================================================================================
    // Always called from UI thread
    // ================================================================================

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump StatusBar from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mIcons) {
            mIcons.dump(pw);
        }

        synchronized (mLock) {
            pw.println("  mDisabled1=0x" + Integer.toHexString(mDisabled1));
            pw.println("  mDisabled2=0x" + Integer.toHexString(mDisabled2));
            final int N = mDisableRecords.size();
            pw.println("  mDisableRecords.size=" + N);
            for (int i=0; i<N; i++) {
                DisableRecord tok = mDisableRecords.get(i);
                pw.println("    [" + i + "] userId=" + tok.userId
                                + " what1=0x" + Integer.toHexString(tok.what1)
                                + " what2=0x" + Integer.toHexString(tok.what2)
                                + " pkg=" + tok.pkg
                                + " token=" + tok.token);
            }
        }
    }
}
