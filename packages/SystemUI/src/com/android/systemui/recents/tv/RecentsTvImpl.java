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

import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.tv.views.TaskCardView;

public class RecentsTvImpl extends RecentsImpl{
    public final static String RECENTS_TV_ACTIVITY =
            "com.android.systemui.recents.tv.RecentsTvActivity";

    public RecentsTvImpl(Context context) {
        super(context);
    }

    @Override
    protected void startRecentsActivity(ActivityManager.RunningTaskInfo topTask,
            boolean isTopTaskHome, boolean animate) {
        RecentsTaskLoader loader = Recents.getTaskLoader();

        // In the case where alt-tab is triggered, we never get a preloadRecents() call, so we
        // should always preload the tasks now. If we are dragging in recents, reload them as
        // the stacks might have changed.
        if (mTriggeredFromAltTab || sInstanceLoadPlan == null) {
            // Create a new load plan if preloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }
        if (mTriggeredFromAltTab || !sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, topTask.id, isTopTaskHome);
        }
        TaskStack stack = sInstanceLoadPlan.getTaskStack();

        if (!animate) {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext, -1, -1);
            startRecentsActivity(topTask, opts, false /* fromHome */, false /* fromThumbnail*/);
            return;
        }

        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (topTask != null) && !isTopTaskHome && hasRecentTasks;

        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptionsForTV(topTask);
            if (opts != null) {
                startRecentsActivity(topTask, opts, false /* fromHome */, true /* fromThumbnail */);
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        if (!useThumbnailTransition) {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition and do the animation from home
            if (hasRecentTasks) {
                ActivityOptions opts = getHomeTransitionActivityOptions();
                startRecentsActivity(topTask, opts, true /* fromHome */, false /* fromThumbnail */);
            } else {
                // Otherwise we do the normal fade from an unknown source
                ActivityOptions opts = getUnknownTransitionActivityOptions();
                startRecentsActivity(topTask, opts, true /* fromHome */, false /* fromThumbnail */);
            }
        }
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    protected void startRecentsActivity(ActivityManager.RunningTaskInfo topTask,
            ActivityOptions opts, boolean fromHome, boolean fromThumbnail) {
        // Update the configuration based on the launch options
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        launchState.launchedFromHome = fromHome;
        launchState.launchedFromApp = fromThumbnail;
        launchState.launchedToTaskId = (topTask != null) ? topTask.id : -1;
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
            ActivityManager.RunningTaskInfo topTask) {
        Bitmap thumbnail = mThumbTransitionBitmapCache;
        Rect rect = TaskCardView.getStartingCardThumbnailRect(mContext);
        if (thumbnail != null) {
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    null, (int) rect.left, (int) rect.top,
                    (int) rect.width(), (int) rect.height(), mHandler, null);
        }
        // If both the screenshot and thumbnail fails, then just fall back to the default transition
        return getUnknownTransitionActivityOptions();
    }
}
