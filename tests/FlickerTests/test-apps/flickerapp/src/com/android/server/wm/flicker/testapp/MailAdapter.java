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

package com.android.server.wm.flicker.testapp;

import static androidx.navigation.fragment.NavHostFragment.findNavController;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.Shape;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.recyclerview.widget.RecyclerView;

public class MailAdapter extends RecyclerView.Adapter<MailAdapter.ViewHolder> {

    private final Fragment mFragment;
    private final int mSize;

    static class BadgeShape extends OvalShape {
        Context mContext;
        String mLabel;

        BadgeShape(Context context, String label) {
            mContext = context;
            mLabel = label;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            final Resources resources = mContext.getResources();
            int textSize = resources.getDimensionPixelSize(R.dimen.icon_text_size);
            paint.setColor(Color.BLACK);
            super.draw(canvas, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(textSize);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fontMetrics = paint.getFontMetrics();
            float distance = (fontMetrics.bottom - fontMetrics.top) / 2 - fontMetrics.bottom;
            canvas.drawText(
                    mLabel,
                    rect().centerX(),
                    rect().centerY() + distance,
                    paint);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        NavController mNavController;
        ImageView mImageView;
        TextView mTextView;
        DisplayMetrics mDisplayMetrics;

        ViewHolder(@NonNull View itemView, NavController navController) {
            super(itemView);
            mNavController = navController;
            itemView.setOnClickListener(this::onClick);
            mImageView = itemView.findViewById(R.id.mail_row_item_icon);
            mTextView = itemView.findViewById(R.id.mail_row_item_text);
            mDisplayMetrics = itemView.getContext().getResources().getDisplayMetrics();
        }

        void onClick(View v) {
            mNavController.navigate(R.id.action_mail_list_to_mail_content);
        }

        public void setContent(int i) {
            final Resources resources = mImageView.getContext().getResources();
            final int badgeSize = resources.getDimensionPixelSize(R.dimen.icon_size);
            final char c = (char) ('A' + i % 26);
            final Shape badge = new BadgeShape(mImageView.getContext(), String.valueOf(c));
            ShapeDrawable drawable = new ShapeDrawable();
            drawable.setIntrinsicHeight(badgeSize);
            drawable.setIntrinsicWidth(badgeSize);
            drawable.setShape(badge);
            mImageView.setImageDrawable(drawable);
            mTextView.setText(String.format("%s-%04d", c, i));
        }
    }

    public MailAdapter(Fragment fragment, int size) {
        super();
        mFragment = fragment;
        mSize = size;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        View v = LayoutInflater
                .from(viewGroup.getContext())
                .inflate(R.layout.mail_row_item, viewGroup, false);
        return new ViewHolder(v, findNavController(mFragment));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, int i) {
        viewHolder.setContent(i);
    }

    @Override
    public int getItemCount() {
        return mSize;
    }
}
