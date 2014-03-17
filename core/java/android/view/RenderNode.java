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
import android.graphics.Path;

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
 *         mDisplayList = DisplayList.create("MyDisplayList");
 *         HardwareCanvas canvas = mDisplayList.start(width, height);
 *         try {
 *             for (Bitmap b : mBitmaps) {
 *                 canvas.drawBitmap(b, 0.0f, 0.0f, null);
 *                 canvas.translate(0.0f, b.getHeight());
 *             }
 *         } finally {
 *             displayList.end();
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
public class RenderNode {
    /**
     * Flag used when calling
     * {@link HardwareCanvas#drawDisplayList(RenderNode, android.graphics.Rect, int)}
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
     * @see HardwareCanvas#drawDisplayList(RenderNode, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DONE = 0x0;

    /**
     * Indicates that the display list needs another drawing pass.
     *
     * @see HardwareCanvas#drawDisplayList(RenderNode, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DRAW = 0x1;

    /**
     * Indicates that the display list needs to re-execute its GL functors.
     *
     * @see HardwareCanvas#drawDisplayList(RenderNode, android.graphics.Rect, int)
     * @see HardwareCanvas#callDrawGLFunction(long)
     *
     * @hide
     */
    public static final int STATUS_INVOKE = 0x2;

    /**
     * Indicates that the display list performed GL drawing operations.
     *
     * @see HardwareCanvas#drawDisplayList(RenderNode, android.graphics.Rect, int)
     *
     * @hide
     */
    public static final int STATUS_DREW = 0x4;

    private boolean mValid;
    private final long mNativeDisplayList;
    private HardwareRenderer mRenderer;

    private RenderNode(String name) {
        mNativeDisplayList = nCreate();
        nSetDisplayListName(mNativeDisplayList, name);
    }

    /**
     * Creates a new display list that can be used to record batches of
     * drawing operations.
     *
     * @param name The name of the display list, used for debugging purpose. May be null.
     *
     * @return A new display list.
     *
     * @hide
     */
    public static RenderNode create(String name) {
        return new RenderNode(name);
    }

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
    public HardwareCanvas start(int width, int height) {
        HardwareCanvas canvas = GLES20RecordingCanvas.obtain();
        canvas.setViewport(width, height);
        // The dirty rect should always be null for a display list
        canvas.onPreDraw(null);
        return canvas;
    }

    /**
     * Ends the recording for this display list. A display list cannot be
     * replayed if recording is not finished. Calling this method marks
     * the display list valid and {@link #isValid()} will return true.
     *
     * @see #start(int, int)
     * @see #isValid()
     */
    public void end(HardwareRenderer renderer, HardwareCanvas endCanvas) {
        if (!(endCanvas instanceof GLES20RecordingCanvas)) {
            throw new IllegalArgumentException("Passed an invalid canvas to end!");
        }

        GLES20RecordingCanvas canvas = (GLES20RecordingCanvas) endCanvas;
        canvas.onPostDraw();
        long displayListData = canvas.finishRecording();
        if (renderer != mRenderer) {
            // If we are changing renderers first destroy with the old
            // renderer, then set with the new one
            destroyDisplayListData();
        }
        mRenderer = renderer;
        setDisplayListData(displayListData);
        canvas.recycle();
        mValid = true;
    }

    /**
     * Reset native resources. This is called when cleaning up the state of display lists
     * during destruction of hardware resources, to ensure that we do not hold onto
     * obsolete resources after related resources are gone.
     *
     * @hide
     */
    public void destroyDisplayListData() {
        if (!mValid) return;

        setDisplayListData(0);
        mRenderer = null;
        mValid = false;
    }

    private void setDisplayListData(long newData) {
        if (mRenderer != null) {
            mRenderer.setDisplayListData(mNativeDisplayList, newData);
        } else {
            throw new IllegalStateException("Trying to set data without a renderer! data=" + newData);
        }
    }

    /**
     * Returns whether the display list is currently usable. If this returns false,
     * the display list should be re-recorded prior to replaying it.
     *
     * @return boolean true if the display list is able to be replayed, false otherwise.
     */
    public boolean isValid() { return mValid; }

    long getNativeDisplayList() {
        if (!mValid) {
            throw new IllegalStateException("The display list is not valid.");
        }
        return mNativeDisplayList;
    }

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
    public void setCaching(boolean caching) {
        nSetCaching(mNativeDisplayList, caching);
    }

    /**
     * Set whether the display list should clip itself to its bounds. This property is controlled by
     * the view's parent.
     *
     * @param clipToBounds true if the display list should clip to its bounds
     */
    public void setClipToBounds(boolean clipToBounds) {
        nSetClipToBounds(mNativeDisplayList, clipToBounds);
    }

    /**
     * Set whether the display list should collect and Z order all 3d composited descendents, and
     * draw them in order with the default Z=0 content.
     *
     * @param isolatedZVolume true if the display list should collect and Z order descendents.
     */
    public void setIsolatedZVolume(boolean isolatedZVolume) {
        nSetIsolatedZVolume(mNativeDisplayList, isolatedZVolume);
    }

    /**
     * Sets whether the display list should be drawn immediately after the
     * closest ancestor display list where isolateZVolume is true. If the
     * display list itself satisfies this constraint, changing this attribute
     * has no effect on drawing order.
     *
     * @param shouldProject true if the display list should be projected onto a
     *            containing volume.
     */
    public void setProjectBackwards(boolean shouldProject) {
        nSetProjectBackwards(mNativeDisplayList, shouldProject);
    }

    /**
     * Sets whether the display list is a projection receiver - that its parent
     * DisplayList should draw any descendent DisplayLists with
     * ProjectBackwards=true directly on top of it. Default value is false.
     */
    public void setProjectionReceiver(boolean shouldRecieve) {
        nSetProjectionReceiver(mNativeDisplayList, shouldRecieve);
    }

    /**
     * Sets the outline, defining the shape that casts a shadow, and the path to
     * be clipped if setClipToOutline is set.
     *
     * Deep copies the native path to simplify reference ownership.
     *
     * @param outline Convex, CW Path to store in the DisplayList. May be null.
     */
    public void setOutline(Path outline) {
        long nativePath = (outline == null) ? 0 : outline.mNativePath;
        nSetOutline(mNativeDisplayList, nativePath);
    }

    /**
     * Enables or disables clipping to the outline.
     *
     * @param clipToOutline true if clipping to the outline.
     */
    public void setClipToOutline(boolean clipToOutline) {
        nSetClipToOutline(mNativeDisplayList, clipToOutline);
    }

    /**
     * Set whether the DisplayList should cast a shadow.
     *
     * The shape of the shadow casting area is defined by the outline of the display list, if set
     * and non-empty, otherwise it will be the bounds rect.
     */
    public void setCastsShadow(boolean castsShadow) {
        nSetCastsShadow(mNativeDisplayList, castsShadow);
    }

    /**
     * Sets whether the DisplayList should be drawn with perspective applied from the global camera.
     *
     * If set to true, camera distance will be ignored. Defaults to false.
     */
    public void setUsesGlobalCamera(boolean usesGlobalCamera) {
        nSetUsesGlobalCamera(mNativeDisplayList, usesGlobalCamera);
    }

    /**
     * Set the static matrix on the display list. The specified matrix is combined with other
     * transforms (such as {@link #setScaleX(float)}, {@link #setRotation(float)}, etc.)
     *
     * @param matrix A transform matrix to apply to this display list
     *
     * @see #getMatrix(android.graphics.Matrix)
     * @see #getMatrix()
     */
    public void setStaticMatrix(Matrix matrix) {
        nSetStaticMatrix(mNativeDisplayList, matrix.native_instance);
    }

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
    public void setAnimationMatrix(Matrix matrix) {
        nSetAnimationMatrix(mNativeDisplayList,
                (matrix != null) ? matrix.native_instance : 0);
    }

    /**
     * Sets the translucency level for the display list.
     *
     * @param alpha The translucency of the display list, must be a value between 0.0f and 1.0f
     *
     * @see View#setAlpha(float)
     * @see #getAlpha()
     */
    public void setAlpha(float alpha) {
        nSetAlpha(mNativeDisplayList, alpha);
    }

    /**
     * Returns the translucency level of this display list.
     *
     * @return A value between 0.0f and 1.0f
     *
     * @see #setAlpha(float)
     */
    public float getAlpha() {
        return nGetAlpha(mNativeDisplayList);
    }

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
    public void setHasOverlappingRendering(boolean hasOverlappingRendering) {
        nSetHasOverlappingRendering(mNativeDisplayList, hasOverlappingRendering);
    }

    /**
     * Indicates whether the content of this display list overlaps.
     *
     * @return True if this display list renders content which overlaps, false otherwise.
     *
     * @see #setHasOverlappingRendering(boolean)
     */
    public boolean hasOverlappingRendering() {
        //noinspection SimplifiableIfStatement
        return nHasOverlappingRendering(mNativeDisplayList);
    }

    /**
     * Sets the translation value for the display list on the X axis.
     *
     * @param translationX The X axis translation value of the display list, in pixels
     *
     * @see View#setTranslationX(float)
     * @see #getTranslationX()
     */
    public void setTranslationX(float translationX) {
        nSetTranslationX(mNativeDisplayList, translationX);
    }

    /**
     * Returns the translation value for this display list on the X axis, in pixels.
     *
     * @see #setTranslationX(float)
     */
    public float getTranslationX() {
        return nGetTranslationX(mNativeDisplayList);
    }

    /**
     * Sets the translation value for the display list on the Y axis.
     *
     * @param translationY The Y axis translation value of the display list, in pixels
     *
     * @see View#setTranslationY(float)
     * @see #getTranslationY()
     */
    public void setTranslationY(float translationY) {
        nSetTranslationY(mNativeDisplayList, translationY);
    }

    /**
     * Returns the translation value for this display list on the Y axis, in pixels.
     *
     * @see #setTranslationY(float)
     */
    public float getTranslationY() {
        return nGetTranslationY(mNativeDisplayList);
    }

    /**
     * Sets the translation value for the display list on the Z axis.
     *
     * @see View#setTranslationZ(float)
     * @see #getTranslationZ()
     */
    public void setTranslationZ(float translationZ) {
        nSetTranslationZ(mNativeDisplayList, translationZ);
    }

    /**
     * Returns the translation value for this display list on the Z axis.
     *
     * @see #setTranslationZ(float)
     */
    public float getTranslationZ() {
        return nGetTranslationZ(mNativeDisplayList);
    }

    /**
     * Sets the rotation value for the display list around the Z axis.
     *
     * @param rotation The rotation value of the display list, in degrees
     *
     * @see View#setRotation(float)
     * @see #getRotation()
     */
    public void setRotation(float rotation) {
        nSetRotation(mNativeDisplayList, rotation);
    }

    /**
     * Returns the rotation value for this display list around the Z axis, in degrees.
     *
     * @see #setRotation(float)
     */
    public float getRotation() {
        return nGetRotation(mNativeDisplayList);
    }

    /**
     * Sets the rotation value for the display list around the X axis.
     *
     * @param rotationX The rotation value of the display list, in degrees
     *
     * @see View#setRotationX(float)
     * @see #getRotationX()
     */
    public void setRotationX(float rotationX) {
        nSetRotationX(mNativeDisplayList, rotationX);
    }

    /**
     * Returns the rotation value for this display list around the X axis, in degrees.
     *
     * @see #setRotationX(float)
     */
    public float getRotationX() {
        return nGetRotationX(mNativeDisplayList);
    }

    /**
     * Sets the rotation value for the display list around the Y axis.
     *
     * @param rotationY The rotation value of the display list, in degrees
     *
     * @see View#setRotationY(float)
     * @see #getRotationY()
     */
    public void setRotationY(float rotationY) {
        nSetRotationY(mNativeDisplayList, rotationY);
    }

    /**
     * Returns the rotation value for this display list around the Y axis, in degrees.
     *
     * @see #setRotationY(float)
     */
    public float getRotationY() {
        return nGetRotationY(mNativeDisplayList);
    }

    /**
     * Sets the scale value for the display list on the X axis.
     *
     * @param scaleX The scale value of the display list
     *
     * @see View#setScaleX(float)
     * @see #getScaleX()
     */
    public void setScaleX(float scaleX) {
        nSetScaleX(mNativeDisplayList, scaleX);
    }

    /**
     * Returns the scale value for this display list on the X axis.
     *
     * @see #setScaleX(float)
     */
    public float getScaleX() {
        return nGetScaleX(mNativeDisplayList);
    }

    /**
     * Sets the scale value for the display list on the Y axis.
     *
     * @param scaleY The scale value of the display list
     *
     * @see View#setScaleY(float)
     * @see #getScaleY()
     */
    public void setScaleY(float scaleY) {
        nSetScaleY(mNativeDisplayList, scaleY);
    }

    /**
     * Returns the scale value for this display list on the Y axis.
     *
     * @see #setScaleY(float)
     */
    public float getScaleY() {
        return nGetScaleY(mNativeDisplayList);
    }

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
    public void setTransformationInfo(float alpha,
            float translationX, float translationY, float translationZ,
            float rotation, float rotationX, float rotationY, float scaleX, float scaleY) {
        nSetTransformationInfo(mNativeDisplayList, alpha,
                translationX, translationY, translationZ,
                rotation, rotationX, rotationY, scaleX, scaleY);
    }

    /**
     * Sets the pivot value for the display list on the X axis
     *
     * @param pivotX The pivot value of the display list on the X axis, in pixels
     *
     * @see View#setPivotX(float)
     * @see #getPivotX()
     */
    public void setPivotX(float pivotX) {
        nSetPivotX(mNativeDisplayList, pivotX);
    }

    /**
     * Returns the pivot value for this display list on the X axis, in pixels.
     *
     * @see #setPivotX(float)
     */
    public float getPivotX() {
        return nGetPivotX(mNativeDisplayList);
    }

    /**
     * Sets the pivot value for the display list on the Y axis
     *
     * @param pivotY The pivot value of the display list on the Y axis, in pixels
     *
     * @see View#setPivotY(float)
     * @see #getPivotY()
     */
    public void setPivotY(float pivotY) {
        nSetPivotY(mNativeDisplayList, pivotY);
    }

    /**
     * Returns the pivot value for this display list on the Y axis, in pixels.
     *
     * @see #setPivotY(float)
     */
    public float getPivotY() {
        return nGetPivotY(mNativeDisplayList);
    }

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
    public void setCameraDistance(float distance) {
        nSetCameraDistance(mNativeDisplayList, distance);
    }

    /**
     * Returns the distance in Z of the camera of the display list.
     *
     * @see #setCameraDistance(float)
     */
    public float getCameraDistance() {
        return nGetCameraDistance(mNativeDisplayList);
    }

    /**
     * Sets the left position for the display list.
     *
     * @param left The left position, in pixels, of the display list
     *
     * @see View#setLeft(int)
     * @see #getLeft()
     */
    public void setLeft(int left) {
        nSetLeft(mNativeDisplayList, left);
    }

    /**
     * Returns the left position for the display list in pixels.
     *
     * @see #setLeft(int)
     */
    public float getLeft() {
        return nGetLeft(mNativeDisplayList);
    }

    /**
     * Sets the top position for the display list.
     *
     * @param top The top position, in pixels, of the display list
     *
     * @see View#setTop(int)
     * @see #getTop()
     */
    public void setTop(int top) {
        nSetTop(mNativeDisplayList, top);
    }

    /**
     * Returns the top position for the display list in pixels.
     *
     * @see #setTop(int)
     */
    public float getTop() {
        return nGetTop(mNativeDisplayList);
    }

    /**
     * Sets the right position for the display list.
     *
     * @param right The right position, in pixels, of the display list
     *
     * @see View#setRight(int)
     * @see #getRight()
     */
    public void setRight(int right) {
        nSetRight(mNativeDisplayList, right);
    }

    /**
     * Returns the right position for the display list in pixels.
     *
     * @see #setRight(int)
     */
    public float getRight() {
        return nGetRight(mNativeDisplayList);
    }

    /**
     * Sets the bottom position for the display list.
     *
     * @param bottom The bottom position, in pixels, of the display list
     *
     * @see View#setBottom(int)
     * @see #getBottom()
     */
    public void setBottom(int bottom) {
        nSetBottom(mNativeDisplayList, bottom);
    }

    /**
     * Returns the bottom position for the display list in pixels.
     *
     * @see #setBottom(int)
     */
    public float getBottom() {
        return nGetBottom(mNativeDisplayList);
    }

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
    public void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        nSetLeftTopRightBottom(mNativeDisplayList, left, top, right, bottom);
    }

    /**
     * Offsets the left and right positions for the display list
     *
     * @param offset The amount that the left and right positions of the display
     *               list are offset, in pixels
     *
     * @see View#offsetLeftAndRight(int)
     */
    public void offsetLeftAndRight(float offset) {
        nOffsetLeftAndRight(mNativeDisplayList, offset);
    }

    /**
     * Offsets the top and bottom values for the display list
     *
     * @param offset The amount that the top and bottom positions of the display
     *               list are offset, in pixels
     *
     * @see View#offsetTopAndBottom(int)
     */
    public void offsetTopAndBottom(float offset) {
        nOffsetTopAndBottom(mNativeDisplayList, offset);
    }

    /**
     * Outputs the display list to the log. This method exists for use by
     * tools to output display lists for selected nodes to the log.
     *
     * @hide
     */
    public void output() {
        nOutput(mNativeDisplayList);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Native methods
    ///////////////////////////////////////////////////////////////////////////

    private static native long nCreate();
    private static native void nDestroyDisplayList(long displayList);
    private static native void nSetDisplayListName(long displayList, String name);

    // Properties

    private static native void nOffsetTopAndBottom(long displayList, float offset);
    private static native void nOffsetLeftAndRight(long displayList, float offset);
    private static native void nSetLeftTopRightBottom(long displayList, int left, int top,
            int right, int bottom);
    private static native void nSetBottom(long displayList, int bottom);
    private static native void nSetRight(long displayList, int right);
    private static native void nSetTop(long displayList, int top);
    private static native void nSetLeft(long displayList, int left);
    private static native void nSetCameraDistance(long displayList, float distance);
    private static native void nSetPivotY(long displayList, float pivotY);
    private static native void nSetPivotX(long displayList, float pivotX);
    private static native void nSetCaching(long displayList, boolean caching);
    private static native void nSetClipToBounds(long displayList, boolean clipToBounds);
    private static native void nSetProjectBackwards(long displayList, boolean shouldProject);
    private static native void nSetProjectionReceiver(long displayList, boolean shouldRecieve);
    private static native void nSetIsolatedZVolume(long displayList, boolean isolateZVolume);
    private static native void nSetOutline(long displayList, long nativePath);
    private static native void nSetClipToOutline(long displayList, boolean clipToOutline);
    private static native void nSetCastsShadow(long displayList, boolean castsShadow);
    private static native void nSetUsesGlobalCamera(long displayList, boolean usesGlobalCamera);
    private static native void nSetAlpha(long displayList, float alpha);
    private static native void nSetHasOverlappingRendering(long displayList,
            boolean hasOverlappingRendering);
    private static native void nSetTranslationX(long displayList, float translationX);
    private static native void nSetTranslationY(long displayList, float translationY);
    private static native void nSetTranslationZ(long displayList, float translationZ);
    private static native void nSetRotation(long displayList, float rotation);
    private static native void nSetRotationX(long displayList, float rotationX);
    private static native void nSetRotationY(long displayList, float rotationY);
    private static native void nSetScaleX(long displayList, float scaleX);
    private static native void nSetScaleY(long displayList, float scaleY);
    private static native void nSetTransformationInfo(long displayList, float alpha,
            float translationX, float translationY, float translationZ,
            float rotation, float rotationX, float rotationY, float scaleX, float scaleY);
    private static native void nSetStaticMatrix(long displayList, long nativeMatrix);
    private static native void nSetAnimationMatrix(long displayList, long animationMatrix);

    private static native boolean nHasOverlappingRendering(long displayList);
    private static native float nGetAlpha(long displayList);
    private static native float nGetLeft(long displayList);
    private static native float nGetTop(long displayList);
    private static native float nGetRight(long displayList);
    private static native float nGetBottom(long displayList);
    private static native float nGetCameraDistance(long displayList);
    private static native float nGetScaleX(long displayList);
    private static native float nGetScaleY(long displayList);
    private static native float nGetTranslationX(long displayList);
    private static native float nGetTranslationY(long displayList);
    private static native float nGetTranslationZ(long displayList);
    private static native float nGetRotation(long displayList);
    private static native float nGetRotationX(long displayList);
    private static native float nGetRotationY(long displayList);
    private static native float nGetPivotX(long displayList);
    private static native float nGetPivotY(long displayList);
    private static native void nOutput(long displayList);

    ///////////////////////////////////////////////////////////////////////////
    // Finalization
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void finalize() throws Throwable {
        try {
            destroyDisplayListData();
            nDestroyDisplayList(mNativeDisplayList);
        } finally {
            super.finalize();
        }
    }
}
