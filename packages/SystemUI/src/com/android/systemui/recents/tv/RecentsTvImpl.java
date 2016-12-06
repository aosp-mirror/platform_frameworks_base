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

package com.android.systemui.recents.tv;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.UserHandle;

import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.model.ThumbnailData;
import com.android.systemui.recents.tv.views.TaskCardView;
import com.android.systemui.statusbar.tv.TvStatusBar;
import com.android.systemui.tv.pip.PipManager;

public class RecentsTvImpl extends RecentsImpl{
    public final static String RECENTS_TV_ACTIVITY =
            "com.android.systemui.recents.tv.RecentsTvActivity";

    private static final PipManager mPipManager = PipManager.getInstance();

    public RecentsTvImpl(Context context) {
        super(context);
    }

    @Override
    protected void startRecentsActivity(ActivityManager.RunningTaskInfo runningTask,
            boolean isHomeStackVisible, boolean animate, int growTarget) {
        RecentsTaskLoader loader = Recents.getTaskLoader();

        // In the case where alt-tab is triggered, we never get a preloadRecents() call, so we
        // should always preload the tasks now. If we are dragging in recents, reload them as
        // the stacks might have changed.
        if (mTriggeredFromAltTab || sInstanceLoadPlan == null) {
            // Create a new load plan if preloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }
        if (mTriggeredFromAltTab || !sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, runningTask.id, !isHomeStackVisible);
        }
        TaskStack stack = sInstanceLoadPlan.getTaskStack();

        if (!animate) {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext, -1, -1);
            startRecentsActivity(runningTask, opts, false /* fromHome */, false /* fromThumbnail*/);
            return;
        }

        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (runningTask != null) && !isHomeStackVisible && hasRecentTasks;

        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptionsForTV(runningTask,
                    stack.getTaskCount());
            if (opts != null) {
                startRecentsActivity(runningTask, opts, false /* fromHome */, true /* fromThumbnail */);
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        if (!useThumbnailTransition) {
            startRecentsActivity(runningTask, null, true /* fromHome */, false /* fromThumbnail */);
        }
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    protected void startRecentsActivity(ActivityManager.RunningTaskInfo runningTask,
            ActivityOptions opts, boolean fromHome, boolean fromThumbnail) {
        // Update the configuration based on the launch options
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        launchState.launchedFromHome = fromHome;
        launchState.launchedFromApp = fromThumbnail;
        launchState.launchedToTaskId = (runningTask != null) ? runningTask.id : -1;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;

        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, RECENTS_TV_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
        } else {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
        EventBus.getDefault().send(new RecentsActivityStartingEvent());
    }

    /**
     * Creates the activity options for an app->recents transition on TV.
     */
    private ActivityOptions getThumbnailTransitionActivityOptionsForTV(
            ActivityManager.RunningTaskInfo runningTask, int numTasks) {
        Rect rect = TaskCardView.getStartingCardThumbnailRect(
            mContext, !mPipManager.isPipShown(), numTasks);
        SystemServicesProxy ssp = Recents.getSystemServices();
        ThumbnailData thumbnailData = ssp.getTaskThumbnail(runningTask.id);
        if (thumbnailData.thumbnail != null) {
            Bitmap thumbnail = Bitmap.createScaledBitmap(thumbnailData.thumbnail, rect.width(),
                    rect.height(), false);
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    thumbnail, (int) rect.left, (int) rect.top, (int) rect.width(),
                    (int) rect.height(), mHandler, null);
        }
        // If both the screenshot and thumbnail fails, then just fall back to the default transition
        return getUnknownTransitionActivityOptions();
    }

    @Override
    public void onVisibilityChanged(Context context, boolean visible) {
        Recents.getSystemServices().setRecentsVisibility(visible);
    }
}
