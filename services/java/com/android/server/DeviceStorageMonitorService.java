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

package com.android.server;

import com.android.server.am.ActivityManagerService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings.Gservices;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.provider.Settings;

/**
 * This class implements a service to monitor the amount of disk storage space
 * on the device. If the free storage on device is less than a tunable threshold value
 * (default is 10%. this value is a gservices parameter) a low memory notification is 
 * displayed to alert the user. If the user clicks on the low memory notification the 
 * Application Manager application gets launched to let the user free storage space.
 * Event log events:
 * A low memory event with the free storage on device in bytes  is logged to the event log
 * when the device goes low on storage space.
 * The amount of free storage on the device is periodically logged to the event log. The log
 * interval is a gservices parameter with a default value of 12 hours
 * When the free storage differential goes below a threshold(again a gservices parameter with
 * a default value of 2MB), the free memory is logged to the event log
 */
class DeviceStorageMonitorService extends Binder {
    private static final String TAG = "DeviceStorageMonitorService";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final int DEVICE_MEMORY_WHAT = 1;
    private static final int MONITOR_INTERVAL = 1; //in minutes
    private static final int LOW_MEMORY_NOTIFICATION_ID = 1;
    private static final int DEFAULT_THRESHOLD_PERCENTAGE = 10;
    private static final int DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES = 12*60; //in minutes
    private static final int EVENT_LOG_STORAGE_BELOW_THRESHOLD = 2744;
    private static final int EVENT_LOG_LOW_STORAGE_NOTIFICATION = 2745;
    private static final int EVENT_LOG_FREE_STORAGE_LEFT = 2746;
    private static final long DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD = 2 * 1024 * 1024; // 2MB
    private long mFreeMem;
    private long mLastReportedFreeMem;
    private long mLastReportedFreeMemTime;
    private boolean mLowMemFlag=false;
    private Context mContext;
    private ContentResolver mContentResolver;
    int mBlkSize;
    long mTotalMemory;
    StatFs mFileStats;
    private static final String DATA_PATH="/data";
    long mThreadStartTime = -1;
    boolean mClearSucceeded = false;
    boolean mClearingCache;
    private Intent mStorageLowIntent;
    private Intent mStorageOkIntent;
    
    /**
     * This string is used for ServiceManager access to this class.
     */
    static final String SERVICE = "devicestoragemonitor";
    
