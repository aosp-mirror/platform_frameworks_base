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

import static com.android.systemui.ScreenDecorations.DisplayCutoutView.boundsFromDirection;

import static java.lang.Float.isNaN;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.util.leak.RotationUtils;

import java.util.List;
import java.util.Objects;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = StatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;
    private final StatusBarContentInsetsProvider mContentInsetsProvider;

    StatusBar mBar;

    boolean mIsFullyOpenedPanel = false;
    private ScrimController mScrimController;
    private float mMinFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPanelFraction == 0.0f) {
                mBar.makeExpandedInvisible();
            }
        }
    };
    private DarkReceiver mBattery;
    private DarkReceiver mClock;
    private int mRotationOrientation = -1;
    @Nullable
    private View mCenterIconSpace;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    private int mStatusBarHeight;
    @Nullable
    private List<StatusBar.ExpansionChangedListener> mExpansionChangedListeners;
    @Nullable
    private PanelExpansionStateChangedListener mPanelExpansionStateChangedListener;

    private PanelEnabledProvider mPanelEnabledProvider;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;
    private boolean mHeadsUpVisible;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContentInsetsProvider = Dependency.get(StatusBarContentInsetsProvider.class);
    }

    public void setBar(StatusBar bar) {
        mBar = bar;
    }

    public void setExpansionChangedListeners(
            @Nullable List<StatusBar.ExpansionChangedListener> listeners) {
        mExpansionChangedListeners = listeners;
    }

    void setPanelExpansionStateChangedListener(PanelExpansionStateChangedListener listener) {
        mPanelExpansionStateChangedListener = listener;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    @Override
    public void onFinishInflate() {
        mBattery = findViewById(R.id.battery);
        mClock = findViewById(R.id.clock);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mCenterIconSpace = findViewById(R.id.centered_icon_area);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClock);
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClock);
        mDisplayCutout = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateOrientationAndCutout() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        return changed;
    }

    @Override
    public boolean panelEnabled() {
        if (mPanelEnabledProvider == null) {
            Log.e(TAG, "panelEnabledProvider is null; defaulting to super class.");
            return super.panelEnabled();
        }
        return mPanelEnabledProvider.panelEnabled();
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
            mPanel.getView().sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
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
    public void onPanelMinFractionChanged(float minFraction) {
        if (isNaN(minFraction)) {
            throw new IllegalArgumentException("minFraction cannot be NaN");
        }
        super.onPanelMinFractionChanged(minFraction);
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(float frac, boolean expanded) {
        super.panelExpansionChanged(frac, expanded);
        updateScrimFraction();
        if ((frac == 0 || frac == 1)) {
            if (mPanelExpansionStateChangedListener != null) {
                mPanelExpansionStateChangedListener.onPanelExpansionStateChanged();
            } else {
                Log.w(TAG, "No PanelExpansionStateChangedListener provided.");
            }
        }

        if (mExpansionChangedListeners != null) {
            for (StatusBar.ExpansionChangedListener listener : mExpansionChangedListeners) {
                listener.onExpansionChanged(frac, expanded);
            }
        }
    }

    /** Set the {@link PanelEnabledProvider} to use. */
    public void setPanelEnabledProvider(PanelEnabledProvider panelEnabledProvider) {
        mPanelEnabledProvider = panelEnabledProvider;
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
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_height);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;

        int statusBarPaddingTop = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_top);
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        int statusBarPaddingEnd = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_end);

        View sbContents = findViewById(R.id.status_bar_contents);
        sbContents.setPaddingRelative(
                statusBarPaddingStart,
                statusBarPaddingTop,
                statusBarPaddingEnd,
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, statusBarPaddingStart, 0, 0);

        setLayoutParams(layoutParams);
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation(StatusBarWindowView.cornerCutoutMargins(mDisplayCutout, getDisplay()));
        updateSafeInsets();
    }

    private void updateCutoutLocation(Pair<Integer, Integer> cornerCutoutMargins) {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || cornerCutoutMargins != null) {
            mCenterIconSpace.setVisibility(View.VISIBLE);
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCenterIconSpace.setVisibility(View.GONE);
        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = new Rect();
        boundsFromDirection(mDisplayCutout, Gravity.TOP, bounds);

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        Rect contentRect = mContentInsetsProvider
                .getStatusBarContentInsetsForRotation(RotationUtils.getExactRotation(getContext()));

        Point size = new Point();
        getDisplay().getRealSize(size);

        setPadding(
                contentRect.left,
                getPaddingTop(),
                size.x - contentRect.right,
                getPaddingBottom());
    }

    /** An interface that will provide whether panel is enabled. */
    interface PanelEnabledProvider {
        /** Returns true if the panel is enabled and false otherwise. */
        boolean panelEnabled();
    }

    /** A listener that will be notified when a panel's expansion state may have changed. */
    public interface PanelExpansionStateChangedListener {
        /** Called when a panel's expansion state may have changed. */
        void onPanelExpansionStateChanged();
    }
}
