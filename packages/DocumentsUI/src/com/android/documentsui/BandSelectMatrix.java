/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.LayoutManager;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.util.SparseBooleanArray;
import android.view.View;

/**
 * Provides a band selection item model for views within a RecyclerView. This class queries the
 * RecyclerView to determine where its items are placed; then, once band selection is underway, it
 * alerts listeners of which items are covered by the selections.
 */
public final class BandSelectMatrix extends RecyclerView.OnScrollListener {

    private final RecyclerViewHelper mHelper;
    private final List<OnSelectionChangedListener> mOnSelectionChangedListeners = new ArrayList<>();

    // Map from the x-value of the left side of an item to an ordered list of metadata of all items
    // whose x-values are the same. The list is ordered by the y-values of the items in the column.
    // For example, if the first column of the view starts at an x-value of 5, mColumns.get(5) would
    // return a list of all items in that column, with the top-most item first in the list and the
    // bottom-most item last in the list.
    private final Map<Integer, List<ItemData>> mColumns = new HashMap<>();

    // List of limits along the x-axis. For example, if the view has two columns, this list will
    // have two elements, each of which lists the lower- and upper-limits of the x-values of the
    // view items. This list is sorted from furthest left to furthest right.
    private final List<Limits> mXLimitsList = new ArrayList<>();

    // Like mXLimitsList, but for y-coordinates. Note that this list only contains items which have
    // been in the viewport. Thus, limits which exist in an area of the view to which the view has
    // not scrolled are not present in the list.
    private final List<Limits> mYLimitsList = new ArrayList<>();

    // The adapter positions which have been recorded so far.
    private final SparseBooleanArray mRecordedPositions = new SparseBooleanArray();

    // Array passed to registered OnSelectionChangedListeners. One array is created and reused
    // throughout the lifetime of the object.
    private final SparseBooleanArray mSelectionForListeners = new SparseBooleanArray();

    // The current pointer (in absolute positioning from the top of the view).
    private Point mPointer = null;

    // The bounds of the band selection.
    private RelativePoint mRelativeOrigin;
    private RelativePoint mRelativePointer;

    BandSelectMatrix(RecyclerViewHelper helper) {
        mHelper = helper;
        mHelper.addOnScrollListener(this);
    }

    BandSelectMatrix(RecyclerView rv) {
        this(new RuntimeRecyclerViewHelper(rv));
    }

    /**
     * Stops listening to the view's scrolls. Call this function before discarding a
     * BandSelecMatrix object to prevent memory leaks.
     */
    void stopListening() {
        mHelper.removeOnScrollListener(this);
    }

    /**
     * Start a band select operation at the given point.
     * @param relativeOrigin The origin of the band select operation, relative to the viewport.
     *     For example, if the view is scrolled to the bottom, the top-left of the viewport would
     *     have a relative origin of (0, 0), even though its absolute point has a higher y-value.
     */
    void startSelection(Point relativeOrigin) {
        Point absoluteOrigin = mHelper.createAbsolutePoint(relativeOrigin);
        mPointer = new Point(absoluteOrigin.x, absoluteOrigin.y);

        processVisibleChildren();
        mRelativeOrigin = new RelativePoint(absoluteOrigin);
        mRelativePointer = new RelativePoint(mPointer);
        computeCurrentSelection();
        notifyListeners();
    }

    /**
     * Resizes the selection by adjusting the pointer (i.e., the corner of the selection opposite
     * the origin.
     * @param relativePointer The pointer (opposite of the origin) of the band select operation,
     *     relative to the viewport. For example, if the view is scrolled to the bottom, the
     *     top-left of the viewport would have a relative origin of (0, 0), even though its absolute
     *     point has a higher y-value.
     */
    void resizeSelection(Point relativePointer) {
        mPointer = mHelper.createAbsolutePoint(relativePointer);
        handlePointerMoved();
    }

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        if (mPointer == null) {
            return;
        }

