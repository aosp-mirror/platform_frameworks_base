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

package com.android.settingslib.users;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.R;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeHelper;

/**
 * Activity to allow the user to choose a user profile picture.
 */
public class AvatarPickerActivity extends Activity {

    static final String EXTRA_AVATAR_INDEX = "avatar_index";

    private FooterButton mDoneButton;
    private AvatarAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.trySetDynamicColor(this);
        setContentView(R.layout.avatar_picker);

        GlifLayout glifLayout = findViewById(R.id.glif_layout);
        RecyclerView recyclerView = findViewById(R.id.avatar_grid);
        FooterBarMixin mixin = glifLayout.getMixin(FooterBarMixin.class);

        FooterButton secondaryButton =
                new FooterButton.Builder(this)
                        .setText("Cancel")
                        .setListener(view -> cancel())
                        .build();

        mDoneButton =
                new FooterButton.Builder(this)
                        .setText("Done")
                        .setListener(view -> confirmSelection())
                        .build();
        mDoneButton.setEnabled(false);

        mixin.setSecondaryButton(secondaryButton);
        mixin.setPrimaryButton(mDoneButton);

        mAdapter = new AvatarAdapter();
        recyclerView.setAdapter(mAdapter);
        recyclerView.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.avatar_picker_columns)));
    }

    private void confirmSelection() {
        Intent data = new Intent();
        data.putExtra(EXTRA_AVATAR_INDEX, mAdapter.indexFromPosition(mAdapter.mSelectedPosition));
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private class AvatarAdapter extends RecyclerView.Adapter<AvatarViewHolder> {

        private static final int NONE = -1;
        private static final int AVATAR_START_POSITION = 0;

        private final TypedArray mImageDrawables;
        private int mSelectedPosition = NONE;

        AvatarAdapter() {
            mImageDrawables = getResources().obtainTypedArray(R.array.avatar_images);
        }

        @NonNull
        @Override
        public AvatarViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
            View itemView = layoutInflater.inflate(R.layout.avatar_item, parent, false);
            return new AvatarViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull AvatarViewHolder viewHolder, int position) {
            Drawable drawable = mImageDrawables.getDrawable(indexFromPosition(position));
            if (drawable instanceof BitmapDrawable) {
                drawable = circularDrawableFrom((BitmapDrawable) drawable);
            } else {
                throw new IllegalStateException("Avatar drawables must be bitmaps");
            }
            viewHolder.setSelected(position == mSelectedPosition);
            viewHolder.setDrawable(drawable);
            viewHolder.setClickListener(view -> {
                if (mSelectedPosition == position) {
                    deselect(position);
                } else {
                    select(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return AVATAR_START_POSITION + mImageDrawables.length();
        }

        private Drawable circularDrawableFrom(BitmapDrawable drawable) {
            Bitmap bitmap = drawable.getBitmap();

            RoundedBitmapDrawable roundedBitmapDrawable =
                    RoundedBitmapDrawableFactory.create(getResources(), bitmap);
            roundedBitmapDrawable.setCircular(true);

            return roundedBitmapDrawable;
        }

        private int indexFromPosition(int position) {
            return position - AVATAR_START_POSITION;
        }

        private void select(int position) {
            final int oldSelection = mSelectedPosition;
            mSelectedPosition = position;
            notifyItemChanged(position);
            if (oldSelection != NONE) {
                notifyItemChanged(oldSelection);
            } else {
                mDoneButton.setEnabled(true);
            }
        }

        private void deselect(int position) {
            mSelectedPosition = NONE;
            notifyItemChanged(position);
            mDoneButton.setEnabled(false);
        }
    }

    private static class AvatarViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mImageView;

        AvatarViewHolder(View view) {
            super(view);
            mImageView = view.findViewById(R.id.avatar_image);
        }

        public void setDrawable(Drawable drawable) {
            mImageView.setImageDrawable(drawable);
        }

        public void setClickListener(View.OnClickListener listener) {
            mImageView.setOnClickListener(listener);
        }

        public void setSelected(boolean selected) {
            mImageView.setSelected(selected);
        }
    }
}
