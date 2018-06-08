/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;

/**
 * <p>A TextureView can be used to display a content stream. Such a content
 * stream can for instance be a video or an OpenGL scene. The content stream
 * can come from the application's process as well as a remote process.</p>
 *
 * <p>TextureView can only be used in a hardware accelerated window. When
 * rendered in software, TextureView will draw nothing.</p>
 *
 * <p>Unlike {@link SurfaceView}, TextureView does not create a separate
 * window but behaves as a regular View. This key difference allows a
 * TextureView to be moved, transformed, animated, etc. For instance, you
 * can make a TextureView semi-translucent by calling
 * <code>myView.setAlpha(0.5f)</code>.</p>
 *
 * <p>Using a TextureView is simple: all you need to do is get its
 * {@link SurfaceTexture}. The {@link SurfaceTexture} can then be used to
 * render content. The following example demonstrates how to render the
 * camera preview into a TextureView:</p>
 *
 * <pre>
 *  public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
 *      private Camera mCamera;
 *      private TextureView mTextureView;
 *
 *      protected void onCreate(Bundle savedInstanceState) {
 *          super.onCreate(savedInstanceState);
 *
 *          mTextureView = new TextureView(this);
 *          mTextureView.setSurfaceTextureListener(this);
 *
 *          setContentView(mTextureView);
 *      }
 *
 *      public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
 *          mCamera = Camera.open();
 *
 *          try {
 *              mCamera.setPreviewTexture(surface);
 *              mCamera.startPreview();
 *          } catch (IOException ioe) {
 *              // Something bad happened
 *          }
 *      }
 *
 *      public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
 *          // Ignored, Camera does all the work for us
 *      }
 *
 *      public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
 *          mCamera.stopPreview();
 *          mCamera.release();
 *          return true;
 *      }
 *
 *      public void onSurfaceTextureUpdated(SurfaceTexture surface) {
 *          // Invoked every time there's a new Camera preview frame
 *      }
 *  }
 * </pre>
 *
 * <p>A TextureView's SurfaceTexture can be obtained either by invoking
 * {@link #getSurfaceTexture()} or by using a {@link SurfaceTextureListener}.
 * It is important to know that a SurfaceTexture is available only after the
 * TextureView is attached to a window (and {@link #onAttachedToWindow()} has
 * been invoked.) It is therefore highly recommended you use a listener to
 * be notified when the SurfaceTexture becomes available.</p>
 *
 * <p>It is important to note that only one producer can use the TextureView.
 * For instance, if you use a TextureView to display the camera preview, you
 * cannot use {@link #lockCanvas()} to draw onto the TextureView at the same
 * time.</p>
 *
 * @see SurfaceView
 * @see SurfaceTexture
 */
public class TextureView extends View {
    private static final String LOG_TAG = "TextureView";

    private TextureLayer mLayer;
    private SurfaceTexture mSurface;
    private SurfaceTextureListener mListener;
    private boolean mHadSurface;

    private boolean mOpaque = true;

    private final Matrix mMatrix = new Matrix();
    private boolean mMatrixChanged;

    private final Object[] mLock = new Object[0];
    private boolean mUpdateLayer;
    private boolean mUpdateSurface;

    private Canvas mCanvas;
    private int mSaveCount;

    private final Object[] mNativeWindowLock = new Object[0];
    // Set by native code, do not write!
    private long mNativeWindow;

    /**
     * Creates a new TextureView.
     *
     * @param context The context to associate this view with.
     */
    public TextureView(Context context) {
        super(context);
    }

    /**
     * Creates a new TextureView.
     *
     * @param context The context to associate this view with.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public TextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Creates a new TextureView.
     *
     * @param context The context to associate this view with.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     */
    public TextureView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Creates a new TextureView.
     *
     * @param context The context to associate this view with.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *        supplies default values for the view, used only if
     *        defStyleAttr is 0 or can not be found in the theme. Can be 0
     *        to not look for defaults.
     */
    public TextureView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOpaque() {
        return mOpaque;
    }

