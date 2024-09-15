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

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ViewHolder;
import com.android.systemui.res.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * An adapter which shows the set of accessibility targets that can be performed.
 */
public class AccessibilityTargetAdapter extends Adapter<ViewHolder> {
    private int mIconWidthHeight;
    private int mItemPadding;
    private final List<AccessibilityTarget> mTargets;

    @IntDef({
            ItemType.FIRST_ITEM,
            ItemType.REGULAR_ITEM,
            ItemType.LAST_ITEM
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

        if (itemType == ItemType.FIRST_ITEM) {
            return new TopViewHolder(root);
        }

        if (itemType == ItemType.LAST_ITEM) {
            return new BottomViewHolder(root);
        }

        return new ViewHolder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final AccessibilityTarget target = mTargets.get(position);
        holder.mIconView.setBackground(target.getIcon());
        holder.updateIconWidthHeight(mIconWidthHeight);
        holder.updateItemPadding(mItemPadding, getItemCount());
        holder.itemView.setOnClickListener((v) -> target.onSelected());
        holder.itemView.setStateDescription(target.getStateDescription());
        holder.itemView.setContentDescription(target.getLabel());

        final String clickHint = target.getFragmentType() == AccessibilityFragmentType.TOGGLE
                ? holder.itemView.getResources().getString(
                R.string.accessibility_floating_button_action_double_tap_to_toggle)
                : null;
        ViewCompat.replaceAccessibilityAction(holder.itemView,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                clickHint, /* command= */ null);
    }

    @ItemType
    @Override
    public int getItemViewType(int position) {
        // This LAST_ITEM condition should be checked before others to ensure proper padding when
        // adding a second target via notifyItemInserted().
        if (position == (getItemCount() - 1)) {
            return ItemType.LAST_ITEM;
        }

        if (position == 0) {
            return ItemType.FIRST_ITEM;
        }

        return ItemType.REGULAR_ITEM;
    }

    @Override
    public int getItemCount() {
        return mTargets.size();
    }

    public void setIconWidthHeight(int iconWidthHeight) {
        mIconWidthHeight = iconWidthHeight;
    }

    public void setItemPadding(int itemPadding) {
        mItemPadding = itemPadding;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View mIconView;

        ViewHolder(View itemView) {
            super(itemView);
            mIconView = itemView.findViewById(R.id.icon_view);
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

        void updateItemPadding(int padding, int size) {
            itemView.setPaddingRelative(padding, padding, padding, 0);
        }
    }

    static class TopViewHolder extends ViewHolder {
        TopViewHolder(View itemView) {
            super(itemView);
        }

        @Override
        void updateItemPadding(int padding, int size) {
            final int paddingBottom = size <= 1 ? padding : 0;
            itemView.setPaddingRelative(padding, padding, padding, paddingBottom);
        }
    }

    static class BottomViewHolder extends ViewHolder {
        BottomViewHolder(View itemView) {
            super(itemView);
        }

        @Override
        void updateItemPadding(int padding, int size) {
            itemView.setPaddingRelative(padding, padding, padding, padding);
        }
    }
}
