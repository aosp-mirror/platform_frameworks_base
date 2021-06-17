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

import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private Context mContext;
    private ActivityManager mActivityManager;
    private Executor mExecutor;
    /** @hide */
    android.app.IProcessObserver.Stub mObserver;
    /** @hide */
    boolean mIsHomeActivityVisible;

    /** @hide */
    void init(Context context, Executor executor, ActivityManager activityManager) {
        mContext = context;
        mActivityManager = activityManager;
        mIsHomeActivityVisible = isHomeActivityVisible();
        mExecutor = executor;
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
        List<ActivityManager.RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }

        String top = tasks.get(0).topActivity.getPackageName();
        if (top == null) {
            return false;
        }

        // We can assume that the screen is idle if the home application is in the foreground.
        ComponentName defaultHomeComponent = mContext.getPackageManager()
                .getHomeActivities(new ArrayList<>());
        if (defaultHomeComponent == null) return false;

        String defaultHomePackage = defaultHomeComponent.getPackageName();
        return Objects.equals(top, defaultHomePackage);
    }
}
