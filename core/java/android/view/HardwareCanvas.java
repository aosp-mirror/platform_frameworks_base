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
     * @return {@link DisplayList#STATUS_DREW} if anything was drawn (such as a call to clear
     * the canvas).
     */
    public abstract int onPreDraw(Rect dirty);

    /**
     * Invoked after all drawing operation have been performed.
     */
    public abstract void onPostDraw();

    /**
     * Draws the specified display list onto this canvas.
     *
     * @param displayList The display list to replay.
     * @param dirty The dirty region to redraw in the next pass, matters only
     *        if this method returns true, can be null.
     * @param flags Optional flags about drawing, see {@link DisplayList} for
     *              the possible flags.
     *
     * @return One of {@link DisplayList#STATUS_DONE}, {@link DisplayList#STATUS_DRAW}, or
     *         {@link DisplayList#STATUS_INVOKE}, or'd with {@link DisplayList#STATUS_DREW}
     *         if anything was drawn.
     */
    public abstract int drawDisplayList(DisplayList displayList, Rect dirty, int flags);

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
     *                       
     * @return One of {@link DisplayList#STATUS_DONE}, {@link DisplayList#STATUS_DRAW} or
     *         {@link DisplayList#STATUS_INVOKE}
     */
    public int callDrawGLFunction(int drawGLFunction) {
        // Noop - this is done in the display list recorder subclass
        return DisplayList.STATUS_DONE;
    }

    /**
     * Invoke all the functors who requested to be invoked during the previous frame.
     * 
     * @param dirty The region to redraw when the functors return {@link DisplayList#STATUS_DRAW}
     *              
     * @return One of {@link DisplayList#STATUS_DONE}, {@link DisplayList#STATUS_DRAW} or
     *         {@link DisplayList#STATUS_INVOKE}
     */
    public int invokeFunctors(Rect dirty) {
        return DisplayList.STATUS_DONE;
    }

    /**
     * Detaches the specified functor from the current functor execution queue.
     *
     * @param functor The native functor to remove from the execution queue.
     *
     * @see #invokeFunctors(android.graphics.Rect)
     * @see #callDrawGLFunction(int)
     * @see #detachFunctor(int) 
     */
    abstract void detachFunctor(int functor);

    /**
     * Attaches the specified functor to the current functor execution queue.
     *
     * @param functor The native functor to add to the execution queue.
     *
     * @see #invokeFunctors(android.graphics.Rect)
     * @see #callDrawGLFunction(int)
     * @see #detachFunctor(int) 
     */
    abstract void attachFunctor(int functor);
}
