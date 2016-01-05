/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.recents.history;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;


/**
 * An item touch handler for items in the history view.
 */
public class RecentsHistoryItemTouchCallbacks extends ItemTouchHelper.SimpleCallback {

    private Context mContext;
    private RecentsHistoryAdapter mAdapter;

    public RecentsHistoryItemTouchCallbacks(Context context, RecentsHistoryAdapter adapter) {
        super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        mContext = context;
        mAdapter = adapter;
    }

    @Override
    public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
            RecyclerView.ViewHolder target) {
        return false;
    }

    @Override
    public int getSwipeDirs(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        int viewType = mAdapter.getItemViewType(viewHolder.getAdapterPosition());
        switch (viewType) {
            case RecentsHistoryAdapter.DATE_ROW_VIEW_TYPE:
                // Disallow swiping
                return 0;
            default:
                return super.getSwipeDirs(recyclerView, viewHolder);
        }
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        int position = viewHolder.getAdapterPosition();
        if (position != RecyclerView.NO_POSITION) {
            RecentsHistoryAdapter.Row row = mAdapter.getRow(position);
            RecentsHistoryAdapter.TaskRow taskRow = (RecentsHistoryAdapter.TaskRow) row;

            // Remove the task from the system
            EventBus.getDefault().send(new DeleteTaskDataEvent(taskRow.task));
            mAdapter.onTaskRemoved(taskRow.task, position);

            // Keep track of deletions by swiping within history
            MetricsLogger.histogram(mContext, "overview_task_dismissed_source",
                    Constants.Metrics.DismissSourceHistorySwipeGesture);
        }
    }
}