    /**
     * Indicates whether the content of this TextureView is opaque. The
     * content is assumed to be opaque by default.
     *
     * @param opaque True if the content of this TextureView is opaque,
     *               false otherwise
     */
    public void setOpaque(boolean opaque) {
        if (opaque != mOpaque) {
            mOpaque = opaque;
            if (mLayer != null) {
                updateLayerAndInvalidate();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!isHardwareAccelerated()) {
            Log.w(LOG_TAG, "A TextureView or a subclass can only be "
                    + "used with hardware acceleration enabled.");
        }

        if (mHadSurface) {
            invalidate(true);
            mHadSurface = false;
        }
    }

    /** @hide */
    @Override
    protected void onDetachedFromWindowInternal() {
        destroyHardwareLayer();
        releaseSurfaceTexture();
        super.onDetachedFromWindowInternal();
    }

    /**
     * @hide
     */
    @Override
    protected void destroyHardwareResources() {
        super.destroyHardwareResources();
        destroyHardwareLayer();
    }

    private void destroyHardwareLayer() {
        if (mLayer != null) {
            mLayer.detachSurfaceTexture();
            mLayer.destroy();
            mLayer = null;
            mMatrixChanged = true;
        }
    }

    private void releaseSurfaceTexture() {
        if (mSurface != null) {
            boolean shouldRelease = true;

            if (mListener != null) {
                shouldRelease = mListener.onSurfaceTextureDestroyed(mSurface);
            }

            synchronized (mNativeWindowLock) {
                nDestroyNativeWindow();
            }

            if (shouldRelease) {
                mSurface.release();
            }
            mSurface = null;
            mHadSurface = true;
        }
    }

    /**
     * The layer type of a TextureView is ignored since a TextureView is always
     * considered to act as a hardware layer. The optional paint supplied to this
     * method will however be taken into account when rendering the content of
     * this TextureView.
     *
     * @param layerType The type of layer to use with this view, must be one of
     *        {@link #LAYER_TYPE_NONE}, {@link #LAYER_TYPE_SOFTWARE} or
     *        {@link #LAYER_TYPE_HARDWARE}
     * @param paint The paint used to compose the layer. This argument is optional
     *        and can be null. It is ignored when the layer type is
     *        {@link #LAYER_TYPE_NONE}
     */
    @Override
    public void setLayerType(int layerType, @Nullable Paint paint) {
        setLayerPaint(paint);
    }

    @Override
    public void setLayerPaint(@Nullable Paint paint) {
        if (paint != mLayerPaint) {
            mLayerPaint = paint;
            invalidate();
        }
    }

    /**
     * Always returns {@link #LAYER_TYPE_HARDWARE}.
     */
    @Override
    public int getLayerType() {
        return LAYER_TYPE_HARDWARE;
    }

    /**
     * Calling this method has no effect.
     */
    @Override
    public void buildLayer() {
    }

    @Override
    public void setForeground(Drawable foreground) {
        if (foreground != null && !sTextureViewIgnoresDrawableSetters) {
            throw new UnsupportedOperationException(
                    "TextureView doesn't support displaying a foreground drawable");
        }
    }

    @Override
    public void setBackgroundDrawable(Drawable background) {
        if (background != null && !sTextureViewIgnoresDrawableSetters) {
            throw new UnsupportedOperationException(
                    "TextureView doesn't support displaying a background drawable");
        }
    }

    /**
     * Subclasses of TextureView cannot do their own rendering
     * with the {@link Canvas} object.
     *
     * @param canvas The Canvas to which the View is rendered.
     */
    @Override
    public final void draw(Canvas canvas) {
        // NOTE: Maintain this carefully (see View#draw)
        mPrivateFlags = (mPrivateFlags & ~PFLAG_DIRTY_MASK) | PFLAG_DRAWN;

        /* Simplify drawing to guarantee the layer is the only thing drawn - so e.g. no background,
        scrolling, or fading edges. This guarantees all drawing is in the layer, so drawing
        properties (alpha, layer paint) affect all of the content of a TextureView. */

        if (canvas.isHardwareAccelerated()) {
            DisplayListCanvas displayListCanvas = (DisplayListCanvas) canvas;

            TextureLayer layer = getTextureLayer();
            if (layer != null) {
                applyUpdate();
                applyTransformMatrix();

                mLayer.setLayerPaint(mLayerPaint); // ensure layer paint is up to date
                displayListCanvas.drawTextureLayer(layer);
            }
        }
    }

    /**
     * Subclasses of TextureView cannot do their own rendering
     * with the {@link Canvas} object.
     *
     * @param canvas The Canvas to which the View is rendered.
     */
    @Override
    protected final void onDraw(Canvas canvas) {
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mSurface != null) {
            mSurface.setDefaultBufferSize(getWidth(), getHeight());
            updateLayer();
            if (mListener != null) {
                mListener.onSurfaceTextureSizeChanged(mSurface, getWidth(), getHeight());
            }
        }
    }

