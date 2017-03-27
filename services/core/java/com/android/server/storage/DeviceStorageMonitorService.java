/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.server.storage;

import android.app.NotificationChannel;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.EventLogTags;
import com.android.server.SystemService;
import com.android.server.pm.InstructionSets;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.EventLog;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.VMRuntime;

/**
 * This class implements a service to monitor the amount of disk
 * storage space on the device.  If the free storage on device is less
 * than a tunable threshold value (a secure settings parameter;
 * default 10%) a low memory notification is displayed to alert the
 * user. If the user clicks on the low memory notification the
 * Application Manager application gets launched to let the user free
 * storage space.
 *
 * Event log events: A low memory event with the free storage on
 * device in bytes is logged to the event log when the device goes low
 * on storage space.  The amount of free storage on the device is
 * periodically logged to the event log. The log interval is a secure
 * settings parameter with a default value of 12 hours.  When the free
 * storage differential goes below a threshold (again a secure
 * settings parameter with a default value of 2MB), the free memory is
 * logged to the event log.
 */
public class DeviceStorageMonitorService extends SystemService {
    static final String TAG = "DeviceStorageMonitorService";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Current int sequence number of the update.
     */
    public static final String EXTRA_SEQUENCE = "seq";

    // TODO: extend to watch and manage caches on all private volumes

    static final boolean DEBUG = false;
    static final boolean localLOGV = false;

    static final int DEVICE_MEMORY_WHAT = 1;
    static final int FORCE_MEMORY_WHAT = 2;
    private static final int MONITOR_INTERVAL = 1; //in minutes

    private static final int DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES = 12*60; //in minutes
    private static final long DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD = 2 * 1024 * 1024; // 2MB
    private static final long DEFAULT_CHECK_INTERVAL = MONITOR_INTERVAL*60*1000;

    // com.android.internal.R.string.low_internal_storage_view_text_no_boot
    // hard codes 250MB in the message as the storage space required for the
    // boot image.
    private static final long BOOT_IMAGE_STORAGE_REQUIREMENT = 250 * 1024 * 1024;

    private long mFreeMem;  // on /data
    private long mFreeMemAfterLastCacheClear;  // on /data
    private long mLastReportedFreeMem;
    private long mLastReportedFreeMemTime;
    boolean mLowMemFlag=false;
    private boolean mMemFullFlag=false;
    private final boolean mIsBootImageOnDisk;
    private final ContentResolver mResolver;
    private final long mTotalMemory;  // on /data
    private final StatFs mDataFileStats;
    private final StatFs mSystemFileStats;
    private final StatFs mCacheFileStats;

    private static final File DATA_PATH = Environment.getDataDirectory();
    private static final File SYSTEM_PATH = Environment.getRootDirectory();
    private static final File CACHE_PATH = Environment.getDownloadCacheDirectory();

    private long mThreadStartTime = -1;
    boolean mUpdatesStopped;
    AtomicInteger mSeq = new AtomicInteger(1);
    boolean mClearSucceeded = false;
    boolean mClearingCache;
    private final Intent mStorageLowIntent;
    private final Intent mStorageOkIntent;
    private final Intent mStorageFullIntent;
    private final Intent mStorageNotFullIntent;
    private CachePackageDataObserver mClearCacheObserver;
    private CacheFileDeletedObserver mCacheFileDeletedObserver;
    private static final int _TRUE = 1;
    private static final int _FALSE = 0;
    // This is the raw threshold that has been set at which we consider
    // storage to be low.
    long mMemLowThreshold;
    // This is the threshold at which we start trying to flush caches
    // to get below the low threshold limit.  It is less than the low
    // threshold; we will allow storage to get a bit beyond the limit
    // before flushing and checking if we are actually low.
    private long mMemCacheStartTrimThreshold;
    // This is the threshold that we try to get to when deleting cache
    // files.  This is greater than the low threshold so that we will flush
    // more files than absolutely needed, to reduce the frequency that
    // flushing takes place.
    private long mMemCacheTrimToThreshold;
    private long mMemFullThreshold;

