/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import static com.android.internal.widget.RecyclerView.NO_ID;

import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.RingtoneManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The adapter presents a list of ringtones which may include fixed item in the list and an action
 * button at the end.
 *
 * The adapter handles three different types of items:
 * <ul>
 * <li>FIXED: Fixed items are items added to the top of the list. These items can not be modified
 * and their position will never change.
 * <li>DYNAMIC: Dynamic items are items from the ringtone manager. These items can be modified
 * and their position can change.
 * <li>FOOTER: A footer item is an added button to the end of the list. This item can be clicked
 * but not selected and its position will never change.
 * </ul>
 */
final class RingtoneListViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FIXED_ITEM = 0;
    private static final int VIEW_TYPE_DYNAMIC_ITEM = 1;
    private static final int VIEW_TYPE_ADD_RINGTONE_ITEM = 2;
    private final Cursor mCursor;
    private final List<Integer> mFixedItemTitles;
    private final Callbacks mCallbacks;
    private final int mRowIDColumn;
    private int mSelectedItem = -1;
    @StringRes private Integer mAddRingtoneItemTitle;

    /** Provides callbacks for the adapter. */
    interface Callbacks {
        void onRingtoneSelected(int position);
        void onAddRingtoneSelected();
        boolean isWorkRingtone(int position);
        Drawable getWorkIconDrawable();
    }

    RingtoneListViewAdapter(Cursor cursor,
            Callbacks callbacks) {
        mCursor = cursor;
        mCallbacks = callbacks;
        mFixedItemTitles = new ArrayList<>();
        mRowIDColumn = mCursor != null ? mCursor.getColumnIndex("_id") : -1;
        setHasStableIds(true);
    }

    void setSelectedItem(int position) {
        notifyItemChanged(mSelectedItem);
        mSelectedItem = position;
        notifyItemChanged(mSelectedItem);
    }

    /**
     * Adds title to the fixed items list and returns the index of the newest added item.
     * @param textResId the title to add to the fixed items list.
     * @return The index of the newest added item in the fixed items list.
     */
    int addTitleForFixedItem(@StringRes int textResId) {
        mFixedItemTitles.add(textResId);
        notifyItemInserted(mFixedItemTitles.size() - 1);
        return mFixedItemTitles.size() - 1;
    }

    void addTitleForAddRingtoneItem(@StringRes int textResId) {
        mAddRingtoneItemTitle = textResId;
        notifyItemInserted(getItemCount() - 1);
    }

    @NotNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == VIEW_TYPE_FIXED_ITEM) {
            View fixedItemView = inflater.inflate(
                    com.android.internal.R.layout.select_dialog_singlechoice_material, parent,
                    false);

            return new FixedItemViewHolder(fixedItemView, mCallbacks);
        }

        if (viewType == VIEW_TYPE_ADD_RINGTONE_ITEM) {
            View addRingtoneItemView = inflater.inflate(R.layout.add_new_sound_item, parent, false);

            return new AddRingtoneItemViewHolder(addRingtoneItemView,
                    mCallbacks);
        }

        View view = inflater.inflate(R.layout.radio_with_work_badge, parent, false);

        return new DynamicItemViewHolder(view, mCallbacks);
    }

    @Override
    public void onBindViewHolder(@NotNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FixedItemViewHolder) {
            FixedItemViewHolder viewHolder = (FixedItemViewHolder) holder;

            viewHolder.onBind(mFixedItemTitles.get(position),
                    /* isChecked= */ position == mSelectedItem);
            return;
        }
        if (holder instanceof AddRingtoneItemViewHolder) {
            AddRingtoneItemViewHolder viewHolder = (AddRingtoneItemViewHolder) holder;

            viewHolder.onBind(mAddRingtoneItemTitle);
            return;
        }

        if (!(holder instanceof DynamicItemViewHolder)) {
            throw new IllegalArgumentException("holder type is not supported");
        }

        DynamicItemViewHolder viewHolder = (DynamicItemViewHolder) holder;
        int pos = position - mFixedItemTitles.size();
        if (!mCursor.moveToPosition(pos)) {
            throw new IllegalStateException("Could not move cursor to position: " + pos);
        }

        Drawable workIcon = (mCallbacks != null)
                && mCallbacks.isWorkRingtone(position)
                ? mCallbacks.getWorkIconDrawable() : null;

        viewHolder.onBind(mCursor.getString(RingtoneManager.TITLE_COLUMN_INDEX),
                /* isChecked= */ position == mSelectedItem, workIcon);
    }

    @Override
    public int getItemViewType(int position) {
        if (!mFixedItemTitles.isEmpty() && position < mFixedItemTitles.size()) {
            return VIEW_TYPE_FIXED_ITEM;
        }
        if (mAddRingtoneItemTitle != null && position == getItemCount() - 1) {
            return VIEW_TYPE_ADD_RINGTONE_ITEM;
        }

        return VIEW_TYPE_DYNAMIC_ITEM;
    }

    @Override
    public int getItemCount() {
        int itemCount = mFixedItemTitles.size() + mCursor.getCount();

        if (mAddRingtoneItemTitle != null) {
            itemCount++;
        }

        return itemCount;
    }

    @Override
    public long getItemId(int position) {
        int itemViewType = getItemViewType(position);
        if (itemViewType == VIEW_TYPE_FIXED_ITEM) {
            // Since the item is a fixed item, then we can use the position as a stable ID
            // since the order of the fixed items should never change.
            return position;
        }
        if (itemViewType == VIEW_TYPE_DYNAMIC_ITEM && mCursor != null
                && mCursor.moveToPosition(position - mFixedItemTitles.size())
                && mRowIDColumn != -1) {
            return mCursor.getLong(mRowIDColumn) + mFixedItemTitles.size();
        }

        // The position is either invalid or the item is the add ringtone item view, so no stable
        // ID is returned. Add ringtone item view cannot be selected and only include an action
        // buttons.
        return NO_ID;
    }

    private static class DynamicItemViewHolder extends RecyclerView.ViewHolder {
        private final CheckedTextView mTitleTextView;
        private final ImageView mWorkIcon;

        DynamicItemViewHolder(View itemView, Callbacks listener) {
            super(itemView);

            mTitleTextView = itemView.requireViewById(R.id.checked_text_view);
            mWorkIcon = itemView.requireViewById(R.id.work_icon);
            itemView.setOnClickListener(v -> listener.onRingtoneSelected(this.getLayoutPosition()));
        }

        void onBind(String title, boolean isChecked, Drawable workIcon) {
            mTitleTextView.setText(title);
            mTitleTextView.setChecked(isChecked);

            if (workIcon == null) {
                mWorkIcon.setVisibility(View.GONE);
            } else {
                mWorkIcon.setImageDrawable(workIcon);
                mWorkIcon.setVisibility(View.VISIBLE);
            }
        }
    }

    private static class FixedItemViewHolder extends RecyclerView.ViewHolder {
        private final CheckedTextView mTitleTextView;

        FixedItemViewHolder(View itemView, Callbacks listener) {
            super(itemView);

            mTitleTextView = (CheckedTextView) itemView;
            itemView.setOnClickListener(v -> listener.onRingtoneSelected(this.getLayoutPosition()));
        }

        void onBind(@StringRes int title, boolean isChecked) {
            Objects.requireNonNull(mTitleTextView);

            mTitleTextView.setText(title);
            mTitleTextView.setChecked(isChecked);
        }
    }

    private static class AddRingtoneItemViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitleTextView;

        AddRingtoneItemViewHolder(View itemView, Callbacks listener) {
            super(itemView);

            mTitleTextView = itemView.requireViewById(R.id.add_new_sound_text);
            itemView.setOnClickListener(v -> listener.onAddRingtoneSelected());
        }

        void onBind(@StringRes int title) {
            mTitleTextView.setText(title);
        }
    }
}
