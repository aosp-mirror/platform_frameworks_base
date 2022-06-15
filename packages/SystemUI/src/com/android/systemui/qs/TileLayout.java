package com.android.systemui.qs;

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;

import java.util.ArrayList;

public class TileLayout extends ViewGroup implements QSTileLayout {

    public static final int NO_MAX_COLUMNS = 100;

    private static final String TAG = "TileLayout";

    protected int mColumns;
    protected int mCellWidth;
    protected int mCellHeightResId = R.dimen.qs_tile_height;
    protected int mCellHeight;
    protected int mMaxCellHeight;
    protected int mCellMarginHorizontal;
    protected int mCellMarginVertical;
    protected int mSidePadding;
    protected int mRows = 1;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    protected boolean mListening;
    protected int mMaxAllowedRows = 3;

    // Prototyping with less rows
    private final boolean mLessRows;
    private int mMinRows = 1;
    private int mMaxColumns = NO_MAX_COLUMNS;
    protected int mResourceColumns;

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
        mLessRows = ((Settings.System.getInt(context.getContentResolver(), "qs_less_rows", 0) != 0)
                || useQsMediaPlayer(context));
        updateResources();
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return getTop();
    }

    public void setListening(boolean listening) {
        setListening(listening, null);
    }

    @Override
    public void setListening(boolean listening, UiEventLogger uiEventLogger) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    @Override
    public boolean setMinRows(int minRows) {
        if (mMinRows != minRows) {
            mMinRows = minRows;
            updateResources();
            return true;
        }
        return false;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        return updateColumns();
    }

    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);
        addTileView(tile);
    }

    protected void addTileView(TileRecord tile) {
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
        mResourceColumns = Math.max(1, res.getInteger(R.integer.quick_settings_num_columns));
        updateColumns();
        mMaxCellHeight = mContext.getResources().getDimensionPixelSize(mCellHeightResId);
        mCellMarginHorizontal = res.getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
        mSidePadding = useSidePadding() ? mCellMarginHorizontal / 2 : 0;
        mCellMarginVertical= res.getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        mMaxAllowedRows = Math.max(1, getResources().getInteger(R.integer.quick_settings_max_rows));
        if (mLessRows) mMaxAllowedRows = Math.max(mMinRows, mMaxAllowedRows - 1);
        if (updateColumns()) {
            requestLayout();
            return true;
        }
        return false;
    }

    protected boolean useSidePadding() {
        return true;
    }

    private boolean updateColumns() {
        int oldColumns = mColumns;
        mColumns = Math.min(mResourceColumns, mMaxColumns);
        return oldColumns != mColumns;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If called with AT_MOST, it will limit the number of rows. If called with UNSPECIFIED
        // it will show all its tiles. In this case, the tiles have to be entered before the
        // container is measured. Any change in the tiles, should trigger a remeasure.
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int availableWidth = width - getPaddingStart() - getPaddingEnd();
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            mRows = (numTiles + mColumns - 1) / mColumns;
        }
        final int gaps = mColumns - 1;
        mCellWidth =
                (availableWidth - (mCellMarginHorizontal * gaps) - mSidePadding * 2) / mColumns;

        // Measure each QS tile.
        View previousView = this;
        int verticalMeasure = exactly(getCellHeight());
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.tileView.measure(exactly(mCellWidth), verticalMeasure);
            previousView = record.tileView.updateAccessibilityOrder(previousView);
            mCellHeight = record.tileView.getMeasuredHeight();
        }

        int height = (mCellHeight + mCellMarginVertical) * mRows;
        height -= mCellMarginVertical;

        if (height < 0) height = 0;

        setMeasuredDimension(width, height);
    }

    /**
     * Determines the maximum number of rows that can be shown based on height. Clips at a minimum
     * of 1 and a maximum of mMaxAllowedRows.
     *
     * @param allowedHeight The height this view has visually available
     * @param tilesCount Upper limit on the number of tiles to show. to prevent empty rows.
     */
    public boolean updateMaxRows(int allowedHeight, int tilesCount) {
        // Add the cell margin in order to divide easily by the height + the margin below
        final int availableHeight =  allowedHeight + mCellMarginVertical;
        final int previousRows = mRows;
        mRows = availableHeight / (getCellHeight() + mCellMarginVertical);
        if (mRows < mMinRows) {
            mRows = mMinRows;
        } else if (mRows >= mMaxAllowedRows) {
            mRows = mMaxAllowedRows;
        }
        if (mRows > (tilesCount + mColumns - 1) / mColumns) {
            mRows = (tilesCount + mColumns - 1) / mColumns;
        }
        return previousRows != mRows;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected int getCellHeight() {
        return mMaxCellHeight;
    }

    protected void layoutTileRecords(int numRecords) {
        final boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int row = 0;
        int column = 0;

        // Layout each QS tile.
        final int tilesToLayout = Math.min(numRecords, mRows * mColumns);
        for (int i = 0; i < tilesToLayout; i++, column++) {
            // If we reached the last column available to layout a tile, wrap back to the next row.
            if (column == mColumns) {
                column = 0;
                row++;
            }

            final TileRecord record = mRecords.get(i);
            final int top = getRowTop(row);
            final int left = getColumnStart(isRtl ? mColumns - column - 1 : column);
            final int right = left + mCellWidth;
            record.tileView.layout(left, top, right, top + record.tileView.getMeasuredHeight());
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutTileRecords(mRecords.size());
    }

    protected int getRowTop(int row) {
        return row * (mCellHeight + mCellMarginVertical);
    }

    protected int getColumnStart(int column) {
        return getPaddingStart() + mSidePadding
                + column *  (mCellWidth + mCellMarginHorizontal);
    }

    @Override
    public int getNumVisibleTiles() {
        return mRecords.size();
    }

    public boolean isFull() {
        return false;
    }

    /**
     * @return The maximum number of tiles this layout can hold
     */
    public int maxTiles() {
        // Each layout should be able to hold at least one tile. If there's not enough room to
        // show even 1 or there are no tiles, it probably means we are in the middle of setting
        // up.
        return Math.max(mColumns * mRows, 1);
    }
}
