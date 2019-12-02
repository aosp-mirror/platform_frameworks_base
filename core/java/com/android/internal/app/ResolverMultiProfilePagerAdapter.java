/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ListView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.PagerAdapter;

/**
 * A {@link PagerAdapter} which describes the work and personal profile intent resolver screens.
 */
@VisibleForTesting
public class ResolverMultiProfilePagerAdapter extends AbstractMultiProfilePagerAdapter {

    private final ResolverProfileDescriptor[] mItems;

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter adapter) {
        super(context, /* currentPage */ 0);
        mItems = new ResolverProfileDescriptor[] {
                createProfileDescriptor(adapter)
        };
    }

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter personalAdapter,
            ResolverListAdapter workAdapter,
            @Profile int defaultProfile) {
        super(context, /* currentPage */ defaultProfile);
        mItems = new ResolverProfileDescriptor[] {
                createProfileDescriptor(personalAdapter),
                createProfileDescriptor(workAdapter)
        };
    }

    private ResolverProfileDescriptor createProfileDescriptor(
            ResolverListAdapter adapter) {
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final ViewGroup rootView =
                (ViewGroup) inflater.inflate(R.layout.resolver_list_per_profile, null, false);
        return new ResolverProfileDescriptor(rootView, adapter);
    }

    ListView getListViewForIndex(int index) {
        return getItem(index).listView;
    }

    @Override
    ResolverProfileDescriptor getItem(int pageIndex) {
        return mItems[pageIndex];
    }

    @Override
    int getItemCount() {
        return mItems.length;
    }

    @Override
    void setupListAdapter(int pageIndex) {
        final ListView listView = getItem(pageIndex).listView;
        listView.setAdapter(getItem(pageIndex).resolverListAdapter);
    }

    @Override
    ResolverListAdapter getAdapterForIndex(int pageIndex) {
        return mItems[pageIndex].resolverListAdapter;
    }

    @Override
    @VisibleForTesting
    public ResolverListAdapter getCurrentListAdapter() {
        return getAdapterForIndex(getCurrentPage());
    }

    @Override
    ResolverListAdapter getCurrentRootAdapter() {
        return getCurrentListAdapter();
    }

    @Override
    ListView getCurrentAdapterView() {
        return getListViewForIndex(getCurrentPage());
    }

    class ResolverProfileDescriptor extends ProfileDescriptor {
        private ResolverListAdapter resolverListAdapter;
        final ListView listView;
        ResolverProfileDescriptor(ViewGroup rootView, ResolverListAdapter adapter) {
            super(rootView);
            resolverListAdapter = adapter;
            listView = rootView.findViewById(R.id.resolver_list);
        }
    }
}
