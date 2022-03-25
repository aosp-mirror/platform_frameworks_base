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

package android.graphics;

import android.annotation.BytesLong;
import android.annotation.ColorInt;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.animation.RenderNodeAnimator;
import android.view.NativeVectorDrawableAnimator;
import android.view.Surface;
import android.view.View;

import com.android.internal.util.ArrayUtils;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * <p>RenderNode is used to build hardware accelerated rendering hierarchies. Each RenderNode
 * contains both a display list as well as a set of properties that affect the rendering of the
 * display list. RenderNodes are used internally for all Views by default and are not typically
 * used directly.</p>
 *
 * <p>RenderNodes are used to divide up the rendering content of a complex scene into smaller
 * pieces that can then be updated individually more cheaply. Updating part of the scene only needs
 * to update the display list or properties of a small number of RenderNode instead of redrawing
 * everything from scratch. A RenderNode only needs its display list re-recorded when its content
 * alone should be changed. RenderNodes can also be transformed without re-recording the display
 * list through the transform properties.</p>
 *
 * <p>A text editor might for instance store each paragraph into its own RenderNode.
 * Thus when the user inserts or removes characters, only the display list of the
 * affected paragraph needs to be recorded again.</p>
 *
 * <h3>Hardware acceleration</h3>
 * <p>RenderNodes can be drawn using a {@link RecordingCanvas}. They are not
 * supported in software. Always make sure that the {@link android.graphics.Canvas}
 * you are using to render a display list is hardware accelerated using
 * {@link android.graphics.Canvas#isHardwareAccelerated()}.</p>
 *
 * <h3>Creating a RenderNode</h3>
 * <pre class="prettyprint">
 *     RenderNode renderNode = new RenderNode("myRenderNode");
 *     renderNode.setPosition(0, 0, 50, 50); // Set the size to 50x50
 *     RecordingCanvas canvas = renderNode.beginRecording();
 *     try {
 *         // Draw with the canvas
 *         canvas.drawRect(...);
 *     } finally {
 *         renderNode.endRecording();
 *     }</pre>
 *
 * <h3>Drawing a RenderNode in a View</h3>
 * <pre class="prettyprint">
 *     protected void onDraw(Canvas canvas) {
 *         if (canvas.isHardwareAccelerated()) {
 *             // Check that the RenderNode has a display list, re-recording it if it does not.
 *             if (!myRenderNode.hasDisplayList()) {
 *                 updateDisplayList(myRenderNode);
 *             }
 *             // Draw the RenderNode into this canvas.
 *             canvas.drawRenderNode(myRenderNode);
 *         }
 *     }</pre>
 *
 * <h3>Releasing resources</h3>
 * <p>This step is not mandatory but recommended if you want to release resources
 * held by a display list as soon as possible. Most significantly any bitmaps it may contain.</p>
 * <pre class="prettyprint">
 *     // Discards the display list content allowing for any held resources to be released.
 *     // After calling this
 *     renderNode.discardDisplayList();</pre>
 *
 *
 * <h3>Properties</h3>
 * <p>In addition, a RenderNode offers several properties, such as
 * {@link #setScaleX(float)} or {@link #setTranslationX(float)}, that can be used to affect all
 * the drawing commands recorded within. For instance, these properties can be used
 * to move around a large number of images without re-issuing all the individual
 * <code>canvas.drawBitmap()</code> calls.</p>
 *
 * <pre class="prettyprint">
 *     private void createDisplayList() {
 *         mRenderNode = new RenderNode("MyRenderNode");
 *         mRenderNode.setPosition(0, 0, width, height);
 *         RecordingCanvas canvas = mRenderNode.beginRecording();
 *         try {
 *             for (Bitmap b : mBitmaps) {
 *                 canvas.drawBitmap(b, 0.0f, 0.0f, null);
 *                 canvas.translate(0.0f, b.getHeight());
 *             }
 *         } finally {
 *             mRenderNode.endRecording();
 *         }
 *     }
 *
 *     protected void onDraw(Canvas canvas) {
 *         if (canvas.isHardwareAccelerated())
 *             canvas.drawRenderNode(mRenderNode);
 *         }
 *     }
 *
 *     private void moveContentBy(int x) {
 *          // This will move all the bitmaps recorded inside the display list
 *          // by x pixels to the right and redraw this view. All the commands
 *          // recorded in createDisplayList() won't be re-issued, only onDraw()
 *          // will be invoked and will execute very quickly
 *          mRenderNode.offsetLeftAndRight(x);
 *          invalidate();
 *     }</pre>
 *
 * <p>A few of the properties may at first appear redundant, such as {@link #setElevation(float)}
 * and {@link #setTranslationZ(float)}. The reason for these duplicates are to allow for a
 * separation between static & transient usages. For example consider a button that raises from 2dp
 * to 8dp when pressed. To achieve that an application may decide to setElevation(2dip), and then
 * on press to animate setTranslationZ to 6dip. Combined this achieves the final desired 8dip
 * value, but the animation need only concern itself with animating the lift from press without
 * needing to know the initial starting value. {@link #setTranslationX(float)} and
 * {@link #setTranslationY(float)} are similarly provided for animation uses despite the functional
 * overlap with {@link #setPosition(Rect)}.
 *
 * <p>The RenderNode's transform matrix is computed at render time as follows:
 * <pre class="prettyprint">
 *     Matrix transform = new Matrix();
 *     transform.setTranslate(renderNode.getTranslationX(), renderNode.getTranslationY());
 *     transform.preRotate(renderNode.getRotationZ(),
 *             renderNode.getPivotX(), renderNode.getPivotY());
 *     transform.preScale(renderNode.getScaleX(), renderNode.getScaleY(),
 *             renderNode.getPivotX(), renderNode.getPivotY());</pre>
 * The current canvas transform matrix, which is translated to the RenderNode's position,
 * is then multiplied by the RenderNode's transform matrix. Therefore the ordering of calling
 * property setters does not affect the result. That is to say that:
 *
 * <pre class="prettyprint">
 *     renderNode.setTranslationX(100);
 *     renderNode.setScaleX(100);</pre>
 *
 * is equivalent to:
 *
 * <pre class="prettyprint">
 *     renderNode.setScaleX(100);
 *     renderNode.setTranslationX(100);</pre>
 *
 * <h3>Threading</h3>
 * <p>RenderNode may be created and used on any thread but they are not thread-safe. Only
 * a single thread may interact with a RenderNode at any given time. It is critical
 * that the RenderNode is only used on the same thread it is drawn with. For example when using
 * RenderNode with a custom View, then that RenderNode must only be used from the UI thread.</p>
 *
 * <h3>When to re-render</h3>
 * <p>Many of the RenderNode mutation methods, such as {@link #setTranslationX(float)}, return
 * a boolean indicating if the value actually changed or not. This is useful in detecting
 * if a new frame should be rendered or not. A typical usage would look like:
 * <pre class="prettyprint">
 *     public void translateTo(int x, int y) {
 *         boolean needsUpdate = myRenderNode.setTranslationX(x);
 *         needsUpdate |= myRenderNode.setTranslationY(y);
 *         if (needsUpdate) {
 *             myOwningView.invalidate();
 *         }
 *     }</pre>
 * This is marginally faster than doing a more explicit up-front check if the value changed by
 * comparing the desired value against {@link #getTranslationX()} as it minimizes JNI transitions.
 * The actual mechanism of requesting a new frame to be rendered will depend on how this
 * RenderNode is being drawn. If it's drawn to a containing View, as in the above snippet,
 * then simply invalidating that View works. If instead the RenderNode is being drawn to a Canvas
 * directly such as with {@link Surface#lockHardwareCanvas()} then a new frame needs to be drawn
 * by calling {@link Surface#lockHardwareCanvas()}, re-drawing the root RenderNode or whatever
 * top-level content is desired, and finally calling {@link Surface#unlockCanvasAndPost(Canvas)}.
 * </p>
 */
public final class RenderNode {

    // Use a Holder to allow static initialization in the boot image.
    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                RenderNode.class.getClassLoader(), nGetNativeFinalizer());
    }

    /**
     * Not for general use; use only if you are ThreadedRenderer or RecordingCanvas.
     *
     * @hide
     */
    public final long mNativeRenderNode;
    private final AnimationHost mAnimationHost;
    private RecordingCanvas mCurrentRecordingCanvas;

    // Will be null if not currently registered
    @Nullable
    private CompositePositionUpdateListener mCompositePositionUpdateListener;

    /**
     * Creates a new RenderNode that can be used to record batches of
     * drawing operations, and store / apply render properties when drawn.
     *
     * @param name The name of the RenderNode, used for debugging purpose. May be null.
     */
    public RenderNode(@Nullable String name) {
        this(name, null);
    }

    private RenderNode(String name, AnimationHost animationHost) {
        mNativeRenderNode = nCreate(name);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mNativeRenderNode);
        mAnimationHost = animationHost;
    }

    /**
     * @see RenderNode#adopt(long)
     */
    private RenderNode(long nativePtr) {
        mNativeRenderNode = nativePtr;
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mNativeRenderNode);
        mAnimationHost = null;
    }

    /** @hide */
    public static RenderNode create(String name, @Nullable AnimationHost animationHost) {
        return new RenderNode(name, animationHost);
    }

    /**
     * Adopts an existing native render node.
     *
     * Note: This will *NOT* incRef() on the native object, however it will
     * decRef() when it is destroyed. The caller should have already incRef'd it
     *
     * @hide
     */
    public static RenderNode adopt(long nativePtr) {
        return new RenderNode(nativePtr);
    }

    /**
     * Listens for RenderNode position updates for synchronous window movement.
     *
     * This is not suitable for generic position listening, it is only designed & intended
     * for use by things which require external position events like SurfaceView, PopupWindow, etc..
     *
     * @hide
     */
    public interface PositionUpdateListener {
        /**
         * Called by native by a Rendering Worker thread to update window position
         *
         * @hide
         */
        void positionChanged(long frameNumber, int left, int top, int right, int bottom);

        /**
         * Called by JNI
         *
         * @hide */
        static boolean callPositionChanged(WeakReference<PositionUpdateListener> weakListener,
                long frameNumber, int left, int top, int right, int bottom) {
            final PositionUpdateListener listener = weakListener.get();
            if (listener != null) {
                listener.positionChanged(frameNumber, left, top, right, bottom);
                return true;
            } else {
                return false;
            }
        }

        /**
         * Call to apply a stretch effect to any child SurfaceControl layers
         *
         * TODO: Fold this into positionChanged & have HWUI do the ASurfaceControl calls?
         *   (njawad) update to consume different stretch parameters for horizontal/vertical stretch
         *   to ensure SkiaGLRenderEngine can also apply the same stretch to a surface
         *
         * @hide
         */
        default void applyStretch(long frameNumber, float width, float height,
                float vecX, float vecY,
                float maxStretchX, float maxStretchY, float childRelativeLeft,
                float childRelativeTop, float childRelativeRight, float childRelativeBottom) { }

        /**
         * Called by JNI
         *
         * @hide */
        static boolean callApplyStretch(WeakReference<PositionUpdateListener> weakListener,
                long frameNumber, float width, float height,
                float vecX, float vecY,
                float maxStretchX, float maxStretchY, float childRelativeLeft,
                float childRelativeTop, float childRelativeRight, float childRelativeBottom) {
            final PositionUpdateListener listener = weakListener.get();
            if (listener != null) {
                listener.applyStretch(frameNumber, width, height, vecX, vecY, maxStretchX,
                        maxStretchY, childRelativeLeft, childRelativeTop, childRelativeRight,
                        childRelativeBottom);
                return true;
            } else {
                return false;
            }
        }

        /**
         * Called by native on RenderThread to notify that the view is no longer in the
         * draw tree. UI thread is blocked at this point.
         *
         * @hide
         */
        void positionLost(long frameNumber);

        /**
         * Called by JNI
         *
         * @hide */
        static boolean callPositionLost(WeakReference<PositionUpdateListener> weakListener,
                long frameNumber) {
            final PositionUpdateListener listener = weakListener.get();
            if (listener != null) {
                listener.positionLost(frameNumber);
                return true;
            } else {
                return false;
            }
        }

    }

    private static final class CompositePositionUpdateListener implements PositionUpdateListener {
        private final PositionUpdateListener[] mListeners;
        private static final PositionUpdateListener[] sEmpty = new PositionUpdateListener[0];

        CompositePositionUpdateListener(PositionUpdateListener... listeners) {
            mListeners = listeners != null ? listeners : sEmpty;
        }

        public CompositePositionUpdateListener with(PositionUpdateListener listener) {
            return new CompositePositionUpdateListener(
                    ArrayUtils.appendElement(PositionUpdateListener.class, mListeners, listener));
        }

        public CompositePositionUpdateListener without(PositionUpdateListener listener) {
            return new CompositePositionUpdateListener(
                    ArrayUtils.removeElement(PositionUpdateListener.class, mListeners, listener));
        }

        @Override
        public void positionChanged(long frameNumber, int left, int top, int right, int bottom) {
            for (PositionUpdateListener pul : mListeners) {
                pul.positionChanged(frameNumber, left, top, right, bottom);
            }
        }

        @Override
        public void positionLost(long frameNumber) {
            for (PositionUpdateListener pul : mListeners) {
                pul.positionLost(frameNumber);
            }
        }

        @Override
        public void applyStretch(long frameNumber, float width, float height,
                float vecX, float vecY, float maxStretchX, float maxStretchY, float childRelativeLeft,
                float childRelativeTop, float childRelativeRight, float childRelativeBottom) {
            for (PositionUpdateListener pul : mListeners) {
                pul.applyStretch(frameNumber, width, height, vecX, vecY, maxStretchX,
                        maxStretchY, childRelativeLeft, childRelativeTop, childRelativeRight,
                        childRelativeBottom);
            }
        }
    }

    /**
     * Enable callbacks for position changes. Call only from the UI thread or with
     * external synchronization.
     *
     * @hide
     */
    public void addPositionUpdateListener(@NonNull PositionUpdateListener listener) {
        CompositePositionUpdateListener comp = mCompositePositionUpdateListener;
        if (comp == null) {
            comp = new CompositePositionUpdateListener(listener);
        } else {
            comp = comp.with(listener);
        }
        mCompositePositionUpdateListener = comp;
        nRequestPositionUpdates(mNativeRenderNode, new WeakReference<>(comp));
    }

    /**
     * Disable a callback for position changes. Call only from the UI thread or with
     * external synchronization.
     *
     * @param listener Callback to remove
     * @hide
     */
    public void removePositionUpdateListener(@NonNull PositionUpdateListener listener) {
        CompositePositionUpdateListener comp = mCompositePositionUpdateListener;
        if (comp != null) {
            comp = comp.without(listener);
            mCompositePositionUpdateListener = comp;
            nRequestPositionUpdates(mNativeRenderNode, new WeakReference<>(comp));
        }
    }

    /**
     * Starts recording a display list for the render node. All
     * operations performed on the returned canvas are recorded and
     * stored in this display list.
     *
     * {@link #endRecording()} must be called when the recording is finished in order to apply
     * the updated display list. Failing to call {@link #endRecording()} will result in an
     * {@link IllegalStateException} if {@link #beginRecording(int, int)} is called again.
     *
     * @param width  The width of the recording viewport. This will not alter the width of the
     *               RenderNode itself, that must be set with {@link #setPosition(Rect)}.
     * @param height The height of the recording viewport. This will not alter the height of the
     *               RenderNode itself, that must be set with {@link #setPosition(Rect)}.
     * @return A canvas to record drawing operations.
     * @throws IllegalStateException If a recording is already in progress. That is, the previous
     * call to {@link #beginRecording(int, int)} did not call {@link #endRecording()}.
     * @see #endRecording()
     * @see #hasDisplayList()
     */
    public @NonNull RecordingCanvas beginRecording(int width, int height) {
        if (mCurrentRecordingCanvas != null) {
            throw new IllegalStateException(
                    "Recording currently in progress - missing #endRecording() call?");
        }
        mCurrentRecordingCanvas = RecordingCanvas.obtain(this, width, height);
        return mCurrentRecordingCanvas;
    }

    /**
     * Same as {@link #beginRecording(int, int)} with the width & height set
     * to the RenderNode's own width & height. The RenderNode's width & height may be set
     * with {@link #setPosition(int, int, int, int)}.
     *
     * @return A canvas to record drawing operations.
     * @throws IllegalStateException If a recording is already in progress. That is, the previous
     * call to {@link #beginRecording(int, int)} did not call {@link #endRecording()}.
     * @see #endRecording()
     * @see #hasDisplayList()
     */
    public @NonNull RecordingCanvas beginRecording() {
        return beginRecording(nGetWidth(mNativeRenderNode), nGetHeight(mNativeRenderNode));
    }

    /**
     * `
     * Ends the recording for this display list. Calling this method marks
     * the display list valid and {@link #hasDisplayList()} will return true.
     *
     * @see #beginRecording(int, int)
     * @see #hasDisplayList()
     */
    public void endRecording() {
        if (mCurrentRecordingCanvas == null) {
            throw new IllegalStateException(
                    "No recording in progress, forgot to call #beginRecording()?");
        }
        RecordingCanvas canvas = mCurrentRecordingCanvas;
        mCurrentRecordingCanvas = null;
        canvas.finishRecording(this);
        canvas.recycle();
    }

    /**
     * @hide
     * @deprecated use {@link #beginRecording(int, int)} instead
     */
    @Deprecated
    public RecordingCanvas start(int width, int height) {
        return beginRecording(width, height);
    }

    /**
     * @hide
     * @deprecated use {@link #endRecording()} instead
     */
    @Deprecated
    public void end(RecordingCanvas canvas) {
        if (canvas != mCurrentRecordingCanvas) {
            throw new IllegalArgumentException("Wrong canvas");
        }
        endRecording();
    }

    /**
     * Reset native resources. This is called when cleaning up the state of display lists
     * during destruction of hardware resources, to ensure that we do not hold onto
     * obsolete resources after related resources are gone.
     */
    public void discardDisplayList() {
        nDiscardDisplayList(mNativeRenderNode);
    }

    /**
     * Returns whether the RenderNode has a display list. If this returns false, the RenderNode
     * should be re-recorded with {@link #beginRecording()} and {@link #endRecording()}.
     *
     * A RenderNode without a display list may still be drawn, however it will have no impact
     * on the rendering content until its display list is updated.
     *
     * When a RenderNode is no longer drawn by anything the system may automatically
     * invoke {@link #discardDisplayList()}. It is therefore important to ensure that
     * {@link #hasDisplayList()} is true on a RenderNode prior to drawing it.
     *
     * See {@link #discardDisplayList()}
     *
     * @return boolean true if this RenderNode has a display list, false otherwise.
     */
    public boolean hasDisplayList() {
        return nIsValid(mNativeRenderNode);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Matrix manipulation
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Whether or not the RenderNode has an identity transform. This is a faster
     * way to do the otherwise equivalent {@link #getMatrix(Matrix)} {@link Matrix#isIdentity()}
     * as it doesn't require copying the Matrix first, thus minimizing overhead.
     *
     * @return true if the RenderNode has an identity transform, false otherwise
     */
    public boolean hasIdentityMatrix() {
        return nHasIdentityMatrix(mNativeRenderNode);
    }

    /**
     * Gets the current transform matrix
     *
     * @param outMatrix The matrix to store the transform of the RenderNode
     */
    public void getMatrix(@NonNull Matrix outMatrix) {
        nGetTransformMatrix(mNativeRenderNode, outMatrix.ni());
    }

    /**
     * Gets the current transform inverted. This is a faster way to do the otherwise
     * equivalent {@link #getMatrix(Matrix)} followed by {@link Matrix#invert(Matrix)}
     *
     * @param outMatrix The matrix to store the inverse transform of the RenderNode
     */
    public void getInverseMatrix(@NonNull Matrix outMatrix) {
        nGetInverseTransformMatrix(mNativeRenderNode, outMatrix.ni());
    }

    ///////////////////////////////////////////////////////////////////////////
    // RenderProperty Setters
    ///////////////////////////////////////////////////////////////////////////

    /**
     * @hide
     * @deprecated use {@link #setUseCompositingLayer(boolean, Paint)} instead
     */
    @Deprecated
    public boolean setLayerType(int layerType) {
        return nSetLayerType(mNativeRenderNode, layerType);
    }

    /**
     * @hide
     * @deprecated use {@link #setUseCompositingLayer(boolean, Paint)} instead
     */
    @Deprecated
    public boolean setLayerPaint(@Nullable Paint paint) {
        return nSetLayerPaint(mNativeRenderNode, paint != null ? paint.getNativeInstance() : 0);
    }

    /**
     * Controls whether or not to force this RenderNode to render to an intermediate buffer.
     * Internally RenderNode will already promote itself to a composition layer if it's useful
     * for performance or required for the current combination of {@link #setAlpha(float)} and
     * {@link #setHasOverlappingRendering(boolean)}.
     *
     * <p>The usage of this is instead to allow for either overriding of the internal behavior
     * if it's measured to be necessary for the particular rendering content in question or, more
     * usefully, to add a composition effect to the RenderNode via the optional paint parameter.
     *
     * <p>Note: When a RenderNode is using a compositing layer it will also result in
     * clipToBounds=true behavior.
     *
     * @param forceToLayer if true this forces the RenderNode to use an intermediate buffer.
     *                     Default & generally recommended value is false.
     * @param paint        The blend mode, alpha, and ColorFilter to apply to the compositing layer.
     *                     Only applies if forceToLayer is true. The paint's alpha is multiplied
     *                     with {@link #getAlpha()} to resolve the final alpha of the RenderNode.
     *                     If null then no additional composition effects are applied on top of the
     *                     composition layer.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setUseCompositingLayer(boolean forceToLayer, @Nullable Paint paint) {
        boolean didChange = nSetLayerType(mNativeRenderNode, forceToLayer ? 2 : 0);
        didChange |= nSetLayerPaint(mNativeRenderNode,
                paint != null ? paint.getNativeInstance() : 0);
        return didChange;
    }

    /**
     * Gets whether or not a compositing layer is forced to be used. The default & recommended
     * is false, as it is typically faster to avoid using compositing layers.
     * See {@link #setUseCompositingLayer(boolean, Paint)}.
     *
     * @return true if a compositing layer is forced, false otherwise
     */
    public boolean getUseCompositingLayer() {
        return nGetLayerType(mNativeRenderNode) != 0;
    }

    /**
     * Sets an additional clip on the RenderNode. If null, the extra clip is removed from the
     * RenderNode. If non-null, the RenderNode will be clipped to this rect. In addition  if
     * {@link #setClipToBounds(boolean)} is true, then the RenderNode will be clipped to the
     * intersection of this rectangle and the bounds of the render node, which is set with
     * {@link #setPosition(Rect)}.
     *
     * <p>This is equivalent to do a {@link Canvas#clipRect(Rect)} at the start of this
     * RenderNode's display list. However, as this is a property of the RenderNode instead
     * of part of the display list it can be more easily animated for transient additional
     * clipping. An example usage of this would be the {@link android.transition.ChangeBounds}
     * transition animation with the resizeClip=true option.
     *
     * @param rect the bounds to clip to. If null, the additional clip is removed.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setClipRect(@Nullable Rect rect) {
        if (rect == null) {
            return nSetClipBoundsEmpty(mNativeRenderNode);
        } else {
            return nSetClipBounds(mNativeRenderNode, rect.left, rect.top, rect.right, rect.bottom);
        }
    }

    /**
     * Set whether the Render node should clip itself to its bounds. This defaults to true,
     * and is useful to the renderer in enable quick-rejection of chunks of the tree as well as
     * better partial invalidation support. Clipping can be further restricted or controlled
     * through the combination of this property as well as {@link #setClipRect(Rect)}, which
     * allows for a different clipping rectangle to be used in addition to or instead of the
     * {@link #setPosition(int, int, int, int)} or the RenderNode.
     *
     * @param clipToBounds true if the display list should clip to its bounds, false otherwise.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setClipToBounds(boolean clipToBounds) {
        return nSetClipToBounds(mNativeRenderNode, clipToBounds);
    }

    /**
     * Returns whether or not the RenderNode is clipping to its bounds. See
     * {@link #setClipToBounds(boolean)} and {@link #setPosition(int, int, int, int)}
     *
     * @return true if the render node clips to its bounds, false otherwise.
     */
    public boolean getClipToBounds() {
        return nGetClipToBounds(mNativeRenderNode);
    }

    /**
     * <p>Sets whether the RenderNode should be drawn immediately after the
     * closest ancestor RenderNode containing a projection receiver.
     *
     * <p>The default is false, and the rendering of this node happens in the typical draw order.
     *
     * <p>If true, then at rendering time this rendernode will not be drawn in order with the
     * {@link Canvas#drawRenderNode(RenderNode)} command that drew this RenderNode, but instead
     * it will be re-positioned in the RenderNode tree to be drawn on the closet ancestor with a
     * child rendernode that has {@link #setProjectionReceiver(boolean)} as true.
     *
     * <p>The typical usage of this is to allow a child RenderNode to draw on a parent's background,
     * such as the platform's usage with {@link android.graphics.drawable.RippleDrawable}. Consider
     * the following structure, built out of which RenderNode called drawRenderNode on a different
     * RenderNode:
     *
     * <pre>
     *        +-------------+
     *        |RenderNode: P|
     *        +-+----------++
     *          |          |
     *          v          v
     *  +-------+-----+  +-+--------------+
     *  |RenderNode: C|  |RenderNode: P'BG|
     *  +-------+-----+  +----------------+
     *          |
     *          |
     * +--------+-------+
     * |RenderNode: C'BG|
     * +----------------+
     * </pre>
     *
     * If P'BG is a projection receiver, and C'BG is set to project backwards then C'BG will
     * behave as if it was drawn directly by P'BG instead of by C. This includes inheriting P'BG's
     * clip instead of C's clip.
     *
     * @param shouldProject true if the display list should be projected onto a
     *                      containing volume. Default is false.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setProjectBackwards(boolean shouldProject) {
        return nSetProjectBackwards(mNativeRenderNode, shouldProject);
    }

    /**
     * Sets whether the RenderNode is a projection receiver. If true then this RenderNode's parent
     * should draw any descendant RenderNodes with ProjectBackwards=true directly on top of it.
     * Default value is false. See
     * {@link #setProjectBackwards(boolean)} for a description of what this entails.
     *
     * @param shouldRecieve True if this RenderNode is a projection receiver, false otherwise.
     *                      Default is false.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setProjectionReceiver(boolean shouldRecieve) {
        return nSetProjectionReceiver(mNativeRenderNode, shouldRecieve);
    }

    /**
     * Sets the outline, defining the shape that casts a shadow, and the path to
     * be clipped if setClipToOutline is set.
     *
     * This will make a copy of the provided {@link Outline}, so any future modifications
     * to the outline will need to call {@link #setOutline(Outline)} with the modified
     * outline for those changes to be applied.
     *
     * @param outline The outline to use for this RenderNode.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setOutline(@Nullable Outline outline) {
        if (outline == null) {
            return nSetOutlineNone(mNativeRenderNode);
        }

        switch (outline.mMode) {
            case Outline.MODE_EMPTY:
                return nSetOutlineEmpty(mNativeRenderNode);
            case Outline.MODE_ROUND_RECT:
                return nSetOutlineRoundRect(mNativeRenderNode,
                        outline.mRect.left, outline.mRect.top,
                        outline.mRect.right, outline.mRect.bottom,
                        outline.mRadius, outline.mAlpha);
            case Outline.MODE_PATH:
                return nSetOutlinePath(mNativeRenderNode, outline.mPath.mNativePath,
                        outline.mAlpha);
        }

        throw new IllegalArgumentException("Unrecognized outline?");
    }

    /** @hide */
    public boolean clearStretch() {
        return nClearStretch(mNativeRenderNode);
    }

    /** @hide */
    public boolean stretch(float vecX, float vecY,
        float maxStretchAmountX, float maxStretchAmountY) {
        if (Float.isInfinite(vecX) || Float.isNaN(vecX)) {
            throw new IllegalArgumentException("vecX must be a finite, non-NaN value " + vecX);
        }
        if (Float.isInfinite(vecY) || Float.isNaN(vecY)) {
            throw new IllegalArgumentException("vecY must be a finite, non-NaN value " + vecY);
        }

        if (maxStretchAmountX <= 0.0f) {
            throw new IllegalArgumentException(
                    "The max horizontal stretch amount must be >0, got " + maxStretchAmountX);
        }
        if (maxStretchAmountY <= 0.0f) {
            throw new IllegalArgumentException(
                    "The max vertical stretch amount must be >0, got " + maxStretchAmountY);
        }
        return nStretch(
                mNativeRenderNode,
                vecX,
                vecY,
                maxStretchAmountX,
                maxStretchAmountY
        );
    }

    /**
     * Checks if the RenderNode has a shadow. That is, if the combination of {@link #getElevation()}
     * and {@link #getTranslationZ()} is greater than zero, there is an {@link Outline} set with
     * a valid shadow caster path, and the provided outline has a non-zero
     * {@link Outline#getAlpha()}.
     *
     * @return True if this RenderNode has a shadow, false otherwise
     */
    public boolean hasShadow() {
        return nHasShadow(mNativeRenderNode);
    }

    /**
     * Sets the color of the spot shadow that is drawn when the RenderNode has a positive Z or
     * elevation value and is drawn inside of a {@link Canvas#enableZ()} section.
     * <p>
     * By default the shadow color is black. Generally, this color will be opaque so the intensity
     * of the shadow is consistent between different RenderNodes with different colors.
     * <p>
     * The opacity of the final spot shadow is a function of the shadow caster height, the
     * alpha channel of the outlineSpotShadowColor (typically opaque), and the
     * {@link android.R.attr#spotShadowAlpha} theme attribute
     *
     * @param color The color this RenderNode will cast for its elevation spot shadow.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setSpotShadowColor(@ColorInt int color) {
        return nSetSpotShadowColor(mNativeRenderNode, color);
    }

    /**
     * @return The shadow color set by {@link #setSpotShadowColor(int)}, or black if nothing
     * was set
     */
    public @ColorInt int getSpotShadowColor() {
        return nGetSpotShadowColor(mNativeRenderNode);
    }

    /**
     * Sets the color of the ambient shadow that is drawn when the RenderNode has a positive Z or
     * elevation value and is drawn inside of a {@link Canvas#enableZ()} section.
     * <p>
     * By default the shadow color is black. Generally, this color will be opaque so the intensity
     * of the shadow is consistent between different RenderNodes with different colors.
     * <p>
     * The opacity of the final ambient shadow is a function of the shadow caster height, the
     * alpha channel of the outlineAmbientShadowColor (typically opaque), and the
     * {@link android.R.attr#ambientShadowAlpha} theme attribute.
     *
     * @param color The color this RenderNode will cast for its elevation shadow.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setAmbientShadowColor(@ColorInt int color) {
        return nSetAmbientShadowColor(mNativeRenderNode, color);
    }

    /**
     * @return The shadow color set by {@link #setAmbientShadowColor(int)}, or black if
     * nothing was set
     */
    public @ColorInt int getAmbientShadowColor() {
        return nGetAmbientShadowColor(mNativeRenderNode);
    }

    /**
     * Enables or disables clipping to the outline.
     *
     * @param clipToOutline true if clipping to the outline.
     * @return True if the clipToOutline value changed, false if previous value matched the new
     *         value.
     */
    public boolean setClipToOutline(boolean clipToOutline) {
        return nSetClipToOutline(mNativeRenderNode, clipToOutline);
    }

    /**
     * See {@link #setClipToOutline(boolean)}
     *
     * @return True if this RenderNode clips to its outline, false otherwise
     */
    public boolean getClipToOutline() {
        return nGetClipToOutline(mNativeRenderNode);
    }

    /**
     * Controls the RenderNode's circular reveal clip.
     *
     * @hide
     */
    public boolean setRevealClip(boolean shouldClip,
            float x, float y, float radius) {
        return nSetRevealClip(mNativeRenderNode, shouldClip, x, y, radius);
    }

    /**
     * Set the static matrix on the display list. The specified matrix is combined with other
     * transforms (such as {@link #setScaleX(float)}, {@link #setRotationZ(float)}, etc.)
     *
     * @param matrix A transform matrix to apply to this display list
     * @hide TODO Do we want this?
     */
    public boolean setStaticMatrix(Matrix matrix) {
        return nSetStaticMatrix(mNativeRenderNode, matrix.ni());
    }

    /**
     * Set the Animation matrix on the display list. This matrix exists if an Animation is
     * currently playing on a View, and is set on the display list during at draw() time. When
     * the Animation finishes, the matrix should be cleared by sending <code>null</code>
     * for the matrix parameter.
     *
     * @param matrix The matrix, null indicates that the matrix should be cleared.
     * @see #getAnimationMatrix()
     *
     * @hide TODO Do we want this?
     */
    public boolean setAnimationMatrix(@Nullable Matrix matrix) {
        return nSetAnimationMatrix(mNativeRenderNode,
                (matrix != null) ? matrix.ni() : 0);
    }

    /**
     * Returns the previously set Animation matrix. This matrix exists if an Animation is
     * currently playing on a View, and is set on the display list during at draw() time.
     * Returns <code>null</code> when there is no transformation provided by
     * {@link #setAnimationMatrix(Matrix)}.
     *
     * @return the current Animation matrix.
     * @see #setAnimationMatrix(Matrix)
     *
     * @hide
     */
    @Nullable
    public Matrix getAnimationMatrix() {
        Matrix output = new Matrix();
        if (nGetAnimationMatrix(mNativeRenderNode, output.ni())) {
            return output;
        } else {
            return null;
        }
    }

    /**
     * Sets the translucency level for the display list.
     *
     * @param alpha The translucency of the display list, must be a value between 0.0f and 1.0f
     * @see View#setAlpha(float)
     * @see #getAlpha()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setAlpha(float alpha) {
        return nSetAlpha(mNativeRenderNode, alpha);
    }

    /**
     * Configure the {@link android.graphics.RenderEffect} to apply to this RenderNode. This
     * will apply a visual effect to the end result of the contents of this RenderNode before
     * it is drawn into the destination. For example if
     * {@link RenderEffect#createBlurEffect(float, float, RenderEffect, Shader.TileMode)}
     * is provided, the contents will be drawn in a separate layer, then this layer will
     * be blurred when this RenderNode is drawn into the destination.
     * @param renderEffect to be applied to the RenderNode. Passing null clears all previously
     *          configured RenderEffects
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setRenderEffect(@Nullable RenderEffect renderEffect) {
        return nSetRenderEffect(mNativeRenderNode,
                renderEffect != null ? renderEffect.getNativeInstance() : 0);
    }

    /**
     * Returns the translucency level of this display list.
     *
     * @return A value between 0.0f and 1.0f
     * @see #setAlpha(float)
     */
    public float getAlpha() {
        return nGetAlpha(mNativeRenderNode);
    }

    /**
     * Sets whether the display list renders content which overlaps. Non-overlapping rendering
     * can use a fast path for alpha that avoids rendering to an offscreen buffer. By default
     * display lists consider they do not have overlapping content.
     *
     * @param hasOverlappingRendering False if the content is guaranteed to be non-overlapping,
     *                                true otherwise.
     * @see android.view.View#hasOverlappingRendering()
     * @see #hasOverlappingRendering()
     */
    public boolean setHasOverlappingRendering(boolean hasOverlappingRendering) {
        return nSetHasOverlappingRendering(mNativeRenderNode, hasOverlappingRendering);
    }

    /** @hide */
    @IntDef({USAGE_BACKGROUND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsageHint {
    }

    /**
     * The default usage hint
     *
     * @hide
     */
    public static final int USAGE_UNKNOWN = 0;

    /**
     * Usage is background content
     *
     * @hide
     */
    public static final int USAGE_BACKGROUND = 1;

    /**
     * Provides a hint on what this RenderNode's display list content contains. This hint is used
     * for automatic content transforms to improve accessibility or similar.
     *
     * @hide
     */
    public void setUsageHint(@UsageHint int usageHint) {
        nSetUsageHint(mNativeRenderNode, usageHint);
    }

    /**
     * Indicates whether the content of this display list overlaps.
     *
     * @return True if this display list renders content which overlaps, false otherwise.
     * @see #setHasOverlappingRendering(boolean)
     */
    public boolean hasOverlappingRendering() {
        return nHasOverlappingRendering(mNativeRenderNode);
    }

    /**
     * Sets the base elevation of this RenderNode in pixels
     *
     * @param lift the elevation in pixels
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setElevation(float lift) {
        return nSetElevation(mNativeRenderNode, lift);
    }

    /**
     * See {@link #setElevation(float)}
     *
     * @return The RenderNode's current elevation
     */
    public float getElevation() {
        return nGetElevation(mNativeRenderNode);
    }

    /**
     * Sets the translation value for the display list on the X axis.
     *
     * @param translationX The X axis translation value of the display list, in pixels
     * @see View#setTranslationX(float)
     * @see #getTranslationX()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setTranslationX(float translationX) {
        return nSetTranslationX(mNativeRenderNode, translationX);
    }

    /**
     * Returns the translation value for this display list on the X axis, in pixels.
     *
     * @see #setTranslationX(float)
     */
    public float getTranslationX() {
        return nGetTranslationX(mNativeRenderNode);
    }

    /**
     * Sets the translation value for the display list on the Y axis.
     *
     * @param translationY The Y axis translation value of the display list, in pixels
     * @see View#setTranslationY(float)
     * @see #getTranslationY()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setTranslationY(float translationY) {
        return nSetTranslationY(mNativeRenderNode, translationY);
    }

    /**
     * Returns the translation value for this display list on the Y axis, in pixels.
     *
     * @see #setTranslationY(float)
     */
    public float getTranslationY() {
        return nGetTranslationY(mNativeRenderNode);
    }

    /**
     * Sets the translation value for the display list on the Z axis.
     *
     * @see View#setTranslationZ(float)
     * @see #getTranslationZ()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setTranslationZ(float translationZ) {
        return nSetTranslationZ(mNativeRenderNode, translationZ);
    }

    /**
     * Returns the translation value for this display list on the Z axis.
     *
     * @see #setTranslationZ(float)
     */
    public float getTranslationZ() {
        return nGetTranslationZ(mNativeRenderNode);
    }

    /**
     * Sets the rotation value for the display list around the Z axis.
     *
     * @param rotation The rotation value of the display list, in degrees
     * @see View#setRotation(float)
     * @see #getRotationZ()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setRotationZ(float rotation) {
        return nSetRotation(mNativeRenderNode, rotation);
    }

    /**
     * Returns the rotation value for this display list around the Z axis, in degrees.
     *
     * @see #setRotationZ(float)
     */
    public float getRotationZ() {
        return nGetRotation(mNativeRenderNode);
    }

    /**
     * Sets the rotation value for the display list around the X axis.
     *
     * @param rotationX The rotation value of the display list, in degrees
     * @see View#setRotationX(float)
     * @see #getRotationX()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setRotationX(float rotationX) {
        return nSetRotationX(mNativeRenderNode, rotationX);
    }

    /**
     * Returns the rotation value for this display list around the X axis, in degrees.
     *
     * @see #setRotationX(float)
     */
    public float getRotationX() {
        return nGetRotationX(mNativeRenderNode);
    }

    /**
     * Sets the rotation value for the display list around the Y axis.
     *
     * @param rotationY The rotation value of the display list, in degrees
     * @see View#setRotationY(float)
     * @see #getRotationY()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setRotationY(float rotationY) {
        return nSetRotationY(mNativeRenderNode, rotationY);
    }

    /**
     * Returns the rotation value for this display list around the Y axis, in degrees.
     *
     * @see #setRotationY(float)
     */
    public float getRotationY() {
        return nGetRotationY(mNativeRenderNode);
    }

    /**
     * Sets the scale value for the display list on the X axis.
     *
     * @param scaleX The scale value of the display list
     * @see View#setScaleX(float)
     * @see #getScaleX()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setScaleX(float scaleX) {
        return nSetScaleX(mNativeRenderNode, scaleX);
    }

    /**
     * Returns the scale value for this display list on the X axis.
     *
     * @see #setScaleX(float)
     */
    public float getScaleX() {
        return nGetScaleX(mNativeRenderNode);
    }

    /**
     * Sets the scale value for the display list on the Y axis.
     *
     * @param scaleY The scale value of the display list
     * @see View#setScaleY(float)
     * @see #getScaleY()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setScaleY(float scaleY) {
        return nSetScaleY(mNativeRenderNode, scaleY);
    }

    /**
     * Returns the scale value for this display list on the Y axis.
     *
     * @see #setScaleY(float)
     */
    public float getScaleY() {
        return nGetScaleY(mNativeRenderNode);
    }

    /**
     * Sets the pivot value for the display list on the X axis
     *
     * @param pivotX The pivot value of the display list on the X axis, in pixels
     * @see View#setPivotX(float)
     * @see #getPivotX()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setPivotX(float pivotX) {
        return nSetPivotX(mNativeRenderNode, pivotX);
    }

    /**
     * Returns the pivot value for this display list on the X axis, in pixels.
     *
     * @see #setPivotX(float)
     */
    public float getPivotX() {
        return nGetPivotX(mNativeRenderNode);
    }

    /**
     * Sets the pivot value for the display list on the Y axis
     *
     * @param pivotY The pivot value of the display list on the Y axis, in pixels
     * @see View#setPivotY(float)
     * @see #getPivotY()
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setPivotY(float pivotY) {
        return nSetPivotY(mNativeRenderNode, pivotY);
    }

    /**
     * Returns the pivot value for this display list on the Y axis, in pixels.
     *
     * @see #setPivotY(float)
     */
    public float getPivotY() {
        return nGetPivotY(mNativeRenderNode);
    }

    /**
     * @return Whether or not a pivot was explicitly set with {@link #setPivotX(float)} or
     * {@link #setPivotY(float)}. If no pivot has been set then the pivot will be the center
     * of the RenderNode.
     */
    public boolean isPivotExplicitlySet() {
        return nIsPivotExplicitlySet(mNativeRenderNode);
    }

    /**
     * Clears any pivot previously set by a call to  {@link #setPivotX(float)} or
     * {@link #setPivotY(float)}. After calling this {@link #isPivotExplicitlySet()} will be false
     * and the pivot used for rotation will return to default of being centered on the view.
     *
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean resetPivot() {
        return nResetPivot(mNativeRenderNode);
    }

    /**
     * <p>Sets the distance along the Z axis (orthogonal to the X/Y plane on which
     * RenderNodes are drawn) from the camera to this RenderNode. The camera's distance
     * affects 3D transformations, for instance rotations around the X and Y
     * axis. If the rotationX or rotationY properties are changed and this view is
     * large (more than half the size of the screen), it is recommended to always
     * use a camera distance that's greater than the height (X axis rotation) or
     * the width (Y axis rotation) of this view.</p>
     *
     * <p>The distance of the camera from the drawing plane can have an affect on the
     * perspective distortion of the RenderNode when it is rotated around the x or y axis.
     * For example, a large distance will result in a large viewing angle, and there
     * will not be much perspective distortion of the view as it rotates. A short
     * distance may cause much more perspective distortion upon rotation, and can
     * also result in some drawing artifacts if the rotated view ends up partially
     * behind the camera (which is why the recommendation is to use a distance at
     * least as far as the size of the view, if the view is to be rotated.)</p>
     *
     * <p>The distance is expressed in pixels and must always be positive</p>
     *
     * @param distance The distance in pixels, must always be positive
     * @see #setRotationX(float)
     * @see #setRotationY(float)
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setCameraDistance(
            @FloatRange(from = 0.0f, to = Float.MAX_VALUE) float distance) {
        if (!Float.isFinite(distance) || distance < 0.0f) {
            throw new IllegalArgumentException("distance must be finite & positive, given="
                    + distance);
        }
        // Native actually wants this to be negative not positive, so we flip it.
        return nSetCameraDistance(mNativeRenderNode, -distance);
    }

    /**
     * Returns the distance in Z of the camera for this RenderNode
     *
     * @return the distance along the Z axis in pixels.
     * @see #setCameraDistance(float)
     */
    public @FloatRange(from = 0.0f, to = Float.MAX_VALUE) float getCameraDistance() {
        return -nGetCameraDistance(mNativeRenderNode);
    }

    /**
     * Sets the left position for the RenderNode.
     *
     * @param left The left position, in pixels, of the RenderNode
     * @return true if the value changed, false otherwise
     * @hide
     */
    public boolean setLeft(int left) {
        return nSetLeft(mNativeRenderNode, left);
    }

    /**
     * Sets the top position for the RenderNode.
     *
     * @param top The top position, in pixels, of the RenderNode
     * @return true if the value changed, false otherwise.
     * @hide
     */
    public boolean setTop(int top) {
        return nSetTop(mNativeRenderNode, top);
    }

    /**
     * Sets the right position for the RenderNode.
     *
     * @param right The right position, in pixels, of the RenderNode
     * @return true if the value changed, false otherwise.
     * @hide
     */
    public boolean setRight(int right) {
        return nSetRight(mNativeRenderNode, right);
    }

    /**
     * Sets the bottom position for the RenderNode.
     *
     * @param bottom The bottom position, in pixels, of the RenderNode
     * @return true if the value changed, false otherwise.
     * @hide
     */
    public boolean setBottom(int bottom) {
        return nSetBottom(mNativeRenderNode, bottom);
    }

    /**
     * Gets the left position for the RenderNode.
     *
     * @return the left position in pixels
     */
    public int getLeft() {
        return nGetLeft(mNativeRenderNode);
    }

    /**
     * Gets the top position for the RenderNode.
     *
     * @return the top position in pixels
     */
    public int getTop() {
        return nGetTop(mNativeRenderNode);
    }

    /**
     * Gets the right position for the RenderNode.
     *
     * @return the right position in pixels
     */
    public int getRight() {
        return nGetRight(mNativeRenderNode);
    }

    /**
     * Gets the bottom position for the RenderNode.
     *
     * @return the bottom position in pixels
     */
    public int getBottom() {
        return nGetBottom(mNativeRenderNode);
    }

    /**
     * Gets the width of the RenderNode, which is the right - left.
     *
     * @return the width of the RenderNode
     */
    public int getWidth() {
        return nGetWidth(mNativeRenderNode);
    }

    /**
     * Gets the height of the RenderNode, which is the bottom - top.
     *
     * @return the height of the RenderNode
     */
    public int getHeight() {
        return nGetHeight(mNativeRenderNode);
    }

    /**
     * Sets the left, top, right, and bottom of the RenderNode.
     *
     * @param left   The left position of the RenderNode, in pixels
     * @param top    The top position of the RenderNode, in pixels
     * @param right  The right position of the RenderNode, in pixels
     * @param bottom The bottom position of the RenderNode, in pixels
     * @return true if any values changed, false otherwise.
     * @hide
     */
    public boolean setLeftTopRightBottom(int left, int top, int right, int bottom) {
        return nSetLeftTopRightBottom(mNativeRenderNode, left, top, right, bottom);
    }

    /**
     * Sets the position of the RenderNode.
     *
     * @param left   The left position of the RenderNode, in pixels
     * @param top    The top position of the RenderNode, in pixels
     * @param right  The right position of the RenderNode, in pixels
     * @param bottom The bottom position of the RenderNode, in pixels
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setPosition(int left, int top, int right, int bottom) {
        return nSetLeftTopRightBottom(mNativeRenderNode, left, top, right, bottom);
    }

    /**
     * Sets the position of the RenderNode.
     *
     * @param position The position rectangle in pixels
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setPosition(@NonNull Rect position) {
        return nSetLeftTopRightBottom(mNativeRenderNode,
                position.left, position.top, position.right, position.bottom);
    }

    /**
     * Offsets the left and right positions for the RenderNode
     *
     * @param offset The amount that the left and right positions are offset in pixels
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean offsetLeftAndRight(int offset) {
        return nOffsetLeftAndRight(mNativeRenderNode, offset);
    }

    /**
     * Offsets the top and bottom values for the RenderNode
     *
     * @param offset The amount that the left and right positions are offset in pixels
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean offsetTopAndBottom(int offset) {
        return nOffsetTopAndBottom(mNativeRenderNode, offset);
    }

    /**
     * Outputs the RenderNode to the log. This method exists for use by
     * tools to output display lists for selected nodes to the log.
     *
     * @hide TODO: Expose? Should the shape of this be different than forced dump to logcat?
     */
    public void output() {
        nOutput(mNativeRenderNode);
    }

    /**
     * Gets the approximate memory usage of the RenderNode for debug purposes. Does not include
     * the memory usage of any child RenderNodes nor any bitmaps, only the memory usage of
     * this RenderNode and any data it owns.
     *
     * @return Approximate memory usage in bytes.
     */
    public @BytesLong long computeApproximateMemoryUsage() {
        return nGetUsageSize(mNativeRenderNode);
    }

    /**
     * Gets the approximate amount of memory allocated for the RenderNode for debug purposes.
     * Does not include the memory allocated by any child RenderNodes nor any bitmaps, only the
     * memory allocated for this RenderNode and any data it owns.
     *
     * The difference between this and {@link #computeApproximateMemoryUsage()} is this includes
     * memory allocated but not used. In particular structures such as DisplayLists are similar
     * to things like ArrayLists - they need to resize as commands are added to them. As such,
     * memory used can be less than memory allocated.
     *
     * @hide */
    public @BytesLong long computeApproximateMemoryAllocated() {
        return nGetAllocatedSize(mNativeRenderNode);
    }

    /**
     * Sets whether or not to allow force dark to apply to this RenderNode.
     *
     * Setting this to false will disable the auto-dark feature on everything this RenderNode
     * draws, including any descendants.
     *
     * Setting this to true will allow this RenderNode to be automatically made dark, however
     * a value of 'true' will not override any 'false' value in its parent chain nor will
     * it prevent any 'false' in any of its children.
     *
     * @param allow Whether or not to allow force dark.
     * @return True if the value changed, false if the new value was the same as the previous value.
     */
    public boolean setForceDarkAllowed(boolean allow) {
        return nSetAllowForceDark(mNativeRenderNode, allow);
    }

    /**
     * See {@link #setForceDarkAllowed(boolean)}
     *
     * @return true if force dark is allowed (default), false if it is disabled
     */
    public boolean isForceDarkAllowed() {
        return nGetAllowForceDark(mNativeRenderNode);
    }

    /**
     * Returns the unique ID that identifies this RenderNode. This ID is unique for the
     * lifetime of the process. IDs are reset on process death, and are unique only within
     * the process.
     *
     * This ID is intended to be used with debugging tools to associate a particular
     * RenderNode across different debug dumping & inspection tools. For example
     * a View layout inspector should include the unique ID for any RenderNodes that it owns
     * to associate the drawing content with the layout content.
     *
     * @return the unique ID for this RenderNode
     */
    public long getUniqueId() {
        return nGetUniqueId(mNativeRenderNode);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Animations
    ///////////////////////////////////////////////////////////////////////////

    /**
     * TODO: Figure out if this can be eliminated/refactored away
     *
     * For now this interface exists to de-couple RenderNode from anything View-specific in a
     * bit of a kludge.
     *
     * @hide
     */
    public interface AnimationHost {
        /** @hide */
        void registerAnimatingRenderNode(RenderNode animator);

        /** @hide */
        void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator);

        /** @hide */
        boolean isAttached();
    }

    /** @hide */
    public void addAnimator(RenderNodeAnimator animator) {
        if (!isAttached()) {
            throw new IllegalStateException("Cannot start this animator on a detached view!");
        }
        nAddAnimator(mNativeRenderNode, animator.getNativeAnimator());
        mAnimationHost.registerAnimatingRenderNode(this);
    }

    /** @hide */
    public boolean isAttached() {
        return mAnimationHost != null && mAnimationHost.isAttached();
    }

    /** @hide */
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animatorSet) {
        if (!isAttached()) {
            throw new IllegalStateException("Cannot start this animator on a detached view!");
        }
        mAnimationHost.registerVectorDrawableAnimator(animatorSet);
    }

    /** @hide */
    public void endAllAnimators() {
        nEndAllAnimators(mNativeRenderNode);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Regular JNI methods
    ///////////////////////////////////////////////////////////////////////////

    private static native long nCreate(String name);

    private static native long nGetNativeFinalizer();

    private static native void nOutput(long renderNode);

    private static native int nGetUsageSize(long renderNode);
    private static native int nGetAllocatedSize(long renderNode);

    private static native void nRequestPositionUpdates(long renderNode,
            WeakReference<PositionUpdateListener> callback);

    // Animations

    private static native void nAddAnimator(long renderNode, long animatorPtr);

    private static native void nEndAllAnimators(long renderNode);

    ///////////////////////////////////////////////////////////////////////////
    // @CriticalNative methods
    ///////////////////////////////////////////////////////////////////////////

    @CriticalNative
    private static native void nDiscardDisplayList(long renderNode);

    @CriticalNative
    private static native boolean nIsValid(long renderNode);

    // Matrix

    @CriticalNative
    private static native void nGetTransformMatrix(long renderNode, long nativeMatrix);

    @CriticalNative
    private static native void nGetInverseTransformMatrix(long renderNode, long nativeMatrix);

    @CriticalNative
    private static native boolean nHasIdentityMatrix(long renderNode);

    // Properties

    @CriticalNative
    private static native boolean nOffsetTopAndBottom(long renderNode, int offset);

    @CriticalNative
    private static native boolean nOffsetLeftAndRight(long renderNode, int offset);

    @CriticalNative
    private static native boolean nSetLeftTopRightBottom(long renderNode, int left, int top,
            int right, int bottom);

    @CriticalNative
    private static native boolean nSetLeft(long renderNode, int left);

    @CriticalNative
    private static native boolean nSetTop(long renderNode, int top);

    @CriticalNative
    private static native boolean nSetRight(long renderNode, int right);

    @CriticalNative
    private static native boolean nSetBottom(long renderNode, int bottom);

    @CriticalNative
    private static native int nGetLeft(long renderNode);

    @CriticalNative
    private static native int nGetTop(long renderNode);

    @CriticalNative
    private static native int nGetRight(long renderNode);

    @CriticalNative
    private static native int nGetBottom(long renderNode);

    @CriticalNative
    private static native boolean nSetCameraDistance(long renderNode, float distance);

    @CriticalNative
    private static native boolean nSetPivotY(long renderNode, float pivotY);

    @CriticalNative
    private static native boolean nSetPivotX(long renderNode, float pivotX);

    @CriticalNative
    private static native boolean nResetPivot(long renderNode);

    @CriticalNative
    private static native boolean nSetLayerType(long renderNode, int layerType);

    @CriticalNative
    private static native int nGetLayerType(long renderNode);

    @CriticalNative
    private static native boolean nSetLayerPaint(long renderNode, long paint);

    @CriticalNative
    private static native boolean nSetClipToBounds(long renderNode, boolean clipToBounds);

    @CriticalNative
    private static native boolean nGetClipToBounds(long renderNode);

    @CriticalNative
    private static native boolean nSetClipBounds(long renderNode, int left, int top,
            int right, int bottom);

    @CriticalNative
    private static native boolean nSetClipBoundsEmpty(long renderNode);

    @CriticalNative
    private static native boolean nSetProjectBackwards(long renderNode, boolean shouldProject);

    @CriticalNative
    private static native boolean nSetProjectionReceiver(long renderNode, boolean shouldRecieve);

    @CriticalNative
    private static native boolean nSetOutlineRoundRect(long renderNode, int left, int top,
            int right, int bottom, float radius, float alpha);

    @CriticalNative
    private static native boolean nSetOutlinePath(long renderNode, long nativePath,
            float alpha);

    @CriticalNative
    private static native boolean nSetOutlineEmpty(long renderNode);

    @CriticalNative
    private static native boolean nSetOutlineNone(long renderNode);

    @CriticalNative
    private static native boolean nClearStretch(long renderNode);

    @CriticalNative
    private static native boolean nStretch(long renderNode, float vecX, float vecY,
            float maxStretchX, float maxStretchY);

    @CriticalNative
    private static native boolean nHasShadow(long renderNode);

    @CriticalNative
    private static native boolean nSetSpotShadowColor(long renderNode, int color);

    @CriticalNative
    private static native boolean nSetAmbientShadowColor(long renderNode, int color);

    @CriticalNative
    private static native int nGetSpotShadowColor(long renderNode);

    @CriticalNative
    private static native int nGetAmbientShadowColor(long renderNode);

    @CriticalNative
    private static native boolean nSetClipToOutline(long renderNode, boolean clipToOutline);

    @CriticalNative
    private static native boolean nSetRevealClip(long renderNode,
            boolean shouldClip, float x, float y, float radius);

    @CriticalNative
    private static native boolean nSetAlpha(long renderNode, float alpha);

    @CriticalNative
    private static native boolean nSetRenderEffect(long renderNode, long renderEffect);

    @CriticalNative
    private static native boolean nSetHasOverlappingRendering(long renderNode,
            boolean hasOverlappingRendering);

    @CriticalNative
    private static native void nSetUsageHint(long renderNode, int usageHint);

    @CriticalNative
    private static native boolean nSetElevation(long renderNode, float lift);

    @CriticalNative
    private static native boolean nSetTranslationX(long renderNode, float translationX);

    @CriticalNative
    private static native boolean nSetTranslationY(long renderNode, float translationY);

    @CriticalNative
    private static native boolean nSetTranslationZ(long renderNode, float translationZ);

    @CriticalNative
    private static native boolean nSetRotation(long renderNode, float rotation);

    @CriticalNative
    private static native boolean nSetRotationX(long renderNode, float rotationX);

    @CriticalNative
    private static native boolean nSetRotationY(long renderNode, float rotationY);

    @CriticalNative
    private static native boolean nSetScaleX(long renderNode, float scaleX);

    @CriticalNative
    private static native boolean nSetScaleY(long renderNode, float scaleY);

    @CriticalNative
    private static native boolean nSetStaticMatrix(long renderNode, long nativeMatrix);

    @CriticalNative
    private static native boolean nSetAnimationMatrix(long renderNode, long animationMatrix);

    @CriticalNative
    private static native boolean nHasOverlappingRendering(long renderNode);

    @CriticalNative
    private static native boolean nGetAnimationMatrix(long renderNode, long animationMatrix);

    @CriticalNative
    private static native boolean nGetClipToOutline(long renderNode);

    @CriticalNative
    private static native float nGetAlpha(long renderNode);

    @CriticalNative
    private static native float nGetCameraDistance(long renderNode);

    @CriticalNative
    private static native float nGetScaleX(long renderNode);

    @CriticalNative
    private static native float nGetScaleY(long renderNode);

    @CriticalNative
    private static native float nGetElevation(long renderNode);

    @CriticalNative
    private static native float nGetTranslationX(long renderNode);

    @CriticalNative
    private static native float nGetTranslationY(long renderNode);

    @CriticalNative
    private static native float nGetTranslationZ(long renderNode);

    @CriticalNative
    private static native float nGetRotation(long renderNode);

    @CriticalNative
    private static native float nGetRotationX(long renderNode);

    @CriticalNative
    private static native float nGetRotationY(long renderNode);

    @CriticalNative
    private static native boolean nIsPivotExplicitlySet(long renderNode);

    @CriticalNative
    private static native float nGetPivotX(long renderNode);

    @CriticalNative
    private static native float nGetPivotY(long renderNode);

    @CriticalNative
    private static native int nGetWidth(long renderNode);

    @CriticalNative
    private static native int nGetHeight(long renderNode);

    @CriticalNative
    private static native boolean nSetAllowForceDark(long renderNode, boolean allowForceDark);

    @CriticalNative
    private static native boolean nGetAllowForceDark(long renderNode);

    @CriticalNative
    private static native long nGetUniqueId(long renderNode);
}
