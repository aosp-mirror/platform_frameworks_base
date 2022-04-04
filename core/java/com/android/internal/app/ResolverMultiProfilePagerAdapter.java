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

import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CANT_ACCESS_WORK;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_CROSS_PROFILE_BLOCKED_TITLE;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_NO_PERSONAL_APPS;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_NO_WORK_APPS;
import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_PAUSED_TITLE;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Resources;
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
    private final boolean mShouldShowNoCrossProfileIntentsEmptyState;
    private boolean mUseLayoutWithDefault;

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter adapter,
            UserHandle personalProfileUserHandle,
            UserHandle workProfileUserHandle) {
        super(context, /* currentPage */ 0, personalProfileUserHandle, workProfileUserHandle);
        mItems = new ResolverProfileDescriptor[] {
                createProfileDescriptor(adapter)
        };
        mShouldShowNoCrossProfileIntentsEmptyState = true;
    }

    ResolverMultiProfilePagerAdapter(Context context,
            ResolverListAdapter personalAdapter,
            ResolverListAdapter workAdapter,
            @Profile int defaultProfile,
            UserHandle personalProfileUserHandle,
            UserHandle workProfileUserHandle,
            boolean shouldShowNoCrossProfileIntentsEmptyState) {
        super(context, /* currentPage */ defaultProfile, personalProfileUserHandle,
                workProfileUserHandle);
        mItems = new ResolverProfileDescriptor[] {
                createProfileDescriptor(personalAdapter),
                createProfileDescriptor(workAdapter)
        };
        mShouldShowNoCrossProfileIntentsEmptyState = shouldShowNoCrossProfileIntentsEmptyState;
    }

    @Override
    void updateAfterConfigChange() {
        super.updateAfterConfigChange();
        for (ResolverProfileDescriptor descriptor : mItems) {
            View emptyStateCont =
                    descriptor.rootView.findViewById(R.id.resolver_empty_state_container);
            Resources resources = getContext().getResources();
            emptyStateCont.setPadding(
                    emptyStateCont.getPaddingLeft(),
                    resources.getDimensionPixelSize(
                            R.dimen.resolver_empty_state_container_padding_top),
                    emptyStateCont.getPaddingRight(),
                    resources.getDimensionPixelSize(
                            R.dimen.resolver_empty_state_container_padding_bottom));

        }
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
        if (getActiveListAdapter().getUserHandle().equals(userHandle)) {
            return getActiveListAdapter();
        } else if (getInactiveListAdapter() != null
                && getInactiveListAdapter().getUserHandle().equals(userHandle)) {
            return getInactiveListAdapter();
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

    @Override
    String getMetricsCategory() {
        return ResolverActivity.METRICS_CATEGORY_RESOLVER;
    }

    @Override
    boolean allowShowNoCrossProfileIntentsEmptyState() {
        return mShouldShowNoCrossProfileIntentsEmptyState;
    }

    @Override
    protected void showWorkProfileOffEmptyState(ResolverListAdapter activeListAdapter,
            View.OnClickListener listener) {
        showEmptyState(activeListAdapter,
                R.drawable.ic_work_apps_off,
                getWorkAppPausedTitle(),
                /* subtitle = */ null,
                listener);
    }

    @Override
    protected void showNoPersonalToWorkIntentsEmptyState(ResolverListAdapter activeListAdapter) {
        showEmptyState(activeListAdapter,
                R.drawable.ic_sharing_disabled,
                getCrossProfileBlockedTitle(),
                getCantAccessWorkMessage());
    }

    @Override
    protected void showNoWorkToPersonalIntentsEmptyState(ResolverListAdapter activeListAdapter) {
        showEmptyState(activeListAdapter,
                R.drawable.ic_sharing_disabled,
                getCrossProfileBlockedTitle(),
                getCantAccessPersonalMessage());
    }

    @Override
    protected void showNoPersonalAppsAvailableEmptyState(ResolverListAdapter listAdapter) {
        showEmptyState(listAdapter,
                R.drawable.ic_no_apps,
                getNoPersonalAppsAvailableMessage(),
                /* subtitle = */ null);
    }

    @Override
    protected void showNoWorkAppsAvailableEmptyState(ResolverListAdapter listAdapter) {
        showEmptyState(listAdapter,
                R.drawable.ic_no_apps,
                getNoWorkAppsAvailableMessage(),
                /* subtitle= */ null);
    }

    private String getWorkAppPausedTitle() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_WORK_PAUSED_TITLE,
                () -> getContext().getString(R.string.resolver_turn_on_work_apps));
    }

    private String getCrossProfileBlockedTitle() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_CROSS_PROFILE_BLOCKED_TITLE,
                () -> getContext().getString(R.string.resolver_cross_profile_blocked));
    }

    private String getCantAccessWorkMessage() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_CANT_ACCESS_WORK,
                () -> getContext().getString(
                        R.string.resolver_cant_access_work_apps_explanation));
    }

    private String getCantAccessPersonalMessage() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_CANT_ACCESS_PERSONAL,
                () -> getContext().getString(
                        R.string.resolver_cant_access_personal_apps_explanation));
    }

    private String getNoWorkAppsAvailableMessage() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_NO_WORK_APPS,
                () -> getContext().getString(
                        R.string.resolver_no_work_apps_available));
    }

    private String getNoPersonalAppsAvailableMessage() {
        return getContext().getSystemService(DevicePolicyManager.class).getResources().getString(
                RESOLVER_NO_PERSONAL_APPS,
                () -> getContext().getString(
                        R.string.resolver_no_personal_apps_available));
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
