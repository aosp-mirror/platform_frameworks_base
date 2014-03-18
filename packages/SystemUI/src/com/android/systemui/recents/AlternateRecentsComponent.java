/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUI;

import java.util.List;

/** A proxy implementation for the recents component */
public class AlternateRecentsComponent {

    /** A handler for messages from the recents implementation */
    class RecentsMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_UPDATE_FOR_CONFIGURATION) {
                Resources res = mContext.getResources();
                float statusBarHeight = res.getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height);
                mFirstTaskRect = (Rect) msg.getData().getParcelable("taskRect");
                mFirstTaskRect.offset(0, (int) statusBarHeight);
            }
        }
    }

    /** A service connection to the recents implementation */
    class RecentsServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Console.log(Constants.DebugFlags.App.RecentsComponent,
                    "[RecentsComponent|ServiceConnection|onServiceConnected]",
                    "toggleRecents: " + mToggleRecentsUponServiceBound);
            mService = new Messenger(service);
            mServiceIsBound = true;

            // Toggle recents if this service connection was triggered by hitting the recents button
            if (mToggleRecentsUponServiceBound) {
                startAlternateRecentsActivity();
            }
            mToggleRecentsUponServiceBound = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Console.log(Constants.DebugFlags.App.RecentsComponent,
                    "[RecentsComponent|ServiceConnection|onServiceDisconnected]");
            mService = null;
            mServiceIsBound = false;
        }
    }

    final static int MSG_UPDATE_FOR_CONFIGURATION = 0;
    final static int MSG_UPDATE_TASK_THUMBNAIL = 1;
    final static int MSG_PRELOAD_TASKS = 2;
    final static int MSG_CANCEL_PRELOAD_TASKS = 3;
    final static int MSG_CLOSE_RECENTS = 4;
    final static int MSG_TOGGLE_RECENTS = 5;

    final static int sMinToggleDelay = 425;

    final static String sToggleRecentsAction = "com.android.systemui.recents.SHOW_RECENTS";
    final static String sRecentsPackage = "com.android.systemui";
    final static String sRecentsActivity = "com.android.systemui.recents.RecentsActivity";
    final static String sRecentsService = "com.android.systemui.recents.RecentsService";

    Context mContext;

    // Recents service binding
    Messenger mService = null;
    Messenger mMessenger;
    boolean mServiceIsBound = false;
    boolean mToggleRecentsUponServiceBound;
    RecentsServiceConnection mConnection = new RecentsServiceConnection();

    View mStatusBarView;
    Rect mFirstTaskRect = new Rect();
    long mLastToggleTime;

    public AlternateRecentsComponent(Context context) {
        mContext = context;
        mMessenger = new Messenger(new RecentsMessageHandler());
    }

    public void onStart() {
        Console.log(Constants.DebugFlags.App.RecentsComponent, "[RecentsComponent|start]");

        // Try to create a long-running connection to the recents service
        bindToRecentsService(false);
    }

    /** Toggles the alternate recents activity */
    public void onToggleRecents(Display display, int layoutDirection, View statusBarView) {
        Console.logStartTracingTime(Constants.DebugFlags.App.TimeRecentsStartup,
                Constants.DebugFlags.App.TimeRecentsStartupKey);
        Console.logStartTracingTime(Constants.DebugFlags.App.TimeRecentsLaunchTask,
                Constants.DebugFlags.App.TimeRecentsLaunchKey);
        Console.log(Constants.DebugFlags.App.RecentsComponent, "[RecentsComponent|toggleRecents]",
                "serviceIsBound: " + mServiceIsBound);
        mStatusBarView = statusBarView;
        if (!mServiceIsBound) {
            // Try to create a long-running connection to the recents service before toggling
            // recents
            bindToRecentsService(true);
            return;
        }

        try {
            startAlternateRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    public void onPreloadRecents() {
        // Do nothing
    }

    public void onCancelPreloadingRecents() {
        // Do nothing
    }

    public void onCloseRecents() {
        Console.log(Constants.DebugFlags.App.RecentsComponent, "[RecentsComponent|closeRecents]");
        if (mServiceIsBound) {
            // Try and update the recents configuration
            try {
                Bundle data = new Bundle();
                Message msg = Message.obtain(null, MSG_CLOSE_RECENTS, 0, 0);
                msg.setData(data);
                mService.send(msg);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mServiceIsBound) {
            Resources res = mContext.getResources();
            int statusBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
            int navBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height);
            Rect rect = new Rect();
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRectSize(rect);

            // Try and update the recents configuration
            try {
                Bundle data = new Bundle();
                data.putParcelable("windowRect", rect);
                data.putParcelable("systemInsets", new Rect(0, statusBarHeight, 0, 0));
                Message msg = Message.obtain(null, MSG_UPDATE_FOR_CONFIGURATION, 0, 0);
                msg.setData(data);
                msg.replyTo = mMessenger;
                mService.send(msg);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
    }

    /** Binds to the recents implementation */
    private void bindToRecentsService(boolean toggleRecentsUponConnection) {
        mToggleRecentsUponServiceBound = toggleRecentsUponConnection;
        Intent intent = new Intent();
        intent.setClassName(sRecentsPackage, sRecentsService);
        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    /** Loads the first task thumbnail */
    Bitmap loadFirstTaskThumbnail() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasksForUser(1,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE | ActivityManager.RECENT_INCLUDE_RELATED,
                UserHandle.CURRENT.getIdentifier());
        for (ActivityManager.RecentTaskInfo t : tasks) {
            // Skip tasks in the home stack
            if (am.isInHomeStack(t.persistentId)) {
                return null;
            }

            Bitmap thumbnail = am.getTaskTopThumbnail(t.persistentId);
            return thumbnail;
        }
        return null;
    }

    /** Returns whether there is a first task */
    boolean hasFirstTask() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasksForUser(1,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE | ActivityManager.RECENT_INCLUDE_RELATED,
                UserHandle.CURRENT.getIdentifier());
        for (ActivityManager.RecentTaskInfo t : tasks) {
            // Skip tasks in the home stack
            if (am.isInHomeStack(t.persistentId)) {
                continue;
            }

            return true;
        }
        return false;
    }

    /** Converts from the device rotation to the degree */
    float getDegreesForRotation(int value) {
        switch (value) {
            case Surface.ROTATION_90:
                return 360f - 90f;
            case Surface.ROTATION_180:
                return 360f - 180f;
            case Surface.ROTATION_270:
                return 360f - 270f;
        }
        return 0f;
    }

    /** Takes a screenshot of the surface */
    Bitmap takeScreenshot(Display display) {
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        float[] dims = {dm.widthPixels, dm.heightPixels};
        float degrees = getDegreesForRotation(display.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            Matrix m = new Matrix();
            m.preRotate(-degrees);
            m.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }
        return SurfaceControl.screenshot((int) dims[0], (int) dims[1]);
    }

    /** Starts the recents activity */
    void startAlternateRecentsActivity() {
        // If the user has toggled it too quickly, then just eat up the event here (it's better than
        // showing a janky screenshot).
        // NOTE: Ideally, the screenshot mechanism would take the window transform into account
        if (System.currentTimeMillis() - mLastToggleTime < sMinToggleDelay) {
            return;
        }

        // If Recents is the front most activity, then we should just communicate with it directly
        // to launch the first task or dismiss itself
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            ComponentName topActivity = tasks.get(0).topActivity;

            // Check if the front most activity is recents
            if (topActivity.getPackageName().equals(sRecentsPackage) &&
                    topActivity.getClassName().equals(sRecentsActivity)) {
                // Notify Recents to toggle itself
                try {
                    Bundle data = new Bundle();
                    Message msg = Message.obtain(null, MSG_TOGGLE_RECENTS, 0, 0);
                    msg.setData(data);
                    mService.send(msg);

                    // Time this path
                    Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsStartup,
                            Constants.DebugFlags.App.TimeRecentsStartupKey, "sendToggleRecents");
                    Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsLaunchTask,
                            Constants.DebugFlags.App.TimeRecentsLaunchKey, "sendToggleRecents");
                } catch (RemoteException re) {
                    re.printStackTrace();
                }
                mLastToggleTime = System.currentTimeMillis();
                return;
            }
        }

        // Otherwise, Recents is not the front-most activity and we should animate into it
        Rect taskRect = mFirstTaskRect;
        if (taskRect != null && taskRect.width() > 0 && taskRect.height() > 0 && hasFirstTask()) {
            // Loading from thumbnail
            Bitmap thumbnail;
            Bitmap firstThumbnail = loadFirstTaskThumbnail();
            if (firstThumbnail == null) {
                // Load the thumbnail from the screenshot
                WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                Display display = wm.getDefaultDisplay();
                Bitmap screenshot = takeScreenshot(display);
                Resources res = mContext.getResources();
                int size = Math.min(screenshot.getWidth(), screenshot.getHeight());
                int statusBarHeight = res.getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height);
                thumbnail = Bitmap.createBitmap(mFirstTaskRect.width(), mFirstTaskRect.height(),
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(thumbnail);
                c.drawBitmap(screenshot, new Rect(0, statusBarHeight, size, statusBarHeight + size),
                        new Rect(0, 0, mFirstTaskRect.width(), mFirstTaskRect.height()), null);
                c.setBitmap(null);
                // Recycle the old screenshot
                screenshot.recycle();
            } else {
                // Create the thumbnail
                thumbnail = Bitmap.createBitmap(mFirstTaskRect.width(), mFirstTaskRect.height(),
                        Bitmap.Config.ARGB_8888);
                int size = Math.min(firstThumbnail.getWidth(), firstThumbnail.getHeight());
                Canvas c = new Canvas(thumbnail);
                c.drawBitmap(firstThumbnail, new Rect(0, 0, size, size),
                        new Rect(0, 0, mFirstTaskRect.width(), mFirstTaskRect.height()), null);
                c.setBitmap(null);
                // Recycle the old thumbnail
                firstThumbnail.recycle();
            }

            ActivityOptions opts = ActivityOptions.makeThumbnailScaleDownAnimation(mStatusBarView,
                    thumbnail, mFirstTaskRect.left, mFirstTaskRect.top, null);
            startAlternateRecentsActivity(opts);
        } else {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.recents_from_launcher_enter,
                    R.anim.recents_from_launcher_exit);
            startAlternateRecentsActivity(opts);
        }

        Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsStartup,
                Constants.DebugFlags.App.TimeRecentsStartupKey, "startRecentsActivity");
        mLastToggleTime = System.currentTimeMillis();
    }

    /** Starts the recents activity */
    void startAlternateRecentsActivity(ActivityOptions opts) {
        Intent intent = new Intent(sToggleRecentsAction);
        intent.setClassName(sRecentsPackage, sRecentsActivity);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), new UserHandle(
                    UserHandle.USER_CURRENT));
        } else {
            mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }
    }
}
