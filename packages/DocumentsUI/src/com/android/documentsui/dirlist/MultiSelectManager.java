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

import android.annotation.IntDef;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
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

import com.android.documentsui.Events.InputEvent;
import com.android.documentsui.Events.MotionInputEvent;
import com.android.documentsui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MultiSelectManager provides support traditional multi-item selection support to RecyclerView.
 * Additionally it can be configured to restrict selection to a single element, @see
 * #setSelectMode.
 */
public final class MultiSelectManager {

    @IntDef(flag = true, value = {
            MODE_MULTIPLE,
            MODE_SINGLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SelectionMode {}
    public static final int MODE_MULTIPLE = 0;
    public static final int MODE_SINGLE = 1;

    private static final String TAG = "MultiSelectManager";

    private final Selection mSelection = new Selection();

    private final SelectionEnvironment mEnvironment;
    private final DocumentsAdapter mAdapter;
    private final List<MultiSelectManager.Callback> mCallbacks = new ArrayList<>(1);

    private Range mRanger;
    private boolean mSingleSelect;

    @Nullable private BandController mBandManager;


    /**
     * @param mode Selection single or multiple selection mode.
     * @param initialSelection selection state probably preserved in external state.
     */
    public MultiSelectManager(
            final RecyclerView recyclerView,
            DocumentsAdapter adapter,
            @SelectionMode int mode,
            @Nullable Selection initialSelection) {

        this(new RuntimeSelectionEnvironment(recyclerView), adapter, mode, initialSelection);

        if (mode == MODE_MULTIPLE) {
            // TODO: Don't load this on low memory devices.
            mBandManager = new BandController();
        }

        recyclerView.addOnItemTouchListener(
                new RecyclerView.OnItemTouchListener() {
                    @Override
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        if (mBandManager != null) {
                            return mBandManager.handleEvent(new MotionInputEvent(e, recyclerView));
                        }
                        return false;
                    }

                    @Override
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                        mBandManager.processInputEvent(
                                new MotionInputEvent(e, recyclerView));
                    }
                    @Override
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });
    }

