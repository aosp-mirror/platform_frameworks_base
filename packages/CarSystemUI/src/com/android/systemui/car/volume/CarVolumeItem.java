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

package com.android.systemui.car.volume;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

/** Holds all related data to represent a volume group. */
public class CarVolumeItem {

    private Drawable mPrimaryIcon;
    private Drawable mSupplementalIcon;
    private View.OnClickListener mSupplementalIconOnClickListener;
    private boolean mShowSupplementalIconDivider;
    private int mGroupId;

    private int mMax;
    private int mProgress;
    private SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener;

    /**
     * Called when {@link CarVolumeItem} is bound to its ViewHolder.
     */
    void bind(CarVolumeItemViewHolder viewHolder) {
        viewHolder.bind(/* carVolumeItem= */ this);
    }

    /** Sets progress of seekbar. */
    public void setProgress(int progress) {
        mProgress = progress;
    }

    /** Sets max value of seekbar. */
    public void setMax(int max) {
        mMax = max;
    }

    /** Sets {@link SeekBar.OnSeekBarChangeListener}. */
    public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener listener) {
        mOnSeekBarChangeListener = listener;
    }

    /** Sets the primary icon. */
    public void setPrimaryIcon(Drawable drawable) {
        mPrimaryIcon = drawable;
    }

    /** Sets the supplemental icon and the visibility of the supplemental icon divider. */
    public void setSupplementalIcon(Drawable drawable, boolean showSupplementalIconDivider) {
        mSupplementalIcon = drawable;
        mShowSupplementalIconDivider = showSupplementalIconDivider;
    }

    /**
     * Gets the group id associated.
     */
    public int getGroupId() {
        return mGroupId;
    }

    /**
     * Sets the group id associated.
     */
    public void setGroupId(int groupId) {
        this.mGroupId = groupId;
    }

    /** Sets {@code OnClickListener} for the supplemental icon. */
    public void setSupplementalIconListener(View.OnClickListener listener) {
        mSupplementalIconOnClickListener = listener;
    }

    /** Defines the view holder which shows the information held by {@link CarVolumeItem}. */
    public static class CarVolumeItemViewHolder extends RecyclerView.ViewHolder {

        private SeekBar mSeekBar;
        private ImageView mPrimaryIcon;
        private View mSupplementalIconDivider;
        private ImageView mSupplementalIcon;

        public CarVolumeItemViewHolder(@NonNull View itemView) {
            super(itemView);

            mSeekBar = itemView.findViewById(R.id.seek_bar);
            mPrimaryIcon = itemView.findViewById(R.id.primary_icon);
            mSupplementalIcon = itemView.findViewById(R.id.supplemental_icon);
            mSupplementalIconDivider = itemView.findViewById(R.id.supplemental_icon_divider);
        }

        /**
         * Binds {@link CarVolumeItem} to the {@link CarVolumeItemViewHolder}.
         */
        void bind(CarVolumeItem carVolumeItem) {
            // Progress bar
            mSeekBar.setMax(carVolumeItem.mMax);
            mSeekBar.setProgress(carVolumeItem.mProgress);
            mSeekBar.setOnSeekBarChangeListener(carVolumeItem.mOnSeekBarChangeListener);

            // Primary icon
            mPrimaryIcon.setVisibility(View.VISIBLE);
            mPrimaryIcon.setImageDrawable(carVolumeItem.mPrimaryIcon);

            // Supplemental icon
            mSupplementalIcon.setVisibility(View.VISIBLE);
            mSupplementalIconDivider.setVisibility(
                    carVolumeItem.mShowSupplementalIconDivider ? View.VISIBLE : View.INVISIBLE);
            mSupplementalIcon.setImageDrawable(carVolumeItem.mSupplementalIcon);
            mSupplementalIcon.setOnClickListener(
                    carVolumeItem.mSupplementalIconOnClickListener);
            mSupplementalIcon.setClickable(
                    carVolumeItem.mSupplementalIconOnClickListener != null);
        }
    }
}
