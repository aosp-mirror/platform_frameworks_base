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
package com.android.internal.widget.remotecompose.player.platform;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;

/**
 * An implementation of PaintContext for the Android Canvas.
 * This is used to play the RemoteCompose operations on Android.
 */
public class AndroidPaintContext extends PaintContext {
    Paint mPaint = new Paint();
    Canvas mCanvas;

    public AndroidPaintContext(RemoteContext context, Canvas canvas) {
        super(context);
        this.mCanvas = canvas;
    }

    public Canvas getCanvas() {
        return mCanvas;
    }

    public void setCanvas(Canvas canvas) {
        this.mCanvas = canvas;
    }

    /**
     * Draw an image onto the canvas
     *
     * @param imageId   the id of the image
     * @param srcLeft   left coordinate of the source area
     * @param srcTop    top coordinate of the source area
     * @param srcRight  right coordinate of the source area
     * @param srcBottom bottom coordinate of the source area
     * @param dstLeft   left coordinate of the destination area
     * @param dstTop    top coordinate of the destination area
     * @param dstRight  right coordinate of the destination area
     * @param dstBottom bottom coordinate of the destination area
     */

    @Override
    public void drawBitmap(int imageId,
                           int srcLeft,
                           int srcTop,
                           int srcRight,
                           int srcBottom,
                           int dstLeft,
                           int dstTop,
                           int dstRight,
                           int dstBottom,
                           int cdId) {
        AndroidRemoteContext androidContext = (AndroidRemoteContext) mContext;
        if (androidContext.mRemoteComposeState.containsId(imageId)) {
            Bitmap bitmap = (Bitmap) androidContext.mRemoteComposeState.getFromId(imageId);
            mCanvas.drawBitmap(
                    bitmap,
                    new Rect(srcLeft, srcTop, srcRight, srcBottom),
                    new Rect(dstLeft, dstTop, dstRight, dstBottom), mPaint
            );
        }
    }

    @Override
    public void scale(float scaleX, float scaleY) {
        mCanvas.scale(scaleX, scaleY);
    }

    @Override
    public void translate(float translateX, float translateY) {
        mCanvas.translate(translateX, translateY);
    }
}

