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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
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
 *      public void onSurfaceTextureDestroyed(SurfaceTexture surface) {
 *          mCamera.stopPreview();
 *          mCamera.release();
 *      }
 *
 *      public void onSurfaceTextureUpdated(SurfaceTexture surface) {
 *          // Ignored
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
 * @see SurfaceView
 * @see SurfaceTexture
 */
public class TextureView extends View {
    private static final String LOG_TAG = "TextureView";

    private HardwareLayer mLayer;
    private SurfaceTexture mSurface;
    private SurfaceTextureListener mListener;

    private boolean mOpaque = true;

    private final Runnable mUpdateLayerAction = new Runnable() {
        @Override
        public void run() {
            updateLayer();
        }
    };
    private SurfaceTexture.OnFrameAvailableListener mUpdateListener;

    /**
     * Creates a new TextureView.
     * 
     * @param context The context to associate this view with.
     */
    public TextureView(Context context) {
        super(context);
        init();
    }

    /**
     * Creates a new TextureView.
     * 
     * @param context The context to associate this view with.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public TextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Creates a new TextureView.
     * 
     * @param context The context to associate this view with.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyle The default style to apply to this view. If 0, no style
     *        will be applied (beyond what is included in the theme). This may
     *        either be an attribute resource, whose value will be retrieved
     *        from the current theme, or an explicit style resource.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public TextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mLayerPaint = new Paint();
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
            if (mLayer != null) updateLayer();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!isHardwareAccelerated()) {
            Log.w(LOG_TAG, "A TextureView or a subclass can only be "
                    + "used with hardware acceleration enabled.");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (isHardwareAccelerated() && mLayer != null) {
            if (mListener != null) {
                mListener.onSurfaceTextureDestroyed(mSurface);
            }

            mLayer.destroy();            
            mSurface = null;
            mLayer = null;
        }
    }

    /**
     * The layer type of a TextureView is ignored since a TextureView is always
     * considered to act as a hardware layer. The optional paint supplied to this
     * method will however be taken into account when rendering the content of
     * this TextureView.
     * 
     * @param layerType The ype of layer to use with this view, must be one of
     *        {@link #LAYER_TYPE_NONE}, {@link #LAYER_TYPE_SOFTWARE} or
     *        {@link #LAYER_TYPE_HARDWARE}
     * @param paint The paint used to compose the layer. This argument is optional
     *        and can be null. It is ignored when the layer type is
     *        {@link #LAYER_TYPE_NONE}
     */
    @Override
    public void setLayerType(int layerType, Paint paint) {
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

    /**
     * Subclasses of TextureView cannot do their own rendering
     * with the {@link Canvas} object.
     * 
     * @param canvas The Canvas to which the View is rendered.
     */
    @Override
    public final void draw(Canvas canvas) {
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
            nSetDefaultBufferSize(mSurface, getWidth(), getHeight());
            if (mListener != null) {
                mListener.onSurfaceTextureSizeChanged(mSurface, getWidth(), getHeight());
            }
        }
    }

    @Override
    HardwareLayer getHardwareLayer() {
        if (mAttachInfo == null || mAttachInfo.mHardwareRenderer == null) {
            return null;
        }

        if (mLayer == null) {
            mLayer = mAttachInfo.mHardwareRenderer.createHardwareLayer(mOpaque);
            mSurface = mAttachInfo.mHardwareRenderer.createSurfaceTexture(mLayer);
            nSetDefaultBufferSize(mSurface, getWidth(), getHeight());

            mUpdateListener = new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    // Per SurfaceTexture's documentation, the callback may be invoked
                    // from an arbitrary thread
                    post(mUpdateLayerAction);
                }
            };
            mSurface.setOnFrameAvailableListener(mUpdateListener);

            if (mListener != null) {
                mListener.onSurfaceTextureAvailable(mSurface, getWidth(), getHeight());
            }
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
                mSurface.setOnFrameAvailableListener(mUpdateListener);
                updateLayer();
            } else {
                mSurface.setOnFrameAvailableListener(null);
            }
        }
    }

    private void updateLayer() {
        if (mAttachInfo == null || mAttachInfo.mHardwareRenderer == null) {
            return;
        }

        mAttachInfo.mHardwareRenderer.updateTextureLayer(mLayer, getWidth(), getHeight(), mOpaque);

        if (mListener != null) {
            mListener.onSurfaceTextureUpdated(mSurface);
        }

        invalidate();
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
            return getBitmap(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
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
     */
    public Bitmap getBitmap(Bitmap bitmap) {
        if (bitmap != null && isAvailable()) {
            mAttachInfo.mHardwareRenderer.copyLayer(mLayer, bitmap);
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
         * After this method is invoked, no rendering should happen inside the surface
         * texture.
         * 
         * @param surface The surface about to be destroyed
         */
        public void onSurfaceTextureDestroyed(SurfaceTexture surface);

        /**
         * Invoked when the specified {@link SurfaceTexture} is updated through
         * {@link SurfaceTexture#updateTexImage()}.
         * 
         * @param surface The surface just updated
         */
        public void onSurfaceTextureUpdated(SurfaceTexture surface);
    }

    private static native void nSetDefaultBufferSize(SurfaceTexture surfaceTexture,
            int width, int height);
}
