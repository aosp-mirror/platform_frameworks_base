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

package com.android.server;

import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.View;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.server.wm.WindowManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A note on locking:  We rely on the fact that calls onto mBar are oneway or
 * if they are local, that they just enqueue messages to not deadlock.
 */
public class StatusBarManagerService extends IStatusBarService.Stub
    implements WindowManagerService.OnHardKeyboardStatusChangeListener
{
    static final String TAG = "StatusBarManagerService";
    static final boolean SPEW = false;

    final Context mContext;
    final WindowManagerService mWindowManager;
    Handler mHandler = new Handler();
    NotificationCallbacks mNotificationCallbacks;
    volatile IStatusBar mBar;
    StatusBarIconList mIcons = new StatusBarIconList();
    HashMap<IBinder,StatusBarNotification> mNotifications
            = new HashMap<IBinder,StatusBarNotification>();

    // for disabling the status bar
    ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    IBinder mSysUiVisToken = new Binder();
    int mDisabled = 0;

    Object mLock = new Object();
    // encompasses lights-out mode and other flags defined on View
    int mSystemUiVisibility = 0;
    boolean mMenuVisible = false;
    int mImeWindowVis = 0;
    int mImeBackDisposition;
    IBinder mImeToken = null;

    private class DisableRecord implements IBinder.DeathRecipient {
        String pkg;
        int what;
        IBinder token;

        public void binderDied() {
            Slog.i(TAG, "binder died for pkg=" + pkg);
            disable(0, token, pkg);
            token.unlinkToDeath(this, 0);
        }
    }

    public interface NotificationCallbacks {
        void onSetDisabled(int status);
        void onClearAll();
        void onNotificationClick(String pkg, String tag, int id);
        void onNotificationClear(String pkg, String tag, int id);
        void onPanelRevealed();
        void onNotificationError(String pkg, String tag, int id,
                int uid, int initialPid, String message);
    }

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarManagerService(Context context, WindowManagerService windowManager) {
        mContext = context;
        mWindowManager = windowManager;
        mWindowManager.setOnHardKeyboardStatusChangeListener(this);

        final Resources res = context.getResources();
        mIcons.defineSlots(res.getStringArray(com.android.internal.R.array.config_statusBarIcons));
    }

    public void setNotificationCallbacks(NotificationCallbacks listener) {
        mNotificationCallbacks = listener;
    }

    // ================================================================================
    // From IStatusBarService
    // ================================================================================
    public void userActivity() {
        if (mBar != null) try {
            mBar.userActivity();
        } catch (RemoteException ex) {}
    }
    public void expand() {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateExpand();
            } catch (RemoteException ex) {
            }
        }
    }

    public void collapse() {
        enforceExpandStatusBar();

        if (mBar != null) {
            try {
                mBar.animateCollapse();
            } catch (RemoteException ex) {
            }
        }
    }

    public void disable(int what, IBinder token, String pkg) {
        enforceStatusBar();

        synchronized (mLock) {
            disableLocked(what, token, pkg);
        }
    }

    private void disableLocked(int what, IBinder token, String pkg) {
        // It's important that the the callback and the call to mBar get done
        // in the same order when multiple threads are calling this function
        // so they are paired correctly.  The messages on the handler will be
        // handled in the order they were enqueued, but will be outside the lock.
        manageDisableListLocked(what, token, pkg);
        final int net = gatherDisableActionsLocked();
        if (net != mDisabled) {
            mDisabled = net;
            mHandler.post(new Runnable() {
                    public void run() {
                        mNotificationCallbacks.onSetDisabled(net);
                    }
                });
            if (mBar != null) {
                try {
                    mBar.disable(net);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    public void setIcon(String slot, String iconPackage, int iconId, int iconLevel,
            String contentDescription) {
        enforceStatusBar();

        synchronized (mIcons) {
            int index = mIcons.getSlotIndex(slot);
            if (index < 0) {
                throw new SecurityException("invalid status bar icon slot: " + slot);
            }

            StatusBarIcon icon = new StatusBarIcon(iconPackage, iconId, iconLevel, 0,
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
     * response to a window with FLAG_NEEDS_MENU_KEY set.
     */
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

    public void setImeWindowStatus(final IBinder token, final int vis, final int backDisposition) {
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
            mHandler.post(new Runnable() {
                public void run() {
                    if (mBar != null) {
                        try {
                            mBar.setImeWindowStatus(token, vis, backDisposition);
                        } catch (RemoteException ex) {
                        }
                    }
                }
            });
        }
    }

    public void setSystemUiVisibility(int vis) {
        // also allows calls from window manager which is in this process.
        enforceStatusBarService();

        if (SPEW) Slog.d(TAG, "setSystemUiVisibility(" + vis + ")");

        synchronized (mLock) {
            updateUiVisibilityLocked(vis);
            disableLocked(vis & StatusBarManager.DISABLE_MASK, mSysUiVisToken,
                    "WindowManager.LayoutParams");
        }
    }

    private void updateUiVisibilityLocked(final int vis) {
        if (mSystemUiVisibility != vis) {
            mSystemUiVisibility = vis;
            mHandler.post(new Runnable() {
                    public void run() {
                        if (mBar != null) {
                            try {
                                mBar.setSystemUiVisibility(vis);
                            } catch (RemoteException ex) {
                            }
                        }
                    }
                });
        }
    }

    public void setHardKeyboardEnabled(final boolean enabled) {
        mHandler.post(new Runnable() {
            public void run() {
                mWindowManager.setHardKeyboardEnabled(enabled);
            }
        });
    }

    @Override
    public void onHardKeyboardStatusChange(final boolean available, final boolean enabled) {
        mHandler.post(new Runnable() {
            public void run() {
                if (mBar != null) {
                    try {
                        mBar.setHardKeyboardStatus(available, enabled);
                    } catch (RemoteException ex) {
                    }
                }
            }
        });
    }

    @Override
    public void toggleRecentApps() {
        if (mBar != null) {
            try {
                mBar.toggleRecentApps();
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
    public void registerStatusBar(IStatusBar bar, StatusBarIconList iconList,
            List<IBinder> notificationKeys, List<StatusBarNotification> notifications,
            int switches[], List<IBinder> binders) {
        enforceStatusBarService();

        Slog.i(TAG, "registerStatusBar bar=" + bar);
        mBar = bar;
        synchronized (mIcons) {
            iconList.copyFrom(mIcons);
        }
        synchronized (mNotifications) {
            for (Map.Entry<IBinder,StatusBarNotification> e: mNotifications.entrySet()) {
                notificationKeys.add(e.getKey());
                notifications.add(e.getValue());
            }
        }
        synchronized (mLock) {
            switches[0] = gatherDisableActionsLocked();
            switches[1] = mSystemUiVisibility;
            switches[2] = mMenuVisible ? 1 : 0;
            switches[3] = mImeWindowVis;
            switches[4] = mImeBackDisposition;
            binders.add(mImeToken);
        }
        switches[5] = mWindowManager.isHardKeyboardAvailable() ? 1 : 0;
        switches[6] = mWindowManager.isHardKeyboardEnabled() ? 1 : 0;
    }

    /**
     * The status bar service should call this each time the user brings the panel from
     * invisible to visible in order to clear the notification light.
     */
    public void onPanelRevealed() {
        enforceStatusBarService();

        // tell the notification manager to turn off the lights.
        mNotificationCallbacks.onPanelRevealed();
    }

    public void onNotificationClick(String pkg, String tag, int id) {
        enforceStatusBarService();

        mNotificationCallbacks.onNotificationClick(pkg, tag, id);
    }

    public void onNotificationError(String pkg, String tag, int id,
            int uid, int initialPid, String message) {
        enforceStatusBarService();

        // WARNING: this will call back into us to do the remove.  Don't hold any locks.
        mNotificationCallbacks.onNotificationError(pkg, tag, id, uid, initialPid, message);
    }

    public void onNotificationClear(String pkg, String tag, int id) {
        enforceStatusBarService();

        mNotificationCallbacks.onNotificationClear(pkg, tag, id);
    }

    public void onClearAllNotifications() {
        enforceStatusBarService();

        mNotificationCallbacks.onClearAll();
    }

    // ================================================================================
    // Callbacks for NotificationManagerService.
    // ================================================================================
    public IBinder addNotification(StatusBarNotification notification) {
        synchronized (mNotifications) {
            IBinder key = new Binder();
            mNotifications.put(key, notification);
            if (mBar != null) {
                try {
                    mBar.addNotification(key, notification);
                } catch (RemoteException ex) {
                }
            }
            return key;
        }
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        synchronized (mNotifications) {
            if (!mNotifications.containsKey(key)) {
                throw new IllegalArgumentException("updateNotification key not found: " + key);
            }
            mNotifications.put(key, notification);
            if (mBar != null) {
                try {
                    mBar.updateNotification(key, notification);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    public void removeNotification(IBinder key) {
        synchronized (mNotifications) {
            final StatusBarNotification n = mNotifications.remove(key);
            if (n == null) {
                throw new IllegalArgumentException("removeNotification key not found: " + key);
            }
            if (mBar != null) {
                try {
                    mBar.removeNotification(key);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    // ================================================================================
    // Can be called from any thread
    // ================================================================================

    // lock on mDisableRecords
    void manageDisableListLocked(int what, IBinder token, String pkg) {
        if (SPEW) {
            Slog.d(TAG, "manageDisableList what=0x" + Integer.toHexString(what) + " pkg=" + pkg);
        }
        // update the list
        final int N = mDisableRecords.size();
        DisableRecord tok = null;
        int i;
        for (i=0; i<N; i++) {
            DisableRecord t = mDisableRecords.get(i);
            if (t.token == token) {
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
                try {
                    token.linkToDeath(tok, 0);
                }
                catch (RemoteException ex) {
                    return; // give up
                }
                mDisableRecords.add(tok);
            }
            tok.what = what;
            tok.token = token;
            tok.pkg = pkg;
        }
    }

    // lock on mDisableRecords
    int gatherDisableActionsLocked() {
        final int N = mDisableRecords.size();
        // gather the new net flags
        int net = 0;
        for (int i=0; i<N; i++) {
            net |= mDisableRecords.get(i).what;
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

        synchronized (mNotifications) {
            int i=0;
            pw.println("Notification list:");
            for (Map.Entry<IBinder,StatusBarNotification> e: mNotifications.entrySet()) {
                pw.printf("  %2d: %s\n", i, e.getValue().toString());
                i++;
            }
        }

        synchronized (mLock) {
            final int N = mDisableRecords.size();
            pw.println("  mDisableRecords.size=" + N
                    + " mDisabled=0x" + Integer.toHexString(mDisabled));
            for (int i=0; i<N; i++) {
                DisableRecord tok = mDisableRecords.get(i);
                pw.println("    [" + i + "] what=0x" + Integer.toHexString(tok.what)
                                + " pkg=" + tok.pkg + " token=" + tok.token);
            }
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                collapse();
            }
            /*
            else if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
            }
            */
        }
    };

}
