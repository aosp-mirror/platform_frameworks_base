/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl.binding;

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.DataBindingItem;
import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.ResourceReference;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ExpandableListAdapter;
import android.widget.HeterogeneousExpandableList;

import java.util.ArrayList;
import java.util.List;

public class FakeExpandableAdapter extends BaseAdapter implements ExpandableListAdapter,
        HeterogeneousExpandableList {

    // don't use a set because the order is important.
    private final List<ResourceReference> mGroupTypes = new ArrayList<ResourceReference>();
    private final List<ResourceReference> mChildrenTypes = new ArrayList<ResourceReference>();

    public FakeExpandableAdapter(ResourceReference adapterRef, AdapterBinding binding,
            IProjectCallback callback) {
        super(adapterRef, binding, callback);

        createItems(binding, binding.getItemCount(), binding.getRepeatCount(), mGroupTypes, 1);
    }

    private void createItems(Iterable<DataBindingItem> iterable, final int itemCount,
            final int repeatCount, List<ResourceReference> types, int depth) {
        // Need an array to count for each type.
        // This is likely too big, but is the max it can be.
        int[] typeCount = new int[itemCount];

        // we put several repeating sets.
        for (int r = 0 ; r < repeatCount ; r++) {
            // loop on the type of list items, and add however many for each type.
            for (DataBindingItem dataBindingItem : iterable) {
                ResourceReference viewRef = dataBindingItem.getViewReference();
                int typeIndex = types.indexOf(viewRef);
                if (typeIndex == -1) {
                    typeIndex = types.size();
                    types.add(viewRef);
                }

                List<DataBindingItem> children = dataBindingItem.getChildren();
                int count = dataBindingItem.getCount();

                // if there are children, we use the count as a repeat count for the children.
                if (children.size() > 0) {
                    count = 1;
                }

                int index = typeCount[typeIndex];
                typeCount[typeIndex] += count;

                for (int k = 0 ; k < count ; k++) {
                    AdapterItem item = new AdapterItem(dataBindingItem, typeIndex, mItems.size(),
                            index++);
                    mItems.add(item);

                    if (children.size() > 0) {
                        createItems(dataBindingItem, depth + 1);
                    }
                }
            }
        }
    }

    private void createItems(DataBindingItem item, int depth) {
        if (depth == 2) {
            createItems(item, item.getChildren().size(), item.getCount(), mChildrenTypes, depth);
        }
    }

    private AdapterItem getChildItem(int groupPosition, int childPosition) {
        AdapterItem item = mItems.get(groupPosition);

        List<AdapterItem> children = item.getChildren();
        return children.get(childPosition);
    }

    // ---- ExpandableListAdapter

    public int getGroupCount() {
        return mItems.size();
    }

    public int getChildrenCount(int groupPosition) {
        AdapterItem item = mItems.get(groupPosition);
        return item.getChildren().size();
    }

    public Object getGroup(int groupPosition) {
        return mItems.get(groupPosition);
    }

    public Object getChild(int groupPosition, int childPosition) {
        return getChildItem(groupPosition, childPosition);
    }

    public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
            ViewGroup parent) {
        // we don't care about recycling here because we never scroll.
        AdapterItem item = mItems.get(groupPosition);
        return getView(item, null /*parentItem*/, convertView, parent);
    }

    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
            View convertView, ViewGroup parent) {
        // we don't care about recycling here because we never scroll.
        AdapterItem parentItem = mItems.get(groupPosition);
        AdapterItem item = getChildItem(groupPosition, childPosition);
        return getView(item, parentItem, convertView, parent);
    }

    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    public long getCombinedGroupId(long groupId) {
        return groupId << 16 | 0x0000FFFF;
    }

    public long getCombinedChildId(long groupId, long childId) {
        return groupId << 16 | childId;
    }

    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public void onGroupCollapsed(int groupPosition) {
        // pass
    }

    public void onGroupExpanded(int groupPosition) {
        // pass
    }

    // ---- HeterogeneousExpandableList

    public int getChildType(int groupPosition, int childPosition) {
        return getChildItem(groupPosition, childPosition).getType();
    }

    public int getChildTypeCount() {
        return mChildrenTypes.size();
    }

    public int getGroupType(int groupPosition) {
        return mItems.get(groupPosition).getType();
    }

    public int getGroupTypeCount() {
        return mGroupTypes.size();
    }
}
