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

package androidx.media.filterpacks.image;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.RenderTarget;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.ViewFilter;

public class SurfaceHolderTarget extends ViewFilter {

    private SurfaceHolder mSurfaceHolder = null;
    private RenderTarget mRenderTarget = null;
    private ImageShader mShader = null;
    private boolean mHasSurface = false;

    private SurfaceHolder.Callback mSurfaceHolderListener = new SurfaceHolder.Callback() {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // This just makes sure the holder is still the one we expect.
            onSurfaceCreated(holder);
        }

        @Override
        public void surfaceCreated (SurfaceHolder holder) {
            onSurfaceCreated(holder);
        }

        @Override
        public void surfaceDestroyed (SurfaceHolder holder) {
            onDestroySurface();
        }
    };

    public SurfaceHolderTarget(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public void onBindToView(View view) {
        if (view instanceof SurfaceView) {
            SurfaceHolder holder = ((SurfaceView)view).getHolder();
            if (holder == null) {
                throw new RuntimeException("Could not get SurfaceHolder from SurfaceView "
                    + view + "!");
            }
            setSurfaceHolder(holder);
        } else {
            throw new IllegalArgumentException("View must be a SurfaceView!");
        }
    }

    public void setSurfaceHolder(SurfaceHolder holder) {
        if (isRunning()) {
            throw new IllegalStateException("Cannot set SurfaceHolder while running!");
        }
        mSurfaceHolder = holder;
    }

    public synchronized void onDestroySurface() {
        if (mRenderTarget != null) {
            mRenderTarget.release();
            mRenderTarget = null;
        }
        mHasSurface = false;
    }

    @Override
    public Signature getSignature() {
        FrameType imageType = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        return super.getSignature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageType)
            .disallowOtherPorts();
    }

    @Override
    protected void onInputPortOpen(InputPort port) {
        super.connectViewInputs(port);
    }

    @Override
    protected synchronized void onPrepare() {
        if (isOpenGLSupported()) {
            mShader = ImageShader.createIdentity();
        }
    }

    @Override
    protected synchronized void onOpen() {
        mSurfaceHolder.addCallback(mSurfaceHolderListener);
        Surface surface = mSurfaceHolder.getSurface();
        mHasSurface = (surface != null) && surface.isValid();
    }

    @Override
    protected synchronized void onProcess() {
        FrameImage2D image = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        if (mHasSurface) {
            // Synchronize the surface holder in case another filter is accessing this surface.
            synchronized (mSurfaceHolder) {
                if (isOpenGLSupported()) {
                    renderGL(image);
                } else {
                    renderCanvas(image);
                }
            }
        }
    }

    /**
     * Renders the given frame to the screen using GLES2.
     * @param image the image to render
     */
    private void renderGL(FrameImage2D image) {
        if (mRenderTarget == null) {
            mRenderTarget = RenderTarget.currentTarget().forSurfaceHolder(mSurfaceHolder);
            mRenderTarget.registerAsDisplaySurface();
        }
        Rect frameRect = new Rect(0, 0, image.getWidth(), image.getHeight());
        Rect surfRect = mSurfaceHolder.getSurfaceFrame();
        setupShader(mShader, frameRect, surfRect);
        mShader.process(image.lockTextureSource(),
                        mRenderTarget,
                        surfRect.width(),
                        surfRect.height());
        image.unlock();
        mRenderTarget.swapBuffers();
    }

    /**
     * Renders the given frame to the screen using a Canvas.
     * @param image the image to render
     */
    private void renderCanvas(FrameImage2D image) {
        Canvas canvas = mSurfaceHolder.lockCanvas();
        Bitmap bitmap = image.toBitmap();
        Rect sourceRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        Rect surfaceRect = mSurfaceHolder.getSurfaceFrame();
        RectF targetRect = getTargetRect(sourceRect, surfaceRect);
        canvas.drawColor(Color.BLACK);
        if (targetRect.width() > 0 && targetRect.height() > 0) {
            canvas.scale(surfaceRect.width(), surfaceRect.height());
            canvas.drawBitmap(bitmap, sourceRect, targetRect, new Paint());
        }
        mSurfaceHolder.unlockCanvasAndPost(canvas);
    }

    @Override
    protected synchronized void onClose() {
        if (mRenderTarget != null) {
            mRenderTarget.unregisterAsDisplaySurface();
            mRenderTarget.release();
            mRenderTarget = null;
        }
        if (mSurfaceHolder != null) {
            mSurfaceHolder.removeCallback(mSurfaceHolderListener);
        }
    }

    private synchronized void onSurfaceCreated(SurfaceHolder holder) {
        if (mSurfaceHolder != holder) {
            throw new RuntimeException("Unexpected Holder!");
        }
        mHasSurface = true;
    }

}

