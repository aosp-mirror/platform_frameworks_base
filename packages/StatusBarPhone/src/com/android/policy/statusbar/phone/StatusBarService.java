/*
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

package com.android.policy.statusbar.phone;

import android.app.Service;
import android.app.IStatusBar;
import android.app.IStatusBarService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import com.android.server.status.IconData;
import com.android.server.status.NotificationData;

public abstract class StatusBarService extends Service {
    private static final String TAG = "StatusBarService";

    Bar mBar = new Bar();
    IStatusBarService mBarService;

    /* TODO
    H mHandler = new H();
    Object mQueueLock = new Object();
    ArrayList<PendingOp> mQueue = new ArrayList<PendingOp>();
    NotificationCallbacks mNotificationCallbacks;
    */

    @Override
    public void onCreate() {
        // Put up the view
        addStatusBarView();

        // Connect in to the status bar manager service
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        try {
            mBarService.registerStatusBar(mBar);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }
    }

    @Override
    public void onDestroy() {
        // we're never destroyed
    }

    /**
     * Nobody binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class Bar extends IStatusBar.Stub {
    }

    /**
     * Implement this to add the main status bar view.
     */
    protected abstract void addStatusBarView();

    public void activate() {
    }

    public void deactivate() {
    }

    public void toggle() {
    }

    public void disable(int what, IBinder token, String pkg) {
    }
    
    public IBinder addIcon(IconData data, NotificationData n) {
        return null;
    }

    public void updateIcon(IBinder key, IconData data, NotificationData n) {
    }

    public void setIconVisibility(IBinder key, boolean visible) {
        //addPendingOp(OP_SET_VISIBLE, key, visible);
    }
}

