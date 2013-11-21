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
import android.filterfw.core.GLEnvironment;
import android.filterfw.core.GLFrame;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import android.filterfw.geometry.Quad;
import android.filterfw.geometry.Point;

import android.graphics.SurfaceTexture;

import android.util.Log;

/**
 * @hide
 */
public class SurfaceTextureTarget extends Filter {

    private final int RENDERMODE_STRETCH   = 0;
    private final int RENDERMODE_FIT       = 1;
    private final int RENDERMODE_FILL_CROP = 2;
    private final int RENDERMODE_CUSTOMIZE = 3;

    /** Required. Sets the destination surfaceTexture.
     */
    @GenerateFinalPort(name = "surfaceTexture")
    private SurfaceTexture mSurfaceTexture;

    /** Required. Sets the width of the output surfaceTexture images */
    @GenerateFinalPort(name = "width")
    private int mScreenWidth;

    /** Required. Sets the height of the output surfaceTexture images */
    @GenerateFinalPort(name = "height")
    private int mScreenHeight;


    /** Optional. Control how the incoming frames are rendered onto the
     * output. Default is FIT.
     * RENDERMODE_STRETCH: Just fill the output surfaceView.
     * RENDERMODE_FIT: Keep aspect ratio and fit without cropping. May
     * have black bars.
     * RENDERMODE_FILL_CROP: Keep aspect ratio and fit without black
     * bars. May crop.
     */
    @GenerateFieldPort(name = "renderMode", hasDefault = true)
    private String mRenderModeString;

    @GenerateFieldPort(name = "sourceQuad", hasDefault = true)
    private Quad mSourceQuad = new Quad(new Point(0.0f, 1.0f),
                                        new Point(1.0f, 1.0f),
                                        new Point(0.0f, 0.0f),
                                        new Point(1.0f, 0.0f));

    @GenerateFieldPort(name = "targetQuad", hasDefault = true)
    private Quad mTargetQuad = new Quad(new Point(0.0f, 0.0f),
                                        new Point(1.0f, 0.0f),
                                        new Point(0.0f, 1.0f),
                                        new Point(1.0f, 1.0f));

    private int mSurfaceId;

    private ShaderProgram mProgram;
    private GLFrame mScreen;
    private int mRenderMode = RENDERMODE_FIT;
    private float mAspectRatio = 1.f;

    private boolean mLogVerbose;
    private static final String TAG = "SurfaceTextureTarget";

    public SurfaceTextureTarget(String name) {
        super(name);

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    }

