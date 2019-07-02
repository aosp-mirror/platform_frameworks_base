/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench.leanback;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.Presenter;
import androidx.core.content.res.ResourcesCompat;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

public class CardPresenter extends Presenter {

    private int mImageWidth = 0;
    private int mImageHeight = 0;

    public CardPresenter(int width, int height) {
        mImageWidth = width;
        mImageHeight = height;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();
        ImageCardView v = new ImageCardView(context);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setMainImageAdjustViewBounds(true);
        v.setMainImageDimensions(mImageWidth, mImageHeight);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        PhotoItem photoItem = (PhotoItem) item;
        ImageCardView cardView = (ImageCardView) viewHolder.view;
        cardView.setTitleText(photoItem.getTitle());
        BitmapLoader.loadBitmap(cardView.getMainImageView(), photoItem.getId(),
                mImageWidth, mImageHeight);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageCardView cardView = (ImageCardView) viewHolder.view;
        BitmapLoader.cancel(cardView.getMainImageView());
    }
}
