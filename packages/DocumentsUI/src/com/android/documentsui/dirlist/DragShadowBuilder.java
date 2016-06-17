/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.documentsui.R;

final class DragShadowBuilder extends View.DragShadowBuilder {

    private final View mShadowView;
    private final TextView mTitle;
    private final ImageView mIcon;
    private final int mWidth;
    private final int mHeight;

    public DragShadowBuilder(Context context, String title, Drawable icon) {
        mWidth = context.getResources().getDimensionPixelSize(R.dimen.drag_shadow_width);
        mHeight= context.getResources().getDimensionPixelSize(R.dimen.drag_shadow_height);

        mShadowView = LayoutInflater.from(context).inflate(R.layout.drag_shadow_layout, null);
        mTitle = (TextView) mShadowView.findViewById(android.R.id.title);
        mIcon = (ImageView) mShadowView.findViewById(android.R.id.icon);

        mTitle.setText(title);
        mIcon.setImageDrawable(icon);
    }

    @Override
    public void onProvideShadowMetrics(
            Point shadowSize, Point shadowTouchPoint) {
        shadowSize.set(mWidth, mHeight);
        shadowTouchPoint.set(mWidth, mHeight);
    }

    @Override
    public void onDrawShadow(Canvas canvas) {
        Rect r = canvas.getClipBounds();
        // Calling measure is necessary in order for all child views to get correctly laid out.
        mShadowView.measure(
                View.MeasureSpec.makeMeasureSpec(r.right- r.left, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(r.top- r.bottom, View.MeasureSpec.EXACTLY));
        mShadowView.layout(r.left, r.top, r.right, r.bottom);
        mShadowView.draw(canvas);
    }
}
