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

package com.android.systemui.accessibility.floatingmenu;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ViewHolder;

import java.util.List;

/**
 * An adapter which shows the set of accessibility targets that can be performed.
 */
public class AccessibilityTargetAdapter extends Adapter<ViewHolder> {

    private final List<AccessibilityTarget> mTargets;
    public AccessibilityTargetAdapter(List<AccessibilityTarget> targets) {
        mTargets = targets;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int i) {
        final View root = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.accessibility_floating_menu_item, parent,
                /* attachToRoot= */ false);

        return new ViewHolder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.mIconView.setBackground(mTargets.get(position).getIcon());

        final boolean isFirstItem = (position == 0);
        final boolean isLastItem = (position == (getItemCount() - 1));
        final int padding = holder.itemView.getPaddingStart();
        final int paddingTop = isFirstItem ? padding : 0;
        final int paddingBottom = isLastItem ? padding : 0;
        holder.itemView.setPaddingRelative(padding, paddingTop, padding, paddingBottom);
        holder.itemView.setOnClickListener((v) -> mTargets.get(position).onSelected());
        holder.mDivider.setVisibility(isLastItem ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return mTargets.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View mIconView;
        final View mDivider;

        ViewHolder(View itemView) {
            super(itemView);
            mIconView = itemView.findViewById(R.id.icon_view);
            mDivider = itemView.findViewById(R.id.transparent_divider);
        }
    }
}
