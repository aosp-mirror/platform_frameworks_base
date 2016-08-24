/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.views;

import android.animation.Animator;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.LaunchTvTaskEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.views.AnimationProps;

import java.util.ArrayList;
import java.util.List;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

public class TaskStackHorizontalViewAdapter extends
        RecyclerView.Adapter<TaskStackHorizontalViewAdapter.ViewHolder> {

    //Full class name is 30 characters
    private static final String TAG = "TaskStackViewAdapter";
    private List<Task> mTaskList;
    private TaskStackHorizontalGridView mGridView;

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
        private TaskCardView mTaskCardView;
        private Task mTask;
        public ViewHolder(View v) {
            super(v);
            mTaskCardView = (TaskCardView) v;
        }

        public void init(Task task) {
            mTaskCardView.init(task);
            mTask = task;
            mTaskCardView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            try {
                if (mTaskCardView.isInDismissState()) {
                    mTaskCardView.startDismissTaskAnimation(
                            getRemoveAtListener(getAdapterPosition(), mTaskCardView.getTask()));
                } else {
                    EventBus.getDefault().send(new LaunchTvTaskEvent(mTaskCardView, mTask,
                            null, INVALID_STACK_ID));
                }
            } catch (Exception e) {
                Log.e(TAG, v.getContext()
                        .getString(R.string.recents_launch_error_message, mTask.title), e);
            }

        }

        private Animator.AnimatorListener getRemoveAtListener(final int position,
                                                              final Task task) {
            return new Animator.AnimatorListener() {

                @Override
                public void onAnimationStart(Animator animation) { }

                @Override
                public void onAnimationEnd(Animator animation) {
                    removeTask(task);
                    EventBus.getDefault().send(new DeleteTaskDataEvent(task));
                }

                @Override
                public void onAnimationCancel(Animator animation) { }

                @Override
                public void onAnimationRepeat(Animator animation) { }
            };

        }
    }

    public TaskStackHorizontalViewAdapter(List tasks) {
        mTaskList = new ArrayList<Task>(tasks);
    }

    public void setNewStackTasks(List tasks) {
        mTaskList.clear();
        mTaskList.addAll(tasks);
        notifyDataSetChanged();
    }

    @Override
    public TaskStackHorizontalViewAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ViewHolder viewHolder = new ViewHolder(
                        inflater.inflate(R.layout.recents_tv_task_card_view, parent, false));
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Task task = mTaskList.get(position);
        // Retrives from caches, loading only if necessary
        Recents.getTaskLoader().loadTaskData(task);
        holder.init(task);
    }

    @Override
    public int getItemCount() {
        return mTaskList.size();
    }

    public void removeTask(Task task) {
        int position = mTaskList.indexOf(task);
        if (position >= 0) {
            mTaskList.remove(position);
            notifyItemRemoved(position);
            if (mGridView != null) {
                mGridView.getStack().removeTask(task, AnimationProps.IMMEDIATE,
                        false);
            }
        }
    }

    public int getPositionOfTask(Task task) {
        int position = mTaskList.indexOf(task);
        return (position >= 0) ? position : 0;
    }


    public void setTaskStackHorizontalGridView(TaskStackHorizontalGridView gridView) {
        mGridView = gridView;
    }

    public void addTaskAt(Task task, int position) {
        mTaskList.add(position, task);
        notifyItemInserted(position);
    }
}
