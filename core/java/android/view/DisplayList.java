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

import android.graphics.Matrix;

/**
 * <p>A display list records a series of graphics related operations and can replay
 * them later. Display lists are usually built by recording operations on a
 * {@link HardwareCanvas}. Replaying the operations from a display list avoids
 * executing application code on every frame, and is thus much more efficient.</p>
 *
 * <p>Display lists are used internally for all views by default, and are not
 * typically used directly. One reason to consider using a display is a custom
 * {@link View} implementation that needs to issue a large number of drawing commands.
 * When the view invalidates, all the drawing commands must be reissued, even if
 * large portions of the drawing command stream stay the same frame to frame, which
 * can become a performance bottleneck. To solve this issue, a custom View might split
 * its content into several display lists. A display list is updated only when its
 * content, and only its content, needs to be updated.</p>
 *
 * <p>A text editor might for instance store each paragraph into its own display list.
 * Thus when the user inserts or removes characters, only the display list of the
 * affected paragraph needs to be recorded again.</p>
 *
 * <h3>Hardware acceleration</h3>
 * <p>Display lists can only be replayed using a {@link HardwareCanvas}. They are not
 * supported in software. Always make sure that the {@link android.graphics.Canvas}
 * you are using to render a display list is hardware accelerated using
 * {@link android.graphics.Canvas#isHardwareAccelerated()}.</p>
 *
 * <h3>Creating a display list</h3>
 * <pre class="prettyprint">
 *     HardwareRenderer renderer = myView.getHardwareRenderer();
 *     if (renderer != null) {
 *         DisplayList displayList = renderer.createDisplayList();
 *         HardwareCanvas canvas = displayList.start(width, height);
 *         try {
 *             // Draw onto the canvas
 *             // For instance: canvas.drawBitmap(...);
 *         } finally {
 *             displayList.end();
 *         }
 *     }
 * </pre>
 *
 * <h3>Rendering a display list on a View</h3>
 * <pre class="prettyprint">
 *     protected void onDraw(Canvas canvas) {
 *         if (canvas.isHardwareAccelerated()) {
 *             HardwareCanvas hardwareCanvas = (HardwareCanvas) canvas;
 *             hardwareCanvas.drawDisplayList(mDisplayList);
 *         }
 *     }
 * </pre>
 *
 * <h3>Releasing resources</h3>
 * <p>This step is not mandatory but recommended if you want to release resources
 * held by a display list as soon as possible.</p>
 * <pre class="prettyprint">
 *     // Mark this display list invalid, it cannot be used for drawing anymore,
 *     // and release resources held by this display list
 *     displayList.clear();
 * </pre>
 *
 * <h3>Properties</h3>
 * <p>In addition, a display list offers several properties, such as
 * {@link #setScaleX(float)} or {@link #setLeft(int)}, that can be used to affect all
 * the drawing commands recorded within. For instance, these properties can be used
 * to move around a large number of images without re-issuing all the individual
 * <code>drawBitmap()</code> calls.</p>
 *
 * <pre class="prettyprint">
 *     private void createDisplayList() {
 *         HardwareRenderer renderer = getHardwareRenderer();
 *         if (renderer != null) {
 *             mDisplayList = renderer.createDisplayList();
 *             HardwareCanvas canvas = mDisplayList.start(width, height);
 *             try {
 *                 for (Bitmap b : mBitmaps) {
 *                     canvas.drawBitmap(b, 0.0f, 0.0f, null);
 *                     canvas.translate(0.0f, b.getHeight());
 *                 }
 *             } finally {
 *                 displayList.end();
 *             }
 *         }
 *     }
 *
 *     protected void onDraw(Canvas canvas) {
 *         if (canvas.isHardwareAccelerated()) {
 *             HardwareCanvas hardwareCanvas = (HardwareCanvas) canvas;
 *             hardwareCanvas.drawDisplayList(mDisplayList);
 *         }
 *     }
 *
 *     private void moveContentBy(int x) {
 *          // This will move all the bitmaps recorded inside the display list
 *          // by x pixels to the right and redraw this view. All the commands
 *          // recorded in createDisplayList() won't be re-issued, only onDraw()
 *          // will be invoked and will execute very quickly
 *          mDisplayList.offsetLeftAndRight(x);
 *          invalidate();
 *     }
 * </pre>
 *
 * <h3>Threading</h3>
 * <p>Display lists must be created on and manipulated from the UI thread only.</p>
 *
 * @hide
 */
public abstract class DisplayList {
    private boolean mDirty;

