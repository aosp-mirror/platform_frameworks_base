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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * An observer / callback to create and register by
 * {@link ActivityManager#registerHomeVisibilityObserver} so that it's triggered when
 * visibility of home page changes.
 * TODO: b/144351078 expose as SystemApi
 * @hide
 */
public abstract class HomeVisibilityObserver {
    private Context mContext;
    private ActivityManager mActivityManager;
    /** @hide */
    IProcessObserver.Stub mObserver;
    /** @hide */
    boolean mIsHomeActivityVisible;

    /** @hide */
    void init(Context context, ActivityManager activityManager) {
        mContext = context;
        mActivityManager = activityManager;
        mIsHomeActivityVisible = isHomeActivityVisible();
    }

    /**
     * The API that needs implemented and will be triggered when activity on home page changes.
     */
    public abstract void onHomeVisibilityChanged(boolean isHomeActivityVisible);

    public HomeVisibilityObserver() {
        mObserver = new IProcessObserver.Stub() {
            @Override
            public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
                boolean isHomeActivityVisible = isHomeActivityVisible();
                if (mIsHomeActivityVisible != isHomeActivityVisible) {
                    mIsHomeActivityVisible = isHomeActivityVisible;
                    onHomeVisibilityChanged(mIsHomeActivityVisible);
                }
            }

            @Override
            public void onForegroundServicesChanged(int pid, int uid, int fgServiceTypes) {
            }

            @Override
            public void onProcessDied(int pid, int uid) {
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
        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_HOME);

        ResolveInfo info = mContext.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null && top.equals(info.activityInfo.packageName)) {
            return true;
        }

        return false;
    }
}