        mPointer.x += dx;
        mPointer.y += dy;
        processVisibleChildren();
        handlePointerMoved();
    }

    /**
     * Queries the view for all children and records their location metadata.
     */
    private void processVisibleChildren() {
        for (int i = 0; i < mHelper.getVisibleChildCount(); i++) {
            int adapterPosition = mHelper.getAdapterPositionAt(i);
            if (!mRecordedPositions.get(adapterPosition)) {
                mRecordedPositions.put(adapterPosition, true);
                captureItemLayoutData(mHelper.getAbsoluteRectForChildViewAt(i), adapterPosition);
            }
        }
    }

    /**
     * Updates the limits lists and column map with the given item metadata.
     * @param absoluteChildRect The absolute rectangle for the child view being processed.
     * @param adapterPosition The position of the child view being processed.
     */
    private void captureItemLayoutData(Rect absoluteChildRect, int adapterPosition) {
        if (mXLimitsList.size() != mHelper.getNumColumns()) {
            // If not all x-limits have been recorded, record this one.
            recordLimits(
                    mXLimitsList, new Limits(absoluteChildRect.left, absoluteChildRect.right));
        }

        if (mYLimitsList.size() != mHelper.getNumRows()) {
            // If not all y-limits have been recorded, record this one.
            recordLimits(
                    mYLimitsList, new Limits(absoluteChildRect.top, absoluteChildRect.bottom));
        }

        List<ItemData> columnList = mColumns.get(absoluteChildRect.left);
        if (columnList == null) {
            columnList = new ArrayList<ItemData>();
            mColumns.put(absoluteChildRect.left, columnList);
        }
        recordItemData(
                columnList, new ItemData(adapterPosition, absoluteChildRect.top));
    }

    /**
     * Ensures limits exists within the sorted list limitsList, and adds it to the list if it does
     * not exist.
     */
    private static void recordLimits(List<Limits> limitsList, Limits limits) {
        int index = Collections.binarySearch(limitsList, limits);
        if (index < 0) {
            limitsList.add(~index, limits);
        }
    }

    /**
     * Ensures itemData exists within the sorted list itemDataList, and adds it to the list if it
     * does not exist.
     */
    private static void recordItemData(List<ItemData> itemDataList, ItemData itemData) {
        int index = Collections.binarySearch(itemDataList, itemData);
        if (index < 0) {
            itemDataList.add(~index, itemData);
        }
    }

    /**
     * Handles a moved pointer; this function determines whether the pointer movement resulted in a
     * selection change and, if it has, notifies listeners of this change.
     */
    private void handlePointerMoved() {
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
        Rect selectionRect = mRelativePointer.computeBounds(mRelativeOrigin);
        computePositionsCoveredByRect(selectionRect);
    }

    /**
     * Notifies all listeners of a selection change. Note that this function simply passes
     * mSelectionForListeners, so computeCurrentSelection() should be called before this function.
     */
    private void notifyListeners() {
        for (OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
            listener.onSelectionChanged(mSelectionForListeners);
        }
    }

    /**
     * @param rect Rectangle including all covered items.
     */
    private void computePositionsCoveredByRect(@Nullable Rect rect) {
        mSelectionForListeners.clear();
        if (rect == null) {
            // If there is no bounding rectangle, there are no items selected, so just return early.
            return;
        }

        int columnIndex = Collections.binarySearch(mXLimitsList, new Limits(rect.left, rect.left));
        Preconditions.checkState(columnIndex >= 0);

        for (; columnIndex < mXLimitsList.size() &&
                mXLimitsList.get(columnIndex).lowerLimit <= rect.right; columnIndex++) {
            List<ItemData> positions =
                    mColumns.get(mXLimitsList.get(columnIndex).lowerLimit);
            int rowIndex = Collections.binarySearch(positions, new ItemData(0, rect.top));
            if (rowIndex < 0) {
                // If band select occurs after the last item in a row with fewer items than columns,
                // go to the next column. This situation occurs in the last row of the grid when the
                // total number of items is not a multiple of the number of columns (e.g., when 10
                // items exist in a grid with 4 columns).
                continue;
            }

            for (; rowIndex < positions.size() &&
                    positions.get(rowIndex).offset <= rect.bottom; rowIndex++) {
                mSelectionForListeners.append(positions.get(rowIndex).position, true);
            }
        }
    }

    /**
     * Provides functionality for interfacing with the view. In practice, RecyclerViewMatrixHelper
     * should be used; this interface exists solely for the purpose of decoupling the view from
     * this class so that the view can be mocked out for tests.
     */
    interface RecyclerViewHelper {
        public void addOnScrollListener(RecyclerView.OnScrollListener listener);
        public void removeOnScrollListener(RecyclerView.OnScrollListener listener);
        public Point createAbsolutePoint(Point relativePoint);
        public int getVisibleChildCount();
        public int getTotalChildCount();
        public int getNumColumns();
        public int getNumRows();
        public int getAdapterPositionAt(int index);
        public Rect getAbsoluteRectForChildViewAt(int index);
    }

    /**
     * Concrete MatrixHelper implementation for use within the Files app.
     */
    static class RuntimeRecyclerViewHelper implements RecyclerViewHelper {
        private final RecyclerView mRecyclerView;

        RuntimeRecyclerViewHelper(RecyclerView rv) {
            mRecyclerView = rv;
        }

        @Override
        public int getAdapterPositionAt(int index) {
            View child = mRecyclerView.getChildAt(index);
            return mRecyclerView.getChildViewHolder(child).getAdapterPosition();
        }

        @Override
        public void addOnScrollListener(OnScrollListener listener) {
            mRecyclerView.addOnScrollListener(listener);
        }

        @Override
        public void removeOnScrollListener(OnScrollListener listener) {
            mRecyclerView.removeOnScrollListener(listener);
        }

        @Override
        public Point createAbsolutePoint(Point relativePoint) {
            return new Point(relativePoint.x + mRecyclerView.computeHorizontalScrollOffset(),
                    relativePoint.y + mRecyclerView.computeVerticalScrollOffset());
        }

        @Override
        public Rect getAbsoluteRectForChildViewAt(int index) {
            final View child = mRecyclerView.getChildAt(index);
            final Rect childRect = new Rect();
            child.getHitRect(childRect);
            childRect.left += mRecyclerView.computeHorizontalScrollOffset();
            childRect.right += mRecyclerView.computeHorizontalScrollOffset();
            childRect.top += mRecyclerView.computeVerticalScrollOffset();
            childRect.bottom += mRecyclerView.computeVerticalScrollOffset();
            return childRect;
        }

        @Override
        public int getVisibleChildCount() {
            return mRecyclerView.getChildCount();
        }

        @Override
        public int getTotalChildCount() {
            return mRecyclerView.getAdapter().getItemCount();
        }

        @Override
        public int getNumColumns() {
            LayoutManager layoutManager = mRecyclerView.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                return ((GridLayoutManager) layoutManager).getSpanCount();
            }

            // Otherwise, it is a list with 1 column.
            return 1;
        }

        @Override
        public int getNumRows() {
            int numFullColumns = getTotalChildCount() / getNumColumns();
            boolean hasPartiallyFullColumn = getTotalChildCount() % getNumColumns() != 0;
            return numFullColumns + (hasPartiallyFullColumn ? 1 : 0);
        }
    }

    /**
     * Listener for changes in which items have been band selected.
     */
    interface OnSelectionChangedListener {
        public void onSelectionChanged(SparseBooleanArray updatedSelection);
    }

    void addOnSelectionChangedListener(OnSelectionChangedListener listener) {
        mOnSelectionChangedListeners.add(listener);
    }

    void removeOnSelectionChangedListener(OnSelectionChangedListener listener) {
        mOnSelectionChangedListeners.remove(listener);
    }

    /**
     * Metadata for an item in the view, consisting of the adapter position and the offset from the
     * top of the view (in pixels). Stored in the mColumns map to model the item grid.
     */
    private static class ItemData implements Comparable<ItemData> {
        int position;
        int offset;

        ItemData(int position, int offset) {
            this.position = position;
            this.offset = offset;
        }

        @Override
        public int compareTo(ItemData other) {
            // The list of columns is sorted via the offset from the top, so PositionMetadata
            // objects with lower y-values are befor those with higher y-values.
            return offset - other.offset;
        }
    }

    /**
     * Limits of a view item. For example, if an item's left side is at x-value 5 and its right side
     * is at x-value 10, the limits would be from 5 to 10. Used to record the left- and right sides
     * of item columns and the top- and bottom sides of item rows so that it can be determined
     * whether the pointer is located within the bounds of an item.
     */
    private static class Limits implements Comparable<Limits> {
        int lowerLimit;
        int upperLimit;

        Limits(int lowerLimit, int upperLimit) {
            this.lowerLimit = lowerLimit;
            this.upperLimit = upperLimit;
        }

        @Override
        public int compareTo(Limits other) {
            return lowerLimit - other.lowerLimit;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Limits)) {
                return false;
            }

            return ((Limits) other).lowerLimit == lowerLimit &&
                    ((Limits) other).upperLimit == upperLimit;
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
            implements Comparable<RelativeCoordinate> {
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
        Limits limitsBeforeCoordinate;

        /**
         * The limits after the coordinate; only populated when type == BETWEEN_TWO_ITEMS.
         */
        Limits limitsAfterCoordinate;

        // Limits of the first known item; only populated when type == BEFORE_FIRST_ITEM.
        Limits mFirstKnownItem;
        // Limits of the last known item; only populated when type == AFTER_LAST_ITEM.
        Limits mLastKnownItem;

        /**
         * @param limitsList The sorted limits list for the coordinate type. If this
         *     CoordinateLocation is an x-value, mXLimitsList should be passed; otherwise,
         *     mYLimitsList should be pased.
         * @param value The coordinate value.
         */
        RelativeCoordinate(List<Limits> limitsList, int value) {
            Limits dummyLimits = new Limits(value, value);
            int index = Collections.binarySearch(limitsList, dummyLimits);

            if (index >= 0) {
                this.type = WITHIN_LIMITS;
                this.limitsBeforeCoordinate = limitsList.get(index);
            } else if (~index == 0) {
                this.type = BEFORE_FIRST_ITEM;
                this.mFirstKnownItem = limitsList.get(0);
            } else if (~index == limitsList.size()) {
                Limits lastLimits = limitsList.get(limitsList.size() - 1);
                if (lastLimits.lowerLimit <= value && value <= lastLimits.upperLimit) {
                    this.type = WITHIN_LIMITS;
                    this.limitsBeforeCoordinate = lastLimits;
                } else {
                    this.type = AFTER_LAST_ITEM;
                    this.mLastKnownItem = lastLimits;
                }
            } else {
                Limits limitsBeforeIndex = limitsList.get(~index - 1);
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
            if (!(other instanceof RelativeCoordinate)) {
                return false;
            }

            RelativeCoordinate otherCoordinate = (RelativeCoordinate) other;
            return toComparisonValue() == otherCoordinate.toComparisonValue();
        }

        @Override
        public int compareTo(RelativeCoordinate other) {
            return toComparisonValue() - other.toComparisonValue();
        }
    }

    /**
     * The location of a point relative to the Limits of nearby items; consists of both an x- and
     * y-RelativeCoordinateLocation.
     */
    private class RelativePoint {
        final RelativeCoordinate xLocation;
        final RelativeCoordinate yLocation;

        RelativePoint(Point point) {
            this.xLocation = new RelativeCoordinate(mXLimitsList, point.x);
            this.yLocation = new RelativeCoordinate(mYLimitsList, point.y);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RelativePoint)) {
                return false;
            }

            RelativePoint otherPoint = (RelativePoint) other;
            return xLocation.equals(otherPoint.xLocation) && yLocation.equals(otherPoint.yLocation);
        }

        /**
         * Generates a rectangle which contains the items selected by the two points.
         * @param other The other PointLocation. A rectangle will be formed between "this" and
         *     "other".
         * @return The rectangle, or null if no items were selected.
         */
        Rect computeBounds(RelativePoint other) {
            if (!areItemsCoveredBySelection(mRelativePointer, mRelativeOrigin)) {
                return null;
            }

            RelativeCoordinate minXLocation =
                    xLocation.compareTo(other.xLocation) < 0 ? xLocation : other.xLocation;
            RelativeCoordinate maxXLocation =
                    minXLocation == xLocation ? other.xLocation : xLocation;
            RelativeCoordinate minYLocation =
                    yLocation.compareTo(other.yLocation) < 0 ? yLocation : other.yLocation;
            RelativeCoordinate maxYLocation =
                    minYLocation == yLocation ? other.yLocation : yLocation;

            Rect rect = new Rect();
            rect.left = getCoordinateValue(minXLocation, mXLimitsList, true);
            rect.right = getCoordinateValue(maxXLocation, mXLimitsList, false);
            rect.top = getCoordinateValue(minYLocation, mYLimitsList, true);
            rect.bottom = getCoordinateValue(maxYLocation, mYLimitsList, false);
            return rect;
        }

        int getCoordinateValue(RelativeCoordinate coordinate,
                List<Limits> limitsList, boolean isStartOfRange) {
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
    }

    private static boolean areItemsCoveredBySelection(
            RelativePoint first, RelativePoint second) {
        return doesCoordinateLocationCoverItems(first.xLocation, second.xLocation) &&
                doesCoordinateLocationCoverItems(first.yLocation, second.yLocation);
    }

    private static boolean doesCoordinateLocationCoverItems(
            RelativeCoordinate pointerCoordinate,
            RelativeCoordinate originCoordinate) {
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
                pointerCoordinate.limitsBeforeCoordinate.equals(originCoordinate) &&
                pointerCoordinate.limitsAfterCoordinate.equals(originCoordinate)) {
            return false;
        }

        return true;
    }
}
