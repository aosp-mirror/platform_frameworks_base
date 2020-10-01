/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.coretests.apps.bstatstestapp;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestService extends Service {
    private static final String TAG = TestService.class.getSimpleName();

    private static final int FLAG_START_FOREGROUND = 1;

    private static final String NOTIFICATION_CHANNEL_ID = TAG;
    private static final int NOTIFICATION_ID = 42;

    private static final int TIMEOUT_OVERLAY_SEC = 2;

    private Context mOverlayContext;
    private View mOverlay;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called. myUid=" + Process.myUid());

        final DisplayManager dm = getSystemService(DisplayManager.class);
        final Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        final Context defaultDisplayContext = createDisplayContext(defaultDisplay);
        mOverlayContext = defaultDisplayContext.createWindowContext(TYPE_APPLICATION_OVERLAY, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called. myUid=" + Process.myUid());
        if (intent != null && (intent.getFlags() & FLAG_START_FOREGROUND) != 0) {
            startForeground();
        }
        notifyServiceLaunched(intent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called. myUid=" + Process.myUid());
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called. myUid=" + Process.myUid());
        removeOverlays();
    }

    private void notifyServiceLaunched(Intent intent) {
        Common.notifyLaunched(intent, mReceiver.asBinder(), TAG);
    }

    private void startForeground() {
        final NotificationManager noMan = getSystemService(NotificationManager.class);
        noMan.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT));
        Log.d(TAG, "Starting foreground. myUid=" + Process.myUid());
        startForeground(NOTIFICATION_ID,
                new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_dialog_alert)
                        .build());
    }

    private void removeOverlays() {
        if (mOverlay != null) {
            final WindowManager wm = mOverlayContext.getSystemService(WindowManager.class);
            wm.removeView(mOverlay);
            mOverlay = null;
        }
    }

    private BaseCmdReceiver mReceiver = new BaseCmdReceiver() {
        @Override
        public void doSomeWork(int durationMs) {
            Common.doSomeWork(durationMs);
        }

        @Override
        public void showApplicationOverlay() throws RemoteException {
            final WindowManager wm = mOverlayContext.getSystemService(WindowManager.class);
            final Rect bounds = wm.getCurrentWindowMetrics().getBounds();

            final WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(
                    TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            wmlp.width = bounds.width() / 2;
            wmlp.height = bounds.height() / 2;
            wmlp.gravity = Gravity.CENTER | Gravity.LEFT;
            wmlp.setTitle(TAG);

            final ViewGroup.LayoutParams vglp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            mOverlay = new View(mOverlayContext);
            mOverlay.setBackgroundColor(Color.GREEN);
            mOverlay.setLayoutParams(vglp);

            final CountDownLatch latch = new CountDownLatch(1);
            final Handler handler = new Handler(TestService.this.getMainLooper());
            handler.post(() -> {
                wm.addView(mOverlay, wmlp);
                latch.countDown();
            });
            try {
                if (!latch.await(TIMEOUT_OVERLAY_SEC, TimeUnit.SECONDS)) {
                    throw new RemoteException("Timed out waiting for the overlay");
                }
            } catch (InterruptedException e) {
                throw new RemoteException("Error while adding overlay: " + e.toString());
            }
            Log.d(TAG, "Overlay displayed, myUid=" + Process.myUid());
        }

        @Override
        public void finishHost() {
            removeOverlays();
            stopSelf();
        }
    };
}