    /**
     * Flag used when calling
     * {@link HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)} 
     * When this flag is set, draw operations lying outside of the bounds of the
     * display list will be culled early. It is recommeneded to always set this
     * flag.
     *
     * @hide
     */
    public static final int FLAG_CLIP_CHILDREN = 0x1;

    // NOTE: The STATUS_* values *must* match the enum in DrawGlInfo.h

    /**
     * Indicates that the display list is done drawing.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DONE = 0x0;

    /**
     * Indicates that the display list needs another drawing pass.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DRAW = 0x1;

    /**
     * Indicates that the display list needs to re-execute its GL functors.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int) 
     * @see HardwareCanvas#callDrawGLFunction(int)
     *
     * @hide
     */
    public static final int STATUS_INVOKE = 0x2;

    /**
     * Indicates that the display list performed GL drawing operations.
     *
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DREW = 0x4;

    /**
     * Starts recording the display list. All operations performed on the
     * returned canvas are recorded and stored in this display list.
     *
     * Calling this method will mark the display list invalid until
     * {@link #end()} is called. Only valid display lists can be replayed.
     *
     * @param width The width of the display list's viewport
     * @param height The height of the display list's viewport
     *
     * @return A canvas to record drawing operations.
     *
     * @see #end()
     * @see #isValid()
     */
    public abstract HardwareCanvas start(int width, int height);

    /**
     * Ends the recording for this display list. A display list cannot be
     * replayed if recording is not finished. Calling this method marks
     * the display list valid and {@link #isValid()} will return true.
     *
     * @see #start(int, int)
     * @see #isValid()
     */
    public abstract void end();

    /**
     * Clears resources held onto by this display list. After calling this method
     * {@link #isValid()} will return false.
     *
     * @see #isValid()
     * @see #reset()
     */
    public abstract void clear();


    /**
     * Reset native resources. This is called when cleaning up the state of display lists
     * during destruction of hardware resources, to ensure that we do not hold onto
     * obsolete resources after related resources are gone.
     *
     * @see #clear()
     *
     * @hide
     */
    public abstract void reset();

    /**
     * Sets the dirty flag. When a display list is dirty, {@link #clear()} should
     * be invoked whenever possible.
     *
     * @see #isDirty()
     * @see #clear()
     *
     * @hide
     */
    public void markDirty() {
        mDirty = true;
    }

    /**
     * Removes the dirty flag. This method can be used to cancel a cleanup
     * previously scheduled by setting the dirty flag.
     *
     * @see #isDirty()
     * @see #clear()
     *
     * @hide
     */
    protected void clearDirty() {
        mDirty = false;
    }

    /**
     * Indicates whether the display list is dirty.
     *
     * @see #markDirty()
     * @see #clear()
     *
     * @hide
     */
    public boolean isDirty() {
        return mDirty;
    }

    /**
     * Returns whether the display list is currently usable. If this returns false,
     * the display list should be re-recorded prior to replaying it.
     *
     * @return boolean true if the display list is able to be replayed, false otherwise.
     */
    public abstract boolean isValid();

    /**
     * Return the amount of memory used by this display list.
     * 
     * @return The size of this display list in bytes
     *
     * @hide
     */
    public abstract int getSize();

    ///////////////////////////////////////////////////////////////////////////
    // DisplayList Property Setters
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Set the caching property on the display list, which indicates whether the display list
     * holds a layer. Layer display lists should avoid creating an alpha layer, since alpha is
     * handled in the drawLayer operation directly (and more efficiently).
     *
     * @param caching true if the display list represents a hardware layer, false otherwise.
     *
     * @hide
     */
    public abstract void setCaching(boolean caching);

    /**
     * Set whether the display list should clip itself to its bounds. This property is controlled by
     * the view's parent.
     *
     * @param clipToBounds true if the display list should clip to its bounds
     */
    public abstract void setClipToBounds(boolean clipToBounds);

    /**
     * Set the static matrix on the display list. The specified matrix is combined with other
     * transforms (such as {@link #setScaleX(float)}, {@link #setRotation(float)}, etc.)
     *
     * @param matrix A transform matrix to apply to this display list
     *
     * @see #getMatrix(android.graphics.Matrix)
     * @see #getMatrix()
     */
    public abstract void setMatrix(Matrix matrix);

    /**
     * Returns the static matrix set on this display list.
     *
     * @return A new {@link Matrix} instance populated with this display list's static
     *         matrix
     *
     * @see #getMatrix(android.graphics.Matrix)
     * @see #setMatrix(android.graphics.Matrix)
     */
    public Matrix getMatrix() {
        return getMatrix(new Matrix());
    }

