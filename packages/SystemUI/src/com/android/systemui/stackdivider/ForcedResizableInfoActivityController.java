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
 * limitations under the License
 */

package com.android.systemui.stackdivider;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.UserHandle;
import android.util.ArraySet;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.AppTransitionFinishedEvent;
import com.android.systemui.recents.events.component.ShowUserToastEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.stackdivider.events.StartedDragingEvent;
import com.android.systemui.stackdivider.events.StoppedDragingEvent;

/**
 * Controller that decides when to show the {@link ForcedResizableInfoActivity}.
 */
public class ForcedResizableInfoActivityController {

    private static final String SELF_PACKAGE_NAME = "com.android.systemui";

    private static final int TIMEOUT = 1000;
    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final ArraySet<Integer> mPendingTaskIds = new ArraySet<>();
    private final ArraySet<String> mPackagesShownInSession = new ArraySet<>();
    private boolean mDividerDraging;

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            showPending();
        }
    };

    public ForcedResizableInfoActivityController(Context context) {
        mContext = context;
        EventBus.getDefault().register(this);
        SystemServicesProxy.getInstance(context).registerTaskStackListener(
                new TaskStackListener() {
                    @Override
                    public void onActivityForcedResizable(String packageName, int taskId) {
                        activityForcedResizable(packageName, taskId);
                    }

                    @Override
                    public void onActivityDismissingDockedStack() {
                        activityDismissingDockedStack();
                    }
                });
    }

    public void notifyDockedStackExistsChanged(boolean exists) {
        if (!exists) {
            mPackagesShownInSession.clear();
        }
    }

    public final void onBusEvent(AppTransitionFinishedEvent event) {
        if (!mDividerDraging) {
            showPending();
        }
    }

    public final void onBusEvent(StartedDragingEvent event) {
        mDividerDraging = true;
        mHandler.removeCallbacks(mTimeoutRunnable);
    }

    public final void onBusEvent(StoppedDragingEvent event) {
        mDividerDraging = false;
        showPending();
    }

    private void activityForcedResizable(String packageName, int taskId) {
        if (debounce(packageName)) {
            return;
        }
        mPendingTaskIds.add(taskId);
        postTimeout();
    }

    private void activityDismissingDockedStack() {
        EventBus.getDefault().send(new ShowUserToastEvent(
                R.string.dock_non_resizeble_failed_to_dock_text, Toast.LENGTH_SHORT));
    }

    private void showPending() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        for (int i = mPendingTaskIds.size() - 1; i >= 0; i--) {
            Intent intent = new Intent(mContext, ForcedResizableInfoActivity.class);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchTaskId(mPendingTaskIds.valueAt(i));
            options.setTaskOverlay(true);
            mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
        }
        mPendingTaskIds.clear();
    }

    private void postTimeout() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mHandler.postDelayed(mTimeoutRunnable, TIMEOUT);
    }

    private boolean debounce(String packageName) {
        if (packageName == null) {
            return false;
        }

        // We launch ForcedResizableInfoActivity into a task that was forced resizable, so that
        // triggers another notification. So ignore our own activity.
        if (SELF_PACKAGE_NAME.equals(packageName)) {
            return true;
        }
        boolean debounce = mPackagesShownInSession.contains(packageName);
        mPackagesShownInSession.add(packageName);
        return debounce;
    }
}
