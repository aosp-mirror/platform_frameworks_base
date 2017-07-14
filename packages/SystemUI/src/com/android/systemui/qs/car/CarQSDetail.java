/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.car;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.qs.QSDetail;
import com.android.systemui.qs.QSPanel;

/**
 * The detail view that displays below the status bar header in the auto use-case. This view
 * additional details of quick settings options, such as for showing the users when user switcher
 * has been selected.
 */
public class CarQSDetail extends FrameLayout {

    private final SparseArray<View> mDetailViews = new SparseArray<>();

    private DetailAdapter mDetailAdapter;

    public CarQSDetail(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        for (int i = 0; i < mDetailViews.size(); i++) {
            mDetailViews.valueAt(i).dispatchConfigurationChanged(newConfig);
        }
    }

    public void setQsPanel(QSPanel panel) {
        panel.setCallback(mQsPanelCallback);
    }

    public boolean isShowingDetail() {
        return mDetailAdapter != null;
    }

    public void handleShowingDetail(@Nullable DetailAdapter adapter) {
        boolean showingDetail = adapter != null;
        setClickable(showingDetail);

        // If it's already in the right state (not showing or already showing the right adapter),
        // then no need to change.
        if ((mDetailAdapter == null && adapter == null) || mDetailAdapter == adapter) {
            return;
        }

        if (showingDetail) {
            int viewCacheIndex = adapter.getMetricsCategory();
            View detailView = adapter.createDetailView(mContext, mDetailViews.get(viewCacheIndex),
                    this);
            if (detailView == null) {
                throw new IllegalStateException("Must return detail view");
            }

            removeAllViews();
            addView(detailView);
            mDetailViews.put(viewCacheIndex, detailView);
            Dependency.get(MetricsLogger.class).visible(adapter.getMetricsCategory());
            mDetailAdapter = adapter;
            setVisibility(View.VISIBLE);
        } else {
            if (mDetailAdapter != null) {
                Dependency.get(MetricsLogger.class).hidden(mDetailAdapter.getMetricsCategory());
            }
            mDetailAdapter = null;
            setVisibility(View.GONE);
        }
    }

    private QSDetail.Callback mQsPanelCallback = new QSDetail.Callback() {
        @Override
        public void onToggleStateChanged(final boolean state) {
        }

        @Override
        public void onShowingDetail(final DetailAdapter detail, final int x, final int y) {
            post(() -> handleShowingDetail(detail));
        }

        @Override
        public void onScanStateChanged(final boolean state) {
        }
    };
}
