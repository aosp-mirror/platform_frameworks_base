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

package com.android.companiondevicemanager;

import static com.android.companiondevicemanager.Utils.getHtmlFromResources;
import static com.android.companiondevicemanager.Utils.getIcon;

import static java.util.Collections.unmodifiableMap;

import android.content.Context;
import android.text.Spanned;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

class PermissionListAdapter extends RecyclerView.Adapter<PermissionListAdapter.ViewHolder> {
    private final Context mContext;
    private List<Integer> mPermissions;
    // Add the expand buttons if permissions are more than PERMISSION_SIZE in the permission list.
    private static final int PERMISSION_SIZE = 2;

    static final int PERMISSION_NOTIFICATION = 0;
    static final int PERMISSION_STORAGE = 1;
    static final int PERMISSION_APP_STREAMING = 2;
    static final int PERMISSION_PHONE = 3;
    static final int PERMISSION_SMS = 4;
    static final int PERMISSION_CONTACTS = 5;
    static final int PERMISSION_CALENDAR = 6;
    static final int PERMISSION_NEARBY_DEVICES = 7;
    static final int PERMISSION_NEARBY_DEVICE_STREAMING = 8;
    static final int PERMISSION_MICROPHONE = 9;

    private static final Map<Integer, Integer> sTitleMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION, R.string.permission_notification);
        map.put(PERMISSION_STORAGE, R.string.permission_storage);
        map.put(PERMISSION_APP_STREAMING, R.string.permission_app_streaming);
        map.put(PERMISSION_PHONE, R.string.permission_phone);
        map.put(PERMISSION_SMS, R.string.permission_sms);
        map.put(PERMISSION_CONTACTS, R.string.permission_contacts);
        map.put(PERMISSION_CALENDAR, R.string.permission_calendar);
        map.put(PERMISSION_NEARBY_DEVICES, R.string.permission_nearby_devices);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING, R.string.permission_nearby_device_streaming);
        map.put(PERMISSION_MICROPHONE, R.string.permission_microphone);
        sTitleMap = unmodifiableMap(map);
    }

    private static final Map<Integer, Integer> sSummaryMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION, R.string.permission_notification_summary);
        map.put(PERMISSION_STORAGE, R.string.permission_storage_summary);
        map.put(PERMISSION_APP_STREAMING, R.string.permission_app_streaming_summary);
        map.put(PERMISSION_PHONE, R.string.permission_phone_summary);
        map.put(PERMISSION_SMS, R.string.permission_sms_summary);
        map.put(PERMISSION_CONTACTS, R.string.permission_contacts_summary);
        map.put(PERMISSION_CALENDAR, R.string.permission_calendar_summary);
        map.put(PERMISSION_NEARBY_DEVICES, R.string.permission_nearby_devices_summary);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING,
                R.string.permission_nearby_device_streaming_summary);
        map.put(PERMISSION_MICROPHONE, R.string.permission_microphone_summary);
        sSummaryMap = unmodifiableMap(map);
    }

    private static final Map<Integer, Integer> sIconMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION, R.drawable.ic_permission_notifications);
        map.put(PERMISSION_STORAGE, R.drawable.ic_permission_storage);
        map.put(PERMISSION_APP_STREAMING, R.drawable.ic_permission_app_streaming);
        map.put(PERMISSION_PHONE, R.drawable.ic_permission_phone);
        map.put(PERMISSION_SMS, R.drawable.ic_permission_sms);
        map.put(PERMISSION_CONTACTS, R.drawable.ic_permission_contacts);
        map.put(PERMISSION_CALENDAR, R.drawable.ic_permission_calendar);
        map.put(PERMISSION_NEARBY_DEVICES, R.drawable.ic_permission_nearby_devices);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING,
                R.drawable.ic_permission_nearby_device_streaming);
        map.put(PERMISSION_MICROPHONE, R.drawable.ic_permission_microphone);
        sIconMap = unmodifiableMap(map);
    }

    PermissionListAdapter(Context context) {
        mContext = context;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.list_item_permission, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        viewHolder.mPermissionIcon.setImageDrawable(getIcon(mContext, sIconMap.get(viewType)));

        if (viewHolder.mExpandButton.getTag() == null) {
            viewHolder.mExpandButton.setTag(R.drawable.btn_expand_more);
        }
        // Add expand buttons if the permissions are more than PERMISSION_SIZE in this list.
        if (mPermissions.size() > PERMISSION_SIZE) {
            view.setOnClickListener(v -> {
                if ((Integer) viewHolder.mExpandButton.getTag() == R.drawable.btn_expand_more) {
                    viewHolder.mExpandButton.setImageResource(R.drawable.btn_expand_less);

                    if (viewHolder.mSummary != null) {
                        viewHolder.mPermissionSummary.setText(viewHolder.mSummary);
                    }

                    viewHolder.mPermissionSummary.setVisibility(View.VISIBLE);
                    viewHolder.mExpandButton.setTag(R.drawable.btn_expand_less);
                } else {
                    viewHolder.mExpandButton.setImageResource(R.drawable.btn_expand_more);
                    viewHolder.mPermissionSummary.setVisibility(View.GONE);
                    viewHolder.mExpandButton.setTag(R.drawable.btn_expand_more);
                }
            });
        }

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        int type = getItemViewType(position);
        final Spanned title = getHtmlFromResources(mContext, sTitleMap.get(type));
        final Spanned summary = getHtmlFromResources(mContext, sSummaryMap.get(type));

        holder.mSummary = summary;
        holder.mPermissionName.setText(title);

        if (mPermissions.size() <= PERMISSION_SIZE) {
            holder.mPermissionSummary.setText(summary);
            holder.mExpandButton.setVisibility(View.GONE);
        } else {
            holder.mPermissionSummary.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return mPermissions.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return mPermissions != null ? mPermissions.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView mPermissionName;
        private final TextView mPermissionSummary;
        private final ImageView mPermissionIcon;
        private final ImageButton mExpandButton;
        private Spanned mSummary = null;
        ViewHolder(View itemView) {
            super(itemView);
            mPermissionName = itemView.findViewById(R.id.permission_name);
            mPermissionSummary = itemView.findViewById(R.id.permission_summary);
            mPermissionIcon = itemView.findViewById(R.id.permission_icon);
            mExpandButton = itemView.findViewById(R.id.permission_expand_button);
        }
    }

    void setPermissionType(List<Integer> permissions) {
        mPermissions = permissions;
    }
}
