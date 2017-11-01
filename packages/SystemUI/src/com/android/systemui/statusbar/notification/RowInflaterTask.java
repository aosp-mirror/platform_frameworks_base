/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.content.Context;
import android.support.v4.view.AsyncLayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;

/**
 * An inflater task that asynchronously inflates a ExpandableNotificationRow
 */
public class RowInflaterTask implements InflationTask, AsyncLayoutInflater.OnInflateFinishedListener {
    private RowInflationFinishedListener mListener;
    private NotificationData.Entry mEntry;
    private boolean mCancelled;

    /**
     * Inflates a new notificationView. This should not be called twice on this object
     */
    public void inflate(Context context, ViewGroup parent, NotificationData.Entry entry,
            RowInflationFinishedListener listener) {
        mListener = listener;
        AsyncLayoutInflater inflater = new AsyncLayoutInflater(context);
        mEntry = entry;
        entry.setInflationTask(this);
        inflater.inflate(R.layout.status_bar_notification_row, parent, this);
    }

    @Override
    public void abort() {
        mCancelled = true;
    }

    @Override
    public void onInflateFinished(View view, int resid, ViewGroup parent) {
        if (!mCancelled) {
            mEntry.onInflationTaskFinished();
            mListener.onInflationFinished((ExpandableNotificationRow) view);
        }
    }

    public interface RowInflationFinishedListener {
        void onInflationFinished(ExpandableNotificationRow row);
    }
}
