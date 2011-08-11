/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.content.res.Configuration;

import com.android.internal.statusbar.IStatusBarService;

import com.android.systemui.R;

public class NavigationBarView extends LinearLayout {
    final static boolean DEBUG = false;
    final static String TAG = "NavigationBarView";

    final static boolean DEBUG_DEADZONE = false;

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];
    AnimatorSet mLastAnimator = null;

    int mBarSize;
    boolean mVertical;

    boolean mHidden;

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        float oldAlpha = mCurrentView.getAlpha();
        if (DEBUG) {
            Slog.d(TAG, "animating alpha: " + oldAlpha + " -> "
                + (!hide ? 1f : 0f));
        }

        if (mLastAnimator != null && mLastAnimator.isRunning()) mLastAnimator.cancel();

        if (!hide) {
            setVisibility(View.VISIBLE);
        }

        // play us off, animatorset
        mLastAnimator = new AnimatorSet();
        mLastAnimator.playTogether(
                ObjectAnimator.ofFloat(mCurrentView, "alpha", hide ? 0f : 1f),
                ObjectAnimator.ofFloat(mCurrentView,
                                       mVertical ? "translationX" : "translationY",
                                       hide ? mBarSize : 0)
        );
        mLastAnimator.setDuration(!hide ? 250 : 1000);
        mLastAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator _a) {
                mLastAnimator = null;
                if (hide) {
                    setVisibility(View.GONE);
                }
            }
        });
        mLastAnimator.start();
    }

    public void onFinishInflate() {
        mRotatedViews[Surface.ROTATION_0] = 
        mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);
        
        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                                                ? findViewById(R.id.rot90)
                                                : findViewById(R.id.rot270);

        mCurrentView = mRotatedViews[Surface.ROTATION_0];
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // immediately bring up the lights
        setHidden(false);
        return false; // pass it on
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        mVertical = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);

        if (DEBUG_DEADZONE) {
            mCurrentView.findViewById(R.id.deadzone).setBackgroundColor(0x808080FF);
        }

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }
    }
}