    TextureLayer getTextureLayer() {
        if (mLayer == null) {
            if (mAttachInfo == null || mAttachInfo.mThreadedRenderer == null) {
                return null;
            }

            mLayer = mAttachInfo.mThreadedRenderer.createTextureLayer();
            boolean createNewSurface = (mSurface == null);
            if (createNewSurface) {
                // Create a new SurfaceTexture for the layer.
                mSurface = new SurfaceTexture(false);
                nCreateNativeWindow(mSurface);
            }
            mLayer.setSurfaceTexture(mSurface);
            mSurface.setDefaultBufferSize(getWidth(), getHeight());
            mSurface.setOnFrameAvailableListener(mUpdateListener, mAttachInfo.mHandler);

            if (mListener != null && createNewSurface) {
                mListener.onSurfaceTextureAvailable(mSurface, getWidth(), getHeight());
            }
            mLayer.setLayerPaint(mLayerPaint);
        }

        if (mUpdateSurface) {
            // Someone has requested that we use a specific SurfaceTexture, so
            // tell mLayer about it and set the SurfaceTexture to use the
            // current view size.
            mUpdateSurface = false;

            // Since we are updating the layer, force an update to ensure its
            // parameters are correct (width, height, transform, etc.)
            updateLayer();
            mMatrixChanged = true;

            mLayer.setSurfaceTexture(mSurface);
            mSurface.setDefaultBufferSize(getWidth(), getHeight());
        }

        return mLayer;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (mSurface != null) {
            // When the view becomes invisible, stop updating it, it's a waste of CPU
            // To cancel updates, the easiest thing to do is simply to remove the
            // updates listener
            if (visibility == VISIBLE) {
                if (mLayer != null) {
                    mSurface.setOnFrameAvailableListener(mUpdateListener, mAttachInfo.mHandler);
                }
                updateLayerAndInvalidate();
            } else {
                mSurface.setOnFrameAvailableListener(null);
            }
        }
    }

    private void updateLayer() {
        synchronized (mLock) {
            mUpdateLayer = true;
        }
    }

    private void updateLayerAndInvalidate() {
        synchronized (mLock) {
            mUpdateLayer = true;
        }
        invalidate();
    }

    private void applyUpdate() {
        if (mLayer == null) {
            return;
        }

        synchronized (mLock) {
            if (mUpdateLayer) {
                mUpdateLayer = false;
            } else {
                return;
            }
        }

        mLayer.prepare(getWidth(), getHeight(), mOpaque);
        mLayer.updateSurfaceTexture();

        if (mListener != null) {
            mListener.onSurfaceTextureUpdated(mSurface);
        }
    }

    /**
     * <p>Sets the transform to associate with this texture view.
     * The specified transform applies to the underlying surface
     * texture and does not affect the size or position of the view
     * itself, only of its content.</p>
     *
     * <p>Some transforms might prevent the content from drawing
     * all the pixels contained within this view's bounds. In such
     * situations, make sure this texture view is not marked opaque.</p>
     *
     * @param transform The transform to apply to the content of
     *        this view.
     *
     * @see #getTransform(android.graphics.Matrix)
     * @see #isOpaque()
     * @see #setOpaque(boolean)
     */
    public void setTransform(Matrix transform) {
        mMatrix.set(transform);
        mMatrixChanged = true;
        invalidateParentIfNeeded();
    }

