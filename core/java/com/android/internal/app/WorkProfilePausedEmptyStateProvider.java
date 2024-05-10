/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_PAUSED_TITLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.stats.devicepolicy.nano.DevicePolicyEnums;

import com.android.internal.R;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyState;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyStateProvider;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.OnSwitchOnWorkSelectedListener;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.QuietModeManager;

/**
 * Chooser/ResolverActivity empty state provider that returns empty state which is shown when
 * work profile is paused and we need to show a button to enable it.
 */
public class WorkProfilePausedEmptyStateProvider implements EmptyStateProvider {

    private final UserHandle mWorkProfileUserHandle;
    private final QuietModeManager mQuietModeManager;
    private final String mMetricsCategory;
    private final OnSwitchOnWorkSelectedListener mOnSwitchOnWorkSelectedListener;
    private final Context mContext;

    public WorkProfilePausedEmptyStateProvider(@NonNull Context context,
            @Nullable UserHandle workProfileUserHandle,
            @NonNull QuietModeManager quietModeManager,
            @Nullable OnSwitchOnWorkSelectedListener onSwitchOnWorkSelectedListener,
            @NonNull String metricsCategory) {
        mContext = context;
        mWorkProfileUserHandle = workProfileUserHandle;
        mQuietModeManager = quietModeManager;
        mMetricsCategory = metricsCategory;
        mOnSwitchOnWorkSelectedListener = onSwitchOnWorkSelectedListener;
    }

    @Nullable
    @Override
    public EmptyState getEmptyState(ResolverListAdapter resolverListAdapter) {
        if (!resolverListAdapter.getUserHandle().equals(mWorkProfileUserHandle)
                || !mQuietModeManager.isQuietModeEnabled(mWorkProfileUserHandle)
                || resolverListAdapter.getCount() == 0) {
            return null;
        }

        final String title = mContext.getSystemService(DevicePolicyManager.class)
                .getResources().getString(RESOLVER_WORK_PAUSED_TITLE,
                () -> mContext.getString(R.string.resolver_turn_on_work_apps));

        return new WorkProfileOffEmptyState(title, (tab) -> {
            tab.showSpinner();
            if (mOnSwitchOnWorkSelectedListener != null) {
                mOnSwitchOnWorkSelectedListener.onSwitchOnWorkSelected();
            }
            mQuietModeManager.requestQuietModeEnabled(false, mWorkProfileUserHandle);
        }, mMetricsCategory);
    }

    public static class WorkProfileOffEmptyState implements EmptyState {

        private final String mTitle;
        private final ClickListener mOnClick;
        private final String mMetricsCategory;

        public WorkProfileOffEmptyState(String title, @NonNull ClickListener onClick,
                @NonNull String metricsCategory) {
            mTitle = title;
            mOnClick = onClick;
            mMetricsCategory = metricsCategory;
        }

        @Nullable
        @Override
        public String getTitle() {
            return mTitle;
        }

        @Nullable
        @Override
        public ClickListener getButtonClickListener() {
            return mOnClick;
        }

        @Override
        public void onEmptyStateShown() {
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.RESOLVER_EMPTY_STATE_WORK_APPS_DISABLED)
                    .setStrings(mMetricsCategory)
                    .write();
        }
    }
}
