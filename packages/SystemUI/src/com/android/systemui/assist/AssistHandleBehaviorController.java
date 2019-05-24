/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.NavigationModeController;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A class for managing Assistant handle logic.
 *
 * Controls when visual handles for Assistant gesture affordance should be shown or hidden using an
 * {@link AssistHandleBehavior}.
 */
public final class AssistHandleBehaviorController implements AssistHandleCallbacks {

    private static final String TAG = "AssistHandleBehavior";
    private static final boolean IS_DEBUG_DEVICE =
            Build.TYPE.toLowerCase(Locale.ROOT).contains("debug")
                    || Build.TYPE.toLowerCase(Locale.ROOT).equals("eng");

    private static final String SHOWN_FREQUENCY_THRESHOLD_KEY =
            "ASSIST_HANDLES_SHOWN_FREQUENCY_THRESHOLD_MS";
    private static final long DEFAULT_SHOWN_FREQUENCY_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(10);
    private static final String SHOW_AND_GO_DURATION_KEY = "ASSIST_HANDLES_SHOW_AND_GO_DURATION_MS";
    private static final long DEFAULT_SHOW_AND_GO_DURATION_MS = TimeUnit.SECONDS.toMillis(3);
    private static final String BEHAVIOR_KEY = "behavior";
    private static final String SET_BEHAVIOR_ACTION =
            "com.android.systemui.SET_ASSIST_HANDLE_BEHAVIOR";

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mHideHandles = this::hideHandles;
    private final Supplier<ScreenDecorations> mScreenDecorationsSupplier;

    private boolean mHandlesShowing = false;
    private long mHandlesLastHiddenAt;
    private AssistHandleBehavior mCurrentBehavior = AssistHandleBehavior.OFF;
    private boolean mInGesturalMode;

    AssistHandleBehaviorController(Context context, Handler handler) {
        this(context, handler, () ->
                SysUiServiceProvider.getComponent(context, ScreenDecorations.class));
    }

    @VisibleForTesting
    AssistHandleBehaviorController(
            Context context,
            Handler handler,
            Supplier<ScreenDecorations> screenDecorationsSupplier) {
        mContext = context;
        mHandler = handler;
        mScreenDecorationsSupplier = screenDecorationsSupplier;

        mInGesturalMode = QuickStepContract.isGesturalMode(
                Dependency.get(NavigationModeController.class)
                        .addListener(this::handleNavigationModeChange));

        if (IS_DEBUG_DEVICE) {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String behaviorString = intent.getExtras().getString(BEHAVIOR_KEY);
                    try {
                        setBehavior(AssistHandleBehavior.valueOf(behaviorString));
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Invalid behavior identifier: " + behaviorString);
                    }
                }
            }, new IntentFilter(SET_BEHAVIOR_ACTION));
        }
    }

    @Override
    public void hide() {
        mHandler.removeCallbacks(mHideHandles);
        mHandler.post(mHideHandles);
    }

    @Override
    public void showAndGo() {
        mHandler.removeCallbacks(mHideHandles);
        mHandler.post(() -> {
            maybeShowHandles(/* ignoreThreshold = */ false);
            mHandler.postDelayed(mHideHandles, getShowAndGoDuration());
        });
    }

    @Override
    public void showAndStay() {
        mHandler.removeCallbacks(mHideHandles);
        mHandler.post(() -> maybeShowHandles(/* ignoreThreshold = */ true));
    }

    void setBehavior(AssistHandleBehavior behavior) {
        if (mCurrentBehavior == behavior) {
            return;
        }

        if (mInGesturalMode) {
            mCurrentBehavior.getController().onModeDeactivated();
            behavior.getController().onModeActivated(mContext, this);
        }

        mCurrentBehavior = behavior;
    }

    private static long getShownFrequencyThreshold() {
        return SystemProperties.getLong(
                SHOWN_FREQUENCY_THRESHOLD_KEY, DEFAULT_SHOWN_FREQUENCY_THRESHOLD_MS);
    }

    private static long getShowAndGoDuration() {
        return SystemProperties.getLong(SHOW_AND_GO_DURATION_KEY, DEFAULT_SHOW_AND_GO_DURATION_MS);
    }

    private void maybeShowHandles(boolean ignoreThreshold) {
        if (mHandlesShowing) {
            return;
        }

        long timeSinceHidden = SystemClock.elapsedRealtime() - mHandlesLastHiddenAt;
        if (ignoreThreshold || timeSinceHidden > getShownFrequencyThreshold()) {
            mHandlesShowing = true;
            ScreenDecorations screenDecorations = mScreenDecorationsSupplier.get();
            if (screenDecorations == null) {
                Log.w(TAG, "Couldn't show handles, ScreenDecorations unavailable");
            } else {
                screenDecorations.setAssistHintVisible(true);
            }
        }
    }

    private void hideHandles() {
        if (!mHandlesShowing) {
            return;
        }

        mHandlesShowing = false;
        mHandlesLastHiddenAt = SystemClock.elapsedRealtime();
        ScreenDecorations screenDecorations = mScreenDecorationsSupplier.get();
        if (screenDecorations == null) {
            Log.w(TAG, "Couldn't hide handles, ScreenDecorations unavailable");
        } else {
            screenDecorations.setAssistHintVisible(false);
        }
    }

    private void handleNavigationModeChange(int navigationMode) {
        boolean inGesturalMode = QuickStepContract.isGesturalMode(navigationMode);
        if (mInGesturalMode == inGesturalMode) {
            return;
        }

        mInGesturalMode = inGesturalMode;
        if (mInGesturalMode) {
            mCurrentBehavior.getController().onModeActivated(mContext, this);
        } else {
            mCurrentBehavior.getController().onModeDeactivated();
            hide();
        }
    }

    @VisibleForTesting
    void setInGesturalModeForTest(boolean inGesturalMode) {
        mInGesturalMode = inGesturalMode;
    }

    interface BehaviorController {
        void onModeActivated(Context context, AssistHandleCallbacks callbacks);
        void onModeDeactivated();
    }
}
