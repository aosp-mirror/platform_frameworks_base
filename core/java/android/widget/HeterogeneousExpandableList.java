/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.view.View;
import android.view.ViewGroup;

/**
 * Additional methods that when implemented make an
 * {@link ExpandableListAdapter} take advantage of the {@link Adapter} view type
 * mechanism.
 * <p>
 * An {@link ExpandableListAdapter} declares it has one view type for its group items
 * and one view type for its child items. Although adapted for most {@link ExpandableListView}s,
 * these values should be tuned for heterogeneous {@link ExpandableListView}s.
 * </p>
 * Lists that contain different types of group and/or child item views, should use an adapter that
 * implements this interface. This way, the recycled views that will be provided to
 * {@link android.widget.ExpandableListAdapter#getGroupView(int, boolean, View, ViewGroup)}
 * and
 * {@link android.widget.ExpandableListAdapter#getChildView(int, int, boolean, View, ViewGroup)}
 * will be of the appropriate group or child type, resulting in a more efficient reuse of the
 * previously created views.
 */
public interface HeterogeneousExpandableList {
    /**
     * Get the type of group View that will be created by
     * {@link android.widget.ExpandableListAdapter#getGroupView(int, boolean, View, ViewGroup)}
     * . for the specified group item.
     * 
     * @param groupPosition the position of the group for which the type should be returned.
     * @return An integer representing the type of group View. Two group views should share the same
     *         type if one can be converted to the other in
     *         {@link android.widget.ExpandableListAdapter#getGroupView(int, boolean, View, ViewGroup)}
     *         . Note: Integers must be in the range 0 to {@link #getGroupTypeCount} - 1.
     *         {@link android.widget.Adapter#IGNORE_ITEM_VIEW_TYPE} can also be returned.
     * @see android.widget.Adapter#IGNORE_ITEM_VIEW_TYPE
     * @see #getGroupTypeCount()
     */
    int getGroupType(int groupPosition);

    /**
     * Get the type of child View that will be created by
     * {@link android.widget.ExpandableListAdapter#getChildView(int, int, boolean, View, ViewGroup)}
     * for the specified child item.
     * 
     * @param groupPosition the position of the group that the child resides in
     * @param childPosition the position of the child with respect to other children in the group
     * @return An integer representing the type of child View. Two child views should share the same
     *         type if one can be converted to the other in
     *         {@link android.widget.ExpandableListAdapter#getChildView(int, int, boolean, View, ViewGroup)}
     *         Note: Integers must be in the range 0 to {@link #getChildTypeCount} - 1.
     *         {@link android.widget.Adapter#IGNORE_ITEM_VIEW_TYPE} can also be returned.
     * @see android.widget.Adapter#IGNORE_ITEM_VIEW_TYPE
     * @see #getChildTypeCount()
     */
    int getChildType(int groupPosition, int childPosition);

    /**
     * <p>
     * Returns the number of types of group Views that will be created by
     * {@link android.widget.ExpandableListAdapter#getGroupView(int, boolean, View, ViewGroup)}
     * . Each type represents a set of views that can be converted in
     * {@link android.widget.ExpandableListAdapter#getGroupView(int, boolean, View, ViewGroup)}
     * . If the adapter always returns the same type of View for all group items, this method should
     * return 1.
     * </p>
     * This method will only be called when the adapter is set on the {@link AdapterView}.
     * 
     * @return The number of types of group Views that will be created by this adapter.
     * @see #getChildTypeCount()
     * @see #getGroupType(int)
     */
    int getGroupTypeCount();

    /**
     * <p>
     * Returns the number of types of child Views that will be created by
     * {@link android.widget.ExpandableListAdapter#getChildView(int, int, boolean, View, ViewGroup)}
     * . Each type represents a set of views that can be converted in
     * {@link android.widget.ExpandableListAdapter#getChildView(int, int, boolean, View, ViewGroup)}
     * , for any group. If the adapter always returns the same type of View for
     * all child items, this method should return 1.
     * </p>
     * This method will only be called when the adapter is set on the {@link AdapterView}.
     * 
     * @return The total number of types of child Views that will be created by this adapter.
     * @see #getGroupTypeCount()
     * @see #getChildType(int, int)
     */
    int getChildTypeCount();
}
