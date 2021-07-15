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

package com.android.wm.shell.legacysplitscreen;


import static com.android.wm.shell.legacysplitscreen.ForcedResizableInfoActivity.EXTRA_FORCED_RESIZEABLE_REASON;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.ArraySet;
import android.widget.Toast;

import com.android.wm.shell.R;
import com.android.wm.shell.common.ShellExecutor;

import java.util.function.Consumer;

/**
 * Controller that decides when to show the {@link ForcedResizableInfoActivity}.
 */
final class ForcedResizableInfoActivityController implements DividerView.DividerCallbacks {

    private static final String SELF_PACKAGE_NAME = "com.android.systemui";

    private static final int TIMEOUT = 1000;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ArraySet<PendingTaskRecord> mPendingTasks = new ArraySet<>();
    private final ArraySet<String> mPackagesShownInSession = new ArraySet<>();
    private boolean mDividerDragging;

    private final Runnable mTimeoutRunnable = this::showPending;

    private final Consumer<Boolean> mDockedStackExistsListener = exists -> {
        if (!exists) {
            mPackagesShownInSession.clear();
        }
    };

    /** Record of force resized task that's pending to be handled. */
    private class PendingTaskRecord {
        int mTaskId;
        /**
         * {@link android.app.ITaskStackListener#FORCED_RESIZEABLE_REASON_SPLIT_SCREEN} or
         * {@link android.app.ITaskStackListener#FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY}
         */
        int mReason;

        PendingTaskRecord(int taskId, int reason) {
            this.mTaskId = taskId;
            this.mReason = reason;
        }
    }

    ForcedResizableInfoActivityController(Context context,
            LegacySplitScreenController splitScreenController,
            ShellExecutor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;
        splitScreenController.registerInSplitScreenListener(mDockedStackExistsListener);
    }

    @Override
    public void onDraggingStart() {
        mDividerDragging = true;
        mMainExecutor.removeCallbacks(mTimeoutRunnable);
    }

    @Override
    public void onDraggingEnd() {
        mDividerDragging = false;
        showPending();
    }

    void onAppTransitionFinished() {
        if (!mDividerDragging) {
            showPending();
        }
    }

    void activityForcedResizable(String packageName, int taskId, int reason) {
        if (debounce(packageName)) {
            return;
        }
        mPendingTasks.add(new PendingTaskRecord(taskId, reason));
        postTimeout();
    }

    void activityDismissingSplitScreen() {
        Toast.makeText(mContext, R.string.dock_non_resizeble_failed_to_dock_text,
                Toast.LENGTH_SHORT).show();
    }

    void activityLaunchOnSecondaryDisplayFailed() {
        Toast.makeText(mContext, R.string.activity_launch_on_secondary_display_failed_text,
                Toast.LENGTH_SHORT).show();
    }

    private void showPending() {
        mMainExecutor.removeCallbacks(mTimeoutRunnable);
        for (int i = mPendingTasks.size() - 1; i >= 0; i--) {
            PendingTaskRecord pendingRecord = mPendingTasks.valueAt(i);
            Intent intent = new Intent(mContext, ForcedResizableInfoActivity.class);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchTaskId(pendingRecord.mTaskId);
            // Set as task overlay and allow to resume, so that when an app enters split-screen and
            // becomes paused, the overlay will still be shown.
            options.setTaskOverlay(true, true /* canResume */);
            intent.putExtra(EXTRA_FORCED_RESIZEABLE_REASON, pendingRecord.mReason);
            mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
        }
        mPendingTasks.clear();
    }

    private void postTimeout() {
        mMainExecutor.removeCallbacks(mTimeoutRunnable);
        mMainExecutor.executeDelayed(mTimeoutRunnable, TIMEOUT);
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
