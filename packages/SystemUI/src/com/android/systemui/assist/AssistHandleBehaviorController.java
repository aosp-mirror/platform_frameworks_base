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

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.NavigationModeController;

import java.util.EnumMap;
import java.util.Map;
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

    private static final long DEFAULT_SHOWN_FREQUENCY_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long DEFAULT_SHOW_AND_GO_DURATION_MS = TimeUnit.SECONDS.toMillis(3);

    /**
     * This is the default behavior that will be used once the system is up. It will be set once the
     * behavior dependencies are available. This ensures proper behavior lifecycle.
     */
    private static final AssistHandleBehavior DEFAULT_BEHAVIOR = AssistHandleBehavior.REMINDER_EXP;

    private final Context mContext;
    private final AssistUtils mAssistUtils;
    private final Handler mHandler;
    private final Runnable mHideHandles = this::hideHandles;
    private final Supplier<ScreenDecorations> mScreenDecorationsSupplier;
    private final Map<AssistHandleBehavior, BehaviorController> mBehaviorMap =
            new EnumMap<>(AssistHandleBehavior.class);

    private boolean mHandlesShowing = false;
    private long mHandlesLastHiddenAt;
    /**
     * This should always be initialized as {@link AssistHandleBehavior#OFF} to ensure proper
     * behavior lifecycle.
     */
    private AssistHandleBehavior mCurrentBehavior = AssistHandleBehavior.OFF;
    private boolean mInGesturalMode;

    AssistHandleBehaviorController(Context context, AssistUtils assistUtils, Handler handler) {
        this(
                context,
                assistUtils,
                handler, () -> SysUiServiceProvider.getComponent(context, ScreenDecorations.class),
                /* testBehavior = */ null);
    }

    @VisibleForTesting
    AssistHandleBehaviorController(
            Context context,
            AssistUtils assistUtils,
            Handler handler,
            Supplier<ScreenDecorations> screenDecorationsSupplier,
            @Nullable BehaviorController testBehavior) {
        mContext = context;
        mAssistUtils = assistUtils;
        mHandler = handler;
        mScreenDecorationsSupplier = screenDecorationsSupplier;

        mBehaviorMap.put(AssistHandleBehavior.OFF, new AssistHandleOffBehavior());
        mBehaviorMap.put(AssistHandleBehavior.LIKE_HOME, new AssistHandleLikeHomeBehavior());
        mBehaviorMap.put(AssistHandleBehavior.REMINDER_EXP, new AssistHandleReminderExpBehavior());
        if (testBehavior != null) {
            mBehaviorMap.put(AssistHandleBehavior.TEST, testBehavior);
        }

        mInGesturalMode = QuickStepContract.isGesturalMode(
                Dependency.get(NavigationModeController.class)
                        .addListener(this::handleNavigationModeChange));

        setBehavior(DeviceConfig.getString(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_BEHAVIOR_MODE,
                DEFAULT_BEHAVIOR.toString()));
        DeviceConfig.addOnPropertyChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                mHandler::post,
                (namespace, name, value) -> {
                    if (SystemUiDeviceConfigFlags.ASSIST_HANDLES_BEHAVIOR_MODE.equals(name)) {
                        setBehavior(value);
                    }
                });
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

    boolean areHandlesShowing() {
        return mHandlesShowing;
    }

    void onAssistantGesturePerformed() {
        mBehaviorMap.get(mCurrentBehavior).onAssistantGesturePerformed();
    }

    void setBehavior(AssistHandleBehavior behavior) {
        if (mCurrentBehavior == behavior) {
            return;
        }

        if (!mBehaviorMap.containsKey(behavior)) {
            Log.e(TAG, "Unsupported behavior requested: " + behavior.toString());
            return;
        }

        if (mInGesturalMode) {
            mBehaviorMap.get(mCurrentBehavior).onModeDeactivated();
            mBehaviorMap.get(behavior).onModeActivated(mContext, /* callbacks = */ this);
        }

        mCurrentBehavior = behavior;
    }

    private void setBehavior(@Nullable String behavior) {
        try {
            setBehavior(AssistHandleBehavior.valueOf(behavior));
        } catch (IllegalArgumentException | NullPointerException e) {
            Log.e(TAG, "Invalid behavior: " + behavior, e);
        }
    }

    private boolean handlesUnblocked(boolean ignoreThreshold) {
        long timeSinceHidden = SystemClock.elapsedRealtime() - mHandlesLastHiddenAt;
        boolean notThrottled = ignoreThreshold || timeSinceHidden > getShownFrequencyThreshold();
        ComponentName assistantComponent =
                mAssistUtils.getAssistComponentForUser(KeyguardUpdateMonitor.getCurrentUser());
        return notThrottled && assistantComponent != null;
    }

    private long getShownFrequencyThreshold() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOWN_FREQUENCY_THRESHOLD_MS,
                DEFAULT_SHOWN_FREQUENCY_THRESHOLD_MS);
    }

    private long getShowAndGoDuration() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DURATION_MS,
                DEFAULT_SHOW_AND_GO_DURATION_MS);
    }

    private void maybeShowHandles(boolean ignoreThreshold) {
        if (mHandlesShowing) {
            return;
        }

        if (handlesUnblocked(ignoreThreshold)) {
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
            mBehaviorMap.get(mCurrentBehavior).onModeActivated(mContext, /* callbacks = */ this);
        } else {
            mBehaviorMap.get(mCurrentBehavior).onModeDeactivated();
            hide();
        }
    }

    @VisibleForTesting
    void setInGesturalModeForTest(boolean inGesturalMode) {
        mInGesturalMode = inGesturalMode;
    }

    interface BehaviorController {
        void onModeActivated(Context context, AssistHandleCallbacks callbacks);
        default void onModeDeactivated() {}
        default void onAssistantGesturePerformed() {}
    }
}
