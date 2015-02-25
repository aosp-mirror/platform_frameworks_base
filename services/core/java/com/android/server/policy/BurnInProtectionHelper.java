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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.SystemClock;
import android.view.Display;

import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

public class BurnInProtectionHelper implements DisplayManager.DisplayListener {
    private static final String TAG = "BurnInProtection";

    // Default value when max burnin radius is not set.
    public static final int BURN_IN_RADIUS_MAX_DEFAULT = -1;

    private static final long BURNIN_PROTECTION_WAKEUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long BURNIN_PROTECTION_MINIMAL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10);

    private static final String ACTION_BURN_IN_PROTECTION =
            "android.internal.policy.action.BURN_IN_PROTECTION";

    private static final int BURN_IN_SHIFT_STEP = 2;

    private boolean mBurnInProtectionActive;

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

    private final AlarmManager mAlarmManager;
    private final PendingIntent mBurnInProtectionIntent;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final Display mDisplay;

    private BroadcastReceiver mBurnInProtectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBurnInProtection();
        }
    };

    public BurnInProtectionHelper(Context context) {
        final Resources resources = context.getResources();
        mMinHorizontalBurnInOffset = resources.getInteger(
                com.android.internal.R.integer.config_burnInProtectionMinHorizontalOffset);
        mMaxHorizontalBurnInOffset = resources.getInteger(
                com.android.internal.R.integer.config_burnInProtectionMaxHorizontalOffset);
        mMinVerticalBurnInOffset = resources.getInteger(
                com.android.internal.R.integer.config_burnInProtectionMinVerticalOffset);
        mMaxVerticalBurnInOffset = resources.getInteger(
                com.android.internal.R.integer.config_burnInProtectionMaxVerticalOffset);
        int burnInRadiusMax = resources.getInteger(
                com.android.internal.R.integer.config_burnInProtectionMaxRadius);
        if (burnInRadiusMax != BURN_IN_RADIUS_MAX_DEFAULT) {
            mBurnInRadiusMaxSquared = burnInRadiusMax * burnInRadiusMax;
        } else {
            mBurnInRadiusMaxSquared = BURN_IN_RADIUS_MAX_DEFAULT;
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
    }

    public void startBurnInProtection() {
        if (!mBurnInProtectionActive) {
            mBurnInProtectionActive = true;
            updateBurnInProtection();
        }
    }

    private void updateBurnInProtection() {
        if (mBurnInProtectionActive) {
            adjustOffsets();
            mDisplayManagerInternal.setDisplayOffsets(mDisplay.getDisplayId(),
                    mLastBurnInXOffset, mLastBurnInYOffset);
            // Next adjustment at least ten seconds in the future.
            long next = SystemClock.elapsedRealtime() + BURNIN_PROTECTION_MINIMAL_INTERVAL_MS;
            // And aligned to the minute.
            next = next - next % BURNIN_PROTECTION_WAKEUP_INTERVAL_MS
                    + BURNIN_PROTECTION_WAKEUP_INTERVAL_MS;
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, next, mBurnInProtectionIntent);
        } else {
            mAlarmManager.cancel(mBurnInProtectionIntent);
            mDisplayManagerInternal.setDisplayOffsets(mDisplay.getDisplayId(), 0, 0);
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
        } while (mBurnInRadiusMaxSquared != BURN_IN_RADIUS_MAX_DEFAULT
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
}
