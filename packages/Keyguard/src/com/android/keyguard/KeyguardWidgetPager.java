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
package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextClock;

import com.android.internal.widget.LockPatternUtils;

import java.util.ArrayList;
import java.util.TimeZone;

public class KeyguardWidgetPager extends PagedView implements PagedView.PageSwitchListener,
        OnLongClickListener, ChallengeLayout.OnBouncerStateChangedListener {

    ZInterpolator mZInterpolator = new ZInterpolator(0.5f);
    private static float CAMERA_DISTANCE = 10000;
    protected static float OVERSCROLL_MAX_ROTATION = 30;
    private static final boolean PERFORM_OVERSCROLL_ROTATION = true;

    private static final int FLAG_HAS_LOCAL_HOUR = 0x1;
    private static final int FLAG_HAS_LOCAL_MINUTE = 0x2;

    protected KeyguardViewStateManager mViewStateManager;
    private LockPatternUtils mLockPatternUtils;

    // Related to the fading in / out background outlines
    public static final int CHILDREN_OUTLINE_FADE_OUT_DURATION = 375;
    public static final int CHILDREN_OUTLINE_FADE_IN_DURATION = 100;
    protected AnimatorSet mChildrenOutlineFadeAnimation;
    protected int mScreenCenter;
    private boolean mHasMeasure = false;
    boolean showHintsAfterLayout = false;

    private static final long CUSTOM_WIDGET_USER_ACTIVITY_TIMEOUT = 30000;
    private static final String TAG = "KeyguardWidgetPager";
    private boolean mCenterSmallWidgetsVertically;

    private int mPage = 0;
    private Callbacks mCallbacks;

    private int mWidgetToResetAfterFadeOut;
    protected boolean mShowingInitialHints = false;

    // A temporary handle to the Add-Widget view
    private View mAddWidgetView;
    private int mLastWidthMeasureSpec;
    private int mLastHeightMeasureSpec;

    // Bouncer
    private int mBouncerZoomInOutDuration = 250;
    private float BOUNCER_SCALE_FACTOR = 0.67f;

    // Background worker thread: used here for persistence, also made available to widget frames
    private final HandlerThread mBackgroundWorkerThread;
    private final Handler mBackgroundWorkerHandler;
    private boolean mCameraEventInProgress;

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

        mBackgroundWorkerThread = new HandlerThread("KeyguardWidgetPager Worker");
        mBackgroundWorkerThread.start();
        mBackgroundWorkerHandler = new Handler(mBackgroundWorkerThread.getLooper());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // Clean up the worker thread
        mBackgroundWorkerThread.quit();
    }

    public void setViewStateManager(KeyguardViewStateManager viewStateManager) {
        mViewStateManager = viewStateManager;
    }

    public void setLockPatternUtils(LockPatternUtils l) {
        mLockPatternUtils = l;
    }

    @Override
    public void onPageSwitching(View newPage, int newPageIndex) {
        if (mViewStateManager != null) {
            mViewStateManager.onPageSwitching(newPage, newPageIndex);
        }
    }

    @Override
    public void onPageSwitched(View newPage, int newPageIndex) {
        boolean showingClock = false;
        if (newPage instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) newPage;
            if (vg.getChildAt(0) instanceof KeyguardStatusView) {
                showingClock = true;
            }
        }

        if (newPage != null &&
                findClockInHierarchy(newPage) == (FLAG_HAS_LOCAL_HOUR | FLAG_HAS_LOCAL_MINUTE)) {
            showingClock = true;
        }

        // Disable the status bar clock if we're showing the default status widget
        if (showingClock) {
            setSystemUiVisibility(getSystemUiVisibility() | View.STATUS_BAR_DISABLE_CLOCK);
        } else {
            setSystemUiVisibility(getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_CLOCK);
        }

        // Extend the display timeout if the user switches pages
        if (mPage != newPageIndex) {
            int oldPageIndex = mPage;
            mPage = newPageIndex;
            userActivity();
            KeyguardWidgetFrame oldWidgetPage = getWidgetPageAt(oldPageIndex);
            if (oldWidgetPage != null) {
                oldWidgetPage.onActive(false);
            }
            KeyguardWidgetFrame newWidgetPage = getWidgetPageAt(newPageIndex);
            if (newWidgetPage != null) {
                newWidgetPage.onActive(true);
                newWidgetPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                newWidgetPage.requestAccessibilityFocus();
            }
            if (mParent != null && AccessibilityManager.getInstance(mContext).isEnabled()) {
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_VIEW_SCROLLED);
                onInitializeAccessibilityEvent(event);
                onPopulateAccessibilityEvent(event);
                mParent.requestSendAccessibilityEvent(this, event);
            }
        }
        if (mViewStateManager != null) {
            mViewStateManager.onPageSwitched(newPage, newPageIndex);
        }
    }

    @Override
    public void onPageBeginWarp() {
        showOutlinesAndSidePages();
        mViewStateManager.onPageBeginWarp();
    }

    @Override
    public void onPageEndWarp() {
        // if we're moving to the warp page, then immediately hide the other widgets.
        int duration = getPageWarpIndex() == getNextPage() ? 0 : -1;
        animateOutlinesAndSidePages(false, duration);
        mViewStateManager.onPageEndWarp();
    }

    @Override
    public void sendAccessibilityEvent(int eventType) {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || isPageMoving()) {
            super.sendAccessibilityEvent(eventType);
        }
    }

    private void updateWidgetFramesImportantForAccessibility() {
        final int pageCount = getPageCount();
        for (int i = 0; i < pageCount; i++) {
            KeyguardWidgetFrame frame = getWidgetPageAt(i);
            updateWidgetFrameImportantForAccessibility(frame);
        }
    }

    private void updateWidgetFrameImportantForAccessibility(KeyguardWidgetFrame frame) {
        if (frame.getContentAlpha() <= 0) {
            frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    private void userActivity() {
        if (mCallbacks != null) {
            mCallbacks.onUserActivityTimeoutChanged();
            mCallbacks.userActivity();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return captureUserInteraction(ev) || super.onTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return captureUserInteraction(ev) || super.onInterceptTouchEvent(ev);
    }

    private boolean captureUserInteraction(MotionEvent ev) {
        KeyguardWidgetFrame currentWidgetPage = getWidgetPageAt(getCurrentPage());
        return currentWidgetPage != null && currentWidgetPage.onUserInteraction(ev);
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
        public void onAddView(View v);
        public void onRemoveView(View v, boolean deletePermanently);
        public void onRemoveViewAnimationCompleted();
    }

    public void addWidget(View widget) {
        addWidget(widget, -1);
    }

    public void onRemoveView(View v, final boolean deletePermanently) {
        final int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
        if (mCallbacks != null) {
            mCallbacks.onRemoveView(v, deletePermanently);
        }
        mBackgroundWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                mLockPatternUtils.removeAppWidget(appWidgetId);
            }
        });
    }

    @Override
    public void onRemoveViewAnimationCompleted() {
        if (mCallbacks != null) {
            mCallbacks.onRemoveViewAnimationCompleted();
        }
    }

    public void onAddView(View v, final int index) {
        final int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
        final int[] pagesRange = new int[mTempVisiblePagesRange.length];
        getVisiblePages(pagesRange);
        boundByReorderablePages(true, pagesRange);
        if (mCallbacks != null) {
            mCallbacks.onAddView(v);
        }
        // Subtract from the index to take into account pages before the reorderable
        // pages (e.g. the "add widget" page)
        mBackgroundWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                mLockPatternUtils.addAppWidget(appWidgetId, index - pagesRange[0]);
            }
        });
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
            frame.addView(widget, lp);

            // We set whether or not this widget supports vertical resizing.
            if (widget instanceof AppWidgetHostView) {
                AppWidgetHostView awhv = (AppWidgetHostView) widget;
                AppWidgetProviderInfo info = awhv.getAppWidgetInfo();
                if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) {
                    frame.setWidgetLockedSmall(false);
                } else {
                    // Lock the widget to be small.
                    frame.setWidgetLockedSmall(true);
                    if (mCenterSmallWidgetsVertically) {
                        lp.gravity = Gravity.CENTER;
                    }
                }
            }
        } else {
            frame = (KeyguardWidgetFrame) widget;
        }

        ViewGroup.LayoutParams pageLp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        frame.setOnLongClickListener(this);
        frame.setWorkerHandler(mBackgroundWorkerHandler);

        if (pageIndex == -1) {
            addView(frame, pageLp);
        } else {
            addView(frame, pageIndex, pageLp);
        }

        // Update the frame content description.
        View content = (widget == frame) ?  frame.getContent() : widget;
        if (content != null) {
            String contentDescription = mContext.getString(
                R.string.keyguard_accessibility_widget,
                content.getContentDescription());
            frame.setContentDescription(contentDescription);
        }
        updateWidgetFrameImportantForAccessibility(frame);
    }

    /**
     * Use addWidget() instead.
     * @deprecated
     */
    @Override
    public void addView(View child, int index) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, index);
    }

    /**
     * Use addWidget() instead.
     * @deprecated
     */
    @Override
    public void addView(View child, int width, int height) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, width, height);
    }

    /**
     * Use addWidget() instead.
     * @deprecated
     */
    @Override
    public void addView(View child, LayoutParams params) {
        enforceKeyguardWidgetFrame(child);
        super.addView(child, params);
    }

    /**
     * Use addWidget() instead.
     * @deprecated
     */
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
        if (mViewStateManager != null) {
            mViewStateManager.onPageBeginMoving();
        }
        if (!isReordering(false)) {
            showOutlinesAndSidePages();
        }
        userActivity();
    }

    @Override
    protected void onPageEndMoving() {
        if (mViewStateManager != null) {
            mViewStateManager.onPageEndMoving();
        }

        // In the reordering case, the pages will be faded appropriately on completion
        // of the zoom in animation.
        if (!isReordering(false)) {
            hideOutlinesAndSidePages();
        }
    }

    protected void enablePageContentLayers() {
        int children = getChildCount();
        for (int i = 0; i < children; i++) {
            getWidgetPageAt(i).enableHardwareLayersForContent();
        }
    }

    protected void disablePageContentLayers() {
        int children = getChildCount();
        for (int i = 0; i < children; i++) {
            getWidgetPageAt(i).disableHardwareLayersForContent();
        }
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
    protected void overScroll(float amount) {
        acceleratedOverScroll(amount);
    }

    float backgroundAlphaInterpolator(float r) {
        return Math.min(1f, r);
    }

    private void updatePageAlphaValues(int screenCenter) {
    }

    public float getAlphaForPage(int screenCenter, int index, boolean showSidePages) {
        if (isWarping()) {
            return index == getPageWarpIndex() ? 1.0f : 0.0f;
        }
        if (showSidePages) {
            return 1f;
        } else {
            return index == mCurrentPage ? 1.0f : 0f;
        }
    }

    public float getOutlineAlphaForPage(int screenCenter, int index, boolean showSidePages) {
        if (showSidePages) {
            return getAlphaForPage(screenCenter, index, showSidePages)
                    * KeyguardWidgetFrame.OUTLINE_ALPHA_MULTIPLIER;
        } else {
            return 0f;
        }
    }

    protected boolean isOverScrollChild(int index, float scrollProgress) {
        boolean isInOverscroll = mOverScrollX < 0 || mOverScrollX > mMaxScrollX;
        return (isInOverscroll && (index == 0 && scrollProgress < 0 ||
                index == getChildCount() - 1 && scrollProgress > 0));
    }

    @Override
    protected void screenScrolled(int screenCenter) {
        mScreenCenter = screenCenter;
        updatePageAlphaValues(screenCenter);
        for (int i = 0; i < getChildCount(); i++) {
            KeyguardWidgetFrame v = getWidgetPageAt(i);
            if (v == mDragView) continue;
            if (v != null) {
                float scrollProgress = getScrollProgress(screenCenter, v, i);

                v.setCameraDistance(mDensity * CAMERA_DISTANCE);

                if (isOverScrollChild(i, scrollProgress) && PERFORM_OVERSCROLL_ROTATION) {
                    float pivotX = v.getMeasuredWidth() / 2;
                    float pivotY = v.getMeasuredHeight() / 2;
                    v.setPivotX(pivotX);
                    v.setPivotY(pivotY);
                    v.setRotationY(- OVERSCROLL_MAX_ROTATION * scrollProgress);
                    v.setOverScrollAmount(Math.abs(scrollProgress), scrollProgress < 0);
                } else {
                    v.setRotationY(0f);
                    v.setOverScrollAmount(0, false);
                }

                float alpha = v.getAlpha();
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

    public boolean isWidgetPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= getChildCount()) {
            return false;
        }
        View v = getChildAt(pageIndex);
        if (v != null && v instanceof KeyguardWidgetFrame) {
            KeyguardWidgetFrame kwf = (KeyguardWidgetFrame) v;
            return kwf.getContentAppWidgetId() != AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        return false;
    }

    /**
     * Returns the bounded set of pages that are re-orderable.  The range is fully inclusive.
     */
    @Override
    void boundByReorderablePages(boolean isReordering, int[] range) {
        if (isReordering) {
            // Remove non-widget pages from the range
            while (range[1] >= range[0] && !isWidgetPage(range[1])) {
                range[1]--;
            }
            while (range[0] <= range[1] && !isWidgetPage(range[0])) {
                range[0]++;
            }
        }
    }

    protected void reorderStarting() {
        showOutlinesAndSidePages();
    }

    @Override
    protected void onStartReordering() {
        super.onStartReordering();
        enablePageContentLayers();
        reorderStarting();
    }

    @Override
    protected void onEndReordering() {
        super.onEndReordering();
        hideOutlinesAndSidePages();
    }

    void showOutlinesAndSidePages() {
        animateOutlinesAndSidePages(true);
    }

    void hideOutlinesAndSidePages() {
        animateOutlinesAndSidePages(false);
    }

    void updateChildrenContentAlpha(float sidePageAlpha) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame child = getWidgetPageAt(i);
            if (i != mCurrentPage) {
                child.setBackgroundAlpha(sidePageAlpha);
                child.setContentAlpha(0f);
            } else {
                child.setBackgroundAlpha(0f);
                child.setContentAlpha(1f);
            }
        }
    }

    public void showInitialPageHints() {
        mShowingInitialHints = true;
        updateChildrenContentAlpha(KeyguardWidgetFrame.OUTLINE_ALPHA_MULTIPLIER);
    }

    @Override
    void setCurrentPage(int currentPage) {
        super.setCurrentPage(currentPage);
        updateChildrenContentAlpha(0.0f);
        updateWidgetFramesImportantForAccessibility();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHasMeasure = false;
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mLastWidthMeasureSpec = widthMeasureSpec;
        mLastHeightMeasureSpec = heightMeasureSpec;

        int maxChallengeTop = -1;
        View parent = (View) getParent();
        boolean challengeShowing = false;
        // Widget pages need to know where the top of the sliding challenge is so that they
        // now how big the widget should be when the challenge is up. We compute it here and
        // then propagate it to each of our children.
        if (parent.getParent() instanceof SlidingChallengeLayout) {
            SlidingChallengeLayout scl = (SlidingChallengeLayout) parent.getParent();
            int top = scl.getMaxChallengeTop();

            // This is a bit evil, but we need to map a coordinate relative to the SCL into a
            // coordinate relative to our children, hence we subtract the top padding.s
            maxChallengeTop = top - getPaddingTop();
            challengeShowing = scl.isChallengeShowing();

            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                KeyguardWidgetFrame frame = getWidgetPageAt(i);
                frame.setMaxChallengeTop(maxChallengeTop);
                // On the very first measure pass, if the challenge is showing, we need to make sure
                // that the widget on the current page is small.
                if (challengeShowing && i == mCurrentPage && !mHasMeasure) {
                    frame.shrinkWidget(true);
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mHasMeasure = true;
    }

    void animateOutlinesAndSidePages(final boolean show) {
        animateOutlinesAndSidePages(show, -1);
    }

    public void setWidgetToResetOnPageFadeOut(int widget) {
        mWidgetToResetAfterFadeOut = widget;
    }

    public int getWidgetToResetOnPageFadeOut() {
        return mWidgetToResetAfterFadeOut;
    }

    void animateOutlinesAndSidePages(final boolean show, int duration) {
        if (mChildrenOutlineFadeAnimation != null) {
            mChildrenOutlineFadeAnimation.cancel();
            mChildrenOutlineFadeAnimation = null;
        }
        int count = getChildCount();
        PropertyValuesHolder alpha;
        ArrayList<Animator> anims = new ArrayList<Animator>();

        if (duration == -1) {
            duration = show ? CHILDREN_OUTLINE_FADE_IN_DURATION :
                CHILDREN_OUTLINE_FADE_OUT_DURATION;
        }

        int curPage = getNextPage();
        for (int i = 0; i < count; i++) {
            float finalContentAlpha;
            if (show) {
                finalContentAlpha = getAlphaForPage(mScreenCenter, i, true);
            } else if (!show && i == curPage) {
                finalContentAlpha = 1f;
            } else {
                finalContentAlpha = 0f;
            }
            KeyguardWidgetFrame child = getWidgetPageAt(i);

            alpha = PropertyValuesHolder.ofFloat("contentAlpha", finalContentAlpha);
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(child, alpha);
            anims.add(a);

            float finalOutlineAlpha = show ? getOutlineAlphaForPage(mScreenCenter, i, true) : 0f;
            child.fadeFrame(this, show, finalOutlineAlpha, duration);
        }

        mChildrenOutlineFadeAnimation = new AnimatorSet();
        mChildrenOutlineFadeAnimation.playTogether(anims);

        mChildrenOutlineFadeAnimation.setDuration(duration);
        mChildrenOutlineFadeAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (show) {
                    enablePageContentLayers();
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!show) {
                    disablePageContentLayers();
                    KeyguardWidgetFrame frame = getWidgetPageAt(mWidgetToResetAfterFadeOut);
                    if (frame != null && !(frame == getWidgetPageAt(mCurrentPage) &&
                            mViewStateManager.isChallengeOverlapping())) {
                        frame.resetSize();
                    }
                    mWidgetToResetAfterFadeOut = -1;
                    mShowingInitialHints = false;
                }
                updateWidgetFramesImportantForAccessibility();
            }
        });
        mChildrenOutlineFadeAnimation.start();
    }

    @Override
    public boolean onLongClick(View v) {
        // Disallow long pressing to reorder if the challenge is showing
        boolean isChallengeOverlapping = mViewStateManager.isChallengeShowing() &&
                mViewStateManager.isChallengeOverlapping();
        if (!isChallengeOverlapping && startReordering()) {
            return true;
        }
        return false;
    }

    public void removeWidget(View view) {
        if (view instanceof KeyguardWidgetFrame) {
            removeView(view);
        } else {
            // Assume view was wrapped by a KeyguardWidgetFrame in KeyguardWidgetPager#addWidget().
            // This supports legacy hard-coded "widgets" like KeyguardTransportControlView.
            int pos = getWidgetPageIndex(view);
            if (pos != -1) {
                KeyguardWidgetFrame frame = (KeyguardWidgetFrame) getChildAt(pos);
                frame.removeView(view);
                removeView(frame);
            } else {
                Slog.w(TAG, "removeWidget() can't find:" + view);
            }
        }
    }

    public int getWidgetPageIndex(View view) {
        if (view instanceof KeyguardWidgetFrame) {
            return indexOfChild(view);
        } else {
            // View was wrapped by a KeyguardWidgetFrame by KeyguardWidgetPager#addWidget()
            return indexOfChild((KeyguardWidgetFrame)view.getParent());
        }
    }

    @Override
    protected void setPageHoveringOverDeleteDropTarget(int viewIndex, boolean isHovering) {
        KeyguardWidgetFrame child = getWidgetPageAt(viewIndex);
        child.setIsHoveringOverDeleteDropTarget(isHovering);
    }

    // ChallengeLayout.OnBouncerStateChangedListener
    @Override
    public void onBouncerStateChanged(boolean bouncerActive) {
        if (bouncerActive) {
            zoomOutToBouncer();
        } else {
            zoomInFromBouncer();
        }
    }

    void setBouncerAnimationDuration(int duration) {
        mBouncerZoomInOutDuration = duration;
    }

    // Zoom in after the bouncer is dismissed
    void zoomInFromBouncer() {
        if (mZoomInOutAnim != null && mZoomInOutAnim.isRunning()) {
            mZoomInOutAnim.cancel();
        }
        final View currentPage = getPageAt(getCurrentPage());
        if (currentPage.getScaleX() < 1f || currentPage.getScaleY() < 1f) {
            mZoomInOutAnim = new AnimatorSet();
            mZoomInOutAnim.playTogether(
                    ObjectAnimator.ofFloat(currentPage, "scaleX", 1f),
                    ObjectAnimator.ofFloat(currentPage , "scaleY", 1f));
            mZoomInOutAnim.setDuration(mBouncerZoomInOutDuration);
            mZoomInOutAnim.setInterpolator(new DecelerateInterpolator(1.5f));
            mZoomInOutAnim.start();
        }
        if (currentPage instanceof KeyguardWidgetFrame) {
            ((KeyguardWidgetFrame)currentPage).onBouncerShowing(false);
        }
    }

    // Zoom out after the bouncer is initiated
    void zoomOutToBouncer() {
        if (mZoomInOutAnim != null && mZoomInOutAnim.isRunning()) {
            mZoomInOutAnim.cancel();
        }
        int curPage = getCurrentPage();
        View currentPage = getPageAt(curPage);
        if (shouldSetTopAlignedPivotForWidget(curPage)) {
            currentPage.setPivotY(0);
            // Note: we are working around the issue that setting the x-pivot to the same value as it
            //       was does not actually work.
            currentPage.setPivotX(0);
            currentPage.setPivotX(currentPage.getMeasuredWidth() / 2);
        }
        if (!(currentPage.getScaleX() < 1f || currentPage.getScaleY() < 1f)) {
            mZoomInOutAnim = new AnimatorSet();
            mZoomInOutAnim.playTogether(
                    ObjectAnimator.ofFloat(currentPage, "scaleX", BOUNCER_SCALE_FACTOR),
                    ObjectAnimator.ofFloat(currentPage, "scaleY", BOUNCER_SCALE_FACTOR));
            mZoomInOutAnim.setDuration(mBouncerZoomInOutDuration);
            mZoomInOutAnim.setInterpolator(new DecelerateInterpolator(1.5f));
            mZoomInOutAnim.start();
        }
        if (currentPage instanceof KeyguardWidgetFrame) {
            ((KeyguardWidgetFrame)currentPage).onBouncerShowing(true);
        }
    }

    void setAddWidgetEnabled(boolean enabled) {
        if (mAddWidgetView != null && enabled) {
            addView(mAddWidgetView, 0);
            // We need to force measure the PagedView so that the calls to update the scroll
            // position below work
            measure(mLastWidthMeasureSpec, mLastHeightMeasureSpec);
            // Bump up the current page to account for the addition of the new page
            setCurrentPage(mCurrentPage + 1);
            mAddWidgetView = null;
        } else if (mAddWidgetView == null && !enabled) {
            View addWidget = findViewById(R.id.keyguard_add_widget);
            if (addWidget != null) {
                mAddWidgetView = addWidget;
                removeView(addWidget);
            }
        }
    }

    boolean isAddPage(int pageIndex) {
        View v = getChildAt(pageIndex);
        return v != null && v.getId() == R.id.keyguard_add_widget;
    }

    boolean isCameraPage(int pageIndex) {
        View v = getChildAt(pageIndex);
        return v != null && v instanceof CameraWidgetFrame;
    }

    @Override
    protected boolean shouldSetTopAlignedPivotForWidget(int childIndex) {
        return !isCameraPage(childIndex) && super.shouldSetTopAlignedPivotForWidget(childIndex);
    }

    /**
     * Search given {@link View} hierarchy for {@link TextClock} instances that
     * show various time components. Returns combination of
     * {@link #FLAG_HAS_LOCAL_HOUR} and {@link #FLAG_HAS_LOCAL_MINUTE}.
     */
    private static int findClockInHierarchy(View view) {
        if (view instanceof TextClock) {
            return getClockFlags((TextClock) view);
        } else if (view instanceof ViewGroup) {
            int flags = 0;
            final ViewGroup group = (ViewGroup) view;
            final int size = group.getChildCount();
            for (int i = 0; i < size; i++) {
                flags |= findClockInHierarchy(group.getChildAt(i));
            }
            return flags;
        } else {
            return 0;
        }
    }

    /**
     * Return combination of {@link #FLAG_HAS_LOCAL_HOUR} and
     * {@link #FLAG_HAS_LOCAL_MINUTE} describing the time represented described
     * by the given {@link TextClock}.
     */
    private static int getClockFlags(TextClock clock) {
        int flags = 0;

        final String timeZone = clock.getTimeZone();
        if (timeZone != null && !TimeZone.getDefault().equals(TimeZone.getTimeZone(timeZone))) {
            // Ignore clocks showing another timezone
            return 0;
        }

        final CharSequence format = clock.getFormat();
        final char hour = clock.is24HourModeEnabled() ? DateFormat.HOUR_OF_DAY
                : DateFormat.HOUR;

        if (DateFormat.hasDesignator(format, hour)) {
            flags |= FLAG_HAS_LOCAL_HOUR;
        }
        if (DateFormat.hasDesignator(format, DateFormat.MINUTE)) {
            flags |= FLAG_HAS_LOCAL_MINUTE;
        }

        return flags;
    }

    public void handleExternalCameraEvent(MotionEvent event) {
        beginCameraEvent();
        int cameraPage = getPageCount() - 1;
        boolean endWarp = false;
        if (isCameraPage(cameraPage) || mCameraEventInProgress) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Once we start dispatching camera events, we must continue to do so
                    // to keep event dispatch happy.
                    mCameraEventInProgress = true;
                    userActivity();
                    startPageWarp(cameraPage);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mCameraEventInProgress = false;
                    endWarp = isWarping();
                    break;
            }
            dispatchTouchEvent(event);
            // This has to happen after the event has been handled by the real widget pager
            if (endWarp) stopPageWarp();
        }
        endCameraEvent();
    }

}
