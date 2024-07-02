/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.views.buttons;

import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.FloatProperty;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.Surface;

import com.android.systemui.Dependency;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.views.NavigationBarView;
import com.android.systemui.res.R;

import javax.inject.Inject;

/**
 * The "dead zone" consumes unintentional taps along the top edge of the navigation bar.
 * When users are typing quickly on an IME they may attempt to hit the space bar, overshoot, and
 * accidentally hit the home button. The DeadZone expands temporarily after each tap in the UI
 * outside the navigation bar (since this is when accidental taps are more likely), then contracts
 * back over time (since a later tap might be intended for the top of the bar).
 */
public class DeadZone {
    public static final String TAG = "DeadZone";

    public static final boolean DEBUG = false;
    public static final int HORIZONTAL = 0;  // Consume taps along the top edge.
    public static final int VERTICAL = 1;  // Consume taps along the left edge.

    private static final boolean CHATTY = true; // print to logcat when we eat a click

    private static final FloatProperty<DeadZone> FLASH_PROPERTY =
            new FloatProperty<DeadZone>("DeadZoneFlash") {
        @Override
        public void setValue(DeadZone object, float value) {
            object.setFlash(value);
        }

        @Override
        public Float get(DeadZone object) {
            return object.getFlash();
        }
    };

    private final boolean mUseDeadZone;
    private final NavigationBarController mNavBarController;
    private final NavigationBarView mNavigationBarView;

    private boolean mShouldFlash;
    private float mFlashFrac = 0f;

    private int mSizeMax;
    private int mSizeMin;
    // Upon activity elsewhere in the UI, the dead zone will hold steady for
    // mHold ms, then move back over the course of mDecay ms
    private int mHold, mDecay;
    private boolean mVertical;
    private long mLastPokeTime;
    private int mDisplayRotation;
    private final int mDisplayId;

    private final Runnable mDebugFlash = new Runnable() {
        @Override
        public void run() {
            ObjectAnimator.ofFloat(DeadZone.this, FLASH_PROPERTY, 1f, 0f).setDuration(150).start();
        }
    };

    @Inject
    public DeadZone(NavigationBarView view) {
        mUseDeadZone = view.getResources().getBoolean(R.bool.config_useDeadZone);

        mNavigationBarView = view;
        mNavBarController = Dependency.get(NavigationBarController.class);
        mDisplayId = view.getContext().getDisplayId();

        onConfigurationChanged(HORIZONTAL);
    }

    static float lerp(float a, float b, float f) {
        return (b - a) * f + a;
    }

    private float getSize(long now) {
        if (mSizeMax == 0)
            return 0;
        long dt = (now - mLastPokeTime);
        if (dt > mHold + mDecay)
            return mSizeMin;
        if (dt < mHold)
            return mSizeMax;
        return (int) lerp(mSizeMax, mSizeMin, (float) (dt - mHold) / mDecay);
    }

    public void setFlashOnTouchCapture(boolean dbg) {
        if (!mUseDeadZone) {
            return;
        }

        mShouldFlash = dbg;
        mFlashFrac = 0f;
        mNavigationBarView.postInvalidate();
    }

    public void onConfigurationChanged(int rotation) {
        if (!mUseDeadZone) {
            return;
        }

        mDisplayRotation = rotation;

        final Resources res = mNavigationBarView.getResources();
        mHold = res.getInteger(R.integer.navigation_bar_deadzone_hold);
        mDecay = res.getInteger(R.integer.navigation_bar_deadzone_decay);

        mSizeMin = res.getDimensionPixelSize(R.dimen.navigation_bar_deadzone_size);
        mSizeMax = res.getDimensionPixelSize(R.dimen.navigation_bar_deadzone_size_max);
        int index = res.getInteger(R.integer.navigation_bar_deadzone_orientation);
        mVertical = (index == VERTICAL);

        if (DEBUG) {
            Slog.v(TAG, this + " size=[" + mSizeMin + "-" + mSizeMax + "] hold=" + mHold
                    + (mVertical ? " vertical" : " horizontal"));
        }
        setFlashOnTouchCapture(res.getBoolean(R.bool.config_dead_zone_flash));
    }

    // I made you a touch event...
    public boolean onTouchEvent(MotionEvent event) {
        if (!mUseDeadZone) {
            return false;
        }

        if (DEBUG) {
            Slog.v(TAG, this + " onTouch: " + MotionEvent.actionToString(event.getAction()));
        }

        // Don't consume events for high precision pointing devices. For this purpose a stylus is
        // considered low precision (like a finger), so its events may be consumed.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return false;
        }

        final int action = event.getAction();
        if (action == MotionEvent.ACTION_OUTSIDE) {
            poke(event);
            return true;
        } else if (action == MotionEvent.ACTION_DOWN) {
            if (DEBUG) {
                Slog.v(TAG, this + " ACTION_DOWN: " + event.getX() + "," + event.getY());
            }
            mNavBarController.touchAutoDim(mDisplayId);
            int size = (int) getSize(event.getEventTime());
            // In the vertical orientation consume taps along the left edge.
            // In horizontal orientation consume taps along the top edge.
            final boolean consumeEvent;
            if (mVertical) {
                if (mDisplayRotation == Surface.ROTATION_270) {
                    consumeEvent = event.getX() > mNavigationBarView.getWidth() - size;
                } else {
                    consumeEvent = event.getX() < size;
                }
            } else {
                consumeEvent = event.getY() < size;
            }
            if (consumeEvent) {
                if (CHATTY) {
                    Slog.v(TAG, "consuming errant click: (" + event.getX() + "," + event.getY() + ")");
                }
                if (mShouldFlash) {
                    mNavigationBarView.post(mDebugFlash);
                    mNavigationBarView.postInvalidate();
                }
                return true; // ...but I eated it
            }
        }
        return false;
    }

    private void poke(MotionEvent event) {
        mLastPokeTime = event.getEventTime();
        if (DEBUG)
            Slog.v(TAG, "poked! size=" + getSize(mLastPokeTime));
        if (mShouldFlash) mNavigationBarView.postInvalidate();
    }

    private void setFlash(float f) {
        mFlashFrac = f;
        mNavigationBarView.postInvalidate();
    }

    private float getFlash() {
        return mFlashFrac;
    }

    public void onDraw(Canvas can) {
        if (!mUseDeadZone || !mShouldFlash || mFlashFrac <= 0f) {
            return;
        }

        final int size = (int) getSize(SystemClock.uptimeMillis());
        if (mVertical) {
            if (mDisplayRotation == Surface.ROTATION_270) {
                can.clipRect(can.getWidth() - size, 0, can.getWidth(), can.getHeight());
            } else {
                can.clipRect(0, 0, size, can.getHeight());
            }
        } else {
            can.clipRect(0, 0, can.getWidth(), size);
        }

        final float frac = DEBUG ? (mFlashFrac - 0.5f) + 0.5f : mFlashFrac;
        can.drawARGB((int) (frac * 0xFF), 0xDD, 0xEE, 0xAA);

        if (DEBUG && size > mSizeMin)
            // Very aggressive redrawing here, for debugging only
            mNavigationBarView.postInvalidateDelayed(100);
    }
}
