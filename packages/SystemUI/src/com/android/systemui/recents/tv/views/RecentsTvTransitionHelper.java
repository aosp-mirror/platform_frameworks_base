/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.views;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;
import com.android.internal.annotations.GuardedBy;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.*;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;


public class RecentsTvTransitionHelper {
    private static final String TAG = "RecentsTvTransitionHelper";

    private Context mContext;
    private Handler mHandler;

    public RecentsTvTransitionHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    public void launchTaskFromRecents(final TaskStack stack, @Nullable final Task task,
            final TaskStackHorizontalGridView stackView, final TaskCardView taskView,
            final Rect bounds, int destinationStack) {
        final ActivityOptions opts = ActivityOptions.makeBasic();
        if (bounds != null) {
            opts.setLaunchBounds(bounds.isEmpty() ? null : bounds);
        }

        final ActivityOptions.OnAnimationStartedListener animStartedListener;
        if (task.thumbnail != null && task.thumbnail.getWidth() > 0 &&
                task.thumbnail.getHeight() > 0) {
            animStartedListener = new ActivityOptions.OnAnimationStartedListener() {
                @Override
                public void onAnimationStarted() {
                    // If we are launching into another task, cancel the previous task's
                    // window transition
                    EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(task));
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                }
            };
        } else {
            // This is only the case if the task is not on screen (scrolled offscreen for example)
            animStartedListener = new ActivityOptions.OnAnimationStartedListener() {
                @Override
                public void onAnimationStarted() {
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                }
            };
        }

        if (taskView == null) {
            // If there is no task view, then we do not need to worry about animating out occluding
            // task views, and we can launch immediately
            startTaskActivity(stack, task, taskView, opts, animStartedListener);
        } else {
            LaunchTvTaskStartedEvent launchStartedEvent = new LaunchTvTaskStartedEvent(taskView);
            EventBus.getDefault().send(launchStartedEvent);
            startTaskActivity(stack, task, taskView, opts, animStartedListener);
        }
    }

    private void startTaskActivity(TaskStack stack, Task task, @Nullable TaskCardView taskView,
            ActivityOptions opts,final ActivityOptions.OnAnimationStartedListener animStartedListener) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.startActivityFromRecents(mContext, task.key, task.title, opts)) {
            // Keep track of the index of the task launch
            int taskIndexFromFront = 0;
            int taskIndex = stack.indexOfStackTask(task);
            if (taskIndex > -1) {
                taskIndexFromFront = stack.getTaskCount() - taskIndex - 1;
            }
            EventBus.getDefault().send(new LaunchTaskSucceededEvent(taskIndexFromFront));
        } else {
            // Keep track of failed launches
            EventBus.getDefault().send(new LaunchTaskFailedEvent());
        }

        Rect taskRect = taskView.getFocusedThumbnailRect();
        // Check both the rect and the thumbnail for null. The rect can be null if the user
        // decides to disallow animations, so automatic scrolling does not happen properly.

        // The thumbnail can be null if the app was partially launched on TV. In this case
        // we do not override the transition.
        if (taskRect == null || task.thumbnail == null) {
            return;
        }

        IRemoteCallback.Stub callback = null;
        if (animStartedListener != null) {
            callback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (animStartedListener != null) {
                                animStartedListener.onAnimationStarted();
                            }
                        }
                    });
                }
            };
        }
        try {
            Bitmap thumbnail = Bitmap.createScaledBitmap(task.thumbnail, taskRect.width(),
                    taskRect.height(), false);
            WindowManagerGlobal.getWindowManagerService()
                    .overridePendingAppTransitionAspectScaledThumb(thumbnail, taskRect.left,
                            taskRect.top, taskRect.width(), taskRect.height(), callback, true);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to override transition: " + e);
        }
    }
}
