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

import static com.android.companiondevicemanager.CompanionDeviceResources.PERMISSION_ICONS;
import static com.android.companiondevicemanager.CompanionDeviceResources.PERMISSION_SUMMARIES;
import static com.android.companiondevicemanager.CompanionDeviceResources.PERMISSION_TITLES;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;
import static com.android.companiondevicemanager.Utils.getIcon;

import android.content.Context;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

class PermissionListAdapter extends RecyclerView.Adapter<PermissionListAdapter.ViewHolder> {
    private final Context mContext;
    private List<Integer> mPermissions;
    // Add the expand buttons if permissions are more than PERMISSION_SIZE in the permission list.
    private static final int PERMISSION_SIZE = 2;

    PermissionListAdapter(Context context) {
        mContext = context;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.list_item_permission, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        viewHolder.mPermissionIcon.setImageDrawable(
                getIcon(mContext, PERMISSION_ICONS.get(viewType)));

        if (viewHolder.mExpandButton.getTag() == null) {
            viewHolder.mExpandButton.setTag(R.drawable.btn_expand_more);
        }

        // Add expand buttons if the permissions are more than PERMISSION_SIZE in this list also
        // make the summary invisible by default.
        if (mPermissions.size() > PERMISSION_SIZE) {
            setAccessibility(view, viewType,
                    AccessibilityNodeInfo.ACTION_CLICK, R.string.permission_expand, 0);

            viewHolder.mPermissionSummary.setVisibility(View.GONE);

            view.setOnClickListener(v -> {
                if ((Integer) viewHolder.mExpandButton.getTag() == R.drawable.btn_expand_more) {
                    viewHolder.mExpandButton.setImageResource(R.drawable.btn_expand_less);
                    viewHolder.mPermissionSummary.setVisibility(View.VISIBLE);
                    viewHolder.mExpandButton.setTag(R.drawable.btn_expand_less);
                    setAccessibility(view, viewType,
                            AccessibilityNodeInfo.ACTION_CLICK,
                            R.string.permission_collapse, R.string.permission_expand);
                } else {
                    viewHolder.mExpandButton.setImageResource(R.drawable.btn_expand_more);
                    viewHolder.mPermissionSummary.setVisibility(View.GONE);
                    viewHolder.mExpandButton.setTag(R.drawable.btn_expand_more);
                    setAccessibility(view, viewType,
                            AccessibilityNodeInfo.ACTION_CLICK,
                            R.string.permission_expand, R.string.permission_collapse);
                }
            });
        } else {
            // Remove expand buttons if the permissions are less than PERMISSION_SIZE in this list
            // also show the summary by default.
            viewHolder.mPermissionSummary.setVisibility(View.VISIBLE);
            viewHolder.mExpandButton.setVisibility(View.GONE);
        }

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        int type = getItemViewType(position);
        final Spanned title = getHtmlFromResources(mContext, PERMISSION_TITLES.get(type));
        final Spanned summary = getHtmlFromResources(mContext, PERMISSION_SUMMARIES.get(type));

        holder.mPermissionSummary.setText(summary);
        holder.mPermissionName.setText(title);
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

        ViewHolder(View itemView) {
            super(itemView);
            mPermissionName = itemView.findViewById(R.id.permission_name);
            mPermissionSummary = itemView.findViewById(R.id.permission_summary);
            mPermissionIcon = itemView.findViewById(R.id.permission_icon);
            mExpandButton = itemView.findViewById(R.id.permission_expand_button);
        }
    }

    private void setAccessibility(View view, int viewType, int action, int statusResourceId,
            int actionResourceId) {
        final String permission = mContext.getString(PERMISSION_TITLES.get(viewType));

        if (actionResourceId != 0) {
            view.announceForAccessibility(
                    getHtmlFromResources(mContext, actionResourceId, permission));
        }

        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(action,
                        getHtmlFromResources(mContext, statusResourceId, permission)));
            }
        });
    }

    void setPermissionType(List<Integer> permissions) {
        mPermissions = permissions;
    }
}
