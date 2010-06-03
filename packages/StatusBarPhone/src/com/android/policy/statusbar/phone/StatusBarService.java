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
import android.app.IPoo;
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

public class StatusBarService extends Service {
    private static final String TAG = "StatusBarService";

    Bar mBar = new Bar();
    IStatusBar mBarService;

    @Override
    public void onCreate() {
        // Put up the view
        addStatusBarView();

        // Connect in to the status bar manager service
        mBarService = IStatusBar.Stub.asInterface(
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

    class Bar extends IPoo.Stub {
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void addStatusBarView() {
        final View view = new View(this);

        // TODO final StatusBarView view = mStatusBarView;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                view.getContext().getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height),
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
                PixelFormat.RGB_888);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBar");
        // TODO lp.windowAnimations = R.style.Animation_StatusBar;

        WindowManagerImpl.getDefault().addView(view, lp);
    }
}

