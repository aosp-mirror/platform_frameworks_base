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

import static android.view.View.GONE;

import static com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ItemType.FIRST_ITEM;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ItemType.LAST_ITEM;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ItemType.REGULAR_ITEM;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ViewHolder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * An adapter which shows the set of accessibility targets that can be performed.
 */
public class AccessibilityTargetAdapter extends Adapter<ViewHolder> {
    private int mIconWidthHeight;
    private final List<AccessibilityTarget> mTargets;

    @IntDef({
            FIRST_ITEM,
            REGULAR_ITEM,
            LAST_ITEM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ItemType {
        int FIRST_ITEM = 0;
        int REGULAR_ITEM = 1;
        int LAST_ITEM = 2;
    }

    public AccessibilityTargetAdapter(List<AccessibilityTarget> targets) {
        mTargets = targets;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, @ItemType int itemType) {
        final View root = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.accessibility_floating_menu_item, parent,
                /* attachToRoot= */ false);

        if (itemType == FIRST_ITEM) {
            return new TopViewHolder(root);
        }

        if (itemType == LAST_ITEM) {
            return new BottomViewHolder(root);
        }

        return new ViewHolder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.mIconView.setBackground(mTargets.get(position).getIcon());
        holder.updateIconWidthHeight(mIconWidthHeight);
        holder.itemView.setOnClickListener((v) -> mTargets.get(position).onSelected());
    }

    @ItemType
    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return FIRST_ITEM;
        }

        if (position == (getItemCount() - 1)) {
            return LAST_ITEM;
        }

        return REGULAR_ITEM;
    }

    @Override
    public int getItemCount() {
        return mTargets.size();
    }

    public void setIconWidthHeight(int iconWidthHeight) {
        mIconWidthHeight = iconWidthHeight;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View mIconView;
        final View mDivider;

        ViewHolder(View itemView) {
            super(itemView);
            mIconView = itemView.findViewById(R.id.icon_view);
            mDivider = itemView.findViewById(R.id.transparent_divider);
        }

        void updateIconWidthHeight(int newValue) {
            final ViewGroup.LayoutParams layoutParams = mIconView.getLayoutParams();
            if (layoutParams.width == newValue) {
                return;
            }
            layoutParams.width = newValue;
            layoutParams.height = newValue;
            mIconView.setLayoutParams(layoutParams);
        }
    }

    static class TopViewHolder extends ViewHolder {
        TopViewHolder(View itemView) {
            super(itemView);
            final int padding = itemView.getPaddingStart();
            itemView.setPaddingRelative(padding, padding, padding, 0);
        }
    }

    static class BottomViewHolder extends ViewHolder {
        BottomViewHolder(View itemView) {
            super(itemView);
            mDivider.setVisibility(GONE);
            final int padding = itemView.getPaddingStart();
            itemView.setPaddingRelative(padding, 0, padding, padding);
        }
    }
}
