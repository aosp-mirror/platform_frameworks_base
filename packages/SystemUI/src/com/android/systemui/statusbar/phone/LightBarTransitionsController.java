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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.MathUtils;
import android.util.TimeUtils;

import com.android.systemui.Dumpable;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/**
 * Class to control all aspects about light bar changes.
 */
public class LightBarTransitionsController implements Dumpable, Callbacks,
        StatusBarStateController.StateListener {

    public static final int DEFAULT_TINT_ANIMATION_DURATION = 120;
    private static final String EXTRA_DARK_INTENSITY = "dark_intensity";

    private final Handler mHandler;
    private final DarkIntensityApplier mApplier;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private final CommandQueue mCommandQueue;

    private boolean mTransitionDeferring;
    private long mTransitionDeferringStartTime;
    private long mTransitionDeferringDuration;
    private boolean mTransitionPending;
    private boolean mTintChangePending;
    private float mPendingDarkIntensity;
    private ValueAnimator mTintAnimator;
    private float mDarkIntensity;
    private float mNextDarkIntensity;
    private float mDozeAmount;
    private int mDisplayId;
    private final Runnable mTransitionDeferringDoneRunnable = new Runnable() {
        @Override
        public void run() {
            mTransitionDeferring = false;
        }
    };

    private final Context mContext;
    private Boolean mOverrideIconTintForNavMode;

    @AssistedInject
    public LightBarTransitionsController(
            Context context,
            @Assisted DarkIntensityApplier applier,
            CommandQueue commandQueue,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController) {
        mApplier = applier;
        mHandler = new Handler();
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
        mDozeAmount = mStatusBarStateController.getDozeAmount();
        mContext = context;
        mDisplayId = mContext.getDisplayId();
    }

    public void destroy(Context context) {
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
    }

    public void saveState(Bundle outState) {
        float intensity = mTintAnimator != null && mTintAnimator.isRunning()
                ?  mNextDarkIntensity : mDarkIntensity;
        outState.putFloat(EXTRA_DARK_INTENSITY, intensity);
    }

    public void restoreState(Bundle savedInstanceState) {
        setIconTintInternal(savedInstanceState.getFloat(EXTRA_DARK_INTENSITY, 0));
        mNextDarkIntensity = mDarkIntensity;
    }

    @Override
    public void appTransitionPending(int displayId, boolean forced) {
        if (mDisplayId != displayId || mKeyguardStateController.isKeyguardGoingAway() && !forced) {
            return;
        }
        mTransitionPending = true;
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        if (mDisplayId != displayId) {
            return;
        }
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity, 0 /* delay */,
                    mApplier.getTintAnimationDuration());
        }
        mTransitionPending = false;
    }

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        if (mDisplayId != displayId || mKeyguardStateController.isKeyguardGoingAway() && !forced) {
            return;
        }
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity,
                    Math.max(0, startTime - SystemClock.uptimeMillis()),
                    duration);

        } else if (mTransitionPending) {

            // If we don't have a pending tint change yet, the change might come in the future until
            // startTime is reached.
            mTransitionDeferring = true;
            mTransitionDeferringStartTime = startTime;
            mTransitionDeferringDuration = duration;
            mHandler.removeCallbacks(mTransitionDeferringDoneRunnable);
            mHandler.postAtTime(mTransitionDeferringDoneRunnable, startTime);
        }
        mTransitionPending = false;
    }

    public void setIconsDark(boolean dark, boolean animate) {
        if (!animate) {
            setIconTintInternal(dark ? 1.0f : 0.0f);
            mNextDarkIntensity = dark ? 1.0f : 0.0f;
        } else if (mTransitionPending) {
            deferIconTintChange(dark ? 1.0f : 0.0f);
        } else if (mTransitionDeferring) {
            animateIconTint(dark ? 1.0f : 0.0f,
                    Math.max(0, mTransitionDeferringStartTime - SystemClock.uptimeMillis()),
                    mTransitionDeferringDuration);
        } else {
            animateIconTint(dark ? 1.0f : 0.0f, 0 /* delay */, mApplier.getTintAnimationDuration());
        }
    }

    public float getCurrentDarkIntensity() {
        return mDarkIntensity;
    }

    private void deferIconTintChange(float darkIntensity) {
        if (mTintChangePending && darkIntensity == mPendingDarkIntensity) {
            return;
        }
        mTintChangePending = true;
        mPendingDarkIntensity = darkIntensity;
    }

    private void animateIconTint(float targetDarkIntensity, long delay,
            long duration) {
        if (mNextDarkIntensity == targetDarkIntensity) {
            return;
        }
        if (mTintAnimator != null) {
            mTintAnimator.cancel();
        }
        mNextDarkIntensity = targetDarkIntensity;
        mTintAnimator = ValueAnimator.ofFloat(mDarkIntensity, targetDarkIntensity);
        mTintAnimator.addUpdateListener(
                animation -> setIconTintInternal((Float) animation.getAnimatedValue()));
        mTintAnimator.setDuration(duration);
        mTintAnimator.setStartDelay(delay);
        mTintAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mTintAnimator.start();
    }

    private void setIconTintInternal(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        dispatchDark();
    }

    private void dispatchDark() {
        mApplier.applyDarkIntensity(MathUtils.lerp(mDarkIntensity, 0f, mDozeAmount));
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.print("  mTransitionDeferring="); pw.print(mTransitionDeferring);
        if (mTransitionDeferring) {
            pw.println();
            pw.print("   mTransitionDeferringStartTime=");
            pw.println(TimeUtils.formatUptime(mTransitionDeferringStartTime));

            pw.print("   mTransitionDeferringDuration=");
            TimeUtils.formatDuration(mTransitionDeferringDuration, pw);
            pw.println();
        }
        pw.print("  mTransitionPending="); pw.print(mTransitionPending);
        pw.print(" mTintChangePending="); pw.println(mTintChangePending);

        pw.print("  mPendingDarkIntensity="); pw.print(mPendingDarkIntensity);
        pw.print(" mDarkIntensity="); pw.print(mDarkIntensity);
        pw.print(" mNextDarkIntensity="); pw.println(mNextDarkIntensity);
    }

    @Override
    public void onStateChanged(int newState) { }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mDozeAmount = eased;
        dispatchDark();
    }

    /**
     * Specify an override value to return for {@link #overrideIconTintForNavMode(boolean)}.
     */
    public void overrideIconTintForNavMode(boolean overrideValue) {
        mOverrideIconTintForNavMode = overrideValue;
    }
    /**
     * Return whether to use the tint calculated in this class for nav icons.
     */
    public boolean supportsIconTintForNavMode(int navigationMode) {
        // In gesture mode, we already do region sampling to update tint based on content beneath.
        return mOverrideIconTintForNavMode != null
                ? mOverrideIconTintForNavMode
                : !QuickStepContract.isGesturalMode(navigationMode);
    }

    /**
     * Interface to apply a specific dark intensity.
     */
    public interface DarkIntensityApplier {
        void applyDarkIntensity(float darkIntensity);
        int getTintAnimationDuration();
    }

    /** Injectable factory for construction a LightBarTransitionsController. */
    @AssistedFactory
    public interface Factory {
        /** */
        LightBarTransitionsController create(DarkIntensityApplier darkIntensityApplier);
    }
}
