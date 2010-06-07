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

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

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
{
    static final String TAG = "StatusBarManagerService";
    static final boolean SPEW = true;

    final Context mContext;
    Handler mHandler = new Handler();
    NotificationCallbacks mNotificationCallbacks;
    volatile IStatusBar mBar;
    StatusBarIconList mIcons = new StatusBarIconList();
    HashMap<IBinder,StatusBarNotification> mNotifications
            = new HashMap<IBinder,StatusBarNotification>();

    // for disabling the status bar
    ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    int mDisabled = 0;

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
        void onPanelRevealed();
        void onNotificationError(String pkg, String tag, int id, String message);
    }

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarManagerService(Context context) {
        mContext = context;

        final Resources res = context.getResources();
        mIcons.defineSlots(res.getStringArray(com.android.internal.R.array.status_bar_icon_order));
    }

    public void setNotificationCallbacks(NotificationCallbacks listener) {
        mNotificationCallbacks = listener;
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================

    public void systemReady() {
    }

    public void systemReady2() {
        ComponentName cn = ComponentName.unflattenFromString(
                mContext.getString(com.android.internal.R.string.config_statusBarComponent));
        Intent intent = new Intent();
        intent.setComponent(cn);
        Slog.i(TAG, "Starting service: " + cn);
        mContext.startService(intent);
    }

    // ================================================================================
    // From IStatusBarService
    // ================================================================================
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

        // It's important that the the callback and the call to mBar get done
        // in the same order when multiple threads are calling this function
        // so they are paired correctly.  The messages on the handler will be
        // handled in the order they were enqueued, but will be outside the lock.
        synchronized (mDisableRecords) {
            manageDisableListLocked(what, token, pkg);
            final int net = gatherDisableActionsLocked();
            Slog.d(TAG, "disable... net=0x" + Integer.toHexString(net));
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
    }

    public void setIcon(String slot, String iconPackage, int iconId, int iconLevel) {
        enforceStatusBar();

        synchronized (mIcons) {
            int index = mIcons.getSlotIndex(slot);
            if (index < 0) {
                throw new SecurityException("invalid status bar icon slot: " + slot);
            }

            StatusBarIcon icon = new StatusBarIcon(iconPackage, iconId, iconLevel);
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
            List<IBinder> notificationKeys, List<StatusBarNotification> notifications) {
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
    }

    /**
     * The status bar service should call this when the user changes whether
     * the status bar is visible or not.
     */
    public void visibilityChanged(boolean visible) {
        enforceStatusBarService();

        //Slog.d(TAG, "visibilityChanged visible=" + visible);
    }

    public void onNotificationClick(String pkg, String tag, int id) {
        enforceStatusBarService();

        mNotificationCallbacks.onNotificationClick(pkg, tag, id);
    }

    public void onNotificationError(String pkg, String tag, int id, String message) {
        enforceStatusBarService();

        // WARNING: this will call back into us to do the remove.  Don't hold any locks.
        mNotificationCallbacks.onNotificationError(pkg, tag, id, message);
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
        synchronized (mDisableRecords) {
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

        synchronized (mDisableRecords) {
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

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    private boolean mPanelSlightlyVisible;
    void panelSlightlyVisible(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            if (visible) {
                // tell the notification manager to turn off the lights.
                mNotificationCallbacks.onPanelRevealed();
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
