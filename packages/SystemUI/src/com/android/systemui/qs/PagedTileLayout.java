package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Scroller;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;
import java.util.Set;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final boolean DEBUG = false;

    private static final String TAG = "PagedTileLayout";
    private static final int REVEAL_SCROLL_DURATION_MILLIS = 750;
    private static final float BOUNCE_ANIMATION_TENSION = 1.3f;
    private static final long BOUNCE_ANIMATION_DURATION = 450L;
    private static final int TILE_ANIMATION_STAGGER_DELAY = 85;
    private static final Interpolator SCROLL_CUBIC = (t) -> {
        t -= 1.0f;
        return t * t * t + 1.0f;
    };


    private final ArrayList<TileRecord> mTiles = new ArrayList<>();
    private final ArrayList<TilePage> mPages = new ArrayList<>();

    private PageIndicator mPageIndicator;
    private float mPageIndicatorPosition;

    private int mNumPages;
    private PageListener mPageListener;

    private boolean mListening;
    private Scroller mScroller;

    private AnimatorSet mBounceAnimatorSet;
    private int mAnimatingToPage = -1;
    private float mLastExpansion;
    private int mHorizontalClipBounds;

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = new Scroller(context, SCROLL_CUBIC);
        setAdapter(mAdapter);
        setOnPageChangeListener(mOnPageChangeListener);
        setCurrentItem(0, false);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        setAdapter(mAdapter);
        setCurrentItem(0, false);
    }

    @Override
    public void setCurrentItem(int item, boolean smoothScroll) {
        if (isLayoutRtl()) {
            item = mPages.size() - 1 - item;
        }
        super.setCurrentItem(item, smoothScroll);
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        updateListening();
    }

    private void updateListening() {
        for (TilePage tilePage : mPages) {
            tilePage.setListening(tilePage.getParent() == null ? false : mListening);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Suppress all touch event during reveal animation.
        if (mAnimatingToPage != -1) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Suppress all touch event during reveal animation.
        if (mAnimatingToPage != -1) {
            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void computeScroll() {
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
            float pageFraction = (float) getScrollX() / getWidth();
            int position = (int) pageFraction;
            float positionOffset = pageFraction - position;
            mOnPageChangeListener.onPageScrolled(position, positionOffset, getScrollX());
            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
            return;
        }
        if (mAnimatingToPage != -1) {
            setCurrentItem(mAnimatingToPage, true);
            mBounceAnimatorSet.start();
            setOffscreenPageLimit(1);
            mAnimatingToPage = -1;
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
        mPages.add((TilePage) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_paged_page, this, false));
    }

    public void setPageIndicator(PageIndicator indicator) {
        mPageIndicator = indicator;
        mPageIndicator.setNumPages(mNumPages);
        mPageIndicator.setLocation(mPageIndicatorPosition);
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
        postDistributeTiles();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            postDistributeTiles();
        }
    }

    @Override
    public void setExpansion(float expansion) {
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
        for (int i = 0; i < mPages.size(); i++) {
            mPages.get(i).setSelected(i == getCurrentItem() ? selected : false);
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public void setPageListener(PageListener listener) {
        mPageListener = listener;
    }

    private void postDistributeTiles() {
        removeCallbacks(mDistribute);
        post(mDistribute);
    }

    private void distributeTiles() {
        if (DEBUG) Log.d(TAG, "Distributing tiles");
        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        int index = 0;
        final int NT = mTiles.size();
        for (int i = 0; i < NT; i++) {
            TileRecord tile = mTiles.get(i);
            if (mPages.get(index).isFull()) {
                if (++index == mPages.size()) {
                    if (DEBUG) Log.d(TAG, "Adding page for "
                            + tile.tile.getClass().getSimpleName());
                    mPages.add((TilePage) LayoutInflater.from(getContext())
                            .inflate(R.layout.qs_paged_page, this, false));
                }
            }
            if (DEBUG) Log.d(TAG, "Adding " + tile.tile.getClass().getSimpleName() + " to "
                    + index);
            mPages.get(index).addTile(tile);
        }
        if (mNumPages != index + 1) {
            mNumPages = index + 1;
            while (mPages.size() > mNumPages) {
                mPages.remove(mPages.size() - 1);
            }
            if (DEBUG) Log.d(TAG, "Size: " + mNumPages);
            mPageIndicator.setNumPages(mNumPages);
            setAdapter(mAdapter);
            mAdapter.notifyDataSetChanged();
            setCurrentItem(0, false);
        }
    }

    @Override
    public boolean updateResources() {
        // Update bottom padding, useful for removing extra space once the panel page indicator is
        // hidden.
        mHorizontalClipBounds = getContext().getResources().getDimensionPixelSize(
                R.dimen.notification_side_paddings);
        setPadding(0, 0, 0,
                getContext().getResources().getDimensionPixelSize(
                        R.dimen.qs_paged_tile_layout_padding_bottom));

        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            changed |= mPages.get(i).updateResources();
        }
        if (changed) {
            distributeTiles();
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
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
        setMeasuredDimension(getMeasuredWidth(), maxHeight + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        Rect clipBounds = new Rect(mHorizontalClipBounds, 0,
                r - l - mHorizontalClipBounds, b - t);
        setClipBounds(clipBounds);
    }


    private final Runnable mDistribute = new Runnable() {
        @Override
        public void run() {
            distributeTiles();
        }
    };

    public int getColumnCount() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mColumns;
    }

    public void startTileReveal(Set<String> tileSpecs, final Runnable postAnimation) {
        if (tileSpecs.isEmpty() || mPages.size() < 2 || getScrollX() != 0) {
            // Do not start the reveal animation unless there are tiles to animate, multiple
            // TilePages available and the user has not already started dragging.
            return;
        }

        final int lastPageNumber = mPages.size() - 1;
        final TilePage lastPage = mPages.get(lastPageNumber);
        final ArrayList<Animator> bounceAnims = new ArrayList<>();
        for (TileRecord tr : lastPage.mRecords) {
            if (tileSpecs.contains(tr.tile.getTileSpec())) {
                bounceAnims.add(setupBounceAnimator(tr.tileView, bounceAnims.size()));
            }
        }

        if (bounceAnims.isEmpty()) {
            // All tileSpecs are on the first page. Nothing to do.
            // TODO: potentially show a bounce animation for first page QS tiles
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
        mAnimatingToPage = lastPageNumber;
        setOffscreenPageLimit(mAnimatingToPage); // Ensure the page to reveal has been inflated.
        mScroller.startScroll(getScrollX(), getScrollY(), getWidth() * mAnimatingToPage, 0,
                REVEAL_SCROLL_DURATION_MILLIS);
        postInvalidateOnAnimation();
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
                @Override
                public void onPageSelected(int position) {
                    updateSelected();
                    if (mPageIndicator == null) return;
                    if (mPageListener != null) {
                        mPageListener.onPageChanged(isLayoutRtl() ? position == mPages.size() - 1
                                : position == 0);
                    }
                }

                @Override
                public void onPageScrolled(int position, float positionOffset,
                        int positionOffsetPixels) {
                    if (mPageIndicator == null) return;
                    mPageIndicatorPosition = position + positionOffset;
                    mPageIndicator.setLocation(mPageIndicatorPosition);
                    if (mPageListener != null) {
                        mPageListener.onPageChanged(positionOffsetPixels == 0 &&
                                (isLayoutRtl() ? position == mPages.size() - 1 : position == 0));
                    }
                }
            };

    public static class TilePage extends TileLayout {
        private int mMaxRows = 3;
        public TilePage(Context context, AttributeSet attrs) {
            super(context, attrs);
            updateResources();
        }

        @Override
        public boolean updateResources() {
            final int rows = getRows();
            boolean changed = rows != mMaxRows;
            if (changed) {
                mMaxRows = rows;
                requestLayout();
            }
            return super.updateResources() || changed;
        }

        private int getRows() {
            return Math.max(1, getResources().getInteger(R.integer.quick_settings_num_rows));
        }

        public void setMaxRows(int maxRows) {
            mMaxRows = maxRows;
        }

        public boolean isFull() {
            return mRecords.size() >= mColumns * mMaxRows;
        }
    }

    private final PagerAdapter mAdapter = new PagerAdapter() {
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (DEBUG) Log.d(TAG, "Destantiating " + position);
            container.removeView((View) object);
            updateListening();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            if (DEBUG) Log.d(TAG, "Instantiating " + position);
            if (isLayoutRtl()) {
                position = mPages.size() - 1 - position;
            }
            ViewGroup view = mPages.get(position);
            container.addView(view);
            updateListening();
            return view;
        }

        @Override
        public int getCount() {
            return mNumPages;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    };

    public interface PageListener {
        void onPageChanged(boolean isFirst);
    }
}
