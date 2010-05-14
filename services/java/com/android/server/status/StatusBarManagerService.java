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

package com.android.server.status;

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;


/**
 * The public (ok, semi-public) service for the status bar.
 * <p>
 * This interesting thing to note about this class is that most of the methods that
 * are called from other classes just post a message, and everything else is batched
 * and coalesced into a series of calls to methods that all start with "perform."
 * There are two reasons for this.  The first is that some of the methods (activate/deactivate)
 * are on IStatusBarService, so they're called from the thread pool and they need to make their
 * way onto the UI thread.  The second is that the message queue is stopped while animations
 * are happening in order to make for smoother transitions.
 * <p>
 * Each icon is either an icon or an icon and a notification.  They're treated mostly
 * separately throughout the code, although they both use the same key, which is assigned
 * when they are created.
 */
public class StatusBarManagerService extends IStatusBarService.Stub
{
    static final String TAG = "StatusBar";
    static final boolean SPEW = false;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    private static final int MSG_ANIMATE = 1000;
    private static final int MSG_ANIMATE_REVEAL = 1001;

    private static final int OP_ADD_ICON = 1;
    private static final int OP_UPDATE_ICON = 2;
    private static final int OP_REMOVE_ICON = 3;
    private static final int OP_SET_VISIBLE = 4;
    private static final int OP_EXPAND = 5;
    private static final int OP_TOGGLE = 6;
    private static final int OP_DISABLE = 7;
    private class PendingOp {
        IBinder key;
        int code;
        IconData iconData;
        NotificationData notificationData;
        boolean visible;
        int integer;
    }

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
    }

    final Context mContext;
    Object mQueueLock = new Object();
    ArrayList<PendingOp> mQueue = new ArrayList<PendingOp>();
    NotificationCallbacks mNotificationCallbacks;
    IStatusBar mBar;
    
    // icons
    StatusBarIconList mIcons = new StatusBarIconList();
    private UninstallReceiver mUninstallReceiver;

    // expanded notifications
    NotificationViewList mNotificationData = new NotificationViewList();

    // for disabling the status bar
    ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    int mDisabled = 0;

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarManagerService(Context context) {
        mContext = context;
        mUninstallReceiver = new UninstallReceiver();

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
        // Start the status bar app
        Intent intent = new Intent(ACTION_STATUSBAR_START);
        mContext.sendBroadcast(intent /** permission  **/);
    }
    
    // ================================================================================
    // From IStatusBarService
    // ================================================================================
    public void activate() {
        enforceExpandStatusBar();
    }

    public void deactivate() {
        enforceExpandStatusBar();
    }

    public void toggle() {
        enforceExpandStatusBar();
    }

    public void disable(int what, IBinder token, String pkg) {
        enforceStatusBar();
        synchronized (mNotificationCallbacks) {
            // This is a little gross, but I think it's safe as long as nobody else
            // synchronizes on mNotificationCallbacks.  It's important that the the callback
            // and the pending op get done in the correct order and not interleaved with
            // other calls, otherwise they'll get out of sync.
            int net;
            synchronized (mDisableRecords) {
                manageDisableListLocked(what, token, pkg);
                net = gatherDisableActionsLocked();
                mNotificationCallbacks.onSetDisabled(net);
            }
            addPendingOp(OP_DISABLE, net);
        }
    }

    public void setIcon(String slot, CharSequence text) {
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

            // Tell the client.  If it fails, it'll restart soon and we'll sync up.
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

                // Tell the client.  If it fails, it'll restart soon and we'll sync up.
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

            // Tell the client.  If it fails, it'll restart soon and we'll sync up.
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

    public void registerStatusBar(IStatusBar bar, StatusBarIconList iconList) {
        Slog.i(TAG, "registerStatusBar bar=" + bar);
        mBar = bar;
        iconList.copyFrom(mIcons);
    }
    
    public IBinder addNotification(IconData iconData, NotificationData notificationData) {
        return new Binder();
    }

    public void updateNotification(IBinder key, IconData iconData,
            NotificationData notificationData) {
    }

    public void removeNotification(IBinder key) {
    }

    // ================================================================================
    // Can be called from any thread
    // ================================================================================

    private void addPendingOp(int code, IBinder key, IconData data, NotificationData n, int i) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.iconData = data == null ? null : data.clone();
            op.notificationData = n;
            op.integer = i;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                //mHandler.sendEmptyMessage(2);
            }
        }
    }

    private void addPendingOp(int code, IBinder key, boolean visible) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.visible = visible;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                //mHandler.sendEmptyMessage(1);
            }
        }
    }

    private void addPendingOp(int code, int integer) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.code = code;
            op.integer = integer;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                //mHandler.sendEmptyMessage(1);
            }
        }
    }

    // lock on mDisableRecords
    void manageDisableListLocked(int what, IBinder token, String pkg) {
        if (SPEW) {
            Slog.d(TAG, "manageDisableList what=0x" + Integer.toHexString(what)
                    + " pkg=" + pkg);
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

    StatusBarNotification getNotification(IBinder key) {
        synchronized (mNotificationData) {
            return mNotificationData.get(key);
        }
    }

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
        
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            final int N = mQueue.size();
            pw.println("  mQueue.size=" + N);
            for (int i=0; i<N; i++) {
                PendingOp op = mQueue.get(i);
                pw.println("    [" + i + "] key=" + op.key + " code=" + op.code + " visible="
                        + op.visible);
                pw.println("           iconData=" + op.iconData);
                pw.println("           notificationData=" + op.notificationData);
            }
        }
        synchronized (mNotificationData) {
            int N = mNotificationData.ongoingCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getOngoing(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
            N = mNotificationData.latestCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getLatest(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
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

    void performDisableActions(int net) {
        /*
        int old = mDisabled;
        int diff = net ^ old;
        mDisabled = net;

        // act accordingly
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((net & StatusBarManager.DISABLE_EXPAND) != 0) {
                Slog.d(TAG, "DISABLE_EXPAND: yes");
                //animateCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((net & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    //mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                //mTicker.halt();
            }
        }
        */
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                deactivate();
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


    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addDataScheme("package");
            mContext.registerReceiver(this, filter);
            IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            mContext.registerReceiver(this, sdFilter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkgList[] = null;
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())) {
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            } else {
                Uri data = intent.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    if (pkg != null) {
                        pkgList = new String[]{pkg};
                    }
                }
            }
            ArrayList<StatusBarNotification> list = null;
            if (pkgList != null) {
                synchronized (StatusBarManagerService.this) {
                    for (String pkg : pkgList) {
                        list = mNotificationData.notificationsForPackage(pkg);
                    }
                }
            }
            
            if (list != null) {
                final int N = list.size();
                for (int i=0; i<N; i++) {
                    // TODO: removeIcon(list.get(i).key);
                }
            }
        }
    }
}
