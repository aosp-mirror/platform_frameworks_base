/*
 * Copyright (C) 2012 The Android Open Source Project
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

package androidx.media.filterfw;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.PreviewCallback;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.opengl.GLES20;
import android.os.Build.VERSION;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGLContext;

/**
 * The CameraStreamer streams Frames from a camera to connected clients.
 *
 * There is one centralized CameraStreamer object per MffContext, and only one stream can be
 * active at any time. The CameraStreamer acts as a Camera "server" that streams frames to any
 * number of connected clients. Typically, these are CameraSource filters that are part of a
 * graph, but other clients can be written as well.
 */
public class CameraStreamer {

    /** Camera Facing: Don't Care: Picks any available camera. */
    public static final int FACING_DONTCARE = 0;
    /** Camera Facing: Front: Use the front facing camera. */
    public static final int FACING_FRONT = 1;
    /** Camera Facing: Back: Use the rear facing camera. */
    public static final int FACING_BACK = 2;

    /** How long the streamer should wait to acquire the camera before giving up. */
    public static long MAX_CAMERA_WAIT_TIME = 5;

    /**
     * The global camera lock, that is closed when the camera is acquired by any CameraStreamer,
     * and opened when a streamer is done using the camera.
     */
    static ReentrantLock mCameraLock = new ReentrantLock();

    /** The Camera thread that grabs frames from the camera */
    private CameraRunnable mCameraRunner = null;

    private abstract class CamFrameHandler {
        protected int mCameraWidth;
        protected int mCameraHeight;
        protected int mOutWidth;
        protected int mOutHeight;
        protected CameraRunnable mRunner;

        /** Map of GLSL shaders (one for each target context) */
        protected HashMap<EGLContext, ImageShader> mTargetShaders
            = new HashMap<EGLContext, ImageShader>();

        /** Map of target textures (one for each target context) */
        protected HashMap<EGLContext, TextureSource> mTargetTextures
            = new HashMap<EGLContext, TextureSource>();

        /** Map of set of clients (one for each target context) */
        protected HashMap<EGLContext, Set<FrameClient>> mContextClients
            = new HashMap<EGLContext, Set<FrameClient>>();

        /** List of clients that are consuming camera frames. */
        protected Vector<FrameClient> mClients = new Vector<FrameClient>();

        public void initWithRunner(CameraRunnable camRunner) {
            mRunner = camRunner;
        }

        public void setCameraSize(int width, int height) {
            mCameraWidth = width;
            mCameraHeight = height;
        }

        public void registerClient(FrameClient client) {
            EGLContext context = RenderTarget.currentContext();
            Set<FrameClient> clientTargets = clientsForContext(context);
            clientTargets.add(client);
            mClients.add(client);
            onRegisterClient(client, context);
        }

        public void unregisterClient(FrameClient client) {
            EGLContext context = RenderTarget.currentContext();
            Set<FrameClient> clientTargets = clientsForContext(context);
            clientTargets.remove(client);
            if (clientTargets.isEmpty()) {
                onCleanupContext(context);
            }
            mClients.remove(client);
        }

        public abstract void setupServerFrame();
        public abstract void updateServerFrame();
        public abstract void grabFrame(FrameImage2D targetFrame);
        public abstract void release();

        public void onUpdateCameraOrientation(int orientation) {
            if (orientation % 180 != 0) {
                mOutWidth = mCameraHeight;
                mOutHeight = mCameraWidth;
            } else {
                mOutWidth = mCameraWidth;
                mOutHeight = mCameraHeight;
            }
        }

        protected Set<FrameClient> clientsForContext(EGLContext context) {
            Set<FrameClient> clients = mContextClients.get(context);
            if (clients == null) {
                clients = new HashSet<FrameClient>();
                mContextClients.put(context, clients);
            }
            return clients;
        }

        protected void onRegisterClient(FrameClient client, EGLContext context) {
        }

        protected void onCleanupContext(EGLContext context) {
            TextureSource texture = mTargetTextures.get(context);
            ImageShader shader = mTargetShaders.get(context);
            if (texture != null) {
                texture.release();
                mTargetTextures.remove(context);
            }
            if (shader != null) {
                mTargetShaders.remove(context);
            }
        }

        protected TextureSource textureForContext(EGLContext context) {
            TextureSource texture = mTargetTextures.get(context);
            if (texture == null) {
                texture = createClientTexture();
                mTargetTextures.put(context, texture);
            }
            return texture;
        }

        protected ImageShader shaderForContext(EGLContext context) {
            ImageShader shader = mTargetShaders.get(context);
            if (shader == null) {
                shader = createClientShader();
                mTargetShaders.put(context, shader);
            }
            return shader;
        }

        protected ImageShader createClientShader() {
            return null;
        }

        protected TextureSource createClientTexture() {
            return null;
        }

        public boolean isFrontMirrored() {
            return true;
        }
    }

    // Jellybean (and later) back-end
    @TargetApi(16)
    private class CamFrameHandlerJB extends CamFrameHandlerICS {

        @Override
        public void setupServerFrame() {
            setupPreviewTexture(mRunner.mCamera);
        }

        @Override
        public synchronized void updateServerFrame() {
            updateSurfaceTexture();
            informClients();
        }

        @Override
        public synchronized void grabFrame(FrameImage2D targetFrame) {
            TextureSource targetTex = TextureSource.newExternalTexture();
            ImageShader copyShader = shaderForContext(RenderTarget.currentContext());
            if (targetTex == null || copyShader == null) {
                throw new RuntimeException("Attempting to grab camera frame from unknown "
                    + "thread: " + Thread.currentThread() + "!");
            }
            mPreviewSurfaceTexture.attachToGLContext(targetTex.getTextureId());
            updateTransform(copyShader);
            updateShaderTargetRect(copyShader);
            targetFrame.resize(new int[] { mOutWidth, mOutHeight });
            copyShader.process(targetTex,
                               targetFrame.lockRenderTarget(),
                               mOutWidth,
                               mOutHeight);
            targetFrame.setTimestamp(mPreviewSurfaceTexture.getTimestamp());
            targetFrame.unlock();
            mPreviewSurfaceTexture.detachFromGLContext();
            targetTex.release();
        }

        @Override
        protected void updateShaderTargetRect(ImageShader shader) {
            if ((mRunner.mActualFacing == FACING_FRONT) && mRunner.mFlipFront) {
                shader.setTargetRect(1f, 1f, -1f, -1f);
            } else {
                shader.setTargetRect(0f, 1f, 1f, -1f);
            }
        }

        @Override
        protected void setupPreviewTexture(Camera camera) {
            super.setupPreviewTexture(camera);
            mPreviewSurfaceTexture.detachFromGLContext();
        }

        protected void updateSurfaceTexture() {
            mPreviewSurfaceTexture.attachToGLContext(mPreviewTexture.getTextureId());
            mPreviewSurfaceTexture.updateTexImage();
            mPreviewSurfaceTexture.detachFromGLContext();
        }