    /**
     * Copies this display list's static matrix into the specified matrix.
     *
     * @param matrix The {@link Matrix} instance in which to copy this display
     *               list's static matrix. Cannot be null
     *
     * @return The <code>matrix</code> parameter, for convenience
     *
     * @see #getMatrix()
     * @see #setMatrix(android.graphics.Matrix)
     */
    public abstract Matrix getMatrix(Matrix matrix);

    /**
     * Set the Animation matrix on the display list. This matrix exists if an Animation is
     * currently playing on a View, and is set on the display list during at draw() time. When
     * the Animation finishes, the matrix should be cleared by sending <code>null</code>
     * for the matrix parameter.
     *
     * @param matrix The matrix, null indicates that the matrix should be cleared.
     *
     * @hide
     */
    public abstract void setAnimationMatrix(Matrix matrix);

    /**
     * Sets the translucency level for the display list.
     *
     * @param alpha The translucency of the display list, must be a value between 0.0f and 1.0f
     *
     * @see View#setAlpha(float)
     * @see #getAlpha()
     */
    public abstract void setAlpha(float alpha);

    /**
     * Returns the translucency level of this display list.
     *
     * @return A value between 0.0f and 1.0f
     *
     * @see #setAlpha(float)
     */
    public abstract float getAlpha();

    /**
     * Sets whether the display list renders content which overlaps. Non-overlapping rendering
     * can use a fast path for alpha that avoids rendering to an offscreen buffer. By default
     * display lists consider they do not have overlapping content.
     *
     * @param hasOverlappingRendering False if the content is guaranteed to be non-overlapping,
     *                                true otherwise.
     *
     * @see android.view.View#hasOverlappingRendering()
     * @see #hasOverlappingRendering()
     */
    public abstract void setHasOverlappingRendering(boolean hasOverlappingRendering);

    /**
     * Indicates whether the content of this display list overlaps.
     *
     * @return True if this display list renders content which overlaps, false otherwise.
     *
     * @see #setHasOverlappingRendering(boolean)
     */
    public abstract boolean hasOverlappingRendering();

    /**
     * Sets the translation value for the display list on the X axis
     *
     * @param translationX The X axis translation value of the display list, in pixels
     *
     * @see View#setTranslationX(float)
     * @see #getTranslationX()
     */
    public abstract void setTranslationX(float translationX);

    /**
     * Returns the translation value for this display list on the X axis, in pixels.
     *
     * @see #setTranslationX(float)
     */
    public abstract float getTranslationX();

    /**
     * Sets the translation value for the display list on the Y axis
     *
     * @param translationY The Y axis translation value of the display list, in pixels
     *
     * @see View#setTranslationY(float)
     * @see #getTranslationY()
     */
    public abstract void setTranslationY(float translationY);

    /**
     * Returns the translation value for this display list on the Y axis, in pixels.
     *
     * @see #setTranslationY(float)
     */
    public abstract float getTranslationY();

    /**
     * Sets the rotation value for the display list around the Z axis
     *
     * @param rotation The rotation value of the display list, in degrees
     *
     * @see View#setRotation(float)
     * @see #getRotation()
     */
    public abstract void setRotation(float rotation);

    /**
     * Returns the rotation value for this display list around the Z axis, in degrees.
     *
     * @see #setRotation(float)
     */
    public abstract float getRotation();

    /**
     * Sets the rotation value for the display list around the X axis
     *
     * @param rotationX The rotation value of the display list, in degrees
     *
     * @see View#setRotationX(float)
     * @see #getRotationX()
     */
    public abstract void setRotationX(float rotationX);

    /**
     * Returns the rotation value for this display list around the X axis, in degrees.
     *
     * @see #setRotationX(float)
     */
    public abstract float getRotationX();

    /**
     * Sets the rotation value for the display list around the Y axis
     *
     * @param rotationY The rotation value of the display list, in degrees
     *
     * @see View#setRotationY(float)
     * @see #getRotationY()
     */
    public abstract void setRotationY(float rotationY);

    /**
     * Returns the rotation value for this display list around the Y axis, in degrees.
     *
     * @see #setRotationY(float)
     */
    public abstract float getRotationY();

    /**
     * Sets the scale value for the display list on the X axis
     *
     * @param scaleX The scale value of the display list
     *
     * @see View#setScaleX(float)
     * @see #getScaleX()
     */
    public abstract void setScaleX(float scaleX);

    /**
     * Returns the scale value for this display list on the X axis.
     *
     * @see #setScaleX(float)
     */
    public abstract float getScaleX();

    /**
     * Sets the scale value for the display list on the Y axis
     *
     * @param scaleY The scale value of the display list
     *
     * @see View#setScaleY(float)
     * @see #getScaleY()
     */
    public abstract void setScaleY(float scaleY);