    /**
     * Returns the transform associated with this texture view.
     *
     * @param transform The {@link Matrix} in which to copy the current
     *        transform. Can be null.
     *
     * @return The specified matrix if not null or a new {@link Matrix}
     *         instance otherwise.
     *
     * @see #setTransform(android.graphics.Matrix)
     */
    public Matrix getTransform(Matrix transform) {
        if (transform == null) {
            transform = new Matrix();
        }

        transform.set(mMatrix);

        return transform;
    }

    private void applyTransformMatrix() {
        if (mMatrixChanged && mLayer != null) {
            mLayer.setTransform(mMatrix);
            mMatrixChanged = false;
        }
    }

    /**
     * <p>Returns a {@link android.graphics.Bitmap} representation of the content
     * of the associated surface texture. If the surface texture is not available,
     * this method returns null.</p>
     *
     * <p>The bitmap returned by this method uses the {@link Bitmap.Config#ARGB_8888}
     * pixel format and its dimensions are the same as this view's.</p>
     *
     * <p><strong>Do not</strong> invoke this method from a drawing method
     * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
     *
     * <p>If an error occurs during the copy, an empty bitmap will be returned.</p>
     *
     * @return A valid {@link Bitmap.Config#ARGB_8888} bitmap, or null if the surface
     *         texture is not available or the width &lt;= 0 or the height &lt;= 0
     *
     * @see #isAvailable()
     * @see #getBitmap(android.graphics.Bitmap)
     * @see #getBitmap(int, int)
     */
    public Bitmap getBitmap() {
        return getBitmap(getWidth(), getHeight());
    }

    /**
     * <p>Returns a {@link android.graphics.Bitmap} representation of the content
     * of the associated surface texture. If the surface texture is not available,
     * this method returns null.</p>
     *
     * <p>The bitmap returned by this method uses the {@link Bitmap.Config#ARGB_8888}
     * pixel format.</p>
     *
     * <p><strong>Do not</strong> invoke this method from a drawing method
     * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
     *
     * <p>If an error occurs during the copy, an empty bitmap will be returned.</p>
     *
     * @param width The width of the bitmap to create
     * @param height The height of the bitmap to create
     *
     * @return A valid {@link Bitmap.Config#ARGB_8888} bitmap, or null if the surface
     *         texture is not available or width is &lt;= 0 or height is &lt;= 0
     *
     * @see #isAvailable()
     * @see #getBitmap(android.graphics.Bitmap)
     * @see #getBitmap()
     */
    public Bitmap getBitmap(int width, int height) {
        if (isAvailable() && width > 0 && height > 0) {
            return getBitmap(Bitmap.createBitmap(getResources().getDisplayMetrics(),
                    width, height, Bitmap.Config.ARGB_8888));
        }
        return null;
    }

    /**
     * <p>Copies the content of this view's surface texture into the specified
     * bitmap. If the surface texture is not available, the copy is not executed.
     * The content of the surface texture will be scaled to fit exactly inside
     * the specified bitmap.</p>
     *
     * <p><strong>Do not</strong> invoke this method from a drawing method
     * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
     *
     * <p>If an error occurs, the bitmap is left unchanged.</p>
     *
     * @param bitmap The bitmap to copy the content of the surface texture into,
     *               cannot be null, all configurations are supported
     *
     * @return The bitmap specified as a parameter
     *
     * @see #isAvailable()
     * @see #getBitmap(int, int)
     * @see #getBitmap()
     *
     * @throws IllegalStateException if the hardware rendering context cannot be
     *         acquired to capture the bitmap
     */
    public Bitmap getBitmap(Bitmap bitmap) {
        if (bitmap != null && isAvailable()) {
            applyUpdate();
            applyTransformMatrix();

            // This case can happen if the app invokes setSurfaceTexture() before
            // we are able to create the hardware layer. We can safely initialize
            // the layer here thanks to the validate() call at the beginning of
            // this method
            if (mLayer == null && mUpdateSurface) {
                getTextureLayer();
            }

            if (mLayer != null) {
                mLayer.copyInto(bitmap);
            }
        }
        return bitmap;
    }

