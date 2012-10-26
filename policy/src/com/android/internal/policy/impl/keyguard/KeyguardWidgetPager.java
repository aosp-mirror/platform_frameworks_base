/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.android.internal.R;

import com.android.internal.widget.LockPatternUtils;

public class KeyguardWidgetPager extends PagedView implements PagedView.PageSwitchListener,
        OnLongClickListener {

    ZInterpolator mZInterpolator = new ZInterpolator(0.5f);
    private static float CAMERA_DISTANCE = 10000;
    private static float TRANSITION_MAX_ROTATION = 30;
    private static final boolean PERFORM_OVERSCROLL_ROTATION = true;

    private KeyguardViewStateManager mViewStateManager;
    private LockPatternUtils mLockPatternUtils;

    // Related to the fading in / out background outlines
    private static final int CHILDREN_OUTLINE_FADE_OUT_DELAY = 0;
    private static final int CHILDREN_OUTLINE_FADE_OUT_DURATION = 375;
    private static final int CHILDREN_OUTLINE_FADE_IN_DURATION = 100;
    private ObjectAnimator mChildrenOutlineFadeInAnimation;
    private ObjectAnimator mChildrenOutlineFadeOutAnimation;
    private float mChildrenOutlineAlpha = 0;
    private float mSidePagesAlpha = 1f;

    private static final long CUSTOM_WIDGET_USER_ACTIVITY_TIMEOUT = 30000;

    private int mPage = 0;
    private Callbacks mCallbacks;

    private boolean mCameraWidgetEnabled;

    public KeyguardWidgetPager(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetPager(Context context) {
        this(null, null, 0);
    }

    public KeyguardWidgetPager(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        setPageSwitchListener(this);

        Resources r = getResources();
        mCameraWidgetEnabled = r.getBoolean(R.bool.kg_enable_camera_default_widget);
    }

    public void setViewStateManager(KeyguardViewStateManager viewStateManager) {
        mViewStateManager = viewStateManager;
    }

    public void setLockPatternUtils(LockPatternUtils l) {
        mLockPatternUtils = l;
    }

    @Override
    public void onPageSwitch(View newPage, int newPageIndex) {
        boolean showingStatusWidget = false;
        if (newPage instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) newPage;
            if (vg.getChildAt(0) instanceof KeyguardStatusView) {
                showingStatusWidget = true;
            }
        }

        // Disable the status bar clock if we're showing the default status widget
        if (showingStatusWidget) {
            setSystemUiVisibility(getSystemUiVisibility() | View.STATUS_BAR_DISABLE_CLOCK);
        } else {
            setSystemUiVisibility(getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_CLOCK);
        }

        // Extend the display timeout if the user switches pages
        if (mPage != newPageIndex) {
            int oldPageIndex = mPage;
            mPage = newPageIndex;
            if (mCallbacks != null) {
                mCallbacks.onUserActivityTimeoutChanged();
                mCallbacks.userActivity();
                mCallbacks.onPageSwitch(newPageIndex);
            }
            KeyguardWidgetFrame oldWidgetPage = getWidgetPageAt(oldPageIndex);
            if (oldWidgetPage != null) {
                oldWidgetPage.onActive(false);
            }
            KeyguardWidgetFrame newWidgetPage = getWidgetPageAt(newPageIndex);
            if (newWidgetPage != null) {
                newWidgetPage.onActive(true);
            }
        }
        if (mViewStateManager != null) {
            mViewStateManager.onPageSwitch(newPage, newPageIndex);
        }
    }

    public void showPagingFeedback() {
        // Nothing yet.
    }

    public long getUserActivityTimeout() {
        View page = getPageAt(mPage);
        if (page instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) page;
            View view = vg.getChildAt(0);
            if (!(view instanceof KeyguardStatusView)
                    && !(view instanceof KeyguardMultiUserSelectorView)) {
                return CUSTOM_WIDGET_USER_ACTIVITY_TIMEOUT;
            }
        }
        return -1;
    }

    public void setCallbacks(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    public interface Callbacks {
        public void userActivity();
        public void onUserActivityTimeoutChanged();
        public void onPageSwitch(int newPageIndex);
    }

    public void addWidget(View widget) {
        addWidget(widget, -1);
    }


    public void onRemoveView(View v) {
        int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
        mLockPatternUtils.removeAppWidget(appWidgetId);
    }

    public void onAddView(View v, int index) {
        int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
        getVisiblePages(mTempVisiblePagesRange);
        boundByReorderablePages(true, mTempVisiblePagesRange);
        // Subtract from the index to take into account pages before the reorderable
        // pages (e.g. the "add widget" page)
        mLockPatternUtils.addAppWidget(appWidgetId, index - mTempVisiblePagesRange[0]);
    }

    /*
     * We wrap widgets in a special frame which handles drawing the over scroll foreground.
     */
    public void addWidget(View widget, int pageIndex) {
        KeyguardWidgetFrame frame;
        // All views contained herein should be wrapped in a KeyguardWidgetFrame
        if (!(widget instanceof KeyguardWidgetFrame)) {
            frame = new KeyguardWidgetFrame(getContext());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.TOP;
            // The framework adds a default padding to AppWidgetHostView. We don't need this padding
            // for the Keyguard, so we override it to be 0.
            widget.setPadding(0,  0, 0, 0);
            if (widget instanceof AppWidgetHostView) {
                AppWidgetHostView awhv = (AppWidgetHostView) widget;
                widget.setContentDescription(awhv.getAppWidgetInfo().label);
            }
            frame.addView(widget, lp);
        } else {
            frame = (KeyguardWidgetFrame) widget;
        }

        ViewGroup.LayoutParams pageLp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        frame.setOnLongClickListener(this);

        if (pageIndex == -1) {
            addView(frame, pageLp);
        } else {
            addView(frame, pageIndex, pageLp);
        }
    }

    // We enforce that all children are KeyguardWidgetFrames
    @Override
    public void addView(View child, int index) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, index);
    }

    @Override
    public void addView(View child, int width, int height) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, width, height);
    }

    @Override
    public void addView(View child, LayoutParams params) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, params);
    }

    @Override
    public void addView(View child, int index, LayoutParams params) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, index, params);
    }

    private void enforceKeyguardWidgetFrame(View child) {
        if (!(child instanceof KeyguardWidgetFrame)) {
            throw new IllegalArgumentException(
                    "KeyguardWidgetPager children must be KeyguardWidgetFrames");
        }
    }

    public KeyguardWidgetFrame getWidgetPageAt(int index) {
        // This is always a valid cast as we've guarded the ability to
        return (KeyguardWidgetFrame) getChildAt(index);
    }

    protected void onUnhandledTap(MotionEvent ev) {
        showPagingFeedback();
    }

    @Override
    protected void onPageBeginMoving() {
        // Enable hardware layers while pages are moving
        // TODO: We should only do this for the two views that are actually moving
        int children = getChildCount();
        for (int i = 0; i < children; i++) {
            getWidgetPageAt(i).enableHardwareLayersForContent();
        }

        if (mViewStateManager != null) {
            mViewStateManager.onPageBeginMoving();
        }
        showOutlinesAndSidePages();
    }

    @Override
    protected void onPageEndMoving() {
        // Disable hardware layers while pages are moving
        int children = getChildCount();
        for (int i = 0; i < children; i++) {
            getWidgetPageAt(i).disableHardwareLayersForContent();
        }

        if (mViewStateManager != null) {
            mViewStateManager.onPageEndMoving();
        }
        hideOutlinesAndSidePages();
    }

    /*
     * This interpolator emulates the rate at which the perceived scale of an object changes
     * as its distance from a camera increases. When this interpolator is applied to a scale
     * animation on a view, it evokes the sense that the object is shrinking due to moving away
     * from the camera.
     */
    static class ZInterpolator implements TimeInterpolator {
        private float focalLength;

        public ZInterpolator(float foc) {
            focalLength = foc;
        }

        public float getInterpolation(float input) {
            return (1.0f - focalLength / (focalLength + input)) /
                (1.0f - focalLength / (focalLength + 1.0f));
        }
    }

    @Override
    public String getCurrentPageDescription() {
        final int nextPageIndex = getNextPage();
        if (nextPageIndex >= 0 && nextPageIndex < getChildCount()) {
            KeyguardWidgetFrame frame = getWidgetPageAt(nextPageIndex);
            CharSequence title = frame.getChildAt(0).getContentDescription();
            if (title == null) {
                title = "";
            }
            return mContext.getString(
                    com.android.internal.R.string.keyguard_accessibility_widget_changed,
                    title, nextPageIndex + 1, getChildCount());
        }
        return super.getCurrentPageDescription();
    }

    @Override
    protected void overScroll(float amount) {
        acceleratedOverScroll(amount);
    }

    float backgroundAlphaInterpolator(float r) {
        return Math.min(1f, r);
    }

    private void updatePageAlphaValues(int screenCenter) {
        boolean isInOverscroll = mOverScrollX < 0 || mOverScrollX > mMaxScrollX;
        if (!isInOverscroll) {
            for (int i = 0; i < getChildCount(); i++) {
                KeyguardWidgetFrame child = getWidgetPageAt(i);
                if (child != null) {
                    float scrollProgress = getScrollProgress(screenCenter, child, i);
                    if (!isReordering(false)) {
                        child.setBackgroundAlphaMultiplier(
                                backgroundAlphaInterpolator(Math.abs(scrollProgress)));
                    } else {
                        child.setBackgroundAlphaMultiplier(1f);
                    }
                }
            }
        }
    }

    @Override
    protected void screenScrolled(int screenCenter) {
        updatePageAlphaValues(screenCenter);
        for (int i = 0; i < getChildCount(); i++) {
            KeyguardWidgetFrame v = getWidgetPageAt(i);
            if (v == mDragView) continue;
            if (v != null) {
                float scrollProgress = getScrollProgress(screenCenter, v, i);

                float alpha = 1.0f;

                v.setCameraDistance(mDensity * CAMERA_DISTANCE);

                if (PERFORM_OVERSCROLL_ROTATION) {
                    if (i == 0 && scrollProgress < 0) {
                        // Over scroll to the left
                        v.setRotationY(-TRANSITION_MAX_ROTATION * scrollProgress);
                        v.setOverScrollAmount(Math.abs(scrollProgress), true);
                        alpha = 1.0f;
                        // On the first page, we don't want the page to have any lateral motion
                    } else if (i == getChildCount() - 1 && scrollProgress > 0) {
                        // Over scroll to the right
                        v.setRotationY(-TRANSITION_MAX_ROTATION * scrollProgress);
                        alpha = 1.0f;
                        v.setOverScrollAmount(Math.abs(scrollProgress), false);
                        // On the last page, we don't want the page to have any lateral motion.
                    } else {
                        v.setRotationY(0f);
                        v.setOverScrollAmount(0, false);
                    }
                }
                v.setAlpha(alpha);

                // If the view has 0 alpha, we set it to be invisible so as to prevent
                // it from accepting touches
                if (alpha == 0) {
                    v.setVisibility(INVISIBLE);
                } else if (v.getVisibility() != VISIBLE) {
                    v.setVisibility(VISIBLE);
                }
            }
        }
    }
    @Override
    void boundByReorderablePages(boolean isReordering, int[] range) {
        if (isReordering) {
            if (isAddWidgetPageVisible()) {
                range[0]++;
            }
            if (isMusicWidgetVisible()) {
                range[1]--;
            }
            if (isCameraWidgetVisible()) {
                range[1]--;
            }
        }
    }

    /*
     * Special widgets
     */
    boolean isAddWidgetPageVisible() {
        // TODO: Make proper test once we decide whether the add-page is always showing
        return true;
    }
    boolean isMusicWidgetVisible() {
        // TODO: Make proper test once we have music in the list
        return false;
    }
    boolean isCameraWidgetVisible() {
        return mCameraWidgetEnabled;
    }

    @Override
    protected void onStartReordering() {
        super.onStartReordering();
        setChildrenOutlineMultiplier(1.0f);
        showOutlinesAndSidePages();
    }

    @Override
    protected void onEndReordering() {
        super.onEndReordering();
        hideOutlinesAndSidePages();
    }

    void showOutlinesAndSidePages() {
        if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();
        if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();

        PropertyValuesHolder outlinesAlpha =
                PropertyValuesHolder.ofFloat("childrenOutlineAlpha", 1.0f);
        PropertyValuesHolder sidePagesAlpha = PropertyValuesHolder.ofFloat("sidePagesAlpha", 1.0f);
        mChildrenOutlineFadeInAnimation =
                ObjectAnimator.ofPropertyValuesHolder(this, outlinesAlpha, sidePagesAlpha);

        mChildrenOutlineFadeInAnimation.setDuration(CHILDREN_OUTLINE_FADE_IN_DURATION);
        mChildrenOutlineFadeInAnimation.start();
    }

    public void showInitialPageHints() {
        // We start with everything showing
        setChildrenOutlineAlpha(1.0f);
        setSidePagesAlpha(1.0f);
        setChildrenOutlineMultiplier(1.0f);

        int currPage = getCurrentPage();
        KeyguardWidgetFrame frame = getWidgetPageAt(currPage);
        frame.setBackgroundAlphaMultiplier(0f);
    }

    void hideOutlinesAndSidePages() {
        if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();
        if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();

        PropertyValuesHolder outlinesAlpha =
                PropertyValuesHolder.ofFloat("childrenOutlineAlpha", 0f);
        PropertyValuesHolder sidePagesAlpha = PropertyValuesHolder.ofFloat("sidePagesAlpha", 0f);
        mChildrenOutlineFadeOutAnimation =
                ObjectAnimator.ofPropertyValuesHolder(this, outlinesAlpha, sidePagesAlpha);

        mChildrenOutlineFadeOutAnimation.setDuration(CHILDREN_OUTLINE_FADE_OUT_DURATION);
        mChildrenOutlineFadeOutAnimation.setStartDelay(CHILDREN_OUTLINE_FADE_OUT_DELAY);
        mChildrenOutlineFadeOutAnimation.start();
    }

    public void setChildrenOutlineAlpha(float alpha) {
        mChildrenOutlineAlpha = alpha;
        for (int i = 0; i < getChildCount(); i++) {
            getWidgetPageAt(i).setBackgroundAlpha(alpha);
        }
    }

    public void setSidePagesAlpha(float alpha) {
        // This gives the current page, or the destination page if in transit.
        int curPage = getNextPage();
        mSidePagesAlpha = alpha;
        for (int i = 0; i < getChildCount(); i++) {
            if (curPage != i) {
                getWidgetPageAt(i).setContentAlpha(alpha);
            } else {
                // We lock the current page alpha to 1.
                getWidgetPageAt(i).setContentAlpha(1.0f);
            }
        }
    }

    public void setChildrenOutlineMultiplier(float alpha) {
        mChildrenOutlineAlpha = alpha;
        for (int i = 0; i < getChildCount(); i++) {
            getWidgetPageAt(i).setBackgroundAlphaMultiplier(alpha);
        }
    }

    public float getSidePagesAlpha() {
        return mSidePagesAlpha;
    }

    public float getChildrenOutlineAlpha() {
        return mChildrenOutlineAlpha;
    }

    @Override
    public boolean onLongClick(View v) {
        if (startReordering()) {
            return true;
        }
        return false;
    }
}
