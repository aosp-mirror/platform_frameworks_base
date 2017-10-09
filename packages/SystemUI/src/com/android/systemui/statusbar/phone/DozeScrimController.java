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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;

/**
 * Controller which handles all the doze animations of the scrims.
 */
public class DozeScrimController {
    private static final String TAG = "DozeScrimController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final DozeParameters mDozeParameters;
    private final Handler mHandler = new Handler();
    private final ScrimController mScrimController;

    private final Context mContext;

    private boolean mDozing;
    private DozeHost.PulseCallback mPulseCallback;
    private int mPulseReason;
    private Animator mInFrontAnimator;
    private Animator mBehindAnimator;
    private float mInFrontTarget;
    private float mBehindTarget;
    private boolean mDozingAborted;
    private boolean mWakeAndUnlocking;
    private boolean mFullyPulsing;

    private float mAodFrontScrimOpacity = 0;
    private Runnable mSetDozeInFrontAlphaDelayed;

    public DozeScrimController(ScrimController scrimController, Context context) {
        mContext = context;
        mScrimController = scrimController;
        mDozeParameters = new DozeParameters(context);
    }

    public void setDozing(boolean dozing, boolean animate) {
        if (mDozing == dozing) return;
        mDozing = dozing;
        mWakeAndUnlocking = false;
        if (mDozing) {
            mDozingAborted = false;
            abortAnimations();
            mScrimController.setDozeBehindAlpha(1f);
            setDozeInFrontAlpha(mDozeParameters.getAlwaysOn() ? mAodFrontScrimOpacity : 1f);
        } else {
            cancelPulsing();
            if (animate) {
                startScrimAnimation(false /* inFront */, 0f /* target */,
                        NotificationPanelView.DOZE_ANIMATION_DURATION,
                        Interpolators.LINEAR_OUT_SLOW_IN);
                startScrimAnimation(true /* inFront */, 0f /* target */,
                        NotificationPanelView.DOZE_ANIMATION_DURATION,
                        Interpolators.LINEAR_OUT_SLOW_IN);
            } else {
                abortAnimations();
                mScrimController.setDozeBehindAlpha(0f);
                setDozeInFrontAlpha(0f);
            }
        }
    }

    /**
     * Set the opacity of the front scrim when showing AOD1
     *
     * Used to emulate lower brightness values than the hardware supports natively.
     */
    public void setAodDimmingScrim(float scrimOpacity) {
        mAodFrontScrimOpacity = scrimOpacity;
        if (mDozing && !isPulsing() && !mDozingAborted && !mWakeAndUnlocking
                && mDozeParameters.getAlwaysOn()) {
            setDozeInFrontAlpha(mAodFrontScrimOpacity);
        }
    }

    public void setWakeAndUnlocking() {
        // Immediately abort the doze scrims in case of wake-and-unlock
        // for pulsing so the Keyguard fade-out animation scrim can take over.
        if (!mWakeAndUnlocking) {
            mWakeAndUnlocking = true;
            mScrimController.setDozeBehindAlpha(0f);
            setDozeInFrontAlpha(0f);
        }
    }

    /** When dozing, fade screen contents in and out using the front scrim. */
    public void pulse(@NonNull DozeHost.PulseCallback callback, int reason) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        if (!mDozing || mPulseCallback != null) {
            // Pulse suppressed.
            callback.onPulseFinished();
            return;
        }

