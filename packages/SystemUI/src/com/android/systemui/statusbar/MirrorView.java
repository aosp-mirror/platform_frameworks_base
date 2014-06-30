/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * A view that mirrors the visual contents of another one. Should be used for animation purposes
 * only, as this view doesn't have any input handling.
 */
public class MirrorView extends View {

    private View mView;
    private int mFixedWidth;
    private int mFixedHeight;

    public MirrorView(Context context) {
        super(context);
    }

    public void setMirroredView(View v, int width, int height) {
        mView = v;
        mFixedWidth = width;
        mFixedHeight = height;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mView != null) {
            setMeasuredDimension(mFixedWidth, mFixedHeight);
        } else {
            setMeasuredDimension(0, 0);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mView != null) {
            mView.draw(canvas);
        }
    }
}
