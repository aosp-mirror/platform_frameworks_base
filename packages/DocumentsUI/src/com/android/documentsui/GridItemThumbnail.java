/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Ensures that grid thumbnails are always square.
 */
public class GridItemThumbnail extends ImageView {
    public GridItemThumbnail(Context context) {
        super(context);
    }

    public GridItemThumbnail(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GridItemThumbnail(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Grid layout uses item width to figure out the number of columns, then dynamically fits
        // rows into the view. The upshot of this is that changing the item width will mess up the
        // grid layout - so to make the items square, throw out the height and use the width for
        // both dimensions. The grid layout will correctly adjust the row height.
        //
        // Note that this code will need to be changed if the layout manager's orientation is
        // changed from VERTICAL to HORIZONTAL.
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
