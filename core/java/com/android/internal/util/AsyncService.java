/**
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.util;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

/**
 * A service that receives Intents and IBinder transactions
 * as messages via an AsyncChannel.
 * <p>
 * The Start Intent arrives as CMD_ASYNC_SERVICE_ON_START_INTENT with msg.arg1 = flags,
 * msg.arg2 = startId, and msg.obj = intent.
 * <p>
 */
abstract public class AsyncService extends Service {
    private static final String TAG = "AsyncService";

    protected static final boolean DBG = true;

    /** The command sent when a onStartCommand is invoked */
    public static final int CMD_ASYNC_SERVICE_ON_START_INTENT = IBinder.LAST_CALL_TRANSACTION;

    /** The command sent when a onDestroy is invoked */
    public static final int CMD_ASYNC_SERVICE_DESTROY = IBinder.LAST_CALL_TRANSACTION + 1;

    /** Messenger transport */
    protected Messenger mMessenger;

    /** Message Handler that will receive messages */
    Handler mHandler;

    public static final class AsyncServiceInfo {
        /** Message Handler that will receive messages */
        public Handler mHandler;

        /**
         * The flags returned by onStartCommand on how to restart.
         * For instance @see android.app.Service#START_STICKY
         */
        public int mRestartFlags;
    }

    AsyncServiceInfo mAsyncServiceInfo;

    /**
     * Create the service's handler returning AsyncServiceInfo.
     *
     * @return AsyncServiceInfo
     */
    abstract public AsyncServiceInfo createHandler();

    /**
     * Get the handler
     */
    public Handler getHandler() {
        return mHandler;
    }

    /**
     * onCreate
     */
    @Override
    public void onCreate() {
        super.onCreate();
        mAsyncServiceInfo = createHandler();
        mHandler = mAsyncServiceInfo.mHandler;
        mMessenger = new Messenger(mHandler);
    }

    /**
     * Sends the CMD_ASYNC_SERVICE_ON_START_INTENT message.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DBG) Log.d(TAG, "onStartCommand");

        Message msg = mHandler.obtainMessage();
        msg.what = CMD_ASYNC_SERVICE_ON_START_INTENT;
        msg.arg1 = flags;
        msg.arg2 = startId;
        msg.obj = intent;
        mHandler.sendMessage(msg);

        return mAsyncServiceInfo.mRestartFlags;
    }

    /**
     * Called when service is destroyed. After returning the
     * service is dead and no more processing should be expected
     * to occur.
     */
    @Override
    public void onDestroy() {
        if (DBG) Log.d(TAG, "onDestroy");

        Message msg = mHandler.obtainMessage();
        msg.what = CMD_ASYNC_SERVICE_DESTROY;
        mHandler.sendMessage(msg);
    }

    /**
     * Returns the Messenger's binder.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }
}
