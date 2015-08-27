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

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.Adapter;
import android.support.v7.widget.RecyclerView.AdapterDataObserver;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.View;

import android.support.annotation.VisibleForTesting;

import java.util.ArrayList;
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
    private RecyclerViewHelper mHelper;
    private boolean mSingleSelect;

    /**
     * @param recyclerView
     * @param gestureDelegate Option delage gesture listener.
     * @param mode Selection mode
     * @template A gestureDelegate that implements both {@link OnGestureListener}
     *     and {@link OnDoubleTapListener}
     */
    public <L extends OnGestureListener & OnDoubleTapListener> MultiSelectManager(
            final RecyclerView recyclerView, L gestureDelegate, int mode) {

        this(
                recyclerView.getAdapter(),
                new RecyclerViewHelper() {
                    @Override
                    public int findEventPosition(MotionEvent e) {
                        View view = recyclerView.findChildViewUnder(e.getX(), e.getY());
                        return view != null
                                ? recyclerView.getChildAdapterPosition(view)
                                : RecyclerView.NO_POSITION;
                    }
                },
                mode);

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
        final GestureDetector detector = new GestureDetector(
                recyclerView.getContext(),
                gestureDelegate == null
                        ? listener
                        : compositeListener);

        detector.setOnDoubleTapListener(compositeListener);

        recyclerView.addOnItemTouchListener(
                new RecyclerView.OnItemTouchListener() {
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        detector.onTouchEvent(e);
                        return false;
                    }
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {}
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });
    }

    /**
     * Constructs a new instance with {@code adapter} and {@code helper}.
     * @hide
     */
    @VisibleForTesting
    MultiSelectManager(Adapter<?> adapter, RecyclerViewHelper helper, int mode) {
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
     * on the current selection.
     *
     * @see #getSelection(Selection) on how to get a snapshot
     *     of the selection that will not reflect future changes
     *     to selection.
     *
     * @return The current selection.
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

        onLongPress(position);
    }

    /**
     * TODO: Roll this back into {@link #onLongPress(MotionEvent)} once MotionEvent
     * can be mocked.
     *
     * @param position
     * @hide
     */
    @VisibleForTesting
    void onLongPress(int position) {
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        if (toggleSelection(position)) {
            notifySelectionChanged();
        }
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

        if (Events.hasShiftBit(metaState) && mRanger != null) {
            mRanger.snapSelection(position);
        } else {
            toggleSelection(position);
        }

        // We're being lazy here notifying even when something might not have changed.
        // To make this more correct, we'd need to update the Ranger class to return
        // information about what has changed.
        notifySelectionChanged();
        return false;
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

        private SparseBooleanArray mSelection;

        public Selection() {
            mSelection = new SparseBooleanArray();
        }

        /**
         * @param position
         * @return true if the position is currently selected.
         */
        public boolean contains(int position) {
            return mSelection.get(position);
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
            return mSelection.keyAt(index);
        }

        /**
         * @return size of the selection.
         */
        public int size() {
            return mSelection.size();
        }

        /**
         * @return true if the selection is empty.
         */
        public boolean isEmpty() {
            return mSelection.size() == 0;
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
            if (!mSelection.get(position)) {
                mSelection.put(position, true);
                return true;
            }
            return false;
        }

        /** @hide */
        @VisibleForTesting
        boolean remove(int position) {
            if (mSelection.get(position)) {
                mSelection.delete(position);
                return true;
            }
            return false;
        }

        /**
         * Adjusts the selection range to reflect the existence of newly inserted values at
         * the specified positions. This has the effect of adjusting all existing selected
         * positions within the specified range accordingly.
         *
         * @param startPosition
         * @param count
         * @hide
         */
        @VisibleForTesting
        void expand(int startPosition, int count) {
            checkState(startPosition >= 0);
            checkState(count > 0);

            for (int i = 0; i < mSelection.size(); i++) {
                int itemPosition = mSelection.keyAt(i);
                if (itemPosition >= startPosition) {
                    mSelection.setKeyAt(i, itemPosition + count);
                }
            }
        }

        /**
         * Adjusts the selection range to reflect the removal specified positions. This has
         * the effect of adjusting all existing selected positions within the specified range
         * accordingly.
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
            for (int i = 0; i < mSelection.size(); i++) {
                int itemPosition = mSelection.keyAt(i);
                if (itemPosition < startPosition) {
                    newSelection.append(itemPosition, true);
                } else if (itemPosition > endPosition) {
                    newSelection.append(itemPosition - count, true);
                }
            }
            mSelection = newSelection;
        }

        /** @hide */
        @VisibleForTesting
        void clear() {
            mSelection.clear();
        }

        /** @hide */
        @VisibleForTesting
        void copyFrom(Selection source) {
            mSelection = source.mSelection.clone();
        }

        @Override
        public String toString() {
            if (size() <= 0) {
                return "size=0, items=[]";
            }

            StringBuilder buffer = new StringBuilder(mSelection.size() * 28);
            buffer.append("{size=")
                    .append(mSelection.size())
                    .append(", ")
                    .append("items=[");
            for (int i=0; i < mSelection.size(); i++) {
                if (i > 0) {
                    buffer.append(", ");
                }
                buffer.append(mSelection.keyAt(i));
            }
            buffer.append("]}");
            return buffer.toString();
        }

        @Override
        public int hashCode() {
            return mSelection.hashCode();
        }

        @Override
        public boolean equals(Object that) {
          if (this == that) {
              return true;
          }

          if (!(that instanceof Selection)) {
              return false;
          }

          return mSelection.equals(((Selection) that).mSelection);
        }
    }

    interface RecyclerViewHelper {
        int findEventPosition(MotionEvent e);
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
}
