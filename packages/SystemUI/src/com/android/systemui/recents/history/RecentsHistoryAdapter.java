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

import android.app.ActivityOptions;
import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;


/**
 * An adapter for the list of recent tasks in the history view.
 */
public class RecentsHistoryAdapter extends RecyclerView.Adapter<RecentsHistoryAdapter.ViewHolder> {

    private static final String TAG = "RecentsHistoryView";
    private static final boolean DEBUG = false;

    static final int DATE_ROW_VIEW_TYPE = 0;
    static final int TASK_ROW_VIEW_TYPE = 1;

    /**
     * View holder implementation.  The {@param TaskCallbacks} are only called for TaskRow view
     * holders.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder implements Task.TaskCallbacks {
        public final View content;

        public ViewHolder(View v) {
            super(v);
            content = v;
        }

        @Override
        public void onTaskDataLoaded(Task task) {
            // This callback is only made for TaskRow view holders
            ImageView iv = (ImageView) content.findViewById(R.id.icon);
            iv.setImageDrawable(task.applicationIcon);
        }

        @Override
        public void onTaskDataUnloaded() {
            // This callback is only made for TaskRow view holders
            ImageView iv = (ImageView) content.findViewById(R.id.icon);
            iv.setImageBitmap(null);
        }

        @Override
        public void onTaskStackIdChanged() {
            // Do nothing, this callback is only made for TaskRow view holders
        }
    }

    /**
     * A single row of content.
     */
    private interface Row {
        int getViewType();
    }

    /**
     * A date row.
     */
    private static class DateRow implements Row {

        public final String date;

        public DateRow(String date) {
            this.date = date;
        }

        @Override
        public int getViewType() {
            return RecentsHistoryAdapter.DATE_ROW_VIEW_TYPE;
        }
    }

    /**
     * A task row.
     */
    private static class TaskRow implements Row, View.OnClickListener {

        public final Task task;

        public TaskRow(Task task) {
            this.task = task;
        }

        @Override
        public void onClick(View v) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ssp.startActivityFromRecents(v.getContext(), task.key.id, task.activityLabel,
                    ActivityOptions.makeBasic());
        }

        @Override
        public int getViewType() {
            return RecentsHistoryAdapter.TASK_ROW_VIEW_TYPE;
        }
    }

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private final List<Task> mTasks = new ArrayList<>();
    private final List<Row> mRows = new ArrayList<>();

    public RecentsHistoryAdapter(Context context) {
        mLayoutInflater = LayoutInflater.from(context);
    }

    /**
     * Updates this adapter with the given tasks.
     */
    public void updateTasks(Context context, List<Task> tasks) {
        mContext = context;
        mTasks.clear();
        mTasks.addAll(tasks);

        final Locale l = context.getResources().getConfiguration().locale;
        final String dateFormatStr = DateFormat.getBestDateTimePattern(l, "EEEEMMMMd");
        final List<Task> tasksMostRecent = new ArrayList<>(tasks);
        Collections.reverse(tasksMostRecent);
        int prevDayKey = -1;
        mRows.clear();
        for (Task task : tasksMostRecent) {
            if (task.isFreeformTask()) {
                continue;
            }

            Calendar cal = Calendar.getInstance(l);
            cal.setTimeInMillis(task.key.lastActiveTime);
            int dayKey = Objects.hash(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR));
            if (dayKey != prevDayKey) {
                prevDayKey = dayKey;
                mRows.add(new DateRow(DateFormat.format(dateFormatStr, cal).toString()));
            }
            mRows.add(new TaskRow(task));
        }
        notifyDataSetChanged();
    }

    /**
     * Removes historical tasks beloning to the specified package and user.
     */
    public void removeTasks(String packageName, int userId) {
        boolean packagesRemoved = false;
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            Task task = mTasks.get(i);
            String taskPackage = task.key.getComponent().getPackageName();
            if (task.key.userId == userId && taskPackage.equals(packageName)) {
                mTasks.remove(i);
                packagesRemoved = true;
            }
        }
        if (packagesRemoved) {
            updateTasks(mContext, new ArrayList<Task>(mTasks));
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case DATE_ROW_VIEW_TYPE:
                return new ViewHolder(mLayoutInflater.inflate(R.layout.recents_history_date, parent,
                        false));
            case TASK_ROW_VIEW_TYPE:
                return new ViewHolder(mLayoutInflater.inflate(R.layout.recents_history_task, parent,
                        false));
            default:
                return new ViewHolder(null);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        RecentsTaskLoader loader = Recents.getTaskLoader();

        Row row = mRows.get(position);
        int viewType = row.getViewType();
        switch (viewType) {
            case DATE_ROW_VIEW_TYPE: {
                TextView tv = (TextView) holder.content;
                tv.setText(((DateRow) row).date);
                break;
            }
            case TASK_ROW_VIEW_TYPE: {
                TaskRow taskRow = (TaskRow) row;
                taskRow.task.addCallback(holder);
                TextView tv = (TextView) holder.content.findViewById(R.id.description);
                tv.setText(taskRow.task.activityLabel);
                holder.content.setOnClickListener(taskRow);
                loader.loadTaskData(taskRow.task);
                break;
            }
        }
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        RecentsTaskLoader loader = Recents.getTaskLoader();

        int position = holder.getAdapterPosition();
        if (position != RecyclerView.NO_POSITION) {
            Row row = mRows.get(position);
            int viewType = row.getViewType();
            if (viewType == TASK_ROW_VIEW_TYPE) {
                TaskRow taskRow = (TaskRow) row;
                taskRow.task.removeCallback(holder);
                loader.unloadTaskData(taskRow.task);
            }
        }
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mRows.get(position).getViewType();
    }
}
