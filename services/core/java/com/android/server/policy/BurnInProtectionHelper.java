/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.policy;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;
import android.view.animation.LinearInterpolator;

import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

public class BurnInProtectionHelper implements DisplayManager.DisplayListener,
        Animator.AnimatorListener, ValueAnimator.AnimatorUpdateListener {
    private static final String TAG = "BurnInProtection";

    // Default value when max burnin radius is not set.
    public static final int BURN_IN_MAX_RADIUS_DEFAULT = -1;

    private static final long BURNIN_PROTECTION_FIRST_WAKEUP_INTERVAL_MS =
            TimeUnit.MINUTES.toMillis(1);
    private static final long BURNIN_PROTECTION_SUBSEQUENT_WAKEUP_INTERVAL_MS =
            TimeUnit.MINUTES.toMillis(2);
    private static final long BURNIN_PROTECTION_MINIMAL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10);

    private static final boolean DEBUG = false;

    private static final String ACTION_BURN_IN_PROTECTION =
            "android.internal.policy.action.BURN_IN_PROTECTION";

    private static final int BURN_IN_SHIFT_STEP = 2;
    private static final long CENTERING_ANIMATION_DURATION_MS = 100;
    private final ValueAnimator mCenteringAnimator;

    private boolean mBurnInProtectionActive;
    private boolean mFirstUpdate;

    private final int mMinHorizontalBurnInOffset;
    private final int mMaxHorizontalBurnInOffset;
    private final int mMinVerticalBurnInOffset;
    private final int mMaxVerticalBurnInOffset;

    private final int mBurnInRadiusMaxSquared;

    private int mLastBurnInXOffset = 0;
    /* 1 means increasing, -1 means decreasing */
    private int mXOffsetDirection = 1;
    private int mLastBurnInYOffset = 0;
    /* 1 means increasing, -1 means decreasing */
    private int mYOffsetDirection = 1;

    private int mAppliedBurnInXOffset = 0;
    private int mAppliedBurnInYOffset = 0;

    private final AlarmManager mAlarmManager;
    private final PendingIntent mBurnInProtectionIntent;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final Display mDisplay;

    private BroadcastReceiver mBurnInProtectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "onReceive " + intent);
            }
            updateBurnInProtection();
        }
    };

    public BurnInProtectionHelper(Context context, int minHorizontalOffset,
            int maxHorizontalOffset, int minVerticalOffset, int maxVerticalOffset,
            int maxOffsetRadius) {
        mMinHorizontalBurnInOffset = minHorizontalOffset;
        mMaxHorizontalBurnInOffset = maxHorizontalOffset;
        mMinVerticalBurnInOffset = minVerticalOffset;
        mMaxVerticalBurnInOffset = maxVerticalOffset;
        if (maxOffsetRadius != BURN_IN_MAX_RADIUS_DEFAULT) {
            mBurnInRadiusMaxSquared = maxOffsetRadius * maxOffsetRadius;
        } else {
            mBurnInRadiusMaxSquared = BURN_IN_MAX_RADIUS_DEFAULT;
        }

        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        context.registerReceiver(mBurnInProtectionReceiver,
                new IntentFilter(ACTION_BURN_IN_PROTECTION));
        Intent intent = new Intent(ACTION_BURN_IN_PROTECTION);
        intent.setPackage(context.getPackageName());
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mBurnInProtectionIntent = PendingIntent.getBroadcast(context, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        displayManager.registerDisplayListener(this, null /* handler */);

        mCenteringAnimator = ValueAnimator.ofFloat(1f, 0f);
        mCenteringAnimator.setDuration(CENTERING_ANIMATION_DURATION_MS);
        mCenteringAnimator.setInterpolator(new LinearInterpolator());
        mCenteringAnimator.addListener(this);
        mCenteringAnimator.addUpdateListener(this);
    }

    public void startBurnInProtection() {
        if (!mBurnInProtectionActive) {
            mBurnInProtectionActive = true;
            mFirstUpdate = true;
            mCenteringAnimator.cancel();
            updateBurnInProtection();
        }
    }

    private void updateBurnInProtection() {
        if (mBurnInProtectionActive) {
            // We don't want to adjust offsets immediately after the device goes into ambient mode.
            // Instead, we want to wait until it's more likely that the user is not observing the
            // screen anymore.
            final long interval = mFirstUpdate
                ? BURNIN_PROTECTION_FIRST_WAKEUP_INTERVAL_MS
                : BURNIN_PROTECTION_SUBSEQUENT_WAKEUP_INTERVAL_MS;
            if (mFirstUpdate) {
                mFirstUpdate = false;
            } else {
                adjustOffsets();
                mAppliedBurnInXOffset = mLastBurnInXOffset;
                mAppliedBurnInYOffset = mLastBurnInYOffset;
                mDisplayManagerInternal.setDisplayOffsets(mDisplay.getDisplayId(),
                        mLastBurnInXOffset, mLastBurnInYOffset);
            }
            // We use currentTimeMillis to compute the next wakeup time since we want to wake up at
            // the same time as we wake up to update ambient mode to minimize power consumption.
            // However, we use elapsedRealtime to schedule the alarm so that setting the time can't
            // disable burn-in protection for extended periods.
            final long nowWall = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();
            // Next adjustment at least ten seconds in the future.
            long nextWall = nowWall + BURNIN_PROTECTION_MINIMAL_INTERVAL_MS;
            // And aligned to the minute.
            nextWall = (nextWall - (nextWall % interval)) + interval;
            // Use elapsed real time that is adjusted to full minute on wall clock.
            final long nextElapsed = nowElapsed + (nextWall - nowWall);
            if (DEBUG) {
                Slog.d(TAG, "scheduling next wake-up, now wall time " + nowWall
                        + ", next wall: " + nextWall + ", now elapsed: " + nowElapsed
                        + ", next elapsed: " + nextElapsed);
            }
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, nextElapsed,
                    mBurnInProtectionIntent);
        } else {
            mAlarmManager.cancel(mBurnInProtectionIntent);
            mCenteringAnimator.start();
        }
    }

    public void cancelBurnInProtection() {
        if (mBurnInProtectionActive) {
            mBurnInProtectionActive = false;
            updateBurnInProtection();
        }
    }

    /**
     * Gently shifts current burn-in offsets, minimizing the change for the user.
     *
     * Shifts are applied in following fashion:
     * 1) shift horizontally from minimum to the maximum;
     * 2) shift vertically by one from minimum to the maximum;
     * 3) shift horizontally from maximum to the minimum;
     * 4) shift vertically by one from minimum to the maximum.
     * 5) if you reach the maximum vertically, start shifting back by one from maximum to minimum.
     *
     * On top of that, stay within specified radius. If the shift distance from the center is
     * higher than the radius, skip these values and go the next position that is within the radius.
     */
    private void adjustOffsets() {
        do {
            // By default, let's just shift the X offset.
            final int xChange = mXOffsetDirection * BURN_IN_SHIFT_STEP;
            mLastBurnInXOffset += xChange;
            if (mLastBurnInXOffset > mMaxHorizontalBurnInOffset
                    || mLastBurnInXOffset < mMinHorizontalBurnInOffset) {
                // Whoops, we went too far horizontally. Let's retract..
                mLastBurnInXOffset -= xChange;
                // change horizontal direction..
                mXOffsetDirection *= -1;
                // and let's shift the Y offset.
                final int yChange = mYOffsetDirection * BURN_IN_SHIFT_STEP;
                mLastBurnInYOffset += yChange;
                if (mLastBurnInYOffset > mMaxVerticalBurnInOffset
                        || mLastBurnInYOffset < mMinVerticalBurnInOffset) {
                    // Whoops, we went to far vertically. Let's retract..
                    mLastBurnInYOffset -= yChange;
                    // and change vertical direction.
                    mYOffsetDirection *= -1;
                }
            }
            // If we are outside of the radius, let's try again.
        } while (mBurnInRadiusMaxSquared != BURN_IN_MAX_RADIUS_DEFAULT
                && mLastBurnInXOffset * mLastBurnInXOffset + mLastBurnInYOffset * mLastBurnInYOffset
                        > mBurnInRadiusMaxSquared);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + TAG);
        prefix += "  ";
        pw.println(prefix + "mBurnInProtectionActive=" + mBurnInProtectionActive);
        pw.println(prefix + "mHorizontalBurnInOffsetsBounds=(" + mMinHorizontalBurnInOffset + ", "
                + mMaxHorizontalBurnInOffset + ")");
        pw.println(prefix + "mVerticalBurnInOffsetsBounds=(" + mMinVerticalBurnInOffset + ", "
                + mMaxVerticalBurnInOffset + ")");
        pw.println(prefix + "mBurnInRadiusMaxSquared=" + mBurnInRadiusMaxSquared);
        pw.println(prefix + "mLastBurnInOffset=(" + mLastBurnInXOffset + ", "
                + mLastBurnInYOffset + ")");
        pw.println(prefix + "mOfsetChangeDirections=(" + mXOffsetDirection + ", "
                + mYOffsetDirection + ")");
    }

    @Override
    public void onDisplayAdded(int i) {
    }

    @Override
    public void onDisplayRemoved(int i) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == mDisplay.getDisplayId()) {
            if (mDisplay.getState() == Display.STATE_DOZE
                    || mDisplay.getState() == Display.STATE_DOZE_SUSPEND) {
                startBurnInProtection();
            } else {
                cancelBurnInProtection();
            }
        }
    }

    @Override
    public void onAnimationStart(Animator animator) {
    }

    @Override
    public void onAnimationEnd(Animator animator) {
        if (animator == mCenteringAnimator && !mBurnInProtectionActive) {
            mAppliedBurnInXOffset = 0;
            mAppliedBurnInYOffset = 0;
            // No matter how the animation finishes, we want to zero the offsets.
            mDisplayManagerInternal.setDisplayOffsets(mDisplay.getDisplayId(), 0, 0);
        }
    }

    @Override
    public void onAnimationCancel(Animator animator) {
    }

    @Override
    public void onAnimationRepeat(Animator animator) {
    }

    @Override
    public void onAnimationUpdate(ValueAnimator valueAnimator) {
        if (!mBurnInProtectionActive) {
            final float value = (Float) valueAnimator.getAnimatedValue();
            mDisplayManagerInternal.setDisplayOffsets(mDisplay.getDisplayId(),
                    (int) (mAppliedBurnInXOffset * value), (int) (mAppliedBurnInYOffset * value));
        }
    }
}
