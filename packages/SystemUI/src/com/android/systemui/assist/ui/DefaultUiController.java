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
import static com.android.systemui.assist.AssistManager.INVOCATION_TYPE_GESTURE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.metrics.LogMaker;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.assist.AssistLogger;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistantSessionEvent;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.res.R;

import dagger.Lazy;

import java.util.Locale;

import javax.inject.Inject;

/**
 * Default UiController implementation. Shows white edge lights along the bottom of the phone,
 * expanding from the corners to meet in the center.
 */
@SysUISingleton
public class DefaultUiController implements AssistManager.UiController {

    private static final String TAG = "DefaultUiController";

    private static final long ANIM_DURATION_MS = 200;

    private static final boolean VERBOSE = Build.TYPE.toLowerCase(Locale.ROOT).contains("debug")
            || Build.TYPE.toLowerCase(Locale.ROOT).equals("eng");

    protected final FrameLayout mRoot;
    protected InvocationLightsView mInvocationLightsView;
    protected final AssistLogger mAssistLogger;

    private final ViewCaptureAwareWindowManager mWindowManager;
    private final MetricsLogger mMetricsLogger;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final WindowManager.LayoutParams mLayoutParams;
    private final PathInterpolator mProgressInterpolator = new PathInterpolator(.83f, 0, .84f, 1);

    private boolean mAttached = false;
    private boolean mInvocationInProgress = false;
    private float mLastInvocationProgress = 0;

    private ValueAnimator mInvocationAnimator = new ValueAnimator();

    @Inject
    public DefaultUiController(Context context, AssistLogger assistLogger,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager,
            MetricsLogger metricsLogger, Lazy<AssistManager> assistManagerLazy,
            NavigationBarController navigationBarController) {
        mAssistLogger = assistLogger;
        mRoot = new FrameLayout(context);
        mWindowManager = viewCaptureAwareWindowManager;
        mMetricsLogger = metricsLogger;
        mAssistManagerLazy = assistManagerLazy;

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
        mLayoutParams.setFitInsetsTypes(0 /* types */);
        mLayoutParams.setTitle("Assist");

        mInvocationLightsView = (InvocationLightsView)
                LayoutInflater.from(context).inflate(R.layout.invocation_lights, mRoot, false);
        mInvocationLightsView.setNavigationBarController(navigationBarController);
        mRoot.addView(mInvocationLightsView);
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
            }
            setProgressInternal(type, progress);
        }
        mLastInvocationProgress = progress;

        logInvocationProgressMetrics(type, progress, invocationWasInProgress);
    }

    @Override // AssistManager.UiController
    public void onGestureCompletion(float velocity) {
        animateInvocationCompletion(AssistManager.INVOCATION_TYPE_GESTURE, velocity);
        logInvocationProgressMetrics(INVOCATION_TYPE_GESTURE, 1, mInvocationInProgress);
    }

    @Override // AssistManager.UiController
    public void hide() {
        detach();
        if (mInvocationAnimator.isRunning()) {
            mInvocationAnimator.cancel();
        }
        mInvocationLightsView.hide();
        mInvocationInProgress = false;
    }

    protected void logInvocationProgressMetrics(
            int type, float progress, boolean invocationWasInProgress) {
        // Logs assistant invocation start.
        if (progress == 1f) {
            if (VERBOSE) {
                Log.v(TAG, "Invocation complete: type=" + type);
            }
        }
        if (!invocationWasInProgress && progress > 0.f) {
            if (VERBOSE) {
                Log.v(TAG, "Invocation started: type=" + type);
            }
            mAssistLogger.reportAssistantInvocationEventFromLegacy(
                    type,
                    /* isInvocationComplete = */ false,
                    /* assistantComponent = */ null,
                    /* legacyDeviceState = */ null);
            mMetricsLogger.write(new LogMaker(MetricsEvent.ASSISTANT)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(mAssistManagerLazy.get().toLoggingSubType(type)));
        }
        // Logs assistant invocation cancelled.
        if ((mInvocationAnimator == null || !mInvocationAnimator.isRunning())
                && invocationWasInProgress && progress == 0f) {
            if (VERBOSE) {
                Log.v(TAG, "Invocation cancelled: type=" + type);
            }
            mAssistLogger.reportAssistantSessionEvent(
                    AssistantSessionEvent.ASSISTANT_SESSION_INVOCATION_CANCELLED);
            MetricsLogger.action(new LogMaker(MetricsEvent.ASSISTANT)
                    .setType(MetricsEvent.TYPE_DISMISS)
                    .setSubtype(DISMISS_REASON_INVOCATION_CANCELLED));
        }
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
