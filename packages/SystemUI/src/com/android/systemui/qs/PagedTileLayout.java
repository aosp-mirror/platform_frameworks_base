package com.android.systemui.qs;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Scroller;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final String CURRENT_PAGE = "current_page";
    private static final int NO_PAGE = -1;

    private static final int REVEAL_SCROLL_DURATION_MILLIS = 750;
    private static final int SINGLE_PAGE_SCROLL_DURATION_MILLIS = 300;
    private static final float BOUNCE_ANIMATION_TENSION = 1.3f;
    private static final long BOUNCE_ANIMATION_DURATION = 450L;
    private static final int TILE_ANIMATION_STAGGER_DELAY = 85;
    private static final Interpolator SCROLL_CUBIC = (t) -> {
        t -= 1.0f;
        return t * t * t + 1.0f;
    };

    private final ArrayList<TileRecord> mTiles = new ArrayList<>();
    private final ArrayList<TileLayout> mPages = new ArrayList<>();

    private QSLogger mLogger;
    @Nullable
    private PageIndicator mPageIndicator;
    private float mPageIndicatorPosition;

    @Nullable
    private PageListener mPageListener;

    private boolean mListening;
    @VisibleForTesting Scroller mScroller;

    /* set of animations used to indicate which tiles were just revealed  */
    @Nullable
    private AnimatorSet mBounceAnimatorSet;
    private float mLastExpansion;
    private boolean mDistributeTiles = false;
    private int mPageToRestore = -1;
    private int mLayoutOrientation;
    private int mLayoutDirection;
    private final UiEventLogger mUiEventLogger = QSEvents.INSTANCE.getQsUiEventsLogger();
    private int mExcessHeight;
    private int mLastExcessHeight;
    private int mMinRows = 1;
    private int mMaxColumns = TileLayout.NO_MAX_COLUMNS;

    /**
     * it's fine to read this value when class is initialized because SysUI is always restarted
     * when running tests in test harness, see SysUiTestIsolationRule. This check is done quite
     * often - with every shade open action - so we don't want to potentially make it less
     * performant only for test use case
     */
    private boolean mRunningInTestHarness = ActivityManager.isRunningInTestHarness();

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = new Scroller(context, SCROLL_CUBIC);
        setAdapter(mAdapter);
        setOnPageChangeListener(mOnPageChangeListener);
        setCurrentItem(0, false);
        mLayoutOrientation = getResources().getConfiguration().orientation;
        mLayoutDirection = getLayoutDirection();
    }
    private int mLastMaxHeight = -1;

    @Override
    public void setPageMargin(int marginPixels) {
        // Using page margins creates some rounding issues that interfere with the correct position
        // in the onPageChangedListener and therefore present bad positions to the PageIndicator.
        // Instead, we use negative margins in the container and positive padding in the pages,
        // matching the margin set from QSContainerImpl (note that new pages will always be inflated
        // with the correct value.
        // QSContainerImpl resources are set onAttachedView, so this view will always have the right
        // values when attached.
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.setMarginStart(-marginPixels);
        lp.setMarginEnd(-marginPixels);
        setLayoutParams(lp);

        int nPages = mPages.size();
        for (int i = 0; i < nPages; i++) {
            View v = mPages.get(i);
            v.setPadding(marginPixels, v.getPaddingTop(), marginPixels, v.getPaddingBottom());
        }
    }

    public void saveInstanceState(Bundle outState) {
        int resolvedPage = mPageToRestore != NO_PAGE ? mPageToRestore : getCurrentPageNumber();
        outState.putInt(CURRENT_PAGE, resolvedPage);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        // There's only 1 page at this point. We want to restore the correct page once the
        // pages have been inflated
        mPageToRestore = savedInstanceState.getInt(CURRENT_PAGE, NO_PAGE);
    }

    @Override
    public int getTilesHeight() {
        // Use the first page as that is the maximum height we need to show.
        TileLayout tileLayout = mPages.get(0);
        if (tileLayout == null) {
            return 0;
        }
        return tileLayout.getTilesHeight();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Pass configuration change to non-attached pages as well. Some config changes will cause
        // QS to recreate itself (as determined in FragmentHostManager), but in order to minimize
        // those, make sure that all get passed to all pages.
        int numPages = mPages.size();
        for (int i = 0; i < numPages; i++) {
            View page = mPages.get(i);
            if (page.getParent() == null) {
                page.dispatchConfigurationChanged(newConfig);
            }
        }
        if (mLayoutOrientation != newConfig.orientation) {
            mLayoutOrientation = newConfig.orientation;
            forceTilesRedistribution("orientation changed to " + mLayoutOrientation);
            setCurrentItem(0, false);
            mPageToRestore = 0;
        } else {
            // logging in case we missed redistribution because orientation was not changed
            // while configuration changed, can be removed after b/255208946 is fixed
            mLogger.d(
                    "Orientation didn't change, tiles might be not redistributed, new config",
                    newConfig);
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        // The configuration change will change the flag in the view (that's returned in
        // isLayoutRtl). As we detect the change, we use the cached direction to store the page
        // before setting it.
        final int page = getPageNumberForDirection(mLayoutDirection == LAYOUT_DIRECTION_RTL);
        super.onRtlPropertiesChanged(layoutDirection);
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            setAdapter(mAdapter);
            setCurrentItem(page, false);
        }
    }

    @Override
    public void setCurrentItem(int item, boolean smoothScroll) {
        if (isLayoutRtl()) {
            item = mPages.size() - 1 - item;
        }
        super.setCurrentItem(item, smoothScroll);
    }

    /**
     * Obtains the current page number respecting RTL
     */
    private int getCurrentPageNumber() {
        return getPageNumberForDirection(isLayoutRtl());
    }

    private int getPageNumberForDirection(boolean isLayoutRTL) {
        int page = getCurrentItem();
        if (isLayoutRTL) {
            page = mPages.size() - 1 - page;
        }
        return page;
    }

    // This will dump to the ui log all the tiles that are visible in this page
    private void logVisibleTiles(TileLayout page) {
        for (int i = 0; i < page.mRecords.size(); i++) {
            QSTile t = page.mRecords.get(i).tile;
            mUiEventLogger.logWithInstanceId(QSEvent.QS_TILE_VISIBLE, 0, t.getMetricsSpec(),
                    t.getInstanceId());
        }
    }

    @Override
    public void setListening(boolean listening, UiEventLogger uiEventLogger) {
        if (mListening == listening) return;
        mListening = listening;
        updateListening();
    }

    @Override
    public void setSquishinessFraction(float squishinessFraction) {
        int nPages = mPages.size();
        for (int i = 0; i < nPages; i++) {
            mPages.get(i).setSquishinessFraction(squishinessFraction);
        }
    }

    private void updateListening() {
        for (TileLayout tilePage : mPages) {
            tilePage.setListening(tilePage.getParent() != null && mListening);
        }
    }

    @Override
    public void fakeDragBy(float xOffset) {
        try {
            super.fakeDragBy(xOffset);
            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
        } catch (NullPointerException e) {
            mLogger.logException("FakeDragBy called before begin", e);
            // If we were trying to fake drag, it means we just added a new tile to the last
            // page, so animate there.
            final int lastPageNumber = mPages.size() - 1;
            post(() -> {
                setCurrentItem(lastPageNumber, true);
                if (mBounceAnimatorSet != null) {
                    mBounceAnimatorSet.start();
                }
                setOffscreenPageLimit(1);
            });
        }
    }

    @Override
    public void endFakeDrag() {
        try {
            super.endFakeDrag();
        } catch (NullPointerException e) {
            // Not sure what's going on. Let's log it
            mLogger.logException("endFakeDrag called without velocityTracker", e);
        }
    }

    @Override
    public void computeScroll() {
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            if (!isFakeDragging()) {
                beginFakeDrag();
            }
            fakeDragBy(getScrollX() - mScroller.getCurrX());
        } else if (isFakeDragging()) {
            endFakeDrag();
            if (mBounceAnimatorSet != null) {
                mBounceAnimatorSet.start();
            }
            setOffscreenPageLimit(1);
        }
        super.computeScroll();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPages.add(createTileLayout());
        mAdapter.notifyDataSetChanged();
    }

    private TileLayout createTileLayout() {
        TileLayout page = (TileLayout) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_paged_page, this, false);
        page.setMinRows(mMinRows);
        page.setMaxColumns(mMaxColumns);
        page.setSelected(false);
        return page;
    }

    public void setPageIndicator(PageIndicator indicator) {
        mPageIndicator = indicator;
        mPageIndicator.setNumPages(mPages.size());
        mPageIndicator.setLocation(mPageIndicatorPosition);
        mPageIndicator.setOnKeyListener((view, keyCode, keyEvent) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // only scroll on ACTION_UP as we don't handle longpressing for now. Still we need
                // to intercept even ACTION_DOWN otherwise keyboard focus will be moved before we
                // have a chance to intercept ACTION_UP.
                if (keyEvent.getAction() == KeyEvent.ACTION_UP && mScroller.isFinished()) {
                    scrollByX(getDeltaXForKeyboardScrolling(keyCode),
                            SINGLE_PAGE_SCROLL_DURATION_MILLIS);
                }
                return true;
            }
            return false;
        });
    }

    private int getDeltaXForKeyboardScrolling(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && getCurrentItem() != 0) {
            return -getWidth();
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                && getCurrentItem() != mPages.size() - 1) {
            return getWidth();
        }
        return 0;
    }

    private void scrollByX(int x, int durationMillis) {
        if (x != 0) {
            mScroller.startScroll(/* startX= */ getScrollX(), /* startY= */ getScrollY(),
                    /* dx= */ x, /* dy= */ 0, /* duration= */ durationMillis);
            // scroller just sets its state, we need to invalidate view to actually start scrolling
            postInvalidateOnAnimation();
        }
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        final ViewGroup parent = (ViewGroup) tile.tileView.getParent();
        if (parent == null) return 0;
        return parent.getTop() + getTop();
    }

    @Override
    public void addTile(TileRecord tile) {
        mTiles.add(tile);
        forceTilesRedistribution("adding new tile");
        requestLayout();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            forceTilesRedistribution("removing tile");
            requestLayout();
        }
    }

    @Override
    public void setExpansion(float expansion, float proposedTranslation) {
        mLastExpansion = expansion;
        updateSelected();
    }

    private void updateSelected() {
        // Start the marquee when fully expanded and stop when fully collapsed. Leave as is for
        // other expansion ratios since there is no way way to pause the marquee.
        if (mLastExpansion > 0f && mLastExpansion < 1f) {
            return;
        }
        boolean selected = mLastExpansion == 1f;

        // Disable accessibility temporarily while we update selected state purely for the
        // marquee. This will ensure that accessibility doesn't announce the TYPE_VIEW_SELECTED
        // event on any of the children.
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int currentItem = getCurrentPageNumber();
        for (int i = 0; i < mPages.size(); i++) {
            TileLayout page = mPages.get(i);
            page.setSelected(i == currentItem ? selected : false);
            if (page.isSelected()) {
                logVisibleTiles(page);
            }
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public void setPageListener(PageListener listener) {
        mPageListener = listener;
    }

    public List<String> getSpecsForPage(int page) {
        ArrayList<String> out = new ArrayList<>();
        if (page < 0) return out;
        int perPage = mPages.get(0).maxTiles();
        int startOfPage = page * perPage;
        int endOfPage = (page + 1) * perPage;
        for (int i = startOfPage; i < endOfPage && i < mTiles.size(); i++) {
            out.add(mTiles.get(i).tile.getTileSpec());
        }
        return out;
    }

    private void distributeTiles() {
        emptyAndInflateOrRemovePages();

        final int tilesPerPageCount = mPages.get(0).maxTiles();
        int index = 0;
        final int totalTilesCount = mTiles.size();
        mLogger.logTileDistributionInProgress(tilesPerPageCount, totalTilesCount);
        for (int i = 0; i < totalTilesCount; i++) {
            TileRecord tile = mTiles.get(i);
            if (mPages.get(index).mRecords.size() == tilesPerPageCount) index++;
            mLogger.logTileDistributed(tile.tile.getClass().getSimpleName(), index);
            mPages.get(index).addTile(tile);
        }
    }

    private void emptyAndInflateOrRemovePages() {
        final int numPages = getNumPages();
        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        if (NP == numPages) {
            return;
        }
        while (mPages.size() < numPages) {
            mLogger.d("Adding new page");
            mPages.add(createTileLayout());
        }
        while (mPages.size() > numPages) {
            mLogger.d("Removing page");
            mPages.remove(mPages.size() - 1);
        }
        mPageIndicator.setNumPages(mPages.size());
        setAdapter(mAdapter);
        mAdapter.notifyDataSetChanged();
        if (mPageToRestore != NO_PAGE) {
            setCurrentItem(mPageToRestore, false);
            mPageToRestore = NO_PAGE;
        }
    }

    @Override
    public boolean updateResources() {
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            changed |= mPages.get(i).updateResources();
        }
        if (changed) {
            forceTilesRedistribution("resources in pages changed");
            requestLayout();
        } else {
            // logging in case we missed redistribution because number of column in updateResources
            // was not changed, can be removed after b/255208946 is fixed
            mLogger.d("resource in pages didn't change, tiles might be not redistributed");
        }
        return changed;
    }

    @Override
    public boolean setMinRows(int minRows) {
        mMinRows = minRows;
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            if (mPages.get(i).setMinRows(minRows)) {
                changed = true;
                forceTilesRedistribution("minRows changed in page");
            }
        }
        return changed;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            if (mPages.get(i).setMaxColumns(maxColumns)) {
                changed = true;
                forceTilesRedistribution("maxColumns in pages changed");
            }
        }
        return changed;
    }

    /**
     * Set the amount of excess space that we gave this view compared to the actual available
     * height. This is because this view is in a scrollview.
     */
    public void setExcessHeight(int excessHeight) {
        mExcessHeight = excessHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        final int nTiles = mTiles.size();
        // If we have no reason to recalculate the number of rows, skip this step. In particular,
        // if the height passed by its parent is the same as the last time, we try not to remeasure.
        if (mDistributeTiles || mLastMaxHeight != MeasureSpec.getSize(heightMeasureSpec)
                || mLastExcessHeight != mExcessHeight) {

            mLastMaxHeight = MeasureSpec.getSize(heightMeasureSpec);
            mLastExcessHeight = mExcessHeight;
            // Only change the pages if the number of rows or columns (from updateResources) has
            // changed or the tiles have changed
            int availableHeight = mLastMaxHeight - mExcessHeight;
            if (mPages.get(0).updateMaxRows(availableHeight, nTiles) || mDistributeTiles) {
                mDistributeTiles = false;
                distributeTiles();
            }

            final int nRows = mPages.get(0).mRows;
            for (int i = 0; i < mPages.size(); i++) {
                TileLayout t = mPages.get(i);
                t.mRows = nRows;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // The ViewPager likes to eat all of the space, instead force it to wrap to the max height
        // of the pages.
        int maxHeight = 0;
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            int height = getChildAt(i).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        if (mPages.get(0).getParent() == null) {
            // Measure page 0 so we know how tall it is if it's not attached to the pager.
            mPages.get(0).measure(widthMeasureSpec, heightMeasureSpec);
            int height = mPages.get(0).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), maxHeight + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mPages.get(0).getParent() == null) {
            // Layout page 0, so we can get the bottom of the tiles. We only do this if the page
            // is not attached.
            mPages.get(0).layout(l, t, r, b);
        }
    }

    public int getColumnCount() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mColumns;
    }

    /**
     * Gets the number of pages in this paged tile layout
     */
    public int getNumPages() {
        final int nTiles = mTiles.size();
        // We should always have at least one page, even if it's empty.
        int numPages = Math.max(nTiles / mPages.get(0).maxTiles(), 1);

        // Add one more not full page if needed
        if (nTiles > numPages * mPages.get(0).maxTiles()) {
            numPages++;
        }

        return numPages;
    }

    public int getNumVisibleTiles() {
        if (mPages.size() == 0) return 0;
        TileLayout currentPage = mPages.get(getCurrentPageNumber());
        return currentPage.mRecords.size();
    }

    public int getNumTilesFirstPage() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mRecords.size();
    }

    public void startTileReveal(Set<String> tilesToReveal, final Runnable postAnimation) {
        if (shouldNotRunAnimation(tilesToReveal)) {
            return;
        }
        // This method has side effects (beings the fake drag, if it returns true). If we have
        // decided that we want to do a tile reveal, we do a last check to verify that we can
        // actually perform a fake drag.
        if (!beginFakeDrag()) {
            return;
        }

        final int lastPageNumber = mPages.size() - 1;
        final TileLayout lastPage = mPages.get(lastPageNumber);
        final ArrayList<Animator> bounceAnims = new ArrayList<>();
        for (TileRecord tr : lastPage.mRecords) {
            if (tilesToReveal.contains(tr.tile.getTileSpec())) {
                bounceAnims.add(setupBounceAnimator(tr.tileView, bounceAnims.size()));
            }
        }

        if (bounceAnims.isEmpty()) {
            // All tilesToReveal are on the first page. Nothing to do.
            // TODO: potentially show a bounce animation for first page QS tiles
            endFakeDrag();
            return;
        }

        mBounceAnimatorSet = new AnimatorSet();
        mBounceAnimatorSet.playTogether(bounceAnims);
        mBounceAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBounceAnimatorSet = null;
                postAnimation.run();
            }
        });
        setOffscreenPageLimit(lastPageNumber); // Ensure the page to reveal has been inflated.
        int dx = getWidth() * lastPageNumber;
        scrollByX(isLayoutRtl() ? -dx : dx, REVEAL_SCROLL_DURATION_MILLIS);
    }

    private boolean shouldNotRunAnimation(Set<String> tilesToReveal) {
        // None of these have side effects. That way, we don't need to rely on short-circuiting
        // behavior
        boolean noAnimationNeeded = tilesToReveal.isEmpty() || mPages.size() < 2;
        boolean scrollingInProgress = getScrollX() != 0 || !isFakeDragging();
        // checking mRunningInTestHarness to disable animation in functional testing as it caused
        // flakiness and is not needed there. Alternative solutions were more complex and would
        // still be either potentially flaky or modify internal data.
        // For more info see b/253493927 and b/293234595
        return noAnimationNeeded || scrollingInProgress || mRunningInTestHarness;
    }

    private int sanitizePageAction(int action) {
        int pageLeftId = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.getId();
        int pageRightId = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.getId();
        if (action == pageLeftId || action == pageRightId) {
            if (!isLayoutRtl()) {
                if (action == pageLeftId) {
                    return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                } else {
                    return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                }
            } else {
                if (action == pageLeftId) {
                    return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                } else {
                    return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                }
            }
        }
        return action;
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        action = sanitizePageAction(action);
        boolean performed = super.performAccessibilityAction(action, arguments);
        if (performed && (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            requestAccessibilityFocus();
        }
        return performed;
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        // getCurrentItem does not respect RTL, so it works well together with page actions that
        // use left/right positioning.
        if (getCurrentItem() != 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT);
        }
        if (getCurrentItem() != mPages.size() - 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mAdapter != null && mAdapter.getCount() > 0) {
            event.setItemCount(mAdapter.getCount());
            event.setFromIndex(getCurrentPageNumber());
            event.setToIndex(getCurrentPageNumber());
        }
    }

    private static Animator setupBounceAnimator(View view, int ordinal) {
        view.setAlpha(0f);
        view.setScaleX(0f);
        view.setScaleY(0f);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1));
        animator.setDuration(BOUNCE_ANIMATION_DURATION);
        animator.setStartDelay(ordinal * TILE_ANIMATION_STAGGER_DELAY);
        animator.setInterpolator(new OvershootInterpolator(BOUNCE_ANIMATION_TENSION));
        return animator;
    }

    private final ViewPager.OnPageChangeListener mOnPageChangeListener =
            new ViewPager.SimpleOnPageChangeListener() {

                private int mCurrentScrollState = SCROLL_STATE_IDLE;
                // Flag to avoid redundant call InteractionJankMonitor::begin()
                private boolean mIsScrollJankTraceBegin = false;

                @Override
                public void onPageSelected(int position) {
                    updateSelected();
                    if (mPageIndicator == null) return;
                    if (mPageListener != null) {
                        int pageNumber = isLayoutRtl() ? mPages.size() - 1 - position : position;
                        mPageListener.onPageChanged(pageNumber == 0, pageNumber);
                    }
                }

                @Override
                public void onPageScrolled(int position, float positionOffset,
                        int positionOffsetPixels) {

                    if (!mIsScrollJankTraceBegin && mCurrentScrollState == SCROLL_STATE_DRAGGING) {
                        InteractionJankMonitor.getInstance().begin(PagedTileLayout.this,
                                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE);
                        mIsScrollJankTraceBegin = true;
                    }

                    if (mPageIndicator == null) return;
                    mPageIndicatorPosition = position + positionOffset;
                    mPageIndicator.setLocation(mPageIndicatorPosition);
                    if (mPageListener != null) {
                        int pageNumber = isLayoutRtl() ? mPages.size() - 1 - position : position;
                        mPageListener.onPageChanged(
                                positionOffsetPixels == 0 && pageNumber == 0,
                                // Send only valid page number on integer pages
                                positionOffsetPixels == 0 ? pageNumber : PageListener.INVALID_PAGE
                        );
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    if (state != mCurrentScrollState && state == SCROLL_STATE_IDLE) {
                        InteractionJankMonitor.getInstance().end(
                                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE);
                        mIsScrollJankTraceBegin = false;
                    }
                    mCurrentScrollState = state;
                }
            };

    private final PagerAdapter mAdapter = new PagerAdapter() {
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            mLogger.d("Destantiating page at", position);
            container.removeView((View) object);
            updateListening();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            mLogger.d("Instantiating page at", position);
            if (isLayoutRtl()) {
                position = mPages.size() - 1 - position;
            }
            ViewGroup view = mPages.get(position);
            if (view.getParent() != null) {
                container.removeView(view);
            }
            container.addView(view);
            updateListening();
            return view;
        }

        @Override
        public int getCount() {
            return mPages.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    };

    /**
     * Force all tiles to be redistributed across pages.
     * Should be called when one of the following changes: rows, columns, number of tiles.
     */
    public void forceTilesRedistribution(String reason) {
        mLogger.d("forcing tile redistribution across pages, reason", reason);
        mDistributeTiles = true;
    }

    public void setLogger(QSLogger qsLogger) {
        mLogger = qsLogger;
    }

    public interface PageListener {
        int INVALID_PAGE = -1;

        void onPageChanged(boolean isFirst, int pageNumber);
    }
}
