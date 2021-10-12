/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.tv.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

/**
 * Adapter for the VerticalGridView of the TvNotificationsPanelView.
 */
public class TvNotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = "TvNotificationAdapter";
    private SparseArray<StatusBarNotification> mNotifications;

    public TvNotificationAdapter() {
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public TvNotificationViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.tv_notification_item,
                parent, false);
        return new TvNotificationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (mNotifications == null) {
            Log.e(TAG, "Could not bind view holder because the notification is missing");
            return;
        }

        TvNotificationViewHolder holder = (TvNotificationViewHolder) viewHolder;
        Notification notification = mNotifications.valueAt(position).getNotification();
        holder.mTitle.setText(notification.extras.getString(Notification.EXTRA_TITLE));
        holder.mDetails.setText(notification.extras.getString(Notification.EXTRA_TEXT));
        holder.mPendingIntent = notification.contentIntent;
    }

    @Override
    public int getItemCount() {
        return mNotifications == null ? 0 : mNotifications.size();
    }

    @Override
    public long getItemId(int position) {
        // the item id is the notification id
        return mNotifications.keyAt(position);
    }

    /**
     * Updates the notifications and calls notifyDataSetChanged().
     */
    public void setNotifications(SparseArray<StatusBarNotification> notifications) {
        this.mNotifications = notifications;
        notifyDataSetChanged();
    }

    private static class TvNotificationViewHolder extends RecyclerView.ViewHolder implements
            View.OnClickListener {
        final TextView mTitle;
        final TextView mDetails;
        PendingIntent mPendingIntent;

        protected TvNotificationViewHolder(View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.tv_notification_title);
            mDetails = itemView.findViewById(R.id.tv_notification_details);
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            try {
                if (mPendingIntent != null) {
                    mPendingIntent.send();
                }
            } catch (PendingIntent.CanceledException e) {
                Log.d(TAG, "Pending intent canceled for : " + mPendingIntent);
            }
        }
    }

}
