/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Hardware accelerated canvas.
 *
 * @hide 
 */
public abstract class HardwareCanvas extends Canvas {
    @Override
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        throw new UnsupportedOperationException();
    }
    
    /**
     * Invoked before any drawing operation is performed in this canvas.
     * 
     * @param dirty The dirty rectangle to update, can be null.
     */
    public abstract void onPreDraw(Rect dirty);

    /**
     * Invoked after all drawing operation have been performed.
     */
    public abstract void onPostDraw();
    
    /**
     * Draws the specified display list onto this canvas.
     * 
     * @param displayList The display list to replay.
     * @param width The width of the display list.
     * @param height The height of the display list.
     * @param dirty The dirty region to redraw in the next pass, matters only
     *        if this method returns true, can be null.
     * 
     * @return True if the content of the display list requires another
     *         drawing pass (invalidate()), false otherwise
     */
    public abstract boolean drawDisplayList(DisplayList displayList, int width, int height, Rect dirty);

    /**
     * Outputs the specified display list to the log. This method exists for use by
     * tools to output display lists for selected nodes to the log.
     *
     * @param displayList The display list to be logged.
     */
    abstract void outputDisplayList(DisplayList displayList);

    /**
     * Draws the specified layer onto this canvas.
     *
     * @param layer The layer to composite on this canvas
     * @param x The left coordinate of the layer
     * @param y The top coordinate of the layer
     * @param paint The paint used to draw the layer
     */
    abstract void drawHardwareLayer(HardwareLayer layer, float x, float y, Paint paint);

    /**
     * Calls the function specified with the drawGLFunction function pointer. This is
     * functionality used by webkit for calling into their renderer from our display lists.
     * This function may return true if an invalidation is needed after the call.
     *
     * @param drawGLFunction A native function pointer
     * @return true if an invalidate is needed after the call, false otherwise
     */
    public boolean callDrawGLFunction(int drawGLFunction) {
        // Noop - this is done in the display list recorder subclass
        return false;
    }
}