    @Override
    public synchronized void setupPorts() {
        // Make sure we have a SurfaceView
        if (mSurfaceTexture == null) {
            throw new RuntimeException("Null SurfaceTexture passed to SurfaceTextureTarget");
        }

        // Add input port - will accept anything that's 4-channel.
        addMaskedInputPort("frame", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
    }

    public void updateRenderMode() {
        if (mLogVerbose) Log.v(TAG, "updateRenderMode. Thread: " + Thread.currentThread());
        if (mRenderModeString != null) {
            if (mRenderModeString.equals("stretch")) {
                mRenderMode = RENDERMODE_STRETCH;
            } else if (mRenderModeString.equals("fit")) {
                mRenderMode = RENDERMODE_FIT;
            } else if (mRenderModeString.equals("fill_crop")) {
                mRenderMode = RENDERMODE_FILL_CROP;
            } else if (mRenderModeString.equals("customize")) {
                mRenderMode = RENDERMODE_CUSTOMIZE;
            } else {
                throw new RuntimeException("Unknown render mode '" + mRenderModeString + "'!");
            }
        }
        updateTargetRect();
    }

    @Override
    public void prepare(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Prepare. Thread: " + Thread.currentThread());
        // Create identity shader to render, and make sure to render upside-down, as textures
        // are stored internally bottom-to-top.
        mProgram = ShaderProgram.createIdentity(context);
        mProgram.setSourceRect(0, 1, 1, -1);
        mProgram.setClearColor(0.0f, 0.0f, 0.0f);

        updateRenderMode();

        // Create a frame representing the screen
        MutableFrameFormat screenFormat = new MutableFrameFormat(FrameFormat.TYPE_BYTE,
                                                                 FrameFormat.TARGET_GPU);
        screenFormat.setBytesPerSample(4);
        screenFormat.setDimensions(mScreenWidth, mScreenHeight);
        mScreen = (GLFrame)context.getFrameManager().newBoundFrame(screenFormat,
                                                                   GLFrame.EXISTING_FBO_BINDING,
                                                                   0);
    }

    @Override
    public synchronized void open(FilterContext context) {
        // Set up SurfaceTexture internals
        if (mSurfaceTexture == null) {
            Log.e(TAG, "SurfaceTexture is null!!");
            throw new RuntimeException("Could not register SurfaceTexture: " + mSurfaceTexture);
        }
        mSurfaceId = context.getGLEnvironment().registerSurfaceTexture(
            mSurfaceTexture, mScreenWidth, mScreenHeight);
        if (mSurfaceId <= 0) {
            throw new RuntimeException("Could not register SurfaceTexture: " + mSurfaceTexture);
        }
    }


    // Once the surface is unregistered, we still need the surfacetexture reference.
    // That is because when the the filter graph stops and starts again, the app
    // may not set the mSurfaceTexture again on the filter. In some cases, the app
    // may not even know that the graph has re-started. So it is difficult to enforce
    // that condition on an app using this filter. The only case where we need
    // to let go of the mSurfaceTexure reference is when the app wants to shut
    // down the graph on purpose, such as in the disconnect call.
    @Override
    public synchronized void close(FilterContext context) {
        if (mSurfaceId > 0) {
            context.getGLEnvironment().unregisterSurfaceId(mSurfaceId);
            mSurfaceId = -1;
        }
    }

    // This should be called from the client side when the surfacetexture is no longer
    // valid. e.g. from onPause() in the application using the filter graph.
    // In this case, we need to let go of our surfacetexture reference.
    public synchronized void disconnect(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "disconnect");
        if (mSurfaceTexture == null) {
            Log.d(TAG, "SurfaceTexture is already null. Nothing to disconnect.");
            return;
        }
        mSurfaceTexture = null;
        // Make sure we unregister the surface as well if a surface was registered.
        // There can be a situation where the surface was not registered but the
        // surfacetexture was valid. For example, the disconnect can be called before
        // the filter was opened. Hence, the surfaceId may not be a valid one here,
        // and need to check for its validity.
        if (mSurfaceId > 0) {
            context.getGLEnvironment().unregisterSurfaceId(mSurfaceId);
            mSurfaceId = -1;
        }
    }