    /**
     * Returns true if the {@link SurfaceTexture} associated with this
     * TextureView is available for rendering. When this method returns
     * true, {@link #getSurfaceTexture()} returns a valid surface texture.
     */
    public boolean isAvailable() {
        return mSurface != null;
    }

    /**
     * <p>Start editing the pixels in the surface.  The returned Canvas can be used
     * to draw into the surface's bitmap.  A null is returned if the surface has
     * not been created or otherwise cannot be edited. You will usually need
     * to implement
     * {@link SurfaceTextureListener#onSurfaceTextureAvailable(android.graphics.SurfaceTexture, int, int)}
     * to find out when the Surface is available for use.</p>
     *
     * <p>The content of the Surface is never preserved between unlockCanvas()
     * and lockCanvas(), for this reason, every pixel within the Surface area
     * must be written. The only exception to this rule is when a dirty
     * rectangle is specified, in which case, non-dirty pixels will be
     * preserved.</p>
     *
     * <p>This method can only be used if the underlying surface is not already
     * owned by another producer. For instance, if the TextureView is being used
     * to render the camera's preview you cannot invoke this method.</p>
     *
     * @return A Canvas used to draw into the surface.
     *
     * @see #lockCanvas(android.graphics.Rect)
     * @see #unlockCanvasAndPost(android.graphics.Canvas)
     */
    public Canvas lockCanvas() {
        return lockCanvas(null);
    }

    /**
     * Just like {@link #lockCanvas()} but allows specification of a dirty
     * rectangle. Every pixel within that rectangle must be written; however
     * pixels outside the dirty rectangle will be preserved by the next call
     * to lockCanvas().
     *
     * This method can return null if the underlying surface texture is not
     * available (see {@link #isAvailable()} or if the surface texture is
     * already connected to an image producer (for instance: the camera,
     * OpenGL, a media player, etc.)
     *
     * @param dirty Area of the surface that will be modified.

     * @return A Canvas used to draw into the surface.
     *
     * @see #lockCanvas()
     * @see #unlockCanvasAndPost(android.graphics.Canvas)
     * @see #isAvailable()
     */
    public Canvas lockCanvas(Rect dirty) {
        if (!isAvailable()) return null;

        if (mCanvas == null) {
            mCanvas = new Canvas();
        }

        synchronized (mNativeWindowLock) {
            if (!nLockCanvas(mNativeWindow, mCanvas, dirty)) {
                return null;
            }
        }
        mSaveCount = mCanvas.save();

        return mCanvas;
    }

    /**
     * Finish editing pixels in the surface. After this call, the surface's
     * current pixels will be shown on the screen, but its content is lost,
     * in particular there is no guarantee that the content of the Surface
     * will remain unchanged when lockCanvas() is called again.
     *
     * @param canvas The Canvas previously returned by lockCanvas()
     *
     * @see #lockCanvas()
     * @see #lockCanvas(android.graphics.Rect)
     */
    public void unlockCanvasAndPost(Canvas canvas) {
        if (mCanvas != null && canvas == mCanvas) {
            canvas.restoreToCount(mSaveCount);
            mSaveCount = 0;

            synchronized (mNativeWindowLock) {
                nUnlockCanvasAndPost(mNativeWindow, mCanvas);
            }
        }
    }

    /**
     * Returns the {@link SurfaceTexture} used by this view. This method
     * may return null if the view is not attached to a window or if the surface
     * texture has not been initialized yet.
     *
     * @see #isAvailable()
     */
    public SurfaceTexture getSurfaceTexture() {
        return mSurface;
    }