    /**
     * Constructs a new instance with {@code adapter} and {@code helper}.
     * @param runtimeSelectionEnvironment
     * @hide
     */
    @VisibleForTesting
    MultiSelectManager(
            SelectionEnvironment environment,
            DocumentsAdapter adapter,
            @SelectionMode int mode,
            @Nullable Selection initialSelection) {

        assert(environment != null);
        assert(adapter != null);

        mEnvironment = environment;
        mAdapter = adapter;

        mSingleSelect = mode == MODE_SINGLE;
        if (initialSelection != null) {
            mSelection.copyFrom(initialSelection);
        }

        mAdapter.registerAdapterDataObserver(
                new RecyclerView.AdapterDataObserver() {

                    private List<String> mModelIds;

                    @Override
                    public void onChanged() {
                        mModelIds = mAdapter.getModelIds();

                        // Update the selection to remove any disappeared IDs.
                        mSelection.cancelProvisionalSelection();
                        mSelection.intersect(mModelIds);

                        if (mBandManager != null && mBandManager.isActive()) {
                            mBandManager.endBandSelect();
                        }
                    }

                    @Override
                    public void onItemRangeChanged(
                            int startPosition, int itemCount, Object payload) {
                        // No change in position. Ignoring.
                    }

                    @Override
                    public void onItemRangeInserted(int startPosition, int itemCount) {
                        mSelection.cancelProvisionalSelection();
                    }

                    @Override
                    public void onItemRangeRemoved(int startPosition, int itemCount) {
                        assert(startPosition >= 0);
                        assert(itemCount > 0);

                        mSelection.cancelProvisionalSelection();
                        // Remove any disappeared IDs from the selection.
                        mSelection.intersect(mModelIds);
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

    public boolean hasSelection() {
        return !mSelection.isEmpty();
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
     * Updates selection to include items in {@code selection}.
     */
    public void updateSelection(Selection selection) {
        setItemsSelected(selection.toList(), true);
    }

    /**
     * Sets the selected state of the specified items. Note that the callback will NOT
     * be consulted to see if an item can be selected.
     *
     * @param ids
     * @param selected
     * @return
     */
    public boolean setItemsSelected(Iterable<String> ids, boolean selected) {
        boolean changed = false;
        for (String id: ids) {
            boolean itemChanged = selected ? mSelection.add(id) : mSelection.remove(id);
            if (itemChanged) {
                notifyItemStateChanged(id, selected);
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

    public void handleLayoutChanged() {
        if (mBandManager != null) {
            mBandManager.handleLayoutChanged();
        }
    }

    /**
     * Clears the selection, without notifying selection listeners. UI elements still need to be
     * notified about state changes so that they can update their appearance.
     */
    private void clearSelectionQuietly() {
        mRanger = null;

        if (!hasSelection()) {
            return;
        }

        Selection oldSelection = getSelection(new Selection());
        mSelection.clear();

        for (String id: oldSelection.getAll()) {
            notifyItemStateChanged(id, false);
        }
    }

    @VisibleForTesting
    void onLongPress(InputEvent input) {
        if (DEBUG) Log.d(TAG, "Handling long press event.");

        if (!input.isOverItem()) {
            if (DEBUG) Log.i(TAG, "Cannot handle tap. No adapter position available.");
        }

        handleAdapterEvent(input);
    }

    @VisibleForTesting
    boolean onSingleTapUp(InputEvent input) {
        if (DEBUG) Log.d(TAG, "Processing tap event.");
        if (!hasSelection()) {
            // No selection active - do nothing.
            return false;
        }

        if (!input.isOverItem()) {
            if (DEBUG) Log.d(TAG, "Activity has no position. Canceling selection.");
            clearSelection();
            return false;
        }

        handleAdapterEvent(input);
        return true;
    }

    /**
     * Handles a change caused by a click on the item with the given position. If the Shift key is
     * held down, this performs a range select; otherwise, it simply toggles the item's selection
     * state.
     */
    private void handleAdapterEvent(InputEvent input) {
        if (mRanger != null && input.isShiftKeyDown()) {
            mRanger.snapSelection(input.getItemPosition());

            // We're being lazy here notifying even when something might not have changed.
            // To make this more correct, we'd need to update the Ranger class to return
            // information about what has changed.
            notifySelectionChanged();
        } else {
            int position = input.getItemPosition();
            toggleSelection(position);
            setSelectionRangeBegin(position);
        }
    }

    /**
     * A convenience method for toggling selection by adapter position.
     *
     * @param position Adapter position to toggle.
     */
    private void toggleSelection(int position) {
        // Position may be special "no position" during certain
        // transitional phases. If so, skip handling of the event.
        if (position == RecyclerView.NO_POSITION) {
            if (DEBUG) Log.d(TAG, "Ignoring toggle for element with no position.");
            return;
        }
        String id = mAdapter.getModelId(position);
        if (id != null) {
            toggleSelection(id);
        }
    }

    /**
     * Toggles selection on the item with the given model ID.
     *
     * @param modelId
     */
    public void toggleSelection(String modelId) {
        assert(modelId != null);

        boolean changed = false;
        if (mSelection.contains(modelId)) {
            changed = attemptDeselect(modelId);
        } else {
            changed = attemptSelect(modelId);
        }

        if (changed) {
            notifySelectionChanged();
        }
    }

    /**
     * Starts a range selection. If a range selection is already active, this will start a new range
     * selection (which will reset the range anchor).
     *
     * @param pos The anchor position for the selection range.
     */
    void startRangeSelection(int pos) {
        attemptSelect(mAdapter.getModelId(pos));
        setSelectionRangeBegin(pos);
    }

    /**
     * Sets the end point for the current range selection, started by a call to
     * {@link #startRangeSelection(int)}. This function should only be called when a range selection
     * is active (see {@link #isRangeSelectionActive()}. Items in the range [anchor, end] will be
     * selected.
     *
     * @param pos The new end position for the selection range.
     */
    void snapRangeSelection(int pos) {
        assert(mRanger != null);

        mRanger.snapSelection(pos);
        notifySelectionChanged();
    }

    /**
     * Stops an in-progress range selection.
     */
    void endRangeSelection() {
        mRanger = null;
    }

    /**
     * @return Whether or not there is a current range selection active.
     */
    boolean isRangeSelectionActive() {
        return mRanger != null;
    }

    /**
     * Sets the magic location at which a selection range begins (the selection anchor). This value
     * is consulted when determining how to extend, and modify selection ranges. Calling this when a
     * range selection is active will reset the range selection.
     *
     * @throws IllegalStateException if {@code position} is not already be selected
     * @param position
     */
    void setSelectionRangeBegin(int position) {
        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        if (mSelection.contains(mAdapter.getModelId(position))) {
            mRanger = new Range(position);
        }
    }

    /**
     * Try to set selection state for all elements in range. Not that callbacks can cancel selection
     * of specific items, so some or even all items may not reflect the desired state after the
     * update is complete.
     *
     * @param begin Adapter position for range start (inclusive).
     * @param end Adapter position for range end (inclusive).
     * @param selected New selection state.
     */
    private void updateRange(int begin, int end, boolean selected) {
        assert(end >= begin);
        for (int i = begin; i <= end; i++) {
            String id = mAdapter.getModelId(i);
            if (id == null) {
                continue;
            }

            if (selected) {
                boolean canSelect = notifyBeforeItemStateChange(id, true);
                if (canSelect) {
                    if (mSingleSelect && hasSelection()) {
                        clearSelectionQuietly();
                    }
                    selectAndNotify(id);
                }
            } else {
                attemptDeselect(id);
            }
        }
    }

    /**
     * @param modelId
     * @return True if the update was applied.
     */
    private boolean selectAndNotify(String modelId) {
        boolean changed = mSelection.add(modelId);
        if (changed) {
            notifyItemStateChanged(modelId, true);
        }
        return changed;
    }

    /**
     * @param id
     * @return True if the update was applied.
     */
    private boolean attemptDeselect(String id) {
        assert(id != null);
        if (notifyBeforeItemStateChange(id, false)) {
            mSelection.remove(id);
            notifyItemStateChanged(id, false);
            if (DEBUG) Log.d(TAG, "Selection after deselect: " + mSelection);
            return true;
        } else {
            if (DEBUG) Log.d(TAG, "Select cancelled by listener.");
            return false;
        }
    }

    /**
     * @param id
     * @return True if the update was applied.
     */
    private boolean attemptSelect(String id) {
        assert(id != null);
        boolean canSelect = notifyBeforeItemStateChange(id, true);
        if (!canSelect) {
            return false;
        }
        if (mSingleSelect && hasSelection()) {
            clearSelectionQuietly();
        }

        selectAndNotify(id);
        return true;
    }

    private boolean notifyBeforeItemStateChange(String id, boolean nextState) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            if (!mCallbacks.get(i).onBeforeItemStateChange(id, nextState)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notifies registered listeners when the selection status of a single item
     * (identified by {@code position}) changes.
     */
    private void notifyItemStateChanged(String id, boolean selected) {
        assert(id != null);
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onItemStateChanged(id, selected);
        }
        mAdapter.onItemSelectionChanged(id);
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
            assert(mRanger != null);
            assert(position != RecyclerView.NO_POSITION);

            if (mEnd == UNDEFINED || mEnd == mBegin) {
                // Reset mEnd so it can be established in establishRange.
                mEnd = UNDEFINED;
                establishRange(position);
            } else {
                reviseRange(position);
            }
        }

        private void establishRange(int position) {
            assert(mRanger.mEnd == UNDEFINED);

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
            assert(mEnd != UNDEFINED);
            assert(mBegin != mEnd);

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
    public static final class Selection implements Parcelable {

        // This class tracks selected items by managing two sets: the saved selection, and the total
        // selection. Saved selections are those which have been completed by tapping an item or by
        // completing a band select operation. Provisional selections are selections which have been
        // temporarily created by an in-progress band select operation (once the user releases the
        // mouse button during a band select operation, the selected items become saved). The total
        // selection is the combination of both the saved selection and the provisional
        // selection. Tracking both separately is necessary to ensure that saved selections do not
        // become deselected when they are removed from the provisional selection; for example, if
        // item A is tapped (and selected), then an in-progress band select covers A then uncovers
        // A, A should still be selected as it has been saved. To ensure this behavior, the saved
        // selection must be tracked separately.
        private final Set<String> mSelection;
        private final Set<String> mProvisionalSelection;
        private String mDirectoryKey;

        public Selection() {
            mSelection = new HashSet<String>();
            mProvisionalSelection = new HashSet<String>();
        }

        /**
         * Used by CREATOR.
         */
        private Selection(String directoryKey, Set<String> selection) {
            mDirectoryKey = directoryKey;
            mSelection = selection;
            mProvisionalSelection = new HashSet<String>();
        }

        /**
         * @param id
         * @return true if the position is currently selected.
         */
        public boolean contains(@Nullable String id) {
            return mSelection.contains(id) || mProvisionalSelection.contains(id);
        }

        /**
         * Returns an unordered array of selected positions.
         */
        public String[] getAll() {
            return toList().toArray(new String[0]);
        }

        /**
         * Returns an unordered array of selected positions (including any
         * provisional selections current in effect).
         */
        public List<String> toList() {
            ArrayList<String> selection = new ArrayList<String>(mSelection);
            selection.addAll(mProvisionalSelection);
            return selection;
        }

        /**
         * @return size of the selection.
         */
        public int size() {
            return mSelection.size() + mProvisionalSelection.size();
        }

        /**
         * @return true if the selection is empty.
         */
        public boolean isEmpty() {
            return mSelection.isEmpty() && mProvisionalSelection.isEmpty();
        }

        /**
         * Sets the provisional selection, which is a temporary selection that can be saved,
         * canceled, or adjusted at a later time. When a new provision selection is applied, the old
         * one (if it exists) is abandoned.
         * @return Map of ids added or removed. Added ids have a value of true, removed are false.
         */
        @VisibleForTesting
        protected Map<String, Boolean> setProvisionalSelection(Set<String> newSelection) {
            Map<String, Boolean> delta = new HashMap<>();

            for (String id: mProvisionalSelection) {
                // Mark each item that used to be in the selection but is unsaved and not in the new
                // provisional selection.
                if (!newSelection.contains(id) && !mSelection.contains(id)) {
                    delta.put(id, false);
                }
            }

            for (String id: mSelection) {
                // Mark each item that used to be in the selection but is unsaved and not in the new
                // provisional selection.
                if (!newSelection.contains(id)) {
                    delta.put(id, false);
                }
            }

            for (String id: newSelection) {
                // Mark each item that was not previously in the selection but is in the new
                // provisional selection.
                if (!mSelection.contains(id) && !mProvisionalSelection.contains(id)) {
                    delta.put(id, true);
                }
            }

            // Now, iterate through the changes and actually add/remove them to/from the current
            // selection. This could not be done in the previous loops because changing the size of
            // the selection mid-iteration changes iteration order erroneously.
            for (Map.Entry<String, Boolean> entry: delta.entrySet()) {
                String id = entry.getKey();
                if (entry.getValue()) {
                    mProvisionalSelection.add(id);
                } else {
                    mProvisionalSelection.remove(id);
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
            mSelection.addAll(mProvisionalSelection);
            mProvisionalSelection.clear();
        }

        /**
         * Abandons the existing provisional selection so that all items provisionally selected are
         * now deselected.
         */
        @VisibleForTesting
        void cancelProvisionalSelection() {
            mProvisionalSelection.clear();
        }

        /** @hide */
        @VisibleForTesting
        boolean add(String id) {
            if (!mSelection.contains(id)) {
                mSelection.add(id);
                return true;
            }
            return false;
        }

        /** @hide */
        @VisibleForTesting
        boolean remove(String id) {
            if (mSelection.contains(id)) {
                mSelection.remove(id);
                return true;
            }
            return false;
        }

        public void clear() {
            mSelection.clear();
        }

        /**
         * Trims this selection to be the intersection of itself with the set of given IDs.
         */
        public void intersect(Collection<String> ids) {
            mSelection.retainAll(ids);
            mProvisionalSelection.retainAll(ids);
        }

        @VisibleForTesting
        void copyFrom(Selection source) {
            mSelection.clear();
            mSelection.addAll(source.mSelection);

            mProvisionalSelection.clear();
            mProvisionalSelection.addAll(source.mProvisionalSelection);
        }

        @Override
        public String toString() {
            if (size() <= 0) {
                return "size=0, items=[]";
            }

            StringBuilder buffer = new StringBuilder(size() * 28);
            buffer.append("Selection{")
                .append("applied{size=" + mSelection.size())
                .append(", entries=" + mSelection)
                .append("}, provisional{size=" + mProvisionalSelection.size())
                .append(", entries=" + mProvisionalSelection)
                .append("}}");
            return buffer.toString();
        }

        @Override
        public int hashCode() {
            return mSelection.hashCode() ^ mProvisionalSelection.hashCode();
        }

        @Override
        public boolean equals(Object that) {
          if (this == that) {
              return true;
          }

          if (!(that instanceof Selection)) {
              return false;
          }

          return mSelection.equals(((Selection) that).mSelection) &&
                  mProvisionalSelection.equals(((Selection) that).mProvisionalSelection);
        }

        /**
         * Sets the state key for this selection, which allows us to match selections
         * to particular states (of DirectoryFragment). Basically this lets us avoid
         * loading a persisted selection in the wrong directory.
         */
        public void setDirectoryKey(String key) {
            mDirectoryKey = key;
        }

        /**
         * Sets the state key for this selection, which allows us to match selections
         * to particular states (of DirectoryFragment). Basically this lets us avoid
         * loading a persisted selection in the wrong directory.
         */
        public boolean hasDirectoryKey(String key) {
            return key.equals(mDirectoryKey);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mDirectoryKey);
            dest.writeStringList(new ArrayList<>(mSelection));
            // We don't include provisional selection since it is
            // typically coupled to some other runtime state (like a band).
        }

        public static final ClassLoaderCreator<Selection> CREATOR =
                new ClassLoaderCreator<Selection>() {
            @Override
            public Selection createFromParcel(Parcel in) {
                return createFromParcel(in, null);
            }

            @Override
            public Selection createFromParcel(Parcel in, ClassLoader loader) {
                String directoryKey = in.readString();

                ArrayList<String> selected = new ArrayList<>();
                in.readStringList(selected);

                return new Selection(directoryKey, new HashSet<String>(selected));
            }

            @Override
            public Selection[] newArray(int size) {
                return new Selection[size];
            }
        };
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

    public interface Callback {
        /**
         * Called when an item is selected or unselected while in selection mode.
         *
         * @param position Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is now selected, <code>false</code>
         *                if the item is now unselected.
         */
        public void onItemStateChanged(String id, boolean selected);

        /**
         * Called prior to an item changing state. Callbacks can cancel
         * the change at {@code position} by returning {@code false}.
         *
         * @param id Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is to be selected, <code>false</code>
         *                if the item is to be unselected.
         */
        public boolean onBeforeItemStateChange(String id, boolean selected);

        /**
         * Called immediately after completion of any set of changes.
         */
        public void onSelectionChanged();
    }

    /**
     * Provides mouse driven band-select support when used in conjunction with {@link RecyclerView}
     * and {@link MultiSelectManager}. This class is responsible for rendering the band select
     * overlay and selecting overlaid items via MultiSelectManager.
     */
    public class BandController extends RecyclerView.OnScrollListener
            implements GridModel.OnSelectionChangedListener {

        private static final int NOT_SET = -1;

        private final Runnable mModelBuilder;

        @Nullable private Rect mBounds;
        @Nullable private Point mCurrentPosition;
        @Nullable private Point mOrigin;
        @Nullable private GridModel mModel;

        // The time at which the current band selection-induced scroll began. If no scroll is in
        // progress, the value is NOT_SET.
        private long mScrollStartTime = NOT_SET;
        private final Runnable mViewScroller = new ViewScroller();

        public BandController() {
            mEnvironment.addOnScrollListener(this);

            mModelBuilder = new Runnable() {
                @Override
                public void run() {
                    mModel = new GridModel(mEnvironment, mAdapter);
                    mModel.addOnSelectionChangedListener(BandController.this);
                }
            };
        }

        public boolean handleEvent(MotionInputEvent e) {
            // b/23793622 notes the fact that we *never* receive ACTION_DOWN
            // events in onTouchEvent. Where it not for this issue, we'd
            // push start handling down into handleInputEvent.
            if (mBandManager.shouldStart(e)) {
                // endBandSelect is handled in handleInputEvent.
                mBandManager.startBandSelect(e.getOrigin());
            } else if (mBandManager.isActive()
                    && e.isMouseEvent()
                    && e.isActionUp()) {
                // Same issue here w b/23793622. The ACTION_UP event
                // is only evert dispatched to onTouchEvent when
                // there is some associated motion. If a user taps
                // mouse, but doesn't move, then band select gets
                // started BUT not ended. Causing phantom
                // bands to appear when the user later clicks to start
                // band select.
                mBandManager.processInputEvent(e);
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
                mModel.removeOnSelectionChangedListener(this);
                mModel.stopListening();

                // build a new model, all fresh and happy.
                mModelBuilder.run();
            }
        }

        boolean shouldStart(MotionInputEvent e) {
            return !isActive()
                    && e.isMouseEvent()  // a mouse
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
                    setSelectionRangeBegin(firstSelected);
                } else {
                    // TODO: Check if this is really happening.
                    Log.w(TAG, "First selected by band is NOT in selection!");
                }
            }

            mModel = null;
            mOrigin = null;
        }

        @Override
        public void onSelectionChanged(Set<String> updatedSelection) {
            Map<String, Boolean> delta = mSelection.setProvisionalSelection(updatedSelection);
            for (Map.Entry<String, Boolean> entry: delta.entrySet()) {
                notifyItemStateChanged(entry.getKey(), entry.getValue());
            }
            notifySelectionChanged();
        }

        @Override
        public boolean onBeforeItemStateChange(String id, boolean nextState) {
            return notifyBeforeItemStateChange(id, nextState);
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
    }

    /**
     * Provides a band selection item model for views within a RecyclerView. This class queries the
     * RecyclerView to determine where its items are placed; then, once band selection is underway,
     * it alerts listeners of which items are covered by the selections.
     */
    public static final class GridModel extends RecyclerView.OnScrollListener {

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

        private final List<OnSelectionChangedListener> mOnSelectionChangedListeners =
                new ArrayList<>();

        // Map from the x-value of the left side of a SparseBooleanArray of adapter positions, keyed
        // by their y-offset. For example, if the first column of the view starts at an x-value of 5,
        // mColumns.get(5) would return an array of positions in that column. Within that array, the
        // value for key y is the adapter position for the item whose y-offset is y.
        private final SparseArray<SparseIntArray> mColumns = new SparseArray<>();

        // List of limits along the x-axis (columns).
        // This list is sorted from furthest left to furthest right.
        private final List<Limits> mColumnBounds = new ArrayList<>();

        // List of limits along the y-axis (rows). Note that this list only contains items which
        // have been in the viewport.
        private final List<Limits> mRowBounds = new ArrayList<>();

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
            for (OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
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
            for (OnSelectionChangedListener listener : mOnSelectionChangedListeners) {
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
                int index = Collections.binarySearch(limitsList, new Limits(value, value));

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

        private boolean areItemsCoveredByBand(
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
