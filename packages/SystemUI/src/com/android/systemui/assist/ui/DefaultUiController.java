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

package com.android.systemui.assist.ui;

import static com.android.systemui.assist.AssistManager.DISMISS_REASON_INVOCATION_CANCELLED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistManager;

/**
 * Default UiController implementation. Shows white edge lights along the bottom of the phone,
 * expanding from the corners to meet in the center.
 */
public class DefaultUiController implements AssistManager.UiController {

    private static final String TAG = "DefaultUiController";

    private static final long ANIM_DURATION_MS = 200;

    protected final FrameLayout mRoot;
    protected InvocationLightsView mInvocationLightsView;

    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mLayoutParams;
    private final PathInterpolator mProgressInterpolator = new PathInterpolator(.83f, 0, .84f, 1);

    private boolean mAttached = false;
    private boolean mInvocationInProgress = false;
    private float mLastInvocationProgress = 0;

    private ValueAnimator mInvocationAnimator = new ValueAnimator();

    public DefaultUiController(Context context) {
        mRoot = new FrameLayout(context);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, 0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        mLayoutParams.gravity = Gravity.BOTTOM;
        mLayoutParams.setTitle("Assist");

        mInvocationLightsView = (InvocationLightsView)
                LayoutInflater.from(context).inflate(R.layout.invocation_lights, mRoot, false);
        mRoot.addView(mInvocationLightsView);
    }

    @Override // AssistManager.UiController
    public void processBundle(Bundle bundle) {
        Log.e(TAG, "Bundle received but handling is not implemented; ignoring");
    }

    @Override // AssistManager.UiController
    public void onInvocationProgress(int type, float progress) {
        boolean invocationWasInProgress = mInvocationInProgress;

        if (progress == 1) {
            animateInvocationCompletion(type, 0);
        } else if (progress == 0) {
            hide();
        } else {
            if (!mInvocationInProgress) {
                attach();
                mInvocationInProgress = true;
                updateAssistHandleVisibility();
            }
            setProgressInternal(type, progress);
        }
        mLastInvocationProgress = progress;

        logInvocationProgressMetrics(type, progress, invocationWasInProgress);
    }

    @Override // AssistManager.UiController
    public void onGestureCompletion(float velocity) {
        animateInvocationCompletion(AssistManager.INVOCATION_TYPE_GESTURE, velocity);
    }

    @Override // AssistManager.UiController
    public void hide() {
        detach();
        if (mInvocationAnimator.isRunning()) {
            mInvocationAnimator.cancel();
        }
        mInvocationLightsView.hide();
        mInvocationInProgress = false;
        updateAssistHandleVisibility();
    }

    protected static void logInvocationProgressMetrics(
            int type, float progress, boolean invocationWasInProgress) {
        // Logs assistant invocation start.
        if (!invocationWasInProgress && progress > 0.f) {
            MetricsLogger.action(new LogMaker(MetricsEvent.ASSISTANT)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(Dependency.get(AssistManager.class).toLoggingSubType(type)));
        }
        // Logs assistant invocation cancelled.
        if (invocationWasInProgress && progress == 0f) {
            MetricsLogger.action(new LogMaker(MetricsEvent.ASSISTANT)
                    .setType(MetricsEvent.TYPE_DISMISS)
                    .setSubtype(DISMISS_REASON_INVOCATION_CANCELLED));
        }
    }

    private void updateAssistHandleVisibility() {
        ScreenDecorations decorations = SysUiServiceProvider.getComponent(mRoot.getContext(),
                ScreenDecorations.class);
        decorations.setAssistHintBlocked(mInvocationInProgress);
    }

    private void attach() {
        if (!mAttached) {
            mWindowManager.addView(mRoot, mLayoutParams);
            mAttached = true;
        }
    }

    private void detach() {
        if (mAttached) {
            mWindowManager.removeViewImmediate(mRoot);
            mAttached = false;
        }
    }

    private void setProgressInternal(int type, float progress) {
        mInvocationLightsView.onInvocationProgress(
                mProgressInterpolator.getInterpolation(progress));
    }

    private void animateInvocationCompletion(int type, float velocity) {
        mInvocationAnimator = ValueAnimator.ofFloat(mLastInvocationProgress, 1);
        mInvocationAnimator.setStartDelay(1);
        mInvocationAnimator.setDuration(ANIM_DURATION_MS);
        mInvocationAnimator.addUpdateListener(
                animation -> setProgressInternal(type, (float) animation.getAnimatedValue()));
        mInvocationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mInvocationInProgress = false;
                mLastInvocationProgress = 0;
                hide();
            }
        });
        mInvocationAnimator.start();
    }
}
