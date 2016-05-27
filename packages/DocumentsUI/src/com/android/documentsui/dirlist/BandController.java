/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.dirlist.ModelBackedDocumentsAdapter.ITEM_TYPE_DIRECTORY;
import static com.android.documentsui.dirlist.ModelBackedDocumentsAdapter.ITEM_TYPE_DOCUMENT;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import com.android.documentsui.Events;
import com.android.documentsui.Events.InputEvent;
import com.android.documentsui.Events.MotionInputEvent;
import com.android.documentsui.R;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides mouse driven band-select support when used in conjunction with {@link RecyclerView}
 * and {@link MultiSelectManager}. This class is responsible for rendering the band select
 * overlay and selecting overlaid items via MultiSelectManager.
 */
public class BandController extends RecyclerView.OnScrollListener {

    private static final int NOT_SET = -1;

    private static final String TAG = "BandController";

    private final Runnable mModelBuilder;
    private final SelectionEnvironment mEnvironment;
    private final DocumentsAdapter mAdapter;
    private final MultiSelectManager mSelectionManager;
    private final Runnable mViewScroller = new ViewScroller();
    private final GridModel.OnSelectionChangedListener mGridListener;

    @Nullable private Rect mBounds;
    @Nullable private Point mCurrentPosition;
    @Nullable private Point mOrigin;
    @Nullable private BandController.GridModel mModel;

    // The time at which the current band selection-induced scroll began. If no scroll is in
    // progress, the value is NOT_SET.
    private long mScrollStartTime = NOT_SET;
    private Selection mSelection;

