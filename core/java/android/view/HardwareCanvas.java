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
import android.graphics.CanvasProperty;
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
     * @return {@link RenderNode#STATUS_DREW} if anything was drawn (such as a call to clear
     *         the canvas).
     *
     * @hide
     */
    public abstract int onPreDraw(Rect dirty);

    /**
     * Invoked after all drawing operation have been performed.
     *
     * @hide
     */
    public abstract void onPostDraw();

    /**
     * Draws the specified display list onto this canvas. The display list can only
     * be drawn if {@link android.view.RenderNode#isValid()} returns true.
     *
     * @param renderNode The RenderNode to replay.
     */
    public void drawRenderNode(RenderNode renderNode) {
        drawRenderNode(renderNode, null, RenderNode.FLAG_CLIP_CHILDREN);
    }

    /**
     * Draws the specified display list onto this canvas.
     *
     * @param renderNode The RenderNode to replay.
     * @param dirty Ignored, can be null.
     * @param flags Optional flags about drawing, see {@link RenderNode} for
     *              the possible flags.
     *
     * @return One of {@link RenderNode#STATUS_DONE} or {@link RenderNode#STATUS_DREW}
     *         if anything was drawn.
     *
     * @hide
     */
    public abstract int drawRenderNode(RenderNode renderNode, Rect dirty, int flags);

    /**
     * Draws the specified layer onto this canvas.
     *
     * @param layer The layer to composite on this canvas
     * @param x The left coordinate of the layer
     * @param y The top coordinate of the layer
     * @param paint The paint used to draw the layer
     *
     * @hide
     */
    abstract void drawHardwareLayer(HardwareLayer layer, float x, float y, Paint paint);

    /**
     * Calls the function specified with the drawGLFunction function pointer. This is
     * functionality used by webkit for calling into their renderer from our display lists.
     * This function may return true if an invalidation is needed after the call.
     *
     * @param drawGLFunction A native function pointer
     *
     * @return {@link RenderNode#STATUS_DONE}
     *
     * @hide
     */
    public int callDrawGLFunction(long drawGLFunction) {
        // Noop - this is done in the display list recorder subclass
        return RenderNode.STATUS_DONE;
    }

    public abstract void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint);

    public abstract void drawRoundRect(CanvasProperty<Float> left, CanvasProperty<Float> top,
            CanvasProperty<Float> right, CanvasProperty<Float> bottom,
            CanvasProperty<Float> rx, CanvasProperty<Float> ry,
            CanvasProperty<Paint> paint);

    public static void setProperty(String name, String value) {
        GLES20Canvas.setProperty(name, value);
    }
}
