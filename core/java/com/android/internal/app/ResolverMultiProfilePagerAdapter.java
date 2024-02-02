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

import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
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
    private boolean mUseLayoutWithDefault;

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter adapter,
            EmptyStateProvider emptyStateProvider,
            QuietModeManager quietModeManager,
            UserHandle workProfileUserHandle,
            UserHandle cloneUserHandle) {
        super(context, /* currentPage */ 0, emptyStateProvider, quietModeManager,
                workProfileUserHandle, cloneUserHandle);
        mItems = new ResolverProfileDescriptor[] {
                createProfileDescriptor(adapter)
        };
    }

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter personalAdapter,
            ResolverListAdapter workAdapter,
            EmptyStateProvider emptyStateProvider,
            QuietModeManager quietModeManager,
            @Profile int defaultProfile,
            UserHandle workProfileUserHandle,
            UserHandle cloneUserHandle) {
        super(context, /* currentPage */ defaultProfile, emptyStateProvider, quietModeManager,
                workProfileUserHandle, cloneUserHandle);
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
    public ResolverProfileDescriptor getItem(int pageIndex) {
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
    @VisibleForTesting
    public ResolverListAdapter getAdapterForIndex(int pageIndex) {
        return mItems[pageIndex].resolverListAdapter;
    }

    @Override
    public ViewGroup instantiateItem(ViewGroup container, int position) {
        setupListAdapter(position);
        return super.instantiateItem(container, position);
    }

    @Override
    @Nullable
    ResolverListAdapter getListAdapterForUserHandle(UserHandle userHandle) {
        if (getPersonalListAdapter().getUserHandle().equals(userHandle)
                || userHandle.equals(getCloneUserHandle())) {
            return getPersonalListAdapter();
        } else if (getWorkListAdapter() != null
                && getWorkListAdapter().getUserHandle().equals(userHandle)) {
            return getWorkListAdapter();
        }
        return null;
    }

    @Override
    @VisibleForTesting
    public ResolverListAdapter getActiveListAdapter() {
        return getAdapterForIndex(getCurrentPage());
    }

    @Override
    @VisibleForTesting
    public ResolverListAdapter getInactiveListAdapter() {
        if (getCount() == 1) {
            return null;
        }
        return getAdapterForIndex(1 - getCurrentPage());
    }

    @Override
    public ResolverListAdapter getPersonalListAdapter() {
        return getAdapterForIndex(PROFILE_PERSONAL);
    }

    @Override
    @Nullable
    public ResolverListAdapter getWorkListAdapter() {
        if (getCount() == 1) {
            return null;
        }
        return getAdapterForIndex(PROFILE_WORK);
    }

    @Override
    ResolverListAdapter getCurrentRootAdapter() {
        return getActiveListAdapter();
    }

    @Override
    ListView getActiveAdapterView() {
        return getListViewForIndex(getCurrentPage());
    }

    @Override
    @Nullable
    ViewGroup getInactiveAdapterView() {
        if (getCount() == 1) {
            return null;
        }
        return getListViewForIndex(1 - getCurrentPage());
    }

    void setUseLayoutWithDefault(boolean useLayoutWithDefault) {
        mUseLayoutWithDefault = useLayoutWithDefault;
    }

    @Override
    protected void setupContainerPadding(View container) {
        int bottom = mUseLayoutWithDefault ? container.getPaddingBottom() : 0;
        container.setPadding(container.getPaddingLeft(), container.getPaddingTop(),
                container.getPaddingRight(), bottom);
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
