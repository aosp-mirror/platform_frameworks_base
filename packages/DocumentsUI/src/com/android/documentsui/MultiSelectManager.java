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

package com.android.documentsui;

import static com.android.documentsui.Events.isMouseEvent;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.Adapter;
import android.support.v7.widget.RecyclerView.AdapterDataObserver;
import android.support.v7.widget.RecyclerView.LayoutManager;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MultiSelectManager provides support traditional multi-item selection support to RecyclerView.
 * Additionally it can be configured to restrict selection to a single element, @see
 * #setSelectMode.
 */
public final class MultiSelectManager {

    /** Selection mode for multiple select. **/
    public static final int MODE_MULTIPLE = 0;

    /** Selection mode for multiple select. **/
    public static final int MODE_SINGLE = 1;

    private static final String TAG = "MultiSelectManager";
    private static final boolean DEBUG = false;

    private final Selection mSelection = new Selection();

    // Only created when selection is cleared.
    private Selection mIntermediateSelection;

    private Range mRanger;
    private final List<MultiSelectManager.Callback> mCallbacks = new ArrayList<>(1);

    private Adapter<?> mAdapter;
    private MultiSelectHelper mHelper;
    private boolean mSingleSelect;
    private BandSelectManager mBandSelectManager;

    /**
     * @param recyclerView
     * @param gestureDelegate Option delegate gesture listener.
     * @param mode Selection mode
     * @template A gestureDelegate that implements both {@link OnGestureListener}
     *     and {@link OnDoubleTapListener}
     */
    public <L extends OnGestureListener & OnDoubleTapListener> MultiSelectManager(
            final RecyclerView recyclerView, L gestureDelegate, int mode) {

        this(
                recyclerView.getAdapter(),
                new RuntimeRecyclerViewHelper(recyclerView),
                mode);

        mBandSelectManager = new BandSelectManager((RuntimeRecyclerViewHelper) mHelper);

        GestureDetector.SimpleOnGestureListener listener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        return MultiSelectManager.this.onSingleTapUp(e);
                    }
                    @Override
                    public void onLongPress(MotionEvent e) {
                        MultiSelectManager.this.onLongPress(e);
                    }
                };

        CompositeOnGestureListener<? extends Object> compositeListener =
                new CompositeOnGestureListener<>(listener, gestureDelegate);
        final GestureDetector detector =
                new GestureDetector(recyclerView.getContext(), compositeListener);

        detector.setOnDoubleTapListener(compositeListener);

        recyclerView.addOnItemTouchListener(
                new RecyclerView.OnItemTouchListener() {
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        detector.onTouchEvent(e);

                        // Only intercept the event if it was a mouse-based band selection.
                        return isMouseEvent(e) && (mBandSelectManager.mIsActive ||
                                e.getActionMasked() != MotionEvent.ACTION_UP);
                    }
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                        checkState(isMouseEvent(e));
                        mBandSelectManager.processMotionEvent(e);
                    }
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });
    }

    /**
     * Constructs a new instance with {@code adapter} and {@code helper}.
     * @hide
     */
    @VisibleForTesting
    MultiSelectManager(Adapter<?> adapter, MultiSelectHelper helper, int mode) {
        checkNotNull(adapter, "'adapter' cannot be null.");
        checkNotNull(helper, "'helper' cannot be null.");

        mSingleSelect = mode == MODE_SINGLE;

        mHelper = helper;
        mAdapter = adapter;

        mAdapter.registerAdapterDataObserver(
                new AdapterDataObserver() {

                    @Override
                    public void onChanged() {
                        mSelection.clear();
                    }

                    @Override
                    public void onItemRangeChanged(
                            int positionStart, int itemCount, Object payload) {
                        // No change in position. Ignoring.
                    }

                    @Override
                    public void onItemRangeInserted(int positionStart, int itemCount) {
                        mSelection.expand(positionStart, itemCount);
                    }

                    @Override
                    public void onItemRangeRemoved(int positionStart, int itemCount) {
                        mSelection.collapse(positionStart, itemCount);
                    }

                    @Override
                    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                        throw new UnsupportedOperationException();
                    }
                });
    }

    /**
     * Adds {@code callback} such that it will be notified when {@code MultiSelectManager}
     * events occur.
     *
     * @param callback
     */
    public void addCallback(MultiSelectManager.Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Returns a Selection object that provides a live view
     * on the current selection. Callers wishing to get
     *
     * @see #getSelectionSnapshot() on how to get a snapshot
     *     of the selection that will not reflect future changes
     *     to selection.
     *
     * @return The current seleciton.
     */
    public Selection getSelection() {
        return mSelection;
    }

    /**
     * Updates {@code dest} to reflect the current selection.
     * @param dest
     *
     * @return The Selection instance passed in, for convenience.
     */
    public Selection getSelection(Selection dest) {
        dest.copyFrom(mSelection);
        return dest;
    }

    /**
     * Causes item at {@code position} in adapter to be selected.
     *
     * @param position Adapter position
     * @param selected
     * @return True if the selection state of the item changed.
     */
    @VisibleForTesting
    public boolean setItemSelected(int position, boolean selected) {
        if (mSingleSelect && !mSelection.isEmpty()) {
            clearSelectionQuietly();
        }
        return setItemsSelected(position, 1, selected);
    }

    /**
     * Sets the selected state of the specified items. Note that the callback will NOT
     * be consulted to see if an item can be selected.
     *
     * @return True if the selection state of any of the items changed.
     */
    public boolean setItemsSelected(int position, int length, boolean selected) {
        boolean changed = false;
        for (int i = position; i < position + length; i++) {
            boolean itemChanged = selected ? mSelection.add(i) : mSelection.remove(i);
            if (itemChanged) {
                notifyItemStateChanged(i, selected);
            }
            changed |= itemChanged;
        }

        notifySelectionChanged();
        return changed;
    }

    /**
     * Clears the selection and notifies (even if nothing changes).
     */
    public void clearSelection() {
        clearSelectionQuietly();
        notifySelectionChanged();
    }

    /**
     * Clears the selection, without notifying anyone.
     */
    private void clearSelectionQuietly() {
        mRanger = null;

        if (mSelection.isEmpty()) {
            return;
        }
        if (mIntermediateSelection == null) {
            mIntermediateSelection = new Selection();
        }
        getSelection(mIntermediateSelection);
        mSelection.clear();

        for (int i = 0; i < mIntermediateSelection.size(); i++) {
            int position = mIntermediateSelection.get(i);
            notifyItemStateChanged(position, false);
        }
    }

    private void onLongPress(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling long press event.");

        int position = mHelper.findEventPosition(e);
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        onLongPress(position, e.getMetaState());
    }

    /**
     * TODO: Roll this back into {@link #onLongPress(MotionEvent)} once MotionEvent
     * can be mocked.
     *
     * @param position
     * @param metaState as returned from {@link MotionEvent#getMetaState()}.
     * @hide
     */
    @VisibleForTesting
    void onLongPress(int position, int metaState) {
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        handlePositionChanged(position, metaState);
    }

    /**
     * @param e
     * @return true if the event was consumed.
     */
    private boolean onSingleTapUp(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling tap event.");
        return onSingleTapUp(mHelper.findEventPosition(e), e.getMetaState(), e.getToolType(0));
    }

    /**
     * TODO: Roll this into {@link #onSingleTapUp(MotionEvent)} once MotionEvent
     * can be mocked.
     *
     * @param position
     * @param metaState as returned from {@link MotionEvent#getMetaState()}.
     * @param toolType
     * @return true if the event was consumed.
     * @hide
     */
    @VisibleForTesting
    boolean onSingleTapUp(int position, int metaState, int toolType) {
        if (mSelection.isEmpty()) {
            // if this is a mouse click on an item, start selection mode.
            if (position != RecyclerView.NO_POSITION && Events.isMouseType(toolType)) {
                toggleSelection(position);
            }
            return false;
        }

        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "View is null. Canceling selection.");
            clearSelection();
            return false;
        }

        handlePositionChanged(position, metaState);
        return false;
    }

    /**
     * Handles a change caused by a click on the item with the given position. If the Shift key is
     * held down, this performs a range select; otherwise, it simply toggles the item's selection
     * state.
     */
    private void handlePositionChanged(int position, int metaState) {
        if (Events.hasShiftBit(metaState) && mRanger != null) {
            mRanger.snapSelection(position);

            // We're being lazy here notifying even when something might not have changed.
            // To make this more correct, we'd need to update the Ranger class to return
            // information about what has changed.
            notifySelectionChanged();
        } else if (toggleSelection(position)) {
            notifySelectionChanged();
        }
    }

    /**
     * Toggles the selection state at position. If an item does end up selected
     * a new Ranger (range selection manager) at that point is created.
     *
     * @param position
     * @return True if state changed.
     */
    private boolean toggleSelection(int position) {
        // Position may be special "no position" during certain
        // transitional phases. If so, skip handling of the event.
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "Ignoring toggle for element with no position.");
            return false;
        }

        boolean changed = false;
        if (mSelection.contains(position)) {
            changed = attemptDeselect(position);
        } else {
            boolean canSelect = notifyBeforeItemStateChange(position, true);
            if (!canSelect) {
                return false;
            }
            if (mSingleSelect && !mSelection.isEmpty()) {
                clearSelectionQuietly();
            }

            // Here we're already in selection mode. In that case
            // When a simple click/tap (without SHIFT) creates causes
            // an item to be selected.
            // By recreating Ranger at this point, we allow the user to create
            // multiple separate contiguous ranges with SHIFT+Click & Click.
            selectAndNotify(position);
            setSelectionFocusBegin(position);
            changed = true;
        }

        return changed;
    }

    /**
     * Sets the magic location at which a selection range begins. This
     * value is consulted when determining how to extend, and modify
     * selection ranges.
     *
     * @throws IllegalStateException if {@code position} is not already be selected
     * @param position
     */
    void setSelectionFocusBegin(int position) {
        checkState(mSelection.contains(position));
        mRanger = new Range(position);
    }

    /**
     * Try to select all elements in range. Not that callbacks can cancel selection
     * of specific items, so some or even all items may not reflect the desired
     * state after the update is complete.
     *
     * @param begin inclusive
     * @param end inclusive
     * @param selected
     */
    private void updateRange(int begin, int end, boolean selected) {
        checkState(end >= begin);
        for (int i = begin; i <= end; i++) {
            if (selected) {
                boolean canSelect = notifyBeforeItemStateChange(i, true);
                if (canSelect) {
                    if (mSingleSelect && !mSelection.isEmpty()) {
                        clearSelectionQuietly();
                    }
                    selectAndNotify(i);
                }
            } else {
                attemptDeselect(i);
            }
        }
    }

    /**
     * @param position
     * @return True if the update was applied.
     */
    private boolean selectAndNotify(int position) {
        boolean changed = mSelection.add(position);
        if (changed) {
            notifyItemStateChanged(position, true);
        }
        return changed;
    }

    /**
     * @param position
     * @return True if the update was applied.
     */
    private boolean attemptDeselect(int position) {
        if (notifyBeforeItemStateChange(position, false)) {
            mSelection.remove(position);
            notifyItemStateChanged(position, false);
            if (DEBUG) Log.d(TAG, "Selection after deselect: " + mSelection);
            return true;
        } else {
            if (DEBUG) Log.d(TAG, "Select cancelled by listener.");
            return false;
        }
    }

    private boolean notifyBeforeItemStateChange(int position, boolean nextState) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            if (!mCallbacks.get(i).onBeforeItemStateChange(position, nextState)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notifies registered listeners when the selection status of a single item
     * (identified by {@code position}) changes.
     */
    private void notifyItemStateChanged(int position, boolean selected) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onItemStateChanged(position, selected);
        }
        mAdapter.notifyItemChanged(position);
    }

    /**
     * Notifies registered listeners when the selection has changed. This
     * notification should be sent only once a full series of changes
     * is complete, e.g. clearingSelection, or updating the single
     * selection from one item to another.
     */
    private void notifySelectionChanged() {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onSelectionChanged();
        }
    }

    /**
     * Class providing support for managing range selections.
     */
    private final class Range {
        private static final int UNDEFINED = -1;

        final int mBegin;
        int mEnd = UNDEFINED;

        public Range(int begin) {
            if (DEBUG) Log.d(TAG, "New Ranger created beginning @ " + begin);
            mBegin = begin;
        }

        private void snapSelection(int position) {
            checkState(mRanger != null);
            checkArgument(position != RecyclerView.NO_POSITION);

            if (mEnd == UNDEFINED || mEnd == mBegin) {
                // Reset mEnd so it can be established in establishRange.
                mEnd = UNDEFINED;
                establishRange(position);
            } else {
                reviseRange(position);
            }
        }

        private void establishRange(int position) {
            checkState(mRanger.mEnd == UNDEFINED);

            if (position == mBegin) {
                mEnd = position;
            }

            if (position > mBegin) {
                updateRange(mBegin + 1, position, true);
            } else if (position < mBegin) {
                updateRange(position, mBegin - 1, true);
            }

            mEnd = position;
        }

        private void reviseRange(int position) {
            checkState(mEnd != UNDEFINED);
            checkState(mBegin != mEnd);

            if (position == mEnd) {
                if (DEBUG) Log.i(TAG, "Skipping no-op revision click on mEndRange.");
            }

            if (mEnd > mBegin) {
                reviseAscendingRange(position);
            } else if (mEnd < mBegin) {
                reviseDescendingRange(position);
            }
            // the "else" case is covered by checkState at beginning of method.

            mEnd = position;
        }

        /**
         * Updates an existing ascending seleciton.
         * @param position
         */
        private void reviseAscendingRange(int position) {
            // Reducing or reversing the range....
            if (position < mEnd) {
                if (position < mBegin) {
                    updateRange(mBegin + 1, mEnd, false);
                    updateRange(position, mBegin -1, true);
                } else {
                    updateRange(position + 1, mEnd, false);
                }
            }

            // Extending the range...
            else if (position > mEnd) {
                updateRange(mEnd + 1, position, true);
            }
        }

        private void reviseDescendingRange(int position) {
            // Reducing or reversing the range....
            if (position > mEnd) {
                if (position > mBegin) {
                    updateRange(mEnd, mBegin - 1, false);
                    updateRange(mBegin + 1, position, true);
                } else {
                    updateRange(mEnd, position - 1, false);
                }
            }

            // Extending the range...
            else if (position < mEnd) {
                updateRange(position, mEnd - 1, true);
            }
        }
    }

    /**
     * Object representing the current selection. Provides read only access
     * public access, and private write access.
     */
    public static final class Selection {

        // This class tracks selected positions by managing two arrays: the saved selection, and
        // the total selection. Saved selections are those which have been completed by tapping an
        // item or by completing a band select operation. Provisional selections are selections
        // which have been temporarily created by an in-progress band select operation (once the
        // user releases the mouse button during a band select operation, the selected items
        // become saved). The total selection is the combination of both the saved selection and
        // the provisional selection. Tracking both separately is necessary to ensure that saved
        // selections do not become deselected when they are removed from the provisional selection;
        // for example, if item A is tapped (and selected), then an in-progress band select covers A
        // then uncovers A, A should still be selected as it has been saved. To ensure this
        // behavior, the saved selection must be tracked separately.
        private SparseBooleanArray mSavedSelection;
        private SparseBooleanArray mTotalSelection;

        public Selection() {
            mSavedSelection = new SparseBooleanArray();
            mTotalSelection = new SparseBooleanArray();
        }

        /**
         * @param position
         * @return true if the position is currently selected.
         */
        public boolean contains(int position) {
            return mTotalSelection.get(position);
        }

        /**
         * Useful for iterating over selection. Please note that
         * iteration should be done over a copy of the selection,
         * not the live selection.
         *
         * @see #copyTo(MultiSelectManager.Selection)
         *
         * @param index
         * @return the position value stored at specified index.
         */
        public int get(int index) {
            return mTotalSelection.keyAt(index);
        }

        /**
         * @return size of the selection.
         */
        public int size() {
            return mTotalSelection.size();
        }

        /**
         * @return true if the selection is empty.
         */
        public boolean isEmpty() {
            return mTotalSelection.size() == 0;
        }

        /**
         * Sets the provisional selection, which is a temporary selection that can be saved,
         * canceled, or adjusted at a later time. When a new provision selection is applied, the old
         * one (if it exists) is abandoned.
         * @return Array with entry for each position added or removed. Entries which were added
         *     contain a value of true, and entries which were removed contain a value of false.
         */
        @VisibleForTesting
        protected SparseBooleanArray setProvisionalSelection(
                SparseBooleanArray provisionalSelection) {
            SparseBooleanArray delta = new SparseBooleanArray();

            for (int i = 0; i < mTotalSelection.size(); i++) {
                int position = mTotalSelection.keyAt(i);
                if (!provisionalSelection.get(position) && !mSavedSelection.get(position)) {
                    // Remove each item that used to be in the selection but is unsaved and not in
                    // the new provisional selection.
                    delta.put(position, false);
                }
            }

            for (int i = 0; i < provisionalSelection.size(); i++) {
                int position = provisionalSelection.keyAt(i);
                if (!mTotalSelection.get(position)) {
                    // Add each item that was not previously in the selection but is in the
                    // new provisional selection.
                    delta.put(position, true);
                }
            }

            // Now, iterate through the changes and actually add/remove them to/from
            // mCurrentSelection. This could not be done in the previous loops because changing the
            // size of the selection mid-iteration changes iteration order erroneously.
            for (int i = 0; i < delta.size(); i++) {
                int position = delta.keyAt(i);
                if (delta.get(position)) {
                    mTotalSelection.put(position, true);
                } else {
                    mTotalSelection.delete(position);
                }
            }

            return delta;
        }

        /**
         * Saves the existing provisional selection. Once the provisional selection is saved,
         * subsequent provisional selections which are different from this existing one cannot
         * cause items in this existing provisional selection to become deselected.
         */
        @VisibleForTesting
        protected void applyProvisionalSelection() {
            mSavedSelection = mTotalSelection.clone();
        }

        /**
         * Abandons the existing provisional selection so that all items provisionally selected are
         * now deselected.
         */
        @VisibleForTesting
        protected void cancelProvisionalSelection() {
            mTotalSelection = mSavedSelection.clone();
        }

        private boolean flip(int position) {
            if (contains(position)) {
                remove(position);
                return false;
            } else {
                add(position);
                return true;
            }
        }

        /** @hide */
        @VisibleForTesting
        boolean add(int position) {
            if (!mTotalSelection.get(position)) {
                mTotalSelection.put(position, true);
                mSavedSelection.put(position, true);
                return true;
            }
            return false;
        }

        /** @hide */
        @VisibleForTesting
        boolean remove(int position) {
            if (mTotalSelection.get(position)) {
                mTotalSelection.delete(position);
                mSavedSelection.delete(position);
                return true;
            }
            return false;
        }

        /**
         * Adjusts the selection range to reflect the existence of newly inserted values at
         * the specified positions. This has the effect of adjusting all existing selected
         * positions within the specified range accordingly. Note that this function discards any
         * provisional selections which may have been applied.
         *
         * @param startPosition
         * @param count
         * @hide
         */
        @VisibleForTesting
        void expand(int startPosition, int count) {
            checkState(startPosition >= 0);
            checkState(count > 0);
            cancelProvisionalSelection();

            for (int i = 0; i < mTotalSelection.size(); i++) {
                int itemPosition = mTotalSelection.keyAt(i);
                if (itemPosition >= startPosition) {
                    mTotalSelection.setKeyAt(i, itemPosition + count);
                    mSavedSelection.setKeyAt(i, itemPosition + count);
                }
            }
        }

        /**
         * Adjusts the selection range to reflect the removal specified positions. This has
         * the effect of adjusting all existing selected positions within the specified range
         * accordingly. Note that this function discards any provisional selections which may have
         * been applied.
         *
         * @param startPosition
         * @param count The length of the range to collapse. Must be greater than 0.
         * @hide
         */
        @VisibleForTesting
        void collapse(int startPosition, int count) {
            checkState(startPosition >= 0);
            checkState(count > 0);

            int endPosition = startPosition + count - 1;

            SparseBooleanArray newSelection = new SparseBooleanArray();
            for (int i = 0; i < mSavedSelection.size(); i++) {
                int itemPosition = mSavedSelection.keyAt(i);
                if (itemPosition < startPosition) {
                    newSelection.append(itemPosition, true);
                } else if (itemPosition > endPosition) {
                    newSelection.append(itemPosition - count, true);
                }
            }
            mSavedSelection = newSelection;
            cancelProvisionalSelection();
        }

        /** @hide */
        @VisibleForTesting
        void clear() {
            mSavedSelection.clear();
            mTotalSelection.clear();
        }

        /** @hide */
        @VisibleForTesting
        void copyFrom(Selection source) {
            mSavedSelection = source.mSavedSelection.clone();
            mTotalSelection = source.mTotalSelection.clone();
        }

        @Override
        public String toString() {
            if (size() <= 0) {
                return "size=0, items=[]";
            }

            StringBuilder buffer = new StringBuilder(mTotalSelection.size() * 28);
            buffer.append("{size=")
                    .append(mTotalSelection.size())
                    .append(", ")
                    .append("items=[");
            for (int i=0; i < mTotalSelection.size(); i++) {
                if (i > 0) {
                    buffer.append(", ");
                }
                buffer.append(mTotalSelection.keyAt(i));
            }
            buffer.append("]}");
            return buffer.toString();
        }

        @Override
        public int hashCode() {
            return mSavedSelection.hashCode() ^ mTotalSelection.hashCode();
        }

        @Override
        public boolean equals(Object that) {
          if (this == that) {
              return true;
          }

          if (!(that instanceof Selection)) {
              return false;
          }

          return mSavedSelection.equals(((Selection) that).mSavedSelection) &&
                  mTotalSelection.equals(((Selection) that).mTotalSelection);
        }
    }

    /**
     * Provides functionality for MultiSelectManager. In practice, use RuntimeRecyclerViewHelper;
     * this interface exists only for mocking in tests.
     */
    interface MultiSelectHelper {
        int findEventPosition(MotionEvent e);
    }

    /**
     * Provides functionality for BandSelectManager. In practice, use RuntimeRecyclerViewHelper;
     * this interface exists only for mocking in tests.
     */
    interface BandManagerHelper {
        void drawBand(Rect rect);
        int findEventPosition(MotionEvent e);
        int getHeight();
        void hideBand();
        void invalidateView();
        void postRunnable(Runnable r);
        void removeCallback(Runnable r);
        void scrollBy(int dy);
    }

    /**
     * Provides functionality for BandSelectModel. In practice, use RuntimeRecyclerViewHelper;
     * this interface exists only for mocking in tests.
     */
    interface BandModelHelper {
        void addOnScrollListener(RecyclerView.OnScrollListener listener);
        Point createAbsolutePoint(Point relativePoint);
        Rect getAbsoluteRectForChildViewAt(int index);
        int getAdapterPositionAt(int index);
        int getNumColumns();
        int getNumRows();
        int getTotalChildCount();
        int getVisibleChildCount();
        void removeOnScrollListener(RecyclerView.OnScrollListener listener);
    }

    /**
     * Concrete RecyclerViewHelper implementation for use within the Files app.
     */
    private static final class RuntimeRecyclerViewHelper implements MultiSelectHelper,
            BandManagerHelper, BandModelHelper {

        private final RecyclerView mRecyclerView;
        private final Drawable mBandSelectRect;

        private boolean mIsOverlayShown = false;

        RuntimeRecyclerViewHelper(RecyclerView rv) {
            mRecyclerView = rv;
            mBandSelectRect = mRecyclerView.getContext().getTheme().getDrawable(
                    R.drawable.band_select_overlay);
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

        @Override
        public int findEventPosition(MotionEvent e) {
            View view = mRecyclerView.findChildViewUnder(e.getX(), e.getY());
            return view != null
                    ? mRecyclerView.getChildAdapterPosition(view)
                    : RecyclerView.NO_POSITION;
        }

        @Override
        public int getHeight() {
            return mRecyclerView.getHeight();
        }

        @Override
        public void invalidateView() {
            mRecyclerView.invalidate();
        }

        @Override
        public void postRunnable(Runnable r) {
            mRecyclerView.postOnAnimation(r);
        }

        @Override
        public void removeCallback(Runnable r) {
            mRecyclerView.removeCallbacks(r);
        }

        @Override
        public void scrollBy(int dy) {
            mRecyclerView.scrollBy(0, dy);
        }

        @Override
        public void drawBand(Rect rect) {
            mBandSelectRect.setBounds(rect);

            if (!mIsOverlayShown) {
                mRecyclerView.getOverlay().add(mBandSelectRect);
            }
        }

        @Override
        public void hideBand() {
            mRecyclerView.getOverlay().remove(mBandSelectRect);
        }
    }

    public interface Callback {
        /**
         * Called when an item is selected or unselected while in selection mode.
         *
         * @param position Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is now selected, <code>false</code>
         *                if the item is now unselected.
         */
        public void onItemStateChanged(int position, boolean selected);

        /**
         * Called prior to an item changing state. Callbacks can cancel
         * the change at {@code position} by returning {@code false}.
         *
         * @param position Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is to be selected, <code>false</code>
         *                if the item is to be unselected.
         */
        public boolean onBeforeItemStateChange(int position, boolean selected);

        /**
         * Called immediately after completion of any set of changes.
         */
        public void onSelectionChanged();
    }

    /**
     * A composite {@code OnGestureDetector} that allows us to delegate unhandled
     * events to an outside party (presumably DirectoryFragment).
     * @template A gestureDelegate that implements both {@link OnGestureListener}
     *     and {@link OnDoubleTapListener}
     */
    private static final class
            CompositeOnGestureListener<L extends OnGestureListener & OnDoubleTapListener>
            implements OnGestureListener, OnDoubleTapListener {

        private L[] mListeners;

        @SafeVarargs
        public CompositeOnGestureListener(L... listeners) {
            mListeners = listeners;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onDown(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onShowPress(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                mListeners[i].onShowPress(e);
            }
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onSingleTapUp(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onScroll(e1, e2, distanceX, distanceY)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                mListeners[i].onLongPress(e);
            }
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onFling(e1, e2, velocityX, velocityY)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onSingleTapConfirmed(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onDoubleTap(e)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            for (int i = 0; i < mListeners.length; i++) {
                if (mListeners[i].onDoubleTapEvent(e)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Provides mouse driven band-select support when used in conjunction with {@link RecyclerView}
     * and {@link MultiSelectManager}. This class is responsible for rendering the band select
     * overlay and selecting overlaid items via MultiSelectManager.
     */
    public class BandSelectManager implements BandSelectModel.OnSelectionChangedListener {

        private static final int NOT_SET = -1;

        private final BandManagerHelper mHelper;

        private boolean mIsActive;
        private Point mOrigin;
        private Point mPointer;
        private Rect mBounds;
        private BandSelectModel mModel;

        // The time at which the current band selection-induced scroll began. If no scroll is in
        // progress, the value is NOT_SET.
        private long mScrollStartTime = NOT_SET;
        private final Runnable mViewScroller = new ViewScroller();

        public <T extends BandManagerHelper & BandModelHelper>
                BandSelectManager(T helper) {
            mHelper = helper;
            mModel = new BandSelectModel(helper);
            mModel.addOnSelectionChangedListener(this);
        }

        /**
         * Processes a MotionEvent by starting, ending, or resizing the band select overlay.
         * @param e
         */
        private void processMotionEvent(MotionEvent e) {
            if (!isMouseEvent(e)) {
                return;
            }

            if (mIsActive && e.getActionMasked() == MotionEvent.ACTION_UP) {
                endBandSelect();
                return;
            }

            mPointer = new Point((int) e.getX(), (int) e.getY());
            if (!mIsActive) {
                // Only start a band select if the pointer is in margins between items, not
                // actually within an item's bounds.
                if (mHelper.findEventPosition(e) != RecyclerView.NO_POSITION) {
                    return;
                }
                startBandSelect();
            } else {
                mModel.resizeSelection(mPointer);
            }

            scrollViewIfNecessary();
            resizeBandSelectRectangle();
        }

        /**
         * Starts band select by adding the drawable to the RecyclerView's overlay.
         */
        private void startBandSelect() {
            if (DEBUG) {
                Log.d(TAG, "Starting band select from (" + mPointer.x + "," + mPointer.y + ").");
            }
            mIsActive = true;
            mOrigin = new Point(mPointer.x, mPointer.y);
            mModel.startSelection(mOrigin);
        }

        /**
         * Scrolls the view if necessary.
         */
        private void scrollViewIfNecessary() {
            mHelper.removeCallback(mViewScroller);
            mViewScroller.run();
            mHelper.invalidateView();
        }

        /**
         * Resizes the band select rectangle by using the origin and the current pointer position as
         * two opposite corners of the selection.
         */
        private void resizeBandSelectRectangle() {
            mBounds = new Rect(Math.min(mOrigin.x, mPointer.x),
                    Math.min(mOrigin.y, mPointer.y),
                    Math.max(mOrigin.x, mPointer.x),
                    Math.max(mOrigin.y, mPointer.y));
            mHelper.drawBand(mBounds);
        }

        /**
         * Ends band select by removing the overlay.
         */
        private void endBandSelect() {
            if (DEBUG) Log.d(TAG, "Ending band select.");
            mIsActive = false;
            mHelper.hideBand();
            mSelection.applyProvisionalSelection();
            mModel.endSelection();
            int firstSelected = mModel.getPositionNearestOrigin();
            if (firstSelected != BandSelectModel.NOT_SET) {
                setSelectionFocusBegin(firstSelected);
            }
        }

        @Override
        public void onSelectionChanged(SparseBooleanArray updatedSelection) {
            SparseBooleanArray delta = mSelection.setProvisionalSelection(updatedSelection);
            for (int i = 0; i < delta.size(); i++) {
                int position = delta.keyAt(i);
                notifyItemStateChanged(position, delta.get(position));
            }
            notifySelectionChanged();
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
                if (mPointer.y <= 0) {
                    pixelsPastView = mPointer.y - 1;
                } else if (mPointer.y >= mHelper.getHeight() - 1) {
                    pixelsPastView = mPointer.y - mHelper.getHeight() + 1;
                }

                if (!mIsActive || pixelsPastView == 0) {
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
                mHelper.scrollBy(numPixels);

                // Adjust the y-coordinate of the origin the opposite number of pixels so that the
                // origin remains in the same place relative to the view's items.
                mOrigin.y -= numPixels;
                resizeBandSelectRectangle();

                mHelper.removeCallback(mViewScroller);
                mHelper.postRunnable(this);
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
                final int maxScrollStep = mHelper.getHeight();
                final int direction = (int) Math.signum(pixelsPastView);
                final int absPastView = Math.abs(pixelsPastView);

                // Calculate the ratio of how far out of the view the pointer currently resides to
                // the entire height of the view.
                final float outOfBoundsRatio = Math.min(
                        1.0f, (float) absPastView / mHelper.getHeight());
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
    }

    /**
     * Provides a band selection item model for views within a RecyclerView. This class queries the
     * RecyclerView to determine where its items are placed; then, once band selection is underway,
     * it alerts listeners of which items are covered by the selections.
     */
    public static final class BandSelectModel extends RecyclerView.OnScrollListener {

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

        private final BandModelHelper mHelper;
        private final List<OnSelectionChangedListener> mOnSelectionChangedListeners = new ArrayList<>();

        // Map from the x-value of the left side of a SparseBooleanArray of adapter positions, keyed
        // by their y-offset. For example, if the first column of the view starts at an x-value of 5,
        // mColumns.get(5) would return an array of positions in that column. Within that array, the
        // value for key y is the adapter position for the item whose y-offset is y.
        private final SparseArray<SparseIntArray> mColumns = new SparseArray<>();

        // List of limits along the x-axis. For example, if the view has two columns, this list will
        // have two elements, each of which lists the lower- and upper-limits of the x-values of the
        // view items. This list is sorted from furthest left to furthest right.
        private final List<Limits> mXLimitsList = new ArrayList<>();

        // Like mXLimitsList, but for y-coordinates. Note that this list only contains items which
        // have been in the viewport. Thus, limits which exist in an area of the view to which the
        // view has not scrolled are not present in the list.
        private final List<Limits> mYLimitsList = new ArrayList<>();

        // The adapter positions which have been recorded so far.
        private final SparseBooleanArray mKnownPositions = new SparseBooleanArray();

        // Array passed to registered OnSelectionChangedListeners. One array is created and reused
        // throughout the lifetime of the object.
        private final SparseBooleanArray mSelection = new SparseBooleanArray();

        // The current pointer (in absolute positioning from the top of the view).
        private Point mPointer = null;

        // The bounds of the band selection.
        private RelativePoint mRelativeOrigin;
        private RelativePoint mRelativePointer;

        private boolean mIsActive;

        // Tracks where the band select originated from. This is used to determine where selections
        // should expand from when Shift+click is used.
        private int mPositionNearestOrigin = NOT_SET;

        BandSelectModel(BandModelHelper helper) {
            mHelper = helper;
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
            mIsActive = true;
            mPointer = mHelper.createAbsolutePoint(relativeOrigin);

            recordVisibleChildren();
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
                if (!mKnownPositions.get(adapterPosition)) {
                    mKnownPositions.put(adapterPosition, true);
                    recordItemData(
                            mHelper.getAbsoluteRectForChildViewAt(i), adapterPosition);
                }
            }
        }

        /**
         * Updates the limits lists and column map with the given item metadata.
         * @param absoluteChildRect The absolute rectangle for the child view being processed.
         * @param adapterPosition The position of the child view being processed.
         */
        private void recordItemData(Rect absoluteChildRect, int adapterPosition) {
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
        private void recordLimits(List<Limits> limitsList, Limits limits) {
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
            if (areItemsCoveredBySelection(mRelativePointer, mRelativeOrigin)) {
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
            for (OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
                listener.onSelectionChanged(mSelection);
            }
        }

        /**
         * @param rect Rectangle including all covered items.
         */
        private void updateSelection(Rect rect) {
            int columnStartIndex =
                    Collections.binarySearch(mXLimitsList, new Limits(rect.left, rect.left));
            checkState(columnStartIndex >= 0);
            int columnEndIndex = columnStartIndex;

            for (int i = columnStartIndex;
                    i < mXLimitsList.size() && mXLimitsList.get(i).lowerLimit <= rect.right; i++) {
                columnEndIndex = i;
            }

            SparseIntArray firstColumn =
                    mColumns.get(mXLimitsList.get(columnStartIndex).lowerLimit);
            int rowStartIndex = firstColumn.indexOfKey(rect.top);
            if (rowStartIndex < 0) {
                mPositionNearestOrigin = NOT_SET;
                return;
            }

            int rowEndIndex = rowStartIndex;
            for (int i = rowStartIndex;
                    i < firstColumn.size() && firstColumn.keyAt(i) <= rect.bottom; i++) {
                rowEndIndex = i;
            }

            updateSelection(columnStartIndex, columnEndIndex, rowStartIndex, rowEndIndex);
        }

        /**
         * Computes the selection given the previously-computed start- and end-indices for each
         * row and column.
         */
        private void updateSelection(
                int columnStartIndex, int columnEndIndex, int rowStartIndex, int rowEndIndex) {
            mSelection.clear();
            for (int column = columnStartIndex; column <= columnEndIndex; column++) {
                SparseIntArray items = mColumns.get(mXLimitsList.get(column).lowerLimit);
                for (int row = rowStartIndex; row <= rowEndIndex; row++) {
                    int position = items.get(items.keyAt(row));
                    mSelection.append(position, true);
                    if (isPossiblePositionNearestOrigin(column, columnStartIndex, columnEndIndex,
                            row, rowStartIndex, rowEndIndex)) {
                        // If this is the position nearest the origin, record it now so that it
                        // can be returned by endSelection() later.
                        mPositionNearestOrigin = position;
                    }
                }
            }
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
            public void onSelectionChanged(SparseBooleanArray updatedSelection);
        }

        void addOnSelectionChangedListener(OnSelectionChangedListener listener) {
            mOnSelectionChangedListeners.add(listener);
        }

        void removeOnSelectionChangedListener(OnSelectionChangedListener listener) {
            mOnSelectionChangedListeners.remove(listener);
        }

        /**
         * Limits of a view item. For example, if an item's left side is at x-value 5 and its right side
         * is at x-value 10, the limits would be from 5 to 10. Used to record the left- and right sides
         * of item columns and the top- and bottom sides of item rows so that it can be determined
         * whether the pointer is located within the bounds of an item.
         */
        private class Limits implements Comparable<Limits> {
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
        private class RelativeCoordinate
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
        }

        /**
         * Generates a rectangle which contains the items selected by the pointer and origin.
         * @return The rectangle, or null if no items were selected.
         */
        private Rect computeBounds() {
            Rect rect = new Rect();
            rect.left = getCoordinateValue(
                    min(mRelativeOrigin.xLocation, mRelativePointer.xLocation),
                    mXLimitsList,
                    true);
            rect.right = getCoordinateValue(
                    max(mRelativeOrigin.xLocation, mRelativePointer.xLocation),
                    mXLimitsList,
                    false);
            rect.top = getCoordinateValue(
                    min(mRelativeOrigin.yLocation, mRelativePointer.yLocation),
                    mYLimitsList,
                    true);
            rect.bottom = getCoordinateValue(
                    max(mRelativeOrigin.yLocation, mRelativePointer.yLocation),
                    mYLimitsList,
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

        private RelativeCoordinate min(RelativeCoordinate first, RelativeCoordinate second) {
            return first.compareTo(second) < 0 ? first : second;
        }

        private RelativeCoordinate max(RelativeCoordinate first, RelativeCoordinate second) {
            return first.compareTo(second) > 0 ? first : second;
        }

        /**
         * @return The absolute coordinate (i.e., the x- or y-value) of the given relative
         *     coordinate.
         */
        private int getCoordinateValue(RelativeCoordinate coordinate,
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

        private boolean areItemsCoveredBySelection(
                RelativePoint first, RelativePoint second) {
            return doesCoordinateLocationCoverItems(first.xLocation, second.xLocation) &&
                    doesCoordinateLocationCoverItems(first.yLocation, second.yLocation);
        }

        private boolean doesCoordinateLocationCoverItems(
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
                    pointerCoordinate.limitsBeforeCoordinate.equals(
                            originCoordinate.limitsBeforeCoordinate) &&
                    pointerCoordinate.limitsAfterCoordinate.equals(
                            originCoordinate.limitsAfterCoordinate)) {
                return false;
            }

            return true;
        }
    }
}
