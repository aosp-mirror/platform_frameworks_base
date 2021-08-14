/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import android.view.View;
import android.widget.ImageView;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

/**
 * View holder for the quick access wallet card.
 */
class WalletCardViewHolder extends RecyclerView.ViewHolder {

    final CardView mCardView;
    final ImageView mImageView;
    WalletCardViewInfo mCardViewInfo;

    WalletCardViewHolder(View view) {
        super(view);
        mCardView = view.requireViewById(R.id.card);
        mImageView = mCardView.requireViewById(R.id.card_image);
    }

}
