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

package com.android.systemui.statusbar.phone;

import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

/** Creates a scaled-up version of an app icon for dragging. */
class AppIconDragShadowBuilder extends View.DragShadowBuilder {
    private final static int ICON_SCALE = 2;
    final Drawable mDrawable;
    final int mIconSize;  // Height and width in device-pixels.

    public AppIconDragShadowBuilder(ImageView icon) {
        mDrawable = icon.getDrawable();
        // The Drawable may not be the same size as the ImageView, so use the ImageView size.
        // The ImageView is not square because it has additional left and right padding to create
        // a wider drop target, so use the height to create a square drag shadow.
        mIconSize = icon.getHeight() * ICON_SCALE;
    }

    @Override
    public void onProvideShadowMetrics(Point size, Point touch) {
        size.set(mIconSize, mIconSize);
        // Shift the drag shadow up slightly because the apps are at the bottom edge of the
        // screen.
        touch.set(mIconSize / 2, mIconSize * 2 / 3);
    }

    @Override
    public void onDrawShadow(Canvas canvas) {
        // The Drawable's native bounds may be different than the source ImageView. Force it
        // to the correct size.
        Rect oldBounds = mDrawable.copyBounds();
        mDrawable.setBounds(0, 0, mIconSize, mIconSize);
        mDrawable.draw(canvas);
        mDrawable.setBounds(oldBounds);
    }
}
