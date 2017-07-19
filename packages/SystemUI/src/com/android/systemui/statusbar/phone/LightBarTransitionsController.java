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
import android.util.TimeUtils;

import com.android.systemui.Dumpable;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Class to control all aspects about light bar changes.
 */
public class LightBarTransitionsController implements Dumpable, Callbacks {

    public static final long DEFAULT_TINT_ANIMATION_DURATION = 120;
    private static final String EXTRA_DARK_INTENSITY = "dark_intensity";

    private final Handler mHandler;
    private final DarkIntensityApplier mApplier;
    private final KeyguardMonitor mKeyguardMonitor;

    private boolean mTransitionDeferring;
    private long mTransitionDeferringStartTime;
    private long mTransitionDeferringDuration;
    private boolean mTransitionPending;
    private boolean mTintChangePending;
    private float mPendingDarkIntensity;
    private ValueAnimator mTintAnimator;
    private float mDarkIntensity;
    private float mNextDarkIntensity;

    private final Runnable mTransitionDeferringDoneRunnable = new Runnable() {
        @Override
        public void run() {
            mTransitionDeferring = false;
        }
    };

    public LightBarTransitionsController(Context context, DarkIntensityApplier applier) {
        mApplier = applier;
        mHandler = new Handler();
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        SysUiServiceProvider.getComponent(context, CommandQueue.class)
                .addCallbacks(this);
    }

    public void destroy(Context context) {
        SysUiServiceProvider.getComponent(context, CommandQueue.class)
                .removeCallbacks(this);
    }

    public void saveState(Bundle outState) {
        float intensity = mTintAnimator != null && mTintAnimator.isRunning()
                ?  mNextDarkIntensity : mDarkIntensity;
        outState.putFloat(EXTRA_DARK_INTENSITY, intensity);
    }

    public void restoreState(Bundle savedInstanceState) {
        setIconTintInternal(savedInstanceState.getFloat(EXTRA_DARK_INTENSITY, 0));
    }

    @Override
    public void appTransitionPending(boolean forced) {
        if (mKeyguardMonitor.isKeyguardGoingAway() && !forced) {
            return;
        }
        mTransitionPending = true;
    }

    @Override
    public void appTransitionCancelled() {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
        mTransitionPending = false;
    }

    @Override
    public void appTransitionStarting(long startTime, long duration, boolean forced) {
        if (mKeyguardMonitor.isKeyguardGoingAway() && !forced) {
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
        } else if (mTransitionPending) {
            deferIconTintChange(dark ? 1.0f : 0.0f);
        } else if (mTransitionDeferring) {
            animateIconTint(dark ? 1.0f : 0.0f,
                    Math.max(0, mTransitionDeferringStartTime - SystemClock.uptimeMillis()),
                    mTransitionDeferringDuration);
        } else {
            animateIconTint(dark ? 1.0f : 0.0f, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
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
        if (mTintAnimator != null) {
            mTintAnimator.cancel();
        }
        if (mDarkIntensity == targetDarkIntensity) {
            return;
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
        mApplier.applyDarkIntensity(darkIntensity);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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

    /**
     * Interface to apply a specific dark intensity.
     */
    public interface DarkIntensityApplier {
        void applyDarkIntensity(float darkIntensity);
    }
}
