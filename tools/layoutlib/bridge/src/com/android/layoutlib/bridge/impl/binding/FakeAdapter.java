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
import com.android.util.Pair;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake adapter to do fake data binding in {@link AdapterView} objects for {@link ListAdapter}
 * and {@link SpinnerAdapter}.
 *
 */
@SuppressWarnings("deprecation")
public class FakeAdapter extends BaseAdapter {

    // don't use a set because the order is important.
    private final List<ResourceReference> mTypes = new ArrayList<ResourceReference>();
    private final IProjectCallback mCallback;
    private final ResourceReference mAdapterRef;
    private final List<AdapterItem> mItems = new ArrayList<AdapterItem>();
    private boolean mSkipCallbackParser = false;

    public FakeAdapter(ResourceReference adapterRef, AdapterBinding binding,
            IProjectCallback callback) {
        mAdapterRef = adapterRef;
        mCallback = callback;

        final int repeatCount = binding.getRepeatCount();
        final int itemCount = binding.getItemCount();

        // Need an array to count for each type.
        // This is likely too big, but is the max it can be.
        int[] typeCount = new int[itemCount];

        // We put several repeating sets.
        for (int r = 0 ; r < repeatCount ; r++) {
            // loop on the type of list items, and add however many for each type.
            for (DataBindingItem dataBindingItem : binding) {
                ResourceReference viewRef = dataBindingItem.getViewReference();
                int typeIndex = mTypes.indexOf(viewRef);
                if (typeIndex == -1) {
                    typeIndex = mTypes.size();
                    mTypes.add(viewRef);
                }

                int count = dataBindingItem.getCount();

                int index = typeCount[typeIndex];
                typeCount[typeIndex] += count;

                for (int k = 0 ; k < count ; k++) {
                    mItems.add(new AdapterItem(dataBindingItem, typeIndex, mItems.size(), index++));
                }
            }
        }
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public Object getItem(int position) {
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position).getType();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // we don't care about recycling here because we never scroll.
        AdapterItem item = mItems.get(position);
        Pair<View, Boolean> pair = AdapterHelper.getView(item, null /*parentGroup*/, parent,
                mCallback, mAdapterRef, mSkipCallbackParser);
        mSkipCallbackParser = pair.getSecond();
        return pair.getFirst();

    }

    @Override
    public int getViewTypeCount() {
        return mTypes.size();
    }

    // ---- SpinnerAdapter

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        // pass
        return null;
    }
}
