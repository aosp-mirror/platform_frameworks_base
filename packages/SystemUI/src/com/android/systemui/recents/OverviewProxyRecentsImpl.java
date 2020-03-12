/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * An implementation of the Recents interface which proxies to the OverviewProxyService.
 */
@Singleton
public class OverviewProxyRecentsImpl implements RecentsImplementation {

    private final static String TAG = "OverviewProxyRecentsImpl";
    @Nullable
    private final Lazy<StatusBar> mStatusBarLazy;
    private final Optional<Divider> mDividerOptional;

    private Context mContext;
    private Handler mHandler;
    private TrustManager mTrustManager;
    private OverviewProxyService mOverviewProxyService;

    private TaskStackChangeListener mListener = new TaskStackChangeListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask) {
            if (task.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                return;
            }

            if (homeTaskVisible) {
                showRecentApps(false /* triggeredFromAltTab */);
            }
        }
    };

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(Optional<Lazy<StatusBar>> statusBarLazy,
            Optional<Divider> dividerOptional) {
        mStatusBarLazy = statusBarLazy.orElse(null);
        mDividerOptional = dividerOptional;
    }

    @Override
    public void onStart(Context context) {
        mContext = context;
        mHandler = new Handler();
        mTrustManager = (TrustManager) context.getSystemService(Context.TRUST_SERVICE);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mListener);
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewShown(triggeredFromAltTab);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        } else {
            // Do nothing
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        } else {
            // Do nothing
        }
    }

    @Override
    public void toggleRecentApps() {
        // If connected to launcher service, let it handle the toggle logic
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mOverviewProxyService.getProxy() != null) {
                        mOverviewProxyService.getProxy().onOverviewToggle();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            if (mStatusBarLazy != null && mStatusBarLazy.get().isKeyguardShowing()) {
                mStatusBarLazy.get().executeRunnableDismissingKeyguard(() -> {
                        // Flush trustmanager before checking device locked per user
                        mTrustManager.reportKeyguardShowingChanged();
                        mHandler.post(toggleRecents);
                    }, null,  true /* dismissShade */, false /* afterKeyguardGone */,
                    true /* deferred */);
            } else {
                toggleRecents.run();
            }
            return;
        } else {
            // Do nothing
        }
    }

    @Override
    public boolean splitPrimaryTask(int stackCreateMode, Rect initialBounds,
            int metricsDockAction) {
        Point realSize = new Point();
        if (initialBounds == null) {
            mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                    .getRealSize(realSize);
            initialBounds = new Rect(0, 0, realSize.x, realSize.y);
        }

        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        final int activityType = runningTask != null
                ? runningTask.configuration.windowConfiguration.getActivityType()
                : ACTIVITY_TYPE_UNDEFINED;
        boolean screenPinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        boolean isRunningTaskInHomeOrRecentsStack =
                activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS;
        if (runningTask != null && !isRunningTaskInHomeOrRecentsStack && !screenPinningActive) {
            if (runningTask.supportsSplitScreenMultiWindow) {
                if (ActivityManagerWrapper.getInstance().setTaskWindowingModeSplitScreenPrimary(
                        runningTask.id, stackCreateMode, initialBounds)) {
                    mDividerOptional.ifPresent(Divider::onDockedTopTask);

                    // The overview service is handling split screen, so just skip the wait for the
                    // first draw and notify the divider to start animating now
                    mDividerOptional.ifPresent(Divider::onRecentsDrawn);

                    return true;
                }
            } else {
                Toast.makeText(mContext, R.string.dock_non_resizeble_failed_to_dock_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        return false;
    }
}