    @Override
    public synchronized void process(FilterContext context) {
        // Surface is not registered. Nothing to render into.
        if (mSurfaceId <= 0) {
            return;
        }
        GLEnvironment glEnv = context.getGLEnvironment();

        // Get input frame
        Frame input = pullInput("frame");
        boolean createdFrame = false;

        float currentAspectRatio =
          (float)input.getFormat().getWidth() / input.getFormat().getHeight();
        if (currentAspectRatio != mAspectRatio) {
            if (mLogVerbose) {
                Log.v(TAG, "Process. New aspect ratio: " + currentAspectRatio +
                    ", previously: " + mAspectRatio + ". Thread: " + Thread.currentThread());
            }
            mAspectRatio = currentAspectRatio;
            updateTargetRect();
        }

        // See if we need to copy to GPU
        Frame gpuFrame = null;
        int target = input.getFormat().getTarget();
        if (target != FrameFormat.TARGET_GPU) {
            gpuFrame = context.getFrameManager().duplicateFrameToTarget(input,
                                                                        FrameFormat.TARGET_GPU);
            createdFrame = true;
        } else {
            gpuFrame = input;
        }

        // Activate our surface
        glEnv.activateSurfaceWithId(mSurfaceId);

        // Process
        mProgram.process(gpuFrame, mScreen);

        glEnv.setSurfaceTimestamp(input.getTimestamp());

        // And swap buffers
        glEnv.swapBuffers();

        if (createdFrame) {
            gpuFrame.release();
        }
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "FPVU. Thread: " + Thread.currentThread());
        updateRenderMode();
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mScreen != null) {
            mScreen.release();
        }
    }

    private void updateTargetRect() {
        if (mLogVerbose) Log.v(TAG, "updateTargetRect. Thread: " + Thread.currentThread());
        if (mScreenWidth > 0 && mScreenHeight > 0 && mProgram != null) {
            float screenAspectRatio = (float)mScreenWidth / mScreenHeight;
            float relativeAspectRatio = screenAspectRatio / mAspectRatio;
            if (mLogVerbose) {
                Log.v(TAG, "UTR. screen w = " + (float)mScreenWidth + " x screen h = " +
                    (float)mScreenHeight + " Screen AR: " + screenAspectRatio +
                    ", frame AR: "  + mAspectRatio + ", relative AR: " + relativeAspectRatio);
            }

            if (relativeAspectRatio == 1.0f && mRenderMode != RENDERMODE_CUSTOMIZE) {
                mProgram.setTargetRect(0, 0, 1, 1);
                mProgram.setClearsOutput(false);
            } else {
                switch (mRenderMode) {
                    case RENDERMODE_STRETCH:
                        mTargetQuad.p0.set(0f, 0.0f);
                        mTargetQuad.p1.set(1f, 0.0f);
                        mTargetQuad.p2.set(0f, 1.0f);
                        mTargetQuad.p3.set(1f, 1.0f);
                        mProgram.setClearsOutput(false);
                        break;
                    case RENDERMODE_FIT:
                        if (relativeAspectRatio > 1.0f) {
                            // Screen is wider than the camera, scale down X
                            mTargetQuad.p0.set(0.5f - 0.5f / relativeAspectRatio, 0.0f);
                            mTargetQuad.p1.set(0.5f + 0.5f / relativeAspectRatio, 0.0f);
                            mTargetQuad.p2.set(0.5f - 0.5f / relativeAspectRatio, 1.0f);
                            mTargetQuad.p3.set(0.5f + 0.5f / relativeAspectRatio, 1.0f);

                        } else {
                            // Screen is taller than the camera, scale down Y
                            mTargetQuad.p0.set(0.0f, 0.5f - 0.5f * relativeAspectRatio);
                            mTargetQuad.p1.set(1.0f, 0.5f - 0.5f * relativeAspectRatio);
                            mTargetQuad.p2.set(0.0f, 0.5f + 0.5f * relativeAspectRatio);
                            mTargetQuad.p3.set(1.0f, 0.5f + 0.5f * relativeAspectRatio);
                        }
                        mProgram.setClearsOutput(true);
                        break;
                    case RENDERMODE_FILL_CROP:
                        if (relativeAspectRatio > 1) {
                            // Screen is wider than the camera, crop in Y
                            mTargetQuad.p0.set(0.0f, 0.5f - 0.5f * relativeAspectRatio);
                            mTargetQuad.p1.set(1.0f, 0.5f - 0.5f * relativeAspectRatio);
                            mTargetQuad.p2.set(0.0f, 0.5f + 0.5f * relativeAspectRatio);
                            mTargetQuad.p3.set(1.0f, 0.5f + 0.5f * relativeAspectRatio);
                        } else {
                            // Screen is taller than the camera, crop in X
                            mTargetQuad.p0.set(0.5f - 0.5f / relativeAspectRatio, 0.0f);
                            mTargetQuad.p1.set(0.5f + 0.5f / relativeAspectRatio, 0.0f);
                            mTargetQuad.p2.set(0.5f - 0.5f / relativeAspectRatio, 1.0f);
                            mTargetQuad.p3.set(0.5f + 0.5f / relativeAspectRatio, 1.0f);
                        }
                        mProgram.setClearsOutput(true);
                        break;
                    case RENDERMODE_CUSTOMIZE:
                        ((ShaderProgram) mProgram).setSourceRegion(mSourceQuad);
                        break;
                }
                if (mLogVerbose) Log.v(TAG,  "UTR. quad: " + mTargetQuad);
                ((ShaderProgram) mProgram).setTargetRegion(mTargetQuad);
            }
        }
    }
}
