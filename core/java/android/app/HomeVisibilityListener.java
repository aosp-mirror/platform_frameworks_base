/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Binder;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A listener that will be invoked when the visibility of the home screen changes.
 * Register this callback via {@link ActivityManager#addHomeVisibilityListener}
 * @hide
 */
// This is a single-method listener that needs a bunch of supporting code, so it can't be an
// interface
@SuppressLint("ListenerInterface")
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@TestApi
public abstract class HomeVisibilityListener {
    private ActivityTaskManager mActivityTaskManager;
    private Executor mExecutor;
    private int mMaxScanTasksForHomeVisibility;
    /** @hide */
    android.app.IProcessObserver.Stub mObserver;
    /** @hide */
    boolean mIsHomeActivityVisible;

    /** @hide */
    void init(Context context, Executor executor) {
        mActivityTaskManager = ActivityTaskManager.getInstance();
        mExecutor = executor;
        mMaxScanTasksForHomeVisibility = context.getResources().getInteger(
                com.android.internal.R.integer.config_maxScanTasksForHomeVisibility);
        mIsHomeActivityVisible = isHomeActivityVisible();
    }

    /**
     * Called when the visibility of the home screen changes.
     *
     * @param isHomeActivityVisible Whether the home screen activity is now visible.
     */
    public abstract void onHomeVisibilityChanged(boolean isHomeActivityVisible);

    public HomeVisibilityListener() {
        mObserver = new android.app.IProcessObserver.Stub() {
            @Override
            public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
                refreshHomeVisibility();
            }

            @Override
            public void onForegroundServicesChanged(int pid, int uid, int fgServiceTypes) {
            }

            @Override
            public void onProcessDied(int pid, int uid) {
                refreshHomeVisibility();
            }

            private void refreshHomeVisibility() {
                boolean isHomeActivityVisible = isHomeActivityVisible();
                if (mIsHomeActivityVisible != isHomeActivityVisible) {
                    mIsHomeActivityVisible = isHomeActivityVisible;
                    Binder.withCleanCallingIdentity(() ->
                            mExecutor.execute(() ->
                                    onHomeVisibilityChanged(mIsHomeActivityVisible)));
                }
            }
        };
    }

    private boolean isHomeActivityVisible() {
        List<ActivityManager.RunningTaskInfo> tasksTopToBottom = mActivityTaskManager.getTasks(
                mMaxScanTasksForHomeVisibility, /* filterOnlyVisibleRecents= */ true,
                /* keepIntentExtra= */ false, DEFAULT_DISPLAY);
        if (tasksTopToBottom == null || tasksTopToBottom.isEmpty()) {
            return false;
        }

        for (int i = 0, taskSize = tasksTopToBottom.size(); i < taskSize; ++i) {
            ActivityManager.RunningTaskInfo task = tasksTopToBottom.get(i);
            if (!task.isVisible()
                    || (task.baseIntent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0) {
                continue;
            }
            return task.getActivityType() == ACTIVITY_TYPE_HOME;
        }
        return false;
    }
}
