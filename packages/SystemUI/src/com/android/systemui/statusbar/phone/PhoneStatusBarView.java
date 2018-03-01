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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.EventLog;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.util.leak.RotationUtils;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = StatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;
    private static final int NO_VALUE = Integer.MIN_VALUE;

    StatusBar mBar;

    boolean mIsFullyOpenedPanel = false;
    private final PhoneStatusBarTransitions mBarTransitions;
    private ScrimController mScrimController;
    private float mMinFraction;
    private float mPanelFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPanelFraction == 0.0f) {
                mBar.makeExpandedInvisible();
            }
        }
    };
    private DarkReceiver mBattery;
    private int mLastOrientation;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mBarTransitions = new PhoneStatusBarTransitions(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(StatusBar bar) {
        mBar = bar;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    @Override
    public void onFinishInflate() {
        mBarTransitions.init();
        mBattery = findViewById(R.id.battery);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        if (updateOrientationAndCutout(getResources().getConfiguration().orientation)) {
            postUpdateLayoutForCutout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        mDisplayCutout = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // May trigger cutout space layout-ing
        if (updateOrientationAndCutout(newConfig.orientation)) {
            postUpdateLayoutForCutout();
        }
    }

    /**
     *
     * @param newOrientation may pass NO_VALUE for no change
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateOrientationAndCutout(int newOrientation) {
        boolean changed = false;
        if (newOrientation != NO_VALUE) {
            if (mLastOrientation != newOrientation) {
                changed = true;
                mLastOrientation = newOrientation;
            }
        }

        if (mDisplayCutout == null) {
            DisplayCutout cutout = getRootWindowInsets().getDisplayCutout();
            if (cutout != null) {
                changed = true;
                mDisplayCutout = cutout;
            }
        }

        return changed;
    }

    @Override
    public boolean panelEnabled() {
        return mBar.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(false);
    }

    @Override
    public void onPanelCollapsed() {
        super.onPanelCollapsed();
        // Close the status bar in the next frame so we can show the end of the animation.
        post(mHideExpandedRunnable);
        mIsFullyOpenedPanel = false;
    }

    public void removePendingHideExpandedRunnables() {
        removeCallbacks(mHideExpandedRunnable);
    }

    @Override
    public void onPanelFullyOpened() {
        super.onPanelFullyOpened();
        if (!mIsFullyOpenedPanel) {
            mPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mIsFullyOpenedPanel = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public void onTrackingStarted() {
        super.onTrackingStarted();
        mBar.onTrackingStarted();
        mScrimController.onTrackingStarted();
        removePendingHideExpandedRunnables();
    }

    @Override
    public void onClosingFinished() {
        super.onClosingFinished();
        mBar.onClosingFinished();
    }

    @Override
    public void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        mBar.onTrackingStopped(expand);
    }

    @Override
    public void onExpandingFinished() {
        super.onExpandingFinished();
        mScrimController.onExpandingFinished();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelScrimMinFractionChanged(float minFraction) {
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(float frac, boolean expanded) {
        super.panelExpansionChanged(frac, expanded);
        mPanelFraction = frac;
        updateScrimFraction();
        if ((frac == 0 || frac == 1) && mBar.getNavigationBarView() != null) {
            mBar.getNavigationBarView().onPanelExpandedChange(expanded);
        }
    }

    private void updateScrimFraction() {
        float scrimFraction = mPanelFraction;
        if (mMinFraction < 1.0f) {
            scrimFraction = Math.max((mPanelFraction - mMinFraction) / (1.0f - mMinFraction),
                    0);
        }
        mScrimController.setPanelExpansion(scrimFraction);
    }

    public void updateResources() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        setLayoutParams(layoutParams);
    }

    private void updateLayoutForCutout() {
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void postUpdateLayoutForCutout() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                updateLayoutForCutout();
            }
        };
        // Let the cutout emulation draw first
        postDelayed(r, 0);
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty()
                    || mLastOrientation != ORIENTATION_PORTRAIT) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();
        lp.width = mDisplayCutout.getBoundingRect().width();
        lp.height = mDisplayCutout.getBoundingRect().height();
    }

    private void updateSafeInsets() {
        // Depending on our rotation, we may have to work around a cutout in the middle of the view,
        // or letterboxing from the right or left sides.

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mDisplayCutout == null || mDisplayCutout.isEmpty()) {
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            return;
        }

        int leftMargin = 0;
        int rightMargin = 0;
        switch (RotationUtils.getRotation(getContext())) {
            /*
             * Landscape: <-|
             * Seascape:  |->
             */
            case RotationUtils.ROTATION_LANDSCAPE:
                leftMargin = getDisplayCutoutHeight();
                break;
            case RotationUtils.ROTATION_SEASCAPE:
                rightMargin = getDisplayCutoutHeight();
                break;
            default:
                break;
        }

        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
    }

    //TODO: Find a better way
    private int getDisplayCutoutHeight() {
        if (mDisplayCutout == null || mDisplayCutout.isEmpty()) {
            return 0;
        }

        Rect r = mDisplayCutout.getBoundingRect();
        return r.bottom - r.top;
    }
}