        protected void informClients() {
            synchronized (mClients) {
                for (FrameClient client : mClients) {
                    client.onCameraFrameAvailable();
                }
            }
        }
    }

    // ICS (and later) back-end
    @TargetApi(15)
    private class CamFrameHandlerICS extends CamFrameHandler  {

        protected static final String mCopyShaderSource =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
            "}\n";

        /** The camera transform matrix */
        private float[] mCameraTransform = new float[16];

        /** The texture the camera streams to */
        protected TextureSource mPreviewTexture = null;
        protected SurfaceTexture mPreviewSurfaceTexture = null;

        /** Map of target surface textures (one for each target context) */
        protected HashMap<EGLContext, SurfaceTexture> mTargetSurfaceTextures
            = new HashMap<EGLContext, SurfaceTexture>();

        /** Map of RenderTargets for client SurfaceTextures */
        protected HashMap<SurfaceTexture, RenderTarget> mClientRenderTargets
            = new HashMap<SurfaceTexture, RenderTarget>();

        /** Server side copy shader */
        protected ImageShader mCopyShader = null;

        @Override
        public synchronized void setupServerFrame() {
            setupPreviewTexture(mRunner.mCamera);
        }

        @Override
        public synchronized void updateServerFrame() {
            mPreviewSurfaceTexture.updateTexImage();
            distributeFrames();
        }

        @Override
        public void onUpdateCameraOrientation(int orientation) {
            super.onUpdateCameraOrientation(orientation);
            mRunner.mCamera.setDisplayOrientation(orientation);
            updateSurfaceTextureSizes();
        }

