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

package android.filterpacks.videosrc;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.GLFrame;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.graphics.SurfaceTexture;
import android.os.ConditionVariable;
import android.opengl.Matrix;

import android.util.Log;

/** <p>A filter that converts textures from a SurfaceTexture object into frames for
 * processing in the filter framework.</p>
 *
 * <p>To use, connect up the sourceListener callback, and then when executing
 * the graph, use the SurfaceTexture object passed to the callback to feed
 * frames into the filter graph. For example, pass the SurfaceTexture into
 * {@link
 * android.hardware.Camera.setPreviewTexture(android.graphics.SurfaceTexture)}.
 * This filter is intended for applications that need for flexibility than the
 * CameraSource and MediaSource provide. Note that the application needs to
 * provide width and height information for the SurfaceTextureSource, which it
 * should obtain from wherever the SurfaceTexture data is coming from to avoid
 * unnecessary resampling.</p>
 *
 * @hide
 */
public class SurfaceTextureSource extends Filter {

    /** User-visible parameters */

    /** The callback interface for the sourceListener parameter */
    public interface SurfaceTextureSourceListener {
        public void onSurfaceTextureSourceReady(SurfaceTexture source);
    }
    /** A callback to send the internal SurfaceTexture object to, once it is
     * created. This callback will be called when the the filter graph is
     * preparing to execute, but before any processing has actually taken
     * place. The SurfaceTexture object passed to this callback is the only way
     * to feed this filter. When the filter graph is shutting down, this
     * callback will be called again with null as the source.
     *
     * This callback may be called from an arbitrary thread, so it should not
     * assume it is running in the UI thread in particular.
     */
    @GenerateFinalPort(name = "sourceListener")
    private SurfaceTextureSourceListener mSourceListener;

    /** The width of the output image frame. If the texture width for the
     * SurfaceTexture source is known, use it here to minimize resampling. */
    @GenerateFieldPort(name = "width")
    private int mWidth;

    /** The height of the output image frame. If the texture height for the
     * SurfaceTexture source is known, use it here to minimize resampling. */
    @GenerateFieldPort(name = "height")
    private int mHeight;

    /** Whether the filter will always wait for a new frame from its
     * SurfaceTexture, or whether it will output an old frame again if a new
     * frame isn't available. The filter will always wait for the first frame,
     * to avoid outputting a blank frame. Defaults to true.
     */
    @GenerateFieldPort(name = "waitForNewFrame", hasDefault = true)
    private boolean mWaitForNewFrame = true;

    /** Maximum timeout before signaling error when waiting for a new frame. Set
     * this to zero to disable the timeout and wait indefinitely. In milliseconds.
     */
    @GenerateFieldPort(name = "waitTimeout", hasDefault = true)
    private int mWaitTimeout = 1000;

    /** Whether a timeout is an exception-causing failure, or just causes the
     * filter to close.
     */
    @GenerateFieldPort(name = "closeOnTimeout", hasDefault = true)
    private boolean mCloseOnTimeout = false;

    // Variables for input->output conversion
    private GLFrame mMediaFrame;
    private ShaderProgram mFrameExtractor;
    private SurfaceTexture mSurfaceTexture;
    private MutableFrameFormat mOutputFormat;
    private ConditionVariable mNewFrameAvailable;
    private boolean mFirstFrame;

    private float[] mFrameTransform;
    private float[] mMappedCoords;
    // These default source coordinates perform the necessary flip
    // for converting from MFF/Bitmap origin to OpenGL origin.
    private static final float[] mSourceCoords = { 0, 1, 0, 1,
                                                   1, 1, 0, 1,
                                                   0, 0, 0, 1,
                                                   1, 0, 0, 1 };
    // Shader for output
    private final String mRenderShader =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
            "}\n";

    // Variables for logging

    private static final String TAG = "SurfaceTextureSource";
    private static final boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    public SurfaceTextureSource(String name) {
        super(name);
        mNewFrameAvailable = new ConditionVariable();
        mFrameTransform = new float[16];
        mMappedCoords = new float[16];
    }

    @Override
    public void setupPorts() {
        // Add input port
        addOutputPort("video", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                  FrameFormat.TARGET_GPU));
    }

    private void createFormats() {
        mOutputFormat = ImageFormat.create(mWidth, mHeight,
                                           ImageFormat.COLORSPACE_RGBA,
                                           FrameFormat.TARGET_GPU);
    }

    @Override
    protected void prepare(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Preparing SurfaceTextureSource");

        createFormats();

        // Prepare input
        mMediaFrame = (GLFrame)context.getFrameManager().newBoundFrame(mOutputFormat,
                                                                       GLFrame.EXTERNAL_TEXTURE,
                                                                       0);

        // Prepare output
        mFrameExtractor = new ShaderProgram(context, mRenderShader);
    }

    @Override
    public void open(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Opening SurfaceTextureSource");
        // Create SurfaceTexture anew each time - it can use substantial memory.
        mSurfaceTexture = new SurfaceTexture(mMediaFrame.getTextureId());
        // Connect SurfaceTexture to callback
        mSurfaceTexture.setOnFrameAvailableListener(onFrameAvailableListener);
        // Connect SurfaceTexture to source
        mSourceListener.onSurfaceTextureSourceReady(mSurfaceTexture);
        mFirstFrame = true;
    }

    @Override
    public void process(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Processing new frame");

        // First, get new frame if available
        if (mWaitForNewFrame || mFirstFrame) {
            boolean gotNewFrame;
            if (mWaitTimeout != 0) {
                gotNewFrame = mNewFrameAvailable.block(mWaitTimeout);
                if (!gotNewFrame) {
                    if (!mCloseOnTimeout) {
                        throw new RuntimeException("Timeout waiting for new frame");
                    } else {
                        if (mLogVerbose) Log.v(TAG, "Timeout waiting for a new frame. Closing.");
                        closeOutputPort("video");
                        return;
                    }
                }
            } else {
                mNewFrameAvailable.block();
            }
            mNewFrameAvailable.close();
            mFirstFrame = false;
        }

        mSurfaceTexture.updateTexImage();

        mSurfaceTexture.getTransformMatrix(mFrameTransform);
        Matrix.multiplyMM(mMappedCoords, 0,
                          mFrameTransform, 0,
                          mSourceCoords, 0);
        mFrameExtractor.setSourceRegion(mMappedCoords[0], mMappedCoords[1],
                                        mMappedCoords[4], mMappedCoords[5],
                                        mMappedCoords[8], mMappedCoords[9],
                                        mMappedCoords[12], mMappedCoords[13]);
        // Next, render to output
        Frame output = context.getFrameManager().newFrame(mOutputFormat);
        mFrameExtractor.process(mMediaFrame, output);

        output.setTimestamp(mSurfaceTexture.getTimestamp());

        pushOutput("video", output);
        output.release();
    }

    @Override
    public void close(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "SurfaceTextureSource closed");
        mSourceListener.onSurfaceTextureSourceReady(null);
        mSurfaceTexture.release();
        mSurfaceTexture = null;
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mMediaFrame != null) {
            mMediaFrame.release();
        }
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (name.equals("width") || name.equals("height") ) {
            mOutputFormat.setDimensions(mWidth, mHeight);
        }
    }

    private SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener =
            new SurfaceTexture.OnFrameAvailableListener() {
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            if (mLogVerbose) Log.v(TAG, "New frame from SurfaceTexture");
            mNewFrameAvailable.open();
        }
    };
}
