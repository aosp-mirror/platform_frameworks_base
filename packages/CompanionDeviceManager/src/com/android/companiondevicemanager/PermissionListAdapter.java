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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

class PermissionListAdapter extends RecyclerView.Adapter<PermissionListAdapter.ViewHolder> {
    private final Context mContext;

    private List<Integer> mPermissions;

    static final int TYPE_NOTIFICATION = 0;
    static final int TYPE_STORAGE = 1;
    static final int TYPE_APPS = 2;

    private static final Map<Integer, Integer> sTitleMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(TYPE_NOTIFICATION, R.string.permission_notification);
        map.put(TYPE_STORAGE, R.string.permission_storage);
        map.put(TYPE_APPS, R.string.permission_apps);
        sTitleMap = unmodifiableMap(map);
    }

    private static final Map<Integer, Integer> sSummaryMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(TYPE_NOTIFICATION, R.string.permission_notification_summary);
        map.put(TYPE_STORAGE, R.string.permission_storage_summary);
        map.put(TYPE_APPS, R.string.permission_apps_summary);
        sSummaryMap = unmodifiableMap(map);
    }

    private static final Map<Integer, Integer> sIconMap;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(TYPE_NOTIFICATION, R.drawable.ic_notifications);
        map.put(TYPE_STORAGE, R.drawable.ic_storage);
        map.put(TYPE_APPS, R.drawable.ic_apps);
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

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        int type = getItemViewType(position);
        final Spanned title = getHtmlFromResources(mContext, sTitleMap.get(type));
        final Spanned summary = getHtmlFromResources(mContext, sSummaryMap.get(type));

        holder.mPermissionName.setText(title);
        holder.mPermissionSummary.setText(summary);
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
        ViewHolder(View itemView) {
            super(itemView);
            mPermissionName = itemView.findViewById(R.id.permission_name);
            mPermissionSummary = itemView.findViewById(R.id.permission_summary);
            mPermissionIcon = itemView.findViewById(R.id.permission_icon);
        }
    }

    void setPermissionType(List<Integer> permissions) {
        mPermissions = permissions;
    }
}
