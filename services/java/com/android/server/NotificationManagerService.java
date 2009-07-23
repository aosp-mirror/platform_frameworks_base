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

import com.android.server.status.IconData;
import com.android.server.status.NotificationData;
import com.android.server.status.StatusBarService;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AsyncPlayer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Power;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

class NotificationManagerService extends INotificationManager.Stub
{
    private static final String TAG = "NotificationService";
    private static final boolean DBG = false;

    // message codes
    private static final int MESSAGE_TIMEOUT = 2;

    private static final int LONG_DELAY = 3500; // 3.5 seconds
    private static final int SHORT_DELAY = 2000; // 2 seconds
    
    private static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250}; 

    private static final int DEFAULT_STREAM_TYPE = AudioManager.STREAM_NOTIFICATION;

    final Context mContext;
    final IActivityManager mAm;
    final IBinder mForegroundToken = new Binder();

    private WorkerHandler mHandler;
    private StatusBarService mStatusBarService;
    private HardwareService mHardware;

    private NotificationRecord mSoundNotification;
    private AsyncPlayer mSound;
    private boolean mSystemReady;
    private int mDisabledNotifications;

    private NotificationRecord mVibrateNotification;
    private Vibrator mVibrator = new Vibrator();

    // adb
    private int mBatteryPlugged;
    private boolean mAdbEnabled = false;
    private boolean mAdbNotificationShown = false;
    private Notification mAdbNotification;
    
    private ArrayList<NotificationRecord> mNotificationList;

    private ArrayList<ToastRecord> mToastQueue;

    private ArrayList<NotificationRecord> mLights = new ArrayList<NotificationRecord>();

    private boolean mBatteryCharging;
    private boolean mBatteryLow;
    private boolean mBatteryFull;
    private NotificationRecord mLedNotification;

    private static final int BATTERY_LOW_ARGB = 0xFFFF0000; // Charging Low - red solid on
    private static final int BATTERY_MEDIUM_ARGB = 0xFFFFFF00;    // Charging - orange solid on
    private static final int BATTERY_FULL_ARGB = 0xFF00FF00; // Charging Full - green solid on
    private static final int BATTERY_BLINK_ON = 125;
    private static final int BATTERY_BLINK_OFF = 2875;

    // Tag IDs for EventLog.
    private static final int EVENT_LOG_ENQUEUE = 2750;
    private static final int EVENT_LOG_CANCEL = 2751;
    private static final int EVENT_LOG_CANCEL_ALL = 2752;

    private static String idDebugString(Context baseContext, String packageName, int id) {
        Context c = null;

        if (packageName != null) {
            try {
                c = baseContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                c = baseContext;
            }
        } else {
            c = baseContext;
        }

        String pkg;
        String type;
        String name;

        Resources r = c.getResources();
        try {
            return r.getResourceName(id);
        } catch (Resources.NotFoundException e) {
            return "<name unknown>";
        }
    }

    private static final class NotificationRecord
    {
        String pkg;
        int id;
        ITransientNotification callback;
        int duration;
        Notification notification;
        IBinder statusBarKey;

        NotificationRecord(String pkg, int id, Notification notification)
        {
            this.pkg = pkg;
            this.id = id;
            this.notification = notification;
        }
        
        void dump(PrintWriter pw, String prefix, Context baseContext) {
            pw.println(prefix + this);
            pw.println(prefix + "  icon=0x" + Integer.toHexString(notification.icon)
                    + " / " + idDebugString(baseContext, this.pkg, notification.icon));
            pw.println(prefix + "  contentIntent=" + notification.contentIntent);
            pw.println(prefix + "  deleteIntent=" + notification.deleteIntent);
            pw.println(prefix + "  tickerText=" + notification.tickerText);
            pw.println(prefix + "  contentView=" + notification.contentView);
            pw.println(prefix + "  defaults=0x" + Integer.toHexString(notification.defaults));
            pw.println(prefix + "  flags=0x" + Integer.toHexString(notification.flags));
            pw.println(prefix + "  sound=" + notification.sound);
            pw.println(prefix + "  vibrate=" + Arrays.toString(notification.vibrate));
            pw.println(prefix + "  ledARGB=0x" + Integer.toHexString(notification.ledARGB)
                    + " ledOnMS=" + notification.ledOnMS
                    + " ledOffMS=" + notification.ledOffMS);
        }
        
        @Override
        public final String toString()
        {
            return "NotificationRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " pkg=" + pkg
                + " id=" + Integer.toHexString(id) + "}";
        }
    }

    private static final class ToastRecord
    {
        final int pid;
        final String pkg;
        final ITransientNotification callback;
        int duration;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration)
        {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
        }

        void update(int duration) {
            this.duration = duration;
        }
        
        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
        }
        
        @Override
        public final String toString()
        {
            return "ToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " pkg=" + pkg
                + " callback=" + callback
                + " duration=" + duration;
        }
    }

    private StatusBarService.NotificationCallbacks mNotificationCallbacks
            = new StatusBarService.NotificationCallbacks() {

        public void onSetDisabled(int status) {
            synchronized (mNotificationList) {
                mDisabledNotifications = status;
                if ((mDisabledNotifications & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
                    // cancel whatever's going on
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mSound.stop();
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    identity = Binder.clearCallingIdentity();
                    try {
                        mVibrator.cancel();
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
        }

        public void onClearAll() {
            cancelAll();
        }

        public void onNotificationClick(String pkg, int id) {
            cancelNotification(pkg, id, Notification.FLAG_AUTO_CANCEL);
        }

        public void onPanelRevealed() {
            synchronized (mNotificationList) {
                // sound
                mSoundNotification = null;
                long identity = Binder.clearCallingIdentity();
                try {
                    mSound.stop();
                }
                finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // vibrate
                mVibrateNotification = null;
                identity = Binder.clearCallingIdentity();
                try {
                    mVibrator.cancel();
                }
                finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // light
                mLights.clear();
                mLedNotification = null;
                updateLightsLocked();
            }
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                boolean batteryCharging = (intent.getIntExtra("plugged", 0) != 0);
                int level = intent.getIntExtra("level", -1);
                boolean batteryLow = (level >= 0 && level <= Power.LOW_BATTERY_THRESHOLD);
                int status = intent.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN);
                boolean batteryFull = (status == BatteryManager.BATTERY_STATUS_FULL || level >= 90);

                if (batteryCharging != mBatteryCharging ||
                        batteryLow != mBatteryLow ||
                        batteryFull != mBatteryFull) {
                    mBatteryCharging = batteryCharging;
                    mBatteryLow = batteryLow;
                    mBatteryFull = batteryFull;
                    updateLights();
                }
                
                mBatteryPlugged = intent.getIntExtra("plugged", 0);
                updateAdbNotification();
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)) {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                String pkgName = uri.getSchemeSpecificPart();
                if (pkgName == null) {
                    return;
                }
                cancelAllNotifications(pkgName);
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        
        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ADB_ENABLED), false, this);
            update();
        }

        @Override public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();
            mAdbEnabled = Settings.Secure.getInt(resolver,
                        Settings.Secure.ADB_ENABLED, 0) != 0;
            updateAdbNotification();
        }
    }
    private final SettingsObserver mSettingsObserver;
    
    NotificationManagerService(Context context, StatusBarService statusBar,
            HardwareService hardware)
    {
        super();
        mContext = context;
        mHardware = hardware;
        mAm = ActivityManagerNative.getDefault();
        mSound = new AsyncPlayer(TAG);
        mSound.setUsesWakeLock(context);
        mToastQueue = new ArrayList<ToastRecord>();
        mNotificationList = new ArrayList<NotificationRecord>();
        mHandler = new WorkerHandler();
        mStatusBarService = statusBar;
        statusBar.setNotificationCallbacks(mNotificationCallbacks);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (0 == Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.DEVICE_PROVISIONED, 0)) {
            mDisabledNotifications = StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
        }

        // register for battery changed notifications
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        mContext.registerReceiver(mIntentReceiver, filter);
        
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
    }

    void systemReady() {
        // no beeping until we're basically done booting
        mSystemReady = true;
    }

    // Toasts
    // ============================================================================
    public void enqueueToast(String pkg, ITransientNotification callback, int duration)
    {
        Log.i(TAG, "enqueueToast pkg=" + pkg + " callback=" + callback + " duration=" + duration);

        if (pkg == null || callback == null) {
            Log.e(TAG, "Not doing toast. pkg=" + pkg + " callback=" + callback);
            return ;
        }

        synchronized (mToastQueue) {
            int callingPid = Binder.getCallingPid();
            long callingId = Binder.clearCallingIdentity();
            try {
                ToastRecord record;
                int index = indexOfToastLocked(pkg, callback);
                // If it's already in the queue, we update it in place, we don't
                // move it to the end of the queue.
                if (index >= 0) {
                    record = mToastQueue.get(index);
                    record.update(duration);
                } else {
                    record = new ToastRecord(callingPid, pkg, callback, duration);
                    mToastQueue.add(record);
                    index = mToastQueue.size() - 1;
                    keepProcessAliveLocked(callingPid);
                }
                // If it's at index 0, it's the current toast.  It doesn't matter if it's
                // new or just been updated.  Call back and tell it to show itself.
                // If the callback fails, this will remove it from the list, so don't
                // assume that it's valid after this.
                if (index == 0) {
                    showNextToastLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }
    }

    public void cancelToast(String pkg, ITransientNotification callback) {
        Log.i(TAG, "cancelToast pkg=" + pkg + " callback=" + callback);

        if (pkg == null || callback == null) {
            Log.e(TAG, "Not cancelling notification. pkg=" + pkg + " callback=" + callback);
            return ;
        }

        synchronized (mToastQueue) {
            long callingId = Binder.clearCallingIdentity();
            try {
                int index = indexOfToastLocked(pkg, callback);
                if (index >= 0) {
                    cancelToastLocked(index);
                } else {
                    Log.w(TAG, "Toast already cancelled. pkg=" + pkg + " callback=" + callback);
                }
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }
    }

    private void showNextToastLocked() {
        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            if (DBG) Log.d(TAG, "Show pkg=" + record.pkg + " callback=" + record.callback);
            try {
                record.callback.show();
                scheduleTimeoutLocked(record, false);
                return;
            } catch (RemoteException e) {
                Log.w(TAG, "Object died trying to show notification " + record.callback
                        + " in package " + record.pkg);
                // remove it from the list and let the process die
                int index = mToastQueue.indexOf(record);
                if (index >= 0) {
                    mToastQueue.remove(index);
                }
                keepProcessAliveLocked(record.pid);
                if (mToastQueue.size() > 0) {
                    record = mToastQueue.get(0);
                } else {
                    record = null;
                }
            }
        }
    }

    private void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Log.w(TAG, "Object died trying to hide notification " + record.callback
                    + " in package " + record.pkg);
            // don't worry about this, we're about to remove it from
            // the list anyway
        }
        mToastQueue.remove(index);
        keepProcessAliveLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked();
        }
    }

    private void scheduleTimeoutLocked(ToastRecord r, boolean immediate)
    {
        Message m = Message.obtain(mHandler, MESSAGE_TIMEOUT, r);
        long delay = immediate ? 0 : (r.duration == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY);
        mHandler.removeCallbacksAndMessages(r);
        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleTimeout(ToastRecord record)
    {
        if (DBG) Log.d(TAG, "Timeout pkg=" + record.pkg + " callback=" + record.callback);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.callback);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    // lock on mToastQueue
    private int indexOfToastLocked(String pkg, ITransientNotification callback)
    {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    // lock on mToastQueue
    private void keepProcessAliveLocked(int pid)
    {
        int toastCount = 0; // toasts from this pid
        ArrayList<ToastRecord> list = mToastQueue;
        int N = list.size();
        for (int i=0; i<N; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            mAm.setProcessForeground(mForegroundToken, pid, toastCount > 0);
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
    }

    private final class WorkerHandler extends Handler
    {
        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_TIMEOUT:
                    handleTimeout((ToastRecord)msg.obj);
                    break;
            }
        }
    }


    // Notifications
    // ============================================================================
    public void enqueueNotification(String pkg, int id, Notification notification, int[] idOut)
    {
        // This conditional is a dirty hack to limit the logging done on
        //     behalf of the download manager without affecting other apps.
        if (!pkg.equals("com.android.providers.downloads")
                || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
            EventLog.writeEvent(EVENT_LOG_ENQUEUE, pkg, id, notification.toString());
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }
        if (notification.icon != 0) {
            if (notification.contentView == null) {
                throw new IllegalArgumentException("contentView required: pkg=" + pkg
                        + " id=" + id + " notification=" + notification);
            }
            if (notification.contentIntent == null) {
                throw new IllegalArgumentException("contentIntent required: pkg=" + pkg
                        + " id=" + id + " notification=" + notification);
            }
        }

        synchronized (mNotificationList) {
            NotificationRecord r = new NotificationRecord(pkg, id, notification);
            NotificationRecord old = null;

            int index = indexOfNotificationLocked(pkg, id);
            if (index < 0) {
                mNotificationList.add(r);
            } else {
                old = mNotificationList.remove(index);
                mNotificationList.add(index, r);
            }
            if (notification.icon != 0) {
                IconData icon = IconData.makeIcon(null, pkg, notification.icon,
                                                    notification.iconLevel,
                                                    notification.number);
                CharSequence truncatedTicker = notification.tickerText;
                
                // TODO: make this restriction do something smarter like never fill
                // more than two screens.  "Why would anyone need more than 80 characters." :-/
                final int maxTickerLen = 80;
                if (truncatedTicker != null && truncatedTicker.length() > maxTickerLen) {
                    truncatedTicker = truncatedTicker.subSequence(0, maxTickerLen);
                }

                NotificationData n = new NotificationData();
                    n.id = id;
                    n.pkg = pkg;
                    n.when = notification.when;
                    n.tickerText = truncatedTicker;
                    n.ongoingEvent = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    if (!n.ongoingEvent && (notification.flags & Notification.FLAG_NO_CLEAR) == 0) {
                        n.clearable = true;
                    }
                    n.contentView = notification.contentView;
                    n.contentIntent = notification.contentIntent;
                    n.deleteIntent = notification.deleteIntent;
                if (old != null && old.statusBarKey != null) {
                    r.statusBarKey = old.statusBarKey;
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mStatusBarService.updateIcon(r.statusBarKey, icon, n);
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                } else {
                    long identity = Binder.clearCallingIdentity();
                    try {
                        r.statusBarKey = mStatusBarService.addIcon(icon, n);
                        mHardware.pulseBreathingLight();
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }

                sendAccessibilityEvent(notification, pkg);

            } else {
                if (old != null && old.statusBarKey != null) {
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mStatusBarService.removeIcon(old.statusBarKey);
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }

            // If we're not supposed to beep, vibrate, etc. then don't.
            if (((mDisabledNotifications & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) == 0)
                    && (!(old != null
                        && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 ))
                    && mSystemReady) {
                // sound
                final boolean useDefaultSound =
                    (notification.defaults & Notification.DEFAULT_SOUND) != 0; 
                if (useDefaultSound || notification.sound != null) {
                    Uri uri;
                    if (useDefaultSound) {
                        uri = Settings.System.DEFAULT_NOTIFICATION_URI;
                    } else {
                        uri = notification.sound;
                    }
                    boolean looping = (notification.flags & Notification.FLAG_INSISTENT) != 0;
                    int audioStreamType;
                    if (notification.audioStreamType >= 0) {
                        audioStreamType = notification.audioStreamType;
                    } else {
                        audioStreamType = DEFAULT_STREAM_TYPE;
                    }
                    mSoundNotification = r;
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mSound.play(mContext, uri, looping, audioStreamType);
                    }
                    finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }

                // vibrate
                final AudioManager audioManager = (AudioManager) mContext
                        .getSystemService(Context.AUDIO_SERVICE);
                final boolean useDefaultVibrate =
                    (notification.defaults & Notification.DEFAULT_VIBRATE) != 0; 
                if ((useDefaultVibrate || notification.vibrate != null)
                        && audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_NOTIFICATION)) {
                    mVibrateNotification = r;

                    mVibrator.vibrate(useDefaultVibrate ? DEFAULT_VIBRATE_PATTERN 
                                                        : notification.vibrate,
                              ((notification.flags & Notification.FLAG_INSISTENT) != 0) ? 0: -1);
                }
            }

            // this option doesn't shut off the lights

            // light
            // the most recent thing gets the light
            mLights.remove(old);
            if (mLedNotification == old) {
                mLedNotification = null;
            }
            //Log.i(TAG, "notification.lights="
            //        + ((old.notification.lights.flags & Notification.FLAG_SHOW_LIGHTS) != 0));
            if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0) {
                mLights.add(r);
                updateLightsLocked();
            } else {
                if (old != null
                        && ((old.notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0)) {
                    updateLightsLocked();
                }
            }
        }

        idOut[0] = id;
    }

    private void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
        AccessibilityManager manager = AccessibilityManager.getInstance(mContext);
        if (!manager.isEnabled()) {
            return;
        }

        AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        event.setParcelableData(notification);
        CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        manager.sendAccessibilityEvent(event);
    }

    private void cancelNotificationLocked(NotificationRecord r) {
        // status bar
        if (r.notification.icon != 0) {
            long identity = Binder.clearCallingIdentity();
            try {
                mStatusBarService.removeIcon(r.statusBarKey);
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
            r.statusBarKey = null;
        }

        // sound
        if (mSoundNotification == r) {
            mSoundNotification = null;
            long identity = Binder.clearCallingIdentity();
            try {
                mSound.stop();
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // vibrate
        if (mVibrateNotification == r) {
            mVibrateNotification = null;
            long identity = Binder.clearCallingIdentity();
            try {
                mVibrator.cancel();
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // light
        mLights.remove(r);
        if (mLedNotification == r) {
            mLedNotification = null;
        }
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}. 
     */
    private void cancelNotification(String pkg, int id, int mustHaveFlags) {
        EventLog.writeEvent(EVENT_LOG_CANCEL, pkg, id, mustHaveFlags);

        synchronized (mNotificationList) {
            NotificationRecord r = null;

            int index = indexOfNotificationLocked(pkg, id);
            if (index >= 0) {
                r = mNotificationList.get(index);
                
                if ((r.notification.flags & mustHaveFlags) != mustHaveFlags) {
                    return;
                }
                
                mNotificationList.remove(index);

                cancelNotificationLocked(r);
                updateLightsLocked();
            }
        }
    }

    /**
     * Cancels all notifications from a given package that have all of the
     * {@code mustHaveFlags}.
     */
    private void cancelAllNotificationsInt(String pkg, int mustHaveFlags) {
        EventLog.writeEvent(EVENT_LOG_CANCEL_ALL, pkg, mustHaveFlags);

        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            boolean canceledSomething = false;
            for (int i = N-1; i >= 0; --i) {
                NotificationRecord r = mNotificationList.get(i);
                if ((r.notification.flags & mustHaveFlags) != mustHaveFlags) {
                    continue;
                }
                if (!r.pkg.equals(pkg)) {
                    continue;
                }
                mNotificationList.remove(i);
                cancelNotificationLocked(r);
                canceledSomething = true;
            }
            if (canceledSomething) {
                updateLightsLocked();
            }
        }
    }

    
    public void cancelNotification(String pkg, int id)
    {
        cancelNotification(pkg, id, 0);
    }

    public void cancelAllNotifications(String pkg)
    {
        cancelAllNotificationsInt(pkg, 0);
    }

    public void cancelAll() {
        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            for (int i=N-1; i>=0; i--) {
                NotificationRecord r = mNotificationList.get(i);

                if ((r.notification.flags & (Notification.FLAG_ONGOING_EVENT
                                | Notification.FLAG_NO_CLEAR)) == 0) {
                    if (r.notification.deleteIntent != null) {
                        try {
                            r.notification.deleteIntent.send();
                        } catch (PendingIntent.CanceledException ex) {
                            // do nothing - there's no relevant way to recover, and
                            //     no reason to let this propagate
                            Log.w(TAG, "canceled PendingIntent for " + r.pkg, ex);
                        }
                    }
                    mNotificationList.remove(i);
                    cancelNotificationLocked(r);
                }
            }

            updateLightsLocked();
        }
    }

    private void updateLights() {
        synchronized (mNotificationList) {
            updateLightsLocked();
        }
    }

    // lock on mNotificationList
    private void updateLightsLocked()
    {
        // Battery low always shows, other states only show if charging.
        if (mBatteryLow) {
            mHardware.setLightFlashing_UNCHECKED(HardwareService.LIGHT_ID_BATTERY, BATTERY_LOW_ARGB,
                    HardwareService.LIGHT_FLASH_TIMED, BATTERY_BLINK_ON, BATTERY_BLINK_OFF);
        } else if (mBatteryCharging) {
            if (mBatteryFull) {
                mHardware.setLightColor_UNCHECKED(HardwareService.LIGHT_ID_BATTERY,
                        BATTERY_FULL_ARGB);
            } else {
                mHardware.setLightColor_UNCHECKED(HardwareService.LIGHT_ID_BATTERY,
                        BATTERY_MEDIUM_ARGB);
            }
        } else {
            mHardware.setLightOff_UNCHECKED(HardwareService.LIGHT_ID_BATTERY);
        }

        // handle notification lights
        if (mLedNotification == null) {
            // get next notification, if any
            int n = mLights.size();
            if (n > 0) {
                mLedNotification = mLights.get(n-1);
            }
        }
        if (mLedNotification == null) {
            mHardware.setLightOff_UNCHECKED(HardwareService.LIGHT_ID_NOTIFICATIONS);
        } else {
            mHardware.setLightFlashing_UNCHECKED(
                    HardwareService.LIGHT_ID_NOTIFICATIONS,
                    mLedNotification.notification.ledARGB,
                    HardwareService.LIGHT_FLASH_TIMED,
                    mLedNotification.notification.ledOnMS,
                    mLedNotification.notification.ledOffMS);
        }
    }

    // lock on mNotificationList
    private int indexOfNotificationLocked(String pkg, int id)
    {
        ArrayList<NotificationRecord> list = mNotificationList;
        final int len = list.size();
        for (int i=0; i<len; i++) {
            NotificationRecord r = list.get(i);
            if (r.id == id && r.pkg.equals(pkg)) {
                return i;
            }
        }
        return -1;
    }

    // This is here instead of StatusBarPolicy because it is an important
    // security feature that we don't want people customizing the platform
    // to accidentally lose.
    private void updateAdbNotification() {
        if (mAdbEnabled && mBatteryPlugged == BatteryManager.BATTERY_PLUGGED_USB) {
            if ("0".equals(SystemProperties.get("persist.adb.notify"))) {
                return;
            }
            if (!mAdbNotificationShown) {
                NotificationManager notificationManager = (NotificationManager) mContext
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    Resources r = mContext.getResources();
                    CharSequence title = r.getText(
                            com.android.internal.R.string.adb_active_notification_title);
                    CharSequence message = r.getText(
                            com.android.internal.R.string.adb_active_notification_message);

                    if (mAdbNotification == null) {
                        mAdbNotification = new Notification();
                        mAdbNotification.icon = com.android.internal.R.drawable.stat_sys_warning;
                        mAdbNotification.when = 0;
                        mAdbNotification.flags = Notification.FLAG_ONGOING_EVENT;
                        mAdbNotification.tickerText = title;
                        mAdbNotification.defaults |= Notification.DEFAULT_SOUND;
                    }

                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    // Note: we are hard-coding the component because this is
                    // an important security UI that we don't want anyone
                    // intercepting.
                    intent.setComponent(new ComponentName("com.android.settings",
                            "com.android.settings.DevelopmentSettings"));
                    PendingIntent pi = PendingIntent.getActivity(mContext, 0,
                            intent, 0);

                    mAdbNotification.setLatestEventInfo(mContext, title, message, pi);
                    
                    mAdbNotificationShown = true;
                    notificationManager.notify(
                            com.android.internal.R.string.adb_active_notification_title,
                            mAdbNotification);
                }
            }
            
        } else if (mAdbNotificationShown) {
            NotificationManager notificationManager = (NotificationManager) mContext
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                mAdbNotificationShown = false;
                notificationManager.cancel(
                        com.android.internal.R.string.adb_active_notification_title);
            }
        }
    }
    
    // ======================================================================
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump NotificationManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        
        pw.println("Current Notification Manager state:");

        int N;

        synchronized (mToastQueue) {
            N = mToastQueue.size();
            if (N > 0) {
                pw.println("  Toast Queue:");
                for (int i=0; i<N; i++) {
                    mToastQueue.get(i).dump(pw, "    ");
                }
                pw.println("  ");
            }
            
        }

        synchronized (mNotificationList) {
            N = mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i=0; i<N; i++) {
                    mNotificationList.get(i).dump(pw, "    ", mContext);
                }
                pw.println("  ");
            }
            
            N = mLights.size();
            if (N > 0) {
                pw.println("  Lights List:");
                for (int i=0; i<N; i++) {
                    mLights.get(i).dump(pw, "    ", mContext);
                }
                pw.println("  ");
            }
            
            pw.println("  mSoundNotification=" + mSoundNotification);
            pw.println("  mSound=" + mSound);
            pw.println("  mVibrateNotification=" + mVibrateNotification);
            pw.println("  mDisabledNotifications=0x" + Integer.toHexString(mDisabledNotifications));
            pw.println("  mSystemReady=" + mSystemReady);
        }
    }
}
