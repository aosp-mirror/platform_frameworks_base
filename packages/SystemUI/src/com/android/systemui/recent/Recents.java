/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.recent;

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


public class Recents extends SystemUI implements RecentsComponent {
    /** A handler for messages from the recents implementation */
    class RecentsMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (!mUseAlternateRecents) return;
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
            if (!mUseAlternateRecents) return;

            Log.d(TAG, "[RecentsComponent|ServiceConnection|onServiceConnected] toggleRecents: " +
                    mToggleRecentsUponServiceBound);
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
            if (!mUseAlternateRecents) return;

            Log.d(TAG, "[RecentsComponent|ServiceConnection|onServiceDisconnected]");
            mService = null;
            mServiceIsBound = false;
        }
    }

    private static final String TAG = "Recents";
    private static final boolean DEBUG = true;

    final static int MSG_UPDATE_FOR_CONFIGURATION = 0;
    final static int MSG_UPDATE_TASK_THUMBNAIL = 1;
    final static int MSG_PRELOAD_TASKS = 2;
    final static int MSG_CANCEL_PRELOAD_TASKS = 3;

    final static String sToggleRecentsAction = "com.android.systemui.recents.TOGGLE_RECENTS";
    final static String sRecentsPackage = "com.android.systemui";
    final static String sRecentsActivity = "com.android.systemui.recents.RecentsActivity";
    final static String sRecentsService = "com.android.systemui.recents.RecentsService";

    // Which recents to use
    boolean mUseAlternateRecents;

    // Recents service binding
    Messenger mService = null;
    Messenger mMessenger;
    boolean mServiceIsBound = false;
    boolean mToggleRecentsUponServiceBound;
    RecentsServiceConnection mConnection = new RecentsServiceConnection();

    View mStatusBarView;
    Rect mFirstTaskRect = new Rect();

    public Recents() {
        mMessenger = new Messenger(new RecentsMessageHandler());
    }

    @Override
    public void start() {
        mUseAlternateRecents =
                SystemProperties.getBoolean("persist.recents.use_alternate", false);

        putComponent(RecentsComponent.class, this);

        if (mUseAlternateRecents) {
            Log.d(TAG, "[RecentsComponent|start]");

            // Try to create a long-running connection to the recents service
            bindToRecentsService(false);
        }
    }

    @Override
    public void toggleRecents(Display display, int layoutDirection, View statusBarView) {
        if (mUseAlternateRecents) {
            // Launch the alternate recents if required
            toggleAlternateRecents(display, layoutDirection, statusBarView);
            return;
        }

        if (DEBUG) Log.d(TAG, "toggle recents panel");
        try {
            TaskDescription firstTask = RecentTasksLoader.getInstance(mContext).getFirstTask();

            Intent intent = new Intent(RecentsActivity.TOGGLE_RECENTS_INTENT);
            intent.setClassName("com.android.systemui",
                    "com.android.systemui.recent.RecentsActivity");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            if (firstTask == null) {
                if (RecentsActivity.forceOpaqueBackground(mContext)) {
                    ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_from_launcher_enter,
                            R.anim.recents_launch_from_launcher_exit);
                    mContext.startActivityAsUser(intent, opts.toBundle(), new UserHandle(
                            UserHandle.USER_CURRENT));
                } else {
                    // The correct window animation will be applied via the activity's style
                    mContext.startActivityAsUser(intent, new UserHandle(
                            UserHandle.USER_CURRENT));
                }

            } else {
                Bitmap first = null;
                if (firstTask.getThumbnail() instanceof BitmapDrawable) {
                    first = ((BitmapDrawable) firstTask.getThumbnail()).getBitmap();
                } else {
                    first = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    Drawable d = RecentTasksLoader.getInstance(mContext).getDefaultThumbnail();
                    d.draw(new Canvas(first));
                }
                final Resources res = mContext.getResources();

                float thumbWidth = res
                        .getDimensionPixelSize(R.dimen.status_bar_recents_thumbnail_width);
                float thumbHeight = res
                        .getDimensionPixelSize(R.dimen.status_bar_recents_thumbnail_height);
                if (first == null) {
                    throw new RuntimeException("Recents thumbnail is null");
                }
                if (first.getWidth() != thumbWidth || first.getHeight() != thumbHeight) {
                    first = Bitmap.createScaledBitmap(first, (int) thumbWidth, (int) thumbHeight,
                            true);
                    if (first == null) {
                        throw new RuntimeException("Recents thumbnail is null");
                    }
                }


                DisplayMetrics dm = new DisplayMetrics();
                display.getMetrics(dm);
                // calculate it here, but consider moving it elsewhere
                // first, determine which orientation you're in.
                final Configuration config = res.getConfiguration();
                int x, y;

                if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    float appLabelLeftMargin = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_app_label_left_margin);
                    float appLabelWidth = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_app_label_width);
                    float thumbLeftMargin = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_thumbnail_left_margin);
                    float thumbBgPadding = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_thumbnail_bg_padding);

                    float width = appLabelLeftMargin +
                            +appLabelWidth
                            + thumbLeftMargin
                            + thumbWidth
                            + 2 * thumbBgPadding;

                    x = (int) ((dm.widthPixels - width) / 2f + appLabelLeftMargin + appLabelWidth
                            + thumbBgPadding + thumbLeftMargin);
                    y = (int) (dm.heightPixels
                            - res.getDimensionPixelSize(R.dimen.status_bar_recents_thumbnail_height)
                            - thumbBgPadding);
                    if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                        x = dm.widthPixels - x - res.getDimensionPixelSize(
                                R.dimen.status_bar_recents_thumbnail_width);
                    }

                } else { // if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    float thumbTopMargin = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_thumbnail_top_margin);
                    float thumbBgPadding = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_thumbnail_bg_padding);
                    float textPadding = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_text_description_padding);
                    float labelTextSize = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_app_label_text_size);
                    Paint p = new Paint();
                    p.setTextSize(labelTextSize);
                    float labelTextHeight = p.getFontMetricsInt().bottom
                            - p.getFontMetricsInt().top;
                    float descriptionTextSize = res.getDimensionPixelSize(
                            R.dimen.status_bar_recents_app_description_text_size);
                    p.setTextSize(descriptionTextSize);
                    float descriptionTextHeight = p.getFontMetricsInt().bottom
                            - p.getFontMetricsInt().top;

                    float statusBarHeight = res.getDimensionPixelSize(
                            com.android.internal.R.dimen.status_bar_height);
                    float recentsItemTopPadding = statusBarHeight;

                    float height = thumbTopMargin
                            + thumbHeight
                            + 2 * thumbBgPadding + textPadding + labelTextHeight
                            + recentsItemTopPadding + textPadding + descriptionTextHeight;
                    float recentsItemRightPadding = res
                            .getDimensionPixelSize(R.dimen.status_bar_recents_item_padding);
                    float recentsScrollViewRightPadding = res
                            .getDimensionPixelSize(R.dimen.status_bar_recents_right_glow_margin);
                    x = (int) (dm.widthPixels - res
                            .getDimensionPixelSize(R.dimen.status_bar_recents_thumbnail_width)
                            - thumbBgPadding - recentsItemRightPadding
                            - recentsScrollViewRightPadding);
                    y = (int) ((dm.heightPixels - statusBarHeight - height) / 2f + thumbTopMargin
                            + recentsItemTopPadding + thumbBgPadding + statusBarHeight);
                }

                ActivityOptions opts = ActivityOptions.makeThumbnailScaleDownAnimation(
                        statusBarView,
                        first, x, y,
                        new ActivityOptions.OnAnimationStartedListener() {
                            public void onAnimationStarted() {
                                Intent intent =
                                        new Intent(RecentsActivity.WINDOW_ANIMATION_START_INTENT);
                                intent.setPackage("com.android.systemui");
                                mContext.sendBroadcastAsUser(intent,
                                        new UserHandle(UserHandle.USER_CURRENT));
                            }
                        });
                intent.putExtra(RecentsActivity.WAITING_FOR_WINDOW_ANIMATION_PARAM, true);
                mContext.startActivityAsUser(intent, opts.toBundle(), new UserHandle(
                        UserHandle.USER_CURRENT));
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentAppsIntent", e);
        }
    }

    /** Toggles the alternate recents activity */
    public void toggleAlternateRecents(Display display, int layoutDirection, View statusBarView) {
        if (!mUseAlternateRecents) return;

        Log.d(TAG, "[RecentsComponent|toggleRecents] serviceIsBound: " + mServiceIsBound);
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
            Log.e(TAG, "Failed to launch RecentAppsIntent", e);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
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
        if (!mUseAlternateRecents) return;

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

    @Override
    public void preloadRecentTasksList() {
        if (mUseAlternateRecents) {
            Log.d(TAG, "[RecentsComponent|preloadRecents]");
        } else {
            Intent intent = new Intent(RecentsActivity.PRELOAD_INTENT);
            intent.setClassName("com.android.systemui",
                    "com.android.systemui.recent.RecentsPreloadReceiver");
            mContext.sendBroadcastAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));

            RecentTasksLoader.getInstance(mContext).preloadFirstTask();
        }
    }

    @Override
    public void cancelPreloadingRecentTasksList() {
        if (mUseAlternateRecents) {
            Log.d(TAG, "[RecentsComponent|cancelPreload]");
        } else {
            Intent intent = new Intent(RecentsActivity.CANCEL_PRELOAD_INTENT);
            intent.setClassName("com.android.systemui",
                    "com.android.systemui.recent.RecentsPreloadReceiver");
            mContext.sendBroadcastAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));

            RecentTasksLoader.getInstance(mContext).cancelPreloadingFirstTask();
        }
    }

    @Override
    public void closeRecents() {
        if (mUseAlternateRecents) {
            Log.d(TAG, "[RecentsComponent|closeRecents]");
        } else {
            Intent intent = new Intent(RecentsActivity.CLOSE_RECENTS_INTENT);
            intent.setPackage("com.android.systemui");
            mContext.sendBroadcastAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));

            RecentTasksLoader.getInstance(mContext).cancelPreloadingFirstTask();
        }
    }
}
