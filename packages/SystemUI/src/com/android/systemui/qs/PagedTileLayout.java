package com.android.systemui.qs;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.provider.Settings;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final boolean DEBUG = false;

    private static final String TAG = "PagedTileLayout";

    private final ArrayList<TileRecord> mTiles = new ArrayList<TileRecord>();
    private final ArrayList<TilePage> mPages = new ArrayList<TilePage>();

    private PageIndicator mPageIndicator;

    private int mNumPages;
    private View mDecorGroup;
    private PageListener mPageListener;

    private int mPosition;
    private boolean mOffPage;
    private boolean mListening;

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAdapter(mAdapter);
        setOnPageChangeListener(new OnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
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
                setCurrentPage(position, positionOffset != 0);
                mPageIndicator.setLocation(position + positionOffset);
                if (mPageListener != null) {
                    mPageListener.onPageChanged(positionOffsetPixels == 0 &&
                            (isLayoutRtl() ? position == mPages.size() - 1 : position == 0));
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
        setCurrentItem(0);
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
        if (mListening) {
            setPageListening(mPosition, true);
            if (mOffPage) {
                setPageListening(mPosition + 1, true);
            }
        } else {
            // Make sure no pages are listening.
            for (int i = 0; i < mPages.size(); i++) {
                mPages.get(i).setListening(false);
            }
        }
    }

    /**
     * Sets individual pages to listening or not.  If offPage it will set
     * the next page after position to listening as well since we are in between
     * pages.
     */
    private void setCurrentPage(int position, boolean offPage) {
        if (mPosition == position && mOffPage == offPage) return;
        if (mListening) {
            if (mPosition != position) {
                // Clear out the last pages from listening.
                setPageListening(mPosition, false);
                if (mOffPage) {
                    setPageListening(mPosition + 1, false);
                }
                // Set the new pages to listening
                setPageListening(position, true);
                if (offPage) {
                    setPageListening(position + 1, true);
                }
            } else if (mOffPage != offPage) {
                // Whether we are showing position + 1 has changed.
                setPageListening(mPosition + 1, offPage);
            }
        }
        // Save the current state.
        mPosition = position;
        mOffPage = offPage;
    }

    private void setPageListening(int position, boolean listening) {
        if (position >= mPages.size()) return;
        if (isLayoutRtl()) {
            position = mPages.size() - 1 - position;
        }
        mPages.get(position).setListening(listening);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPageIndicator = (PageIndicator) findViewById(R.id.page_indicator);
        mDecorGroup = findViewById(R.id.page_decor);
        ((LayoutParams) mDecorGroup.getLayoutParams()).isDecor = true;

        mPages.add((TilePage) LayoutInflater.from(mContext)
                .inflate(R.layout.qs_paged_page, this, false));
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
                    mPages.add((TilePage) LayoutInflater.from(mContext)
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
            mDecorGroup.setVisibility(mNumPages > 1 ? View.VISIBLE : View.GONE);
            setAdapter(mAdapter);
            mAdapter.notifyDataSetChanged();
            setCurrentItem(0, false);
        }
    }

    @Override
    public boolean updateResources() {
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
        setMeasuredDimension(getMeasuredWidth(), maxHeight
                + (mDecorGroup.getVisibility() != View.GONE ? mDecorGroup.getMeasuredHeight() : 0));
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

    public static class TilePage extends TileLayout {
        private int mMaxRows = 3;

        public TilePage(Context context, AttributeSet attrs) {
            super(context, attrs);
            updateResources();
            setContentDescription(mContext.getString(R.string.accessibility_desc_quick_settings));
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
            final Resources res = getContext().getResources();
            final ContentResolver resolver = mContext.getContentResolver();
            final boolean isPortrait = res.getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT;
            final int columnsPortrait = Settings.System.getInt(resolver,
                    Settings.System.QS_ROWS_PORTRAIT, 3);
            final int columnsLandscape = Settings.System.getInt(resolver,
                    Settings.System.QS_ROWS_LANDSCAPE, res.getInteger(
                    com.android.internal.R.integer.config_qs_num_rows_landscape_default));
            final int columns = Math.max(1, isPortrait ? columnsPortrait : columnsLandscape);
            return Math.max(1, isPortrait ? columnsPortrait : columnsLandscape);
        }

        public void setMaxRows(int maxRows) {
            mMaxRows = maxRows;
        }

        public boolean isFull() {
            return mRecords.size() >= mColumns * mMaxRows;
        }
    }

    private final PagerAdapter mAdapter = new PagerAdapter() {
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (DEBUG) Log.d(TAG, "Destantiating " + position);
            container.removeView((View) object);
        }

        public Object instantiateItem(ViewGroup container, int position) {
            if (DEBUG) Log.d(TAG, "Instantiating " + position);
            if (isLayoutRtl()) {
                position = mPages.size() - 1 - position;
            }
            ViewGroup view = mPages.get(position);
            container.addView(view);
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

    @Override
    public void updateSettings() {
        for (int i = 0; i < mPages.size(); i++) {
            mPages.get(i).updateSettings();
        }
        postDistributeTiles();
    }
}
