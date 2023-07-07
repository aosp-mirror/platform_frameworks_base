/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.statusbar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.CompletableFuture;

/**
 * A helper class to communicate with the App Clips service running in SystemUI.
 */
public class AppClipsServiceConnector {

    private static final String TAG = AppClipsServiceConnector.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;

    public AppClipsServiceConnector(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
    }

    /**
     * @return true if the task represented by {@code taskId} can launch App Clips screenshot flow,
     * false otherwise.
     */
    public boolean canLaunchCaptureContentActivityForNote(int taskId) {
        try {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            connectToServiceAndProcessRequest(taskId, future);
            return future.get();
        } catch (Exception e) {
            Log.d(TAG, "Exception from service\n" + e);
        }

        return false;
    }

    private void connectToServiceAndProcessRequest(int taskId, CompletableFuture<Boolean> future) {
        ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    future.complete(IAppClipsService.Stub.asInterface(
                            service).canLaunchCaptureContentActivityForNote(taskId));
                } catch (Exception e) {
                    Log.d(TAG, "Exception from service\n" + e);
                }
                future.complete(false);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (!future.isDone()) {
                    future.complete(false);
                }
            }
        };

        final ComponentName serviceComponent = ComponentName.unflattenFromString(
                mContext.getResources().getString(
                        com.android.internal.R.string.config_screenshotAppClipsServiceComponent));
        final Intent serviceIntent = new Intent();
        serviceIntent.setComponent(serviceComponent);

        boolean bindService = mContext.bindServiceAsUser(serviceIntent, serviceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, mHandler,
                mContext.getUser());

        // Complete the future early if service not bound.
        if (!bindService) {
            future.complete(false);
        }
    }
}