        @Override
        public synchronized void onRegisterClient(FrameClient client, EGLContext context) {
            final Set<FrameClient> clientTargets = clientsForContext(context);

            // Make sure we have texture, shader, and surfacetexture setup for this context.
            TextureSource clientTex = textureForContext(context);
            ImageShader copyShader = shaderForContext(context);
            SurfaceTexture surfTex = surfaceTextureForContext(context);

            // Listen to client-side surface texture updates
            surfTex.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    for (FrameClient clientTarget : clientTargets) {
                        clientTarget.onCameraFrameAvailable();
                    }
                }
            });
        }

        @Override
        public synchronized void grabFrame(FrameImage2D targetFrame) {
            // Get the GL objects for the receiver's context
            EGLContext clientContext = RenderTarget.currentContext();
            TextureSource clientTex = textureForContext(clientContext);
            ImageShader copyShader = shaderForContext(clientContext);
            SurfaceTexture surfTex = surfaceTextureForContext(clientContext);
            if (clientTex == null || copyShader == null || surfTex == null) {
                throw new RuntimeException("Attempting to grab camera frame from unknown "
                    + "thread: " + Thread.currentThread() + "!");
            }

            // Copy from client ST to client tex
            surfTex.updateTexImage();
            targetFrame.resize(new int[] { mOutWidth, mOutHeight });
            copyShader.process(clientTex,
                               targetFrame.lockRenderTarget(),
                               mOutWidth,
                               mOutHeight);

            targetFrame.setTimestamp(mPreviewSurfaceTexture.getTimestamp());
            targetFrame.unlock();
        }

        @Override
        public synchronized void release() {
            if (mPreviewTexture != null) {
                mPreviewTexture.release();
                mPreviewTexture = null;
            }
            if (mPreviewSurfaceTexture != null) {
                mPreviewSurfaceTexture.release();
                mPreviewSurfaceTexture = null;
            }
        }

        @Override
        protected ImageShader createClientShader() {
            return new ImageShader(mCopyShaderSource);
        }

        @Override
        protected TextureSource createClientTexture() {
            return TextureSource.newExternalTexture();
        }

        protected void distributeFrames() {
            updateTransform(getCopyShader());
            updateShaderTargetRect(getCopyShader());

            for (SurfaceTexture clientTexture : mTargetSurfaceTextures.values()) {
                RenderTarget clientTarget = renderTargetFor(clientTexture);
                clientTarget.focus();
                getCopyShader().process(mPreviewTexture,
                                        clientTarget,
                                        mOutWidth,
                                        mOutHeight);
                GLToolbox.checkGlError("distribute frames");
                clientTarget.swapBuffers();
            }
        }

        protected RenderTarget renderTargetFor(SurfaceTexture surfaceTex) {
            RenderTarget target = mClientRenderTargets.get(surfaceTex);
            if (target == null) {
                target = RenderTarget.currentTarget().forSurfaceTexture(surfaceTex);
                mClientRenderTargets.put(surfaceTex, target);
            }
            return target;
        }

        protected void setupPreviewTexture(Camera camera) {
            if (mPreviewTexture == null) {
                mPreviewTexture = TextureSource.newExternalTexture();
            }
            if (mPreviewSurfaceTexture == null) {
                mPreviewSurfaceTexture = new SurfaceTexture(mPreviewTexture.getTextureId());
                try {
                    camera.setPreviewTexture(mPreviewSurfaceTexture);
                } catch (IOException e) {
                    throw new RuntimeException("Could not bind camera surface texture: " +
                                               e.getMessage() + "!");
                }
                mPreviewSurfaceTexture.setOnFrameAvailableListener(mOnCameraFrameListener);
            }
        }

        protected ImageShader getCopyShader() {
            if (mCopyShader == null) {
                mCopyShader = new ImageShader(mCopyShaderSource);
            }
            return mCopyShader;
        }

        protected SurfaceTexture surfaceTextureForContext(EGLContext context) {
            SurfaceTexture surfTex = mTargetSurfaceTextures.get(context);
            if (surfTex == null) {
                TextureSource texture = textureForContext(context);
                if (texture != null) {
                    surfTex = new SurfaceTexture(texture.getTextureId());
                    surfTex.setDefaultBufferSize(mOutWidth, mOutHeight);
                    mTargetSurfaceTextures.put(context, surfTex);
                }
            }
            return surfTex;
        }

        protected void updateShaderTargetRect(ImageShader shader) {
            if ((mRunner.mActualFacing == FACING_FRONT) && mRunner.mFlipFront) {
                shader.setTargetRect(1f, 0f, -1f, 1f);
            } else {
                shader.setTargetRect(0f, 0f, 1f, 1f);
            }
        }

        protected synchronized void updateSurfaceTextureSizes() {
            for (SurfaceTexture clientTexture : mTargetSurfaceTextures.values()) {
                clientTexture.setDefaultBufferSize(mOutWidth, mOutHeight);
            }
        }

        protected void updateTransform(ImageShader shader) {
            mPreviewSurfaceTexture.getTransformMatrix(mCameraTransform);
            shader.setSourceTransform(mCameraTransform);
        }

        @Override
        protected void onCleanupContext(EGLContext context) {
            super.onCleanupContext(context);
            SurfaceTexture surfaceTex = mTargetSurfaceTextures.get(context);
            if (surfaceTex != null) {
                surfaceTex.release();
                mTargetSurfaceTextures.remove(context);
            }
        }

        protected SurfaceTexture.OnFrameAvailableListener mOnCameraFrameListener =
                new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                mRunner.signalNewFrame();
            }
        };
    }

    // Gingerbread (and later) back-end
    @TargetApi(9)
    private final class CamFrameHandlerGB extends CamFrameHandler  {

        private SurfaceView mSurfaceView;
        private byte[] mFrameBufferFront;
        private byte[] mFrameBufferBack;
        private boolean mWriteToBack = true;
        private float[] mTargetCoords = new float[] { 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f };
        final Object mBufferLock = new Object();

        private String mNV21ToRGBAFragment =
            "precision mediump float;\n" +
            "\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "varying vec2 v_y_texcoord;\n" +
            "varying vec2 v_vu_texcoord;\n" +
            "varying vec2 v_pixcoord;\n" +
            "\n" +
            "vec3 select(vec4 yyyy, vec4 vuvu, int s) {\n" +
            "  if (s == 0) {\n" +
            "    return vec3(yyyy.r, vuvu.g, vuvu.r);\n" +
            "  } else if (s == 1) {\n" +
            "    return vec3(yyyy.g, vuvu.g, vuvu.r);\n" +
            " } else if (s == 2) {\n" +
            "    return vec3(yyyy.b, vuvu.a, vuvu.b);\n" +
            "  } else  {\n" +
            "    return vec3(yyyy.a, vuvu.a, vuvu.b);\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "vec3 yuv2rgb(vec3 yuv) {\n" +
            "  mat4 conversion = mat4(1.0,  0.0,    1.402, -0.701,\n" +
            "                         1.0, -0.344, -0.714,  0.529,\n" +
            "                         1.0,  1.772,  0.0,   -0.886,\n" +
            "                         0, 0, 0, 0);" +
            "  return (vec4(yuv, 1.0) * conversion).rgb;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "  vec4 yyyy = texture2D(tex_sampler_0, v_y_texcoord);\n" +
            "  vec4 vuvu = texture2D(tex_sampler_0, v_vu_texcoord);\n" +
            "  int s = int(mod(floor(v_pixcoord.x), 4.0));\n" +
            "  vec3 yuv = select(yyyy, vuvu, s);\n" +
            "  vec3 rgb = yuv2rgb(yuv);\n" +
            "  gl_FragColor = vec4(rgb, 1.0);\n" +
            "}";

        private String mNV21ToRGBAVertex =
            "attribute vec4 a_position;\n" +
            "attribute vec2 a_y_texcoord;\n" +
            "attribute vec2 a_vu_texcoord;\n" +
            "attribute vec2 a_pixcoord;\n" +
            "varying vec2 v_y_texcoord;\n" +
            "varying vec2 v_vu_texcoord;\n" +
            "varying vec2 v_pixcoord;\n" +
            "void main() {\n" +
            "  gl_Position = a_position;\n" +
            "  v_y_texcoord = a_y_texcoord;\n" +
            "  v_vu_texcoord = a_vu_texcoord;\n" +
            "  v_pixcoord = a_pixcoord;\n" +
            "}\n";

        private byte[] readBuffer() {
            synchronized (mBufferLock) {
                return mWriteToBack ? mFrameBufferFront : mFrameBufferBack;
            }
        }

        private byte[] writeBuffer() {
            synchronized (mBufferLock) {
                return mWriteToBack ? mFrameBufferBack : mFrameBufferFront;
            }
        }

        private synchronized void swapBuffers() {
            synchronized (mBufferLock) {
                mWriteToBack = !mWriteToBack;
            }
        }

        private PreviewCallback mPreviewCallback = new PreviewCallback() {

            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                swapBuffers();
                camera.addCallbackBuffer(writeBuffer());
                mRunner.signalNewFrame();
            }

        };

        @Override
        public void setupServerFrame() {
            checkCameraDimensions();
            Camera camera = mRunner.mCamera;
            int bufferSize = mCameraWidth * (mCameraHeight + mCameraHeight/2);
            mFrameBufferFront = new byte[bufferSize];
            mFrameBufferBack = new byte[bufferSize];
            camera.addCallbackBuffer(writeBuffer());
            camera.setPreviewCallbackWithBuffer(mPreviewCallback);
            SurfaceView previewDisplay = getPreviewDisplay();
            if (previewDisplay != null) {
                try {
                    camera.setPreviewDisplay(previewDisplay.getHolder());
                } catch (IOException e) {
                    throw new RuntimeException("Could not start camera with given preview " +
                            "display!");
                }
            }
        }

        private void checkCameraDimensions() {
            if (mCameraWidth % 4 != 0) {
                throw new RuntimeException("Camera width must be a multiple of 4!");
            } else if (mCameraHeight % 2 != 0) {
                throw new RuntimeException("Camera height must be a multiple of 2!");
            }
        }

        @Override
        public void updateServerFrame() {
            // Server frame has been updated already, simply inform clients here.
            informClients();
        }

        @Override
        public void grabFrame(FrameImage2D targetFrame) {
            EGLContext clientContext = RenderTarget.currentContext();

            // Copy camera data to the client YUV texture
            TextureSource clientTex = textureForContext(clientContext);
            int texWidth = mCameraWidth / 4;
            int texHeight = mCameraHeight + mCameraHeight / 2;
            synchronized(mBufferLock) {    // Don't swap buffers while we are reading
                ByteBuffer pixels = ByteBuffer.wrap(readBuffer());
                clientTex.allocateWithPixels(pixels, texWidth, texHeight);
            }
            clientTex.setParameter(GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            clientTex.setParameter(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);

            // Setup the YUV-2-RGBA shader
            ImageShader transferShader = shaderForContext(clientContext);
            transferShader.setTargetCoords(mTargetCoords);
            updateShaderPixelSize(transferShader);

            // Convert pixels into target frame
            targetFrame.resize(new int[] { mOutWidth, mOutHeight });
            transferShader.process(clientTex,
                    targetFrame.lockRenderTarget(),
                    mOutWidth,
                    mOutHeight);
            targetFrame.unlock();
        }

        @Override
        public void onUpdateCameraOrientation(int orientation) {
            super.onUpdateCameraOrientation(orientation);
            if ((mRunner.mActualFacing == FACING_FRONT) && mRunner.mFlipFront) {
                switch (orientation) {
                    case 0:
                        mTargetCoords = new float[] { 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f };
                        break;
                    case 90:
                        mTargetCoords = new float[] { 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f };
                        break;
                    case 180:
                        mTargetCoords = new float[] { 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f };
                        break;
                    case 270:
                        mTargetCoords = new float[] { 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f };
                        break;
                }
            } else {
                switch (orientation) {
                    case 0:
                        mTargetCoords = new float[] { 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f };
                        break;
                    case 90:
                        mTargetCoords = new float[] { 1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f };
                        break;
                    case 180:
                        mTargetCoords = new float[] { 1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f };
                        break;
                    case 270:
                        mTargetCoords = new float[] { 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f };
                        break;
                }
            }
        }

        @Override
        public void release() {
            mFrameBufferBack = null;
            mFrameBufferFront = null;
        }

        @Override
        public boolean isFrontMirrored() {
            return false;
        }

        @Override
        protected ImageShader createClientShader() {
            ImageShader shader = new ImageShader(mNV21ToRGBAVertex, mNV21ToRGBAFragment);
            // TODO: Make this a VBO
            float[] yCoords = new float[] {
                    0f, 0f,
                    1f, 0f,
                    0f, 2f / 3f,
                    1f, 2f / 3f };
            float[] uvCoords = new float[] {
                    0f, 2f / 3f,
                    1f, 2f / 3f,
                    0f, 1f,
                    1f, 1f };
            shader.setAttributeValues("a_y_texcoord", yCoords, 2);
            shader.setAttributeValues("a_vu_texcoord", uvCoords, 2);
            return shader;
        }

        @Override
        protected TextureSource createClientTexture() {
            TextureSource texture = TextureSource.newTexture();
            texture.setParameter(GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            texture.setParameter(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            return texture;
        }

        private void updateShaderPixelSize(ImageShader shader) {
            float[] pixCoords = new float[] {
                    0f, 0f,
                    mCameraWidth, 0f,
                    0f, mCameraHeight,
                    mCameraWidth, mCameraHeight };
            shader.setAttributeValues("a_pixcoord", pixCoords, 2);
        }

        private SurfaceView getPreviewDisplay() {
            if (mSurfaceView == null) {
                mSurfaceView = mRunner.getContext().getDummySurfaceView();
            }
            return mSurfaceView;
        }

        private void informClients() {
            synchronized (mClients) {
                for (FrameClient client : mClients) {
                    client.onCameraFrameAvailable();
                }
            }
        }
    }

    private static class State {
        public static final int STATE_RUNNING = 1;
        public static final int STATE_STOPPED = 2;
        public static final int STATE_HALTED = 3;

        private AtomicInteger mCurrent = new AtomicInteger(STATE_STOPPED);

        public int current() {
            return mCurrent.get();
        }

        public void set(int newState) {
            mCurrent.set(newState);
        }
    }

    private static class Event {
        public static final int START = 1;
        public static final int FRAME = 2;
        public static final int STOP = 3;
        public static final int HALT = 4;
        public static final int RESTART = 5;
        public static final int UPDATE = 6;
        public static final int TEARDOWN = 7;

        public int code;

        public Event(int code) {
            this.code = code;
        }
    }

    private final class CameraRunnable implements Runnable {

        /** On slower devices the event queue can easily fill up. We bound the queue to this. */
        private final static int MAX_EVENTS = 32;

        /** The runner's state */
        private State mState = new State();

        /** The CameraRunner's event queue */
        private LinkedBlockingQueue<Event> mEventQueue = new LinkedBlockingQueue<Event>(MAX_EVENTS);

        /** The requested FPS */
        private int mRequestedFramesPerSec = 30;

        /** The actual FPS */
        private int mActualFramesPerSec = 0;

        /** The requested preview width and height */
        private int mRequestedPreviewWidth = 640;
        private int mRequestedPreviewHeight = 480;

        /** The requested picture width and height */
        private int mRequestedPictureWidth = 640;
        private int mRequestedPictureHeight = 480;

        /** The actual camera width and height */
        private int[] mActualDims = null;

        /** The requested facing */
        private int mRequestedFacing = FACING_DONTCARE;

        /** The actual facing */
        private int mActualFacing = FACING_DONTCARE;

        /** Whether to horizontally flip the front facing camera */
        private boolean mFlipFront = true;

        /** The display the camera streamer is bound to. */
        private Display mDisplay = null;

        /** The camera and screen orientation. */
        private int mCamOrientation = 0;
        private int mOrientation = -1;

        /** The camera rotation (used for capture). */
        private int mCamRotation = 0;

        /** The camera flash mode */
        private String mFlashMode = Camera.Parameters.FLASH_MODE_OFF;

        /** The camera object */
        private Camera mCamera = null;

        private MediaRecorder mRecorder = null;

        /** The ID of the currently used camera */
        int mCamId = 0;

        /** The platform-dependent camera frame handler. */
        private CamFrameHandler mCamFrameHandler = null;

        /** The set of camera listeners. */
        private Set<CameraListener> mCamListeners = new HashSet<CameraListener>();

        private ReentrantLock mCameraReadyLock = new ReentrantLock(true);
        // mCameraReady condition is used when waiting for the camera getting ready.
        private Condition mCameraReady = mCameraReadyLock.newCondition();
        // external camera lock used to provide the capability of external camera access.
        private ExternalCameraLock mExternalCameraLock = new ExternalCameraLock();

        private RenderTarget mRenderTarget;
        private MffContext mContext;

        /**
         *  This provides the capability of locking and unlocking from different threads.
         *  The thread will wait until the lock state is idle. Any thread can wake up
         *  a waiting thread by calling unlock (i.e. signal), provided that unlock
         *  are called using the same context when lock was called. Using context prevents
         *  from rogue usage of unlock.
         */
        private class ExternalCameraLock {
            public static final int IDLE = 0;
            public static final int IN_USE = 1;
            private int mLockState = IDLE;
            private Object mLockContext;
            private final ReentrantLock mLock = new ReentrantLock(true);
            private final Condition mInUseLockCondition= mLock.newCondition();

            public boolean lock(Object context) {
                if (context == null) {
                    throw new RuntimeException("Null context when locking");
                }
                mLock.lock();
                if (mLockState == IN_USE) {
                    try {
                        mInUseLockCondition.await();
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                mLockState = IN_USE;
                mLockContext = context;
                mLock.unlock();
                return true;
            }

            public void unlock(Object context) {
                mLock.lock();
                if (mLockState != IN_USE) {
                    throw new RuntimeException("Not in IN_USE state");
                }
                if (context != mLockContext) {
                    throw new RuntimeException("Lock is not owned by this context");
                }
                mLockState = IDLE;
                mLockContext = null;
                mInUseLockCondition.signal();
                mLock.unlock();
            }
        }

        public CameraRunnable(MffContext context) {
            mContext = context;
            createCamFrameHandler();
            mCamFrameHandler.initWithRunner(this);
            launchThread();
        }

        public MffContext getContext() {
            return mContext;
        }

        public void loop() {
            while (true) {
                try {
                    Event event = nextEvent();
                    if (event == null) continue;
                    switch (event.code) {
                        case Event.START:
                            onStart();
                            break;
                        case Event.STOP:
                            onStop();
                            break;
                        case Event.FRAME:
                            onFrame();
                            break;
                        case Event.HALT:
                            onHalt();
                            break;
                        case Event.RESTART:
                            onRestart();
                            break;
                        case Event.UPDATE:
                            onUpdate();
                            break;
                        case Event.TEARDOWN:
                            onTearDown();
                            break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void run() {
            loop();
        }

        public void signalNewFrame() {
            pushEvent(Event.FRAME, false);
        }

        public void pushEvent(int eventId, boolean required) {
            try {
                if (required) {
                    mEventQueue.put(new Event(eventId));
                } else {
                    mEventQueue.offer(new Event(eventId));
                }
            } catch (InterruptedException e) {
                // We should never get here (as we do not limit capacity in the queue), but if
                // we do, we log an error.
                Log.e("CameraStreamer", "Dropping event " + eventId + "!");
            }
        }

        public void launchThread() {
            Thread cameraThread = new Thread(this);
            cameraThread.start();
        }

        @Deprecated
        public Camera getCamera() {
            synchronized (mState) {
                return mCamera;
            }
        }

        public Camera lockCamera(Object context) {
            mExternalCameraLock.lock(context);
            /**
             * since lockCamera can happen right after closeCamera,
             * the camera handle can be null, wait until valid handle
             * is acquired.
             */
            while (mCamera == null) {
                mExternalCameraLock.unlock(context);
                mCameraReadyLock.lock();
                try {
                    mCameraReady.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Condition interrupted", e);
                }
                mCameraReadyLock.unlock();
                mExternalCameraLock.lock(context);
            }
            return mCamera;
        }

        public void unlockCamera(Object context) {
            mExternalCameraLock.unlock(context);
        }

        public int getCurrentCameraId() {
            synchronized (mState) {
                return mCamId;
            }
        }

        public boolean isRunning() {
            return mState.current() != State.STATE_STOPPED;
        }

        public void addListener(CameraListener listener) {
            synchronized (mCamListeners) {
                mCamListeners.add(listener);
            }
        }

        public void removeListener(CameraListener listener) {
            synchronized (mCamListeners) {
                mCamListeners.remove(listener);
            }
        }

        public synchronized void bindToDisplay(Display display) {
            mDisplay = display;
        }

        public synchronized void setDesiredPreviewSize(int width, int height) {
            if (width != mRequestedPreviewWidth || height != mRequestedPreviewHeight) {
                mRequestedPreviewWidth = width;
                mRequestedPreviewHeight = height;
                onParamsUpdated();
            }
        }

        public synchronized void setDesiredPictureSize(int width, int height) {
            if (width != mRequestedPictureWidth || height != mRequestedPictureHeight) {
                mRequestedPictureWidth = width;
                mRequestedPictureHeight = height;
                onParamsUpdated();
            }
        }

        public synchronized void setDesiredFrameRate(int fps) {
            if (fps != mRequestedFramesPerSec) {
                mRequestedFramesPerSec = fps;
                onParamsUpdated();
            }
        }

        public synchronized void setFacing(int facing) {
            if (facing != mRequestedFacing) {
                switch (facing) {
                    case FACING_DONTCARE:
                    case FACING_FRONT:
                    case FACING_BACK:
                        mRequestedFacing = facing;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown facing value '" + facing
                            + "' passed to setFacing!");
                }
                onParamsUpdated();
            }
        }

        public synchronized void setFlipFrontCamera(boolean flipFront) {
            if (mFlipFront != flipFront) {
                mFlipFront = flipFront;
            }
        }

        public synchronized void setFlashMode(String flashMode) {
            if (!flashMode.equals(mFlashMode)) {
                mFlashMode = flashMode;
                onParamsUpdated();
            }
        }

        public synchronized int getCameraFacing() {
            return mActualFacing;
        }

        public synchronized int getCameraRotation() {
            return mCamRotation;
        }

        public synchronized boolean supportsHardwareFaceDetection() {
            //return mCamFrameHandler.supportsHardwareFaceDetection();
            // TODO
            return true;
        }

        public synchronized int getCameraWidth() {
            return (mActualDims != null) ? mActualDims[0] : 0;
        }

        public synchronized int getCameraHeight() {
            return (mActualDims != null) ? mActualDims[1] : 0;
        }

        public synchronized int getCameraFrameRate() {
            return mActualFramesPerSec;
        }

        public synchronized String getFlashMode() {
            return mCamera.getParameters().getFlashMode();
        }

        public synchronized boolean canStart() {
            // If we can get a camera id without error we should be able to start.
            try {
                getCameraId();
            } catch (RuntimeException e) {
                return false;
            }
            return true;
        }

        public boolean grabFrame(FrameImage2D targetFrame) {
            // Make sure we stay in state running while we are grabbing the frame.
            synchronized (mState) {
                if (mState.current() != State.STATE_RUNNING) {
                    return false;
                }
                // we may not have the camera ready, this might happen when in the middle
                // of switching camera.
                if (mCamera == null) {
                    return false;
                }
                mCamFrameHandler.grabFrame(targetFrame);
                return true;
            }
        }

        public CamFrameHandler getCamFrameHandler() {
            return mCamFrameHandler;
        }

        private void onParamsUpdated() {
            pushEvent(Event.UPDATE, true);
        }

        private Event nextEvent() {
            try {
                return mEventQueue.take();
            } catch (InterruptedException e) {
                // Ignore and keep going.
                Log.w("GraphRunner", "Event queue processing was interrupted.");
                return null;
            }
        }

        private void onStart() {
            if (mState.current() == State.STATE_STOPPED) {
                mState.set(State.STATE_RUNNING);
                getRenderTarget().focus();
                openCamera();
            }
        }

        private void onStop() {
            if (mState.current() == State.STATE_RUNNING) {
                closeCamera();
                RenderTarget.focusNone();
            }
            // Set state to stop (halted becomes stopped).
            mState.set(State.STATE_STOPPED);
        }

        private void onHalt() {
            // Only halt if running. Stopped overrides halt.
            if (mState.current() == State.STATE_RUNNING) {
                closeCamera();
                RenderTarget.focusNone();
                mState.set(State.STATE_HALTED);
            }
        }

        private void onRestart() {
            // Only restart if halted
            if (mState.current() == State.STATE_HALTED) {
                mState.set(State.STATE_RUNNING);
                getRenderTarget().focus();
                openCamera();
            }
        }

        private void onUpdate() {
            if (mState.current() == State.STATE_RUNNING) {
                pushEvent(Event.STOP, true);
                pushEvent(Event.START, true);
            }
        }
        private void onFrame() {
            if (mState.current() == State.STATE_RUNNING) {
                updateRotation();
                mCamFrameHandler.updateServerFrame();
            }
        }

        private void onTearDown() {
            if (mState.current() == State.STATE_STOPPED) {
                // Remove all listeners. This will release their resources
                for (CameraListener listener : mCamListeners) {
                    removeListener(listener);
                }
                mCamListeners.clear();
            } else {
                Log.e("CameraStreamer", "Could not tear-down CameraStreamer as camera still "
                        + "seems to be running!");
            }
        }

        private void createCamFrameHandler() {
            // TODO: For now we simply assert that OpenGL is supported. Later on, we should add
            // a CamFrameHandler that does not depend on OpenGL.
            getContext().assertOpenGLSupported();
            if (VERSION.SDK_INT >= 16) {
                mCamFrameHandler = new CamFrameHandlerJB();
            } else if (VERSION.SDK_INT >= 15) {
                mCamFrameHandler = new CamFrameHandlerICS();
            } else {
                mCamFrameHandler = new CamFrameHandlerGB();
            }
        }

        private void updateRotation() {
            if (mDisplay != null) {
                updateDisplayRotation(mDisplay.getRotation());
            }
        }

        private synchronized void updateDisplayRotation(int rotation) {
            switch (rotation) {
                case Surface.ROTATION_0:
                    onUpdateOrientation(0);
                    break;
                case Surface.ROTATION_90:
                    onUpdateOrientation(90);
                    break;
                case Surface.ROTATION_180:
                    onUpdateOrientation(180);
                    break;
                case Surface.ROTATION_270:
                    onUpdateOrientation(270);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported display rotation constant! Use "
                        + "one of the Surface.ROTATION_ constants!");
            }
        }

        private RenderTarget getRenderTarget() {
            if (mRenderTarget == null) {
                mRenderTarget = RenderTarget.newTarget(1, 1);
            }
            return mRenderTarget;
        }

        private void updateCamera() {
            synchronized (mState) {
                mCamId = getCameraId();
                updateCameraOrientation(mCamId);
                mCamera = Camera.open(mCamId);
                initCameraParameters();
            }
        }

        private void updateCameraOrientation(int camId) {
            CameraInfo cameraInfo = new CameraInfo();
            Camera.getCameraInfo(camId, cameraInfo);
            mCamOrientation = cameraInfo.orientation;
            mOrientation = -1;  // Forces recalculation to match display
            mActualFacing = (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT)
                ? FACING_FRONT
                : FACING_BACK;
        }

        private int getCameraId() {
            int camCount = Camera.getNumberOfCameras();
            if (camCount == 0) {
                throw new RuntimeException("Device does not have any cameras!");
            } else if (mRequestedFacing == FACING_DONTCARE) {
                // Simply return first camera if mRequestedFacing is don't care
                return 0;
            }

            // Attempt to find requested camera
            boolean useFrontCam = (mRequestedFacing == FACING_FRONT);
            CameraInfo cameraInfo = new CameraInfo();
            for (int i = 0; i < camCount; ++i) {
                Camera.getCameraInfo(i, cameraInfo);
                if ((cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) == useFrontCam) {
                    return i;
                }
            }
            throw new RuntimeException("Could not find a camera facing (" + mRequestedFacing
                    + ")!");
        }

        private void initCameraParameters() {
            Camera.Parameters params = mCamera.getParameters();

            // Find closest preview size
            mActualDims =
                findClosestPreviewSize(mRequestedPreviewWidth, mRequestedPreviewHeight, params);
            mCamFrameHandler.setCameraSize(mActualDims[0], mActualDims[1]);
            params.setPreviewSize(mActualDims[0], mActualDims[1]);
            // Find closest picture size
            int[] dims =
                findClosestPictureSize(mRequestedPictureWidth, mRequestedPictureHeight, params);
            params.setPictureSize(dims[0], dims[1]);

            // Find closest FPS
            int closestRange[] = findClosestFpsRange(mRequestedFramesPerSec, params);
            params.setPreviewFpsRange(closestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                                      closestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);

            // Set flash mode (if supported)
            if (params.getFlashMode() != null) {
                params.setFlashMode(mFlashMode);
            }

            mCamera.setParameters(params);
        }

        private int[] findClosestPreviewSize(int width, int height, Camera.Parameters parameters) {
            List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
            return findClosestSizeFromList(width, height, previewSizes);
        }

        private int[] findClosestPictureSize(int width, int height, Camera.Parameters parameters) {
            List<Camera.Size> pictureSizes = parameters.getSupportedPictureSizes();
            return findClosestSizeFromList(width, height, pictureSizes);
        }

        private int[] findClosestSizeFromList(int width, int height, List<Camera.Size> sizes) {
            int closestWidth = -1;
            int closestHeight = -1;
            int smallestWidth = sizes.get(0).width;
            int smallestHeight =  sizes.get(0).height;
            for (Camera.Size size : sizes) {
                // Best match defined as not being larger in either dimension than
                // the requested size, but as close as possible. The below isn't a
                // stable selection (reording the size list can give different
                // results), but since this is a fallback nicety, that's acceptable.
                if ( size.width <= width &&
                     size.height <= height &&
                     size.width >= closestWidth &&
                     size.height >= closestHeight) {
                    closestWidth = size.width;
                    closestHeight = size.height;
                }
                if ( size.width < smallestWidth &&
                     size.height < smallestHeight) {
                    smallestWidth = size.width;
                    smallestHeight = size.height;
                }
            }
            if (closestWidth == -1) {
                // Requested size is smaller than any listed size; match with smallest possible
                closestWidth = smallestWidth;
                closestHeight = smallestHeight;
            }
            int[] closestSize = {closestWidth, closestHeight};
            return closestSize;
        }

        private int[] findClosestFpsRange(int fps, Camera.Parameters params) {
            List<int[]> supportedFpsRanges = params.getSupportedPreviewFpsRange();
            int[] closestRange = supportedFpsRanges.get(0);
            int fpsk = fps * 1000;
            int minDiff = 1000000;
            for (int[] range : supportedFpsRanges) {
                int low = range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
                int high = range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
                if (low <= fpsk && high >= fpsk) {
                    int diff = (fpsk - low) + (high - fpsk);
                    if (diff < minDiff) {
                        closestRange = range;
                        minDiff = diff;
                    }
                }
            }
            mActualFramesPerSec = closestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] / 1000;
            return closestRange;
        }

        private void onUpdateOrientation(int orientation) {
            // First we calculate the camera rotation.
            int rotation = (mActualFacing == FACING_FRONT)
                    ? (mCamOrientation + orientation) % 360
                    : (mCamOrientation - orientation + 360) % 360;
            if (rotation != mCamRotation) {
                synchronized (this) {
                    mCamRotation = rotation;
                }
            }

            // We compensate for mirroring in the orientation. This differs from the rotation,
            // where we are invariant to mirroring.
            int fixedOrientation = rotation;
            if (mActualFacing == FACING_FRONT && mCamFrameHandler.isFrontMirrored()) {
                fixedOrientation = (360 - rotation) % 360;  // compensate the mirror
            }
            if (mOrientation != fixedOrientation) {
                mOrientation = fixedOrientation;
                mCamFrameHandler.onUpdateCameraOrientation(mOrientation);
            }
        }

        private void openCamera() {
            // Acquire lock for camera
            try {
                if (!mCameraLock.tryLock(MAX_CAMERA_WAIT_TIME, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timed out while waiting to acquire camera!");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while waiting to acquire camera!");
            }

            // Make sure external entities are not holding camera. We need to hold the lock until
            // the preview is started again.
            Object lockContext = new Object();
            mExternalCameraLock.lock(lockContext);

            // Need to synchronize this as many of the member values are modified during setup.
            synchronized (this) {
                updateCamera();
                updateRotation();
                mCamFrameHandler.setupServerFrame();
            }

            mCamera.startPreview();

            // Inform listeners
            synchronized (mCamListeners) {
                for (CameraListener listener : mCamListeners) {
                    listener.onCameraOpened(CameraStreamer.this);
                }
            }
            mExternalCameraLock.unlock(lockContext);
            // New camera started
            mCameraReadyLock.lock();
            mCameraReady.signal();
            mCameraReadyLock.unlock();
        }

        /**
         * Creates an instance of MediaRecorder to be used for the streamer.
         * User should call the functions in the following sequence:<p>
         *   {@link #createRecorder}<p>
         *   {@link #startRecording}<p>
         *   {@link #stopRecording}<p>
         *   {@link #releaseRecorder}<p>
         * @param outputPath the output video path for the recorder
         * @param profile the recording {@link CamcorderProfile} which has parameters indicating
         *  the resolution, quality etc.
         */
        public void createRecorder(String outputPath, CamcorderProfile profile) {
            lockCamera(this);
            mCamera.unlock();
            if (mRecorder != null) {
                mRecorder.release();
            }
            mRecorder = new MediaRecorder();
            mRecorder.setCamera(mCamera);
            mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mRecorder.setProfile(profile);
            mRecorder.setOutputFile(outputPath);
            try {
                mRecorder.prepare();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Starts recording video using the created MediaRecorder object
         */
        public void startRecording() {
            if (mRecorder == null) {
                throw new RuntimeException("No recorder created");
            }
            mRecorder.start();
        }

        /**
         * Stops recording video
         */
        public void stopRecording() {
            if (mRecorder == null) {
                throw new RuntimeException("No recorder created");
            }
            mRecorder.stop();
        }

        /**
         * Release the resources held by the MediaRecorder, call this after done recording.
         */
        public void releaseRecorder() {
            if (mRecorder == null) {
                throw new RuntimeException("No recorder created");
            }
            mRecorder.release();
            mRecorder = null;
            mCamera.lock();
            unlockCamera(this);
        }

        private void closeCamera() {
            Object lockContext = new Object();
            mExternalCameraLock.lock(lockContext);
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
            mCameraLock.unlock();
            mCamFrameHandler.release();
            mExternalCameraLock.unlock(lockContext);
            // Inform listeners
            synchronized (mCamListeners) {
                for (CameraListener listener : mCamListeners) {
                    listener.onCameraClosed(CameraStreamer.this);
                }
            }
        }

    }

    /**
     * The frame-client callback interface.
     * FrameClients, that wish to receive Frames from the camera must implement this callback
     * method.
     * Note, that this method is called on the Camera server thread. However, the
     * {@code getLatestFrame()} method must be called from the client thread.
     */
    public static interface FrameClient {
        public void onCameraFrameAvailable();
    }

    /**
     * The CameraListener callback interface.
     * This interface allows observers to monitor the CameraStreamer and respond to stream open
     * and close events.
     */
    public static interface CameraListener {
        /**
         * Called when the camera is opened and begins producing frames.
         * This is also called when settings have changed that caused the camera to be reopened.
         */
        public void onCameraOpened(CameraStreamer camera);

        /**
         * Called when the camera is closed and stops producing frames.
         */
        public void onCameraClosed(CameraStreamer camera);
    }

    /**
     * Manually update the display rotation.
     * You do not need to call this, if the camera is bound to a display, or your app does not
     * support multiple orientations.
     */
    public void updateDisplayRotation(int rotation) {
        mCameraRunner.updateDisplayRotation(rotation);
    }

    /**
     * Bind the camera to your Activity's display.
     * Use this, if your Activity supports multiple display orientation, and you would like the
     * camera to update accordingly when the orientation is changed.
     */
    public void bindToDisplay(Display display) {
        mCameraRunner.bindToDisplay(display);
    }

    /**
     * Sets the desired preview size.
     * Note that the actual width and height may vary.
     *
     * @param width The desired width of the preview camera stream.
     * @param height The desired height of the preview camera stream.
     */
    public void setDesiredPreviewSize(int width, int height) {
        mCameraRunner.setDesiredPreviewSize(width, height);
    }

    /**
     * Sets the desired picture size.
     * Note that the actual width and height may vary.
     *
     * @param width The desired picture width.
     * @param height The desired picture height.
     */
    public void setDesiredPictureSize(int width, int height) {
        mCameraRunner.setDesiredPictureSize(width, height);
    }

    /**
     * Sets the desired camera frame-rate.
     * Note, that the actual frame-rate may vary.
     *
     * @param fps The desired FPS.
     */
    public void setDesiredFrameRate(int fps) {
        mCameraRunner.setDesiredFrameRate(fps);
    }

    /**
     * Sets the camera facing direction.
     *
     * Specify {@code FACING_DONTCARE} (default) if you would like the CameraStreamer to choose
     * the direction. When specifying any other direction be sure to first check whether the
     * device supports the desired facing.
     *
     * @param facing The desired camera facing direction.
     */
    public void setFacing(int facing) {
        mCameraRunner.setFacing(facing);
    }

    /**
     * Set whether to flip the camera image horizontally when using the front facing camera.
     */
    public void setFlipFrontCamera(boolean flipFront) {
        mCameraRunner.setFlipFrontCamera(flipFront);
    }

    /**
     * Sets the camera flash mode.
     *
     * This must be one of the String constants defined in the Camera.Parameters class.
     *
     * @param flashMode A String constant specifying the flash mode.
     */
    public void setFlashMode(String flashMode) {
        mCameraRunner.setFlashMode(flashMode);
    }

    /**
     * Returns the current flash mode.
     *
     * This returns the currently running camera's flash-mode, or NULL if flash modes are not
     * supported on that camera.
     *
     * @return The flash mode String, or NULL if flash modes are not supported.
     */
    public String getFlashMode() {
        return mCameraRunner.getFlashMode();
    }

    /**
     * Get the actual camera facing.
     * Returns 0 if actual facing is not yet known.
     */
    public int getCameraFacing() {
        return mCameraRunner.getCameraFacing();
    }

    /**
     * Get the current camera rotation.
     *
     * Use this rotation if you want to snap pictures from the camera and need to rotate the
     * picture to be up-right.
     *
     * @return the current camera rotation.
     */
    public int getCameraRotation() {
        return mCameraRunner.getCameraRotation();
    }

    /**
     * Specifies whether or not the camera supports hardware face detection.
     * @return true, if the camera supports hardware face detection.
     */
    public boolean supportsHardwareFaceDetection() {
        return mCameraRunner.supportsHardwareFaceDetection();
    }

    /**
     * Returns the camera facing that is chosen when DONT_CARE is specified.
     * Returns 0 if neither a front nor back camera could be found.
     */
    public static int getDefaultFacing() {
        int camCount = Camera.getNumberOfCameras();
        if (camCount == 0) {
            return 0;
        } else {
            CameraInfo cameraInfo = new CameraInfo();
            Camera.getCameraInfo(0, cameraInfo);
            return (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT)
                ? FACING_FRONT
                : FACING_BACK;
        }
    }

    /**
     * Get the actual camera width.
     * Returns 0 if actual width is not yet known.
     */
    public int getCameraWidth() {
        return mCameraRunner.getCameraWidth();
    }

    /**
     * Get the actual camera height.
     * Returns 0 if actual height is not yet known.
     */
    public int getCameraHeight() {
        return mCameraRunner.getCameraHeight();
    }

    /**
     * Get the actual camera frame-rate.
     * Returns 0 if actual frame-rate is not yet known.
     */
    public int getCameraFrameRate() {
        return mCameraRunner.getCameraFrameRate();
    }

    /**
     * Returns true if the camera can be started at this point.
     */
    public boolean canStart() {
        return mCameraRunner.canStart();
    }

    /**
     * Returns true if the camera is currently running.
     */
    public boolean isRunning() {
        return mCameraRunner.isRunning();
    }

    /**
     * Starts the camera.
     */
    public void start() {
        mCameraRunner.pushEvent(Event.START, true);
    }

    /**
     * Stops the camera.
     */
    public void stop() {
        mCameraRunner.pushEvent(Event.STOP, true);
    }

    /**
     * Stops the camera and waits until it is completely closed. Generally, this should not be
     * called in the UI thread, but may be necessary if you need the camera to be closed before
     * performing subsequent steps.
     */
    public void stopAndWait() {
        mCameraRunner.pushEvent(Event.STOP, true);
        try {
            if (!mCameraLock.tryLock(MAX_CAMERA_WAIT_TIME, TimeUnit.SECONDS)) {
                Log.w("CameraStreamer", "Time-out waiting for camera to close!");
            }
        } catch (InterruptedException e) {
            Log.w("CameraStreamer", "Interrupted while waiting for camera to close!");
        }
        mCameraLock.unlock();
    }

    /**
     * Registers a listener to handle camera state changes.
     */
    public void addListener(CameraListener listener) {
        mCameraRunner.addListener(listener);
    }

    /**
     * Unregisters a listener to handle camera state changes.
     */
    public void removeListener(CameraListener listener) {
        mCameraRunner.removeListener(listener);
    }

    /**
     * Registers the frame-client with the camera.
     * This MUST be called from the client thread!
     */
    public void registerClient(FrameClient client) {
        mCameraRunner.getCamFrameHandler().registerClient(client);
    }

    /**
     * Unregisters the frame-client with the camera.
     * This MUST be called from the client thread!
     */
    public void unregisterClient(FrameClient client) {
        mCameraRunner.getCamFrameHandler().unregisterClient(client);
    }

    /**
     * Gets the latest camera frame for the client.
     *
     * This must be called from the same thread as the {@link #registerClient(FrameClient)} call!
     * The frame passed in will be resized by the camera streamer to fit the camera frame.
     * Returns false if the frame could not be grabbed. This may happen if the camera has been
     * closed in the meantime, and its resources let go.
     *
     * @return true, if the frame was grabbed successfully.
     */
    public boolean getLatestFrame(FrameImage2D targetFrame) {
        return mCameraRunner.grabFrame(targetFrame);
    }

    /**
     * Expose the underlying android.hardware.Camera object.
     * Use the returned object with care: some camera functions may break the functionality
     * of CameraStreamer.
     * @return the Camera object.
     */
    @Deprecated
    public Camera getCamera() {
        return mCameraRunner.getCamera();
    }

    /**
     * Obtain access to the underlying android.hardware.Camera object.
     * This grants temporary access to the internal Camera handle. Once you are done using the
     * handle you must call {@link #unlockCamera(Object)}. While you are holding the Camera,
     * it will not be modified or released by the CameraStreamer. The Camera object return is
     * guaranteed to have the preview running.
     *
     * The CameraStreamer does not account for changes you make to the Camera. That is, if you
     * change the Camera unexpectedly this may cause unintended behavior by the streamer.
     *
     * Note that the returned object may be null. This can happen when the CameraStreamer is not
     * running, or is just transitioning to another Camera, such as during a switch from front to
     * back Camera.
     * @param context an object used as a context for locking and unlocking. lockCamera and
     *   unlockCamera should use the same context object.
     * @return The Camera object.
     */
    public Camera lockCamera(Object context) {
        return mCameraRunner.lockCamera(context);
    }

    /**
     * Release the acquire Camera object.
     * @param context the context object that used when lockCamera is called.
     */
    public void unlockCamera(Object context) {
        mCameraRunner.unlockCamera(context);
    }

    /**
     * Creates an instance of MediaRecorder to be used for the streamer.
     * User should call the functions in the following sequence:<p>
     *   {@link #createRecorder}<p>
     *   {@link #startRecording}<p>
     *   {@link #stopRecording}<p>
     *   {@link #releaseRecorder}<p>
     * @param path the output video path for the recorder
     * @param profile the recording {@link CamcorderProfile} which has parameters indicating
     *  the resolution, quality etc.
     */
    public void createRecorder(String path, CamcorderProfile profile) {
        mCameraRunner.createRecorder(path, profile);
    }

    public void releaseRecorder() {
        mCameraRunner.releaseRecorder();
    }

    public void startRecording() {
        mCameraRunner.startRecording();
    }

    public void stopRecording() {
        mCameraRunner.stopRecording();
    }

    /**
     * Retrieve the ID of the currently used camera.
     * @return the ID of the currently used camera.
     */
    public int getCameraId() {
        return mCameraRunner.getCurrentCameraId();
    }

    /**
     * @return The number of cameras available for streaming on this device.
     */
    public static int getNumberOfCameras() {
        // Currently, this is just the number of cameras that are available on the device.
        return Camera.getNumberOfCameras();
    }

    CameraStreamer(MffContext context) {
        mCameraRunner = new CameraRunnable(context);
    }

    /** Halt is like stop, but may be resumed using restart(). */
    void halt() {
        mCameraRunner.pushEvent(Event.HALT, true);
    }

    /** Restart starts the camera only if previously halted. */
    void restart() {
        mCameraRunner.pushEvent(Event.RESTART, true);
    }

    static boolean requireDummySurfaceView() {
        return VERSION.SDK_INT < 15;
    }

    void tearDown() {
        mCameraRunner.pushEvent(Event.TEARDOWN, true);
    }
}