        // Begin pulse.  Note that it's very important that the pulse finished callback
        // be invoked when we're done so that the caller can drop the pulse wakelock.
        mPulseCallback = callback;
        mPulseReason = reason;
        setDozeInFrontAlpha(1f);
        mHandler.post(mPulseIn);
    }

    /**
     * Aborts pulsing immediately.
     */
    public void abortPulsing() {
        cancelPulsing();
        if (mDozing && !mWakeAndUnlocking) {
            mScrimController.setDozeBehindAlpha(1f);
            setDozeInFrontAlpha(mDozeParameters.getAlwaysOn() && !mDozingAborted
                    ? mAodFrontScrimOpacity : 1f);
        }
    }

    /**
     * Aborts dozing immediately.
     */
    public void abortDoze() {
        mDozingAborted = true;
        abortPulsing();
    }

    public void pulseOutNow() {
        if (mPulseCallback != null && mFullyPulsing) {
            mPulseOut.run();
        }
    }

    public void onScreenTurnedOn() {
        if (isPulsing()) {
            final boolean pickupOrDoubleTap = mPulseReason == DozeLog.PULSE_REASON_SENSOR_PICKUP
                    || mPulseReason == DozeLog.PULSE_REASON_SENSOR_DOUBLE_TAP;
            startScrimAnimation(true /* inFront */, 0f,
                    mDozeParameters.getPulseInDuration(pickupOrDoubleTap),
                    pickupOrDoubleTap ? Interpolators.LINEAR_OUT_SLOW_IN : Interpolators.ALPHA_OUT,
                    mPulseInFinished);
        }
    }

    public boolean isPulsing() {
        return mPulseCallback != null;
    }

    public boolean isDozing() {
        return mDozing;
    }

    public void extendPulse() {
        mHandler.removeCallbacks(mPulseOut);
    }

    private void cancelPulsing() {
        if (DEBUG) Log.d(TAG, "Cancel pulsing");

        if (mPulseCallback != null) {
            mFullyPulsing = false;
            mHandler.removeCallbacks(mPulseIn);
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            pulseFinished();
        }
    }

    private void pulseStarted() {
        if (mPulseCallback != null) {
            mPulseCallback.onPulseStarted();
        }
    }

    private void pulseFinished() {
        if (mPulseCallback != null) {
            mPulseCallback.onPulseFinished();
            mPulseCallback = null;
        }
    }

    private void abortAnimations() {
        if (mInFrontAnimator != null) {
            mInFrontAnimator.cancel();
        }
        if (mBehindAnimator != null) {
            mBehindAnimator.cancel();
        }
    }

    private void startScrimAnimation(final boolean inFront, float target, long duration,
            Interpolator interpolator) {
        startScrimAnimation(inFront, target, duration, interpolator, null /* endRunnable */);
    }

    private void startScrimAnimation(final boolean inFront, float target, long duration,
            Interpolator interpolator, final Runnable endRunnable) {
        Animator current = getCurrentAnimator(inFront);
        if (current != null) {
            float currentTarget = getCurrentTarget(inFront);
            if (currentTarget == target) {
                return;
            }
            current.cancel();
        }
        ValueAnimator anim = ValueAnimator.ofFloat(getDozeAlpha(inFront), target);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                setDozeAlpha(inFront, value);
            }
        });
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setCurrentAnimator(inFront, null);
                if (endRunnable != null) {
                    endRunnable.run();
                }
            }
        });
        anim.start();
        setCurrentAnimator(inFront, anim);
        setCurrentTarget(inFront, target);
    }

    private float getCurrentTarget(boolean inFront) {
        return inFront ? mInFrontTarget : mBehindTarget;
    }

    private void setCurrentTarget(boolean inFront, float target) {
        if (inFront) {
            mInFrontTarget = target;
        } else {
            mBehindTarget = target;
        }
    }

    private Animator getCurrentAnimator(boolean inFront) {
        return inFront ? mInFrontAnimator : mBehindAnimator;
    }

    private void setCurrentAnimator(boolean inFront, Animator animator) {
        if (inFront) {
            mInFrontAnimator = animator;
        } else {
            mBehindAnimator = animator;
        }
    }

    private void setDozeAlpha(boolean inFront, float alpha) {
        if (mWakeAndUnlocking) {
            return;
        }
        if (inFront) {
            mScrimController.setDozeInFrontAlpha(alpha);
        } else {
            mScrimController.setDozeBehindAlpha(alpha);
        }
    }

    private float getDozeAlpha(boolean inFront) {
        return inFront
                ? mScrimController.getDozeInFrontAlpha()
                : mScrimController.getDozeBehindAlpha();
    }

    private void setDozeInFrontAlpha(float opacity) {
        setDozeInFrontAlphaDelayed(opacity, 0 /* delay */);

    }

    private void setDozeInFrontAlphaDelayed(float opacity, long delayMs) {
        if (mSetDozeInFrontAlphaDelayed != null) {
            mHandler.removeCallbacks(mSetDozeInFrontAlphaDelayed);
            mSetDozeInFrontAlphaDelayed = null;
        }
        if (delayMs <= 0) {
            mScrimController.setDozeInFrontAlpha(opacity);
        } else {
            mHandler.postDelayed(mSetDozeInFrontAlphaDelayed = () -> {
                setDozeInFrontAlpha(opacity);
            }, delayMs);
        }
    }

    private final Runnable mPulseIn = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Pulse in, mDozing=" + mDozing + " mPulseReason="
                    + DozeLog.pulseReasonToString(mPulseReason));
            if (!mDozing) return;
            DozeLog.tracePulseStart(mPulseReason);

            // Signal that the pulse is ready to turn the screen on and draw.
            pulseStarted();
        }
    };

    private final Runnable mPulseInFinished = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Pulse in finished, mDozing=" + mDozing);
            if (!mDozing) return;
            mHandler.postDelayed(mPulseOut, mDozeParameters.getPulseVisibleDuration());
            mHandler.postDelayed(mPulseOutExtended,
                    mDozeParameters.getPulseVisibleDurationExtended());
            mFullyPulsing = true;
        }
    };

    private final Runnable mPulseOutExtended = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(mPulseOut);
            mPulseOut.run();
        }
    };

    private final Runnable mPulseOut = new Runnable() {
        @Override
        public void run() {
            mFullyPulsing = false;
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            if (DEBUG) Log.d(TAG, "Pulse out, mDozing=" + mDozing);
            if (!mDozing) return;
            startScrimAnimation(true /* inFront */, 1,
                    mDozeParameters.getPulseOutDuration(),
                    Interpolators.ALPHA_IN, mPulseOutFinishing);
        }
    };

    private final Runnable mPulseOutFinishing = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Pulse out finished");
            DozeLog.tracePulseFinish();
            if (mDozeParameters.getAlwaysOn() && mDozing) {
                // Setting power states can block rendering. For AOD, delay finishing the pulse and
                // setting the power state until the fully black scrim had time to hit the
                // framebuffer.
                mHandler.postDelayed(mPulseOutFinished, 30);
            } else {
                mPulseOutFinished.run();
            }
        }
    };

    private final Runnable mPulseOutFinished = new Runnable() {
        @Override
        public void run() {
            // Signal that the pulse is all finished so we can turn the screen off now.
            DozeScrimController.this.pulseFinished();
            if (mDozeParameters.getAlwaysOn()) {
                // Setting power states can happen after we push out the frame. Make sure we
                // stay fully opaque until the power state request reaches the lower levels.
                setDozeInFrontAlphaDelayed(mAodFrontScrimOpacity, 100);
            }
        }
    };
}
