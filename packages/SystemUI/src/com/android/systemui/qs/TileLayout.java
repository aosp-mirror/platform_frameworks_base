package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;

public class TileLayout extends ViewGroup implements QSTileLayout {

    private static final float TILE_ASPECT = 1.2f;

    private static final String TAG = "TileLayout";

    protected int mColumns;
    protected int mCellWidth;
    protected int mCellHeight;
    protected int mCellMargin;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    private int mCellMarginTop;
    private boolean mListening;

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
        updateResources();
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return getTop();
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);
        addView(tile.tileView);
    }

    @Override
    public void removeTile(TileRecord tile) {
        mRecords.remove(tile);
        tile.tile.setListening(this, false);
        removeView(tile.tileView);
    }

    public void removeAllViews() {
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, false);
        }
        mRecords.clear();
        super.removeAllViews();
    }

    public boolean updateResources() {
        final Resources res = mContext.getResources();
        final int columns = Math.max(1, res.getInteger(R.integer.quick_settings_num_columns));
        mCellHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
        mCellMargin = res.getDimensionPixelSize(R.dimen.qs_tile_margin);
        mCellMarginTop = res.getDimensionPixelSize(R.dimen.qs_tile_margin_top);
        if (mColumns != columns) {
            mColumns = columns;
            requestLayout();
            return true;
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int rows = (numTiles + mColumns - 1) / mColumns;
        mCellWidth = (width - (mCellMargin * (mColumns + 1))) / mColumns;

        View previousView = this;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            previousView = record.tileView.updateAccessibilityOrder(previousView);
        }
        int height = (mCellHeight + mCellMargin) * rows + (mCellMarginTop - mCellMargin);
        if (height < 0) height = 0;
        setMeasuredDimension(width, height);
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = getWidth();
        boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int row = 0;
        int column = 0;
        for (int i = 0; i < mRecords.size(); i++, column++) {
            if (column == mColumns) {
                row++;
                column -= mColumns;
            }
            TileRecord record = mRecords.get(i);
            int left = getColumnStart(column);
            final int top = getRowTop(row);
            int right;
            if (isRtl) {
                right = w - left;
                left = right - mCellWidth;
            } else {
                right = left + mCellWidth;
            }
            record.tileView.layout(left, top, right, top + record.tileView.getMeasuredHeight());
        }
    }

    private int getRowTop(int row) {
        return row * (mCellHeight + mCellMargin) + mCellMarginTop;
    }

    private int getColumnStart(int column) {
        return column * (mCellWidth + mCellMargin) + mCellMargin;
    }
}
