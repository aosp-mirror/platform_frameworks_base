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


package android.filterpacks.ui;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.FilterSurfaceView;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.GLEnvironment;
import android.filterfw.core.GLFrame;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import android.view.SurfaceHolder;

import android.util.Log;

/**
 * @hide
 */
public class SurfaceRenderFilter extends Filter implements SurfaceHolder.Callback {

    private final int RENDERMODE_STRETCH   = 0;
    private final int RENDERMODE_FIT       = 1;
    private final int RENDERMODE_FILL_CROP = 2;

    /** Required. Sets the destination filter surface view for this
     * node.
     */
    @GenerateFinalPort(name = "surfaceView")
    private FilterSurfaceView mSurfaceView;

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

    private boolean mIsBound = false;

    private ShaderProgram mProgram;
    private GLFrame mScreen;
    private int mRenderMode = RENDERMODE_FIT;
    private float mAspectRatio = 1.f;

    private int mScreenWidth;
    private int mScreenHeight;

    private boolean mLogVerbose;
    private static final String TAG = "SurfaceRenderFilter";

    public SurfaceRenderFilter(String name) {
        super(name);

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    }

    @Override
    public void setupPorts() {
        // Make sure we have a SurfaceView
        if (mSurfaceView == null) {
            throw new RuntimeException("NULL SurfaceView passed to SurfaceRenderFilter");
        }

        // Add input port
        addMaskedInputPort("frame", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
    }

    public void updateRenderMode() {
        if (mRenderModeString != null) {
            if (mRenderModeString.equals("stretch")) {
                mRenderMode = RENDERMODE_STRETCH;
            } else if (mRenderModeString.equals("fit")) {
                mRenderMode = RENDERMODE_FIT;
            } else if (mRenderModeString.equals("fill_crop")) {
                mRenderMode = RENDERMODE_FILL_CROP;
            } else {
                throw new RuntimeException("Unknown render mode '" + mRenderModeString + "'!");
            }
        }
        updateTargetRect();
    }

    @Override
    public void prepare(FilterContext context) {
        // Create identity shader to render, and make sure to render upside-down, as textures
        // are stored internally bottom-to-top.
        mProgram = ShaderProgram.createIdentity(context);
        mProgram.setSourceRect(0, 1, 1, -1);
        mProgram.setClearsOutput(true);
        mProgram.setClearColor(0.0f, 0.0f, 0.0f);

        updateRenderMode();

        // Create a frame representing the screen
        MutableFrameFormat screenFormat = ImageFormat.create(mSurfaceView.getWidth(),
                                                             mSurfaceView.getHeight(),
                                                             ImageFormat.COLORSPACE_RGBA,
                                                             FrameFormat.TARGET_GPU);
        mScreen = (GLFrame)context.getFrameManager().newBoundFrame(screenFormat,
                                                                   GLFrame.EXISTING_FBO_BINDING,
                                                                   0);
    }

    @Override
    public void open(FilterContext context) {
        // Bind surface view to us. This will emit a surfaceCreated and surfaceChanged call that
        // will update our screen width and height.
        mSurfaceView.unbind();
        mSurfaceView.bindToListener(this, context.getGLEnvironment());
    }

    @Override
    public void process(FilterContext context) {
        // Make sure we are bound to a surface before rendering
        if (!mIsBound) {
            Log.w("SurfaceRenderFilter",
                  this + ": Ignoring frame as there is no surface to render to!");
            return;
        }

        if (mLogVerbose) Log.v(TAG, "Starting frame processing");

        GLEnvironment glEnv = mSurfaceView.getGLEnv();
        if (glEnv != context.getGLEnvironment()) {
            throw new RuntimeException("Surface created under different GLEnvironment!");
        }


        // Get input frame
        Frame input = pullInput("frame");
        boolean createdFrame = false;

        float currentAspectRatio = (float)input.getFormat().getWidth() / input.getFormat().getHeight();
        if (currentAspectRatio != mAspectRatio) {
            if (mLogVerbose) Log.v(TAG, "New aspect ratio: " + currentAspectRatio +", previously: " + mAspectRatio);
            mAspectRatio = currentAspectRatio;
            updateTargetRect();
        }

        // See if we need to copy to GPU
        Frame gpuFrame = null;
        if (mLogVerbose) Log.v("SurfaceRenderFilter", "Got input format: " + input.getFormat());
        int target = input.getFormat().getTarget();
        if (target != FrameFormat.TARGET_GPU) {
            gpuFrame = context.getFrameManager().duplicateFrameToTarget(input,
                                                                        FrameFormat.TARGET_GPU);
            createdFrame = true;
        } else {
            gpuFrame = input;
        }

        // Activate our surface
        glEnv.activateSurfaceWithId(mSurfaceView.getSurfaceId());

        // Process
        mProgram.process(gpuFrame, mScreen);

        // And swap buffers
        glEnv.swapBuffers();

        if (createdFrame) {
            gpuFrame.release();
        }
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        updateTargetRect();
    }

    @Override
    public void close(FilterContext context) {
        mSurfaceView.unbind();
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mScreen != null) {
            mScreen.release();
        }
    }

    @Override
    public synchronized void surfaceCreated(SurfaceHolder holder) {
        mIsBound = true;
    }

    @Override
    public synchronized void surfaceChanged(SurfaceHolder holder,
                                            int format,
                                            int width,
                                            int height) {
        // If the screen is null, we do not care about surface changes (yet). Once we have a
        // screen object, we need to keep track of these changes.
        if (mScreen != null) {
            mScreenWidth = width;
            mScreenHeight = height;
            mScreen.setViewport(0, 0, mScreenWidth, mScreenHeight);
            updateTargetRect();
        }
    }

    @Override
    public synchronized void surfaceDestroyed(SurfaceHolder holder) {
        mIsBound = false;
    }

    private void updateTargetRect() {
        if (mScreenWidth > 0 && mScreenHeight > 0 && mProgram != null) {
            float screenAspectRatio = (float)mScreenWidth / mScreenHeight;
            float relativeAspectRatio = screenAspectRatio / mAspectRatio;

            switch (mRenderMode) {
                case RENDERMODE_STRETCH:
                    mProgram.setTargetRect(0, 0, 1, 1);
                    break;
                case RENDERMODE_FIT:
                    if (relativeAspectRatio > 1.0f) {
                        // Screen is wider than the camera, scale down X
                        mProgram.setTargetRect(0.5f - 0.5f / relativeAspectRatio, 0.0f,
                                               1.0f / relativeAspectRatio, 1.0f);
                    } else {
                        // Screen is taller than the camera, scale down Y
                        mProgram.setTargetRect(0.0f, 0.5f - 0.5f * relativeAspectRatio,
                                               1.0f, relativeAspectRatio);
                    }
                    break;
                case RENDERMODE_FILL_CROP:
                    if (relativeAspectRatio > 1) {
                        // Screen is wider than the camera, crop in Y
                        mProgram.setTargetRect(0.0f, 0.5f - 0.5f * relativeAspectRatio,
                                               1.0f, relativeAspectRatio);
                    } else {
                        // Screen is taller than the camera, crop in X
                        mProgram.setTargetRect(0.5f - 0.5f / relativeAspectRatio, 0.0f,
                                               1.0f / relativeAspectRatio, 1.0f);
                    }
                    break;
            }
        }
    }
}
