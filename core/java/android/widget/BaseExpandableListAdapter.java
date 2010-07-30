/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.database.DataSetObservable;
import android.database.DataSetObserver;

/**
 * Base class for a {@link ExpandableListAdapter} used to provide data and Views
 * from some data to an expandable list view.
 * <p>
 * Adapters inheriting this class should verify that the base implementations of
 * {@link #getCombinedChildId(long, long)} and {@link #getCombinedGroupId(long)}
 * are correct in generating unique IDs from the group/children IDs.
 * <p>
 * @see SimpleExpandableListAdapter
 * @see SimpleCursorTreeAdapter
 */
public abstract class BaseExpandableListAdapter implements ExpandableListAdapter, 
        HeterogeneousExpandableList {
    private final DataSetObservable mDataSetObservable = new DataSetObservable();
    
    public void registerDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.registerObserver(observer);
    }

    public void unregisterDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.unregisterObserver(observer);
    }
    
    /**
     * @see DataSetObservable#notifyInvalidated()
     */
    public void notifyDataSetInvalidated() {
        mDataSetObservable.notifyInvalidated();
    }
    
    /**
     * @see DataSetObservable#notifyChanged()
     */
    public void notifyDataSetChanged() {
        mDataSetObservable.notifyChanged();
    }

    public boolean areAllItemsEnabled() {
        return true;
    }

    public void onGroupCollapsed(int groupPosition) {
    }

    public void onGroupExpanded(int groupPosition) {
    }

    /**
     * Override this method if you foresee a clash in IDs based on this scheme:
     * <p>
     * Base implementation returns a long:
     * <li> bit 0: Whether this ID points to a child (unset) or group (set), so for this method
     *             this bit will be 1.
     * <li> bit 1-31: Lower 31 bits of the groupId
     * <li> bit 32-63: Lower 32 bits of the childId.
     * <p> 
     * {@inheritDoc}
     */
    public long getCombinedChildId(long groupId, long childId) {
        return 0x8000000000000000L | ((groupId & 0x7FFFFFFF) << 32) | (childId & 0xFFFFFFFF);
    }

    /**
     * Override this method if you foresee a clash in IDs based on this scheme:
     * <p>
     * Base implementation returns a long:
     * <li> bit 0: Whether this ID points to a child (unset) or group (set), so for this method
     *             this bit will be 0.
     * <li> bit 1-31: Lower 31 bits of the groupId
     * <li> bit 32-63: Lower 32 bits of the childId.
     * <p> 
     * {@inheritDoc}
     */
    public long getCombinedGroupId(long groupId) {
        return (groupId & 0x7FFFFFFF) << 32;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        return getGroupCount() == 0;
    }


    /**
     * {@inheritDoc}
     * @return 0 for any group or child position, since only one child type count is declared.
     */
    public int getChildType(int groupPosition, int childPosition) {
        return 0;
    }

    /**
     * {@inheritDoc}
     * @return 1 as a default value in BaseExpandableListAdapter.
     */
    public int getChildTypeCount() {
        return 1;
    }

    /**
     * {@inheritDoc}
     * @return 0 for any groupPosition, since only one group type count is declared.
     */
    public int getGroupType(int groupPosition) {
        return 0;
    }

    /**
     * {@inheritDoc}
     * @return 1 as a default value in BaseExpandableListAdapter.
     */
    public int getGroupTypeCount() {
        return 1;
    }
}