    public BandController(
            final RecyclerView view,
            DocumentsAdapter adapter,
            MultiSelectManager selectionManager) {
        this(new RuntimeSelectionEnvironment(view), adapter, selectionManager);

        view.addOnItemTouchListener(
                new RecyclerView.OnItemTouchListener() {
                    @Override
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        return handleEvent(new MotionInputEvent(e, view));
                    }
                    @Override
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                        if (Events.isMouseEvent(e)) {
                            processInputEvent(new MotionInputEvent(e, view));
                        }
                    }
                    @Override
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });
    }

    private BandController(
            SelectionEnvironment env,
            DocumentsAdapter adapter,
            MultiSelectManager selectionManager) {

        selectionManager.bindContoller(this);

        mEnvironment = env;
        mAdapter = adapter;
        mSelectionManager = selectionManager;

        mEnvironment.addOnScrollListener(this);

        mAdapter.registerAdapterDataObserver(
                new RecyclerView.AdapterDataObserver() {
                    @Override
                    public void onChanged() {
                        if (isActive()) {
                            endBandSelect();
                        }
                    }

                    @Override
                    public void onItemRangeChanged(
                            int startPosition, int itemCount, Object payload) {
                        // No change in position. Ignoring.
                    }

                    @Override
                    public void onItemRangeInserted(int startPosition, int itemCount) {
                        if (isActive()) {
                            endBandSelect();
                        }
                    }

                    @Override
                    public void onItemRangeRemoved(int startPosition, int itemCount) {
                        assert(startPosition >= 0);
                        assert(itemCount > 0);

                        // TODO: Should update grid model.
                    }

                    @Override
                    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                        throw new UnsupportedOperationException();
                    }
                });

        mGridListener = new GridModel.OnSelectionChangedListener() {

            @Override
            public void onSelectionChanged(Set<String> updatedSelection) {
                BandController.this.onSelectionChanged(updatedSelection);
            }

            @Override
            public boolean onBeforeItemStateChange(String id, boolean nextState) {
                return BandController.this.onBeforeItemStateChange(id, nextState);
            }
        };

        mModelBuilder = new Runnable() {
            @Override
            public void run() {
                mModel = new GridModel(mEnvironment, mAdapter);
                mModel.addOnSelectionChangedListener(mGridListener);
            }
        };
    }

    void bindSelection(Selection selection) {
        mSelection = selection;
    }

    private boolean handleEvent(MotionInputEvent e) {
        if (!e.isMouseEvent() && isActive()) {
            // Weird things happen if we keep up band select
            // when touch events happen.
            endBandSelect();
            return false;
        }

        // b/23793622 notes the fact that we *never* receive ACTION_DOWN
        // events in onTouchEvent. Where it not for this issue, we'd
        // push start handling down into handleInputEvent.
        if (shouldStart(e)) {
            // endBandSelect is handled in handleInputEvent.
            startBandSelect(e.getOrigin());
        } else if (isActive() && e.isActionUp()) {
            // Same issue here w b/23793622. The ACTION_UP event
            // is only evert dispatched to onTouchEvent when
            // there is some associated motion. If a user taps
            // mouse, but doesn't move, then band select gets
            // started BUT not ended. Causing phantom
            // bands to appear when the user later clicks to start
            // band select.
            if (e.isMouseEvent()) {
                processInputEvent(e);
            }
        }

        return isActive();
    }

    private boolean isActive() {
        return mModel != null;
    }

    /**
     * Handle a change in layout by cleaning up and getting rid of the old model and creating
     * a new model which will track the new layout.
     */
    public void handleLayoutChanged() {
        if (mModel != null) {
            mModel.removeOnSelectionChangedListener(mGridListener);
            mModel.stopListening();

            // build a new model, all fresh and happy.
            mModelBuilder.run();
        }
    }

    boolean shouldStart(MotionInputEvent e) {
        return !isActive()
                && e.isActionDown()  // the initial button press
                && mAdapter.getItemCount() > 0
                && e.getItemPosition() == RecyclerView.NO_ID;  // in empty space
    }

    boolean shouldStop(InputEvent input) {
        return isActive()
                && input.isMouseEvent()
                && input.isActionUp();
    }

    /**
     * Processes a MotionEvent by starting, ending, or resizing the band select overlay.
     * @param input
     */
    private void processInputEvent(InputEvent input) {
        assert(input.isMouseEvent());

        if (shouldStop(input)) {
            endBandSelect();
            return;
        }

        // We shouldn't get any events in this method when band select is not active,
        // but it turns some guests show up late to the party.
        if (!isActive()) {
            return;
        }

        mCurrentPosition = input.getOrigin();
        mModel.resizeSelection(input.getOrigin());
        scrollViewIfNecessary();
        resizeBandSelectRectangle();
    }

    /**
     * Starts band select by adding the drawable to the RecyclerView's overlay.
     */
    private void startBandSelect(Point origin) {
        if (DEBUG) Log.d(TAG, "Starting band select @ " + origin);

        mOrigin = origin;
        mModelBuilder.run();  // Creates a new selection model.
        mModel.startSelection(mOrigin);
    }

    /**
     * Scrolls the view if necessary.
     */
    private void scrollViewIfNecessary() {
        mEnvironment.removeCallback(mViewScroller);
        mViewScroller.run();
        mEnvironment.invalidateView();
    }

    /**
     * Resizes the band select rectangle by using the origin and the current pointer position as
     * two opposite corners of the selection.
     */
    private void resizeBandSelectRectangle() {
        mBounds = new Rect(Math.min(mOrigin.x, mCurrentPosition.x),
                Math.min(mOrigin.y, mCurrentPosition.y),
                Math.max(mOrigin.x, mCurrentPosition.x),
                Math.max(mOrigin.y, mCurrentPosition.y));
        mEnvironment.showBand(mBounds);
    }

    /**
     * Ends band select by removing the overlay.
     */
    private void endBandSelect() {
        if (DEBUG) Log.d(TAG, "Ending band select.");

        mEnvironment.hideBand();
        mSelection.applyProvisionalSelection();
        mModel.endSelection();
        int firstSelected = mModel.getPositionNearestOrigin();
        if (firstSelected != NOT_SET) {
            if (mSelection.contains(mAdapter.getModelId(firstSelected))) {
                // TODO: firstSelected should really be lastSelected, we want to anchor the item
                // where the mouse-up occurred.
                mSelectionManager.setSelectionRangeBegin(firstSelected);
            } else {
                // TODO: Check if this is really happening.
                Log.w(TAG, "First selected by band is NOT in selection!");
            }
        }

        mModel = null;
        mOrigin = null;
    }

    private void onSelectionChanged(Set<String> updatedSelection) {
        Map<String, Boolean> delta = mSelection.setProvisionalSelection(updatedSelection);
        for (Map.Entry<String, Boolean> entry: delta.entrySet()) {
            mSelectionManager.notifyItemStateChanged(entry.getKey(), entry.getValue());
        }
        mSelectionManager.notifySelectionChanged();
    }

    private boolean onBeforeItemStateChange(String id, boolean nextState) {
        return mSelectionManager.notifyBeforeItemStateChange(id, nextState);
    }

    private class ViewScroller implements Runnable {
        /**
         * The number of milliseconds of scrolling at which scroll speed continues to increase.
         * At first, the scroll starts slowly; then, the rate of scrolling increases until it
         * reaches its maximum value at after this many milliseconds.
         */
        private static final long SCROLL_ACCELERATION_LIMIT_TIME_MS = 2000;

        @Override
        public void run() {
            // Compute the number of pixels the pointer's y-coordinate is past the view.
            // Negative values mean the pointer is at or before the top of the view, and
            // positive values mean that the pointer is at or after the bottom of the view. Note
            // that one additional pixel is added here so that the view still scrolls when the
            // pointer is exactly at the top or bottom.
            int pixelsPastView = 0;
            if (mCurrentPosition.y <= 0) {
                pixelsPastView = mCurrentPosition.y - 1;
            } else if (mCurrentPosition.y >= mEnvironment.getHeight() - 1) {
                pixelsPastView = mCurrentPosition.y - mEnvironment.getHeight() + 1;
            }

            if (!isActive() || pixelsPastView == 0) {
                // If band selection is inactive, or if it is active but not at the edge of the
                // view, no scrolling is necessary.
                mScrollStartTime = NOT_SET;
                return;
            }

            if (mScrollStartTime == NOT_SET) {
                // If the pointer was previously not at the edge of the view but now is, set the
                // start time for the scroll.
                mScrollStartTime = System.currentTimeMillis();
            }

            // Compute the number of pixels to scroll, and scroll that many pixels.
            final int numPixels = computeScrollDistance(
                    pixelsPastView, System.currentTimeMillis() - mScrollStartTime);
            mEnvironment.scrollBy(numPixels);

            mEnvironment.removeCallback(mViewScroller);
            mEnvironment.runAtNextFrame(this);
        }

        /**
         * Computes the number of pixels to scroll based on how far the pointer is past the end
         * of the view and how long it has been there. Roughly based on ItemTouchHelper's
         * algorithm for computing the number of pixels to scroll when an item is dragged to the
         * end of a {@link RecyclerView}.
         * @param pixelsPastView
         * @param scrollDuration
         * @return
         */
        private int computeScrollDistance(int pixelsPastView, long scrollDuration) {
            final int maxScrollStep = mEnvironment.getHeight();
            final int direction = (int) Math.signum(pixelsPastView);
            final int absPastView = Math.abs(pixelsPastView);

            // Calculate the ratio of how far out of the view the pointer currently resides to
            // the entire height of the view.
            final float outOfBoundsRatio = Math.min(
                    1.0f, (float) absPastView / mEnvironment.getHeight());
            // Interpolate this ratio and use it to compute the maximum scroll that should be
            // possible for this step.
            final float cappedScrollStep =
                    direction * maxScrollStep * smoothOutOfBoundsRatio(outOfBoundsRatio);

            // Likewise, calculate the ratio of the time spent in the scroll to the limit.
            final float timeRatio = Math.min(
                    1.0f, (float) scrollDuration / SCROLL_ACCELERATION_LIMIT_TIME_MS);
            // Interpolate this ratio and use it to compute the final number of pixels to
            // scroll.
            final int numPixels = (int) (cappedScrollStep * smoothTimeRatio(timeRatio));

            // If the final number of pixels to scroll ends up being 0, the view should still
            // scroll at least one pixel.
            return numPixels != 0 ? numPixels : direction;
        }

        /**
         * Interpolates the given out of bounds ratio on a curve which starts at (0,0) and ends
         * at (1,1) and quickly approaches 1 near the start of that interval. This ensures that
         * drags that are at the edge or barely past the edge of the view still cause sufficient
         * scrolling. The equation y=(x-1)^5+1 is used, but this could also be tweaked if
         * needed.
         * @param ratio A ratio which is in the range [0, 1].
         * @return A "smoothed" value, also in the range [0, 1].
         */
        private float smoothOutOfBoundsRatio(float ratio) {
            return (float) Math.pow(ratio - 1.0f, 5) + 1.0f;
        }

        /**
         * Interpolates the given time ratio on a curve which starts at (0,0) and ends at (1,1)
         * and stays close to 0 for most input values except those very close to 1. This ensures
         * that scrolls start out very slowly but speed up drastically after the scroll has been
         * in progress close to SCROLL_ACCELERATION_LIMIT_TIME_MS. The equation y=x^5 is used,
         * but this could also be tweaked if needed.
         * @param ratio A ratio which is in the range [0, 1].
         * @return A "smoothed" value, also in the range [0, 1].
         */
        private float smoothTimeRatio(float ratio) {
            return (float) Math.pow(ratio, 5);
        }
    };

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        if (!isActive()) {
            return;
        }

        // Adjust the y-coordinate of the origin the opposite number of pixels so that the
        // origin remains in the same place relative to the view's items.
        mOrigin.y -= dy;
        resizeBandSelectRectangle();
    }

    /**
     * Provides a band selection item model for views within a RecyclerView. This class queries the
     * RecyclerView to determine where its items are placed; then, once band selection is underway,
     * it alerts listeners of which items are covered by the selections.
     */
    @VisibleForTesting
    static final class GridModel extends RecyclerView.OnScrollListener {

        public static final int NOT_SET = -1;

        // Enum values used to determine the corner at which the origin is located within the
        private static final int UPPER = 0x00;
        private static final int LOWER = 0x01;
        private static final int LEFT = 0x00;
        private static final int RIGHT = 0x02;
        private static final int UPPER_LEFT = UPPER | LEFT;
        private static final int UPPER_RIGHT = UPPER | RIGHT;
        private static final int LOWER_LEFT = LOWER | LEFT;
        private static final int LOWER_RIGHT = LOWER | RIGHT;

        private final SelectionEnvironment mHelper;
        private final DocumentsAdapter mAdapter;

        private final List<GridModel.OnSelectionChangedListener> mOnSelectionChangedListeners =
                new ArrayList<>();

        // Map from the x-value of the left side of a SparseBooleanArray of adapter positions, keyed
        // by their y-offset. For example, if the first column of the view starts at an x-value of 5,
        // mColumns.get(5) would return an array of positions in that column. Within that array, the
        // value for key y is the adapter position for the item whose y-offset is y.
        private final SparseArray<SparseIntArray> mColumns = new SparseArray<>();

        // List of limits along the x-axis (columns).
        // This list is sorted from furthest left to furthest right.
        private final List<GridModel.Limits> mColumnBounds = new ArrayList<>();

        // List of limits along the y-axis (rows). Note that this list only contains items which
        // have been in the viewport.
        private final List<GridModel.Limits> mRowBounds = new ArrayList<>();

        // The adapter positions which have been recorded so far.
        private final SparseBooleanArray mKnownPositions = new SparseBooleanArray();

        // Array passed to registered OnSelectionChangedListeners. One array is created and reused
        // throughout the lifetime of the object.
        private final Set<String> mSelection = new HashSet<>();

        // The current pointer (in absolute positioning from the top of the view).
        private Point mPointer = null;

        // The bounds of the band selection.
        private RelativePoint mRelativeOrigin;
        private RelativePoint mRelativePointer;

        private boolean mIsActive;

        // Tracks where the band select originated from. This is used to determine where selections
        // should expand from when Shift+click is used.
        private int mPositionNearestOrigin = NOT_SET;

        GridModel(SelectionEnvironment helper, DocumentsAdapter adapter) {
            mHelper = helper;
            mAdapter = adapter;
            mHelper.addOnScrollListener(this);
        }

        /**
         * Stops listening to the view's scrolls. Call this function before discarding a
         * BandSelecModel object to prevent memory leaks.
         */
        void stopListening() {
            mHelper.removeOnScrollListener(this);
        }

        /**
         * Start a band select operation at the given point.
         * @param relativeOrigin The origin of the band select operation, relative to the viewport.
         *     For example, if the view is scrolled to the bottom, the top-left of the viewport
         *     would have a relative origin of (0, 0), even though its absolute point has a higher
         *     y-value.
         */
        void startSelection(Point relativeOrigin) {
            recordVisibleChildren();
            if (isEmpty()) {
                // The selection band logic works only if there is at least one visible child.
                return;
            }

            mIsActive = true;
            mPointer = mHelper.createAbsolutePoint(relativeOrigin);
            mRelativeOrigin = new RelativePoint(mPointer);
            mRelativePointer = new RelativePoint(mPointer);
            computeCurrentSelection();
            notifyListeners();
        }

        /**
         * Resizes the selection by adjusting the pointer (i.e., the corner of the selection
         * opposite the origin.
         * @param relativePointer The pointer (opposite of the origin) of the band select operation,
         *     relative to the viewport. For example, if the view is scrolled to the bottom, the
         *     top-left of the viewport would have a relative origin of (0, 0), even though its
         *     absolute point has a higher y-value.
         */
        @VisibleForTesting
        void resizeSelection(Point relativePointer) {
            mPointer = mHelper.createAbsolutePoint(relativePointer);
            updateModel();
        }

        /**
         * Ends the band selection.
         */
        void endSelection() {
            mIsActive = false;
        }

        /**
         * @return The adapter position for the item nearest the origin corresponding to the latest
         *         band select operation, or NOT_SET if the selection did not cover any items.
         */
        int getPositionNearestOrigin() {
            return mPositionNearestOrigin;
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (!mIsActive) {
                return;
            }

            mPointer.x += dx;
            mPointer.y += dy;
            recordVisibleChildren();
            updateModel();
        }

        /**
         * Queries the view for all children and records their location metadata.
         */
        private void recordVisibleChildren() {
            for (int i = 0; i < mHelper.getVisibleChildCount(); i++) {
                int adapterPosition = mHelper.getAdapterPositionAt(i);
                // Sometimes the view is not attached, as we notify the multi selection manager
                // synchronously, while views are attached asynchronously. As a result items which
                // are in the adapter may not actually have a corresponding view (yet).
                if (mHelper.hasView(adapterPosition) &&
                        !mHelper.isLayoutItem(adapterPosition) &&
                        !mKnownPositions.get(adapterPosition)) {
                    mKnownPositions.put(adapterPosition, true);
                    recordItemData(mHelper.getAbsoluteRectForChildViewAt(i), adapterPosition);
                }
            }
        }

        /**
         * Checks if there are any recorded children.
         */
        private boolean isEmpty() {
            return mColumnBounds.size() == 0 || mRowBounds.size() == 0;
        }

        /**
         * Updates the limits lists and column map with the given item metadata.
         * @param absoluteChildRect The absolute rectangle for the child view being processed.
         * @param adapterPosition The position of the child view being processed.
         */
        private void recordItemData(Rect absoluteChildRect, int adapterPosition) {
            if (mColumnBounds.size() != mHelper.getColumnCount()) {
                // If not all x-limits have been recorded, record this one.
                recordLimits(
                        mColumnBounds, new Limits(absoluteChildRect.left, absoluteChildRect.right));
            }

            recordLimits(mRowBounds, new Limits(absoluteChildRect.top, absoluteChildRect.bottom));

            SparseIntArray columnList = mColumns.get(absoluteChildRect.left);
            if (columnList == null) {
                columnList = new SparseIntArray();
                mColumns.put(absoluteChildRect.left, columnList);
            }
            columnList.put(absoluteChildRect.top, adapterPosition);
        }

        /**
         * Ensures limits exists within the sorted list limitsList, and adds it to the list if it
         * does not exist.
         */
        private void recordLimits(List<GridModel.Limits> limitsList, GridModel.Limits limits) {
            int index = Collections.binarySearch(limitsList, limits);
            if (index < 0) {
                limitsList.add(~index, limits);
            }
        }

        /**
         * Handles a moved pointer; this function determines whether the pointer movement resulted
         * in a selection change and, if it has, notifies listeners of this change.
         */
        private void updateModel() {
            RelativePoint old = mRelativePointer;
            mRelativePointer = new RelativePoint(mPointer);
            if (old != null && mRelativePointer.equals(old)) {
                return;
            }

            computeCurrentSelection();
            notifyListeners();
        }

        /**
         * Computes the currently-selected items.
         */
        private void computeCurrentSelection() {
            if (areItemsCoveredByBand(mRelativePointer, mRelativeOrigin)) {
                updateSelection(computeBounds());
            } else {
                mSelection.clear();
                mPositionNearestOrigin = NOT_SET;
            }
        }

        /**
         * Notifies all listeners of a selection change. Note that this function simply passes
         * mSelection, so computeCurrentSelection() should be called before this
         * function.
         */
        private void notifyListeners() {
            for (GridModel.OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
                listener.onSelectionChanged(mSelection);
            }
        }

        /**
         * @param rect Rectangle including all covered items.
         */
        private void updateSelection(Rect rect) {
            int columnStart =
                    Collections.binarySearch(mColumnBounds, new Limits(rect.left, rect.left));
            assert(columnStart >= 0);
            int columnEnd = columnStart;

            for (int i = columnStart; i < mColumnBounds.size()
                    && mColumnBounds.get(i).lowerLimit <= rect.right; i++) {
                columnEnd = i;
            }

            int rowStart = Collections.binarySearch(mRowBounds, new Limits(rect.top, rect.top));
            if (rowStart < 0) {
                mPositionNearestOrigin = NOT_SET;
                return;
            }

            int rowEnd = rowStart;
            for (int i = rowStart; i < mRowBounds.size()
                    && mRowBounds.get(i).lowerLimit <= rect.bottom; i++) {
                rowEnd = i;
            }

            updateSelection(columnStart, columnEnd, rowStart, rowEnd);
        }

        /**
         * Computes the selection given the previously-computed start- and end-indices for each
         * row and column.
         */
        private void updateSelection(
                int columnStartIndex, int columnEndIndex, int rowStartIndex, int rowEndIndex) {
            if (DEBUG) Log.d(TAG, String.format("updateSelection: %d, %d, %d, %d",
                    columnStartIndex, columnEndIndex, rowStartIndex, rowEndIndex));

            mSelection.clear();
            for (int column = columnStartIndex; column <= columnEndIndex; column++) {
                SparseIntArray items = mColumns.get(mColumnBounds.get(column).lowerLimit);
                for (int row = rowStartIndex; row <= rowEndIndex; row++) {
                    // The default return value for SparseIntArray.get is 0, which is a valid
                    // position. Use a sentry value to prevent erroneously selecting item 0.
                    final int rowKey = mRowBounds.get(row).lowerLimit;
                    int position = items.get(rowKey, NOT_SET);
                    if (position != NOT_SET) {
                        String id = mAdapter.getModelId(position);
                        if (id != null) {
                            // The adapter inserts items for UI layout purposes that aren't associated
                            // with files.  Those will have a null model ID.  Don't select them.
                            if (canSelect(id)) {
                                mSelection.add(id);
                            }
                        }
                        if (isPossiblePositionNearestOrigin(column, columnStartIndex, columnEndIndex,
                                row, rowStartIndex, rowEndIndex)) {
                            // If this is the position nearest the origin, record it now so that it
                            // can be returned by endSelection() later.
                            mPositionNearestOrigin = position;
                        }
                    }
                }
            }
        }

        /**
         * @return True if the item is selectable.
         */
        private boolean canSelect(String id) {
            // TODO: Simplify the logic, so the check whether we can select is done in one place.
            // Consider injecting FragmentTuner, or move the checks from MultiSelectManager to
            // Selection.
            for (GridModel.OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
                if (!listener.onBeforeItemStateChange(id, true)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * @return Returns true if the position is the nearest to the origin, or, in the case of the
         *     lower-right corner, whether it is possible that the position is the nearest to the
         *     origin. See comment below for reasoning for this special case.
         */
        private boolean isPossiblePositionNearestOrigin(int columnIndex, int columnStartIndex,
                int columnEndIndex, int rowIndex, int rowStartIndex, int rowEndIndex) {
            int corner = computeCornerNearestOrigin();
            switch (corner) {
                case UPPER_LEFT:
                    return columnIndex == columnStartIndex && rowIndex == rowStartIndex;
                case UPPER_RIGHT:
                    return columnIndex == columnEndIndex && rowIndex == rowStartIndex;
                case LOWER_LEFT:
                    return columnIndex == columnStartIndex && rowIndex == rowEndIndex;
                case LOWER_RIGHT:
                    // Note that in some cases, the last row will not have as many items as there
                    // are columns (e.g., if there are 4 items and 3 columns, the second row will
                    // only have one item in the first column). This function is invoked for each
                    // position from left to right, so return true for any position in the bottom
                    // row and only the right-most position in the bottom row will be recorded.
                    return rowIndex == rowEndIndex;
                default:
                    throw new RuntimeException("Invalid corner type.");
            }
        }

        /**
         * Listener for changes in which items have been band selected.
         */
        static interface OnSelectionChangedListener {
            public void onSelectionChanged(Set<String> updatedSelection);
            public boolean onBeforeItemStateChange(String id, boolean nextState);
        }

        void addOnSelectionChangedListener(GridModel.OnSelectionChangedListener listener) {
            mOnSelectionChangedListeners.add(listener);
        }

        void removeOnSelectionChangedListener(GridModel.OnSelectionChangedListener listener) {
            mOnSelectionChangedListeners.remove(listener);
        }

        /**
         * Limits of a view item. For example, if an item's left side is at x-value 5 and its right side
         * is at x-value 10, the limits would be from 5 to 10. Used to record the left- and right sides
         * of item columns and the top- and bottom sides of item rows so that it can be determined
         * whether the pointer is located within the bounds of an item.
         */
        private static class Limits implements Comparable<GridModel.Limits> {
            int lowerLimit;
            int upperLimit;

            Limits(int lowerLimit, int upperLimit) {
                this.lowerLimit = lowerLimit;
                this.upperLimit = upperLimit;
            }

            @Override
            public int compareTo(GridModel.Limits other) {
                return lowerLimit - other.lowerLimit;
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof GridModel.Limits)) {
                    return false;
                }

                return ((GridModel.Limits) other).lowerLimit == lowerLimit &&
                        ((GridModel.Limits) other).upperLimit == upperLimit;
            }

            @Override
            public String toString() {
                return "(" + lowerLimit + ", " + upperLimit + ")";
            }
        }

        /**
         * The location of a coordinate relative to items. This class represents a general area of the
         * view as it relates to band selection rather than an explicit point. For example, two
         * different points within an item are considered to have the same "location" because band
         * selection originating within the item would select the same items no matter which point
         * was used. Same goes for points between items as well as those at the very beginning or end
         * of the view.
         *
         * Tracking a coordinate (e.g., an x-value) as a CoordinateLocation instead of as an int has the
         * advantage of tying the value to the Limits of items along that axis. This allows easy
         * selection of items within those Limits as opposed to a search through every item to see if a
         * given coordinate value falls within those Limits.
         */
        private static class RelativeCoordinate
                implements Comparable<GridModel.RelativeCoordinate> {
            /**
             * Location describing points after the last known item.
             */
            static final int AFTER_LAST_ITEM = 0;

            /**
             * Location describing points before the first known item.
             */
            static final int BEFORE_FIRST_ITEM = 1;

            /**
             * Location describing points between two items.
             */
            static final int BETWEEN_TWO_ITEMS = 2;

            /**
             * Location describing points within the limits of one item.
             */
            static final int WITHIN_LIMITS = 3;

            /**
             * The type of this coordinate, which is one of AFTER_LAST_ITEM, BEFORE_FIRST_ITEM,
             * BETWEEN_TWO_ITEMS, or WITHIN_LIMITS.
             */
            final int type;

            /**
             * The limits before the coordinate; only populated when type == WITHIN_LIMITS or type ==
             * BETWEEN_TWO_ITEMS.
             */
            GridModel.Limits limitsBeforeCoordinate;

            /**
             * The limits after the coordinate; only populated when type == BETWEEN_TWO_ITEMS.
             */
            GridModel.Limits limitsAfterCoordinate;

            // Limits of the first known item; only populated when type == BEFORE_FIRST_ITEM.
            GridModel.Limits mFirstKnownItem;
            // Limits of the last known item; only populated when type == AFTER_LAST_ITEM.
            GridModel.Limits mLastKnownItem;

            /**
             * @param limitsList The sorted limits list for the coordinate type. If this
             *     CoordinateLocation is an x-value, mXLimitsList should be passed; otherwise,
             *     mYLimitsList should be pased.
             * @param value The coordinate value.
             */
            RelativeCoordinate(List<GridModel.Limits> limitsList, int value) {
                int index = Collections.binarySearch(limitsList, new Limits(value, value));

                if (index >= 0) {
                    this.type = WITHIN_LIMITS;
                    this.limitsBeforeCoordinate = limitsList.get(index);
                } else if (~index == 0) {
                    this.type = BEFORE_FIRST_ITEM;
                    this.mFirstKnownItem = limitsList.get(0);
                } else if (~index == limitsList.size()) {
                    GridModel.Limits lastLimits = limitsList.get(limitsList.size() - 1);
                    if (lastLimits.lowerLimit <= value && value <= lastLimits.upperLimit) {
                        this.type = WITHIN_LIMITS;
                        this.limitsBeforeCoordinate = lastLimits;
                    } else {
                        this.type = AFTER_LAST_ITEM;
                        this.mLastKnownItem = lastLimits;
                    }
                } else {
                    GridModel.Limits limitsBeforeIndex = limitsList.get(~index - 1);
                    if (limitsBeforeIndex.lowerLimit <= value && value <= limitsBeforeIndex.upperLimit) {
                        this.type = WITHIN_LIMITS;
                        this.limitsBeforeCoordinate = limitsList.get(~index - 1);
                    } else {
                        this.type = BETWEEN_TWO_ITEMS;
                        this.limitsBeforeCoordinate = limitsList.get(~index - 1);
                        this.limitsAfterCoordinate = limitsList.get(~index);
                    }
                }
            }

            int toComparisonValue() {
                if (type == BEFORE_FIRST_ITEM) {
                    return mFirstKnownItem.lowerLimit - 1;
                } else if (type == AFTER_LAST_ITEM) {
                    return mLastKnownItem.upperLimit + 1;
                } else if (type == BETWEEN_TWO_ITEMS) {
                    return limitsBeforeCoordinate.upperLimit + 1;
                } else {
                    return limitsBeforeCoordinate.lowerLimit;
                }
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof GridModel.RelativeCoordinate)) {
                    return false;
                }

                GridModel.RelativeCoordinate otherCoordinate = (GridModel.RelativeCoordinate) other;
                return toComparisonValue() == otherCoordinate.toComparisonValue();
            }

            @Override
            public int compareTo(GridModel.RelativeCoordinate other) {
                return toComparisonValue() - other.toComparisonValue();
            }
        }

        /**
         * The location of a point relative to the Limits of nearby items; consists of both an x- and
         * y-RelativeCoordinateLocation.
         */
        private class RelativePoint {
            final GridModel.RelativeCoordinate xLocation;
            final GridModel.RelativeCoordinate yLocation;

            RelativePoint(Point point) {
                this.xLocation = new RelativeCoordinate(mColumnBounds, point.x);
                this.yLocation = new RelativeCoordinate(mRowBounds, point.y);
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof RelativePoint)) {
                    return false;
                }

                RelativePoint otherPoint = (RelativePoint) other;
                return xLocation.equals(otherPoint.xLocation) && yLocation.equals(otherPoint.yLocation);
            }
        }

        /**
         * Generates a rectangle which contains the items selected by the pointer and origin.
         * @return The rectangle, or null if no items were selected.
         */
        private Rect computeBounds() {
            Rect rect = new Rect();
            rect.left = getCoordinateValue(
                    min(mRelativeOrigin.xLocation, mRelativePointer.xLocation),
                    mColumnBounds,
                    true);
            rect.right = getCoordinateValue(
                    max(mRelativeOrigin.xLocation, mRelativePointer.xLocation),
                    mColumnBounds,
                    false);
            rect.top = getCoordinateValue(
                    min(mRelativeOrigin.yLocation, mRelativePointer.yLocation),
                    mRowBounds,
                    true);
            rect.bottom = getCoordinateValue(
                    max(mRelativeOrigin.yLocation, mRelativePointer.yLocation),
                    mRowBounds,
                    false);
            return rect;
        }

        /**
         * Computes the corner of the selection nearest the origin.
         * @return
         */
        private int computeCornerNearestOrigin() {
            int cornerValue = 0;

            if (mRelativeOrigin.yLocation ==
                    min(mRelativeOrigin.yLocation, mRelativePointer.yLocation)) {
                cornerValue |= UPPER;
            } else {
                cornerValue |= LOWER;
            }

            if (mRelativeOrigin.xLocation ==
                    min(mRelativeOrigin.xLocation, mRelativePointer.xLocation)) {
                cornerValue |= LEFT;
            } else {
                cornerValue |= RIGHT;
            }

            return cornerValue;
        }

        private GridModel.RelativeCoordinate min(GridModel.RelativeCoordinate first, GridModel.RelativeCoordinate second) {
            return first.compareTo(second) < 0 ? first : second;
        }

        private GridModel.RelativeCoordinate max(GridModel.RelativeCoordinate first, GridModel.RelativeCoordinate second) {
            return first.compareTo(second) > 0 ? first : second;
        }

        /**
         * @return The absolute coordinate (i.e., the x- or y-value) of the given relative
         *     coordinate.
         */
        private int getCoordinateValue(GridModel.RelativeCoordinate coordinate,
                List<GridModel.Limits> limitsList, boolean isStartOfRange) {
            switch (coordinate.type) {
                case RelativeCoordinate.BEFORE_FIRST_ITEM:
                    return limitsList.get(0).lowerLimit;
                case RelativeCoordinate.AFTER_LAST_ITEM:
                    return limitsList.get(limitsList.size() - 1).upperLimit;
                case RelativeCoordinate.BETWEEN_TWO_ITEMS:
                    if (isStartOfRange) {
                        return coordinate.limitsAfterCoordinate.lowerLimit;
                    } else {
                        return coordinate.limitsBeforeCoordinate.upperLimit;
                    }
                case RelativeCoordinate.WITHIN_LIMITS:
                    return coordinate.limitsBeforeCoordinate.lowerLimit;
            }

            throw new RuntimeException("Invalid coordinate value.");
        }

        private boolean areItemsCoveredByBand(
                RelativePoint first, RelativePoint second) {
            return doesCoordinateLocationCoverItems(first.xLocation, second.xLocation) &&
                    doesCoordinateLocationCoverItems(first.yLocation, second.yLocation);
        }

        private boolean doesCoordinateLocationCoverItems(
                GridModel.RelativeCoordinate pointerCoordinate,
                GridModel.RelativeCoordinate originCoordinate) {
            if (pointerCoordinate.type == RelativeCoordinate.BEFORE_FIRST_ITEM &&
                    originCoordinate.type == RelativeCoordinate.BEFORE_FIRST_ITEM) {
                return false;
            }

            if (pointerCoordinate.type == RelativeCoordinate.AFTER_LAST_ITEM &&
                    originCoordinate.type == RelativeCoordinate.AFTER_LAST_ITEM) {
                return false;
            }

            if (pointerCoordinate.type == RelativeCoordinate.BETWEEN_TWO_ITEMS &&
                    originCoordinate.type == RelativeCoordinate.BETWEEN_TWO_ITEMS &&
                    pointerCoordinate.limitsBeforeCoordinate.equals(
                            originCoordinate.limitsBeforeCoordinate) &&
                    pointerCoordinate.limitsAfterCoordinate.equals(
                            originCoordinate.limitsAfterCoordinate)) {
                return false;
            }

            return true;
        }
    }

    /**
     * Provides functionality for BandController. Exists primarily to tests that are
     * fully isolated from RecyclerView.
     */
    interface SelectionEnvironment {
        void showBand(Rect rect);
        void hideBand();
        void addOnScrollListener(RecyclerView.OnScrollListener listener);
        void removeOnScrollListener(RecyclerView.OnScrollListener listener);
        void scrollBy(int dy);
        int getHeight();
        void invalidateView();
        void runAtNextFrame(Runnable r);
        void removeCallback(Runnable r);
        Point createAbsolutePoint(Point relativePoint);
        Rect getAbsoluteRectForChildViewAt(int index);
        int getAdapterPositionAt(int index);
        int getColumnCount();
        int getChildCount();
        int getVisibleChildCount();
        /**
         * Layout items are excluded from the GridModel.
         */
        boolean isLayoutItem(int adapterPosition);
        /**
         * Items may be in the adapter, but without an attached view.
         */
        boolean hasView(int adapterPosition);
    }

    /** Recycler view facade implementation backed by good ol' RecyclerView. */
    private static final class RuntimeSelectionEnvironment implements SelectionEnvironment {

        private final RecyclerView mView;
        private final Drawable mBand;

        private boolean mIsOverlayShown = false;

        RuntimeSelectionEnvironment(RecyclerView view) {
            mView = view;
            mBand = mView.getContext().getTheme().getDrawable(R.drawable.band_select_overlay);
        }

        @Override
        public int getAdapterPositionAt(int index) {
            return mView.getChildAdapterPosition(mView.getChildAt(index));
        }

        @Override
        public void addOnScrollListener(RecyclerView.OnScrollListener listener) {
            mView.addOnScrollListener(listener);
        }

        @Override
        public void removeOnScrollListener(RecyclerView.OnScrollListener listener) {
            mView.removeOnScrollListener(listener);
        }

        @Override
        public Point createAbsolutePoint(Point relativePoint) {
            return new Point(relativePoint.x + mView.computeHorizontalScrollOffset(),
                    relativePoint.y + mView.computeVerticalScrollOffset());
        }

        @Override
        public Rect getAbsoluteRectForChildViewAt(int index) {
            final View child = mView.getChildAt(index);
            final Rect childRect = new Rect();
            child.getHitRect(childRect);
            childRect.left += mView.computeHorizontalScrollOffset();
            childRect.right += mView.computeHorizontalScrollOffset();
            childRect.top += mView.computeVerticalScrollOffset();
            childRect.bottom += mView.computeVerticalScrollOffset();
            return childRect;
        }

        @Override
        public int getChildCount() {
            return mView.getAdapter().getItemCount();
        }

        @Override
        public int getVisibleChildCount() {
            return mView.getChildCount();
        }

        @Override
        public int getColumnCount() {
            RecyclerView.LayoutManager layoutManager = mView.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                return ((GridLayoutManager) layoutManager).getSpanCount();
            }

            // Otherwise, it is a list with 1 column.
            return 1;
        }

        @Override
        public int getHeight() {
            return mView.getHeight();
        }

        @Override
        public void invalidateView() {
            mView.invalidate();
        }

        @Override
        public void runAtNextFrame(Runnable r) {
            mView.postOnAnimation(r);
        }

        @Override
        public void removeCallback(Runnable r) {
            mView.removeCallbacks(r);
        }

        @Override
        public void scrollBy(int dy) {
            mView.scrollBy(0, dy);
        }

        @Override
        public void showBand(Rect rect) {
            mBand.setBounds(rect);

            if (!mIsOverlayShown) {
                mView.getOverlay().add(mBand);
            }
        }

        @Override
        public void hideBand() {
            mView.getOverlay().remove(mBand);
        }

        @Override
        public boolean isLayoutItem(int pos) {
            // The band selection model only operates on documents and directories. Exclude other
            // types of adapter items (e.g. whitespace items like dividers).
            RecyclerView.ViewHolder vh = mView.findViewHolderForAdapterPosition(pos);
            switch (vh.getItemViewType()) {
                case ITEM_TYPE_DOCUMENT:
                case ITEM_TYPE_DIRECTORY:
                    return false;
                default:
                    return true;
            }
        }

        @Override
        public boolean hasView(int pos) {
            return mView.findViewHolderForAdapterPosition(pos) != null;
        }
    }
}