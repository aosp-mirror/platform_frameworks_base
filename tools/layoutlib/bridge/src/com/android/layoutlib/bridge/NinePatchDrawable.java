/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import com.android.ninepatch.NinePatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

public class NinePatchDrawable extends Drawable {

    private NinePatch m9Patch;

    NinePatchDrawable(NinePatch ninePatch) {
        m9Patch = ninePatch;
    }
    
    @Override
    public int getMinimumWidth() {
        return m9Patch.getWidth();
    }
    
    @Override
    public int getMinimumHeight() {
        return m9Patch.getHeight();
    }
    
    /**
     * Return the intrinsic width of the underlying drawable object.  Returns
     * -1 if it has no intrinsic width, such as with a solid color.
     */
    @Override
    public int getIntrinsicWidth() {
        return m9Patch.getWidth();
    }

    /**
     * Return the intrinsic height of the underlying drawable object. Returns
     * -1 if it has no intrinsic height, such as with a solid color.
     */
    @Override
    public int getIntrinsicHeight() {
        return m9Patch.getHeight();
    }
    
    /**
     * Return in padding the insets suggested by this Drawable for placing
     * content inside the drawable's bounds. Positive values move toward the
     * center of the Drawable (set Rect.inset). Returns true if this drawable
     * actually has a padding, else false. When false is returned, the padding
     * is always set to 0.
     */
    @Override
    public boolean getPadding(Rect padding) {
        int[] padd = new int[4];
        m9Patch.getPadding(padd);
        padding.left = padd[0];
        padding.top = padd[1];
        padding.right = padd[2];
        padding.bottom = padd[3];
        return true;
    }
    
    @Override
    public void draw(Canvas canvas) {
        if (canvas instanceof BridgeCanvas) {
            BridgeCanvas bridgeCanvas = (BridgeCanvas)canvas;
            
            Rect r = getBounds();
            m9Patch.draw(bridgeCanvas.getGraphics2d(), r.left, r.top, r.width(), r.height());
            
            return;
        }

        throw new UnsupportedOperationException();
    }

    
    // ----------- Not implemented methods ---------------
    

    @Override
    public int getOpacity() {
        // FIXME
        return 0xFF;
    }

    @Override
    public void setAlpha(int arg0) {
        // FIXME !
    }

    @Override
    public void setColorFilter(ColorFilter arg0) {
        // FIXME
    }
}
