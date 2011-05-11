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
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.ServiceManager;
import android.util.AttributeSet;
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
    protected IStatusBarService mBarService;
    final Display mDisplay;
    View[] mRotatedViews = new View[4];
    View mBackground;
    Animator mLastAnimator = null;

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        //setLayerType(View.LAYER_TYPE_HARDWARE, null);

        setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                boolean on = (visibility == View.STATUS_BAR_VISIBLE);
                android.util.Log.d("NavigationBarView", "LIGHTS " 
                    + (on ? "ON" : "OUT"));
                setLights(on);
            }
        });
    }

    private void setLights(final boolean on) {
        float oldAlpha = mBackground.getAlpha();
        android.util.Log.d("NavigationBarView", "animating alpha: " + oldAlpha + " -> "
            + (on ? 1f : 0f));

        if (mLastAnimator != null && mLastAnimator.isRunning()) mLastAnimator.cancel();

        mLastAnimator = ObjectAnimator.ofFloat(mBackground, "alpha", oldAlpha, on ? 1f : 0f)
            .setDuration(on ? 250 : 1500);
        mLastAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator _a) {
                mLastAnimator = null;
            }
        });
        mLastAnimator.start();
    }

    public void onFinishInflate() {
        mBackground = findViewById(R.id.background);

        mRotatedViews[Surface.ROTATION_0] = 
        mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);
        
        mRotatedViews[Surface.ROTATION_270] = findViewById(R.id.rot270);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // immediately bring up the lights
        setLights(true);
        return false; // pass it on
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mRotatedViews[rot].setVisibility(View.VISIBLE);

        android.util.Log.d("NavigationBarView", "reorient(): rot=" + mDisplay.getRotation());
    }
}
