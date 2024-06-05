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

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.RecyclerView;

/**
 * A {@link PagerAdapter} which describes the work and personal profile share sheet screens.
 */
@VisibleForTesting
public class ChooserMultiProfilePagerAdapter extends AbstractMultiProfilePagerAdapter {
    private static final int SINGLE_CELL_SPAN_SIZE = 1;

    private final ChooserProfileDescriptor[] mItems;
    private int mBottomOffset;
    private int mMaxTargetsPerRow;

    ChooserMultiProfilePagerAdapter(Context context,
            ChooserActivity.ChooserGridAdapter adapter,
            EmptyStateProvider emptyStateProvider,
            QuietModeManager quietModeManager,
            UserHandle workProfileUserHandle,
            UserHandle cloneUserHandle,
            int maxTargetsPerRow) {
        super(context, /* currentPage */ 0, emptyStateProvider, quietModeManager,
                workProfileUserHandle, cloneUserHandle);
        mItems = new ChooserProfileDescriptor[] {
                createProfileDescriptor(adapter)
        };
        mMaxTargetsPerRow = maxTargetsPerRow;
    }

    ChooserMultiProfilePagerAdapter(Context context,
            ChooserActivity.ChooserGridAdapter personalAdapter,
            ChooserActivity.ChooserGridAdapter workAdapter,
            EmptyStateProvider emptyStateProvider,
            QuietModeManager quietModeManager,
            @Profile int defaultProfile,
            UserHandle workProfileUserHandle,
            UserHandle cloneUserHandle,
            int maxTargetsPerRow) {
        super(context, /* currentPage */ defaultProfile, emptyStateProvider,
                quietModeManager, workProfileUserHandle, cloneUserHandle);
        mItems = new ChooserProfileDescriptor[] {
                createProfileDescriptor(personalAdapter),
                createProfileDescriptor(workAdapter)
        };
        mMaxTargetsPerRow = maxTargetsPerRow;
    }

    private ChooserProfileDescriptor createProfileDescriptor(
            ChooserActivity.ChooserGridAdapter adapter) {
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final ViewGroup rootView =
                (ViewGroup) inflater.inflate(R.layout.chooser_list_per_profile, null, false);
        ChooserProfileDescriptor profileDescriptor =
                new ChooserProfileDescriptor(rootView, adapter);
        profileDescriptor.recyclerView.setAccessibilityDelegateCompat(
                new ChooserRecyclerViewAccessibilityDelegate(profileDescriptor.recyclerView));
        return profileDescriptor;
    }

    public void setMaxTargetsPerRow(int maxTargetsPerRow) {
        mMaxTargetsPerRow = maxTargetsPerRow;
    }

    RecyclerView getListViewForIndex(int index) {
        return getItem(index).recyclerView;
    }

    @Override
    public ChooserProfileDescriptor getItem(int pageIndex) {
        return mItems[pageIndex];
    }

    @Override
    int getItemCount() {
        return mItems.length;
    }

    @Override
    @VisibleForTesting
    public ChooserActivity.ChooserGridAdapter getAdapterForIndex(int pageIndex) {
        return mItems[pageIndex].chooserGridAdapter;
    }

    @Override
    @Nullable
    ChooserListAdapter getListAdapterForUserHandle(UserHandle userHandle) {
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
    void setupListAdapter(int pageIndex) {
        final RecyclerView recyclerView = getItem(pageIndex).recyclerView;
        ChooserActivity.ChooserGridAdapter chooserGridAdapter =
                getItem(pageIndex).chooserGridAdapter;
        GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
        glm.setSpanCount(mMaxTargetsPerRow);
        glm.setSpanSizeLookup(
                new GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return chooserGridAdapter.shouldCellSpan(position)
                                ? SINGLE_CELL_SPAN_SIZE
                                : glm.getSpanCount();
                    }
                });
    }

    @Override
    @VisibleForTesting
    public ChooserListAdapter getActiveListAdapter() {
        return getAdapterForIndex(getCurrentPage()).getListAdapter();
    }

    @Override
    @VisibleForTesting
    public ChooserListAdapter getInactiveListAdapter() {
        if (getCount() == 1) {
            return null;
        }
        return getAdapterForIndex(1 - getCurrentPage()).getListAdapter();
    }

    @Override
    public ChooserListAdapter getPersonalListAdapter() {
        return getAdapterForIndex(PROFILE_PERSONAL).getListAdapter();
    }

    @Override
    @Nullable
    public ChooserListAdapter getWorkListAdapter() {
        if (getCount() == 1) {
            return null;
        }
        return getAdapterForIndex(PROFILE_WORK).getListAdapter();
    }

    @Override
    ChooserActivity.ChooserGridAdapter getCurrentRootAdapter() {
        return getAdapterForIndex(getCurrentPage());
    }

    @Override
    RecyclerView getActiveAdapterView() {
        return getListViewForIndex(getCurrentPage());
    }

    @Override
    @Nullable
    RecyclerView getInactiveAdapterView() {
        if (getCount() == 1) {
            return null;
        }
        return getListViewForIndex(1 - getCurrentPage());
    }

    void setEmptyStateBottomOffset(int bottomOffset) {
        mBottomOffset = bottomOffset;
    }

    @Override
    protected void setupContainerPadding(View container) {
        int initialBottomPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.resolver_empty_state_container_padding_bottom);
        container.setPadding(container.getPaddingLeft(), container.getPaddingTop(),
                container.getPaddingRight(), initialBottomPadding + mBottomOffset);
    }

    class ChooserProfileDescriptor extends ProfileDescriptor {
        private ChooserActivity.ChooserGridAdapter chooserGridAdapter;
        private RecyclerView recyclerView;
        ChooserProfileDescriptor(ViewGroup rootView, ChooserActivity.ChooserGridAdapter adapter) {
            super(rootView);
            chooserGridAdapter = adapter;
            recyclerView = rootView.findViewById(R.id.resolver_list);
        }
    }
}
