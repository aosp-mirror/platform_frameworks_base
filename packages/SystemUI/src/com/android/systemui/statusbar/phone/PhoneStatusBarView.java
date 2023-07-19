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


import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.statusbar.policy.Offset;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;
import android.util.TypedValue;
import com.android.systemui.tuner.TunerService;
import android.provider.Settings;

import java.util.Objects;

public class PhoneStatusBarView extends FrameLayout implements TunerService.Tunable {
    private static final String TAG = "PhoneStatusBarView";

    private static final String LEFT_PADDING =
            "system:" + Settings.System.LEFT_PADDING;
    private static final String RIGHT_PADDING =
            "system:" + Settings.System.RIGHT_PADDING;

    private final StatusBarContentInsetsProvider mContentInsetsProvider;

    private int mBasePaddingBottom;
    private int mLeftPad;
    private int mRightPad;
    private int sbPaddingStartRes;
    private int sbPaddingEndRes;
    private int mBasePaddingTop;

    private DarkReceiver mBattery;
    private DarkReceiver mClock;
    private DarkReceiver mClockCentre;
    private DarkReceiver mClockRight;
    private int mRotationOrientation = -1;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private TouchEventHandler mTouchEventHandler;
    @Nullable
    private ViewGroup mStatusBarContents = null;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContentInsetsProvider = Dependency.get(StatusBarContentInsetsProvider.class);
    }

    void setTouchEventHandler(TouchEventHandler handler) {
        mTouchEventHandler = handler;
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    public void offsetStatusBar(Offset offset) {
        if (mStatusBarContents == null) {
            return;
        }
        mStatusBarContents.setTranslationX(offset.getX());
        mStatusBarContents.setTranslationY(offset.getY());
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mBattery = findViewById(R.id.battery);
        mClock = findViewById(R.id.clock);
        mClockCentre = findViewById(R.id.center_clock);
        mClockRight = findViewById(R.id.right_clock);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);

	Dependency.get(TunerService.class).addTunable(this,
                LEFT_PADDING, RIGHT_PADDING);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClock);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClockCentre);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClockRight);
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClock);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClockCentre);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClockRight);
        mDisplayCutout = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
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

        final Rect newSize = mContext.getResources().getConfiguration().windowConfiguration
                .getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        return changed;
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
    public boolean onTouchEvent(MotionEvent event) {
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.handleTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        mTouchEventHandler.onInterceptTouchEvent(event);
        return super.onInterceptTouchEvent(event);
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
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;

        float density = Resources.getSystem().getDisplayMetrics().density;
        Resources res = null;
        try {
            res = mContext.getPackageManager().getResourcesForApplication("com.android.systemui");
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }

        int statusBarPaddingTop = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_top);
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        int statusBarPaddingEnd = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_end);
        sbPaddingStartRes = (int) (statusBarPaddingStart / density);
        sbPaddingEndRes = (int) (statusBarPaddingEnd / density);

        mStatusBarContents.setPaddingRelative(
                (int) mLeftPad,
                statusBarPaddingTop,
                (int) mRightPad,
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, (int) mLeftPad, 0, 0);

        setLayoutParams(layoutParams);
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout = mContentInsetsProvider.currentRotationHasCornerCutout();
        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        Pair<Integer, Integer> insets = mContentInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();

        setPadding(
                insets.first,
                getPaddingTop(),
                insets.second,
                getPaddingBottom());

        // Apply negative paddings to centered area layout so that we'll actually be on the center.
        final int winRotation = getDisplay().getRotation();
        LayoutParams centeredAreaParams =
                (LayoutParams) findViewById(R.id.center_clock_layout).getLayoutParams();
        centeredAreaParams.leftMargin =
                winRotation == Surface.ROTATION_0 ? -insets.first : 0;
        centeredAreaParams.rightMargin =
                winRotation == Surface.ROTATION_0 ? -insets.second : 0;
    }

    /**
     * A handler responsible for all touch event handling on the status bar.
     *
     * Touches that occur on the status bar view may have ramifications for the notification
     * panel (e.g. a touch that pulls down the shade could start on the status bar), so this
     * interface provides a way to notify the panel controller when these touches occur.
     *
     * The handler will be notified each time {@link PhoneStatusBarView#onTouchEvent} and
     * {@link PhoneStatusBarView#onInterceptTouchEvent} are called.
     **/
    public interface TouchEventHandler {
        /** Called each time {@link PhoneStatusBarView#onInterceptTouchEvent} is called. */
        void onInterceptTouchEvent(MotionEvent event);

        /**
         * Called each time {@link PhoneStatusBarView#onTouchEvent} is called.
         *
         * Should return true if the touch was handled by this handler and false otherwise. The
         * return value from the handler will be returned from
         * {@link PhoneStatusBarView#onTouchEvent}.
         */
        boolean handleTouchEvent(MotionEvent event);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (LEFT_PADDING.equals(key)) {
            int mLPadding = TunerService.parseInteger(newValue, sbPaddingStartRes);
            mLeftPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, mLPadding,
                getResources().getDisplayMetrics()));
            updateStatusBarHeight();
        } else if (RIGHT_PADDING.equals(key)) {
            int mRPadding = TunerService.parseInteger(newValue, sbPaddingEndRes);
            mRightPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, mRPadding,
                getResources().getDisplayMetrics()));
            updateStatusBarHeight();
        }
    }
}
