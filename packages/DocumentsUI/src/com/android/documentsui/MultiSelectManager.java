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

import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.Adapter;
import android.support.v7.widget.RecyclerView.AdapterDataObserver;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.View;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * MultiSelectManager adds traditional multi-item selection support to RecyclerView.
 */
public final class MultiSelectManager {

    private static final String TAG = "MultiSelectManager";
    private static final boolean DEBUG = false;

    private final Selection mSelection = new Selection();
    // Only created when selection is cleared.
    private Selection mIntermediateSelection;

    private final List<MultiSelectManager.Callback> mCallbacks = new ArrayList<>(1);

    private Adapter<?> mAdapter;
    private RecyclerViewHelper mHelper;

    /**
     * @param recyclerView
     * @param gestureDelegate Option delage gesture listener.
     */
    public MultiSelectManager(final RecyclerView recyclerView, OnGestureListener gestureDelegate) {
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
                });

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

        final GestureDetector detector = new GestureDetector(
                recyclerView.getContext(),
                gestureDelegate == null
                        ? listener
                        : new CompositeOnGestureListener(listener, gestureDelegate));

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

    MultiSelectManager(Adapter<?> adapter, RecyclerViewHelper helper) {
        if (adapter == null) {
            throw new IllegalArgumentException("Adapter cannot be null.");
        }
        if (helper == null) {
            throw new IllegalArgumentException("Helper cannot be null.");
        }

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

    public void selectItem(int position) {
        selectItems(position, 1);
    }

    public void selectItems(int position, int length) {
        for (int i = position; i < position + length; i++) {
            mSelection.add(i);
        }
    }

    public void clearSelection() {
        if (DEBUG) Log.d(TAG, "Clearing selection");
        if (mIntermediateSelection == null) {
            mIntermediateSelection = new Selection();
        }
        getSelection(mIntermediateSelection);
        mSelection.clear();

        for (int i = 0; i < mIntermediateSelection.size(); i++) {
            int position = mIntermediateSelection.get(i);
            mAdapter.notifyItemChanged(position);
            notifyItemStateChanged(position, false);
        }
    }

    public boolean onSingleTapUp(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling tap event.");
        if (mSelection.size() == 0) {
            return false;
        }

        return onSingleTapUp(mHelper.findEventPosition(e));
    }

    /**
     * @param position
     * @hide
     */
    @VisibleForTesting
    boolean onSingleTapUp(int position) {
        if (mSelection.size() == 0) {
            return false;
        }

        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
            return false;
        }

        toggleSelection(position);
        return true;
    }

    public void onLongPress(MotionEvent e) {
        if (DEBUG) Log.d(TAG, "Handling long press event.");

        int position = mHelper.findEventPosition(e);
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        toggleSelection(position);
    }

    /**
     * @param position
     * @hide
     */
    @VisibleForTesting
    void onLongPress(int position) {
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.i(TAG, "View is null. Cannot handle tap event.");
        }

        toggleSelection(position);
    }

    private void toggleSelection(int position) {
        // Position may be special "no position" during certain
        // transitional phases. If so, skip handling of the event.
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "Ignoring toggle for element with no position.");
            return;
        }

        if (DEBUG) Log.d(TAG, "Handling long press on view: " + position);
        boolean nextState = !mSelection.contains(position);
        if (notifyBeforeItemStateChange(position, nextState)) {
            boolean selected = mSelection.flip(position);
            notifyItemStateChanged(position, selected);
            mAdapter.notifyItemChanged(position);
            if (DEBUG) Log.d(TAG, "Selection after long press: " + mSelection);
        } else {
            Log.i(TAG, "Selection change cancelled by listener.");
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
     * Notifies registered listeners when a selection changes.
     *
     * @param position
     * @param selected
     */
    private void notifyItemStateChanged(int position, boolean selected) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onItemStateChanged(position, selected);
        }
    }

    /**
     * Object representing the current selection.
     */
    // NOTE: Much of the code in this class was copious swiped from
    // ArrayUtils, GrowingArrayUtils, and SparseBooleanArray.
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
        void add(int position) {
            mSelection.put(position, true);
        }

        /** @hide */
        @VisibleForTesting
        void remove(int position) {
            mSelection.delete(position);
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
            if (startPosition < 0) {
                throw new IllegalArgumentException("startPosition must be non-negative");
            }
            if (count < 1) {
                throw new IllegalArgumentException("countMust be greater than 0");
            }

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
            if (startPosition < 0) {
                throw new IllegalArgumentException("startPosition must be non-negative");
            }
            if (count < 1) {
                throw new IllegalArgumentException("countMust be greater than 0");
            }

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
            buffer.append(String.format("{size=%d, ", mSelection.size()));
            buffer.append("items=[");
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
         * @param position
         * @param selected
         * @return false to cancel the change.
         */
        public boolean onBeforeItemStateChange(int position, boolean selected);
    }

    /**
     * A composite {@code OnGestureDetector} that allows us to delegate unhandled
     * events to other interested parties.
     */
    private static final class CompositeOnGestureListener implements OnGestureListener {

        private OnGestureListener[] mListeners;

        public CompositeOnGestureListener(OnGestureListener... listeners) {
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
    }
}
