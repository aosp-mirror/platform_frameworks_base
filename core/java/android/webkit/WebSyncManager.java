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

package android.webkit;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

abstract class WebSyncManager implements Runnable {
    // message code for sync message
    private static final int SYNC_MESSAGE = 101;
    // time delay in millisec for a sync (now) message
    private static int SYNC_NOW_INTERVAL = 100; // 100 millisec
    // time delay in millisec for a sync (later) message
    private static int SYNC_LATER_INTERVAL = 5 * 60 * 1000; // 5 minutes
    // thread for syncing
    private Thread mSyncThread;
    // Name of thread
    private String mThreadName;
    // handler of the sync thread
    protected Handler mHandler;
    // database for the persistent storage
    // Note that this remains uninitialised as it is unused. We cannot remove
    // the member as it leaked into the public API via CookieSyncManager.
    // TODO: hide this member, ditto for mHandler.
    protected WebViewDatabase mDataBase;
    // Ref count for calls to start/stop sync
    private int mStartSyncRefCount;
    // log tag
    protected static final String LOGTAG = "websync";

    private class SyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SYNC_MESSAGE) {
                if (DebugFlags.WEB_SYNC_MANAGER) {
                    Log.v(LOGTAG, "*** WebSyncManager sync ***");
                }
                syncFromRamToFlash();

                // send a delayed message to request sync later
                Message newmsg = obtainMessage(SYNC_MESSAGE);
                sendMessageDelayed(newmsg, SYNC_LATER_INTERVAL);
            }
        }
    }

    protected WebSyncManager(Context context, String name) {
        mThreadName = name;
        if (context != null) {
            mSyncThread = new Thread(this);
            mSyncThread.setName(mThreadName);
            mSyncThread.start();
        } else {
            throw new IllegalStateException(
                    "WebSyncManager can't be created without context");
        }
    }

    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("doesn't implement Cloneable");
    }

    public void run() {
        // prepare Looper for sync handler
        Looper.prepare();
        mHandler = new SyncHandler();
        onSyncInit();
        // lower the priority after onSyncInit() is done
       Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        Message msg = mHandler.obtainMessage(SYNC_MESSAGE);
        mHandler.sendMessageDelayed(msg, SYNC_LATER_INTERVAL);

        Looper.loop();
    }

    /**
     * sync() forces sync manager to sync now
     */
    public void sync() {
        if (DebugFlags.WEB_SYNC_MANAGER) {
            Log.v(LOGTAG, "*** WebSyncManager sync ***");
        }
        if (mHandler == null) {
            return;
        }
        mHandler.removeMessages(SYNC_MESSAGE);
        Message msg = mHandler.obtainMessage(SYNC_MESSAGE);
        mHandler.sendMessageDelayed(msg, SYNC_NOW_INTERVAL);
    }

    /**
     * resetSync() resets sync manager's timer
     */
    public void resetSync() {
        if (DebugFlags.WEB_SYNC_MANAGER) {
            Log.v(LOGTAG, "*** WebSyncManager resetSync ***");
        }
        if (mHandler == null) {
            return;
        }
        mHandler.removeMessages(SYNC_MESSAGE);
        Message msg = mHandler.obtainMessage(SYNC_MESSAGE);
        mHandler.sendMessageDelayed(msg, SYNC_LATER_INTERVAL);
    }

    /**
     * startSync() requests sync manager to start sync
     */
    public void startSync() {
        if (DebugFlags.WEB_SYNC_MANAGER) {
            Log.v(LOGTAG, "***  WebSyncManager startSync ***, Ref count:" + 
                    mStartSyncRefCount);
        }
        if (mHandler == null) {
            return;
        }
        if (++mStartSyncRefCount == 1) {
            Message msg = mHandler.obtainMessage(SYNC_MESSAGE);
            mHandler.sendMessageDelayed(msg, SYNC_LATER_INTERVAL);
        }
    }

    /**
     * stopSync() requests sync manager to stop sync. remove any SYNC_MESSAGE in
     * the queue to break the sync loop
     */
    public void stopSync() {
        if (DebugFlags.WEB_SYNC_MANAGER) {
            Log.v(LOGTAG, "*** WebSyncManager stopSync ***, Ref count:" + 
                    mStartSyncRefCount);
        }
        if (mHandler == null) {
            return;
        }
        if (--mStartSyncRefCount == 0) {
            mHandler.removeMessages(SYNC_MESSAGE);
        }
    }

    protected void onSyncInit() {
    }

    abstract void syncFromRamToFlash();
}