    /**
    * Handler that checks the amount of disk space on the device and sends a 
    * notification if the device runs low on disk space
    */
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            //dont handle an invalid message
            if (msg.what != DEVICE_MEMORY_WHAT) {
                Log.e(TAG, "Will not process invalid message");
                return;
            }
            checkMemory();
        }
    };
    
    class CachePackageDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(String packageName, boolean succeeded) {
            mClearSucceeded = succeeded;
            mClearingCache = false;
            if(localLOGV) Log.i(TAG, " Clear succeeded:"+mClearSucceeded
                    +", mClearingCache:"+mClearingCache);
        }        
    }
    
    private final void restatDataDir() {
        mFileStats.restat(DATA_PATH);
        mFreeMem = mFileStats.getAvailableBlocks()*mBlkSize;
        // Allow freemem to be overridden by debug.freemem for testing
        String debugFreeMem = SystemProperties.get("debug.freemem");
        if (!"".equals(debugFreeMem)) {
            mFreeMem = Long.parseLong(debugFreeMem);
        }
        // Read the log interval from Gservices
        long freeMemLogInterval = Gservices.getLong(mContentResolver,
                Gservices.SYS_FREE_STORAGE_LOG_INTERVAL,
                DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES)*60*1000;
        //log the amount of free memory in event log
        long currTime = SystemClock.elapsedRealtime();
        if((mLastReportedFreeMemTime == 0) || 
                (currTime-mLastReportedFreeMemTime) >= freeMemLogInterval) {
            mLastReportedFreeMemTime = currTime;
            EventLog.writeEvent(EVENT_LOG_FREE_STORAGE_LEFT, mFreeMem);
        }
        // Read the reporting threshold from Gservices
        long threshold = Gservices.getLong(mContentResolver,
                Gservices.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD);
        // If mFree changed significantly log the new value
        long delta = mFreeMem - mLastReportedFreeMem;
        if (delta > threshold || delta < -threshold) {
            mLastReportedFreeMem = mFreeMem;
            EventLog.writeEvent(EVENT_LOG_STORAGE_BELOW_THRESHOLD, mFreeMem);
        }
    }
    
    private final void clearCache() {
        CachePackageDataObserver observer = new CachePackageDataObserver();
        mClearingCache = true;
        try {
            IPackageManager.Stub.asInterface(ServiceManager.getService("package")).
                    freeStorageAndNotify(getMemThreshold(), observer);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get handle for PackageManger Exception: "+e);
            mClearingCache = false;
            mClearSucceeded = false;
        }
    }
    
    private final void checkMemory() {
        //if the thread that was started to clear cache is still running do nothing till its 
        //finished clearing cache. Ideally this flag could be modified by clearCache 
        // and should be accessed via a lock but even if it does this test will fail now and
        //hopefully the next time this flag will be set to the correct value.
        if(mClearingCache) {
            if(localLOGV) Log.i(TAG, "Thread already running just skip");
            //make sure the thread is not hung for too long
            long diffTime = System.currentTimeMillis() - mThreadStartTime;
            if(diffTime > (10*60*1000)) {
                Log.w(TAG, "Thread that clears cache file seems to run for ever");
            } 
        } else {
            restatDataDir();
            if (localLOGV)  Log.v(TAG, "freeMemory="+mFreeMem);
            //post intent to NotificationManager to display icon if necessary
            long memThreshold = getMemThreshold();
            if (mFreeMem < memThreshold) {
                if (!mLowMemFlag) {
                    //see if clearing cache helps
                    mThreadStartTime = System.currentTimeMillis();
                    clearCache();
                    Log.i(TAG, "Running low on memory. Sending notification");
                    sendNotification();
                    mLowMemFlag = true;
                } else {
                    if (localLOGV) Log.v(TAG, "Running low on memory " +
                            "notification already sent. do nothing");
                }
            } else {
                if (mLowMemFlag) {
                    Log.i(TAG, "Memory available. Cancelling notification");
                    cancelNotification();
                    mLowMemFlag = false;
                }
            }
        }
        if(localLOGV) Log.i(TAG, "Posting Message again");
        //keep posting messages to itself periodically
        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEVICE_MEMORY_WHAT), 
                MONITOR_INTERVAL*60*1000);
    }
    
    /*
     * just query settings to retrieve the memory threshold. 
     * Preferred this over using a ContentObserver since Settings.Gservices caches the value
     * any way
     */
    private long getMemThreshold() {
        int value = Settings.Gservices.getInt(
                              mContentResolver, 
                              Settings.Gservices.SYS_STORAGE_THRESHOLD_PERCENTAGE, 
                              DEFAULT_THRESHOLD_PERCENTAGE);
        if(localLOGV) Log.v(TAG, "Threshold Percentage="+value);
        //evaluate threshold value
        return mTotalMemory*value;
    }

    /**
    * Constructor to run service. initializes the disk space threshold value
    * and posts an empty message to kickstart the process.
    */
    public DeviceStorageMonitorService(Context context) {
        mLastReportedFreeMemTime = 0;
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        //create StatFs object
        mFileStats = new StatFs(DATA_PATH);
        //initialize block size
        mBlkSize = mFileStats.getBlockSize();
        //initialize total storage on device
        mTotalMemory = (mFileStats.getBlockCount()*mBlkSize)/100;
        mStorageLowIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_LOW);
        mStorageOkIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_OK);
        checkMemory();
    }
    

    /**
    * This method sends a notification to NotificationManager to display
    * an error dialog indicating low disk space and launch the Installer
    * application
    */
    private final void sendNotification() {
        if(localLOGV) Log.i(TAG, "Sending low memory notification");
        //log the event to event log with the amount of free storage(in bytes) left on the device
        EventLog.writeEvent(EVENT_LOG_LOW_STORAGE_NOTIFICATION, mFreeMem);
        //  Pack up the values and broadcast them to everyone
        Intent lowMemIntent = new Intent(Intent.ACTION_MANAGE_PACKAGE_STORAGE);
        lowMemIntent.putExtra("memory", mFreeMem);
        lowMemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager mNotificationMgr = 
                (NotificationManager)mContext.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        CharSequence title = mContext.getText(
                com.android.internal.R.string.low_internal_storage_view_title);
        CharSequence details = mContext.getText(
                com.android.internal.R.string.low_internal_storage_view_text);
        PendingIntent intent = PendingIntent.getActivity(mContext, 0,  lowMemIntent, 0);
        Notification notification = new Notification();
        notification.icon = com.android.internal.R.drawable.stat_notify_disk_full;
        notification.tickerText = title;
        notification.flags |= Notification.FLAG_NO_CLEAR;
        notification.setLatestEventInfo(mContext, title, details, intent);
        mNotificationMgr.notify(LOW_MEMORY_NOTIFICATION_ID, notification);
        mContext.sendStickyBroadcast(mStorageLowIntent);
    }

    /**
     * Cancels low storage notification and sends OK intent.
     */
    private final void cancelNotification() {
        if(localLOGV) Log.i(TAG, "Canceling low memory notification");
        NotificationManager mNotificationMgr =
                (NotificationManager)mContext.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        //cancel notification since memory has been freed
        mNotificationMgr.cancel(LOW_MEMORY_NOTIFICATION_ID);

        mContext.removeStickyBroadcast(mStorageLowIntent);
        mContext.sendBroadcast(mStorageOkIntent);
    }
    
    public void updateMemory() {
        ActivityManagerService ams = (ActivityManagerService)ServiceManager.getService("activity");
        int callingUid = getCallingUid();
        if(callingUid != Process.SYSTEM_UID) {
            return;
        }
        //remove queued messages
        mHandler.removeMessages(DEVICE_MEMORY_WHAT);
        //force an early check
        checkMemory();
    }
}