    /**
     * This string is used for ServiceManager access to this class.
     */
    static final String SERVICE = "devicestoragemonitor";

    private static final String TV_NOTIFICATION_CHANNEL_ID = "devicestoragemonitor.tv";

    /**
    * Handler that checks the amount of disk space on the device and sends a
    * notification if the device runs low on disk space
    */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            //don't handle an invalid message
            switch (msg.what) {
                case DEVICE_MEMORY_WHAT:
                    checkMemory(msg.arg1 == _TRUE);
                    return;
                case FORCE_MEMORY_WHAT:
                    forceMemory(msg.arg1, msg.arg2);
                    return;
                default:
                    Slog.w(TAG, "Will not process invalid message");
                    return;
            }
        }
    };

    private class CachePackageDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(String packageName, boolean succeeded) {
            mClearSucceeded = succeeded;
            mClearingCache = false;
            if(localLOGV) Slog.i(TAG, " Clear succeeded:"+mClearSucceeded
                    +", mClearingCache:"+mClearingCache+" Forcing memory check");
            postCheckMemoryMsg(false, 0);
        }
    }

    private void restatDataDir() {
        try {
            mDataFileStats.restat(DATA_PATH.getAbsolutePath());
            mFreeMem = (long) mDataFileStats.getAvailableBlocks() *
                mDataFileStats.getBlockSize();
        } catch (IllegalArgumentException e) {
            // use the old value of mFreeMem
        }
        // Allow freemem to be overridden by debug.freemem for testing
        String debugFreeMem = SystemProperties.get("debug.freemem");
        if (!"".equals(debugFreeMem)) {
            mFreeMem = Long.parseLong(debugFreeMem);
        }
        // Read the log interval from secure settings
        long freeMemLogInterval = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES)*60*1000;
        //log the amount of free memory in event log
        long currTime = SystemClock.elapsedRealtime();
        if((mLastReportedFreeMemTime == 0) ||
           (currTime-mLastReportedFreeMemTime) >= freeMemLogInterval) {
            mLastReportedFreeMemTime = currTime;
            long mFreeSystem = -1, mFreeCache = -1;
            try {
                mSystemFileStats.restat(SYSTEM_PATH.getAbsolutePath());
                mFreeSystem = (long) mSystemFileStats.getAvailableBlocks() *
                    mSystemFileStats.getBlockSize();
            } catch (IllegalArgumentException e) {
                // ignore; report -1
            }
            try {
                mCacheFileStats.restat(CACHE_PATH.getAbsolutePath());
                mFreeCache = (long) mCacheFileStats.getAvailableBlocks() *
                    mCacheFileStats.getBlockSize();
            } catch (IllegalArgumentException e) {
                // ignore; report -1
            }
            EventLog.writeEvent(EventLogTags.FREE_STORAGE_LEFT,
                                mFreeMem, mFreeSystem, mFreeCache);
        }
        // Read the reporting threshold from secure settings
        long threshold = Settings.Global.getLong(mResolver,
                Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD);
        // If mFree changed significantly log the new value
        long delta = mFreeMem - mLastReportedFreeMem;
        if (delta > threshold || delta < -threshold) {
            mLastReportedFreeMem = mFreeMem;
            EventLog.writeEvent(EventLogTags.FREE_STORAGE_CHANGED, mFreeMem);
        }
    }

    private void clearCache() {
        if (mClearCacheObserver == null) {
            // Lazy instantiation
            mClearCacheObserver = new CachePackageDataObserver();
        }
        mClearingCache = true;
        try {
            if (localLOGV) Slog.i(TAG, "Clearing cache");
            IPackageManager.Stub.asInterface(ServiceManager.getService("package")).
                    freeStorageAndNotify(null, mMemCacheTrimToThreshold, mClearCacheObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to get handle for PackageManger Exception: "+e);
            mClearingCache = false;
            mClearSucceeded = false;
        }
    }

    void forceMemory(int opts, int seq) {
        if ((opts&OPTION_UPDATES_STOPPED) == 0) {
            if (mUpdatesStopped) {
                mUpdatesStopped = false;
                checkMemory(true);
            }
        } else {
            mUpdatesStopped = true;
            final boolean forceLow = (opts&OPTION_STORAGE_LOW) != 0;
            if (mLowMemFlag != forceLow || (opts&OPTION_FORCE_UPDATE) != 0) {
                mLowMemFlag = forceLow;
                if (forceLow) {
                    sendNotification(seq);
                } else {
                    cancelNotification(seq);
                }
            }
        }
    }

    void checkMemory(boolean checkCache) {
        if (mUpdatesStopped) {
            return;
        }

        //if the thread that was started to clear cache is still running do nothing till its
        //finished clearing cache. Ideally this flag could be modified by clearCache
        // and should be accessed via a lock but even if it does this test will fail now and
        //hopefully the next time this flag will be set to the correct value.
        if (mClearingCache) {
            if(localLOGV) Slog.i(TAG, "Thread already running just skip");
            //make sure the thread is not hung for too long
            long diffTime = System.currentTimeMillis() - mThreadStartTime;
            if(diffTime > (10*60*1000)) {
                Slog.w(TAG, "Thread that clears cache file seems to run for ever");
            }
        } else {
            restatDataDir();
            if (localLOGV)  Slog.v(TAG, "freeMemory="+mFreeMem);

            //post intent to NotificationManager to display icon if necessary
            if (mFreeMem < mMemLowThreshold) {
                if (checkCache) {
                    // We are allowed to clear cache files at this point to
                    // try to get down below the limit, because this is not
                    // the initial call after a cache clear has been attempted.
                    // In this case we will try a cache clear if our free
                    // space has gone below the cache clear limit.
                    if (mFreeMem < mMemCacheStartTrimThreshold) {
                        // We only clear the cache if the free storage has changed
                        // a significant amount since the last time.
                        if ((mFreeMemAfterLastCacheClear-mFreeMem)
                                >= ((mMemLowThreshold-mMemCacheStartTrimThreshold)/4)) {
                            // See if clearing cache helps
                            // Note that clearing cache is asynchronous and so we do a
                            // memory check again once the cache has been cleared.
                            mThreadStartTime = System.currentTimeMillis();
                            mClearSucceeded = false;
                            clearCache();
                        }
                    }
                } else {
                    // This is a call from after clearing the cache.  Note
                    // the amount of free storage at this point.
                    mFreeMemAfterLastCacheClear = mFreeMem;
                    if (!mLowMemFlag) {
                        // We tried to clear the cache, but that didn't get us
                        // below the low storage limit.  Tell the user.
                        Slog.i(TAG, "Running low on memory. Sending notification");
                        sendNotification(0);
                        mLowMemFlag = true;
                    } else {
                        if (localLOGV) Slog.v(TAG, "Running low on memory " +
                                "notification already sent. do nothing");
                    }
                }
            } else {
                mFreeMemAfterLastCacheClear = mFreeMem;
                if (mLowMemFlag) {
                    Slog.i(TAG, "Memory available. Cancelling notification");
                    cancelNotification(0);
                    mLowMemFlag = false;
                }
            }
            if (!mLowMemFlag && !mIsBootImageOnDisk && mFreeMem < BOOT_IMAGE_STORAGE_REQUIREMENT) {
                Slog.i(TAG, "No boot image on disk due to lack of space. Sending notification");
                sendNotification(0);
                mLowMemFlag = true;
            }
            if (mFreeMem < mMemFullThreshold) {
                if (!mMemFullFlag) {
                    sendFullNotification();
                    mMemFullFlag = true;
                }
            } else {
                if (mMemFullFlag) {
                    cancelFullNotification();
                    mMemFullFlag = false;
                }
            }
        }
        if(localLOGV) Slog.i(TAG, "Posting Message again");
        //keep posting messages to itself periodically
        postCheckMemoryMsg(true, DEFAULT_CHECK_INTERVAL);
    }

    void postCheckMemoryMsg(boolean clearCache, long delay) {
        // Remove queued messages
        mHandler.removeMessages(DEVICE_MEMORY_WHAT);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEVICE_MEMORY_WHAT,
                clearCache ?_TRUE : _FALSE, 0),
                delay);
    }

    public DeviceStorageMonitorService(Context context) {
        super(context);
        mLastReportedFreeMemTime = 0;
        mResolver = context.getContentResolver();
        mIsBootImageOnDisk = isBootImageOnDisk();
        //create StatFs object
        mDataFileStats = new StatFs(DATA_PATH.getAbsolutePath());
        mSystemFileStats = new StatFs(SYSTEM_PATH.getAbsolutePath());
        mCacheFileStats = new StatFs(CACHE_PATH.getAbsolutePath());
        //initialize total storage on device
        mTotalMemory = (long)mDataFileStats.getBlockCount() *
                        mDataFileStats.getBlockSize();
        mStorageLowIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_LOW);
        mStorageLowIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mStorageOkIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_OK);
        mStorageOkIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mStorageFullIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_FULL);
        mStorageFullIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mStorageNotFullIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_NOT_FULL);
        mStorageNotFullIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
    }

    private static boolean isBootImageOnDisk() {
        for (String instructionSet : InstructionSets.getAllDexCodeInstructionSets()) {
            if (!VMRuntime.isBootClassPathOnDisk(instructionSet)) {
                return false;
            }
        }
        return true;
    }

    /**
    * Initializes the disk space threshold value and posts an empty message to
    * kickstart the process.
    */
    @Override
    public void onStart() {
        // cache storage thresholds
        Context context = getContext();
        final StorageManager sm = StorageManager.from(context);
        mMemLowThreshold = sm.getStorageLowBytes(DATA_PATH);
        mMemFullThreshold = sm.getStorageFullBytes(DATA_PATH);

        mMemCacheStartTrimThreshold = ((mMemLowThreshold*3)+mMemFullThreshold)/4;
        mMemCacheTrimToThreshold = mMemLowThreshold
                + ((mMemLowThreshold-mMemCacheStartTrimThreshold)*2);
        mFreeMemAfterLastCacheClear = mTotalMemory;
        checkMemory(true);

        mCacheFileDeletedObserver = new CacheFileDeletedObserver();
        mCacheFileDeletedObserver.startWatching();

        // Ensure that the notification channel is set up
        NotificationManager notificationMgr =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        PackageManager packageManager = context.getPackageManager();
        boolean isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        if (isTv) {
            notificationMgr.createNotificationChannel(new NotificationChannel(
                    TV_NOTIFICATION_CHANNEL_ID,
                    context.getString(
                        com.android.internal.R.string.device_storage_monitor_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }

        publishBinderService(SERVICE, mRemoteService);
        publishLocalService(DeviceStorageMonitorInternal.class, mLocalService);
    }

    private final DeviceStorageMonitorInternal mLocalService = new DeviceStorageMonitorInternal() {
        @Override
        public void checkMemory() {
            // force an early check
            postCheckMemoryMsg(true, 0);
        }

        @Override
        public boolean isMemoryLow() {
            return mLowMemFlag;
        }

        @Override
        public long getMemoryLowThreshold() {
            return mMemLowThreshold;
        }
    };

    private final Binder mRemoteService = new Binder() {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {

                pw.println("Permission Denial: can't dump " + SERVICE + " from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(fd, pw, args);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpHelp(pw);
        }
    }

    static final int OPTION_FORCE_UPDATE = 1<<0;
    static final int OPTION_UPDATES_STOPPED = 1<<1;
    static final int OPTION_STORAGE_LOW = 1<<2;

    int parseOptions(Shell shell) {
        String opt;
        int opts = 0;
        while ((opt = shell.getNextOption()) != null) {
            if ("-f".equals(opt)) {
                opts |= OPTION_FORCE_UPDATE;
            }
        }
        return opts;
    }

    int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        switch (cmd) {
            case "force-low": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                int seq = mSeq.incrementAndGet();
                mHandler.sendMessage(mHandler.obtainMessage(FORCE_MEMORY_WHAT,
                        opts | OPTION_UPDATES_STOPPED | OPTION_STORAGE_LOW, seq));
                if ((opts & OPTION_FORCE_UPDATE) != 0) {
                    pw.println(seq);
                }
            } break;
            case "force-not-low": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                int seq = mSeq.incrementAndGet();
                mHandler.sendMessage(mHandler.obtainMessage(FORCE_MEMORY_WHAT,
                        opts | OPTION_UPDATES_STOPPED, seq));
                if ((opts & OPTION_FORCE_UPDATE) != 0) {
                    pw.println(seq);
                }
            } break;
            case "reset": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                int seq = mSeq.incrementAndGet();
                mHandler.sendMessage(mHandler.obtainMessage(FORCE_MEMORY_WHAT,
                        opts, seq));
                if ((opts & OPTION_FORCE_UPDATE) != 0) {
                    pw.println(seq);
                }
            } break;
            default:
                return shell.handleDefaultCommands(cmd);
        }
        return 0;
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Device storage monitor service (devicestoragemonitor) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  force-low [-f]");
        pw.println("    Force storage to be low, freezing storage state.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
        pw.println("  force-not-low [-f]");
        pw.println("    Force storage to not be low, freezing storage state.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
        pw.println("  reset [-f]");
        pw.println("    Unfreeze storage state, returning to current real values.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
    }

    void dumpImpl(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args == null || args.length == 0 || "-a".equals(args[0])) {
            final Context context = getContext();

            pw.println("Current DeviceStorageMonitor state:");

            pw.print("  mFreeMem=");
            pw.print(Formatter.formatFileSize(context, mFreeMem));
            pw.print(" mTotalMemory=");
            pw.println(Formatter.formatFileSize(context, mTotalMemory));

            pw.print("  mFreeMemAfterLastCacheClear=");
            pw.println(Formatter.formatFileSize(context, mFreeMemAfterLastCacheClear));

            pw.print("  mLastReportedFreeMem=");
            pw.print(Formatter.formatFileSize(context, mLastReportedFreeMem));
            pw.print(" mLastReportedFreeMemTime=");
            TimeUtils.formatDuration(mLastReportedFreeMemTime, SystemClock.elapsedRealtime(), pw);
            pw.println();

            if (mUpdatesStopped) {
                pw.print("  mUpdatesStopped=");
                pw.print(mUpdatesStopped);
                pw.print(" mSeq=");
                pw.println(mSeq.get());
            } else {
                pw.print("  mClearSucceeded=");
                pw.print(mClearSucceeded);
                pw.print(" mClearingCache=");
                pw.println(mClearingCache);
            }

            pw.print("  mLowMemFlag=");
            pw.print(mLowMemFlag);
            pw.print(" mMemFullFlag=");
            pw.println(mMemFullFlag);

            pw.print("  mMemLowThreshold=");
            pw.print(Formatter.formatFileSize(context, mMemLowThreshold));
            pw.print(" mMemFullThreshold=");
            pw.println(Formatter.formatFileSize(context, mMemFullThreshold));

            pw.print("  mMemCacheStartTrimThreshold=");
            pw.print(Formatter.formatFileSize(context, mMemCacheStartTrimThreshold));
            pw.print(" mMemCacheTrimToThreshold=");
            pw.println(Formatter.formatFileSize(context, mMemCacheTrimToThreshold));

            pw.print("  mIsBootImageOnDisk="); pw.println(mIsBootImageOnDisk);
        } else {
            Shell shell = new Shell();
            shell.exec(mRemoteService, null, fd, null, args, null, new ResultReceiver(null));
        }
    }

    /**
    * This method sends a notification to NotificationManager to display
    * an error dialog indicating low disk space and launch the Installer
    * application
    */
    private void sendNotification(int seq) {
        final Context context = getContext();
        if(localLOGV) Slog.i(TAG, "Sending low memory notification");
        //log the event to event log with the amount of free storage(in bytes) left on the device
        EventLog.writeEvent(EventLogTags.LOW_STORAGE, mFreeMem);
        //  Pack up the values and broadcast them to everyone
        Intent lowMemIntent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);
        lowMemIntent.putExtra("memory", mFreeMem);
        lowMemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager notificationMgr =
                (NotificationManager)context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        CharSequence title = context.getText(
                com.android.internal.R.string.low_internal_storage_view_title);
        CharSequence details = context.getText(mIsBootImageOnDisk
                ? com.android.internal.R.string.low_internal_storage_view_text
                : com.android.internal.R.string.low_internal_storage_view_text_no_boot);
        PendingIntent intent = PendingIntent.getActivityAsUser(context, 0,  lowMemIntent, 0,
                null, UserHandle.CURRENT);
        Notification notification =
                new Notification.Builder(context, SystemNotificationChannels.ALERTS)
                        .setSmallIcon(com.android.internal.R.drawable.stat_notify_disk_full)
                        .setTicker(title)
                        .setColor(context.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(details)
                        .setContentIntent(intent)
                        .setStyle(new Notification.BigTextStyle()
                              .bigText(details))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .extend(new Notification.TvExtender()
                                .setChannel(TV_NOTIFICATION_CHANNEL_ID))
                        .build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        notificationMgr.notifyAsUser(null, SystemMessage.NOTE_LOW_STORAGE, notification,
                UserHandle.ALL);
        Intent broadcast = new Intent(mStorageLowIntent);
        if (seq != 0) {
            broadcast.putExtra(EXTRA_SEQUENCE, seq);
        }
        context.sendStickyBroadcastAsUser(broadcast, UserHandle.ALL);
    }

    /**
     * Cancels low storage notification and sends OK intent.
     */
    private void cancelNotification(int seq) {
        final Context context = getContext();
        if(localLOGV) Slog.i(TAG, "Canceling low memory notification");
        NotificationManager mNotificationMgr =
                (NotificationManager)context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        //cancel notification since memory has been freed
        mNotificationMgr.cancelAsUser(null, SystemMessage.NOTE_LOW_STORAGE, UserHandle.ALL);

        context.removeStickyBroadcastAsUser(mStorageLowIntent, UserHandle.ALL);
        Intent broadcast = new Intent(mStorageOkIntent);
        if (seq != 0) {
            broadcast.putExtra(EXTRA_SEQUENCE, seq);
        }
        context.sendBroadcastAsUser(broadcast, UserHandle.ALL);
    }

    /**
     * Send a notification when storage is full.
     */
    private void sendFullNotification() {
        if(localLOGV) Slog.i(TAG, "Sending memory full notification");
        getContext().sendStickyBroadcastAsUser(mStorageFullIntent, UserHandle.ALL);
    }

    /**
     * Cancels memory full notification and sends "not full" intent.
     */
    private void cancelFullNotification() {
        if(localLOGV) Slog.i(TAG, "Canceling memory full notification");
        getContext().removeStickyBroadcastAsUser(mStorageFullIntent, UserHandle.ALL);
        getContext().sendBroadcastAsUser(mStorageNotFullIntent, UserHandle.ALL);
    }

    private static class CacheFileDeletedObserver extends FileObserver {
        public CacheFileDeletedObserver() {
            super(Environment.getDownloadCacheDirectory().getAbsolutePath(), FileObserver.DELETE);
        }

        @Override
        public void onEvent(int event, String path) {
            EventLogTags.writeCacheFileDeleted(path);
        }
    }
}