    /**
     * Set the {@link SurfaceTexture} for this view to use. If a {@link
     * SurfaceTexture} is already being used by this view, it is immediately
     * released and not usable any more.  The {@link
     * SurfaceTextureListener#onSurfaceTextureDestroyed} callback is <b>not</b>
     * called for the previous {@link SurfaceTexture}.  Similarly, the {@link
     * SurfaceTextureListener#onSurfaceTextureAvailable} callback is <b>not</b>
     * called for the {@link SurfaceTexture} passed to setSurfaceTexture.
     *
     * The {@link SurfaceTexture} object must be detached from all OpenGL ES
     * contexts prior to calling this method.
     *
     * @param surfaceTexture The {@link SurfaceTexture} that the view should use.
     * @see SurfaceTexture#detachFromGLContext()
     */
    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) {
            throw new NullPointerException("surfaceTexture must not be null");
        }
        if (surfaceTexture == mSurface) {
            throw new IllegalArgumentException("Trying to setSurfaceTexture to " +
                    "the same SurfaceTexture that's already set.");
        }
        if (surfaceTexture.isReleased()) {
            throw new IllegalArgumentException("Cannot setSurfaceTexture to a " +
                    "released SurfaceTexture");
        }
        if (mSurface != null) {
            nDestroyNativeWindow();
            mSurface.release();
        }
        mSurface = surfaceTexture;
        nCreateNativeWindow(mSurface);

        /*
         * If the view is visible and we already made a layer, update the
         * listener in the new surface to use the existing listener in the view.
         * Otherwise this will be called when the view becomes visible or the
         * layer is created
         */
        if (((mViewFlags & VISIBILITY_MASK) == VISIBLE) && mLayer != null) {
            mSurface.setOnFrameAvailableListener(mUpdateListener, mAttachInfo.mHandler);
        }
        mUpdateSurface = true;
        invalidateParentIfNeeded();
    }

    /**
     * Returns the {@link SurfaceTextureListener} currently associated with this
     * texture view.
     *
     * @see #setSurfaceTextureListener(android.view.TextureView.SurfaceTextureListener)
     * @see SurfaceTextureListener
     */
    public SurfaceTextureListener getSurfaceTextureListener() {
        return mListener;
    }

    /**
     * Sets the {@link SurfaceTextureListener} used to listen to surface
     * texture events.
     *
     * @see #getSurfaceTextureListener()
     * @see SurfaceTextureListener
     */
    public void setSurfaceTextureListener(SurfaceTextureListener listener) {
        mListener = listener;
    }

    private final SurfaceTexture.OnFrameAvailableListener mUpdateListener =
            new SurfaceTexture.OnFrameAvailableListener() {
        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            updateLayer();
            invalidate();
        }
    };

    /**
     * This listener can be used to be notified when the surface texture
     * associated with this texture view is available.
     */
    public static interface SurfaceTextureListener {
        /**
         * Invoked when a {@link TextureView}'s SurfaceTexture is ready for use.
         *
         * @param surface The surface returned by
         *                {@link android.view.TextureView#getSurfaceTexture()}
         * @param width The width of the surface
         * @param height The height of the surface
         */
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height);

        /**
         * Invoked when the {@link SurfaceTexture}'s buffers size changed.
         *
         * @param surface The surface returned by
         *                {@link android.view.TextureView#getSurfaceTexture()}
         * @param width The new width of the surface
         * @param height The new height of the surface
         */
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height);

        /**
         * Invoked when the specified {@link SurfaceTexture} is about to be destroyed.
         * If returns true, no rendering should happen inside the surface texture after this method
         * is invoked. If returns false, the client needs to call {@link SurfaceTexture#release()}.
         * Most applications should return true.
         *
         * @param surface The surface about to be destroyed
         */
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface);

        /**
         * Invoked when the specified {@link SurfaceTexture} is updated through
         * {@link SurfaceTexture#updateTexImage()}.
         *
         * @param surface The surface just updated
         */
        public void onSurfaceTextureUpdated(SurfaceTexture surface);
    }

    private native void nCreateNativeWindow(SurfaceTexture surface);
    private native void nDestroyNativeWindow();

    private static native boolean nLockCanvas(long nativeWindow, Canvas canvas, Rect dirty);
    private static native void nUnlockCanvasAndPost(long nativeWindow, Canvas canvas);
}