    /**
     * Returns the scale value for this display list on the Y axis.
     *
     * @see #setScaleY(float)
     */
    public abstract float getScaleY();

    /**
     * Sets all of the transform-related values of the display list
     *
     * @param alpha The alpha value of the display list
     * @param translationX The translationX value of the display list
     * @param translationY The translationY value of the display list
     * @param rotation The rotation value of the display list
     * @param rotationX The rotationX value of the display list
     * @param rotationY The rotationY value of the display list
     * @param scaleX The scaleX value of the display list
     * @param scaleY The scaleY value of the display list
     *
     * @hide
     */
    public abstract void setTransformationInfo(float alpha, float translationX, float translationY,
            float rotation, float rotationX, float rotationY, float scaleX, float scaleY);

    /**
     * Sets the pivot value for the display list on the X axis
     *
     * @param pivotX The pivot value of the display list on the X axis, in pixels
     *
     * @see View#setPivotX(float)
     * @see #getPivotX()
     */
    public abstract void setPivotX(float pivotX);

    /**
     * Returns the pivot value for this display list on the X axis, in pixels.
     *
     * @see #setPivotX(float)
     */
    public abstract float getPivotX();

    /**
     * Sets the pivot value for the display list on the Y axis
     *
     * @param pivotY The pivot value of the display list on the Y axis, in pixels
     *
     * @see View#setPivotY(float)
     * @see #getPivotY()
     */
    public abstract void setPivotY(float pivotY);

    /**
     * Returns the pivot value for this display list on the Y axis, in pixels.
     *
     * @see #setPivotY(float)
     */
    public abstract float getPivotY();

    /**
     * Sets the camera distance for the display list. Refer to
     * {@link View#setCameraDistance(float)} for more information on how to
     * use this property.
     *
     * @param distance The distance in Z of the camera of the display list
     *
     * @see View#setCameraDistance(float)
     * @see #getCameraDistance()
     */
    public abstract void setCameraDistance(float distance);

    /**
     * Returns the distance in Z of the camera of the display list.
     *
     * @see #setCameraDistance(float)
     */
    public abstract float getCameraDistance();

    /**
     * Sets the left position for the display list.
     *
     * @param left The left position, in pixels, of the display list
     *
     * @see View#setLeft(int)
     * @see #getLeft()
     */
    public abstract void setLeft(int left);

    /**
     * Returns the left position for the display list in pixels.
     *
     * @see #setLeft(int)
     */
    public abstract float getLeft();

    /**
     * Sets the top position for the display list.
     *
     * @param top The top position, in pixels, of the display list
     *
     * @see View#setTop(int)
     * @see #getTop()
     */
    public abstract void setTop(int top);

    /**
     * Returns the top position for the display list in pixels.
     *
     * @see #setTop(int)
     */
    public abstract float getTop();

    /**
     * Sets the right position for the display list.
     *
     * @param right The right position, in pixels, of the display list
     *
     * @see View#setRight(int)
     * @see #getRight()
     */
    public abstract void setRight(int right);

    /**
     * Returns the right position for the display list in pixels.
     *
     * @see #setRight(int)
     */
    public abstract float getRight();

    /**
     * Sets the bottom position for the display list.
     *
     * @param bottom The bottom position, in pixels, of the display list
     *
     * @see View#setBottom(int)
     * @see #getBottom()
     */
    public abstract void setBottom(int bottom);

    /**
     * Returns the bottom position for the display list in pixels.
     *
     * @see #setBottom(int)
     */
    public abstract float getBottom();

    /**
     * Sets the left and top positions for the display list
     *
     * @param left The left position of the display list, in pixels
     * @param top The top position of the display list, in pixels
     * @param right The right position of the display list, in pixels
     * @param bottom The bottom position of the display list, in pixels
     *
     * @see View#setLeft(int)
     * @see View#setTop(int)
     * @see View#setRight(int)
     * @see View#setBottom(int)
     */
    public abstract void setLeftTopRightBottom(int left, int top, int right, int bottom);

    /**
     * Offsets the left and right positions for the display list
     *
     * @param offset The amount that the left and right positions of the display
     *               list are offset, in pixels
     *
     * @see View#offsetLeftAndRight(int)
     */
    public abstract void offsetLeftAndRight(float offset);

    /**
     * Offsets the top and bottom values for the display list
     *
     * @param offset The amount that the top and bottom positions of the display
     *               list are offset, in pixels
     *
     * @see View#offsetTopAndBottom(int)
     */
    public abstract void offsetTopAndBottom(float offset);
}
