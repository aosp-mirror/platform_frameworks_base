/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.provider.Settings;
import android.view.CompositionSamplingListener;
import android.view.View;

import java.io.PrintWriter;

/**
 * Updates the nav bar tint based on the color of the content behind the nav bar.
 */
public class NavBarTintController implements View.OnAttachStateChangeListener,
        View.OnLayoutChangeListener {

    public static final int MIN_COLOR_ADAPT_TRANSITION_TIME = 400;
    public static final int DEFAULT_COLOR_ADAPT_TRANSITION_TIME = 1700;

    // Passing the threshold of this luminance value will make the button black otherwise white
    private static final float LUMINANCE_THRESHOLD = 0.3f;

    private final Handler mHandler = new Handler();
    private final NavigationBarView mNavigationBarView;
    private final LightBarTransitionsController mLightBarController;

    private final CompositionSamplingListener mSamplingListener;
    private final Runnable mUpdateSamplingListener = this::updateSamplingListener;
    private final Rect mSamplingBounds = new Rect();
    private boolean mSamplingEnabled = false;
    private boolean mSamplingListenerRegistered = false;

    private float mLastMediaLuma;
    private boolean mUpdateOnNextDraw;

    public NavBarTintController(NavigationBarView navigationBarView,
            LightBarTransitionsController lightBarController) {
        mSamplingListener = new CompositionSamplingListener(
                navigationBarView.getContext().getMainExecutor()) {
            @Override
            public void onSampleCollected(float medianLuma) {
                updateTint(medianLuma);
            }
        };
        mNavigationBarView = navigationBarView;
        mNavigationBarView.addOnAttachStateChangeListener(this);
        mNavigationBarView.addOnLayoutChangeListener(this);
        mLightBarController = lightBarController;
    }

    void onDraw() {
        if (mUpdateOnNextDraw) {
            mUpdateOnNextDraw = false;
            requestUpdateSamplingListener();
        }
    }

    void start() {
        if (!isEnabled(mNavigationBarView.getContext())) {
            return;
        }
        mSamplingEnabled = true;
        // Defer calling updateSamplingListener since we may have just reinflated prior to this
        requestUpdateSamplingListener();
    }

    void stop() {
        mSamplingEnabled = false;
        requestUpdateSamplingListener();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        requestUpdateSamplingListener();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        // Defer calling updateSamplingListener the attach info has not yet been reset
        requestUpdateSamplingListener();
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        mSamplingBounds.setEmpty();
        // TODO: Extend this to 2/3 button layout as well
        View view = mNavigationBarView.getHomeHandle().getCurrentView();
        if (view != null) {
            int[] pos = new int[2];
            view.getLocationOnScreen(pos);
            final Rect samplingBounds = new Rect(pos[0], pos[1],
                    pos[0] + view.getWidth(), pos[1] + view.getHeight());
            if (!samplingBounds.equals(mSamplingBounds)) {
                mSamplingBounds.set(samplingBounds);
                requestUpdateSamplingListener();
            }
        }
    }

    private void requestUpdateSamplingListener() {
        mHandler.removeCallbacks(mUpdateSamplingListener);
        mHandler.post(mUpdateSamplingListener);
    }

    private void updateSamplingListener() {
        if (mSamplingListenerRegistered) {
            mSamplingListenerRegistered = false;
            CompositionSamplingListener.unregister(mSamplingListener);
        }
        if (mSamplingEnabled && !mSamplingBounds.isEmpty()
                && mNavigationBarView.isAttachedToWindow()) {
            if (!mNavigationBarView.getViewRootImpl().getSurfaceControl().isValid()) {
                // The view may still be attached, but the surface backing the window can be
                // destroyed, so wait until the next draw to update the listener again
                mUpdateOnNextDraw = true;
                return;
            }
            mSamplingListenerRegistered = true;
            CompositionSamplingListener.register(mSamplingListener, DEFAULT_DISPLAY,
                    mNavigationBarView.getViewRootImpl().getSurfaceControl().getHandle(),
                    mSamplingBounds);
        }
    }

    private void updateTint(float medianLuma) {
        mLastMediaLuma = medianLuma;
        if (medianLuma > LUMINANCE_THRESHOLD) {
            // Black
            mLightBarController.setIconsDark(true /* dark */, true /* animate */);
        } else {
            // White
            mLightBarController.setIconsDark(false /* dark */, true /* animate */);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("NavBarTintController:");
        pw.println("  navBar isAttached: " + mNavigationBarView.isAttachedToWindow());
        pw.println("  navBar isScValid: " + (mNavigationBarView.isAttachedToWindow()
                ? mNavigationBarView.getViewRootImpl().getSurfaceControl().isValid()
                : "false"));
        pw.println("  mSamplingListenerRegistered: " + mSamplingListenerRegistered);
        pw.println("  mSamplingBounds: " + mSamplingBounds);
        pw.println("  mLastMediaLuma: " + mLastMediaLuma);
    }

    public static boolean isEnabled(Context context) {
        return context.getDisplayId() == DEFAULT_DISPLAY
                && Settings.Global.getInt(context.getContentResolver(),
                        NavigationPrototypeController.NAV_COLOR_ADAPT_ENABLE_SETTING, 0) == 1
                && Settings.Global.getInt(context.getContentResolver(),
                        NavigationPrototypeController.SHOW_HOME_HANDLE_SETTING, 0) == 1;
    }
}
