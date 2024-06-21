/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.wm;

import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;

import android.annotation.NonNull;
import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.RemoteException;

import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/**
 * Class that refreshes the activity (through stop/pause -> resume) based on configuration change.
 *
 * <p>This class queries all of its {@link Evaluator}s and restarts the activity if any of them
 * return {@code true} in {@link Evaluator#shouldRefreshActivity}. {@link ActivityRefresher} cycles
 * through either stop or pause and then resume, based on the global config and per-app override.
 */
class ActivityRefresher {
    // Delay for ensuring that onActivityRefreshed is always called after an activity refresh. The
    // client process may not always report the event back to the server, such as process is
    // crashed or got killed.
    private static final long REFRESH_CALLBACK_TIMEOUT_MS = 2000L;

    @NonNull private final WindowManagerService mWmService;
    @NonNull private final Handler mHandler;
    @NonNull private final ArrayList<Evaluator> mEvaluators = new ArrayList<>();

    ActivityRefresher(@NonNull WindowManagerService wmService, @NonNull Handler handler) {
        mWmService = wmService;
        mHandler = handler;
    }

    void addEvaluator(@NonNull Evaluator evaluator) {
        mEvaluators.add(evaluator);
    }

    void removeEvaluator(@NonNull Evaluator evaluator) {
        mEvaluators.remove(evaluator);
    }

    /**
     * "Refreshes" activity by going through "stopped -> resumed" or "paused -> resumed" cycle.
     * This allows to clear cached values in apps (e.g. display or camera rotation) that influence
     * camera preview and can lead to sideways or stretching issues persisting even after force
     * rotation.
     */
    void onActivityConfigurationChanging(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig, @NonNull Configuration lastReportedConfig) {
        if (!shouldRefreshActivity(activity, newConfig, lastReportedConfig)) {
            return;
        }

        final boolean cycleThroughStop =
                mWmService.mLetterboxConfiguration
                        .isCameraCompatRefreshCycleThroughStopEnabled()
                        && !activity.mAppCompatController.getAppCompatCameraOverrides()
                            .shouldRefreshActivityViaPauseForCameraCompat();

        activity.mAppCompatController.getAppCompatCameraOverrides().setIsRefreshRequested(true);
        ProtoLog.v(WM_DEBUG_STATES,
                "Refreshing activity for freeform camera compatibility treatment, "
                        + "activityRecord=%s", activity);
        final RefreshCallbackItem refreshCallbackItem = RefreshCallbackItem.obtain(
                activity.token, cycleThroughStop ? ON_STOP : ON_PAUSE);
        final ResumeActivityItem resumeActivityItem = ResumeActivityItem.obtain(
                activity.token, /* isForward */ false, /* shouldSendCompatFakeFocus */ false);
        try {
            activity.mAtmService.getLifecycleManager().scheduleTransactionAndLifecycleItems(
                    activity.app.getThread(), refreshCallbackItem, resumeActivityItem);
            mHandler.postDelayed(() -> {
                synchronized (mWmService.mGlobalLock) {
                    onActivityRefreshed(activity);
                }
            }, REFRESH_CALLBACK_TIMEOUT_MS);
        } catch (RemoteException e) {
            activity.mAppCompatController.getAppCompatCameraOverrides()
                    .setIsRefreshRequested(false);
        }
    }

    boolean isActivityRefreshing(@NonNull ActivityRecord activity) {
        return activity.mAppCompatController.getAppCompatCameraOverrides().isRefreshRequested();
    }

    void onActivityRefreshed(@NonNull ActivityRecord activity) {
        // TODO(b/333060789): can we tell that refresh did not happen by observing the activity
        //  state?
        activity.mAppCompatController.getAppCompatCameraOverrides().setIsRefreshRequested(false);
    }

    private boolean shouldRefreshActivity(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig, @NonNull Configuration lastReportedConfig) {
        return mWmService.mLetterboxConfiguration.isCameraCompatRefreshEnabled()
                && activity.mAppCompatController.getAppCompatOverrides()
                    .getAppCompatCameraOverrides().shouldRefreshActivityForCameraCompat()
                && ArrayUtils.find(mEvaluators.toArray(), evaluator ->
                ((Evaluator) evaluator)
                        .shouldRefreshActivity(activity, newConfig, lastReportedConfig)) != null;
    }

    /**
     * Interface for classes that would like to refresh the recently updated activity, based on the
     * configuration change.
     */
    interface Evaluator {
        boolean shouldRefreshActivity(@NonNull ActivityRecord activity,
                @NonNull Configuration newConfig, @NonNull Configuration lastReportedConfig);
    }
}
